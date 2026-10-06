package io.ekbatan.core.persistence;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;

/**
 * {@link DataSources#driverDataSource} opens a new connection through the configured driver on
 * every call, and hands out the driver's own connection: nothing in between to keep it, reuse it
 * or retry it.
 */
// Mockito cannot run inside a native image (see ActionExecutorTest). The same behaviour is
// exercised against a real PostgreSQL by FlywayMigratorConnectionIntegrationTest in
// ekbatan-integration-tests:postgres-sharded.
@DisabledInNativeImage
class DataSourcesTest {

    /** Records every connect and answers it with a new connection; reached only by class name. */
    public static final class RecordingDriver implements Driver {

        static final List<Properties> CONNECTS = new CopyOnWriteArrayList<>();
        static final List<Connection> MADE = new CopyOnWriteArrayList<>();

        @Override
        public Connection connect(String url, Properties info) {
            if (!acceptsURL(url)) {
                return null;
            }
            var connection = mock(Connection.class);
            CONNECTS.add((Properties) info.clone());
            MADE.add(connection);
            return connection;
        }

        @Override
        public boolean acceptsURL(String url) {
            return url.startsWith("jdbc:recording:");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }

    @Test
    void every_call_opens_a_new_connection_through_the_configured_driver() throws Exception {
        // GIVEN
        RecordingDriver.CONNECTS.clear();
        RecordingDriver.MADE.clear();
        var dataSource = DataSources.driverDataSource(dataSourceConfig()
                .jdbcUrl("jdbc:recording:postgresql://db1:5432/orders")
                .username("app")
                .password("the-real-one")
                .driverClassName(RecordingDriver.class.getName())
                .build());

        // WHEN
        var first = dataSource.getConnection();
        var second = dataSource.getConnection();

        // THEN - one connect per call, each with the configured user and password
        assertThat(RecordingDriver.CONNECTS).hasSize(2).allSatisfy(info -> {
            assertThat(info.getProperty("user")).isEqualTo("app");
            assertThat(info.getProperty("password")).isEqualTo("the-real-one");
        });

        // AND - each is the driver's own connection, not a pool's stand-in for one
        assertThat(List.of(first, second)).containsExactlyElementsOf(RecordingDriver.MADE);
    }

    @Test
    void the_driver_gets_the_extra_settings_and_no_user_when_none_is_configured() throws Exception {
        // GIVEN - a plugin would supply the user, so none is configured
        RecordingDriver.CONNECTS.clear();
        RecordingDriver.MADE.clear();
        var dataSource = DataSources.driverDataSource(dataSourceConfig()
                .jdbcUrl("jdbc:recording:mariadb://db1:3306/orders")
                .driverClassName(RecordingDriver.class.getName())
                .dataSourceProperties(java.util.Map.of("credentialType", "VAULT", "ha.enableJMX", "true"))
                .build());

        // WHEN
        dataSource.getConnection();

        // THEN - the settings arrive as written; neither user nor password is invented
        var info = RecordingDriver.CONNECTS.get(0);
        assertThat(info.getProperty("credentialType")).isEqualTo("VAULT");
        assertThat(info.getProperty("ha.enableJMX")).isEqualTo("true");
        assertThat(info.stringPropertyNames()).doesNotContain("user", "password");
    }

    @Test
    void every_driver_setting_reaches_the_driver_as_its_own_setting() throws Exception {
        // GIVEN - a name that begins another, and dotted names
        RecordingDriver.CONNECTS.clear();
        RecordingDriver.MADE.clear();
        var settings = new java.util.LinkedHashMap<String, String>();
        settings.put("a", "1");
        settings.put("a.b", "2");
        settings.put("ha.enableJMX", "true");
        settings.put("kms.region", "eu-west-1");
        var dataSource = DataSources.driverDataSource(dataSourceConfig()
                .jdbcUrl("jdbc:recording:mysql://db1:3306/orders")
                .username("app")
                .driverClassName(RecordingDriver.class.getName())
                .dataSourceProperties(settings)
                .build());

        // WHEN
        dataSource.getConnection();

        // THEN
        var info = RecordingDriver.CONNECTS.get(0);
        settings.forEach(
                (name, value) -> assertThat(info.getProperty(name)).as(name).isEqualTo(value));
        assertThat(info.getProperty("user")).isEqualTo("app");
    }

    @Test
    void a_null_configuration_is_refused() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> DataSources.driverDataSource(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("dataSourceConfig cannot be null");
    }
}
