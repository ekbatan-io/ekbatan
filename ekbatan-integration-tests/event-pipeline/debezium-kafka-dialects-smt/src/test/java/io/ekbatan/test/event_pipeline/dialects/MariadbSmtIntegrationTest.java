package io.ekbatan.test.event_pipeline.dialects;

import static io.ekbatan.test.event_pipeline.dialects.DialectPipeline.CONTAINER_DESCRIPTORS_DIR;
import static io.ekbatan.test.event_pipeline.dialects.DialectPipeline.CONTAINER_PLUGIN_DIR;
import static io.ekbatan.test.event_pipeline.dialects.DialectPipeline.EVENT_TYPE;
import static io.ekbatan.test.event_pipeline.dialects.DialectPipeline.INIT_SQL;
import static io.ekbatan.test.event_pipeline.dialects.DialectPipeline.NAMESPACE;
import static io.ekbatan.test.event_pipeline.dialects.DialectPipeline.TOPIC_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;

import io.debezium.testing.testcontainers.ConnectorConfiguration;
import io.debezium.testing.testcontainers.DebeziumContainer;
import io.ekbatan.events.streaming.actionevent.protobuf.ActionEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The MariaDB half of the encoding pipeline, end to end against real Debezium.
 *
 * <p>Like MySQL, MariaDB has no boolean type, so {@code delivered} arrives as an INT16. Unlike
 * MySQL, {@code EventEntityRepository} binds {@code id} and {@code action_id} as <em>native</em>
 * {@code UUID} columns, which MariaDB stores as {@code BINARY(16)}.
 *
 * <p>That last point was the open question this test was built to settle, and the answer was not
 * the expected one: Debezium 3.5 renders a native {@code UUID} column as a UUID <em>string</em>,
 * not as bytes. So the SMT's binary-to-UUID branch is not on the MariaDB path at all, and MariaDB
 * takes the same route as MySQL. The assertion below pins the measured behaviour rather than the
 * assumed one.
 */
class MariadbSmtIntegrationTest {

    private static final Path SMT_JAR = Path.of(System.getProperty("smt.plugin.jar"));
    private static final Path ACTION_EVENT_DESCRIPTOR = Path.of(System.getProperty("smt.action.event.descriptor"));

    private static final Network NETWORK = Network.newNetwork();

    private static final MariaDBContainer DB = new MariaDBContainer("mariadb:11.8")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withCopyToContainer(Transferable.of(INIT_SQL), "/docker-entrypoint-initdb.d/init.sql")
            .withEnv("TZ", "UTC")
            // MariaDB does not enable the binary log by default; Debezium cannot stream without it.
            .withCommand("--log-bin=mariadb-bin", "--binlog-format=ROW", "--server-id=1", "--log-basename=mariadb")
            .withNetwork(NETWORK)
            .withNetworkAliases("mariadb");

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.0")
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka")
            .withListener("kafka:19092");

    private static DebeziumContainer debezium;
    private static ActionEvent event;

    @BeforeAll
    static void setUp() throws Exception {
        var descriptors = Files.createTempDirectory("ekbatan-mariadb-smt");
        var payloadDescriptor = DialectPipeline.writePayloadDescriptor(descriptors);

        debezium = new DebeziumContainer("quay.io/debezium/connect:3.7.0.Final")
                .withNetwork(NETWORK)
                .withEnv("BOOTSTRAP_SERVERS", "kafka:19092")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(SMT_JAR), CONTAINER_PLUGIN_DIR + "/" + SMT_JAR.getFileName())
                .withCopyFileToContainer(
                        MountableFile.forHostPath(ACTION_EVENT_DESCRIPTOR),
                        CONTAINER_DESCRIPTORS_DIR + "/ActionEvent.desc")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(payloadDescriptor), CONTAINER_DESCRIPTORS_DIR + "/payload.desc")
                .dependsOn(KAFKA);

        Startables.deepStart(DB, KAFKA, debezium).join();

        DialectPipeline.createOutboxTable(
                DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), DialectPipeline.MARIADB_DDL);

        debezium.registerConnector("mariadb-outbox", connectorConfig());

        var registry =
                DialectPipeline.registry(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), SQLDialect.MARIADB);
        DialectPipeline.writeEvent(registry, "porch-lamp", false);

        var events = DialectPipeline.drain(KAFKA.getBootstrapServers(), "mariadb-e2e", 1, Duration.ofSeconds(90));
        assertThat(events)
                .as("no ActionEvent reached Kafka from MariaDB - check the connector task status")
                .isNotEmpty();
        event = events.getFirst();
    }

    @AfterAll
    static void tearDown() {
        if (debezium != null) {
            debezium.stop();
        }
        KAFKA.stop();
        DB.stop();
    }

    @Test
    void the_row_survives_the_whole_pipeline() {
        assertThat(event.getNamespace()).isEqualTo(NAMESPACE);
        assertThat(event.getActionName()).isEqualTo("ToggleWidgetAction");
        assertThat(event.getEventType()).isEqualTo(EVENT_TYPE);
        assertThat(event.getModelType()).isEqualTo("Widget");
    }

    /** MariaDB has no boolean type either: TINYINT(1) reaches the SMT as an INT16. */
    @Test
    void delivered_is_a_boolean_not_an_int16() {
        assertThat(event.getDelivered()).isFalse();
    }

    /**
     * Timestamps have to arrive as instants a consumer cannot misread. These were once a bare
     * {@code int64} of microseconds, which is indistinguishable from milliseconds: guessing wrong
     * placed the event in the year 58535 or three weeks after the epoch, silently. They are now a
     * {@code google.protobuf.Timestamp}, so the unit travels inside the value.
     */
    @Test
    void timestamps_arrive_as_instants_in_the_present() {
        var thisYear = Instant.now().atZone(ZoneOffset.UTC).getYear();
        for (var timestamp : List.of(event.getStartedDate(), event.getCompletionDate(), event.getEventDate())) {
            var when = Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());

            assertThat(when.atZone(ZoneOffset.UTC).getYear())
                    .as("%s decodes to %s", timestamp, when)
                    .isEqualTo(thisYear);
            assertThat(timestamp.getNanos() % 1_000)
                    .as("DATETIME(6) stores microseconds, so the nanosecond digits must be zero;"
                            + " anything else means precision was invented on the way")
                    .isZero();
        }
    }

    /** The measurement this test exists for: a native MariaDB UUID column, whatever its wire form. */
    @Test
    void native_uuid_columns_arrive_as_canonical_uuid_strings() {
        assertThat(UUID.fromString(event.getId())).isNotNull();
        assertThat(UUID.fromString(event.getActionId())).isNotNull();
        assertThat(event.getId()).hasSize(36).isLowerCase();
    }

    @Test
    void the_payload_and_action_params_survive() {
        assertThat(event.getPayload().size()).isPositive();
        assertThat(event.getActionParams()).contains("porch-lamp");
    }

    private static ConnectorConfiguration connectorConfig() {
        return ConnectorConfiguration.create()
                .with("connector.class", "io.debezium.connector.mariadb.MariaDbConnector")
                .with("database.hostname", "mariadb")
                .with("database.port", "3306")
                .with("database.user", "test")
                .with("database.password", "test")
                .with("database.server.id", "184055")
                .with("topic.prefix", TOPIC_PREFIX)
                .with("database.include.list", "eventlog")
                .with("table.include.list", "eventlog.events")
                .with("schema.history.internal.kafka.bootstrap.servers", "kafka:19092")
                .with("schema.history.internal.kafka.topic", "schema-changes.eventlog")
                // See MysqlSmtIntegrationTest: the regression test for audit finding 2.
                .with("include.schema.changes", "true")
                .with("heartbeat.interval.ms", "1000")
                .with("value.converter", "org.apache.kafka.connect.converters.ByteArrayConverter")
                .with("transforms", "encodeProto")
                .with(
                        "transforms.encodeProto.type",
                        "io.ekbatan.events.streaming.debeziumsmt.protobuf.OutboxToProtobufTransform")
                .with(
                        "transforms.encodeProto.payload.descriptors",
                        NAMESPACE + ".proto." + EVENT_TYPE + ":" + CONTAINER_DESCRIPTORS_DIR + "/payload.desc")
                .with(
                        "transforms.encodeProto.action.event.descriptor",
                        CONTAINER_DESCRIPTORS_DIR + "/ActionEvent.desc");
    }
}
