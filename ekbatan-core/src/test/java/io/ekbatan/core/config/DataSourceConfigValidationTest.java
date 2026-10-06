package io.ekbatan.core.config;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The numeric fields were unvalidated while every string beside them was checked, so a bad number
 * was accepted here and surfaced somewhere unrelated - a pool that can never serve a connection, or
 * leak detection quietly switched off.
 */
class DataSourceConfigValidationTest {

    private static DataSourceConfig.Builder valid() {
        return dataSourceConfig()
                .jdbcUrl("jdbc:postgresql://localhost:5432/db")
                .username("app")
                .password("secret");
    }

    @Test
    void should_reject_a_pool_that_can_never_serve_a_connection() {
        assertThatThrownBy(() -> valid().maximumPoolSize(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximumPoolSize");
    }

    @Test
    void should_reject_a_negative_pool_size() {
        assertThatThrownBy(() -> valid().maximumPoolSize(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximumPoolSize");
    }

    @Test
    void should_reject_a_minimum_idle_above_the_maximum() {
        // Hikari clamps this silently, leaving the pool sized differently from its configuration.
        assertThatThrownBy(() -> valid().maximumPoolSize(5).minimumIdle(10).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimumIdle");
    }

    @Test
    void should_reject_a_negative_minimum_idle() {
        assertThatThrownBy(() -> valid().minimumIdle(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimumIdle");
    }

    @Test
    void should_reject_a_negative_idle_timeout() {
        assertThatThrownBy(() -> valid().idleTimeout(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idleTimeout");
    }

    // ----- leak detection: 0 is how you switch it off, and that has to keep working -----

    @Test
    void should_accept_zero_as_leak_detection_disabled() {
        var config = valid().leakDetectionThreshold(0).build();

        assertThat(config.leakDetectionThreshold).contains(0L);
    }

    @Test
    void should_accept_a_leak_detection_threshold_hikari_will_honour() {
        assertThatCode(() -> valid().leakDetectionThreshold(2_000).build()).doesNotThrowAnyException();
    }

    @Test
    void should_reject_a_leak_detection_threshold_hikari_would_silently_disable() {
        // 1..1999 is the trap: Hikari turns detection off and says so only in a log line.
        assertThatThrownBy(() -> valid().leakDetectionThreshold(1_000).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leakDetectionThreshold");
    }

    @Test
    void should_reject_a_negative_leak_detection_threshold() {
        assertThatThrownBy(() -> valid().leakDetectionThreshold(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leakDetectionThreshold");
    }

    @Test
    void should_name_the_field_when_an_optional_setter_is_given_null() {
        // Optional.of(null) threw a message-less NPE that named nothing.
        assertThatThrownBy(() -> valid().driverClassName(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("driverClassName");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void a_blank_driver_class_name_means_none(String blank) {
        // An unset environment variable behind a placeholder gives a blank one; the driver is then
        // found from the URL, as when the setting is left out.
        var config = valid().driverClassName(blank).build();

        assertThat(config.driverClassName).isEmpty();
    }

    @Test
    void a_driver_class_name_loses_its_surrounding_spaces() {
        var config = valid().driverClassName(" org.postgresql.Driver ").build();

        assertThat(config.driverClassName).contains("org.postgresql.Driver");
    }

    @Test
    void should_still_build_with_defaults_untouched() {
        var config = valid().build();

        assertThat(config.maximumPoolSize).isEqualTo(10);
        assertThat(config.minimumIdle).isEmpty();
        assertThat(config.idleTimeout).isEmpty();
        assertThat(config.leakDetectionThreshold).isEmpty();
    }
}
