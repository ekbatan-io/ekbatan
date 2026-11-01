package io.ekbatan.test.event_pipeline.avro_smt;

import static io.ekbatan.core.action.ActionExecutor.Builder.actionExecutor;
import static io.ekbatan.core.action.ActionRegistry.Builder.actionRegistry;
import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;
import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.finance.avro.WalletCreatedEvent;
import com.example.finance.avro.WalletMoneyDepositedEvent;
import io.debezium.testing.testcontainers.ConnectorConfiguration;
import io.debezium.testing.testcontainers.DebeziumContainer;
import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.flyway.FlywayMigrator;
import io.ekbatan.test.event_pipeline.avro_smt.router.AvroEventRouter;
import io.ekbatan.test.event_pipeline.avro_smt.streaming.AvroRetryingEventConsumer;
import io.ekbatan.test.event_pipeline.common.router.EventRoute;
import io.ekbatan.test.event_pipeline.common.wallet.action.WalletCreateAction;
import io.ekbatan.test.event_pipeline.common.wallet.action.WalletDepositMoneyAction;
import io.ekbatan.test.event_pipeline.common.wallet.models.Wallet;
import io.ekbatan.test.event_pipeline.common.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class EventStreamingAvroSmtIntegrationTest {

    private static final String NAMESPACE = "com.example.finance";
    private static final String RAW_TOPIC = "dbserver1.eventlog.events";
    private static final String DLQ_TOPIC = "dbserver1.eventlog.events.dlq";
    private static final String EVENT_TOPIC_CREATED = "ekbatan." + NAMESPACE + ".event.WalletCreatedEvent";
    private static final String EVENT_TOPIC_DEPOSITED = "ekbatan." + NAMESPACE + ".event.WalletMoneyDepositedEvent";

    private static final Path SMT_JAR = Path.of(System.getProperty("smt.plugin.jar"));
    private static final Path PAYLOAD_SCHEMAS_DIR = Path.of(System.getProperty("smt.payload.schemas.dir"));
    private static final Path ACTION_EVENT_SCHEMA = Path.of(System.getProperty("smt.action.event.schema"));

    private static final String CONTAINER_PLUGIN_DIR = "/kafka/connect/ekbatan-smt-avro";
    private static final String CONTAINER_SCHEMAS_DIR = "/opt/ekbatan-schemas";

    private static final Network NETWORK = Network.newNetwork();

    private static final PostgreSQLContainer PG = new PostgreSQLContainer(
                    org.testcontainers.utility.DockerImageName.parse("quay.io/debezium/postgres:15")
                            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres");

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.0")
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka")
            .withListener("kafka:19092");

    private static final DebeziumContainer DEBEZIUM = new DebeziumContainer("quay.io/debezium/connect:3.7.0.Final")
            .withNetwork(NETWORK)
            .withEnv("BOOTSTRAP_SERVERS", "kafka:19092")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(SMT_JAR), CONTAINER_PLUGIN_DIR + "/" + SMT_JAR.getFileName())
            .withCopyFileToContainer(
                    MountableFile.forHostPath(ACTION_EVENT_SCHEMA), CONTAINER_SCHEMAS_DIR + "/ActionEvent.avsc")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(PAYLOAD_SCHEMAS_DIR.resolve("WalletCreatedEvent.avsc")),
                    CONTAINER_SCHEMAS_DIR + "/WalletCreatedEvent.avsc")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(PAYLOAD_SCHEMAS_DIR.resolve("WalletMoneyDepositedEvent.avsc")),
                    CONTAINER_SCHEMAS_DIR + "/WalletMoneyDepositedEvent.avsc")
            .dependsOn(KAFKA);

    private static ActionExecutor executor;
    private static AvroEventRouter router;

    @BeforeAll
    static void setUp() {
        Startables.deepStart(PG, KAFKA, DEBEZIUM).join();

        var dataSourceConfig = dataSourceConfig()
                .jdbcUrl(PG.getJdbcUrl())
                .username(PG.getUsername())
                .password(PG.getPassword())
                .maximumPoolSize(10)
                .build();
        var primaryProvider = hikariConnectionProvider(dataSourceConfig);
        var secondaryProvider = hikariConnectionProvider(dataSourceConfig);
        var transactionManager = new TransactionManager(primaryProvider, secondaryProvider, SQLDialect.POSTGRES);

        FlywayMigrator.migrate(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());

        var databaseRegistry =
                databaseRegistry().withDatabase(transactionManager).build();

        var walletRepo = new WalletRepository(databaseRegistry);
        var actionRegistry = actionRegistry()
                .withAction(WalletCreateAction.class, new WalletCreateAction(Clock.systemUTC()))
                .withAction(WalletDepositMoneyAction.class, new WalletDepositMoneyAction(Clock.systemUTC(), walletRepo))
                .build();

        executor = actionExecutor()
                .namespace(NAMESPACE)
                .databaseRegistry(databaseRegistry)
                .objectMapper(new ObjectMapper())
                .repositoryRegistry(repositoryRegistry()
                        .withModelRepository(Wallet.class, walletRepo)
                        .build())
                .actionRegistry(actionRegistry)
                .build();

        var schemaMapping = NAMESPACE + ".avro.WalletCreatedEvent:" + CONTAINER_SCHEMAS_DIR + "/WalletCreatedEvent.avsc"
                + "," + NAMESPACE + ".avro.WalletMoneyDepositedEvent:" + CONTAINER_SCHEMAS_DIR
                + "/WalletMoneyDepositedEvent.avsc";

        var connectorConfig = ConnectorConfiguration.forJdbcContainer(PG)
                .with("topic.prefix", "dbserver1")
                .with("schema.include.list", "eventlog")
                .with("table.include.list", "eventlog.events")
                .with("plugin.name", "pgoutput")
                .with("value.converter", "org.apache.kafka.connect.converters.ByteArrayConverter")
                .with("transforms", "encodeAvro")
                .with(
                        "transforms.encodeAvro.type",
                        "io.ekbatan.events.streaming.debeziumsmt.avro.OutboxToAvroTransform")
                .with("transforms.encodeAvro.payload.schemas", schemaMapping)
                .with("transforms.encodeAvro.action.event.schema", CONTAINER_SCHEMAS_DIR + "/ActionEvent.avsc")
                .with("transforms.encodeAvro.payload.field", "payload")
                .with("transforms.encodeAvro.event.type.field", "event_type");
        DEBEZIUM.registerConnector("events-connector", connectorConfig);

        router = new AvroEventRouter(
                KAFKA.getBootstrapServers(),
                RAW_TOPIC,
                List.of(
                        EventRoute.forEventType("WalletCreatedEvent", EVENT_TOPIC_CREATED),
                        EventRoute.forEventType("WalletMoneyDepositedEvent", EVENT_TOPIC_DEPOSITED)));
        router.start();
    }

    @org.junit.jupiter.api.AfterAll
    static void tearDown() {
        if (router != null) router.close();
    }

    @Test
    void wallet_created_event_is_fully_avro_encoded() throws Exception {
        var consumer = new AvroRetryingEventConsumer(
                KAFKA.getBootstrapServers(), EVENT_TOPIC_CREATED, "test-create-avro", _ -> {}, DLQ_TOPIC, 3);
        consumer.start();

        executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("Avro Wallet"));

        waitForEvents(consumer::getHandled, 1);
        consumer.close();

        var actionEvent = consumer.getHandled().getFirst();
        assertThat(actionEvent.getEventType()).isEqualTo("WalletCreatedEvent");
        assertThat(actionEvent.getNamespace()).isEqualTo(NAMESPACE);
        assertThat(actionEvent.getActionName()).isEqualTo(WalletCreateAction.class.getSimpleName());

        var event = decode(actionEvent.getPayload().array(), WalletCreatedEvent.class);
        assertThat(event.getName()).isEqualTo("Avro Wallet");
        assertThat(event.getModelName()).isEqualTo("Wallet");
        assertThat(event.getModelId()).isNotNull();
    }

    /**
     * The only end-to-end coverage of a {@code decimal} payload field, which is what money is.
     *
     * <p>{@code WalletMoneyDepositedEvent.avsc} declares {@code amount} as a decimal because the
     * event carries a {@link BigDecimal} and Jackson writes that as a JSON <em>number</em>. It used
     * to declare a {@code string}, which had never been right - the old lenient JSON-to-Avro
     * converter simply called {@code asText()} on the number and the mismatch stayed invisible.
     * Once the converter was made strict ("the schema decides the type, and a value that does not
     * fit fails"), it correctly refused to encode, the payload never reached Kafka, and this test
     * sat on its 31-second timeout - looking like a broken pipeline rather than a wrong fixture.
     *
     * <p>So do not "simplify" this field back to a string: it would encode by accident, and it would
     * take the framework's only e2e decimal coverage with it.
     */
    @Test
    void wallet_deposit_event_is_fully_avro_encoded() throws Exception {
        var consumer = new AvroRetryingEventConsumer(
                KAFKA.getBootstrapServers(), EVENT_TOPIC_DEPOSITED, "test-deposit-avro", _ -> {}, DLQ_TOPIC, 3);
        consumer.start();

        var wallet = executor.execute(
                () -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("Deposit Avro Wallet"));
        executor.execute(
                () -> "test-user",
                WalletDepositMoneyAction.class,
                new WalletDepositMoneyAction.Params(wallet.id, new BigDecimal("123.45")));

        waitForEvents(consumer::getHandled, 1);
        consumer.close();

        var deposit = consumer.getHandled().getFirst();
        var event = decode(deposit.getPayload().array(), WalletMoneyDepositedEvent.class);
        assertThat(event.getModelName()).isEqualTo("Wallet");
        assertThat(event.getAmount()).isEqualByComparingTo(new BigDecimal("123.45"));
    }

    @Test
    void sentinel_row_is_dropped_and_connector_continues() throws Exception {
        var consumer = new AvroRetryingEventConsumer(
                KAFKA.getBootstrapServers(), EVENT_TOPIC_CREATED, "test-sentinel-avro", _ -> {}, DLQ_TOPIC, 3);
        consumer.start();
        try {
            insertSentinelRow("NoOpAvroAction");
            var walletName = "After Sentinel Avro Wallet " + UUID.randomUUID();
            executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params(walletName));

            var event = waitForCreatedEventNamed(consumer, walletName);
            assertThat(event.getName()).isEqualTo(walletName);
            assertThat(consumer.getDeadLettered()).isEmpty();
        } finally {
            consumer.close();
        }
    }

    // --- helpers ---

    private static void insertSentinelRow(String actionName) throws Exception {
        try (var conn = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
                var stmt = conn.prepareStatement("""
                        INSERT INTO eventlog.events (
                            id, namespace, action_id, action_name, action_params,
                            started_date, completion_date, model_id, model_type, event_type,
                            payload, event_date, delivered
                        )
                        VALUES (?, ?, ?, ?, ?::jsonb, NOW(), NOW(), NULL, NULL, NULL, NULL, NOW(), FALSE)
                        """)) {
            stmt.setObject(1, UUID.randomUUID());
            stmt.setString(2, NAMESPACE);
            stmt.setObject(3, UUID.randomUUID());
            stmt.setString(4, actionName);
            stmt.setString(5, "{\"action\":\"noop\"}");
            stmt.executeUpdate();
        }
    }

    /**
     * Decodes a payload through the generated class's own {@code fromByteBuffer}, reached
     * reflectively because the payload type varies per test.
     *
     * <p>Both the envelope and the payload carry Avro's single-object framing, so a consumer makes
     * the same call at both levels. Reading either with a bare {@code binaryDecoder} - as this did -
     * only ever confirmed that the SMT agreed with itself.
     */
    private static <T> T decode(byte[] avroBytes, Class<T> avroClass) {
        try {
            var fromByteBuffer = avroClass.getMethod("fromByteBuffer", java.nio.ByteBuffer.class);
            return avroClass.cast(fromByteBuffer.invoke(null, java.nio.ByteBuffer.wrap(avroBytes)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode Avro bytes for " + avroClass.getSimpleName(), e);
        }
    }

    private static WalletCreatedEvent waitForCreatedEventNamed(AvroRetryingEventConsumer consumer, String walletName)
            throws Exception {
        for (int i = 0; i < 150; i++) {
            for (var actionEvent : consumer.getHandled()) {
                if (!"WalletCreatedEvent".contentEquals(actionEvent.getEventType())) {
                    continue;
                }
                var event = decode(actionEvent.getPayload().array(), WalletCreatedEvent.class);
                if (walletName.contentEquals(event.getName())) {
                    return event;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for wallet-created event: " + walletName);
    }

    private static void waitForEvents(java.util.function.Supplier<List<?>> target, int expectedCount)
            throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            if (target.get().size() >= expectedCount) return;
            Thread.sleep(200);
        }
    }
}
