package io.ekbatan.test.di;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.flyway.FlywayMigrator;
import io.ekbatan.test.di.shared.widget.action.WidgetCreateAction;
import io.ekbatan.test.di.shared.widget.handler.WidgetCreatedCounterHandler;
import io.ekbatan.test.di.shared.widget.models.WidgetState;
import io.ekbatan.test.di.shared.widget.repository.WidgetRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * End-to-end smoke test for the Ekbatan Micronaut integration.
 *
 * <p>Boots a full Micronaut {@code ApplicationContext} backed by a Testcontainers Postgres and
 * exercises the same scenarios the Spring Boot starter and the Quarkus extension do:
 *
 * <ol>
 *   <li>The {@code @EkbatanAction} {@link WidgetCreateAction} (from the {@code di:shared}
 *       module), lifted to a {@code @Singleton} bean at compile time by the
 *       {@code EkbatanStereotypeVisitor}, persists a widget through
 *       {@link ActionExecutor#execute}, and the {@code @EkbatanRepository}
 *       {@link WidgetRepository} can fetch it.</li>
 *   <li>The {@code @EkbatanEventHandler} {@link WidgetCreatedCounterHandler} is invoked
 *       asynchronously after the fan-out and handling jobs run.</li>
 * </ol>
 *
 * <p>{@link TestPropertyProvider#getProperties()} runs <em>before</em> the application context is
 * built, so by the time {@code EkbatanCoreConfiguration} resolves {@code ekbatan.sharding.*}, the
 * container is up, Flyway has applied {@code public.widgets} / {@code eventlog.events} /
 * {@code eventlog.event_notifications} / {@code scheduled_tasks}, and the dynamic JDBC URL is
 * already in the property tree.
 *
 * <p>{@link TestInstance.Lifecycle#PER_CLASS} ensures a single property-provider instance is
 * reused across all tests in the class, avoiding container restarts between methods.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EkbatanMicronautIntegrationTest implements TestPropertyProvider {

    private final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16")
            .withDatabaseName("ekbatan_test")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    @Inject
    ActionExecutor executor;

    @Inject
    WidgetRepository widgetRepository;

    @Inject
    WidgetCreatedCounterHandler counterHandler;

    @Override
    public Map<String, String> getProperties() {
        postgres.start();

        FlywayMigrator.migrate(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        var props = new HashMap<String, String>();
        // Sharding - single shard pointing at the Testcontainers PG. driverClassName is supplied
        // explicitly because not every Micronaut/Hikari combo discovers the PG driver via the
        // SPI when the JVM is started by the Gradle test worker.
        props.put("ekbatan.sharding.defaultShard.group", "0");
        props.put("ekbatan.sharding.defaultShard.member", "0");
        props.put("ekbatan.sharding.groups[0].group", "0");
        props.put("ekbatan.sharding.groups[0].name", "default");
        props.put("ekbatan.sharding.groups[0].members[0].member", "0");
        props.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl", postgres.getJdbcUrl());
        props.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username", postgres.getUsername());
        props.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password", postgres.getPassword());
        props.put(
                "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.driverClassName", "org.postgresql.Driver");
        props.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.maximumPoolSize", "5");

        // jobsConfig - scheduler shares the same PG for this smoke test.
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.jdbcUrl", postgres.getJdbcUrl());
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.username", postgres.getUsername());
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.password", postgres.getPassword());
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.driverClassName", "org.postgresql.Driver");
        props.put("ekbatan.sharding.groups[0].members[0].configs.jobsConfig.maximumPoolSize", "4");

        return props;
    }

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
            assertThat(rows.getString(1)).isEqualTo("ekbatan-micronaut-di");
            assertThat(rows.getString(2)).isEqualTo("4321ms");
        } finally {
            pool.release(connection);
        }
    }
}
