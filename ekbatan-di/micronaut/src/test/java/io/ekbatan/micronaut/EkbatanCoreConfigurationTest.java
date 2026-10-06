package io.ekbatan.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.ekbatan.core.config.ShardingConfig;
import io.ekbatan.distributedjobs.config.JobsConfig;
import io.ekbatan.events.localeventhandler.config.LocalEventHandlerConfig;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * In-process tests for {@link EkbatanCoreConfiguration#ekbatanShardingConfig} - the binding glue
 * that turns Micronaut's flat {@code ekbatan.sharding.*} property output into a
 * {@link ShardingConfig} via Jackson's {@code JavaPropsMapper}.
 *
 * <p>No Docker, no DB - these complement the full end-to-end test in
 * {@code ekbatan-integration-tests-di-micronaut} by exhaustively covering branches that test
 * doesn't reach: casing variants, optional-present vs optional-absent, multi-named-configs,
 * type coercion, and the error-path contracts.
 *
 * <p>The helper {@link #withEnv} builds an {@link ApplicationContext} but only starts the
 * {@link Environment} - never the bean container. That keeps the eager
 * {@code EkbatanDistributedJobsConfiguration.Lifecycle} listener (which would otherwise pull in
 * the whole {@code ShardingConfig -> DatabaseRegistry -> DataSource} chain at startup) from firing
 * and trying to open real database pools.
 */
class EkbatanCoreConfigurationTest {

    /** Minimal single-shard properties in camelCase - the shape Micronaut emits when source YAML is camelCase. */
    private static Map<String, Object> minimalCamelCase() {
        var p = new LinkedHashMap<String, Object>();
        p.put("ekbatan.sharding.defaultShard.group", "0");
        p.put("ekbatan.sharding.defaultShard.member", "0");
        p.put("ekbatan.sharding.groups[0].group", "0");
        p.put("ekbatan.sharding.groups[0].name", "default");
        p.put("ekbatan.sharding.groups[0].members[0].member", "0");
        p.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl", "jdbc:postgresql://h/db");
        p.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username", "u");
        p.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password", "p");
        return p;
    }

    /**
     * Runs {@code body} against a Micronaut {@link Environment} fed with {@code props}, without
     * starting the bean container. This is the key isolation trick - see the class-level comment.
     */
    private static void withEnv(Map<String, Object> props, Consumer<Environment> body) {
        var ctx = ApplicationContext.builder()
                .propertySources(PropertySource.of("test", props))
                .build();
        try {
            var env = ctx.getEnvironment();
            env.start();
            body.accept(env);
        } finally {
            ctx.close();
        }
    }

    private static ShardingConfig bind(Environment env) {
        return new EkbatanCoreConfiguration().ekbatanShardingConfig(env);
    }

    @Nested
    class Casing {

        @Test
        void bindsAllCamelCaseKeys() {
            withEnv(minimalCamelCase(), env -> {
                var cfg = bind(env);
                var primary = cfg.groups.get(0).members.get(0).configs.get("primaryConfig");
                assertThat(primary).isNotNull();
                assertThat(primary.jdbcUrl).isEqualTo("jdbc:postgresql://h/db");
                assertThat(primary.username).contains("u");
                assertThat(primary.password).contains("p");
            });
        }

        @Test
        void readsDriverSettingsUnderTheCamelCaseKey() {
            var p = minimalCamelCase();
            var prefix = "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.dataSourceProperties.";
            p.put(prefix + "ApplicationName", "orders");
            p.put(prefix + "ha.enableJMX", "true");
            p.put(prefix + "keyStorePassword", "k3y");
            withEnv(p, env -> assertThat(bind(env).groups.get(0).members.get(0).primaryConfig().dataSourceProperties)
                    .containsOnly(
                            Map.entry("ApplicationName", "orders"),
                            Map.entry("ha.enableJMX", "true"),
                            Map.entry("keyStorePassword", "k3y")));
        }

        @Test
        void keepsDriverSettingNamesExactlyAsWritten() {
            // The container key is normalised like any other; the driver setting names inside it
            // are not, since a driver reads them exactly as written - capitals, dots and all.
            var p = minimalCamelCase();
            var prefix = "ekbatan.sharding.groups[0].members[0].configs.primary-config.data-source-properties.";
            p.put(prefix + "keyStorePassword", "k3y");
            p.put(prefix + "ApplicationName", "orders");
            p.put(prefix + "ha.enableJMX", "true");
            p.put(prefix + "a", "1");
            p.put(prefix + "a.b", "2");
            p.put(prefix + "kms.region", "eu-west-1");
            p.put(prefix + "connectionAttributes", "team:orders,env:prod");
            withEnv(p, env -> assertThat(bind(env).groups.get(0).members.get(0).primaryConfig().dataSourceProperties)
                    .containsOnly(
                            Map.entry("keyStorePassword", "k3y"),
                            Map.entry("ApplicationName", "orders"),
                            Map.entry("ha.enableJMX", "true"),
                            Map.entry("a", "1"),
                            Map.entry("a.b", "2"),
                            Map.entry("kms.region", "eu-west-1"),
                            Map.entry("connectionAttributes", "team:orders,env:prod")));
        }

        @Test
        void keepsDriverSettingsWrittenInsideAYamlList() {
            // A YAML file's groups list reaches the raw view only as the whole structure - a list
            // of maps - not as flat keys; the driver settings inside it must still arrive exactly
            // as written, a placeholder in a value resolved.
            var p = minimalCamelCase();
            p.remove("ekbatan.sharding.groups[0].group");
            p.remove("ekbatan.sharding.groups[0].name");
            p.remove("ekbatan.sharding.groups[0].members[0].member");
            p.remove("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl");
            p.remove("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username");
            p.remove("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password");
            var settings = new LinkedHashMap<String, Object>();
            settings.put("ApplicationName", "orders");
            settings.put("ha.enableJMX", "true");
            settings.put("a", "1");
            settings.put("a.b", "2");
            settings.put("sslpassword", "${EKBATAN_TEST_KEY_PASSWORD:k3y-from-default}");
            var primary = new LinkedHashMap<String, Object>();
            primary.put("jdbc-url", "jdbc:postgresql://h/db");
            primary.put("username", "u");
            primary.put("data-source-properties", settings);
            var member = new LinkedHashMap<String, Object>();
            member.put("member", 0);
            member.put("configs", Map.of("primary-config", primary));
            var group = new LinkedHashMap<String, Object>();
            group.put("group", 0);
            group.put("name", "default");
            group.put("members", java.util.List.of(member));
            p.put("ekbatan.sharding.groups", java.util.List.of(group));

            withEnv(p, env -> assertThat(bind(env).groups.get(0).members.get(0).primaryConfig().dataSourceProperties)
                    .containsOnly(
                            Map.entry("ApplicationName", "orders"),
                            Map.entry("ha.enableJMX", "true"),
                            Map.entry("a", "1"),
                            Map.entry("a.b", "2"),
                            Map.entry("sslpassword", "k3y-from-default")));
        }

        @Test
        void bindsAllKebabCaseKeys() {
            // Micronaut's idiomatic style. StringConvention.CAMEL_CASE must normalise every path
            // segment so the resulting properties match the Jackson Builder method names - the
            // configs-map key in particular must come out as "primaryConfig", not "primary-config".
            var p = new LinkedHashMap<String, Object>();
            p.put("ekbatan.sharding.default-shard.group", "0");
            p.put("ekbatan.sharding.default-shard.member", "0");
            p.put("ekbatan.sharding.groups[0].group", "0");
            p.put("ekbatan.sharding.groups[0].name", "default");
            p.put("ekbatan.sharding.groups[0].members[0].member", "0");
            p.put("ekbatan.sharding.groups[0].members[0].configs.primary-config.jdbc-url", "jdbc:postgresql://h/db");
            p.put("ekbatan.sharding.groups[0].members[0].configs.primary-config.username", "u");
            p.put("ekbatan.sharding.groups[0].members[0].configs.primary-config.password", "p");

            withEnv(p, env -> {
                var cfg = bind(env);
                assertThat(cfg.groups.get(0).members.get(0).configs)
                        .containsKey("primaryConfig")
                        .doesNotContainKey("primary-config");
                assertThat(cfg.groups.get(0).members.get(0).configs.get("primaryConfig").jdbcUrl)
                        .isEqualTo("jdbc:postgresql://h/db");
            });
        }

        @Test
        void bindsMixedCamelAndKebabCaseInSameTree() {
            // Mixing styles inside one YAML is rare but legal - both spellings must coalesce
            // into the same canonical camelCase property without either branch shadowing the
            // other.
            var p = new LinkedHashMap<String, Object>();
            p.put("ekbatan.sharding.defaultShard.group", "0");
            p.put("ekbatan.sharding.defaultShard.member", "0");
            p.put("ekbatan.sharding.groups[0].group", "0");
            p.put("ekbatan.sharding.groups[0].name", "default");
            p.put("ekbatan.sharding.groups[0].members[0].member", "0");
            // Mixed: camelCase configs-map key, kebab-case leaf properties.
            p.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbc-url", "jdbc:postgresql://h/db");
            p.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username", "u");
            p.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password", "p");

            withEnv(p, env -> {
                var cfg = bind(env);
                assertThat(cfg.groups.get(0).members.get(0).configs.get("primaryConfig").jdbcUrl)
                        .isEqualTo("jdbc:postgresql://h/db");
            });
        }
    }

    @Nested
    class OptionalFields {

        @Test
        void optionalDataSourceFields_areEmpty_whenAbsent() {
            withEnv(minimalCamelCase(), env -> {
                var primary = bind(env).groups.get(0).members.get(0).configs.get("primaryConfig");
                assertThat(primary.driverClassName).isEmpty();
                assertThat(primary.minimumIdle).isEmpty();
                assertThat(primary.idleTimeout).isEmpty();
                assertThat(primary.leakDetectionThreshold).isEmpty();
            });
        }

        @Test
        void optionalDataSourceFields_areBound_whenPresent() {
            var p = minimalCamelCase();
            var prefix = "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.";
            p.put(prefix + "driverClassName", "org.postgresql.Driver");
            p.put(prefix + "minimumIdle", "2");
            p.put(prefix + "idleTimeout", "600000");
            p.put(prefix + "leakDetectionThreshold", "60000");

            withEnv(p, env -> {
                var primary = bind(env).groups.get(0).members.get(0).configs.get("primaryConfig");
                assertThat(primary.driverClassName).contains("org.postgresql.Driver");
                assertThat(primary.minimumIdle).contains(2);
                assertThat(primary.idleTimeout).contains(600_000L);
                assertThat(primary.leakDetectionThreshold).contains(60_000L);
            });
        }

        @Test
        void optionalMemberName_isEmpty_whenAbsent() {
            withEnv(minimalCamelCase(), env -> assertThat(
                            bind(env).groups.get(0).members.get(0).name)
                    .isEmpty());
        }

        @Test
        void optionalMemberName_isBound_whenPresent() {
            var p = minimalCamelCase();
            p.put("ekbatan.sharding.groups[0].members[0].name", "primary-eu-west-1");
            withEnv(p, env -> assertThat(bind(env).groups.get(0).members.get(0).name)
                    .contains("primary-eu-west-1"));
        }

        @Test
        void optionalDataSourceFields_bindUnderKebabCaseKeys() {
            // Cross-cutting check: the optional-Long/Integer fields also normalise from kebab.
            var p = minimalCamelCase();
            var prefix = "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.";
            p.put(prefix + "driver-class-name", "org.postgresql.Driver");
            p.put(prefix + "minimum-idle", "2");
            p.put(prefix + "idle-timeout", "600000");
            p.put(prefix + "leak-detection-threshold", "60000");

            withEnv(p, env -> {
                var primary = bind(env).groups.get(0).members.get(0).configs.get("primaryConfig");
                assertThat(primary.driverClassName).contains("org.postgresql.Driver");
                assertThat(primary.minimumIdle).contains(2);
                assertThat(primary.idleTimeout).contains(600_000L);
                assertThat(primary.leakDetectionThreshold).contains(60_000L);
            });
        }
    }

    @Nested
    class Topology {

        @Test
        void bindsMultipleNamedConfigsForOneMember() {
            // A member can carry several named DataSourceConfigs - e.g. "primaryConfig" for app
            // queries and "jobsConfig" for the distributed-jobs scheduler. Both must arrive as
            // distinct keys in the configs map.
            var p = minimalCamelCase();
            var prefix = "ekbatan.sharding.groups[0].members[0].configs.jobsConfig.";
            p.put(prefix + "jdbcUrl", "jdbc:postgresql://h/db_jobs");
            p.put(prefix + "username", "uj");
            p.put(prefix + "password", "pj");

            withEnv(p, env -> {
                var configs = bind(env).groups.get(0).members.get(0).configs;
                assertThat(configs).containsOnlyKeys("primaryConfig", "jobsConfig");
                assertThat(configs.get("primaryConfig").jdbcUrl).isEqualTo("jdbc:postgresql://h/db");
                assertThat(configs.get("jobsConfig").jdbcUrl).isEqualTo("jdbc:postgresql://h/db_jobs");
                assertThat(configs.get("jobsConfig").username).contains("uj");
            });
        }

        @Test
        void bindsMultiGroupMultiMemberTopology() {
            var p = new LinkedHashMap<String, Object>();
            p.put("ekbatan.sharding.defaultShard.group", "1");
            p.put("ekbatan.sharding.defaultShard.member", "0");
            for (int g = 0; g < 2; g++) {
                p.put("ekbatan.sharding.groups[" + g + "].group", String.valueOf(g));
                p.put("ekbatan.sharding.groups[" + g + "].name", "g" + g);
                for (int m = 0; m < 2; m++) {
                    var prefix = "ekbatan.sharding.groups[" + g + "].members[" + m + "].";
                    p.put(prefix + "member", String.valueOf(m));
                    p.put(prefix + "configs.primaryConfig.jdbcUrl", "jdbc:postgresql://h/g" + g + "m" + m);
                    p.put(prefix + "configs.primaryConfig.username", "u");
                    p.put(prefix + "configs.primaryConfig.password", "p");
                }
            }

            withEnv(p, env -> {
                var cfg = bind(env);
                assertThat(cfg.defaultShard.group).isEqualTo(1);
                assertThat(cfg.defaultShard.member).isZero();
                assertThat(cfg.groups).hasSize(2);
                assertThat(cfg.groups.get(0).members).hasSize(2);
                assertThat(cfg.groups.get(1).members).hasSize(2);
                assertThat(cfg.groups.get(0).members.get(0).configs.get("primaryConfig").jdbcUrl)
                        .isEqualTo("jdbc:postgresql://h/g0m0");
                assertThat(cfg.groups.get(1).members.get(1).configs.get("primaryConfig").jdbcUrl)
                        .isEqualTo("jdbc:postgresql://h/g1m1");
            });
        }

        @Test
        void bindsArrayIndicesBeyondZero() {
            // Defensive check against any off-by-one in JavaPropsMapper's [idx] array marker.
            var p = new LinkedHashMap<String, Object>();
            p.put("ekbatan.sharding.defaultShard.group", "0");
            p.put("ekbatan.sharding.defaultShard.member", "2");
            p.put("ekbatan.sharding.groups[0].group", "0");
            p.put("ekbatan.sharding.groups[0].name", "default");
            for (int m = 0; m < 3; m++) {
                var prefix = "ekbatan.sharding.groups[0].members[" + m + "].";
                p.put(prefix + "member", String.valueOf(m));
                p.put(prefix + "configs.primaryConfig.jdbcUrl", "jdbc:postgresql://h/m" + m);
                p.put(prefix + "configs.primaryConfig.username", "u");
                p.put(prefix + "configs.primaryConfig.password", "p");
            }

            withEnv(p, env -> {
                var cfg = bind(env);
                assertThat(cfg.groups.get(0).members).hasSize(3);
                assertThat(cfg.groups.get(0).members.get(2).member).isEqualTo(2);
                assertThat(cfg.groups.get(0).members.get(2).configs.get("primaryConfig").jdbcUrl)
                        .isEqualTo("jdbc:postgresql://h/m2");
            });
        }
    }

    @Nested
    class TypeCoercion {

        @Test
        void coercesNumericStrings() {
            // Micronaut hands us string values; Jackson must coerce them to int / Long.
            var p = minimalCamelCase();
            p.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.maximumPoolSize", "17");

            withEnv(p, env -> assertThat(
                            bind(env).groups.get(0).members.get(0).configs.get("primaryConfig").maximumPoolSize)
                    .isEqualTo(17));
        }
    }

    @Nested
    class ErrorPaths {

        @Test
        void throwsWhenEkbatanShardingIsAbsent() {
            withEnv(Map.of(), env -> assertThatThrownBy(() -> bind(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ekbatan.sharding")
                    .hasMessageContaining("primaryConfig"));
        }

        @Test
        void aUrlCarryingAPasswordFailsWithoutEverPrintingIt() {
            // the whole printed failure - what a startup log shows - names the problem, never the value
            var p = minimalCamelCase();
            p.put(
                    "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl",
                    "jdbc:postgresql://h/db?password=Zq7-secret-in-the-url");

            withEnv(p, env -> {
                var failure = catchThrowable(() -> bind(env));
                var printed = new StringWriter();
                failure.printStackTrace(new PrintWriter(printed));

                assertThat(failure).isInstanceOf(IllegalStateException.class);
                assertThat(printed.toString())
                        .contains("carries the login password")
                        .doesNotContain("Zq7-secret-in-the-url");
            });
        }

        @Test
        void failsOnUnknownPropertySurfacesTypos() {
            // FAIL_ON_UNKNOWN_PROPERTIES on the private mapper surfaces typos at startup
            // rather than silently dropping them - that's the whole point of the strict mapper.
            var p = minimalCamelCase();
            p.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.notARealField", "x");

            withEnv(p, env -> assertThatThrownBy(() -> bind(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to bind 'ekbatan.sharding'"));
        }
    }

    @Nested
    class JobsConfigBinding {

        private JobsConfig bindJobs(Environment env) {
            return new EkbatanCoreConfiguration().ekbatanJobsConfig(env);
        }

        @Test
        void returnsDefaults_whenSubtreeAbsent() {
            withEnv(Map.of(), env -> {
                var cfg = bindJobs(env);
                assertThat(cfg.pollingInterval).isEmpty();
                assertThat(cfg.heartbeatInterval).isEmpty();
                assertThat(cfg.shutdownMaxWait).isEmpty();
            });
        }

        @Test
        void bindsKebabCaseKeysWithIso8601Durations() {
            withEnv(
                    Map.of(
                            "ekbatan.jobs.polling-interval", "PT5S",
                            "ekbatan.jobs.heartbeat-interval", "PT3S",
                            "ekbatan.jobs.shutdown-max-wait", "PT30S"),
                    env -> {
                        var cfg = bindJobs(env);
                        assertThat(cfg.pollingInterval).contains(java.time.Duration.ofSeconds(5));
                        assertThat(cfg.heartbeatInterval).contains(java.time.Duration.ofSeconds(3));
                        assertThat(cfg.shutdownMaxWait).contains(java.time.Duration.ofSeconds(30));
                    });
        }

        @Test
        void bindsCamelCaseKeys() {
            withEnv(
                    Map.of(
                            "ekbatan.jobs.pollingInterval", "PT5S",
                            "ekbatan.jobs.heartbeatInterval", "PT3S",
                            "ekbatan.jobs.shutdownMaxWait", "PT30S"),
                    env -> {
                        var cfg = bindJobs(env);
                        assertThat(cfg.pollingInterval).contains(java.time.Duration.ofSeconds(5));
                        assertThat(cfg.heartbeatInterval).contains(java.time.Duration.ofSeconds(3));
                        assertThat(cfg.shutdownMaxWait).contains(java.time.Duration.ofSeconds(30));
                    });
        }

        @Test
        void bindsMixedKebabAndCamelCaseKeys() {
            // Both forms in the same env - Micronaut's StringConvention.CAMEL_CASE handles the
            // normalisation before Jackson sees the keys, just like the kebabToCamel path the
            // other DIs use.
            withEnv(
                    Map.of(
                            "ekbatan.jobs.polling-interval", "PT5S", // kebab
                            "ekbatan.jobs.heartbeatInterval", "PT3S", // camel
                            "ekbatan.jobs.shutdown-max-wait", "PT30S"), // kebab
                    env -> {
                        var cfg = bindJobs(env);
                        assertThat(cfg.pollingInterval).contains(java.time.Duration.ofSeconds(5));
                        assertThat(cfg.heartbeatInterval).contains(java.time.Duration.ofSeconds(3));
                        assertThat(cfg.shutdownMaxWait).contains(java.time.Duration.ofSeconds(30));
                    });
        }

        @Test
        void failsOnUnknownProperty() {
            withEnv(Map.of("ekbatan.jobs.not-a-real-field", "x"), env -> assertThatThrownBy(() -> bindJobs(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to bind 'ekbatan.jobs'"));
        }
    }

    @Nested
    class LocalEventHandlerConfigBinding {

        private LocalEventHandlerConfig bindLeh(Environment env) {
            return new EkbatanCoreConfiguration().ekbatanLocalEventHandlerConfig(env);
        }

        @Test
        void returnsDefaults_whenSubtreeAbsent() {
            withEnv(Map.of(), env -> {
                var cfg = bindLeh(env);
                assertThat(cfg.fanoutPollDelay).isEmpty();
                assertThat(cfg.handling.enabled).isFalse();
            });
        }

        @Test
        void bindsFlatAndNestedKnobs() {
            withEnv(
                    Map.of(
                            "ekbatan.local-event-handler.fanout-poll-delay", "PT0.2S",
                            "ekbatan.local-event-handler.fanout-batch-size", "100",
                            "ekbatan.local-event-handler.handling-poll-delay", "PT0.15S",
                            "ekbatan.local-event-handler.handling.enabled", "true"),
                    env -> {
                        var cfg = bindLeh(env);
                        assertThat(cfg.fanoutPollDelay).contains(java.time.Duration.ofMillis(200));
                        assertThat(cfg.fanoutBatchSize).contains(100);
                        assertThat(cfg.handlingPollDelay).contains(java.time.Duration.ofMillis(150));
                        assertThat(cfg.handling.enabled).isTrue();
                    });
        }

        @Test
        void bindsCamelCaseKeys() {
            withEnv(
                    Map.of(
                            "ekbatan.local-event-handler.fanoutPollDelay", "PT0.2S",
                            "ekbatan.local-event-handler.fanoutBatchSize", "100",
                            "ekbatan.local-event-handler.handlingPollDelay", "PT0.15S",
                            "ekbatan.local-event-handler.handling.enabled", "true"),
                    env -> {
                        var cfg = bindLeh(env);
                        assertThat(cfg.fanoutPollDelay).contains(java.time.Duration.ofMillis(200));
                        assertThat(cfg.fanoutBatchSize).contains(100);
                        assertThat(cfg.handlingPollDelay).contains(java.time.Duration.ofMillis(150));
                        assertThat(cfg.handling.enabled).isTrue();
                    });
        }

        @Test
        void bindsCamelCasedParentSegment() {
            // Micronaut's StringConvention.CAMEL_CASE on the getProperties call normalises both
            // the source keys and the prefix; this pins that behaviour for the parent segment.
            withEnv(
                    Map.of(
                            "ekbatan.localEventHandler.fanoutPollDelay", "PT0.2S",
                            "ekbatan.localEventHandler.fanoutBatchSize", "100",
                            "ekbatan.localEventHandler.handlingPollDelay", "PT0.15S",
                            "ekbatan.localEventHandler.handling.enabled", "true"),
                    env -> {
                        var cfg = bindLeh(env);
                        assertThat(cfg.fanoutPollDelay).contains(java.time.Duration.ofMillis(200));
                        assertThat(cfg.fanoutBatchSize).contains(100);
                        assertThat(cfg.handlingPollDelay).contains(java.time.Duration.ofMillis(150));
                        assertThat(cfg.handling.enabled).isTrue();
                    });
        }

        @Test
        void bindsCamelCasedParentSegmentWithKebabLeaves() {
            withEnv(
                    Map.of(
                            "ekbatan.localEventHandler.fanout-poll-delay", "PT0.2S",
                            "ekbatan.localEventHandler.fanout-batch-size", "100",
                            "ekbatan.localEventHandler.handling-poll-delay", "PT0.15S",
                            "ekbatan.localEventHandler.handling.enabled", "true"),
                    env -> {
                        var cfg = bindLeh(env);
                        assertThat(cfg.fanoutPollDelay).contains(java.time.Duration.ofMillis(200));
                        assertThat(cfg.fanoutBatchSize).contains(100);
                        assertThat(cfg.handlingPollDelay).contains(java.time.Duration.ofMillis(150));
                        assertThat(cfg.handling.enabled).isTrue();
                    });
        }

        @Test
        void failsOnUnknownProperty() {
            withEnv(Map.of("ekbatan.local-event-handler.not-a-real-field", "x"), env -> assertThatThrownBy(
                            () -> bindLeh(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to bind 'ekbatan.local-event-handler'"));
        }
    }
}
