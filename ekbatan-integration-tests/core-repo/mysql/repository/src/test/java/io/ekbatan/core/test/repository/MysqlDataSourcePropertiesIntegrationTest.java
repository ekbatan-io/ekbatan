package io.ekbatan.core.test.repository;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.mysql.cj.jdbc.JdbcConnection;
import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.flyway.FlywayMigrator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * {@code data-source-properties} against a real MySQL: every setting reaches MySQL's driver
 * exactly as written - for a migration's connections and for the application pool's - and has
 * the effect the driver gives it.
 *
 * <p>The settings are chosen so their effect can be read back: {@code sessionVariables} sets a
 * session variable on every connection (its value itself holds an {@code =}),
 * {@code connectionAttributes} reaches the server's {@code performance_schema} (its value holds
 * {@code :} and {@code ,}), and {@code ha.enableJMX}, whose name holds a dot, is read back from the
 * driver's own settings of the open connection.
 */
@Testcontainers
class MysqlDataSourcePropertiesIntegrationTest {

    @Container
    private static final MySQLContainer DB = new MySQLContainer("mysql:9.4.0")
            .withDatabaseName("settings")
            // root, so the test can read performance_schema
            .withUsername("root")
            .withPassword("Zq7-mysql-root")
            .withEnv("TZ", "UTC");

    @TempDir
    static Path migrations;

    private static String location;

    @BeforeAll
    static void writeMigration() throws Exception {
        // records what the migration's own connection saw
        Files.writeString(
                migrations.resolve("V1__seen.sql"),
                "CREATE TABLE seen AS SELECT @@session.wait_timeout AS wait_timeout,"
                        + " (SELECT ATTR_VALUE FROM performance_schema.session_connect_attrs"
                        + "  WHERE PROCESSLIST_ID = CONNECTION_ID() AND ATTR_NAME = 'team') AS team;");
        location = "filesystem:" + migrations;
    }

    private static DataSourceConfig config() {
        var settings = new LinkedHashMap<String, String>();
        settings.put("sessionVariables", "wait_timeout=1234");
        settings.put("connectionAttributes", "team:orders,env:integration-test");
        settings.put("ha.enableJMX", "true");
        return dataSourceConfig()
                .jdbcUrl(DB.getJdbcUrl())
                .username(DB.getUsername())
                .password(DB.getPassword())
                .dataSourceProperties(settings)
                .build();
    }

    @Test
    void every_setting_reaches_the_driver_for_migrations_and_the_application_pool() throws Exception {
        // GIVEN
        var config = config();

        // WHEN
        var migrated = FlywayMigrator.migrate(config, location);

        // THEN - the migration's connection had them
        assertThat(migrated.migrationsExecuted).isEqualTo(1);
        try (var pool = ConnectionProvider.hikariConnectionProvider(config)) {
            var connection = pool.acquire();
            try (var rows = connection
                    .createStatement()
                    .executeQuery("SELECT wait_timeout, team, @@session.wait_timeout,"
                            + " (SELECT ATTR_VALUE FROM performance_schema.session_connect_attrs"
                            + "  WHERE PROCESSLIST_ID = CONNECTION_ID() AND ATTR_NAME = 'env')"
                            + " FROM seen")) {
                rows.next();
                assertThat(rows.getLong("wait_timeout")).isEqualTo(1234);
                assertThat(rows.getString("team")).isEqualTo("orders");

                // AND - so did the application pool's
                assertThat(rows.getLong(3)).isEqualTo(1234);
                assertThat(rows.getString(4)).isEqualTo("integration-test");
            }

            // AND - the dotted name reached the driver as the one setting it is
            var driverSettings = connection.unwrap(JdbcConnection.class).getPropertySet();
            assertThat(driverSettings.getBooleanProperty("ha.enableJMX").getValue())
                    .isTrue();
            pool.release(connection);
        }
    }
}
