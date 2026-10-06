package io.ekbatan.flyway;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.config.ShardingConfig;
import io.ekbatan.core.persistence.DataSources;
import io.ekbatan.core.shard.ShardIdentifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Flyway migration utility for Ekbatan applications.
 *
 * <p>The same class covers the common cases:
 * <ul>
 *   <li>one datasource, via {@link #migrate(DataSourceConfig, String...)};
 *   <li>all primary datasources in a {@link ShardingConfig}, via
 *       {@link #migrate(ShardingConfig, String...)};
 *   <li>an explicit set of shard targets, via {@link #migrate(Target...)} or the builder.
 * </ul>
 *
 * <p><b>How a migration connects.</b> Flyway is handed a data source rather than a URL: one built
 * from the same {@link DataSourceConfig} the application's pools are built from, by
 * {@link DataSources#driverDataSource(DataSourceConfig)}. It opens a new connection each
 * time Flyway asks for one, and Flyway's closing it closes it for good. So a migration connects
 * the way the application does - {@code driverClassName} included, and through a driver whose
 * URL Flyway would refuse by itself: Flyway's free edition refuses the {@code jdbc-secretsmanager:}
 * URL of AWS's Secrets Manager driver, and knows no URL it has no plugin for. Nothing a migration
 * sets on a connection - a {@code SET statement_timeout} - can reach the application's pool, which
 * a migration never draws from. A refused login fails at the first attempt, as it did when Flyway
 * connected by itself, and Flyway's own {@code connectRetries} still applies. Every entry point
 * connects this way, {@link #migrate(String, String, String, String...)} included.
 *
 * <p>Inside a GraalVM native image it installs an internal resource scanner automatically so
 * Flyway can enumerate bundled {@code classpath:} migrations.
 *
 * <p><b>Which database a log line is about.</b> Flyway names only the schema in what it logs -
 * {@code Migrating schema "public" to version "2"} - and every PostgreSQL shard is usually
 * {@code public}, so the lines of a sharded run cannot be told apart. While each target is
 * migrated, its shard and name are therefore put in the SLF4J MDC under {@link #MDC_SHARD}
 * ({@code 1:0}) and {@link #MDC_TARGET} ({@code mexico/member-0}), so every line Flyway writes for
 * it carries both wherever the log format or encoder includes MDC values; and one line is logged
 * before and after each target, so a plain log shows where each one starts and ends. Values a
 * caller had under those keys are put back afterwards.
 */
public final class FlywayMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(FlywayMigrator.class);

    /** MDC key holding the shard being migrated, as {@code group:member} - {@code 1:0}. */
    public static final String MDC_SHARD = "ekbatanShard";

    /** MDC key holding the name of the target being migrated - {@code mexico/member-0}. */
    public static final String MDC_TARGET = "ekbatanTarget";

    /** Conventional Flyway migration location used when no locations are supplied. */
    public static final String DEFAULT_LOCATION = "classpath:db/migration";

    private FlywayMigrator() {}

    /**
     * Runs Flyway against one datasource.
     *
     * @param dataSourceConfig the target datasource.
     * @param locations optional Flyway locations; defaults to {@link #DEFAULT_LOCATION}.
     * @return Flyway's migration result.
     */
    public static MigrateResult migrate(DataSourceConfig dataSourceConfig, String... locations) {
        Objects.requireNonNull(dataSourceConfig, "dataSourceConfig is required");
        var normalized = normalizeLocations(locations);
        return inLogContext(
                ShardIdentifier.DEFAULT, "default", () -> migrateOne(dataSourceConfig, normalized, cfg -> {}));
    }

    /**
     * Runs Flyway against one datasource given by its URL, username and password.
     *
     * <p>Exactly {@link #migrate(DataSourceConfig, String...)} with a {@link DataSourceConfig} built
     * from the three, its checks included: a URL that carries a user name or a secret, or that names
     * a database Ekbatan does not support, is refused before anything connects.
     *
     * @param jdbcUrl the JDBC URL of the target database.
     * @param username the database username used to apply migrations.
     * @param password the database password.
     * @param locations optional Flyway locations; defaults to {@link #DEFAULT_LOCATION}.
     * @return Flyway's migration result.
     */
    public static MigrateResult migrate(String jdbcUrl, String username, String password, String... locations) {
        return migrate(
                DataSourceConfig.Builder.dataSourceConfig()
                        .jdbcUrl(jdbcUrl)
                        .username(username)
                        .password(password)
                        .build(),
                locations);
    }

    /**
     * Runs Flyway against every primary shard in {@code shardingConfig}, sequentially and fail-fast.
     *
     * @param shardingConfig the Ekbatan sharding config.
     * @param locations optional Flyway locations; defaults to {@link #DEFAULT_LOCATION}.
     * @return one result per migrated shard.
     */
    public static List<Result> migrate(ShardingConfig shardingConfig, String... locations) {
        return builder().withShardingConfig(shardingConfig).locations(locations).migrate();
    }

    /**
     * Runs Flyway against the supplied shard targets, sequentially and fail-fast.
     *
     * @param targets explicit shard targets to migrate.
     * @return one result per migrated target.
     */
    public static List<Result> migrate(Target... targets) {
        return builder().withTargets(targets).migrate();
    }

    /**
     * Runs Flyway against the supplied shard targets, sequentially and fail-fast.
     *
     * @param targets explicit shard targets to migrate.
     * @return one result per migrated target.
     */
    public static List<Result> migrate(Collection<Target> targets) {
        return builder().withTargets(targets).migrate();
    }

    /**
     * Builds one target per primary shard in {@code shardingConfig}.
     *
     * @param shardingConfig the Ekbatan sharding config.
     * @return targets in the same group/member order as the config.
     */
    public static List<Target> targets(ShardingConfig shardingConfig) {
        Objects.requireNonNull(shardingConfig, "shardingConfig is required");
        List<Target> targets = new ArrayList<>();
        for (var group : shardingConfig.groups) {
            for (var member : group.members) {
                var shard = ShardIdentifier.of(group.group, member.member);
                var name = group.name + "/" + member.name.orElse("member-" + member.member);
                targets.add(target(shard, name, member.primaryConfig()));
            }
        }
        return List.copyOf(targets);
    }

    /**
     * Creates a target for a datasource on the default shard.
     *
     * @param dataSourceConfig target datasource.
     * @return a migration target.
     */
    public static Target target(DataSourceConfig dataSourceConfig) {
        return target(ShardIdentifier.DEFAULT, "default", dataSourceConfig);
    }

    /**
     * Creates a target for a shard datasource.
     *
     * @param shard shard identifier.
     * @param dataSourceConfig target datasource.
     * @return a migration target.
     */
    public static Target target(ShardIdentifier shard, DataSourceConfig dataSourceConfig) {
        return target(shard, "shard-" + shard.group + "-" + shard.member, dataSourceConfig);
    }

    /**
     * Creates a target for a named shard datasource.
     *
     * @param shard shard identifier.
     * @param name human-readable target name used in results/logging.
     * @param dataSourceConfig target datasource.
     * @return a migration target.
     */
    public static Target target(ShardIdentifier shard, String name, DataSourceConfig dataSourceConfig) {
        return new Target(shard, name, dataSourceConfig);
    }

    /** {@return a builder for advanced configuration} */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Migrates one datasource through connections made the way the application makes its own; see
     * "How a migration connects" above. The data source needs no closing: it holds no connection
     * between Flyway's calls, and Flyway closes every connection it opened before
     * {@code migrate()} returns, failed or not.
     */
    private static MigrateResult migrateOne(
            DataSourceConfig dataSourceConfig, String[] locations, Consumer<FluentConfiguration> customizer) {
        var cfg = Flyway.configure()
                .dataSource(DataSources.driverDataSource(dataSourceConfig))
                .locations(locations);
        applyCustomizerThenProvider(cfg, customizer, NativeImageFlywayResourceProvider.inNativeImage());
        return cfg.load().migrate();
    }

    /**
     * Applies the caller's customizer and then installs the native-image scanner, in that order.
     *
     * <p>Extracted from {@link #migrateOne} because the ordering of these two steps <em>is</em> the
     * behaviour: a test that performs them in its own order would prove nothing. Kept free of the
     * datasource so it stays reachable without a database.
     *
     * @param cfg the configuration being prepared.
     * @param customizer the caller's configuration customizer.
     * @param inNativeImage whether this is running inside a native image.
     */
    static void applyCustomizerThenProvider(
            FluentConfiguration cfg, Consumer<FluentConfiguration> customizer, boolean inNativeImage) {
        customizer.accept(cfg);
        installNativeResourceProvider(cfg, inNativeImage);
    }

    /**
     * Installs the native-image resource scanner, unless the caller already supplied a provider of
     * their own.
     *
     * <p>Called <em>after</em> the customizer. Built beforehand, the scanner captured
     * {@code cfg.getLocations()} while the customizer had yet to run, so a customizer that changed
     * the locations updated the configuration but not the scanner that actually enumerates the
     * files - honoured on the JVM, silently ignored in a native image, which is the pair of
     * environments a caller is most likely to assume agree.
     *
     * <p>The {@code null} check keeps the documented escape hatch working: a customizer that sets
     * its own {@link org.flywaydb.core.api.ResourceProvider} still wins, which a plain reorder
     * would have broken. Flyway leaves the property {@code null} until someone sets it.
     *
     * @param cfg the configuration being prepared.
     * @param inNativeImage whether this is running inside a native image; a parameter rather than a
     *     direct call so the behaviour is reachable from a JVM test.
     */
    static void installNativeResourceProvider(FluentConfiguration cfg, boolean inNativeImage) {
        if (!inNativeImage || cfg.getResourceProvider() != null) {
            return;
        }
        cfg.resourceProvider(new NativeImageFlywayResourceProvider(
                cfg.getLocations(), Thread.currentThread().getContextClassLoader(), StandardCharsets.UTF_8));
    }

    /**
     * Runs one target's migration with its shard and name in the MDC, and a line logged before
     * and after it.
     *
     * <p>Package-private so the MDC handling can be tested without a database. Values a caller
     * had under the two keys are put back afterwards, whether the migration succeeded or not.
     *
     * @param shard the shard being migrated.
     * @param name the target's name.
     * @param migration the migration itself.
     * @return what the migration returned.
     */
    static MigrateResult inLogContext(ShardIdentifier shard, String name, Supplier<MigrateResult> migration) {
        var label = label(shard, name);
        var previousShard = MDC.get(MDC_SHARD);
        var previousTarget = MDC.get(MDC_TARGET);
        MDC.put(MDC_SHARD, shard.group + ":" + shard.member);
        MDC.put(MDC_TARGET, name);
        try {
            LOG.info("Migrating {}", label);
            var result = migration.get();
            var version = Objects.requireNonNullElse(
                    result.targetSchemaVersion, Objects.requireNonNullElse(result.initialSchemaVersion, "<< empty >>"));
            LOG.info("{} is at version {} ({} applied)", label, version, result.migrationsExecuted);
            return result;
        } catch (RuntimeException e) {
            LOG.error("Migrating {} failed", label);
            throw e;
        } finally {
            restore(MDC_SHARD, previousShard);
            restore(MDC_TARGET, previousTarget);
        }
    }

    private static String label(ShardIdentifier shard, String name) {
        return name + " (" + shard.group + ":" + shard.member + ")";
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previous);
        }
    }

    private static String[] normalizeLocations(String[] locations) {
        if (locations == null || locations.length == 0) {
            return new String[] {DEFAULT_LOCATION};
        }
        var out = new String[locations.length];
        for (int i = 0; i < locations.length; i++) {
            out[i] = requireNotBlank(locations[i], "locations cannot contain blank values");
        }
        return out;
    }

    private static String requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    /**
     * One database target to migrate.
     *
     * @param shard the shard identifier this datasource represents.
     * @param name a human-readable target name used in result reporting.
     * @param dataSourceConfig the datasource configuration to migrate.
     */
    public record Target(ShardIdentifier shard, String name, DataSourceConfig dataSourceConfig) {

        /** Creates a validated target. */
        public Target {
            Objects.requireNonNull(shard, "shard is required");
            name = requireNotBlank(name, "name is required");
            Objects.requireNonNull(dataSourceConfig, "dataSourceConfig is required");
        }
    }

    /**
     * Flyway result associated with the shard target that produced it.
     *
     * @param target the migrated target.
     * @param migrateResult Flyway's migration result for that target.
     */
    public record Result(Target target, MigrateResult migrateResult) {

        /** Creates a validated result. */
        public Result {
            Objects.requireNonNull(target, "target is required");
            Objects.requireNonNull(migrateResult, "migrateResult is required");
        }
    }

    /** Builder for migrations that need explicit targets or Flyway customization. */
    public static final class Builder {

        private final List<Target> targets = new ArrayList<>();
        private String[] locations = new String[] {DEFAULT_LOCATION};
        private Consumer<FluentConfiguration> customizer = cfg -> {};

        private Builder() {}

        /**
         * Adds one default-shard datasource target.
         *
         * @param dataSourceConfig target datasource.
         * @return this builder.
         */
        public Builder withDataSource(DataSourceConfig dataSourceConfig) {
            return withTarget(target(dataSourceConfig));
        }

        /**
         * Adds every primary shard from {@code shardingConfig}.
         *
         * @param shardingConfig the Ekbatan sharding config.
         * @return this builder.
         */
        public Builder withShardingConfig(ShardingConfig shardingConfig) {
            return withTargets(targets(shardingConfig));
        }

        /**
         * Adds one explicit target.
         *
         * @param target target to migrate.
         * @return this builder.
         */
        public Builder withTarget(Target target) {
            targets.add(Objects.requireNonNull(target, "target is required"));
            return this;
        }

        /**
         * Adds explicit targets.
         *
         * @param targets targets to migrate.
         * @return this builder.
         */
        public Builder withTargets(Target... targets) {
            Objects.requireNonNull(targets, "targets is required");
            for (var target : targets) {
                withTarget(target);
            }
            return this;
        }

        /**
         * Adds explicit targets.
         *
         * @param targets targets to migrate.
         * @return this builder.
         */
        public Builder withTargets(Collection<Target> targets) {
            Objects.requireNonNull(targets, "targets is required");
            targets.forEach(this::withTarget);
            return this;
        }

        /**
         * Replaces Flyway migration locations.
         *
         * @param locations Flyway locations; defaults to {@link #DEFAULT_LOCATION} when empty.
         * @return this builder.
         */
        public Builder locations(String... locations) {
            this.locations = normalizeLocations(locations);
            return this;
        }

        /**
         * Customizes Flyway configuration before {@code load().migrate()}.
         *
         * <p>Runs before the native-image resource provider is installed, so changes made here -
         * including {@code locations(...)} - are what the native scanner is built from. A customizer
         * that sets its own {@link org.flywaydb.core.api.ResourceProvider} keeps it: the built-in
         * one is only installed when none was supplied. Likewise a customizer that sets a data
         * source of its own replaces the one built from the target's configuration.
         *
         * @param customizer Flyway configuration customizer.
         * @return this builder.
         */
        public Builder customize(Consumer<FluentConfiguration> customizer) {
            this.customizer = this.customizer.andThen(Objects.requireNonNull(customizer, "customizer is required"));
            return this;
        }

        /**
         * Runs migrations sequentially, failing fast on the first Flyway failure.
         *
         * @return one result per migrated target.
         */
        public List<Result> migrate() {
            if (targets.isEmpty()) {
                throw new IllegalStateException("at least one Flyway migration target is required");
            }
            List<Result> results = new ArrayList<>();
            for (var target : targets) {
                try {
                    var result = inLogContext(
                            target.shard(),
                            target.name(),
                            () -> migrateOne(target.dataSourceConfig(), locations, customizer));
                    results.add(new Result(target, result));
                } catch (RuntimeException e) {
                    // Fail-fast leaves a cluster half-migrated; say which half, since Flyway's own
                    // error names neither the shard nor the targets before it.
                    LOG.error(
                            "Stopped at {}; already migrated: {}",
                            label(target.shard(), target.name()),
                            results.stream().map(r -> r.target().name()).toList());
                    throw e;
                }
            }
            return List.copyOf(results);
        }
    }
}
