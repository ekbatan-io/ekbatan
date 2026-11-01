package io.ekbatan.test.event_pipeline.protobuf_smt;

import static io.ekbatan.core.action.ActionExecutor.Builder.actionExecutor;
import static io.ekbatan.core.action.ActionRegistry.Builder.actionRegistry;
import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;
import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.finance.proto.WalletCreatedEvent;
import com.example.finance.proto.WalletMoneyDepositedEvent;
import io.debezium.testing.testcontainers.ConnectorConfiguration;
import io.debezium.testing.testcontainers.DebeziumContainer;
import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.flyway.FlywayMigrator;
import io.ekbatan.test.event_pipeline.common.router.EventRoute;
import io.ekbatan.test.event_pipeline.common.wallet.action.WalletCreateAction;
import io.ekbatan.test.event_pipeline.common.wallet.action.WalletDepositMoneyAction;
import io.ekbatan.test.event_pipeline.common.wallet.models.Wallet;
import io.ekbatan.test.event_pipeline.common.wallet.repository.WalletRepository;
import io.ekbatan.test.event_pipeline.protobuf_smt.router.ProtobufEventRouter;
import io.ekbatan.test.event_pipeline.protobuf_smt.streaming.ProtobufRetryingEventConsumer;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.AfterAll;
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
class EventStreamingProtobufSmtIntegrationTest {

    private static final String NAMESPACE = "com.example.finance";
    private static final String RAW_TOPIC = "dbserver1.eventlog.events";
    private static final String EVENT_TOPIC_CREATED = "ekbatan." + NAMESPACE + ".event.WalletCreatedEvent";
    private static final String EVENT_TOPIC_DEPOSITED = "ekbatan." + NAMESPACE + ".event.WalletMoneyDepositedEvent";

    private static final Path SMT_JAR = Path.of(System.getProperty("smt.plugin.jar"));
    private static final Path PAYLOAD_DESCRIPTORS = Path.of(System.getProperty("smt.payload.descriptors"));
    private static final Path ACTION_EVENT_DESCRIPTOR = Path.of(System.getProperty("smt.action.event.descriptor"));

    private static final String CONTAINER_PLUGIN_DIR = "/kafka/connect/ekbatan-smt-protobuf";
    private static final String CONTAINER_DESCRIPTORS_DIR = "/opt/ekbatan-descriptors";

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
                    MountableFile.forHostPath(ACTION_EVENT_DESCRIPTOR), CONTAINER_DESCRIPTORS_DIR + "/ActionEvent.desc")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(PAYLOAD_DESCRIPTORS), CONTAINER_DESCRIPTORS_DIR + "/payloads.desc")
            .dependsOn(KAFKA);

    private static ActionExecutor executor;
    private static ProtobufEventRouter router;

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

        // Both event types live in the same combined descriptor file.
        var payloadDescriptors = NAMESPACE + ".proto.WalletCreatedEvent:" + CONTAINER_DESCRIPTORS_DIR + "/payloads.desc"
                + "," + NAMESPACE + ".proto.WalletMoneyDepositedEvent:" + CONTAINER_DESCRIPTORS_DIR + "/payloads.desc";

        var connectorConfig = ConnectorConfiguration.forJdbcContainer(PG)
                .with("topic.prefix", "dbserver1")
                .with("schema.include.list", "eventlog")
                .with("table.include.list", "eventlog.events")
                .with("plugin.name", "pgoutput")
                .with("value.converter", "org.apache.kafka.connect.converters.ByteArrayConverter")
                .with("transforms", "encodeProto")
                .with(
                        "transforms.encodeProto.type",
                        "io.ekbatan.events.streaming.debeziumsmt.protobuf.OutboxToProtobufTransform")
                .with("transforms.encodeProto.payload.descriptors", payloadDescriptors)
                .with("transforms.encodeProto.action.event.descriptor", CONTAINER_DESCRIPTORS_DIR + "/ActionEvent.desc")
                .with("transforms.encodeProto.payload.field", "payload")
                .with("transforms.encodeProto.event.type.field", "event_type");
        DEBEZIUM.registerConnector("events-connector", connectorConfig);

        router = new ProtobufEventRouter(
                KAFKA.getBootstrapServers(),
                RAW_TOPIC,
                List.of(
                        EventRoute.forEventType("WalletCreatedEvent", EVENT_TOPIC_CREATED),
                        EventRoute.forEventType("WalletMoneyDepositedEvent", EVENT_TOPIC_DEPOSITED)));
        router.start();
    }

    @AfterAll
    static void tearDown() {
        if (router != null) router.close();
    }

    @Test
    void wallet_created_event_is_fully_protobuf_encoded() throws Exception {
        var consumer = new ProtobufRetryingEventConsumer(
                KAFKA.getBootstrapServers(), EVENT_TOPIC_CREATED, "test-create-proto", _ -> {}, 3);
        consumer.start();

        executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("Proto Wallet"));

        waitForEvents(consumer::getHandled, 1);
        consumer.close();

        var actionEvent = consumer.getHandled().getFirst();
        assertThat(actionEvent.getEventType()).isEqualTo("WalletCreatedEvent");
        assertThat(actionEvent.getNamespace()).isEqualTo(NAMESPACE);
        assertThat(actionEvent.getActionName()).isEqualTo(WalletCreateAction.class.getSimpleName());

        var event = WalletCreatedEvent.parseFrom(actionEvent.getPayload());
        assertThat(event.getName()).isEqualTo("Proto Wallet");
        assertThat(event.getModelName()).isEqualTo("Wallet");
        assertThat(event.getModelId()).isNotBlank();
    }

    @Test
    void wallet_deposit_event_is_fully_protobuf_encoded() throws Exception {
        var consumer = new ProtobufRetryingEventConsumer(
                KAFKA.getBootstrapServers(), EVENT_TOPIC_DEPOSITED, "test-deposit-proto", _ -> {}, 3);
        consumer.start();

        var wallet = executor.execute(
                () -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("Deposit Proto Wallet"));
        executor.execute(
                () -> "test-user",
                WalletDepositMoneyAction.class,
                new WalletDepositMoneyAction.Params(wallet.id, new BigDecimal("77.10")));

        waitForEvents(consumer::getHandled, 1);
        consumer.close();

        var event = WalletMoneyDepositedEvent.parseFrom(
                consumer.getHandled().getFirst().getPayload());
        assertThat(event.getModelName()).isEqualTo("Wallet");
        assertThat(new BigDecimal(event.getAmount())).isEqualByComparingTo(new BigDecimal("77.10"));
    }

    @Test
    void sentinel_row_is_dropped_and_connector_continues() throws Exception {
        var consumer = new ProtobufRetryingEventConsumer(
                KAFKA.getBootstrapServers(), EVENT_TOPIC_CREATED, "test-sentinel-proto", _ -> {}, 3);
        consumer.start();
        try {
            insertSentinelRow("NoOpProtoAction");
            var walletName = "After Sentinel Proto Wallet " + UUID.randomUUID();
            executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params(walletName));

            var event = waitForCreatedEventNamed(consumer, walletName);
            assertThat(event.getName()).isEqualTo(walletName);
        } finally {
            consumer.close();
        }
    }

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

    private static WalletCreatedEvent waitForCreatedEventNamed(
            ProtobufRetryingEventConsumer consumer, String walletName) throws Exception {
        for (int i = 0; i < 150; i++) {
            for (var actionEvent : consumer.getHandled()) {
                if (!actionEvent.hasEventType() || !"WalletCreatedEvent".equals(actionEvent.getEventType())) {
                    continue;
                }
                var event = WalletCreatedEvent.parseFrom(actionEvent.getPayload());
                if (walletName.equals(event.getName())) {
                    return event;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for wallet-created event: " + walletName);
    }

    private static void waitForEvents(Supplier<List<?>> target, int expectedCount) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            if (target.get().size() >= expectedCount) return;
            Thread.sleep(200);
        }
    }
}
