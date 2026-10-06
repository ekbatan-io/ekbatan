package io.ekbatan.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;
import tools.jackson.dataformat.javaprop.JavaPropsSchema;

/**
 * The whole path a DI integration takes - canonical keys, rewritten so the structure is separated
 * by '/', Jackson's properties reader told so - with the awkward names real drivers have.
 *
 * <p>Every DI integration flattens the configuration to lines and hands them to Jackson's
 * properties reader, which reads each separator as one level deeper. A driver setting name keeps
 * its dots only because the structure is read with '/' instead; with dots, {@code a} beside
 * {@code a.b} is merged into one nested value and {@code a} is lost.
 */
class ShardingConfigPropertiesReaderTest {

    private static final String PRIMARY = "groups[0].members[0].configs.primaryConfig.";
    private static final String SECONDARY = "groups[0].members[0].configs.secondaryConfig.";
    private static final String JOBS = "groups[0].members[0].configs.jobsConfig.";

    private static Properties layout() {
        var p = new Properties();
        p.setProperty("defaultShard.group", "0");
        p.setProperty("defaultShard.member", "0");
        p.setProperty("groups[0].group", "0");
        p.setProperty("groups[0].name", "global");
        p.setProperty("groups[0].members[0].member", "0");
        p.setProperty(PRIMARY + "jdbcUrl", "jdbc:postgresql://primary.example:5432/db?ssl=true");
        p.setProperty(PRIMARY + "username", "app");
        return p;
    }

    /** What every DI integration does with the canonical properties. */
    private static ShardingConfig read(Properties canonical) {
        try {
            return JavaPropsMapper.builder()
                    .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build()
                    .readPropertiesAs(
                            PropertyKeyNormalizer.toReaderKeys(canonical),
                            JavaPropsSchema.emptySchema()
                                    .withPathSeparator(PropertyKeyNormalizer.READER_PATH_SEPARATOR),
                            ShardingConfig.class);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void every_driver_setting_name_and_value_arrives_exactly_as_written() {
        // GIVEN - names with capitals and dots, a name that begins another, and values with
        // separators, spaces and JSON text in them
        var p = layout();
        var settings = Map.ofEntries(
                Map.entry("a", "1"),
                Map.entry("a.b", "2"),
                Map.entry("a.b.c", "3"),
                Map.entry("ha.enableJMX", "true"),
                Map.entry("ha.loadBalanceStrategy", "random"),
                Map.entry("kms.region", "eu-west-1"),
                Map.entry("ApplicationName", "orders"),
                Map.entry("keyStorePassword", "k3y=pass:word"),
                Map.entry("connectionAttributes", "team:orders,env:prod"),
                Map.entry("options", "-c search_path=orders"),
                Map.entry("trailing", "text   "),
                Map.entry("json", "{\"x\": 1}"),
                Map.entry("empty", ""));
        settings.forEach((name, value) -> p.setProperty(PRIMARY + "dataSourceProperties." + name, value));

        // WHEN
        var primary = read(p).groups.get(0).members.get(0).primaryConfig();

        // THEN
        assertThat(primary.dataSourceProperties).containsExactlyInAnyOrderEntriesOf(settings);

        // AND - the rest of the configuration is read as before
        assertThat(primary.jdbcUrl).isEqualTo("jdbc:postgresql://primary.example:5432/db?ssl=true");
        assertThat(primary.username).contains("app");
    }

    @Test
    void each_config_keeps_its_own_driver_settings() {
        // GIVEN
        var p = layout();
        p.setProperty(PRIMARY + "dataSourceProperties.ha.enableJMX", "true");
        p.setProperty(SECONDARY + "jdbcUrl", "jdbc:postgresql://replica.example:5432/db");
        p.setProperty(SECONDARY + "dataSourceProperties.ha.enableJMX", "false");
        p.setProperty(JOBS + "jdbcUrl", "jdbc:postgresql://jobs.example:5432/db");

        // WHEN
        var member = read(p).groups.get(0).members.get(0);

        // THEN
        assertThat(member.primaryConfig().dataSourceProperties).containsExactly(Map.entry("ha.enableJMX", "true"));
        assertThat(member.secondaryConfig().orElseThrow().dataSourceProperties)
                .containsExactly(Map.entry("ha.enableJMX", "false"));
        assertThat(member.configFor("jobsConfig").orElseThrow().dataSourceProperties)
                .isEmpty();
    }

    @Test
    void without_the_escape_a_name_that_begins_another_is_lost() throws Exception {
        // GIVEN - why the escape exists: the plain reader merges a and a.b into one nested value
        var p = new Properties();
        p.setProperty("dataSourceProperties.a", "1");
        p.setProperty("dataSourceProperties.a.b", "2");

        // WHEN
        var plain = new JavaPropsMapper().readPropertiesAs(p, Map.class);

        // THEN - "a" is no longer a setting, only a block holding "" and "b"
        assertThat(plain).isEqualTo(Map.of("dataSourceProperties", Map.of("a", Map.of("", "1", "b", "2"))));
    }

    @Test
    void a_driver_setting_name_the_reader_would_treat_as_structure_is_refused() {
        // GIVEN
        var p = layout();
        p.setProperty(PRIMARY + "dataSourceProperties.odd/name", "x");

        // WHEN / THEN
        assertThatThrownBy(() -> read(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("which no driver setting has");
    }

    @Test
    void list_positions_still_work_for_several_groups_and_members() {
        // GIVEN - two groups, the second with two members, each with its own driver settings
        var p = layout();
        p.setProperty(PRIMARY + "dataSourceProperties.ha.enableJMX", "true");
        p.setProperty("groups[1].group", "1");
        p.setProperty("groups[1].name", "mexico");
        for (var m = 0; m < 2; m++) {
            var config = "groups[1].members[" + m + "].configs.primaryConfig.";
            p.setProperty("groups[1].members[" + m + "].member", String.valueOf(m));
            p.setProperty(config + "jdbcUrl", "jdbc:postgresql://mx-" + m + ".example:5432/db");
            p.setProperty(config + "dataSourceProperties.kms.region", "region-" + m);
        }

        // WHEN
        var config = read(p);

        // THEN
        assertThat(config.groups).hasSize(2);
        assertThat(config.groups.get(0).members.get(0).primaryConfig().dataSourceProperties)
                .containsExactly(Map.entry("ha.enableJMX", "true"));
        assertThat(config.groups.get(1).members).hasSize(2);
        assertThat(config.groups.get(1).members.get(0).primaryConfig().dataSourceProperties)
                .containsExactly(Map.entry("kms.region", "region-0"));
        assertThat(config.groups.get(1).members.get(1).primaryConfig().dataSourceProperties)
                .containsExactly(Map.entry("kms.region", "region-1"));
        assertThat(config.groups.get(1).members.get(1).primaryConfig().jdbcUrl)
                .isEqualTo("jdbc:postgresql://mx-1.example:5432/db");
    }
}
