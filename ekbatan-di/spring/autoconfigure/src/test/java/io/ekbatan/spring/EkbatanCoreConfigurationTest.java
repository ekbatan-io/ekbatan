package io.ekbatan.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.action.ActionRegistry;
import io.ekbatan.core.config.ShardingConfig;
import io.ekbatan.core.repository.RepositoryRegistry;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.distributedjobs.config.JobsConfig;
import io.ekbatan.events.localeventhandler.config.LocalEventHandlerConfig;
import io.ekbatan.spring.fixture.FixtureAction;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Map;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Component test for the auto-config wiring up to {@link ShardingConfig} and {@link ActionExecutor}
 * - without a real database. We mock the {@link DatabaseRegistry} so {@link ActionExecutor}
 * builds without opening Hikari pools. End-to-end wiring with a real Postgres lives in
 * {@code ekbatan-integration-tests}.
 *
 * <p>Disabled on native image: slice tests use Spring's {@code ApplicationContextRunner}, which
 * relies on {@code MetadataReader} reading {@code .class} files as classpath resources at runtime.
 * Native binaries don't preserve {@code .class} files (they're consumed at AOT time), so any
 * {@code AutoConfigurations.of(...).run(...)} call fails with {@code FileNotFoundException}.
 * End-to-end native coverage of these auto-configs lives in {@code ekbatan-integration-tests:di:spring-boot-starter}'s
 * full {@code @SpringBootTest}, where Spring AOT pre-evaluates the auto-config conditions at build time.
 */
@DisabledInNativeImage
class EkbatanCoreConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, EkbatanCoreConfiguration.class))
            .withUserConfiguration(MockDatabaseRegistryConfig.class);

    @Test
    void aUrlCarryingAPasswordFailsTheContextWithoutEverPrintingIt() {
        contextRunner
                .withPropertyValues(
                        "ekbatan.sharding.defaultShard.group=0",
                        "ekbatan.sharding.defaultShard.member=0",
                        "ekbatan.sharding.groups[0].group=0",
                        "ekbatan.sharding.groups[0].members[0].member=0",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl="
                                + "jdbc:postgresql://primary:5432/db?password=Zq7-secret-in-the-url",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username=app")
                .run(ctx -> {
                    // the whole printed failure - what a startup log shows - names the problem,
                    // never the value
                    assertThat(ctx).hasFailed();
                    var printed = new StringWriter();
                    ctx.getStartupFailure().printStackTrace(new PrintWriter(printed));
                    assertThat(printed.toString())
                            .contains("carries the login password")
                            .doesNotContain("Zq7-secret-in-the-url");
                });
    }

    @Test
    void shouldBuildShardingConfigBeanFromProperties() {
        contextRunner
                .withPropertyValues(
                        "ekbatan.sharding.defaultShard.group=0",
                        "ekbatan.sharding.defaultShard.member=0",
                        "ekbatan.sharding.groups[0].group=0",
                        "ekbatan.sharding.groups[0].name=global",
                        "ekbatan.sharding.groups[0].members[0].member=0",
                        "ekbatan.sharding.groups[0].members[0].name=global-eu-1",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl=jdbc:postgresql://primary:5432/db",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username=app",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password=secret",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.maximumPoolSize=20")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(ShardingConfig.class);
                    assertThat(ctx).hasSingleBean(ActionRegistry.class);
                    assertThat(ctx).hasSingleBean(RepositoryRegistry.class);
                    assertThat(ctx).hasSingleBean(ActionExecutor.class);

                    var cfg = ctx.getBean(ShardingConfig.class);
                    assertThat(cfg.groups).hasSize(1);
                    var member = cfg.groups.get(0).members.get(0);
                    assertThat(member.primaryConfig().jdbcUrl).isEqualTo("jdbc:postgresql://primary:5432/db");
                    assertThat(member.primaryConfig().maximumPoolSize).isEqualTo(20);
                    assertThat(member.primaryConfig().dialect).isEqualTo(SQLDialect.POSTGRES);
                });
    }

    @Test
    void shouldAcceptAllKebabCaseShardingKeys() {
        // Same shape as shouldBuildShardingConfigBeanFromProperties but every segment is kebab,
        // including the configs-map key. PropertyKeyNormalizer collapses each segment so the
        // bound config is bit-identical to the all-camelCase fixture.
        contextRunner
                .withPropertyValues(
                        "ekbatan.sharding.default-shard.group=0",
                        "ekbatan.sharding.default-shard.member=0",
                        "ekbatan.sharding.groups[0].group=0",
                        "ekbatan.sharding.groups[0].name=global",
                        "ekbatan.sharding.groups[0].members[0].member=0",
                        "ekbatan.sharding.groups[0].members[0].name=global-eu-1",
                        "ekbatan.sharding.groups[0].members[0].configs.primary-config.jdbc-url=jdbc:postgresql://primary:5432/db",
                        "ekbatan.sharding.groups[0].members[0].configs.primary-config.username=app",
                        "ekbatan.sharding.groups[0].members[0].configs.primary-config.password=secret",
                        "ekbatan.sharding.groups[0].members[0].configs.primary-config.maximum-pool-size=20")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var cfg = ctx.getBean(ShardingConfig.class);
                    assertThat(cfg.groups.get(0).members.get(0).configs)
                            .containsKey("primaryConfig")
                            .doesNotContainKey("primary-config");
                    var primary = cfg.groups.get(0).members.get(0).primaryConfig();
                    assertThat(primary.jdbcUrl).isEqualTo("jdbc:postgresql://primary:5432/db");
                    assertThat(primary.maximumPoolSize).isEqualTo(20);
                });
    }

    @Test
    void shouldReadDriverSettingsUnderTheCamelCaseKey() {
        var prefix = "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.dataSourceProperties.";
        contextRunner
                .withPropertyValues(
                        "ekbatan.sharding.defaultShard.group=0",
                        "ekbatan.sharding.defaultShard.member=0",
                        "ekbatan.sharding.groups[0].group=0",
                        "ekbatan.sharding.groups[0].name=global",
                        "ekbatan.sharding.groups[0].members[0].member=0",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl=jdbc:postgresql://primary:5432/db",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username=app",
                        prefix + "ApplicationName=orders",
                        prefix + "ha.enableJMX=true",
                        prefix + "keyStorePassword=k3y")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var primary = ctx.getBean(ShardingConfig.class)
                            .groups
                            .get(0)
                            .members
                            .get(0)
                            .primaryConfig();
                    assertThat(primary.dataSourceProperties)
                            .containsOnly(
                                    java.util.Map.entry("ApplicationName", "orders"),
                                    java.util.Map.entry("ha.enableJMX", "true"),
                                    java.util.Map.entry("keyStorePassword", "k3y"));
                });
    }

    @Test
    void shouldKeepDriverSettingNamesExactlyAsWritten() {
        // The container key is normalised like any other; the driver setting names inside it are
        // not, since a driver reads them exactly as written - capitals, dots and all.
        var prefix = "ekbatan.sharding.groups[0].members[0].configs.primary-config.data-source-properties.";
        contextRunner
                .withPropertyValues(
                        "ekbatan.sharding.default-shard.group=0",
                        "ekbatan.sharding.default-shard.member=0",
                        "ekbatan.sharding.groups[0].group=0",
                        "ekbatan.sharding.groups[0].name=global",
                        "ekbatan.sharding.groups[0].members[0].member=0",
                        "ekbatan.sharding.groups[0].members[0].configs.primary-config.jdbc-url=jdbc:postgresql://primary:5432/db",
                        "ekbatan.sharding.groups[0].members[0].configs.primary-config.username=app",
                        prefix + "keyStorePassword=k3y",
                        prefix + "ApplicationName=orders",
                        prefix + "ha.enableJMX=true",
                        prefix + "a=1",
                        prefix + "a.b=2",
                        prefix + "kms.region=eu-west-1",
                        prefix + "connectionAttributes=team:orders,env:prod",
                        // a placeholder, as the docs recommend for a key password
                        prefix + "sslpassword=${EKBATAN_TEST_KEY_PASSWORD:k3y-from-default}")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var primary = ctx.getBean(ShardingConfig.class)
                            .groups
                            .get(0)
                            .members
                            .get(0)
                            .primaryConfig();
                    assertThat(primary.dataSourceProperties)
                            .containsOnly(
                                    java.util.Map.entry("keyStorePassword", "k3y"),
                                    java.util.Map.entry("ApplicationName", "orders"),
                                    java.util.Map.entry("ha.enableJMX", "true"),
                                    java.util.Map.entry("a", "1"),
                                    java.util.Map.entry("a.b", "2"),
                                    java.util.Map.entry("kms.region", "eu-west-1"),
                                    java.util.Map.entry("connectionAttributes", "team:orders,env:prod"),
                                    java.util.Map.entry("sslpassword", "k3y-from-default"));
                    assertThat(primary.password).isEmpty();
                });
    }

    @Test
    void shouldAcceptMixedKebabAndCamelCaseShardingKeys() {
        // PropertyKeyNormalizer folds kebab segments to camelCase before Jackson sees them; user
        // can write either spelling - including for the configs-map key (primary-config vs
        // primaryConfig) - and the same bean falls out either way.
        contextRunner
                .withPropertyValues(
                        "ekbatan.sharding.default-shard.group=0", // kebab top-level
                        "ekbatan.sharding.default-shard.member=0",
                        "ekbatan.sharding.groups[0].group=0",
                        "ekbatan.sharding.groups[0].name=global",
                        "ekbatan.sharding.groups[0].members[0].member=0",
                        // Mixed: kebab map-key + camel leaf + kebab leaf.
                        "ekbatan.sharding.groups[0].members[0].configs.primary-config.jdbcUrl=jdbc:postgresql://primary:5432/db",
                        "ekbatan.sharding.groups[0].members[0].configs.primary-config.username=app",
                        "ekbatan.sharding.groups[0].members[0].configs.primary-config.password=secret",
                        "ekbatan.sharding.groups[0].members[0].configs.primary-config.maximum-pool-size=20")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var cfg = ctx.getBean(ShardingConfig.class);
                    // The kebab map key landed under the canonical camelCase form.
                    assertThat(cfg.groups.get(0).members.get(0).configs)
                            .containsKey("primaryConfig")
                            .doesNotContainKey("primary-config");
                    var primary = cfg.groups.get(0).members.get(0).primaryConfig();
                    assertThat(primary.jdbcUrl).isEqualTo("jdbc:postgresql://primary:5432/db");
                    assertThat(primary.maximumPoolSize).isEqualTo(20);
                });
    }

    @Test
    void actionsShouldNotBeSpringBeansAndShouldBeRegisteredAsSingletons() {
        // Actions are framework-internal: discovered via classpath scan, instantiated once at
        // startup via AutowireCapableBeanFactory.createBean(...) (which wires constructor
        // dependencies from existing singleton beans - Clock here, plus @EkbatanRepository
        // beans in real apps), and registered in the ActionRegistry. The resulting instances
        // are NOT registered as managed Spring beans; they're reachable only via ActionExecutor
        // / test-support ActionSpec. Per-execution state lives in Action.plan() (a ScopedValue), not on the
        // instance - so a single instance is shared across all concurrent executions.
        contextRunner
                .withInitializer(ctx -> AutoConfigurationPackages.register(
                        (BeanDefinitionRegistry) ctx.getBeanFactory(),
                        FixtureAction.class.getPackage().getName()))
                .withPropertyValues(
                        "ekbatan.sharding.defaultShard.group=0",
                        "ekbatan.sharding.defaultShard.member=0",
                        "ekbatan.sharding.groups[0].group=0",
                        "ekbatan.sharding.groups[0].name=g",
                        "ekbatan.sharding.groups[0].members[0].member=0",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl=jdbc:postgresql://x:5432/db",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username=u",
                        "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password=p")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();

                    // Action class is discoverable but NOT a Spring bean.
                    assertThat(ctx).doesNotHaveBean(FixtureAction.class);

                    // It IS in the ActionRegistry - and resolves to the same singleton on every call.
                    var registry = ctx.getBean(ActionRegistry.class);
                    var first = registry.get(FixtureAction.class);
                    var second = registry.get(FixtureAction.class);
                    assertThat(first).isInstanceOf(FixtureAction.class);
                    assertThat(first).isSameAs(second);
                });
    }

    @Test
    void shouldFailFastWhenShardingPrefixIsAbsent() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ekbatan.sharding");
        });
    }

    /**
     * Suppress the auto-config's real {@link DatabaseRegistry} (which would open Hikari pools)
     * and the {@link ActionExecutor} factory (which dereferences {@code DatabaseRegistry}
     * internals during construction) by providing mocks. {@code @ConditionalOnMissingBean} on
     * the auto-config skips its own factories in favor of these. End-to-end wiring is verified
     * by the Testcontainers integration test, not here.
     */
    @Configuration
    static class MockDatabaseRegistryConfig {
        @Bean
        DatabaseRegistry mockDatabaseRegistry() {
            return mock(DatabaseRegistry.class);
        }

        @Bean
        ActionExecutor mockActionExecutor() {
            return mock(ActionExecutor.class);
        }
    }

    /**
     * Direct-invocation tests for the {@code ekbatanJobsConfig} producer -- no Spring context
     * boot, just a {@link StandardEnvironment} with a {@link MapPropertySource} feeding the
     * producer method. End-to-end wiring through the auto-config is exercised by the
     * Testcontainers integration test in {@code ekbatan-integration-tests-di-spring-boot-starter}.
     */
    @Nested
    class JobsConfigBinding {

        private JobsConfig bindJobs(Map<String, Object> props) {
            var env = new StandardEnvironment();
            if (!props.isEmpty()) {
                env.getPropertySources().addFirst(new MapPropertySource("test", props));
            }
            return new EkbatanCoreConfiguration().ekbatanJobsConfig(env);
        }

        @Test
        void returnsDefaults_whenSubtreeAbsent() {
            var cfg = bindJobs(Map.of());
            assertThat(cfg.pollingInterval).isEmpty();
            assertThat(cfg.heartbeatInterval).isEmpty();
            assertThat(cfg.shutdownMaxWait).isEmpty();
        }

        @Test
        void bindsKebabCaseKeysWithIso8601Durations() {
            var cfg = bindJobs(Map.of(
                    "ekbatan.jobs.polling-interval", "PT5S",
                    "ekbatan.jobs.heartbeat-interval", "PT3S",
                    "ekbatan.jobs.shutdown-max-wait", "PT30S"));

            assertThat(cfg.pollingInterval).contains(Duration.ofSeconds(5));
            assertThat(cfg.heartbeatInterval).contains(Duration.ofSeconds(3));
            assertThat(cfg.shutdownMaxWait).contains(Duration.ofSeconds(30));
        }

        @Test
        void bindsCamelCaseKeys() {
            var cfg = bindJobs(Map.of(
                    "ekbatan.jobs.pollingInterval", "PT5S",
                    "ekbatan.jobs.heartbeatInterval", "PT3S",
                    "ekbatan.jobs.shutdownMaxWait", "PT30S"));

            assertThat(cfg.pollingInterval).contains(Duration.ofSeconds(5));
            assertThat(cfg.heartbeatInterval).contains(Duration.ofSeconds(3));
            assertThat(cfg.shutdownMaxWait).contains(Duration.ofSeconds(30));
        }

        @Test
        void bindsMixedKebabAndCamelCaseKeys() {
            // PropertyKeyNormalizer collapses both spellings to the same canonical form.
            var cfg = bindJobs(Map.of(
                    "ekbatan.jobs.polling-interval", "PT5S", // kebab
                    "ekbatan.jobs.heartbeatInterval", "PT3S", // camel
                    "ekbatan.jobs.shutdown-max-wait", "PT30S")); // kebab

            assertThat(cfg.pollingInterval).contains(Duration.ofSeconds(5));
            assertThat(cfg.heartbeatInterval).contains(Duration.ofSeconds(3));
            assertThat(cfg.shutdownMaxWait).contains(Duration.ofSeconds(30));
        }

        @Test
        void failsOnUnknownProperty() {
            assertThatThrownBy(() -> bindJobs(Map.of("ekbatan.jobs.not-a-real-field", "x")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to bind 'ekbatan.jobs'");
        }
    }

    /**
     * Direct-invocation tests for the {@code ekbatanLocalEventHandlerConfig} producer -- mirror of
     * {@link JobsConfigBinding}, covering both flat and nested ({@code handling.*}) knobs.
     */
    @Nested
    class LocalEventHandlerConfigBinding {

        private LocalEventHandlerConfig bindLeh(Map<String, Object> props) {
            var env = new StandardEnvironment();
            if (!props.isEmpty()) {
                env.getPropertySources().addFirst(new MapPropertySource("test", props));
            }
            return new EkbatanCoreConfiguration().ekbatanLocalEventHandlerConfig(env);
        }

        @Test
        void returnsDefaults_whenSubtreeAbsent() {
            var cfg = bindLeh(Map.of());
            assertThat(cfg.fanoutPollDelay).isEmpty();
            assertThat(cfg.handling.enabled).isFalse();
        }

        @Test
        void bindsFlatAndNestedKnobs() {
            var cfg = bindLeh(Map.of(
                    "ekbatan.local-event-handler.fanout-poll-delay", "PT0.2S",
                    "ekbatan.local-event-handler.fanout-batch-size", "100",
                    "ekbatan.local-event-handler.handling-poll-delay", "PT0.15S",
                    "ekbatan.local-event-handler.handling.enabled", "true"));

            assertThat(cfg.fanoutPollDelay).contains(Duration.ofMillis(200));
            assertThat(cfg.fanoutBatchSize).contains(100);
            assertThat(cfg.handlingPollDelay).contains(Duration.ofMillis(150));
            assertThat(cfg.handling.enabled).isTrue();
        }

        @Test
        void bindsCamelCaseKeys() {
            var cfg = bindLeh(Map.of(
                    "ekbatan.local-event-handler.fanoutPollDelay", "PT0.2S",
                    "ekbatan.local-event-handler.fanoutBatchSize", "100",
                    "ekbatan.local-event-handler.handlingPollDelay", "PT0.15S",
                    "ekbatan.local-event-handler.handling.enabled", "true"));

            assertThat(cfg.fanoutPollDelay).contains(Duration.ofMillis(200));
            assertThat(cfg.fanoutBatchSize).contains(100);
            assertThat(cfg.handlingPollDelay).contains(Duration.ofMillis(150));
            assertThat(cfg.handling.enabled).isTrue();
        }

        @Test
        void bindsCamelCasedParentSegment() {
            var cfg = bindLeh(Map.of(
                    "ekbatan.localEventHandler.fanoutPollDelay", "PT0.2S",
                    "ekbatan.localEventHandler.fanoutBatchSize", "100",
                    "ekbatan.localEventHandler.handlingPollDelay", "PT0.15S",
                    "ekbatan.localEventHandler.handling.enabled", "true"));

            assertThat(cfg.fanoutPollDelay).contains(Duration.ofMillis(200));
            assertThat(cfg.fanoutBatchSize).contains(100);
            assertThat(cfg.handlingPollDelay).contains(Duration.ofMillis(150));
            assertThat(cfg.handling.enabled).isTrue();
        }

        @Test
        void bindsCamelCasedParentSegmentWithKebabLeaves() {
            var cfg = bindLeh(Map.of(
                    "ekbatan.localEventHandler.fanout-poll-delay", "PT0.2S",
                    "ekbatan.localEventHandler.fanout-batch-size", "100",
                    "ekbatan.localEventHandler.handling-poll-delay", "PT0.15S",
                    "ekbatan.localEventHandler.handling.enabled", "true"));

            assertThat(cfg.fanoutPollDelay).contains(Duration.ofMillis(200));
            assertThat(cfg.fanoutBatchSize).contains(100);
            assertThat(cfg.handlingPollDelay).contains(Duration.ofMillis(150));
            assertThat(cfg.handling.enabled).isTrue();
        }

        @Test
        void failsOnUnknownProperty() {
            assertThatThrownBy(() -> bindLeh(Map.of("ekbatan.local-event-handler.not-a-real-field", "x")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to bind 'ekbatan.local-event-handler'");
        }
    }
}
