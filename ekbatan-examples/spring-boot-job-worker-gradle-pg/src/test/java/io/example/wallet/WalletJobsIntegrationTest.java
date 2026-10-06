package io.example.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.example.wallet.action.WalletCreateAction;
import io.example.wallet.model.NotificationKind;
import io.example.wallet.repository.NotificationRepository;
import io.example.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

@SpringBootTest(
        classes = {Application.class, TestcontainersConfiguration.class},
        webEnvironment = WebEnvironment.NONE,
        properties = {
            "ekbatan.namespace=test.wallet.worker",
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
            "ekbatan.jobs.polling-interval=PT0.2S",
            "ekbatan.jobs.shutdown-max-wait=PT5S",
            "ekbatan.local-event-handler.handling.enabled=true",
        })
class WalletJobsIntegrationTest {

    private static final ShardIdentifier GLOBAL_SHARD = ShardIdentifier.of(0, 0);
    private static final ShardIdentifier MEXICO_SHARD = ShardIdentifier.of(1, 0);

    @Autowired
    private ActionExecutor executor;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void stipend_job_tops_up_underfunded_wallets_on_each_shard_and_triggers_notifications() throws Exception {
        final var globalWallet = executor.execute(
                () -> "test-user",
                WalletCreateAction.class,
                new WalletCreateAction.Params("US", UUID.randomUUID(), Currency.getInstance("USD"), BigDecimal.ZERO));
        final var mexicoWallet = executor.execute(
                () -> "test-user",
                WalletCreateAction.class,
                new WalletCreateAction.Params("MX", UUID.randomUUID(), Currency.getInstance("MXN"), BigDecimal.ZERO));

        assertThat(walletRepository.existsOnShard(GLOBAL_SHARD, globalWallet.id.getValue()))
                .isTrue();
        assertThat(walletRepository.existsOnShard(MEXICO_SHARD, globalWallet.id.getValue()))
                .isFalse();
        assertThat(walletRepository.existsOnShard(MEXICO_SHARD, mexicoWallet.id.getValue()))
                .isTrue();
        assertThat(walletRepository.existsOnShard(GLOBAL_SHARD, mexicoWallet.id.getValue()))
                .isFalse();

        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    final var global = walletRepository.getById(globalWallet.id.getValue());
                    final var mexico = walletRepository.getById(mexicoWallet.id.getValue());
                    assertThat(global.balance).isGreaterThanOrEqualTo(new BigDecimal("10.00"));
                    assertThat(mexico.balance).isGreaterThanOrEqualTo(new BigDecimal("10.00"));

                    final var globalNotifications = notificationRepository.findAllByWalletId(global.id.getValue());
                    final var mexicoNotifications = notificationRepository.findAllByWalletId(mexico.id.getValue());
                    assertThat(globalNotifications).isNotEmpty();
                    assertThat(mexicoNotifications).isNotEmpty();
                    assertThat(globalNotifications)
                            .allSatisfy(n -> assertThat(n.kind).isEqualTo(NotificationKind.MONEY_DEPOSITED));
                    assertThat(mexicoNotifications)
                            .allSatisfy(n -> assertThat(n.kind).isEqualTo(NotificationKind.MONEY_DEPOSITED));
                });
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
