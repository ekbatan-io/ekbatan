package io.ekbatan.test.di;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.test.di.shared.widget.action.WidgetCreateAction;
import io.ekbatan.test.di.shared.widget.handler.WidgetCreatedCounterHandler;
import io.ekbatan.test.di.shared.widget.models.WidgetState;
import io.ekbatan.test.di.shared.widget.repository.WidgetRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * End-to-end smoke test for the Ekbatan Quarkus extension.
 *
 * <p>Boots a real {@code @QuarkusTest} application against a Testcontainers Postgres
 * (configured via {@link PostgresTestResource}) and lets the extension's deployment-time build
 * step ({@code EkbatanProcessor#discoverEkbatanBeans}) discover the {@code @EkbatanAction}
 * {@link WidgetCreateAction}, {@code @EkbatanRepository} {@link WidgetRepository}, and
 * {@code @EkbatanEventHandler} {@link WidgetCreatedCounterHandler} via the Jandex index.
 *
 * <p>Asserts that:
 *
 * <ol>
 *   <li>The widget is persisted by {@link ActionExecutor#execute} and can be fetched via the
 *       autowired repository.</li>
 *   <li>The event handler is invoked asynchronously after the fan-out and handling jobs run.</li>
 * </ol>
 *
 * <p>This is the only place the full Quarkus wiring graph is exercised: Jandex discovery -> Arc
 * {@code AdditionalBeanBuildItem} for stereotype singletons -> {@code @ConfigMapping} for flat
 * properties -> {@code EkbatanCoreConfiguration#ekbatanShardingConfig} via Jackson hybrid -> real
 * {@code DatabaseRegistry} with Hikari pool -> {@code SingleTableJsonEventPersister} writing to
 * {@code eventlog.events} -> {@code EventFanoutJob} writing notifications ->
 * {@code EventHandlingJob} draining them and invoking the handler -> repository fetching from
 * the real {@code widgets} table.
 *
 * <p>The {@code handling.enabled=true} flag is set at build time in
 * {@code application.properties} since Quarkus's {@code @IfBuildProperty} reads at jar
 * assembly, not at runtime.
 */
@QuarkusTest
@QuarkusTestResource(value = PostgresTestResource.class, restrictToAnnotatedClass = true)
class EkbatanQuarkusExtensionIntegrationTest {

    @Inject
    ActionExecutor executor;

    @Inject
    WidgetRepository widgetRepository;

    @Inject
    WidgetCreatedCounterHandler counterHandler;

    @Test
    void createAction_persists_widget_and_repository_can_fetch_it() throws Exception {
        var created = executor.execute(
                () -> "test-user", WidgetCreateAction.class, new WidgetCreateAction.Params("first widget", "blue"));

        var fetched = widgetRepository.findById(created.id.getValue());

        assertThat(fetched).isPresent();
        assertThat(fetched.get().name).isEqualTo("first widget");
        assertThat(fetched.get().color).isEqualTo("blue");
        assertThat(fetched.get().state).isEqualTo(WidgetState.ACTIVE);
        assertThat(fetched.get().version).isEqualTo(1L);
    }

    @Test
    void event_handler_is_invoked_after_widget_is_created_via_action() throws Exception {
        var beforeCount = counterHandler.callCount();

        var created = executor.execute(
                () -> "test-user", WidgetCreateAction.class, new WidgetCreateAction.Params("handled widget", "red"));

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(counterHandler.callCount()).isEqualTo(beforeCount + 1);
                    assertThat(counterHandler.handledNames()).contains("handled widget");
                });

        var fetched = widgetRepository.findById(created.id.getValue());
        assertThat(fetched).isPresent();
        assertThat(fetched.get().name).isEqualTo("handled widget");
    }

    @Inject
    DatabaseRegistry databaseRegistry;

    @Test
    void driver_settings_from_the_configuration_file_reach_the_application_connections() throws Exception {
        // GIVEN - data-source-properties in this app's configuration file: ApplicationName, and
        // options, whose value holds spaces and an '='
        var pool = databaseRegistry.transactionManager(ShardIdentifier.of(0, 0)).primaryConnectionProvider;

        // WHEN
        var connection = pool.acquire();
        try (var rows = connection
                .createStatement()
                .executeQuery("SELECT current_setting('application_name'), current_setting('statement_timeout')")) {
            rows.next();

            // THEN - PostgreSQL received both, exactly as written
            assertThat(rows.getString(1)).isEqualTo("ekbatan-quarkus-di");
            assertThat(rows.getString(2)).isEqualTo("4321ms");
        } finally {
            pool.release(connection);
        }
    }
}
