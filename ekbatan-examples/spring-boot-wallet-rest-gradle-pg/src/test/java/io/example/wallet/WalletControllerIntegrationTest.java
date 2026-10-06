package io.example.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.example.wallet.model.NotificationKind;
import io.example.wallet.repository.NotificationRepository;
import io.example.wallet.repository.WalletRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = {Application.class, TestcontainersConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ekbatan.namespace=test.wallet",
            "ekbatan.sharding.defaultShard.group=0",
            "ekbatan.sharding.defaultShard.member=0",
            "ekbatan.sharding.groups[0].group=0",
            "ekbatan.sharding.groups[0].name=global",
            "ekbatan.sharding.groups[0].members[0].member=0",
            "ekbatan.sharding.groups[0].members[0].name=global",
            "ekbatan.sharding.groups[1].group=1",
            "ekbatan.sharding.groups[1].name=mexico",
            "ekbatan.sharding.groups[1].members[0].member=0",
            "ekbatan.sharding.groups[1].members[0].name=mexico",
            "ekbatan.local-event-handler.fanout-poll-delay=PT0.2S",
            "ekbatan.local-event-handler.handling-poll-delay=PT0.2S",
            "ekbatan.jobs.polling-interval=PT1S",
            "ekbatan.jobs.shutdown-max-wait=PT5S",
            "ekbatan.local-event-handler.handling.enabled=true",
        })
class WalletControllerIntegrationTest {

    private static final ShardIdentifier GLOBAL_SHARD = ShardIdentifier.of(0, 0);
    private static final ShardIdentifier MEXICO_SHARD = ShardIdentifier.of(1, 0);

    @LocalServerPort
    private int port;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void separate_wallets_route_to_their_own_shards() throws Exception {
        final var globalWallet = parse(createWallet("US", "USD", "10.00"));
        final var mexicoWallet = parse(createWallet("MX", "MXN", "20.00"));
        final var globalId = UUID.fromString((String) globalWallet.get("id"));
        final var mexicoId = UUID.fromString((String) mexicoWallet.get("id"));

        assertThat(globalWallet).containsEntry("shardGroup", 0).containsEntry("shardMember", 0);
        assertThat(mexicoWallet).containsEntry("shardGroup", 1).containsEntry("shardMember", 0);

        assertThat(parse(deposit(globalId, "5.00", "global@example.com"))).containsEntry("balance", 15.00);
        assertThat(parse(deposit(mexicoId, "7.00", "mexico@example.com"))).containsEntry("balance", 27.00);

        assertThat(walletRepository.existsOnShard(GLOBAL_SHARD, globalId)).isTrue();
        assertThat(walletRepository.existsOnShard(MEXICO_SHARD, globalId)).isFalse();
        assertThat(walletRepository.existsOnShard(MEXICO_SHARD, mexicoId)).isTrue();
        assertThat(walletRepository.existsOnShard(GLOBAL_SHARD, mexicoId)).isFalse();
    }

    @Test
    void deposit_emits_event_and_handler_creates_notification() throws Exception {
        final var wallet = parse(createWallet("US", "USD", "0.00"));
        final var walletId = UUID.fromString((String) wallet.get("id"));

        final var depositResponse = deposit(walletId, "100.00", "alice@example.com");
        assertThat(depositResponse.statusCode()).isEqualTo(200);
        assertThat(parse(depositResponse)).containsEntry("balance", 100.00);

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
    void close_transitions_wallet_to_closed_state() throws Exception {
        final var wallet = parse(createWallet("US", "EUR", "0.00"));
        final var walletId = UUID.fromString((String) wallet.get("id"));

        final var closeResponse = post("/wallets/" + walletId + "/close", Map.of());
        assertThat(closeResponse.statusCode()).isEqualTo(200);
        assertThat(parse(get("/wallets/" + walletId))).containsEntry("state", "CLOSED");
    }

    private HttpResponse<String> createWallet(String countryCode, String currency, String initialBalance)
            throws Exception {
        return post(
                "/wallets",
                Map.of(
                        "countryCode", countryCode,
                        "ownerId", UUID.randomUUID().toString(),
                        "currency", currency,
                        "initialBalance", initialBalance));
    }

    private HttpResponse<String> deposit(UUID walletId, String amount, String recipient) throws Exception {
        return post("/wallets/" + walletId + "/deposits", Map.of("amount", amount, "recipient", recipient));
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(HttpResponse<String> response) throws Exception {
        return objectMapper.readValue(response.body(), Map.class);
    }

    @Autowired
    private DatabaseRegistry databaseRegistry;

    @Test
    void driver_settings_from_the_configuration_reach_the_database() throws Exception {
        // application.yml gives every primaryConfig a dataSourceProperties entry (ApplicationName);
        // each shard's primary pool must have handed it to the driver
        for (var shard : java.util.List.of(ShardIdentifier.of(0, 0), ShardIdentifier.of(1, 0))) {
            var pool = databaseRegistry.transactionManager(shard).primaryConnectionProvider;
            var connection = pool.acquire();
            try (var rows = connection.createStatement().executeQuery("SELECT current_setting('application_name')")) {
                rows.next();
                assertEquals("wallet-service", rows.getString(1), "shard " + shard);
            } finally {
                pool.release(connection);
            }
        }
    }
}
