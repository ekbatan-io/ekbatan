package io.ekbatan.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.core.shard.UnknownShardPolicy;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * End-to-end YAML deserialization of {@link ShardingConfig} via the inline Jackson annotations on
 * the sharding config classes ({@code @JsonDeserialize(builder = ...)},
 * {@code @JsonPOJOBuilder}, {@code @JsonIgnore}). No mix-in module; a plain {@link YAMLMapper}
 * picks up the annotations directly from the target classes.
 */
class ShardingConfigJacksonBindingTest {

    private static final YAMLMapper MAPPER = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void should_deserialize_full_sharding_config_from_yaml() {
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

        var config = MAPPER.readValue(yaml, ShardingConfig.class);

        assertThat(config.defaultShard).isEqualTo(ShardIdentifier.of(0, 0));
        assertThat(config.groups).hasSize(2);

        var globalGroup = config.groups.get(0);
        assertThat(globalGroup.group).isEqualTo(0);
        assertThat(globalGroup.name).isEqualTo("global");
        assertThat(globalGroup.members).hasSize(1);

        var globalMember = globalGroup.members.get(0);
        assertThat(globalMember.member).isEqualTo(0);
        assertThat(globalMember.name).contains("global-eu-1");

        var primary = globalMember.primaryConfig();
        assertThat(primary.jdbcUrl).isEqualTo("jdbc:postgresql://primary-eu-1:5432/db");
        assertThat(primary.username).isEqualTo("app");
        assertThat(primary.password).isEqualTo("secret");
        assertThat(primary.maximumPoolSize).isEqualTo(20);
        assertThat(primary.minimumIdle).contains(5);
        assertThat(primary.dialect).isEqualTo(SQLDialect.POSTGRES);

        assertThat(globalMember.secondaryConfig()).isPresent();
        assertThat(globalMember.secondaryConfig().get().jdbcUrl).isEqualTo("jdbc:postgresql://replica-eu-1:5432/db");

        var lockConfig = globalMember.configFor("lockConfig");
        assertThat(lockConfig).isPresent();
        assertThat(lockConfig.get().maximumPoolSize).isEqualTo(50);
        assertThat(lockConfig.get().leakDetectionThreshold).contains(0L);

        var mexicoGroup = config.groups.get(1);
        assertThat(mexicoGroup.group).isEqualTo(1);
        assertThat(mexicoGroup.name).isEqualTo("mexico");

        var mexicoMember = mexicoGroup.members.get(0);
        assertThat(mexicoMember.name).isEmpty();
        assertThat(mexicoMember.primaryConfig().jdbcUrl).isEqualTo("jdbc:postgresql://primary-mx-1:5432/db");
        assertThat(mexicoMember.secondaryConfig()).isEmpty();
        assertThat(mexicoMember.configFor("lockConfig")).isEmpty();
    }

    @Test
    void should_handle_member_with_only_primary_config() {
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

        var config = MAPPER.readValue(yaml, ShardingConfig.class);

        var member = config.groups.get(0).members.get(0);
        assertThat(member.primaryConfig().jdbcUrl).isEqualTo("jdbc:postgresql://only:5432/db");
        assertThat(member.primaryConfig().maximumPoolSize).isEqualTo(10);
        assertThat(member.secondaryConfig()).isEmpty();
        assertThat(member.configFor("anything")).isEmpty();
    }

    @Test
    void should_throw_clear_error_when_member_omits_primary_config() {
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

        assertThatThrownBy(() -> MAPPER.readValue(yaml, ShardingConfig.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("primaryConfig");
    }

    @Test
    void should_fail_fast_on_unknown_property() {
        var yaml = """
                defaultShard: { group: 0, member: 0 }
                groups:
                  - group: 0
                    name: typo
                    memmbers:
                      - member: 0
                """;

        assertThatThrownBy(() -> MAPPER.readValue(yaml, ShardingConfig.class))
                .isInstanceOf(MismatchedInputException.class)
                .hasMessageContaining("memmbers");
    }

    // -- unknownShardPolicy, the one setting an operator reaches for after a misrouted write ------

    @Test
    void should_bind_unknown_shard_policy_from_yaml_whatever_the_spelling() {
        for (var written : new String[] {"fail", "FAIL", "Fail"}) {
            var config = MAPPER.readValue(minimalYaml("unknownShardPolicy: " + written + "\n"), ShardingConfig.class);

            assertThat(config.unknownShardPolicy)
                    .describedAs("unknownShardPolicy: %s", written)
                    .isEqualTo(UnknownShardPolicy.FAIL);
        }
        for (var written : new String[] {"useDefaultShard", "use_default_shard", "USE-DEFAULT-SHARD"}) {
            var config = MAPPER.readValue(minimalYaml("unknownShardPolicy: " + written + "\n"), ShardingConfig.class);

            assertThat(config.unknownShardPolicy)
                    .describedAs("unknownShardPolicy: %s", written)
                    .isEqualTo(UnknownShardPolicy.USE_DEFAULT_SHARD);
        }
    }

    @Test
    void should_use_default_shard_when_yaml_omits_the_policy() {
        var config = MAPPER.readValue(minimalYaml(""), ShardingConfig.class);

        assertThat(config.unknownShardPolicy).isEqualTo(UnknownShardPolicy.USE_DEFAULT_SHARD);
    }

    @Test
    void should_refuse_a_policy_value_that_names_neither() {
        assertThatThrownBy(() -> MAPPER.readValue(minimalYaml("unknownShardPolicy: shrug\n"), ShardingConfig.class))
                .hasMessageContaining("unknownShardPolicy must be 'useDefaultShard' or 'fail'");
    }

    private static String minimalYaml(String extra) {
        return extra + """
                defaultShard:
                  group: 0
                  member: 0
                groups:
                  - group: 0
                    name: global
                    members:
                      - member: 0
                        name: global
                        configs:
                          primaryConfig:
                            jdbcUrl: jdbc:postgresql://primary:5432/db
                            username: app
                            password: secret
                """;
    }
}
