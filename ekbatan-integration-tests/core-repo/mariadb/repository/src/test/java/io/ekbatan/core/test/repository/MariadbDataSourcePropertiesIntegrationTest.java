package io.ekbatan.core.test.repository;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.flyway.FlywayMigrator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mariadb.jdbc.plugin.Credential;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * {@code data-source-properties} against a real MariaDB: a setting reaches MariaDB's driver for a
 * migration's connections and for the application pool's - including the one setting that lets a
 * login need neither {@code username} nor {@code password}: a login plugin, named by
 * {@code credentialType}, that hands the driver both for every connection.
 */
@Testcontainers
class MariadbDataSourcePropertiesIntegrationTest {

    @Container
    private static final MariaDBContainer DB = new MariaDBContainer("mariadb:latest")
            .withDatabaseName("settings")
            .withUsername("settings_app")
            .withPassword("Zq7-mariadb-pw")
            .withEnv("TZ", "UTC");

    @TempDir
    Path migrations;

    /**
     * A migration that records what its own connection saw. The two tests share one database, so
     * each keeps its own history table and starts it from version 0 in a database that is not
     * empty.
     */
    private String migrationRecording(String table) throws Exception {
        Files.writeString(
                migrations.resolve("V1__" + table + ".sql"),
                "CREATE TABLE " + table + " AS SELECT @@session.wait_timeout AS wait_timeout,"
                        + " CURRENT_USER() AS who;");
        return "filesystem:" + migrations;
    }

    @Test
    void a_setting_reaches_the_driver_for_migrations_and_the_application_pool() throws Exception {
        // GIVEN - the value itself holds an '='
        var config = dataSourceConfig()
                .jdbcUrl(DB.getJdbcUrl())
                .username(DB.getUsername())
                .password(DB.getPassword())
                .dataSourceProperties(Map.of("sessionVariables", "wait_timeout=1234"))
                .build();

        // WHEN
        var migrated = FlywayMigrator.builder()
                .withDataSource(config)
                .locations(migrationRecording("seen_session"))
                .customize(cfg ->
                        cfg.table("history_session").baselineOnMigrate(true).baselineVersion("0"))
                .migrate();

        // THEN - the migration's connection had it
        assertThat(migrated.get(0).migrateResult().migrationsExecuted).isEqualTo(1);
        try (var pool = ConnectionProvider.hikariConnectionProvider(config)) {
            var connection = pool.acquire();
            try (var rows = connection
                    .createStatement()
                    .executeQuery("SELECT wait_timeout, @@session.wait_timeout FROM seen_session")) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(1234);

                // AND - so did the application pool's
                assertThat(rows.getLong(2)).isEqualTo(1234);
            } finally {
                pool.release(connection);
            }
        }
    }

    @Test
    void a_login_plugin_named_in_the_settings_supplies_the_user_and_the_password() throws Exception {
        // GIVEN - neither username nor password configured: the plugin supplies both
        TestCredentialPlugin.CURRENT.set(new Credential(DB.getUsername(), DB.getPassword()));
        var askedBefore = TestCredentialPlugin.ASKED.get();
        var config = dataSourceConfig()
                .jdbcUrl(DB.getJdbcUrl())
                .dataSourceProperties(Map.of("credentialType", "EKBATAN_TEST"))
                .build();
        assertThat(config.username).isEmpty();
        assertThat(config.password).isEmpty();

        // WHEN
        var migrated = FlywayMigrator.builder()
                .withDataSource(config)
                .locations(migrationRecording("seen_plugin"))
                .customize(cfg ->
                        cfg.table("history_plugin").baselineOnMigrate(true).baselineVersion("0"))
                .migrate();

        // THEN - the migration logged in as the plugin's user
        assertThat(migrated.get(0).migrateResult().migrationsExecuted).isEqualTo(1);
        try (var pool = ConnectionProvider.hikariConnectionProvider(config)) {
            var connection = pool.acquire();
            try (var rows = connection.createStatement().executeQuery("SELECT who, CURRENT_USER() FROM seen_plugin")) {
                rows.next();
                assertThat(rows.getString(1)).startsWith(DB.getUsername() + "@");

                // AND - so did the application pool
                assertThat(rows.getString(2)).startsWith(DB.getUsername() + "@");
            } finally {
                pool.release(connection);
            }
        }

        // AND - it was the plugin the driver asked
        assertThat(TestCredentialPlugin.ASKED.get()).isGreaterThan(askedBefore);
    }
}
