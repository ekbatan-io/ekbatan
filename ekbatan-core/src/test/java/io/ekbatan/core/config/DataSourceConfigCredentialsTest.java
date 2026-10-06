package io.ekbatan.core.config;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Who logs in, and the extra settings the driver gets.
 *
 * <p>{@code username} and {@code password} are optional: a login can have no password (an IAM
 * token, a client certificate) and the user can come from a driver plugin. A blank username is
 * refused rather than taken as "none" - it is far more often an unset variable. The driver's extra
 * settings are kept exactly as written, and may not carry the login itself.
 */
class DataSourceConfigCredentialsTest {

    private static final String URL = "jdbc:postgresql://db1:5432/orders";

    @Test
    void username_and_password_may_both_be_left_out() {
        // GIVEN / WHEN
        var config = dataSourceConfig().jdbcUrl(URL).build();

        // THEN
        assertThat(config.username).isEmpty();
        assertThat(config.password).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void a_blank_username_is_refused_not_taken_as_none(String blank) {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> dataSourceConfig().jdbcUrl(URL).username(blank).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username cannot be empty");
    }

    @Test
    void the_empty_string_stays_a_valid_password() {
        // GIVEN / WHEN - MySQL's root with no password, PostgreSQL's trust login
        var config =
                dataSourceConfig().jdbcUrl(URL).username("root").password("").build();

        // THEN
        assertThat(config.password).contains("");
    }

    @Test
    void driver_settings_are_kept_exactly_as_written() {
        // GIVEN
        var settings = new LinkedHashMap<String, Object>();
        settings.put("sslpassword", "k3y-pass");
        settings.put("ApplicationName", "orders");
        settings.put("ha.enableJMX", true);

        // WHEN
        var config = dataSourceConfig()
                .jdbcUrl(URL)
                .username("app")
                .dataSourceProperties(settings)
                .build();

        // THEN - names untouched, values as text, order kept
        assertThat(config.dataSourceProperties)
                .containsExactly(
                        Map.entry("sslpassword", "k3y-pass"),
                        Map.entry("ApplicationName", "orders"),
                        Map.entry("ha.enableJMX", "true"));
    }

    @Test
    void a_nested_block_is_refused_with_a_hint() {
        // GIVEN - no driver setting holds a block; it is how ha.enableJMX looks when split by mistake
        var builder = dataSourceConfig().jdbcUrl(URL).username("app");

        // WHEN / THEN
        assertThatThrownBy(() -> builder.dataSourceProperties(Map.of("ha", Map.of("enableJMX", "true"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nested block")
                .hasMessageContaining("ha.enableJMX");
    }

    @Test
    void a_name_that_begins_another_stays_its_own_setting() {
        // GIVEN
        var settings = new LinkedHashMap<String, Object>();
        settings.put("a", "1");
        settings.put("a.b", "2");

        // WHEN
        var config = dataSourceConfig()
                .jdbcUrl(URL)
                .username("app")
                .dataSourceProperties(settings)
                .build();

        // THEN
        assertThat(config.dataSourceProperties).containsExactly(Map.entry("a", "1"), Map.entry("a.b", "2"));
    }

    @Test
    void driver_settings_cannot_be_changed_after_build() {
        // GIVEN
        var config = dataSourceConfig()
                .jdbcUrl(URL)
                .username("app")
                .dataSourceProperties(Map.of("ApplicationName", "orders"))
                .build();

        // WHEN / THEN
        assertThatThrownBy(() -> config.dataSourceProperties.put("sslpassword", "x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "password", "USER", "Password"})
    void the_login_cannot_hide_among_the_driver_settings(String name) {
        // GIVEN - either would silently win over username/password; MySQL's and MariaDB's drivers
        // read the two names in any case
        var builder = dataSourceConfig().jdbcUrl(URL).username("app");

        // WHEN / THEN
        assertThatThrownBy(() -> builder.dataSourceProperties(Map.of(name, "Zq7s3cret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'" + name + "' does not belong in dataSourceProperties")
                .hasMessageNotContaining("Zq7s3cret");
    }

    @Test
    void a_driver_setting_must_be_a_single_value() {
        // GIVEN
        var builder = dataSourceConfig().jdbcUrl(URL).username("app");

        // WHEN / THEN
        assertThatThrownBy(() -> builder.dataSourceProperties(Map.of("sslpassword", List.of("a", "b"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single value")
                .hasMessageNotContaining("a, b");
    }
}
