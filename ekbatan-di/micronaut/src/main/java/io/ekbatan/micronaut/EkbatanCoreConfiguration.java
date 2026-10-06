package io.ekbatan.micronaut;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.action.ActionRegistry;
import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.config.PropertyKeyNormalizer;
import io.ekbatan.core.config.ShardingConfig;
import io.ekbatan.core.repository.AbstractRepository;
import io.ekbatan.core.repository.RepositoryRegistry;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.distributedjobs.config.JobsConfig;
import io.ekbatan.events.localeventhandler.config.LocalEventHandlerConfig;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.env.Environment;
import io.micronaut.core.naming.conventions.StringConvention;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;
import tools.jackson.dataformat.javaprop.JavaPropsSchema;

/**
 * Micronaut {@code @Factory} class for Ekbatan's core surface. Binds {@code ekbatan.sharding.*}
 * config to {@link ShardingConfig} (via the Jackson hybrid path), wires the
 * {@link DatabaseRegistry}, and produces the {@link ActionRegistry} / {@link RepositoryRegistry}
 * / {@link ActionExecutor} chain.
 *
 * <p>User-supplied {@code @EkbatanAction} / {@code @EkbatanRepository} beans are lifted to
 * Micronaut {@code @Singleton}s at compile time by the {@code EkbatanStereotypeVisitor}
 * (a {@code TypeElementVisitor} shipped on the AP path via the {@code ekbatan-micronaut} jar),
 * then injected here via {@code List<Action<?, ?>>} / {@code List<AbstractRepository<?, ?, ?, ?>>}.
 */
@Factory
public class EkbatanCoreConfiguration {

    /** Required by Micronaut; the container instantiates this {@code @Factory} class to invoke its {@code @Bean} methods. */
    public EkbatanCoreConfiguration() {}

    /**
     * Binds the {@code ekbatan.sharding} subtree from Micronaut's {@link Environment} into
     * {@link ShardingConfig} via Jackson's {@link JavaPropsMapper}. Micronaut hands us a flat
     * map keyed by dotted paths with {@code [idx]} array notation (e.g. {@code groups[0].primaryConfig.jdbcUrl})
     * - the exact format JavaPropsMapper parses natively, so no custom tree reconstruction is needed.
     * {@link StringConvention#CAMEL_CASE} normalises every path segment to camelCase regardless of
     * whether the source YAML uses kebab-case ({@code jdbc-url}) or camelCase ({@code jdbcUrl}),
     * so the resulting keys always match the Ekbatan builder methods. The Jackson binding metadata
     * lives inline on those classes via {@code @JsonDeserialize} / {@code @JsonPOJOBuilder} /
     * {@code @JsonIgnore}, so the {@link JavaPropsMapper} below picks it up without any extra
     * module registration.
     *
     * @param environment Micronaut's environment, source of the {@code ekbatan.sharding.*} keys.
     * @return the parsed {@link ShardingConfig} for the running application.
     */
    @Bean
    @Singleton
    public ShardingConfig ekbatanShardingConfig(Environment environment) {
        var flat = environment.getProperties("ekbatan.sharding", StringConvention.CAMEL_CASE);
        if (flat.isEmpty()) {
            throw new IllegalStateException(
                    "Ekbatan requires 'ekbatan.sharding' to be configured (groups[].members[].configs.primaryConfig.*). "
                            + "Either populate it in application.yml/application.properties or define a ShardingConfig "
                            + "@Bean of your own.");
        }
        // Micronaut emits synthetic aggregate entries (e.g. `groups` -> ArrayList<Map>) alongside
        // the flat leaf keys; skip those - JavaPropsMapper rebuilds the structure from the leaves.
        var props = new Properties();
        flat.forEach((k, v) -> {
            if (v != null && !(v instanceof Map<?, ?>) && !(v instanceof List<?>) && !isDriverSetting(k)) {
                props.setProperty(k, v.toString());
            }
        });
        // Driver setting names must reach the driver exactly as written, and the camelCase view
        // re-cases the segments of flat keys: ApplicationName arrives as applicationName,
        // ha.enableJMX as ha.enableJmx, and the driver ignores both. Take those keys from the raw
        // view instead, through the same normaliser Spring and Quarkus use, which leaves them
        // untouched. The raw view holds a YAML list only as the whole structure - groups as a
        // list of maps, every key in it as written - and flat keys (a properties file, a test's
        // property provider) as leaves; both are walked, flat keys last so they take precedence.
        var raw = environment.getProperties("ekbatan.sharding", StringConvention.RAW);
        var driverSettings = new LinkedHashMap<String, String>();
        raw.forEach((k, v) -> {
            if (v instanceof Map<?, ?> || v instanceof List<?>) {
                collectDriverSettings(k, v, driverSettings, environment);
            }
        });
        raw.forEach((k, v) -> {
            if (v != null && !(v instanceof Map<?, ?>) && !(v instanceof List<?>)) {
                collectDriverSettings(k, v, driverSettings, environment);
            }
        });
        driverSettings.forEach(props::setProperty);
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
            // Jackson 3 throws unchecked JacksonException for binding failures (the IOException
            // path is declared on the method signature but only triggers on lower-level I/O
            // problems we don't expect with an in-memory Properties source). Wrap both so any
            // misconfiguration of `ekbatan.sharding.*` surfaces a single, contextual message.
            throw new IllegalStateException("Failed to bind 'ekbatan.sharding' configuration to ShardingConfig", e);
        }
    }

    /**
     * Binds the {@code ekbatan.jobs} subtree from Micronaut's {@link Environment} into
     * {@link JobsConfig} via the same Jackson hybrid path. Optional -- falls through to
     * {@link JobsConfig#defaults()} when no keys are present, so every knob lands at
     * db-scheduler's framework default at builder-apply time.
     *
     * @param environment Micronaut's environment, source of the {@code ekbatan.jobs.*} keys.
     * @return the parsed {@link JobsConfig} for the running application.
     */
    @Bean
    @Singleton
    public JobsConfig ekbatanJobsConfig(Environment environment) {
        return bindOptionalSubtree(environment, "ekbatan.jobs", JobsConfig.class, JobsConfig.defaults());
    }

    /**
     * Binds the {@code ekbatan.local-event-handler} subtree from Micronaut's {@link Environment}
     * into {@link LocalEventHandlerConfig} via the same Jackson hybrid path. Optional -- falls
     * through to {@link LocalEventHandlerConfig#defaults()} when no keys are present.
     *
     * @param environment Micronaut's environment, source of the {@code ekbatan.local-event-handler.*} keys.
     * @return the parsed {@link LocalEventHandlerConfig} for the running application.
     */
    @Bean
    @Singleton
    public LocalEventHandlerConfig ekbatanLocalEventHandlerConfig(Environment environment) {
        return bindOptionalSubtree(
                environment,
                "ekbatan.local-event-handler",
                LocalEventHandlerConfig.class,
                LocalEventHandlerConfig.defaults());
    }

    /**
     * Shared helper for the optional Jackson-hybrid subtrees (jobs / local-event-handler). Reads
     * {@code prefix.*} keys via Micronaut's {@link Environment#getProperties} with camelCase
     * normalisation, hands the leaf entries to a strict {@link JavaPropsMapper}, and falls back
     * to {@code ifEmpty} when no keys are present.
     */
    private static <T> T bindOptionalSubtree(Environment environment, String prefix, Class<T> target, T ifEmpty) {
        var flat = environment.getProperties(prefix, StringConvention.CAMEL_CASE);
        if (flat.isEmpty()) return ifEmpty;
        var props = new Properties();
        flat.forEach((k, v) -> {
            if (v != null && !(v instanceof Map<?, ?>) && !(v instanceof List<?>)) {
                props.setProperty(k, v.toString());
            }
        });
        var mapper = JavaPropsMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        try {
            return mapper.readPropertiesAs(props, target);
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException(
                    "Failed to bind '" + prefix + "' configuration to " + target.getSimpleName(), e);
        }
    }

    /**
     * Builds the {@link DatabaseRegistry} that owns connection pools for every shard member.
     *
     * @param shardingConfig the sharding topology resolved from configuration.
     * @return a registry that opens pools eagerly; Micronaut closes it via {@code preDestroy="close"}.
     */
    @Bean(preDestroy = "close")
    @Singleton
    public DatabaseRegistry ekbatanDatabaseRegistry(ShardingConfig shardingConfig) {
        return DatabaseRegistry.fromConfig(shardingConfig);
    }

    /** {@return the framework's default UTC {@link Clock}; override with your own {@code @Bean Clock} for tests} */
    @Bean
    @Singleton
    public Clock ekbatanClock() {
        return Clock.systemUTC();
    }

    /** {@return the application-facing Jackson 3 {@link JsonMapper} used for event payload serialization} */
    @Bean
    @Singleton
    public JsonMapper ekbatanJsonMapper() {
        return JsonMapper.builder().build();
    }

    /**
     * Collects every {@code @EkbatanRepository}-annotated bean discovered by the compile-time
     * {@code EkbatanStereotypeVisitor} and bundles them into a single {@link RepositoryRegistry}.
     *
     * @param repositories the application's repository beans, injected by Micronaut.
     * @return the registry consulted by actions and tests for repository lookup.
     */
    @Bean
    @Singleton
    public RepositoryRegistry ekbatanRepositoryRegistry(List<AbstractRepository<?, ?, ?, ?>> repositories) {
        return RepositoryRegistry.Builder.repositoryRegistry()
                .withRepositories(repositories)
                .build();
    }

    /**
     * Collects every {@code @EkbatanAction}-annotated bean discovered by the compile-time
     * {@code EkbatanStereotypeVisitor} and bundles them into a single {@link ActionRegistry}.
     *
     * <p>Singleton-scoped Actions: per-call mutable state on {@code Action.plan} is bound by the
     * framework via {@code Action.runIn(...)} using a {@code ScopedValue}, so a single instance
     * is safe across concurrent {@code ActionExecutor.execute(...)} calls.
     *
     * @param actions the application's action beans, injected by Micronaut.
     * @return the registry consulted by the {@link ActionExecutor} during dispatch.
     */
    @Bean
    @Singleton
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ActionRegistry ekbatanActionRegistry(List<Action<?, ?>> actions) {
        return (ActionRegistry) ActionRegistry.Builder.actionRegistry()
                .withActions((List) actions)
                .build();
    }

    /**
     * Wires the central {@link ActionExecutor} from its required collaborators. If the application
     * defines its own {@link EventPersister} bean (e.g. one that encrypts payloads or uses a custom
     * table layout), it's injected here; otherwise the builder falls back to its built-in
     * single-table JSON default.
     *
     * @param properties the Ekbatan runtime configuration (namespace, jobs, local event handler).
     * @param databaseRegistry per-shard connection pools.
     * @param actionRegistry the registry of {@code @EkbatanAction} beans.
     * @param repositoryRegistry the registry of {@code @EkbatanRepository} beans.
     * @param jsonMapper the Jackson mapper used for event payload serialization.
     * @param clock the system clock used for event timestamps.
     * @param eventPersister an optional user-supplied event persister.
     * @return the configured {@link ActionExecutor}.
     */
    @Bean
    @Singleton
    public ActionExecutor ekbatanActionExecutor(
            EkbatanProperties properties,
            DatabaseRegistry databaseRegistry,
            ActionRegistry actionRegistry,
            RepositoryRegistry repositoryRegistry,
            JsonMapper jsonMapper,
            Clock clock,
            Optional<EventPersister> eventPersister) {
        var builder = ActionExecutor.Builder.actionExecutor()
                .namespace(properties.getNamespace())
                .databaseRegistry(databaseRegistry)
                .actionRegistry(actionRegistry)
                .repositoryRegistry(repositoryRegistry)
                .objectMapper(jsonMapper)
                .clock(clock);
        // Optional override: an application can supply its own EventPersister bean (e.g. one that
        // encrypts payloads, writes to a separate sink, or uses a different table layout).
        // Otherwise the builder falls back to its built-in SingleTableJsonEventPersister default.
        eventPersister.ifPresent(builder::eventPersister);
        return builder.build();
    }

    private static boolean isDriverSetting(String canonicalKey) {
        return canonicalKey.contains(".dataSourceProperties.");
    }

    /**
     * Walks one raw entry down to its leaves - map entries joined with '.', list items with
     * {@code [i]}, every key exactly as written - and keeps the driver settings among them. A
     * value inside a raw structure may still hold a {@code ${...}} placeholder, so each one is
     * resolved here; a leaf the environment already resolved comes back unchanged.
     */
    private static void collectDriverSettings(
            String path, Object value, Map<String, String> into, Environment environment) {
        switch (value) {
            case null -> {}
            case Map<?, ?> map ->
                map.forEach((key, nested) -> collectDriverSettings(path + "." + key, nested, into, environment));
            case List<?> list -> {
                for (int i = 0; i < list.size(); i++) {
                    collectDriverSettings(path + "[" + i + "]", list.get(i), into, environment);
                }
            }
            default -> {
                var canonical = PropertyKeyNormalizer.kebabToCamel(path);
                if (isDriverSetting(canonical)) {
                    into.put(
                            canonical,
                            environment.getPlaceholderResolver().resolveRequiredPlaceholders(value.toString()));
                }
            }
        }
    }
}
