package io.ekbatan.test.postgres_sharded;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.flyway.FlywayMigrator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * How a migration connects, against a real PostgreSQL.
 *
 * <p>The migrator hands Flyway a data source built from the {@link DataSourceConfig} - the way the
 * application's pools connect, without the pool - instead of the URL. These are what that is for:
 * the configured driver class is the one used, even under a URL Flyway would refuse; nothing a
 * migration opened is left open; a refused login fails at once, after one attempt, as it did when
 * Flyway connected by itself; and Flyway's own {@code connectRetries} still applies. A pool would
 * retry a refused login for its whole connection timeout: 30 seconds and 14 refused logins.
 */
@Testcontainers
class FlywayMigratorConnectionIntegrationTest {

    private static final String SECRET = "Zq7-connect-java-pw";
    private static final String WRONG = "Wr0ng-connect-java-pw";

    @Container
    private static final PostgreSQLContainer db = new PostgreSQLContainer("postgres:latest")
            .withDatabaseName("connect_tests")
            .withUsername("app")
            .withPassword(SECRET)
            .withEnv("TZ", "UTC");

    @TempDir
    static Path migrations;

    private static String location;

    @BeforeAll
    static void writeMigrations() throws Exception {
        Files.writeString(migrations.resolve("V1__runs.sql"), "CREATE TABLE runs (id BIGSERIAL PRIMARY KEY);");
        location = "filesystem:" + migrations;
    }

    private static DataSourceConfig config(String password) {
        return dataSourceConfig()
                .jdbcUrl(db.getJdbcUrl())
                .username(db.getUsername())
                .password(password)
                .build();
    }

    @Test
    void a_driver_named_in_the_config_is_the_one_a_migration_connects_through() throws Exception {
        // GIVEN - a database of its own, under a URL Flyway has no plugin for
        try (var connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), SECRET)) {
            connection.createStatement().execute("CREATE DATABASE wrapped");
        }
        var url = db.getJdbcUrl().replace("jdbc:", WrappingDriver.PREFIX).replace("/connect_tests", "/wrapped");
        var config = dataSourceConfig()
                .jdbcUrl(url)
                .username(db.getUsername())
                .password(SECRET)
                .driverClassName(WrappingDriver.class.getName())
                .build();
        var before = WrappingDriver.CONNECTIONS.get();

        // WHEN
        var result = FlywayMigrator.migrate(config, location);

        // THEN
        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(WrappingDriver.CONNECTIONS.get()).isGreaterThan(before);

        // AND - handed the URL instead, Flyway would not have connected at all
        assertThatThrownBy(() -> Flyway.configure().dataSource(url, db.getUsername(), SECRET))
                .hasMessageContaining("No Flyway database plugin found");
    }

    @Test
    void the_url_overload_connects_the_same_way() throws Exception {
        // GIVEN - the wrapping driver registered, as a real one registers itself, and a database
        // under a URL only it answers to
        try (var connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), SECRET)) {
            connection.createStatement().execute("CREATE DATABASE wrapped_by_url");
        }
        var url = db.getJdbcUrl().replace("jdbc:", WrappingDriver.PREFIX).replace("/connect_tests", "/wrapped_by_url");
        var driver = new WrappingDriver();
        DriverManager.registerDriver(driver);
        try {
            var before = WrappingDriver.CONNECTIONS.get();

            // WHEN
            var result = FlywayMigrator.migrate(url, db.getUsername(), SECRET, location);

            // THEN - through the driver, which Flyway handed the URL would never have reached
            assertThat(result.migrationsExecuted).isEqualTo(1);
            assertThat(WrappingDriver.CONNECTIONS.get()).isGreaterThan(before);
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    @Test
    void the_url_overload_refuses_a_url_that_carries_a_password() {
        // GIVEN
        var url = db.getJdbcUrl() + "&password=" + SECRET;

        // WHEN / THEN - refused before anything connects, without repeating the URL
        assertThatThrownBy(() -> FlywayMigrator.migrate(url, db.getUsername(), SECRET, location))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries the login password")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void driver_settings_reach_both_migrations_and_the_application_pool() throws Exception {
        // GIVEN - a setting PostgreSQL reports back, and a migration that records it
        var recorded = Files.createTempDirectory("records-application-name");
        Files.writeString(
                recorded.resolve("V1__seen.sql"),
                "CREATE TABLE seen AS SELECT current_setting('application_name') AS name;");
        var config = dataSourceConfig()
                .jdbcUrl(db.getJdbcUrl())
                .username(db.getUsername())
                .password(SECRET)
                .dataSourceProperties(Map.of("ApplicationName", "ekbatan-driver-settings"))
                .build();

        // WHEN
        FlywayMigrator.builder()
                .withDataSource(config)
                .locations("filesystem:" + recorded)
                .customize(cfg -> cfg.schemas("driver_settings"))
                .migrate();

        // THEN - the migration's connection carried it
        try (var pool = ConnectionProvider.hikariConnectionProvider(config)) {
            var connection = pool.acquire();
            try (var rows = connection
                    .createStatement()
                    .executeQuery(
                            "SELECT (SELECT name FROM driver_settings.seen), current_setting('application_name')")) {
                rows.next();
                assertThat(rows.getString(1)).isEqualTo("ekbatan-driver-settings");

                // AND - so does the application pool's
                assertThat(rows.getString(2)).isEqualTo("ekbatan-driver-settings");
            } finally {
                pool.release(connection);
            }
        }
    }

    @Test
    void a_migration_leaves_no_connection_open() throws Exception {
        // GIVEN
        var config = config(SECRET);

        // WHEN
        var results = FlywayMigrator.builder()
                .withDataSource(config)
                .locations(location)
                .customize(cfg -> cfg.schemas("left_open"))
                .migrate();

        // THEN - not one of the migration's connections is still there, kept in a pool or forgotten
        assertThat(results.get(0).migrateResult().migrationsExecuted).isEqualTo(1);
        assertThat(otherConnections()).isEmpty();
    }

    @Test
    void a_refused_login_fails_at_the_first_attempt_and_says_why() throws Exception {
        // GIVEN
        var refusedBefore = refusedLogins();
        var started = System.nanoTime();

        // WHEN
        var thrown = catchThrowable(() -> FlywayMigrator.migrate(config(WRONG), location));

        // THEN - at once
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(thrown).isInstanceOf(FlywayException.class);
        assertThat(thrown).rootCause().hasMessageContaining("password authentication failed");

        // AND - after a single attempt
        assertThat(refusedLoginsSince(refusedBefore, 1)).isEqualTo(1);

        // AND - nothing thrown carries the password it tried
        assertThat(chainOf(thrown)).doesNotContain(WRONG);
    }

    @Test
    void flyway_connect_retries_still_apply() throws Exception {
        // GIVEN
        var refusedBefore = refusedLogins();

        // WHEN
        var thrown = catchThrowable(() -> FlywayMigrator.builder()
                .withDataSource(config(WRONG))
                .locations(location)
                .customize(cfg -> cfg.connectRetries(2))
                .migrate());

        // THEN - the first attempt and two retries
        assertThat(thrown).isInstanceOf(FlywayException.class);
        assertThat(refusedLoginsSince(refusedBefore, 3)).isEqualTo(3);
    }

    /**
     * Client connections to the server other than the asking one - each as its application name
     * and state - once closed ones have had time to go. PostgreSQL's own background workers are
     * not client connections.
     */
    private static List<String> otherConnections() throws Exception {
        try (var connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), SECRET)) {
            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (true) {
                var found = new ArrayList<String>();
                try (var rows = connection
                        .createStatement()
                        .executeQuery("SELECT application_name, state FROM pg_stat_activity"
                                + " WHERE backend_type = 'client backend' AND pid <> pg_backend_pid()")) {
                    while (rows.next()) {
                        found.add(rows.getString(1) + " (" + rows.getString(2) + ")");
                    }
                }
                if (found.isEmpty() || System.nanoTime() > deadline) {
                    return found;
                }
                Thread.sleep(100);
            }
        }
    }

    /** Refused logins PostgreSQL has logged so far. */
    private static long refusedLogins() {
        return db.getLogs()
                .lines()
                .filter(line -> line.contains("password authentication failed"))
                .count();
    }

    /** Refused logins logged since {@code before}, waiting for at least {@code expected} to arrive. */
    private static long refusedLoginsSince(long before, long expected) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (refusedLogins() - before < expected && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        // and for any late line beyond the expected ones
        Thread.sleep(300);
        return refusedLogins() - before;
    }

    private static String chainOf(Throwable thrown) {
        var chain = new StringBuilder();
        for (var cause = thrown; cause != null; cause = cause.getCause()) {
            chain.append(cause).append('\n');
        }
        return chain.toString();
    }
}
