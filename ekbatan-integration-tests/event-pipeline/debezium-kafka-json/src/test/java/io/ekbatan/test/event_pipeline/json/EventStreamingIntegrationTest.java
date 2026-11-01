package io.ekbatan.test.event_pipeline.json;

import static io.ekbatan.core.action.ActionExecutor.Builder.actionExecutor;
import static io.ekbatan.core.action.ActionRegistry.Builder.actionRegistry;
import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;
import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static org.assertj.core.api.Assertions.assertThat;

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
import io.ekbatan.test.event_pipeline.json.router.EventRouter;
import io.ekbatan.test.event_pipeline.json.streaming.RetryingEventConsumer;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class EventStreamingIntegrationTest {

    private static final String NAMESPACE = "com.example.finance";
    private static final String RAW_TOPIC = "dbserver1.eventlog.events";
    private static final String MODEL_TOPIC = "ekbatan." + NAMESPACE + ".model.Wallet";
    private static final String EVENT_TOPIC_CREATED = "ekbatan." + NAMESPACE + ".event.WalletCreatedEvent";
    private static final String EVENT_TOPIC_DEPOSITED = "ekbatan." + NAMESPACE + ".event.WalletMoneyDepositedEvent";
    private static final String DLQ_TOPIC = "ekbatan." + NAMESPACE + ".dlq";

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
            .dependsOn(KAFKA);

    private static ActionExecutor executor;
    private static EventRouter router;

    @BeforeAll
    static void setUp() {
        Startables.deepStart(PG, KAFKA, DEBEZIUM).join();

        // Database setup
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

        // Register Debezium PostgreSQL connector
        var connectorConfig = ConnectorConfiguration.forJdbcContainer(PG)
                .with("topic.prefix", "dbserver1")
                .with("schema.include.list", "eventlog")
                .with("table.include.list", "eventlog.events")
                .with("plugin.name", "pgoutput");
        DEBEZIUM.registerConnector("events-connector", connectorConfig);

        // Config-driven router: raw topic -> per-model and per-event-type topics
        router = new EventRouter(
                KAFKA.getBootstrapServers(),
                RAW_TOPIC,
                List.of(
                        EventRoute.forModelType("Wallet", MODEL_TOPIC),
                        EventRoute.forEventType("WalletCreatedEvent", EVENT_TOPIC_CREATED),
                        EventRoute.forEventType("WalletMoneyDepositedEvent", EVENT_TOPIC_DEPOSITED)));
        router.start();
    }

    @AfterAll
    static void tearDown() {
        if (router != null) router.close();
    }

    @BeforeEach
    void cleanTopics() {
        try (var admin =
                Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.deleteTopics(List.of(MODEL_TOPIC, EVENT_TOPIC_CREATED, EVENT_TOPIC_DEPOSITED, DLQ_TOPIC))
                    .all()
                    .get();
        } catch (Exception _) {
            // topics may not exist yet
        }
    }

    private void waitForEvents(java.util.function.Supplier<List<?>> target, int expectedCount)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (target.get().size() >= expectedCount) return;
            Thread.sleep(200);
        }
    }

    // --- Tests ---

    @Test
    void action_event_flows_through_debezium_and_router_to_model_topic() throws Exception {
        // GIVEN
        var consumer = new RetryingEventConsumer(
                KAFKA.getBootstrapServers(), MODEL_TOPIC, "test-model-consumer", _ -> {}, DLQ_TOPIC, 3);
        consumer.start();

        // WHEN
        executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("My Wallet"));

        // THEN
        waitForEvents(consumer::getHandled, 1);
        consumer.close();

        assertThat(consumer.getHandled()).hasSize(1);
        var actionEvent = consumer.getHandled().getFirst();
        assertThat(actionEvent.namespace).isEqualTo(NAMESPACE);
        assertThat(actionEvent.actionName).isEqualTo("WalletCreateAction");
        assertThat(actionEvent.actionId).isNotNull();
        assertThat(actionEvent.modelType).isEqualTo("Wallet");
        assertThat(actionEvent.eventType).isEqualTo("WalletCreatedEvent");
        assertThat(actionEvent.modelId).isNotNull();
        assertThat(actionEvent.payload.get("name").asString()).isEqualTo("My Wallet");
        assertThat(actionEvent.startedDate).isNotNull();
        assertThat(actionEvent.completionDate).isNotNull();
        assertThat(actionEvent.eventDate).isNotNull();
    }

    @Test
    void action_event_flows_through_debezium_and_router_to_event_type_topic() throws Exception {
        // GIVEN
        var consumer = new RetryingEventConsumer(
                KAFKA.getBootstrapServers(), EVENT_TOPIC_CREATED, "test-event-consumer", _ -> {}, DLQ_TOPIC, 3);
        consumer.start();

        // WHEN
        executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("Event Wallet"));

        // THEN
        waitForEvents(consumer::getHandled, 1);
        consumer.close();

        assertThat(consumer.getHandled()).hasSize(1);
        assertThat(consumer.getHandled().getFirst().payload.get("name").asString())
                .isEqualTo("Event Wallet");
    }

    @Test
    void single_event_routed_to_both_model_and_event_type_topics() throws Exception {
        // GIVEN
        var modelConsumer = new RetryingEventConsumer(
                KAFKA.getBootstrapServers(), MODEL_TOPIC, "test-both-model", _ -> {}, DLQ_TOPIC, 3);
        var eventConsumer = new RetryingEventConsumer(
                KAFKA.getBootstrapServers(), EVENT_TOPIC_CREATED, "test-both-event", _ -> {}, DLQ_TOPIC, 3);
        modelConsumer.start();
        eventConsumer.start();

        // WHEN
        executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("Both Wallet"));

        // THEN
        waitForEvents(modelConsumer::getHandled, 1);
        waitForEvents(eventConsumer::getHandled, 1);
        modelConsumer.close();
        eventConsumer.close();

        assertThat(modelConsumer.getHandled()).hasSize(1);
        assertThat(eventConsumer.getHandled()).hasSize(1);
        assertThat(modelConsumer.getHandled().getFirst().payload.get("name").asString())
                .isEqualTo("Both Wallet");
        assertThat(eventConsumer.getHandled().getFirst().payload.get("name").asString())
                .isEqualTo("Both Wallet");
    }

    @Test
    void sentinel_row_skipped_by_router() throws Exception {
        // GIVEN - insert a sentinel row (action with zero events)
        try (var conn = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
                var stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO eventlog.events (id, namespace, action_id, action_name, action_params, "
                    + "started_date, completion_date, model_id, model_type, event_type, payload, event_date, delivered) VALUES ("
                    + "'" + java.util.UUID.randomUUID() + "', "
                    + "'" + NAMESPACE + "', "
                    + "'" + java.util.UUID.randomUUID() + "', "
                    + "'NoOpAction', "
                    + "'{\"action\":\"noop\"}'::jsonb, "
                    + "NOW(), NOW(), NULL, NULL, NULL, NULL, NOW(), FALSE)");
        }

        // WHEN
        var consumer = new RetryingEventConsumer(
                KAFKA.getBootstrapServers(), MODEL_TOPIC, "test-sentinel", _ -> {}, DLQ_TOPIC, 3);
        consumer.start();

        Thread.sleep(5000);
        consumer.close();

        // THEN - sentinel row was not routed
        assertThat(consumer.getHandled()).isEmpty();
    }

    @Test
    void selective_consumer_receives_only_deposit_events_not_create_events() throws Exception {
        // GIVEN - consumer only on the deposit event topic
        var consumer = new RetryingEventConsumer(
                KAFKA.getBootstrapServers(), EVENT_TOPIC_DEPOSITED, "test-deposit-only", _ -> {}, DLQ_TOPIC, 3);
        consumer.start();

        // WHEN - create then deposit
        var wallet = executor.execute(
                () -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("Deposit Wallet"));
        executor.execute(
                () -> "test-user",
                WalletDepositMoneyAction.class,
                new WalletDepositMoneyAction.Params(wallet.id, new BigDecimal("50.00")));

        // THEN - only the deposit event, not the create event
        waitForEvents(consumer::getHandled, 1);
        consumer.close();

        assertThat(consumer.getHandled()).hasSize(1);
        var actionEvent = consumer.getHandled().getFirst();
        assertThat(actionEvent.eventType).isEqualTo("WalletMoneyDepositedEvent");
        assertThat(actionEvent.actionName).isEqualTo("WalletDepositMoneyAction");
        assertThat(new BigDecimal(actionEvent.payload.get("amount").asString()))
                .isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(consumer.getDeadLettered()).isEmpty();
    }

    @Test
    void failing_handler_retries_then_sends_to_dlq() throws Exception {
        // GIVEN - a handler that always fails
        var failCount = new AtomicInteger(0);
        var consumer = new RetryingEventConsumer(
                KAFKA.getBootstrapServers(),
                EVENT_TOPIC_DEPOSITED,
                "test-dlq",
                _ -> {
                    failCount.incrementAndGet();
                    throw new RuntimeException("Simulated failure");
                },
                DLQ_TOPIC,
                3);
        consumer.start();

        // WHEN
        var wallet = executor.execute(
                () -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("DLQ Wallet"));
        executor.execute(
                () -> "test-user",
                WalletDepositMoneyAction.class,
                new WalletDepositMoneyAction.Params(wallet.id, new BigDecimal("25.00")));

        // THEN - retried 3 times, then DLQ'd
        waitForEvents(consumer::getDeadLettered, 1);
        consumer.close();

        assertThat(failCount.get()).isEqualTo(3);
        assertThat(consumer.getHandled()).isEmpty();
        assertThat(consumer.getDeadLettered()).hasSize(1);
    }
}
