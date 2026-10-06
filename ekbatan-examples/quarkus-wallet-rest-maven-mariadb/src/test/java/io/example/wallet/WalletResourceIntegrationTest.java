package io.example.wallet;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.example.wallet.model.NotificationKind;
import io.example.wallet.repository.NotificationRepository;
import io.example.wallet.repository.WalletRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = MariaDBTestResource.class, restrictToAnnotatedClass = true)
class WalletResourceIntegrationTest {

    private static final ShardIdentifier GLOBAL_SHARD = ShardIdentifier.of(0, 0);
    private static final ShardIdentifier MEXICO_SHARD = ShardIdentifier.of(1, 0);

    @Inject
    WalletRepository walletRepository;

    @Inject
    NotificationRepository notificationRepository;

    @Test
    void separate_wallets_route_to_their_own_shards() {
        final var globalId = createWallet("US", "USD", "10.00", 0);
        final var mexicoId = createWallet("MX", "MXN", "20.00", 1);

        given().contentType("application/json")
                .body(Map.of("amount", "5.00", "recipient", "global@example.com"))
                .when()
                .post("/wallets/" + globalId + "/deposits")
                .then()
                .statusCode(200)
                .body("balance", equalTo(15.00f));

        given().contentType("application/json")
                .body(Map.of("amount", "7.00", "recipient", "mexico@example.com"))
                .when()
                .post("/wallets/" + mexicoId + "/deposits")
                .then()
                .statusCode(200)
                .body("balance", equalTo(27.00f));

        assertThat(walletRepository.existsOnShard(GLOBAL_SHARD, globalId)).isTrue();
        assertThat(walletRepository.existsOnShard(MEXICO_SHARD, globalId)).isFalse();
        assertThat(walletRepository.existsOnShard(MEXICO_SHARD, mexicoId)).isTrue();
        assertThat(walletRepository.existsOnShard(GLOBAL_SHARD, mexicoId)).isFalse();
    }

    @Test
    void deposit_emits_event_and_handler_creates_notification() {
        final var walletId = createWallet("US", "USD", "0.00", 0);

        given().contentType("application/json")
                .body(Map.of("amount", "100.00", "recipient", "alice@example.com"))
                .when()
                .post("/wallets/" + walletId + "/deposits")
                .then()
                .statusCode(200)
                .body("balance", equalTo(100.00f));

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
        final var walletId = createWallet("US", "EUR", "0.00", 0);

        given().contentType("application/json")
                .when()
                .post("/wallets/" + walletId + "/close")
                .then()
                .statusCode(200);

        given().when().get("/wallets/" + walletId).then().statusCode(200).body("state", equalTo("CLOSED"));
    }

    private static UUID createWallet(String countryCode, String currency, String initialBalance, int expectedGroup) {
        return UUID.fromString(given().contentType("application/json")
                .body(Map.of(
                        "countryCode", countryCode,
                        "ownerId", UUID.randomUUID().toString(),
                        "currency", currency,
                        "initialBalance", initialBalance))
                .when()
                .post("/wallets")
                .then()
                .statusCode(201)
                .body("state", equalTo("OPENED"))
                .body("id", notNullValue())
                .body("shardGroup", equalTo(expectedGroup))
                .body("shardMember", equalTo(0))
                .extract()
                .path("id"));
    }

    @Inject
    DatabaseRegistry databaseRegistry;

    @Test
    void driver_settings_from_the_configuration_reach_the_database() throws Exception {
        // application.properties gives every primaryConfig a dataSourceProperties entry (sessionVariables);
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
