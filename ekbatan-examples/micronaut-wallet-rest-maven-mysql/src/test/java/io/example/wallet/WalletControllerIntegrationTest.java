package io.example.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.example.wallet.model.NotificationKind;
import io.example.wallet.repository.NotificationRepository;
import io.example.wallet.repository.WalletRepository;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Property(name = "ekbatan.namespace", value = "test.wallet")
@Property(name = "ekbatan.local-event-handler.handling.enabled", value = "true")
@Property(name = "ekbatan.local-event-handler.fanout-poll-delay", value = "PT0.2S")
@Property(name = "ekbatan.local-event-handler.handling-poll-delay", value = "PT0.2S")
@Property(name = "ekbatan.jobs.polling-interval", value = "PT1S")
@Property(name = "ekbatan.jobs.shutdown-max-wait", value = "PT5S")
class WalletControllerIntegrationTest implements TestPropertyProvider {

    private static final ShardIdentifier GLOBAL_SHARD = ShardIdentifier.of(0, 0);
    private static final ShardIdentifier MEXICO_SHARD = ShardIdentifier.of(1, 0);

    private final MySQLContainer global = new MySQLContainer("mysql:9.4.0")
            .withDatabaseName("wallet")
            .withUsername("wallet")
            .withPassword("wallet")
            .withEnv("TZ", "UTC")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("mysql_init.sql"), "/docker-entrypoint-initdb.d/mysql_init.sql");

    private final MySQLContainer mexico = new MySQLContainer("mysql:9.4.0")
            .withDatabaseName("wallet")
            .withUsername("wallet")
            .withPassword("wallet")
            .withEnv("TZ", "UTC")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("mysql_init.sql"), "/docker-entrypoint-initdb.d/mysql_init.sql");

    @Inject
    EmbeddedServer server;

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Inject
    WalletRepository walletRepository;

    @Inject
    NotificationRepository notificationRepository;

    @Override
    public Map<String, String> getProperties() {
        global.start();
        mexico.start();
        var props = new HashMap<String, String>();
        props.put("ekbatan.sharding.defaultShard.group", "0");
        props.put("ekbatan.sharding.defaultShard.member", "0");
        props.put("ekbatan.sharding.groups[0].group", "0");
        props.put("ekbatan.sharding.groups[0].name", "global");
        props.put("ekbatan.sharding.groups[0].members[0].member", "0");
        props.put("ekbatan.sharding.groups[0].members[0].name", "global");
        registerShard(
                props,
                "ekbatan.sharding.groups[0].members[0]",
                global.getJdbcUrl(),
                global.getUsername(),
                global.getPassword(),
                "com.mysql.cj.jdbc.Driver");
        props.put("ekbatan.sharding.groups[1].group", "1");
        props.put("ekbatan.sharding.groups[1].name", "mexico");
        props.put("ekbatan.sharding.groups[1].members[0].member", "0");
        props.put("ekbatan.sharding.groups[1].members[0].name", "mexico");
        registerShard(
                props,
                "ekbatan.sharding.groups[1].members[0]",
                mexico.getJdbcUrl(),
                mexico.getUsername(),
                mexico.getPassword(),
                "com.mysql.cj.jdbc.Driver");
        return props;
    }

    @Test
    void separate_wallets_route_to_their_own_shards() {
        final var globalWallet = createWallet("US", "USD", "10.00");
        final var mexicoWallet = createWallet("MX", "MXN", "20.00");
        final var globalId = UUID.fromString((String) globalWallet.body().get("id"));
        final var mexicoId = UUID.fromString((String) mexicoWallet.body().get("id"));

        assertThat(globalWallet.body()).containsEntry("shardGroup", 0).containsEntry("shardMember", 0);
        assertThat(mexicoWallet.body()).containsEntry("shardGroup", 1).containsEntry("shardMember", 0);

        deposit(globalId, "5.00", "global@example.com");
        deposit(mexicoId, "7.00", "mexico@example.com");

        assertThat(walletRepository.existsOnShard(GLOBAL_SHARD, globalId)).isTrue();
        assertThat(walletRepository.existsOnShard(MEXICO_SHARD, globalId)).isFalse();
        assertThat(walletRepository.existsOnShard(MEXICO_SHARD, mexicoId)).isTrue();
        assertThat(walletRepository.existsOnShard(GLOBAL_SHARD, mexicoId)).isFalse();
    }

    @Test
    void deposit_emits_event_and_handler_creates_notification() {
        final var wallet = createWallet("US", "USD", "0.00");
        final var walletId = UUID.fromString((String) wallet.body().get("id"));

        final var depositResponse = deposit(walletId, "100.00", "alice@example.com");
        assertThat(depositResponse.code()).isEqualTo(200);
        assertThat(depositResponse.body()).containsEntry("balance", 100.00);

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    final var notifications = notificationRepository.findAllByWalletId(walletId);
                    assertThat(notifications).hasSize(1);
                    assertThat(notifications.getFirst().kind).isEqualTo(NotificationKind.MONEY_DEPOSITED);
                    assertThat(notifications.getFirst().recipient).isEqualTo("alice@example.com");
                    assertThat(notifications.getFirst().message).contains("100");
                });
    }

    @Test
    void close_transitions_wallet_to_closed_state() {
        final var wallet = createWallet("US", "EUR", "0.00");
        final var walletId = UUID.fromString((String) wallet.body().get("id"));

        final HttpResponse<Map> closeResponse = httpClient
                .toBlocking()
                .exchange(HttpRequest.POST("/wallets/" + walletId + "/close", Map.of()), Map.class);
        assertThat(closeResponse.code()).isEqualTo(200);

        final HttpResponse<Map> getResponse =
                httpClient.toBlocking().exchange(HttpRequest.GET("/wallets/" + walletId), Map.class);
        assertThat(getResponse.body()).containsEntry("state", "CLOSED");
    }

    private HttpResponse<Map> createWallet(String countryCode, String currency, String initialBalance) {
        return httpClient
                .toBlocking()
                .exchange(
                        HttpRequest.POST(
                                "/wallets",
                                Map.of(
                                        "countryCode", countryCode,
                                        "ownerId", UUID.randomUUID().toString(),
                                        "currency", currency,
                                        "initialBalance", initialBalance)),
                        Map.class);
    }

    private HttpResponse<Map> deposit(UUID walletId, String amount, String recipient) {
        return httpClient
                .toBlocking()
                .exchange(
                        HttpRequest.POST(
                                "/wallets/" + walletId + "/deposits", Map.of("amount", amount, "recipient", recipient)),
                        Map.class);
    }

    private static void registerShard(
            Map<String, String> props,
            String prefix,
            String jdbcUrl,
            String username,
            String password,
            String driverClassName) {
        addDataSource(props, prefix + ".configs.primaryConfig", jdbcUrl, username, password, driverClassName, "5");
        addDataSource(props, prefix + ".configs.jobsConfig", jdbcUrl, username, password, driverClassName, "4");
        addDataSource(props, prefix + ".configs.lockConfig", jdbcUrl, username, password, driverClassName, "15");
        props.put(prefix + ".configs.lockConfig.leakDetectionThreshold", "0");
    }

    private static void addDataSource(
            Map<String, String> props,
            String prefix,
            String jdbcUrl,
            String username,
            String password,
            String driverClassName,
            String maximumPoolSize) {
        props.put(prefix + ".jdbcUrl", jdbcUrl);
        props.put(prefix + ".username", username);
        props.put(prefix + ".password", password);
        props.put(prefix + ".driverClassName", driverClassName);
        props.put(prefix + ".maximumPoolSize", maximumPoolSize);
    }

    @Inject
    DatabaseRegistry databaseRegistry;

    @Test
    void driver_settings_from_the_configuration_reach_the_database() throws Exception {
        // application.yml gives every primaryConfig a dataSourceProperties entry (sessionVariables);
        // each shard's primary pool must have handed it to the driver
        for (var shard : java.util.List.of(ShardIdentifier.of(0, 0), ShardIdentifier.of(1, 0))) {
            var pool = databaseRegistry.transactionManager(shard).primaryConnectionProvider;
            var connection = pool.acquire();
            try (var rows = connection.createStatement().executeQuery("SELECT @@session.innodb_lock_wait_timeout")) {
                rows.next();
                assertEquals(10, rows.getLong(1), "shard " + shard);
            } finally {
                pool.release(connection);
            }
        }
    }
}
