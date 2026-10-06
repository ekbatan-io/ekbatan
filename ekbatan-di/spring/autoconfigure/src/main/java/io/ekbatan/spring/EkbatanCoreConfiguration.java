package io.ekbatan.spring;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.action.ActionRegistry;
import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.config.PropertyKeyNormalizer;
import io.ekbatan.core.config.ShardingConfig;
import io.ekbatan.core.repository.AbstractRepository;
import io.ekbatan.core.repository.RepositoryRegistry;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.di.EkbatanAction;
import io.ekbatan.distributedjobs.config.JobsConfig;
import io.ekbatan.events.localeventhandler.config.LocalEventHandlerConfig;
import io.ekbatan.spring.internal.EkbatanActionsHolder;
import io.ekbatan.spring.internal.EkbatanStereotypeBeanRegistrar;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;
import tools.jackson.dataformat.javaprop.JavaPropsSchema;

/**
 * Spring Boot auto-configuration for Ekbatan's core surface: binds {@code ekbatan.sharding.*}
 * config to {@link ShardingConfig}, wires the {@link DatabaseRegistry} (and its underlying
 * {@code TransactionManager}s and Hikari pools), and produces the {@link ActionRegistry} /
 * {@link RepositoryRegistry} / {@link ActionExecutor} chain.
 *
 * <p>{@link EkbatanStereotypeBeanRegistrar} (imported here) scans the application's auto-
 * configuration packages for {@code @EkbatanAction} / {@code @EkbatanRepository} / etc.
 * stereotypes and registers them as Spring beans so they reach the registries above without
 * manual wiring.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean} - users can override any one piece by
 * declaring their own. This is the canonical Spring Boot starter idiom.
 */
@AutoConfiguration
@EnableConfigurationProperties(EkbatanProperties.class)
@Import(EkbatanStereotypeBeanRegistrar.class)
public class EkbatanCoreConfiguration {

    /** Required by Spring; the container instantiates this auto-configuration class to invoke its {@code @Bean} methods. */
    public EkbatanCoreConfiguration() {}

    /**
     * Binds the {@code ekbatan.sharding} subtree from Spring's {@link Environment} into
     * {@link ShardingConfig} via Jackson's {@link JavaPropsMapper}. Spring exposes hierarchical
     * YAML/properties as flat keys with {@code [idx]} array notation (e.g.
     * {@code groups[0].primaryConfig.jdbcUrl}) -- the exact shape JavaPropsMapper parses natively,
     * so no custom tree reconstruction is needed. The Jackson binding metadata lives inline on
     * the sharding config classes via {@code @JsonDeserialize} / {@code @JsonPOJOBuilder} /
     * {@code @JsonIgnore}, so the mapper below picks it up without any extra module registration.
     *
     * <p>Ekbatan accepts both camelCase ({@code jdbcUrl}, {@code primaryConfig}) and kebab-case
     * ({@code jdbc-url}, {@code primary-config}) property names here; {@link #readSubtree}
     * canonicalises keys before handing them to Jackson.
     *
     * @param environment Spring's environment, source of the {@code ekbatan.sharding.*} keys.
     * @return the parsed {@link ShardingConfig} for the running application.
     */
    @Bean
    @ConditionalOnMissingBean
    public ShardingConfig ekbatanShardingConfig(Environment environment) {
        var props = readSubtree(environment, "ekbatan.sharding.");
        if (props.isEmpty()) {
            throw new IllegalStateException(
                    "Ekbatan requires 'ekbatan.sharding' to be configured (groups[].members[].configs.primaryConfig.*). "
                            + "Either populate it in application.yml or define a ShardingConfig @Bean.");
        }
        // Private mapper: FAIL_ON_UNKNOWN_PROPERTIES surfaces typos at startup without leaking
        // strictness into any application-level Jackson configuration.
        var mapper = JavaPropsMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        try {
            // Driver setting names keep their dots: the structure is read with '/' as its separator.
            return mapper.readPropertiesAs(
                    PropertyKeyNormalizer.toReaderKeys(props),
                    JavaPropsSchema.emptySchema().withPathSeparator(PropertyKeyNormalizer.READER_PATH_SEPARATOR),
                    ShardingConfig.class);
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("Failed to bind 'ekbatan.sharding' configuration to ShardingConfig", e);
        }
    }

    /**
     * Binds the {@code ekbatan.jobs} subtree from Spring's {@link Environment} into
     * {@link JobsConfig} via the same Jackson hybrid path. Optional -- falls through to
     * {@link JobsConfig#defaults()} when no keys are present so every knob ends up at
     * db-scheduler's framework default at builder-apply time.
     *
     * @param environment Spring's environment, source of the {@code ekbatan.jobs.*} keys.
     * @return the parsed {@link JobsConfig} for the running application.
     */
    @Bean
    @ConditionalOnMissingBean
    public JobsConfig ekbatanJobsConfig(Environment environment) {
        return bindSubtree(environment, "ekbatan.jobs.", JobsConfig.class, JobsConfig::defaults);
    }

    /**
     * Binds the {@code ekbatan.local-event-handler} subtree from Spring's {@link Environment} into
     * {@link LocalEventHandlerConfig} via the same Jackson hybrid path. Optional -- falls through
     * to {@link LocalEventHandlerConfig#defaults()} when no keys are present.
     *
     * @param environment Spring's environment, source of the {@code ekbatan.local-event-handler.*} keys.
     * @return the parsed {@link LocalEventHandlerConfig} for the running application.
     */
    @Bean
    @ConditionalOnMissingBean
    public LocalEventHandlerConfig ekbatanLocalEventHandlerConfig(Environment environment) {
        return bindSubtree(
                environment,
                "ekbatan.local-event-handler.",
                LocalEventHandlerConfig.class,
                LocalEventHandlerConfig::defaults);
    }

    /**
     * Shared helper for the optional Jackson-hybrid subtrees (jobs / local-event-handler). Reads
     * Spring config keys under {@code prefix} via {@link #readSubtree} (which folds kebab segments
     * to camelCase via {@link PropertyKeyNormalizer}) and falls back to {@code ifEmpty} when no
     * keys are present. Both spellings -- {@code polling-interval} and {@code pollingInterval} --
     * bind against the same builder methods because the keys arrive at Jackson already
     * canonicalised.
     */
    private static <T> T bindSubtree(Environment environment, String prefix, Class<T> target, Supplier<T> ifEmpty) {
        var props = readSubtree(environment, prefix);
        if (props.isEmpty()) return ifEmpty.get();
        var mapper = JavaPropsMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        try {
            return mapper.readPropertiesAs(props, target);
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException(
                    "Failed to bind '" + prefix.substring(0, prefix.length() - 1) + "' configuration to "
                            + target.getSimpleName(),
                    e);
        }
    }

    /**
     * Iterates every {@link EnumerablePropertySource} on the Spring environment and copies the
     * subset of properties starting with {@code prefix} into a flat {@link Properties} (prefix
     * stripped). This matches the property-iteration model Quarkus and Micronaut use for the same
     * Jackson hybrid path, and replaces the older {@code Binder.bind} + tree-rebuild path --
     * Spring's flat key form ({@code groups[0].name=...}) is exactly what JavaPropsMapper expects.
     *
     * <p>Non-enumerable {@code PropertySource}s are skipped; Spring Boot's built-in YAML /
     * properties / env-var / system-prop / @TestPropertySource sources all implement
     * {@link EnumerablePropertySource}, so any user override mechanism that works with the
     * standard {@code Binder.bind(prefix, Map)} call also works here.
     */
    private static Properties readSubtree(Environment environment, String prefix) {
        var props = new Properties();
        if (!(environment instanceof ConfigurableEnvironment ce)) return props;
        // Normalise both the prefix and each candidate key before comparing so the parent segment
        // is also case-tolerant: ekbatan.local-event-handler.* and ekbatan.localEventHandler.*
        // both match the same prefix. Leaves are canonicalised by the same call.
        var canonicalPrefix = PropertyKeyNormalizer.kebabToCamel(prefix);
        for (var src : ce.getPropertySources()) {
            if (!(src instanceof EnumerablePropertySource<?> eps)) continue;
            for (var name : eps.getPropertyNames()) {
                var canonical = PropertyKeyNormalizer.kebabToCamel(name);
                if (!canonical.startsWith(canonicalPrefix)) continue;
                var sub = canonical.substring(canonicalPrefix.length());
                if (sub.isEmpty()) continue;
                if (props.containsKey(sub)) continue; // higher-priority source already wrote this key
                var value = environment.getProperty(name);
                if (value != null) {
                    props.setProperty(sub, value);
                }
            }
        }
        return props;
    }

    /**
     * Builds the {@link DatabaseRegistry} that owns connection pools for every shard member.
     *
     * @param shardingConfig the sharding topology resolved from configuration.
     * @return a registry that opens pools eagerly; closed automatically by Spring at shutdown.
     */
    @Bean
    @ConditionalOnMissingBean
    public DatabaseRegistry ekbatanDatabaseRegistry(ShardingConfig shardingConfig) {
        return DatabaseRegistry.fromConfig(shardingConfig);
    }

    /** {@return the framework's default UTC {@link Clock}; override with your own {@code @Bean Clock} for tests} */
    @Bean
    @ConditionalOnMissingBean
    public Clock ekbatanClock() {
        return Clock.systemUTC();
    }

    /**
     * Collects every {@code @EkbatanRepository}-annotated bean discovered by
     * {@link EkbatanStereotypeBeanRegistrar} and bundles them into a single {@link RepositoryRegistry}.
     *
     * @param repositories the application's repository beans, injected by Spring.
     * @return the registry consulted by actions and tests for repository lookup.
     */
    @Bean
    @ConditionalOnMissingBean
    public RepositoryRegistry ekbatanRepositoryRegistry(List<AbstractRepository<?, ?, ?, ?>> repositories) {
        return RepositoryRegistry.Builder.repositoryRegistry()
                .withRepositories(repositories)
                .build();
    }

    /**
     * Builds the {@link ActionRegistry} from {@code @EkbatanAction}-annotated classes. Action
     * instances are framework-private singletons constructed via
     * {@link AutowireCapableBeanFactory#createBean(Class)} (not registered as Spring beans
     * directly) because per-call mutable state on {@code Action.plan} is bound via a
     * {@code ScopedValue} so a single instance is safely shared across concurrent
     * {@code execute(...)} calls.
     *
     * @param beanFactory the bean factory used to discover the AOT-collected or runtime-scanned action classes.
     * @param environment Spring's environment for the runtime-scan path.
     * @param autowireBeanFactory used to instantiate each action class with its dependencies wired.
     * @return the registry consulted by the {@link ActionExecutor} during dispatch.
     */
    @Bean
    @ConditionalOnMissingBean
    public ActionRegistry ekbatanActionRegistry(
            BeanFactory beanFactory, Environment environment, AutowireCapableBeanFactory autowireBeanFactory) {
        // createBean() instead of registering bean definitions: action instances are
        // framework-private (reachable only via ActionExecutor / test-support ActionSpec) and per-call mutable
        // state on Action.plan is bound via runIn(...) using a ScopedValue, so a single instance
        // is safely shared across concurrent execute(...) calls.
        var actionClasses = scanActionClasses(beanFactory, environment);
        var actions = new java.util.ArrayList<Action<?, ?>>(actionClasses.size());
        for (var cls : actionClasses) {
            actions.add((Action<?, ?>) autowireBeanFactory.createBean(cls));
        }
        return ActionRegistry.Builder.actionRegistry().withActions(actions).build();
    }

    /**
     * Wires the central {@link ActionExecutor} from its required collaborators. If the
     * application defines its own {@link EventPersister} bean (e.g. one that encrypts payloads
     * or uses a custom table layout), it's injected here; otherwise the builder falls back to
     * its built-in single-table JSON default.
     *
     * @param properties the Ekbatan runtime configuration (namespace, jobs, local event handler).
     * @param databaseRegistry per-shard connection pools.
     * @param actionRegistry the registry of {@code @EkbatanAction} classes.
     * @param repositoryRegistry the registry of {@code @EkbatanRepository} beans.
     * @param objectMapper the Jackson mapper used for event payload serialization.
     * @param clock the system clock used for event timestamps.
     * @param eventPersisterProvider an Spring {@link ObjectProvider} that may resolve a user-supplied event persister.
     * @return the configured {@link ActionExecutor}.
     */
    @Bean
    @ConditionalOnMissingBean
    public ActionExecutor ekbatanActionExecutor(
            EkbatanProperties properties,
            DatabaseRegistry databaseRegistry,
            ActionRegistry actionRegistry,
            RepositoryRegistry repositoryRegistry,
            ObjectMapper objectMapper,
            Clock clock,
            ObjectProvider<EventPersister> eventPersisterProvider) {
        var builder = ActionExecutor.Builder.actionExecutor()
                .namespace(properties.namespace())
                .databaseRegistry(databaseRegistry)
                .actionRegistry(actionRegistry)
                .repositoryRegistry(repositoryRegistry)
                .objectMapper(objectMapper)
                .clock(clock);
        // Optional override: an application can supply its own EventPersister bean (e.g. one that
        // encrypts payloads, writes to a separate sink, or uses a different table layout).
        // Otherwise the builder falls back to its built-in SingleTableJsonEventPersister default.
        var customPersister = eventPersisterProvider.getIfAvailable();
        if (customPersister != null) {
            builder.eventPersister(customPersister);
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static Set<Class<? extends Action<?, ?>>> scanActionClasses(
            BeanFactory beanFactory, Environment environment) {
        // AOT mode (including native image): EkbatanActionsAotProcessor ran the scan at
        // AOT processing time and emitted an initializer that populates this holder
        // before the auto-config runs. We just read the prepared list - the runtime
        // classpath scan below would silently return empty on native because there are
        // no .class files to walk in a native binary.
        // Taken, not read: the holder is a JVM-wide static, so leaving it populated let the next
        // context in the same JVM inherit this one's actions and skip its own scan.
        var aotDiscovered = EkbatanActionsHolder.consume();
        if (!aotDiscovered.isEmpty()) {
            var classes = new LinkedHashSet<Class<? extends Action<?, ?>>>(aotDiscovered.size());
            for (Class<?> cls : aotDiscovered) {
                if (!Action.class.isAssignableFrom(cls)) {
                    throw new IllegalStateException("EkbatanActionsHolder contained non-Action class " + cls.getName());
                }
                classes.add((Class<? extends Action<?, ?>>) cls);
            }
            return classes;
        }

        // Regular JVM mode: walk the classpath now.
        if (!AutoConfigurationPackages.has(beanFactory)) {
            return Set.of();
        }
        var packages = AutoConfigurationPackages.get(beanFactory);
        var scanner = new ClassPathScanningCandidateComponentProvider(false, environment);
        scanner.addIncludeFilter(new AnnotationTypeFilter(EkbatanAction.class));
        var classes = new LinkedHashSet<Class<? extends Action<?, ?>>>();
        for (var pkg : packages) {
            for (var bd : scanner.findCandidateComponents(pkg)) {
                Class<?> cls = ClassUtils.resolveClassName(bd.getBeanClassName(), null);
                if (!Action.class.isAssignableFrom(cls)) {
                    throw new IllegalStateException(
                            "@EkbatanAction on " + cls.getName() + " - class must extend " + Action.class.getName());
                }
                classes.add((Class<? extends Action<?, ?>>) cls);
            }
        }
        return classes;
    }
}
