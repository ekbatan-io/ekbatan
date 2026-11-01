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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
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
 * The unwrapped pipeline: {@code ExtractNewRecordState} runs before the SMT, so Debezium's
 * envelope - and with it {@code op} - is gone by the time the SMT sees the record.
 *
 * <p>Without {@code op} the SMT cannot use the usual {@code c}/{@code r} filter, so it falls back
 * to the row's own {@code delivered} flag: the persister inserts every row false, and the only
 * writer that sets it true is the {@code local-event-handler} fanout. This test is what holds
 * that reasoning to reality - it performs exactly the two writes a real deployment performs, in
 * order, and asserts that the second produces no message.
 *
 * <p>It is deliberately paired with {@code EventEntityDeliveredDefaultTest} in {@code ekbatan-core}.
 * That one guards the invariant at its source; this one guards the consequence. If a future change
 * makes the persister insert rows already delivered, this test goes red by publishing nothing at
 * all - which is precisely the production failure it exists to prevent.
 */
class UnwrappedSmtIntegrationTest {

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
    private static int messagesAfterInsert;
    private static int messagesAfterDeliveredFlip;
    private static boolean deliveredOnInsert;

    @BeforeAll
    static void setUp() throws Exception {
        var descriptors = Files.createTempDirectory("ekbatan-unwrapped-smt");
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

        debezium.registerConnector("unwrapped-outbox", connectorConfig());

        // 1. The insert, through the real persister.
        var registry = DialectPipeline.registry(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), SQLDialect.MYSQL);
        DialectPipeline.writeEvent(registry, "hall-lamp", true);

        deliveredOnInsert = readDeliveredFlag();
        messagesAfterInsert = DialectPipeline.drain(
                        KAFKA.getBootstrapServers(), "unwrapped-insert", 1, Duration.ofSeconds(90))
                .size();

        // 2. The fanout flip, exactly as local-event-handler performs it.
        flipDelivered();

        // Ask for two so the drain runs its full window instead of stopping at the first: the
        // point is to prove nothing further arrives, so it has to wait to be sure.
        messagesAfterDeliveredFlip = DialectPipeline.drain(
                        KAFKA.getBootstrapServers(), "unwrapped-total", 2, Duration.ofSeconds(30))
                .size();
    }

    @AfterAll
    static void tearDown() {
        if (debezium != null) {
            debezium.stop();
        }
        KAFKA.stop();
        DB.stop();
    }

    /** The invariant this whole strategy rests on, observed on a real row rather than assumed. */
    @Test
    void the_persister_writes_rows_that_are_not_yet_delivered() {
        assertThat(deliveredOnInsert)
                .as("the unwrapped path uses delivered = false as its insert signal; see"
                        + " EventEntityDeliveredDefaultTest in ekbatan-core")
                .isFalse();
    }

    @Test
    void the_insert_is_published() {
        assertThat(messagesAfterInsert).isEqualTo(1);
    }

    /** The regression: without the delivered check, this flip published a duplicate event. */
    @Test
    void the_delivered_flip_publishes_nothing_further() {
        assertThat(messagesAfterDeliveredFlip)
                .as("the UPDATE that sets delivered = TRUE must not be republished as a second event")
                .isEqualTo(1);
    }

    private static boolean readDeliveredFlag() throws Exception {
        try (var conn = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery("SELECT delivered FROM eventlog.events LIMIT 1")) {
            assertThat(rs.next()).as("the persister wrote no row at all").isTrue();
            return rs.getBoolean(1);
        }
    }

    private static void flipDelivered() throws Exception {
        try (var conn = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
                var stmt = conn.createStatement()) {
            assertThat(stmt.executeUpdate("UPDATE eventlog.events SET delivered = TRUE"))
                    .isEqualTo(1);
        }
    }

    private static ConnectorConfiguration connectorConfig() {
        return ConnectorConfiguration.create()
                .with("connector.class", "io.debezium.connector.mysql.MySqlConnector")
                .with("database.hostname", "mysql")
                .with("database.port", "3306")
                .with("database.user", "test")
                .with("database.password", "test")
                .with("database.server.id", "184056")
                .with("topic.prefix", TOPIC_PREFIX)
                .with("database.include.list", "eventlog")
                .with("table.include.list", "eventlog.events")
                .with("schema.history.internal.kafka.bootstrap.servers", "kafka:19092")
                .with("schema.history.internal.kafka.topic", "schema-changes.unwrapped")
                .with("include.schema.changes", "true")
                .with("heartbeat.interval.ms", "1000")
                .with("value.converter", "org.apache.kafka.connect.converters.ByteArrayConverter")
                // Unwrap FIRST, deliberately without add.fields=op, so the SMT has to fall back to
                // the delivered flag. This is the configuration that used to duplicate events.
                .with("transforms", "unwrap,encodeProto")
                .with("transforms.unwrap.type", "io.debezium.transforms.ExtractNewRecordState")
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
