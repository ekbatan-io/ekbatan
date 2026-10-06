package io.example.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.domain.Id;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.example.wallet.action.CompleteTransferAction;
import io.example.wallet.action.RefundTransferAction;
import io.example.wallet.model.Wallet;
import java.math.BigDecimal;
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

/**
 * End-to-end integration test for the wallet transfer saga. The tests cover:
 *
 * <ol>
 *   <li><b>Happy path</b> - transfer 25 from A (balance 100) to B (balance 0). The saga's happy
 *       path (InitiateTransfer -> CompleteTransfer) runs via the local-event-handler; both
 *       wallets converge to A = 75, B = 25.</li>
 *   <li><b>Compensation path</b> - transfer 25 from A to a <em>closed</em> wallet B. The saga
 *       reaches step 2, can't credit the closed destination, emits TransferFailed on the source,
 *       and the failed handler invokes RefundTransferAction. A returns to its original balance.</li>
 *   <li><b>Replay safety</b> - re-running a completed follow-up action with the same transferId
 *       is a no-op, so local-handler at-least-once delivery doesn't double-credit or double-refund.</li>
 * </ol>
 *
 * <p>The saga is asynchronous from the caller's perspective - the REST endpoint returns 202
 * after step 1 commits. Awaitility waits for the chain to converge.
 */
@SpringBootTest(
        classes = {Application.class, TestcontainersConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ekbatan.namespace=test.wallet.saga",
            "ekbatan.sharding.defaultShard.group=0",
            "ekbatan.sharding.defaultShard.member=0",
            "ekbatan.sharding.groups[0].group=0",
            "ekbatan.sharding.groups[0].name=default",
            "ekbatan.sharding.groups[0].members[0].member=0",
            "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.maximumPoolSize=5",
            "ekbatan.sharding.groups[0].members[0].configs.jobsConfig.maximumPoolSize=4",
            "ekbatan.sharding.groups[0].members[0].configs.lockConfig.maximumPoolSize=5",
            "ekbatan.sharding.groups[0].members[0].configs.lockConfig.leakDetectionThreshold=0",
            // Tighten poll intervals so the saga's event chain converges quickly under test.
            "ekbatan.local-event-handler.fanout-poll-delay=PT0.2S",
            "ekbatan.local-event-handler.handling-poll-delay=PT0.2S",
            "ekbatan.jobs.polling-interval=PT1S",
            "ekbatan.jobs.shutdown-max-wait=PT5S",
            "ekbatan.local-event-handler.handling.enabled=true",
        })
class WalletSagaIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ActionExecutor executor;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void happy_path_transfer_credits_destination_via_the_local_event_handler() throws Exception {
        // GIVEN - wallet A (balance 100 USD) and wallet B (balance 0 USD)
        final var aId = createWallet("USD", "100.00");
        final var bId = createWallet("USD", "0.00");

        // WHEN - request transfer 25 from A to B
        final var transferResponse = post(
                "/wallets/transfers",
                Map.of(
                        "fromWalletId", aId.toString(),
                        "toWalletId", bId.toString(),
                        "amount", "25.00"));

        // THEN - synchronous step 1 already committed (debit + TransferInitiated)
        assertThat(transferResponse.statusCode()).isEqualTo(202);
        final var transferBody = parse(transferResponse);
        assertThat(transferBody).containsKey("transferId");
        final var transferId = UUID.fromString((String) transferBody.get("transferId"));
        // Source debited synchronously to 75
        @SuppressWarnings("unchecked")
        final var fromWalletAfterDebit = (Map<String, Object>) transferBody.get("fromWallet");
        assertThat(fromWalletAfterDebit).containsEntry("balance", 75.00);

        // AND - the local-event-handler chains step 2 (CompleteTransferAction) which credits B
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(parse(get("/wallets/" + bId))).containsEntry("balance", 25.00);
                    assertThat(parse(get("/wallets/" + aId))).containsEntry("balance", 75.00);
                });

        // AND - replaying step 2 with the same transferId is a no-op, not a second credit.
        executor.execute(
                () -> "test",
                CompleteTransferAction.class,
                new CompleteTransferAction.Params(
                        transferId, Id.of(Wallet.class, aId), Id.of(Wallet.class, bId), new BigDecimal("25.00")));

        assertThat(parse(get("/wallets/" + bId))).containsEntry("balance", 25.00);
        assertThat(parse(get("/wallets/" + aId))).containsEntry("balance", 75.00);
    }

    @Test
    void compensation_path_refunds_source_when_destination_is_closed() throws Exception {
        // GIVEN - wallet A (balance 100 USD) and a CLOSED wallet B
        final var aId = createWallet("USD", "100.00");
        final var bId = createWallet("USD", "0.00");
        final var closeResponse = post("/wallets/" + bId + "/close", Map.of());
        assertThat(closeResponse.statusCode()).isEqualTo(200);
        assertThat(parse(closeResponse)).containsEntry("state", "CLOSED");

        // WHEN - try to transfer 25 from A to the closed B
        final var transferResponse = post(
                "/wallets/transfers",
                Map.of(
                        "fromWalletId", aId.toString(),
                        "toWalletId", bId.toString(),
                        "amount", "25.00"));
        assertThat(transferResponse.statusCode()).isEqualTo(202);
        final var transferId = UUID.fromString((String) parse(transferResponse).get("transferId"));
        // Step 1 debited A synchronously to 75
        @SuppressWarnings("unchecked")
        final var fromWalletAfterDebit =
                (Map<String, Object>) parse(transferResponse).get("fromWallet");
        assertThat(fromWalletAfterDebit).containsEntry("balance", 75.00);

        // THEN - step 2 sees a CLOSED destination and emits TransferFailed.
        // TransferFailedEventHandler picks it up and runs RefundTransferAction, restoring A.
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(parse(get("/wallets/" + aId))).containsEntry("balance", 100.00);
                    // B should be unchanged - still CLOSED, balance 0
                    assertThat(parse(get("/wallets/" + bId))).containsEntry("balance", 0.00);
                    assertThat(parse(get("/wallets/" + bId))).containsEntry("state", "CLOSED");
                });

        // AND - replaying the compensation with the same transferId is a no-op, not a second refund.
        executor.execute(
                () -> "test",
                RefundTransferAction.class,
                new RefundTransferAction.Params(transferId, Id.of(Wallet.class, aId), new BigDecimal("25.00")));

        assertThat(parse(get("/wallets/" + aId))).containsEntry("balance", 100.00);
    }

    @Test
    void compensation_path_refunds_source_when_destination_does_not_exist() throws Exception {
        // GIVEN - only wallet A; destination wallet does not exist
        final var aId = createWallet("USD", "100.00");
        final var nonExistentBId = UUID.randomUUID();

        // WHEN
        final var transferResponse = post(
                "/wallets/transfers",
                Map.of(
                        "fromWalletId", aId.toString(),
                        "toWalletId", nonExistentBId.toString(),
                        "amount", "25.00"));
        assertThat(transferResponse.statusCode()).isEqualTo(202);

        // THEN - same compensation outcome, restored balance on source
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(parse(get("/wallets/" + aId))).containsEntry("balance", 100.00));
    }

    private UUID createWallet(String currency, String initialBalance) throws Exception {
        final var response = post(
                "/wallets",
                Map.of(
                        "ownerId", UUID.randomUUID().toString(),
                        "currency", currency,
                        "initialBalance", initialBalance));
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString((String) parse(response).get("id"));
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
        for (var shard : java.util.List.of(ShardIdentifier.of(0, 0))) {
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
