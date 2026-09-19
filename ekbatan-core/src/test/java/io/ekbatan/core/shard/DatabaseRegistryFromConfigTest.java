package io.ekbatan.core.shard;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.config.ShardGroupConfig.Builder.shardGroupConfig;
import static io.ekbatan.core.config.ShardMemberConfig.Builder.shardMemberConfig;
import static io.ekbatan.core.config.ShardingConfig.Builder.shardingConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.config.DataSourceConfig;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;

class DatabaseRegistryFromConfigTest {

    private static final DataSourceConfig PG_CONFIG_G0_M0 = dataSourceConfig()
            .jdbcUrl("jdbc:postgresql://host-g0-m0:5432/db")
            .username("user")
            .password("pass")
            .maximumPoolSize(5)
            .build();

    private static final DataSourceConfig PG_CONFIG_G0_M1 = dataSourceConfig()
            .jdbcUrl("jdbc:postgresql://host-g0-m1:5432/db")
            .username("user")
            .password("pass")
            .maximumPoolSize(5)
            .build();

    private static final DataSourceConfig PG_CONFIG_G1_M0 = dataSourceConfig()
            .jdbcUrl("jdbc:postgresql://host-g1-m0:5432/db")
            .username("user")
            .password("pass")
            .maximumPoolSize(5)
            .build();

    @Test
    void should_create_registry_with_two_shards_across_two_groups() {
        // GIVEN
        var config = shardingConfig()
                .defaultShard(ShardIdentifier.of(0, 0))
                .withGroup(shardGroupConfig()
                        .group(0)
                        .name("global")
                        .withMember(shardMemberConfig()
                                .member(0)
                                .name("default")
                                .primaryConfig(PG_CONFIG_G0_M0)
                                .secondaryConfig(PG_CONFIG_G0_M0)
                                .build())
                        .build())
                .withGroup(shardGroupConfig()
                        .group(1)
                        .name("mexico")
                        .withMember(shardMemberConfig()
                                .member(0)
                                .name("mexico-1")
                                .primaryConfig(PG_CONFIG_G1_M0)
                                .secondaryConfig(PG_CONFIG_G1_M0)
                                .build())
                        .build())
                .build();

        // WHEN
        var registry = DatabaseRegistry.fromConfig(config);

        // THEN
        assertThat(registry.defaultShard).isEqualTo(ShardIdentifier.of(0, 0));
        assertThat(registry.primary).hasSize(2);
        assertThat(registry.secondary).hasSize(2);
        assertThat(registry.transactionManager(ShardIdentifier.of(0, 0))).isNotNull();
        assertThat(registry.transactionManager(ShardIdentifier.of(1, 0))).isNotNull();
        assertThat(registry.transactionManager(ShardIdentifier.of(0, 0)).dialect)
                .isEqualTo(SQLDialect.POSTGRES);
    }

    @Test
    void should_create_registry_with_single_shard() {
        // GIVEN
        var config = shardingConfig()
                .defaultShard(ShardIdentifier.of(0, 0))
                .withGroup(shardGroupConfig()
                        .group(0)
                        .name("default")
                        .withMember(shardMemberConfig()
                                .member(0)
                                .primaryConfig(PG_CONFIG_G0_M0)
                                .secondaryConfig(PG_CONFIG_G0_M0)
                                .build())
                        .build())
                .build();

        // WHEN
        var registry = DatabaseRegistry.fromConfig(config);

        // THEN
        assertThat(registry.primary).hasSize(1);
        assertThat(registry.defaultTransactionManager()).isNotNull();
    }

    @Test
    void should_create_registry_with_multiple_members_in_one_group() {
        // GIVEN
        var config = shardingConfig()
                .defaultShard(ShardIdentifier.of(0, 0))
                .withGroup(shardGroupConfig()
                        .group(0)
                        .name("global")
                        .withMember(shardMemberConfig()
                                .member(0)
                                .primaryConfig(PG_CONFIG_G0_M0)
                                .secondaryConfig(PG_CONFIG_G0_M0)
                                .build())
                        .withMember(shardMemberConfig()
                                .member(1)
                                .primaryConfig(PG_CONFIG_G0_M1)
                                .secondaryConfig(PG_CONFIG_G0_M1)
                                .build())
                        .build())
                .build();

        // WHEN
        var registry = DatabaseRegistry.fromConfig(config);

        // THEN
        assertThat(registry.primary).hasSize(2);
        assertThat(registry.transactionManager(ShardIdentifier.of(0, 0)))
                .isNotSameAs(registry.transactionManager(ShardIdentifier.of(0, 1)));
    }

    @Test
    void should_derive_dialect_from_member_config() {
        // GIVEN
        var config = shardingConfig()
                .defaultShard(ShardIdentifier.of(0, 0))
                .withGroup(shardGroupConfig()
                        .group(0)
                        .name("postgres-group")
                        .withMember(shardMemberConfig()
                                .member(0)
                                .primaryConfig(PG_CONFIG_G0_M0)
                                .secondaryConfig(PG_CONFIG_G0_M0)
                                .build())
                        .build())
                .build();

        // WHEN
        var registry = DatabaseRegistry.fromConfig(config);

        // THEN - dialect derived from JDBC URL
        assertThat(registry.transactionManager(ShardIdentifier.of(0, 0)).dialect)
                .isEqualTo(SQLDialect.POSTGRES);
    }

    @Test
    void should_fail_when_default_shard_not_in_config() {
        // GIVEN
        var config = shardingConfig()
                .defaultShard(ShardIdentifier.of(9, 9))
                .withGroup(shardGroupConfig()
                        .group(0)
                        .name("global")
                        .withMember(shardMemberConfig()
                                .member(0)
                                .primaryConfig(PG_CONFIG_G0_M0)
                                .secondaryConfig(PG_CONFIG_G0_M0)
                                .build())
                        .build())
                .build();

        // WHEN / THEN
        assertThatThrownBy(() -> DatabaseRegistry.fromConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultShard must reference a registered database");
    }

    @Test
    void should_set_default_transaction_manager_correctly() {
        // GIVEN
        var config = shardingConfig()
                .defaultShard(ShardIdentifier.of(1, 0))
                .withGroup(shardGroupConfig()
                        .group(0)
                        .name("global")
                        .withMember(shardMemberConfig()
                                .member(0)
                                .primaryConfig(PG_CONFIG_G0_M0)
                                .secondaryConfig(PG_CONFIG_G0_M0)
                                .build())
                        .build())
                .withGroup(shardGroupConfig()
                        .group(1)
                        .name("mexico")
                        .withMember(shardMemberConfig()
                                .member(0)
                                .primaryConfig(PG_CONFIG_G1_M0)
                                .secondaryConfig(PG_CONFIG_G1_M0)
                                .build())
                        .build())
                .build();

        // WHEN
        var registry = DatabaseRegistry.fromConfig(config);

        // THEN - default points to (1, 0), not (0, 0)
        assertThat(registry.defaultShard).isEqualTo(ShardIdentifier.of(1, 0));
        assertThat(registry.defaultTransactionManager())
                .isSameAs(registry.transactionManager(ShardIdentifier.of(1, 0)));
    }

    // -- an unregistered shard says so, and can be made to refuse ---------------------------

    @Test
    void should_fall_back_to_the_default_for_an_unregistered_shard() {
        // GIVEN - two shards, and an identifier naming neither
        var registry = DatabaseRegistry.fromConfig(twoShards(null));

        // WHEN / THEN - the historical behaviour, unchanged, and still the default policy
        assertThat(registry.unknownShardPolicy).isEqualTo(UnknownShardPolicy.USE_DEFAULT_SHARD);
        assertThat(registry.effectiveShard(ShardIdentifier.of(9, 9))).isEqualTo(ShardIdentifier.of(0, 0));
        assertThat(registry.effectiveShard(ShardIdentifier.of(1, 0))).isEqualTo(ShardIdentifier.of(1, 0));
    }

    @Test
    void should_fail_on_an_unregistered_shard_when_configured_to() {
        // GIVEN - a deployment that holds the whole layout, so an unregistered shard is a bug
        var registry = DatabaseRegistry.fromConfig(twoShards(UnknownShardPolicy.FAIL));

        // WHEN / THEN
        assertThatThrownBy(() -> registry.effectiveShard(ShardIdentifier.of(9, 9)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not registered and unknownShardPolicy is FAIL");

        // AND - a registered shard is unaffected
        assertThat(registry.effectiveShard(ShardIdentifier.of(1, 0))).isEqualTo(ShardIdentifier.of(1, 0));
    }

    @Test
    void should_use_default_shard_when_the_config_does_not_name_a_policy() {
        assertThat(twoShards(null).unknownShardPolicy).isEqualTo(UnknownShardPolicy.USE_DEFAULT_SHARD);
    }

    private static io.ekbatan.core.config.ShardingConfig twoShards(UnknownShardPolicy policy) {
        var builder = shardingConfig()
                .defaultShard(ShardIdentifier.of(0, 0))
                .withGroup(shardGroupConfig()
                        .group(0)
                        .name("global")
                        .withMember(shardMemberConfig()
                                .member(0)
                                .name("global")
                                .primaryConfig(PG_CONFIG_G0_M0)
                                .build())
                        .build())
                .withGroup(shardGroupConfig()
                        .group(1)
                        .name("mexico")
                        .withMember(shardMemberConfig()
                                .member(0)
                                .name("mexico")
                                .primaryConfig(PG_CONFIG_G1_M0)
                                .build())
                        .build());
        if (policy != null) {
            builder = builder.unknownShardPolicy(policy);
        }
        return builder.build();
    }
}
