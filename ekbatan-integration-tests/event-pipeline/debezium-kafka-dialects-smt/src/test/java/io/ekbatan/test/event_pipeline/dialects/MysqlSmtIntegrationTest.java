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
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The MySQL half of the encoding pipeline, end to end against real Debezium.
 *
 * <p>This is the case that used to kill the connector task on its first record. MySQL has no
 * boolean type - {@code BOOLEAN} is an alias for {@code TINYINT(1)} - so Debezium sends
 * {@code delivered} as an INT16, and the SMT handed that straight to protobuf's
 * {@code setField}, which throws for a {@code bool} field.
 *
 * <p>The unit tests assert the same thing against hand-built Connect records, but those encode
 * one particular model of what Debezium emits. This test is what holds that model accountable to
 * what the real connector actually produces.
 */
class MysqlSmtIntegrationTest {

    private static final Path SMT_JAR = Path.of(System.getProperty("smt.plugin.jar"));
    private static final Path ACTION_EVENT_DESCRIPTOR = Path.of(System.getProperty("smt.action.event.descriptor"));

    private static final Network NETWORK = Network.newNetwork();

    private static final MySQLContainer DB = new MySQLContainer("mysql:9.4.0")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withCopyToContainer(Transferable.of(INIT_SQL), "/docker-entrypoint-initdb.d/init.sql")
            .withEnv("TZ", "UTC")
            .withNetwork(NETWORK)
            .withNetworkAliases("mysql");

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.0")
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka")
            .withListener("kafka:19092");

    private static DebeziumContainer debezium;
    private static ActionEvent event;

    @BeforeAll
    static void setUp() throws Exception {
        var descriptors = Files.createTempDirectory("ekbatan-mysql-smt");
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
                DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), DialectPipeline.MYSQL_DDL);

        debezium.registerConnector("mysql-outbox", connectorConfig());

        var registry = DialectPipeline.registry(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), SQLDialect.MYSQL);
        DialectPipeline.writeEvent(registry, "kitchen-light", true);

        var events = DialectPipeline.drain(KAFKA.getBootstrapServers(), "mysql-e2e", 1, Duration.ofSeconds(90));
        assertThat(events)
                .as("no ActionEvent reached Kafka from MySQL - check the connector task status")
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

    /** The regression: Debezium's INT16 for a TINYINT(1) has to arrive as a protobuf bool. */
    @Test
    void delivered_is_a_boolean_not_an_int16() {
        assertThat(event.getDelivered()).isFalse();
    }

    /**
     * Timestamps have to arrive as instants a consumer cannot misread. These were once a bare
     * {@code int64} of microseconds, which is indistinguishable from milliseconds: guessing wrong
     * placed the event in the year 58535 or three weeks after the epoch, silently. They are now a
     * {@code google.protobuf.Timestamp}, so the unit travels inside the value.
     *
     * <p>Pinning the year proves the whole pipeline agrees on the unit - the column type, Debezium's
     * {@code time.precision.mode} and the SMT's conversion - rather than merely that the SMT
     * compiles against the new descriptor.
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

    /** MySQL stores these as CHAR(36) behind a converter; they must survive as UUID strings. */
    @Test
    void identifiers_are_uuid_strings() {
        assertThat(UUID.fromString(event.getId())).isNotNull();
        assertThat(UUID.fromString(event.getActionId())).isNotNull();
        assertThat(UUID.fromString(event.getModelId())).isNotNull();
    }

    @Test
    void the_payload_and_action_params_survive() {
        assertThat(event.getPayload().size()).isPositive();
        assertThat(event.getActionParams()).contains("kitchen-light");
    }

    private static ConnectorConfiguration connectorConfig() {
        return ConnectorConfiguration.create()
                .with("connector.class", "io.debezium.connector.mysql.MySqlConnector")
                .with("database.hostname", "mysql")
                .with("database.port", "3306")
                .with("database.user", "test")
                .with("database.password", "test")
                .with("database.server.id", "184054")
                .with("topic.prefix", TOPIC_PREFIX)
                .with("database.include.list", "eventlog")
                .with("table.include.list", "eventlog.events")
                .with("schema.history.internal.kafka.bootstrap.servers", "kafka:19092")
                .with("schema.history.internal.kafka.topic", "schema-changes.eventlog")
                // The regression test for audit finding 2. Schema-change and heartbeat records
                // have no `after` field; the SMT used to hand them back untouched, and
                // ByteArrayConverter cannot serialize a Struct, so one of either killed the task.
                // With both switched on, the pipeline has to keep delivering.
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
