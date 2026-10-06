package io.ekbatan.core.config;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A JDBC URL carries no user name and no secret: the login goes to {@code username(...)} and
 * {@code password(...)}, every other user name or secret to {@code data-source-properties}. A URL is
 * logged and repeated - Flyway logs it, a driver repeats it in an error - and whatever it carries
 * goes wherever it goes.
 *
 * <p>The check reads the URL's text. Every setting of every driver is covered by the ledger tests
 * ({@link JdbcUrlCredentialsTest}); these tests go through the builder, and show the cases where
 * asking the driver instead gets it wrong both ways.
 */
class DataSourceConfigUrlCredentialsTest {

    private static final String SECRET = "Zq7s3cret";

    private static DataSourceConfig configFor(String jdbcUrl) {
        return dataSourceConfig()
                .jdbcUrl(jdbcUrl)
                .username("app")
                .password("the-real-one")
                .build();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:postgresql://db1:5432/orders?password=" + SECRET,
                "jdbc:postgresql://db1:5432/orders?ssl=true&password=" + SECRET + "&ApplicationName=x",
                "jdbc:mysql://db1:3306/orders?PASSWORD=" + SECRET,
                "jdbc:mariadb://db1:3306/orders?Password=" + SECRET,
            })
    void refuses_the_login_password_and_points_to_password(String jdbcUrl) {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> configFor(jdbcUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries the login password")
                .hasMessageContaining("Give it to password(...) instead")
                .hasMessageNotContaining(SECRET)
                .hasMessageNotContaining("db1");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:postgresql://db1:5432/orders?user=orders_app",
                "jdbc:mysql://db1:3306/orders?useSSL=true&User=orders_app",
                "jdbc:mariadb://db1:3306/orders?USER=orders_app",
                // the Secrets Manager driver takes the secret's name from username, not the URL
                "jdbc-secretsmanager:postgresql://db1:5432/orders?user=orders_secret",
            })
    void refuses_the_login_user_and_points_to_username(String jdbcUrl) {
        // GIVEN / WHEN / THEN - one place for the login user; in the URL it would compete with
        // username, and PostgreSQL's and MariaDB's drivers would let the URL win
        assertThatThrownBy(() -> configFor(jdbcUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries the login user")
                .hasMessageContaining("Give it to username(...) instead")
                .hasMessageNotContaining("orders_");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:postgresql://db1:5432/orders?sslpassword=" + SECRET,
                "jdbc:mysql://db1:3306/orders?password1=" + SECRET,
                "jdbc:mysql://db1:3306/orders?clientCertificateKeyStorePassword=" + SECRET,
                "jdbc:mariadb://db1:3306/orders?trustStorePassword=" + SECRET,
                "jdbc:mariadb://db1:3306/orders?credentialType=AWS-IAM&secretKey=" + SECRET,
                "jdbc:aws-wrapper:postgresql://db1:5432/orders?wrapperPlugins=okta&idpPassword=" + SECRET,
                "jdbc:aws-wrapper:postgresql://db1:5432/orders?wrapperPlugins=efm2&monitoring-password=" + SECRET,
                "jdbc:postgresql://db1:5432/orders?azure.clientSecret=" + SECRET,
            })
    void refuses_any_other_secret_and_points_to_data_source_properties(String jdbcUrl) {
        // GIVEN / WHEN / THEN - a key's or keystore's password is not the login password, so
        // password(...) would be the wrong place for it
        assertThatThrownBy(() -> configFor(jdbcUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries a secret")
                .hasMessageContaining("data-source-properties")
                .hasMessageNotContaining("password(...)")
                .hasMessageNotContaining(SECRET);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:aws-wrapper:postgresql://db1:5432/orders?wrapperPlugins=okta&idpUsername=jane@example.com",
                "jdbc:aws-wrapper:mysql://db1:3306/orders?wrapperPlugins=federatedAuth&dbUser=jane",
                "jdbc:postgresql://db1:5432/orders?azure.username=jane@example.com",
            })
    void refuses_any_other_user_name_and_points_to_data_source_properties(String jdbcUrl) {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> configFor(jdbcUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries a user name")
                .hasMessageContaining("data-source-properties")
                .hasMessageNotContaining("jane");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:mysql://app:" + SECRET + "@db1:3306/orders",
                // MariaDB has no such form, and its own error repeats the URL - this one does not
                "jdbc:mariadb://app:" + SECRET + "@db1:3306/orders",
            })
    void refuses_a_user_and_password_before_an_at_sign(String jdbcUrl) {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> configFor(jdbcUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before an '@'")
                .hasMessageNotContaining(SECRET)
                .hasNoCause();
    }

    @Test
    void refuses_a_password_the_driver_itself_does_not_report() throws Exception {
        // GIVEN - a URL with two hosts: MySQL's driver, asked what the URL carries, says nothing
        var jdbcUrl = "jdbc:mysql://h1:3306,h2:3306/orders?password=" + SECRET;
        var reported = Arrays.stream(DriverManager.getDriver(jdbcUrl).getPropertyInfo(jdbcUrl, new Properties()))
                .filter(setting -> setting.name.equals("password"))
                .map(setting -> setting.value)
                .toList();
        assertThat(reported).isNotEmpty().allMatch(value -> value == null || value.isEmpty());

        // WHEN / THEN - the URL's text carries it, and that text is what gets logged
        assertThatThrownBy(() -> configFor(jdbcUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries the login password")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void accepts_a_url_whose_password_the_driver_takes_from_a_file(@TempDir Path dir) throws Exception {
        // GIVEN - a PostgreSQL service file holding a user and a password, named by the URL
        var serviceFile = Files.writeString(
                dir.resolve("pg_service.conf"),
                "[orders]\nhost=db1\nport=5432\ndbname=orders\nuser=svc\npassword=" + SECRET + "\n");
        var jdbcUrl = "jdbc:postgresql://db1:5432/orders?service=orders";
        var before = System.setProperty("org.postgresql.pgservicefile", serviceFile.toString());
        try {
            // the driver, asked what the URL carries, adds the file's password to its answer
            var reported = Arrays.stream(DriverManager.getDriver(jdbcUrl).getPropertyInfo(jdbcUrl, new Properties()))
                    .filter(setting -> setting.name.equals("password"))
                    .map(setting -> setting.value)
                    .toList();
            assertThat(reported).containsExactly(SECRET);

            // WHEN
            var config = configFor(jdbcUrl);

            // THEN - the URL's text carries no password, so it is accepted
            assertThat(config.jdbcUrl).isEqualTo(jdbcUrl);
        } finally {
            if (before == null) {
                System.clearProperty("org.postgresql.pgservicefile");
            } else {
                System.setProperty("org.postgresql.pgservicefile", before);
            }
        }
    }

    @Test
    void checks_a_url_whose_driver_is_not_on_the_classpath() {
        // GIVEN - pgjdbc-ng's URL; that driver is not here, and the check does not need it
        var jdbcUrl = "jdbc:pgsql://db1:5432/orders?ssl.key.password=" + SECRET;
        assertThatThrownBy(() -> DriverManager.getDriver(jdbcUrl)).isInstanceOf(SQLException.class);

        // WHEN / THEN
        assertThatThrownBy(() -> configFor(jdbcUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'ssl.key.password' setting")
                .hasMessageNotContaining(SECRET);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:postgresql://db1:5432/orders",
                "jdbc:postgresql://db1:5432/orders?ssl=true&ApplicationName=x",
                "jdbc:mysql://db1:3306/orders?useSSL=true",
                "jdbc:mysql://h1:3306,h2:3306/orders",
                "jdbc:mariadb://db1:3306/orders",
                "jdbc:mariadb:sequential://h1,h2/orders",
                // settings whose names say "password" without being one
                "jdbc:mysql://db1:3306/orders?disconnectOnExpiredPasswords=false",
                "jdbc:mysql://db1:3306/orders?passwordCharacterEncoding=UTF-8",
                "jdbc:mariadb://db1:3306/orders?disconnectOnExpiredPasswords=false",
            })
    void accepts_a_url_without_a_user_name_or_a_secret(String jdbcUrl) {
        // GIVEN / WHEN
        var config = configFor(jdbcUrl);

        // THEN
        assertThat(config.jdbcUrl).isEqualTo(jdbcUrl);
        assertThat(config.username).contains("app");
        assertThat(config.password).contains("the-real-one");
    }
}
