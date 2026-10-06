package io.ekbatan.core.config;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.config.ShardGroupConfig.Builder.shardGroupConfig;
import static io.ekbatan.core.config.ShardMemberConfig.Builder.shardMemberConfig;
import static io.ekbatan.core.config.ShardingConfig.Builder.shardingConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import io.ekbatan.core.shard.ShardIdentifier;
import java.util.List;
import java.util.Map;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Sanity-check that the YAML shape documented in the README maps cleanly to the existing
 * builder API. The framework does not ship a YAML loader; this test traverses a parsed
 * {@code Map<String, Object>} and feeds it into the existing builders, asserting the shape
 * round-trips into the same {@link ShardingConfig} that calling the builders by hand would
 * produce.
 */
class ShardingConfigYamlTest {

    private static final YAMLMapper YAML = new YAMLMapper();

    @Test
    void should_deserialize_full_sharding_config_from_yaml() {
        // GIVEN
        var yaml = """
                defaultShard:
                  group: 0
                  member: 0
                groups:
                  - group: 0
                    name: global
                    members:
                      - member: 0
                        name: global-eu-1
                        configs:
                          primaryConfig:
                            jdbcUrl: jdbc:postgresql://primary-eu-1:5432/db
                            username: app
                            password: secret
                            maximumPoolSize: 20
                            minimumIdle: 5
                            dataSourceProperties:
                              ApplicationName: orders-service
                              ha.enableJMX: true
                              connectTimeout: 10
                          secondaryConfig:
                            jdbcUrl: jdbc:postgresql://replica-eu-1:5432/db
                            username: app
                            password: secret
                            maximumPoolSize: 10
                          lockConfig:
                            jdbcUrl: jdbc:postgresql://locks-eu-1:5432/db
                            username: app
                            password: secret
                            maximumPoolSize: 50
                            leakDetectionThreshold: 0
                  - group: 1
                    name: mexico
                    members:
                      - member: 0
                        configs:
                          primaryConfig:
                            jdbcUrl: jdbc:postgresql://primary-mx-1:5432/db
                            username: app
                            password: secret
                            maximumPoolSize: 8
                """;

        // WHEN
        var config = parse(yaml);

        // THEN - top level
        assertThat(config.defaultShard).isEqualTo(ShardIdentifier.of(0, 0));
        assertThat(config.groups).hasSize(2);

        // AND - group 0
        var globalGroup = config.groups.get(0);
        assertThat(globalGroup.group).isEqualTo(0);
        assertThat(globalGroup.name).isEqualTo("global");
        assertThat(globalGroup.members).hasSize(1);

        var globalMember = globalGroup.members.get(0);
        assertThat(globalMember.member).isEqualTo(0);
        assertThat(globalMember.name).contains("global-eu-1");

        var primary = globalMember.primaryConfig();
        assertThat(primary.jdbcUrl).isEqualTo("jdbc:postgresql://primary-eu-1:5432/db");
        assertThat(primary.username).contains("app");
        assertThat(primary.password).contains("secret");
        assertThat(primary.maximumPoolSize).isEqualTo(20);
        assertThat(primary.minimumIdle).contains(5);
        assertThat(primary.dialect).isEqualTo(SQLDialect.POSTGRES);
        // names kept as written, dots included; every value becomes its text
        assertThat(primary.dataSourceProperties)
                .containsOnly(
                        entry("ApplicationName", "orders-service"),
                        entry("ha.enableJMX", "true"),
                        entry("connectTimeout", "10"));

        assertThat(globalMember.secondaryConfig()).isPresent();
        assertThat(globalMember.secondaryConfig().get().jdbcUrl).isEqualTo("jdbc:postgresql://replica-eu-1:5432/db");
        assertThat(globalMember.secondaryConfig().get().dataSourceProperties).isEmpty();

        var lockConfig = globalMember.configFor("lockConfig");
        assertThat(lockConfig).isPresent();
        assertThat(lockConfig.get().maximumPoolSize).isEqualTo(50);
        assertThat(lockConfig.get().leakDetectionThreshold).contains(0L);

        // AND - group 1
        var mexicoGroup = config.groups.get(1);
        assertThat(mexicoGroup.group).isEqualTo(1);
        assertThat(mexicoGroup.name).isEqualTo("mexico");
        assertThat(mexicoGroup.members).hasSize(1);

        var mexicoMember = mexicoGroup.members.get(0);
        assertThat(mexicoMember.name).isEmpty();
        assertThat(mexicoMember.primaryConfig().jdbcUrl).isEqualTo("jdbc:postgresql://primary-mx-1:5432/db");
        assertThat(mexicoMember.secondaryConfig()).isEmpty();
        assertThat(mexicoMember.configFor("lockConfig")).isEmpty();
    }

    @Test
    void should_handle_member_with_only_primary_config() {
        // GIVEN
        var yaml = """
                defaultShard: { group: 0, member: 0 }
                groups:
                  - group: 0
                    name: solo
                    members:
                      - member: 0
                        configs:
                          primaryConfig:
                            jdbcUrl: jdbc:postgresql://only:5432/db
                            username: app
                            password: secret
                """;

        // WHEN
        var config = parse(yaml);

        // THEN
        var member = config.groups.get(0).members.get(0);
        assertThat(member.primaryConfig().jdbcUrl).isEqualTo("jdbc:postgresql://only:5432/db");
        assertThat(member.primaryConfig().maximumPoolSize).isEqualTo(10); // builder default
        assertThat(member.secondaryConfig()).isEmpty();
        assertThat(member.configFor("anything")).isEmpty();
    }

    @Test
    void should_throw_clear_error_when_member_omits_primary_config() {
        // GIVEN - secondary present but primary missing
        var yaml = """
                defaultShard: { group: 0, member: 0 }
                groups:
                  - group: 0
                    name: broken
                    members:
                      - member: 0
                        configs:
                          secondaryConfig:
                            jdbcUrl: jdbc:postgresql://only-secondary:5432/db
                            username: app
                            password: secret
                """;

        // WHEN / THEN
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("primaryConfig");
    }

    // ----- YAML -> builder traversal helpers -----

    private static ShardingConfig parse(String yaml) {
        @SuppressWarnings("unchecked")
        var raw = (Map<String, Object>) YAML.readValue(yaml, Map.class);
        return toShardingConfig(raw);
    }

    @SuppressWarnings("unchecked")
    private static ShardingConfig toShardingConfig(Map<String, Object> raw) {
        var defaultShard = toShardIdentifier((Map<String, Object>) raw.get("defaultShard"));
        var builder = shardingConfig().defaultShard(defaultShard);
        for (var groupNode : (List<Map<String, Object>>) raw.get("groups")) {
            builder.withGroup(toShardGroupConfig(groupNode));
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static ShardGroupConfig toShardGroupConfig(Map<String, Object> raw) {
        var builder = shardGroupConfig().group((Integer) raw.get("group")).name((String) raw.get("name"));
        for (var memberNode : (List<Map<String, Object>>) raw.get("members")) {
            builder.withMember(toShardMemberConfig(memberNode));
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static ShardMemberConfig toShardMemberConfig(Map<String, Object> raw) {
        var builder = shardMemberConfig().member((Integer) raw.get("member"));
        if (raw.get("name") instanceof String n) {
            builder.name(n);
        }
        var configs = (Map<String, Object>) raw.get("configs");
        if (configs != null) {
            for (var entry : configs.entrySet()) {
                builder.withConfig(entry.getKey(), toDataSourceConfig((Map<String, Object>) entry.getValue()));
            }
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static DataSourceConfig toDataSourceConfig(Map<String, Object> raw) {
        var builder = dataSourceConfig()
                .jdbcUrl((String) raw.get("jdbcUrl"))
                .username((String) raw.get("username"))
                .password((String) raw.get("password"));
        if (raw.get("driverClassName") instanceof String d) {
            builder.driverClassName(d);
        }
        if (raw.get("maximumPoolSize") instanceof Integer p) {
            builder.maximumPoolSize(p);
        }
        if (raw.get("minimumIdle") instanceof Integer m) {
            builder.minimumIdle(m);
        }
        if (raw.get("idleTimeout") instanceof Number n) {
            builder.idleTimeout(n.longValue());
        }
        if (raw.get("leakDetectionThreshold") instanceof Number n) {
            builder.leakDetectionThreshold(n.longValue());
        }
        if (raw.get("dataSourceProperties") instanceof Map<?, ?> properties) {
            builder.dataSourceProperties((Map<String, ?>) properties);
        }
        return builder.build();
    }

    private static ShardIdentifier toShardIdentifier(Map<String, Object> raw) {
        return ShardIdentifier.of((Integer) raw.get("group"), (Integer) raw.get("member"));
    }
}
