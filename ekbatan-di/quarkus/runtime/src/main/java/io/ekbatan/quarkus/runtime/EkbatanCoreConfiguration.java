package io.ekbatan.quarkus.runtime;

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
import io.quarkus.arc.All;
import io.smallrye.config.EnvConfigSource;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;
import tools.jackson.dataformat.javaprop.JavaPropsSchema;

/**
 * Quarkus runtime CDI producer class for Ekbatan's core surface. Produces the
 * {@link ShardingConfig}, {@link DatabaseRegistry}, {@link ActionRegistry},
 * {@link RepositoryRegistry}, and {@link ActionExecutor} beans that the extension's
 * deployment processor registers in the bean container.
 *
 * <p>Discovered at deployment time via the Quarkus extension processor
 * (see {@code io.ekbatan.quarkus.deployment.EkbatanProcessor}) and constructed at runtime
 * by Arc. User-supplied {@code @EkbatanAction} / {@code @EkbatanRepository} beans are
 * picked up by the deployment processor's index walk and injected here via {@code @All}.
 */
@Singleton
public class EkbatanCoreConfiguration {

    /** Required by CDI; the container instantiates this bean class to invoke its producer methods. */
    public EkbatanCoreConfiguration() {}

    /**
     * Binds the {@code ekbatan.sharding} subtree from SmallRye Config into {@link ShardingConfig}
     * via Jackson's {@link JavaPropsMapper}. SmallRye exposes hierarchical YAML as flat keys with
     * {@code [idx]} array notation (e.g. {@code groups[0].primaryConfig.jdbcUrl}) - the exact
     * shape JavaPropsMapper parses natively, so no custom tree reconstruction is needed.
     *
     * <p>Ekbatan accepts both camelCase ({@code jdbcUrl}, {@code primaryConfig}) and kebab-case
     * ({@code jdbc-url}, {@code primary-config}) property names here; keys are canonicalised before
     * they are handed to Jackson.
     *
     * <p>{@code @ConfigMapping} can't construct {@code DataSourceConfig} entries directly - they
     * have private constructors + Builders, and the {@code configs} map uses user-defined keys.
     * The Jackson binding metadata lives inline on those classes via {@code @JsonDeserialize} /
     * {@code @JsonPOJOBuilder} / {@code @JsonIgnore}, so the {@link JavaPropsMapper} below picks
     * it up without any extra module registration.
     *
     * @return the parsed {@link ShardingConfig} for the running application.
     */
    @Produces
    @Singleton
    public ShardingConfig ekbatanShardingConfig() {
        var config = ConfigProvider.getConfig();
        // The prefix has no hyphens so it's already canonical; for consistency with the optional
        // subtrees we still match against the normalised key so any future kebab parent (or a
        // weird `ekbatan.sharding.Default-Shard` from a user) bind identically.
        var props = collectSubtree(config, "ekbatan.sharding.");
        if (props.isEmpty()) {
            throw new IllegalStateException(
                    "Ekbatan requires 'ekbatan.sharding' to be configured (groups[].members[].configs.primaryConfig.*). "
                            + "Either populate it in application.yaml/application.properties or define a ShardingConfig @Produces of your own.");
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
            // Jackson 3 throws unchecked JacksonException for binding failures; the IOException
            // path is declared on the method signature but only triggers on lower-level I/O
            // problems we don't expect with an in-memory Properties source. Wrap both so any
            // misconfiguration of `ekbatan.sharding.*` surfaces a single, contextual message.
            throw new IllegalStateException("Failed to bind 'ekbatan.sharding' configuration to ShardingConfig", e);
        }
    }

    /**
     * Binds the {@code ekbatan.jobs} subtree from SmallRye Config into {@link JobsConfig} via the
     * same Jackson hybrid path used for {@code ekbatan.sharding}. The subtree is optional - if no
     * {@code ekbatan.jobs.*} keys are configured, returns {@link JobsConfig#defaults()} so every
     * knob falls through to db-scheduler's framework defaults at builder-apply time.
     *
     * @return the parsed {@link JobsConfig} for the running application.
     */
    @Produces
    @Singleton
    public JobsConfig ekbatanJobsConfig() {
        return bindSubtree("ekbatan.jobs.", JobsConfig.class, JobsConfig::defaults);
    }

    /**
     * Binds the {@code ekbatan.local-event-handler} subtree from SmallRye Config into
     * {@link LocalEventHandlerConfig} via the same Jackson hybrid path. Optional -- falls through
     * to {@link LocalEventHandlerConfig#defaults()} when no keys are present.
     *
     * @return the parsed {@link LocalEventHandlerConfig} for the running application.
     */
    @Produces
    @Singleton
    public LocalEventHandlerConfig ekbatanLocalEventHandlerConfig() {
        return bindSubtree(
                "ekbatan.local-event-handler.", LocalEventHandlerConfig.class, LocalEventHandlerConfig::defaults);
    }

    /**
     * Collects one {@code prefix}ed subtree of SmallRye Config into flat {@link Properties} keyed by
     * the canonical (camelCase) property name, ready for the strict {@code JavaPropsMapper}.
     *
     * <p>Two things here are subtler than they look, and both were live defects:
     *
     * <p><b>1. An enumerated name is not necessarily a name you may write.</b> SmallRye's
     * {@code EnvConfigSource} publishes every environment variable under <em>two</em> names: the raw
     * {@code FOO_BAR}, and a synthesized lower-cased dotted alias. The alias is lower-cased, so every
     * camelCase segment of an Ekbatan key is flattened - {@code configs.primaryConfig.password}
     * arrives as {@code configs.primaryconfig.password}. Copying that verbatim into the Jackson input
     * (as this code used to) creates a phantom {@code primaryconfig} entry in the {@code configs} map
     * that has no {@code jdbcUrl}, and the whole bind fails with "jdbcUrl is required" - so setting a
     * database password by environment variable, the standard container practice, could not work.
     * We therefore restore the casing of any flattened name against the canonical spellings the
     * non-environment sources already gave us, and resolve the <em>value</em> through
     * {@link Config#getConfigValue(String)} under that repaired name, which lets SmallRye apply
     * source ordinals and its own environment-variable matching.
     *
     * <p><b>2. {@code getOptionalValue} cannot express an empty value.</b> SmallRye's String
     * converter maps {@code ""} to absent, so a legal empty password (MySQL root with no password,
     * Postgres {@code trust} auth) was silently dropped and startup then failed with
     * "password is required" - while the identical configuration bound fine on Spring and Micronaut.
     * {@link Config#getConfigValue(String)} bypasses the converter and preserves the empty string.
     *
     * <p><b>Known limitation.</b> Casing can only be restored from a spelling that some non-environment
     * source supplies. A key that exists <em>only</em> as an environment variable and whose canonical
     * form contains uppercase letters (say {@code maximumPoolSize}, with no counterpart in any file)
     * cannot be recovered, and the strict mapper will reject it by name. That fails loudly rather than
     * silently, and it does not affect the usual split of topology-in-file plus secrets-in-environment.
     *
     * @param config the active SmallRye config.
     * @param prefix the subtree prefix, including its trailing dot.
     * @return flat properties relative to {@code prefix}; empty when the subtree is absent.
     */
    private static Properties collectSubtree(Config config, String prefix) {
        final var canonicalPrefix = PropertyKeyNormalizer.kebabToCamel(prefix);
        final var flattenedNames = environmentSynthesizedNames(config);

        // Pass 1 - canonical spellings, taken only from sources that preserve case, so that a
        // flattened environment alias can have its casing restored against them.
        final var canonicalSpellings = new LinkedHashSet<String>();
        for (var name : config.getPropertyNames()) {
            if (flattenedNames.contains(name)) continue;
            var canonical = PropertyKeyNormalizer.kebabToCamel(name);
            if (canonical.startsWith(canonicalPrefix) && canonical.length() > canonicalPrefix.length()) {
                canonicalSpellings.add(canonical);
            }
        }
        final var spellings = knownSpellingsByLowerCase(canonicalSpellings);

        // Pass 2 - resolve every name in the subtree. The KEY is canonical (kebab folded to camel,
        // flattened environment aliases repaired); the VALUE is read back under the name the source
        // actually published, because that is the only name SmallRye can resolve. Two different
        // source names can normalise onto one canonical key (`primary-config` in a file and
        // `primaryconfig` from the environment), so the higher source ordinal wins - which is how
        // an environment variable keeps overriding a file.
        final var resolved = new HashMap<String, ResolvedValue>();
        for (var name : config.getPropertyNames()) {
            var canonical = PropertyKeyNormalizer.kebabToCamel(name);
            if (!canonical.startsWith(canonicalPrefix) || canonical.length() == canonicalPrefix.length()) {
                continue;
            }
            if (flattenedNames.contains(name)) {
                canonical = restoreCasing(canonical, spellings);
                if (!canonical.startsWith(canonicalPrefix)) continue;
            }
            // getConfigValue, not getOptionalValue: the latter runs SmallRye's String converter,
            // which maps "" to absent and so silently drops a legal empty password.
            final var configValue = config.getConfigValue(name);
            final var value = configValue.getValue();
            if (value == null) continue;
            final var key = canonical.substring(canonicalPrefix.length());
            final var existing = resolved.get(key);
            if (existing == null || configValue.getSourceOrdinal() > existing.ordinal()) {
                resolved.put(key, new ResolvedValue(value, configValue.getSourceOrdinal()));
            }
        }

        final var props = new Properties();
        resolved.forEach((key, value) -> props.setProperty(key, value.value()));
        return props;
    }

    /** A subtree value plus the ordinal of the source it came from, so overrides resolve correctly. */
    private record ResolvedValue(String value, int ordinal) {}

    /** {@return every property name published by an environment-variable config source} */
    private static Set<String> environmentSynthesizedNames(Config config) {
        final var names = new HashSet<String>();
        for (var source : config.getConfigSources()) {
            if (source instanceof EnvConfigSource) {
                source.getPropertyNames().forEach(names::add);
            }
        }
        return names;
    }

    /** Indexes every dot-prefix of every canonical name by its lower-cased form. */
    private static Map<String, String> knownSpellingsByLowerCase(Set<String> canonicalNames) {
        final var spellings = new HashMap<String, String>();
        for (var canonical : canonicalNames) {
            var prefix = new StringBuilder();
            for (var segment : canonical.split("\\.")) {
                if (!prefix.isEmpty()) prefix.append('.');
                prefix.append(segment);
                spellings.putIfAbsent(prefix.toString().toLowerCase(Locale.ROOT), prefix.toString());
            }
        }
        return spellings;
    }

    /**
     * Rebuilds {@code flattened} segment by segment, swapping in a known canonical spelling wherever
     * the prefix so far matches one case-insensitively. Segments with no known spelling are kept
     * as-is - see the limitation on {@link #collectSubtree}.
     */
    private static String restoreCasing(String flattened, Map<String, String> spellings) {
        final var restored = new StringBuilder();
        for (var segment : flattened.split("\\.")) {
            var candidate = restored.isEmpty() ? segment : restored + "." + segment;
            var known = spellings.get(candidate.toLowerCase(Locale.ROOT));
            restored.setLength(0);
            restored.append(known != null ? known : candidate);
        }
        return restored.toString();
    }

    /**
     * Shared helper for the optional Jackson-hybrid subtrees (jobs / local-event-handler). Folds
     * kebab-case segments to camelCase via {@link PropertyKeyNormalizer} BEFORE prefix matching,
     * so both the parent segment ({@code local-event-handler} vs {@code localEventHandler}) and
     * every leaf ({@code polling-interval} vs {@code pollingInterval}) bind against the same
     * canonical form. Falls back to {@code ifEmpty} when no keys are present.
     */
    private static <T> T bindSubtree(String prefix, Class<T> target, java.util.function.Supplier<T> ifEmpty) {
        var props = collectSubtree(ConfigProvider.getConfig(), prefix);
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
     * Builds the {@link DatabaseRegistry} that owns connection pools for every shard member.
     *
     * @param shardingConfig the sharding topology resolved from configuration.
     * @return a registry that opens pools eagerly and is closed by {@link #closeDatabaseRegistry}.
     */
    @Produces
    @Singleton
    public DatabaseRegistry ekbatanDatabaseRegistry(ShardingConfig shardingConfig) {
        return DatabaseRegistry.fromConfig(shardingConfig);
    }

    /**
     * CDI disposer for {@link #ekbatanDatabaseRegistry} - drains connection pools at shutdown.
     *
     * @param registry the registry being disposed.
     * @throws Exception if pool teardown fails.
     */
    public void closeDatabaseRegistry(@Disposes DatabaseRegistry registry) throws Exception {
        registry.close();
    }

    /**
     * Produces the framework's default UTC {@link Clock}. Override by defining your own
     * {@code @Produces Clock} bean (useful for tests that need a test-support {@code VirtualClock}).
     *
     * @return a system UTC clock.
     */
    @Produces
    @Singleton
    public Clock ekbatanClock() {
        return Clock.systemUTC();
    }

    /**
     * Produces the application-facing {@link JsonMapper} used by the {@link ActionExecutor}
     * to serialize event payloads to the eventlog.
     *
     * @return a default Jackson 3 {@link JsonMapper}.
     */
    @Produces
    @Singleton
    public JsonMapper ekbatanJsonMapper() {
        return JsonMapper.builder().build();
    }

    /**
     * Collects every {@code @EkbatanRepository}-annotated bean discovered by the Quarkus extension
     * deployment processor and bundles them into a single {@link RepositoryRegistry}.
     *
     * @param repositories the application's repository beans, injected via Arc {@code @All}.
     * @return the registry consulted by actions and tests for repository lookup.
     */
    @Produces
    @Singleton
    public RepositoryRegistry ekbatanRepositoryRegistry(@All List<AbstractRepository<?, ?, ?, ?>> repositories) {
        return RepositoryRegistry.Builder.repositoryRegistry()
                .withRepositories(repositories)
                .build();
    }

    /**
     * Collects every {@code @EkbatanAction}-annotated bean discovered by the Quarkus extension
     * deployment processor and bundles them into a single {@link ActionRegistry}.
     *
     * <p>Singleton-scoped Actions: per-call mutable state on Action.plan is bound by the framework
     * via Action.runIn(plan, principal, params) using a ScopedValue, so a single instance is safe
     * across concurrent {@link ActionExecutor#execute} calls.
     *
     * @param actions the application's action beans, injected via Arc {@code @All}.
     * @return the registry consulted by the {@link ActionExecutor} during dispatch.
     */
    @Produces
    @Singleton
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ActionRegistry ekbatanActionRegistry(@All List<Action<?, ?>> actions) {
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
     * @param config the Ekbatan runtime configuration (namespace, jobs, local event handler).
     * @param databaseRegistry per-shard connection pools.
     * @param actionRegistry the registry of {@code @EkbatanAction} beans.
     * @param repositoryRegistry the registry of {@code @EkbatanRepository} beans.
     * @param jsonMapper the Jackson mapper used for event payload serialization.
     * @param clock the system clock used for event timestamps.
     * @param eventPersisterInstance an Arc {@link Instance} that may resolve a user-supplied event persister.
     * @return the configured {@link ActionExecutor}.
     */
    @Produces
    @Singleton
    public ActionExecutor ekbatanActionExecutor(
            EkbatanProperties config,
            DatabaseRegistry databaseRegistry,
            ActionRegistry actionRegistry,
            RepositoryRegistry repositoryRegistry,
            JsonMapper jsonMapper,
            Clock clock,
            Instance<EventPersister> eventPersisterInstance) {
        var builder = ActionExecutor.Builder.actionExecutor()
                .namespace(config.namespace())
                .databaseRegistry(databaseRegistry)
                .actionRegistry(actionRegistry)
                .repositoryRegistry(repositoryRegistry)
                .objectMapper(jsonMapper)
                .clock(clock);
        // Optional override: an application can supply its own EventPersister bean (e.g. one that
        // encrypts payloads, writes to a separate sink, or uses a different table layout).
        // Otherwise the builder falls back to its built-in SingleTableJsonEventPersister default.
        if (eventPersisterInstance.isResolvable()) {
            builder.eventPersister(eventPersisterInstance.get());
        }
        return builder.build();
    }
}
