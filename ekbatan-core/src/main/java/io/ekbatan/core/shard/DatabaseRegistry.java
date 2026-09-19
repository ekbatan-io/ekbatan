package io.ekbatan.core.shard;

import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;

import io.ekbatan.core.config.ShardingConfig;
import io.ekbatan.core.internal.Validate;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * The framework's view of every database it can talk to. Maps each {@link ShardIdentifier}
 * to its {@link TransactionManager} (which owns the Hikari pools and SQL dialect) and to
 * primary / secondary jOOQ {@link DSLContext}s built on top.
 *
 * <p>{@link io.ekbatan.core.action.ActionExecutor} consults this registry when persisting
 * an {@link io.ekbatan.core.action.ActionPlan}: it groups changes by shard via each
 * repository's {@link ShardingStrategy}, then opens a per-shard transaction via
 * {@link #transactionManager(ShardIdentifier)}.
 *
 * <p>Build manually via {@link Builder#withDatabase(TransactionManager)} /
 * {@link Builder#withDefaultDatabase(TransactionManager)}, or - when wiring from external
 * config - via {@link #fromConfig(io.ekbatan.core.config.ShardingConfig)} which reads
 * the same shape that Spring / Quarkus / Micronaut auto-configure produces from
 * {@code ekbatan.sharding.*} keys.
 *
 * <p>The registry is {@link AutoCloseable}; DI containers wire {@link #close()} as the
 * destroy-method so all pools shut down when the application context shuts down.
 */
public final class DatabaseRegistry implements AutoCloseable {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DatabaseRegistry.class);

    private final Map<ShardIdentifier, TransactionManager> transactionManagers;

    /** Per-shard primary (writer) {@link DSLContext}s built on top of each {@link TransactionManager}'s primary pool. */
    public final Map<ShardIdentifier, DSLContext> primary;

    /** Per-shard secondary (read-replica) {@link DSLContext}s; falls back to the primary if no replica is configured. */
    public final Map<ShardIdentifier, DSLContext> secondary;

    /** The shard the executor routes to when an action declines to specify one. */
    public final ShardIdentifier defaultShard;

    /** What to do with a shard this deployment has not configured. */
    public final UnknownShardPolicy unknownShardPolicy;

    private DatabaseRegistry(Builder builder) {
        this.transactionManagers = Collections.unmodifiableMap(builder.transactionManagers);
        this.unknownShardPolicy = builder.unknownShardPolicy;
        Validate.isTrue(!this.transactionManagers.isEmpty(), "at least one database is required");
        this.defaultShard = builder.defaultShard.orElseGet(() -> {
            Validate.isTrue(
                    this.transactionManagers.size() == 1,
                    "withDefaultDatabase must be called when more than one database is registered");
            return this.transactionManagers.keySet().iterator().next();
        });

        var p = new HashMap<ShardIdentifier, DSLContext>();
        var s = new HashMap<ShardIdentifier, DSLContext>();
        this.transactionManagers.forEach((id, tm) -> {
            p.put(id, DSL.using(tm.primaryConnectionProvider.getDataSource(), tm.dialect));
            s.put(id, DSL.using(tm.secondaryConnectionProvider.getDataSource(), tm.dialect));
        });
        this.primary = Map.copyOf(p);
        this.secondary = Map.copyOf(s);
    }

    /**
     * Looks up the {@link TransactionManager} for a given shard.
     *
     * @param id the shard identifier.
     * @return the matching transaction manager.
     * @throws IllegalArgumentException if no database is registered for this shard.
     */
    public TransactionManager transactionManager(ShardIdentifier id) {
        var tm = transactionManagers.get(id);
        if (tm == null) {
            throw new IllegalArgumentException("No database registered for shard: " + id);
        }
        return tm;
    }

    /**
     * Returns the requested shard if registered, otherwise the default shard. Used by
     * sharding strategies to gracefully degrade when a strategy proposes a shard that
     * doesn't exist in the current deployment.
     *
     * <p>Degrading gracefully is the point, and it is also what a mistyped shard identifier
     * does: it writes to the default, successfully, where nobody looks for the row afterwards.
     * Nothing here can tell a foreign id apart from a bug, so this no longer tries to - it logs
     * every fallback it takes. A deployment holding the whole layout, where an unregistered
     * shard can only be a bug, can configure {@link UnknownShardPolicy#FAIL} instead.
     *
     * @param shard the proposed shard.
     * @return {@code shard} if registered, otherwise {@link #defaultShard}.
     * @throws IllegalArgumentException if it is not registered and the policy is
     *     {@link UnknownShardPolicy#FAIL}.
     */
    public ShardIdentifier effectiveShard(ShardIdentifier shard) {
        if (transactionManagers.containsKey(shard)) {
            return shard;
        }
        if (unknownShardPolicy == UnknownShardPolicy.FAIL) {
            throw new IllegalArgumentException("Shard " + shard
                    + " is not registered and unknownShardPolicy is FAIL; registered: "
                    + transactionManagers.keySet());
        }
        LOG.warn(
                "Shard {} is not registered, routing to default {} [registered={}]",
                shard,
                defaultShard,
                transactionManagers.keySet());
        return defaultShard;
    }

    /** {@return the {@link TransactionManager} for the default shard} */
    public TransactionManager defaultTransactionManager() {
        return transactionManager(defaultShard);
    }

    /** {@return every registered transaction manager, in insertion order} */
    public Collection<TransactionManager> allTransactionManagers() {
        return transactionManagers.values();
    }

    /**
     * Closes every shard's {@link TransactionManager} (and thus its underlying Hikari pools).
     * Failures on individual shards are collected and reported together so a single
     * misbehaving shard doesn't strand the rest.
     *
     * <p>This is invoked automatically when the registry is exposed as a Spring/Quarkus/Micronaut
     * managed bean - Spring's {@code (inferred)} destroy-method detection finds this {@code close}
     * on context shutdown.
     */
    @Override
    public void close() {
        RuntimeException firstError = null;
        for (var entry : transactionManagers.entrySet()) {
            try {
                entry.getValue().close();
            } catch (RuntimeException e) {
                LOG.warn("Failed to close TransactionManager for shard {}: {}", entry.getKey(), e.toString());
                if (firstError == null) {
                    firstError = e;
                } else {
                    firstError.addSuppressed(e);
                }
            }
        }
        if (firstError != null) {
            throw firstError;
        }
    }

    /**
     * Builds a registry from an externally-loaded {@link ShardingConfig} - the canonical entry
     * point when wiring from {@code ekbatan.sharding.*} configuration.
     *
     * @param config the sharding configuration.
     * @return a fully built registry with one transaction manager per shard member.
     */
    public static DatabaseRegistry fromConfig(ShardingConfig config) {
        // Checked before a single pool is opened. This ran after the loop, which meant a mistyped
        // default-shard - the one error anyone actually makes here - built every pool first and
        // then threw. Nothing held a reference to them afterwards, so each abandoned pool kept its
        // Hikari housekeeper thread alive for the life of the JVM. Usually invisible, because a
        // failed startup normally ends in process exit; not invisible in a test suite, or wherever
        // something catches the failure and retries.
        //
        // The check needs no pools at all - it is a scan of the config - so the ordering was only
        // ever accidental.
        Validate.isTrue(
                config.groups.stream()
                        .flatMap(group ->
                                group.members.stream().map(member -> ShardIdentifier.of(group.group, member.member)))
                        .anyMatch(id -> id.equals(config.defaultShard)),
                "defaultShard must reference a registered database");

        var builder = databaseRegistry().withUnknownShardPolicy(config.unknownShardPolicy);

        for (var group : config.groups) {
            for (var member : group.members) {
                var shardIdentifier = ShardIdentifier.of(group.group, member.member);
                var primaryDataSourceConfig = member.primaryConfig();
                var primaryProvider = ConnectionProvider.hikariConnectionProvider(primaryDataSourceConfig);
                // Fall back on the primary *provider*, not on its config. Falling back on the
                // config still ran the factory a second time, and identical settings do not
                // produce the same pool - they produce a second one against the same database,
                // with its own connections and housekeeping threads. A member with no replica
                // therefore opened twice the configured maximum: eight shards at 20 held 320
                // connections where the operator had budgeted 160, invisibly, because both pools
                // were perfectly healthy. TransactionManager#close already assumes sharing - it
                // closes the secondary only when it is a different object.
                var secondaryProvider = member.secondaryConfig()
                        .map(ConnectionProvider::hikariConnectionProvider)
                        .orElse(primaryProvider);
                var tm = new TransactionManager(
                        primaryProvider, secondaryProvider, primaryDataSourceConfig.dialect, shardIdentifier);
                if (shardIdentifier.equals(config.defaultShard)) {
                    builder.withDefaultDatabase(tm);
                } else {
                    builder.withDatabase(tm);
                }
            }
        }

        return builder.build();
    }

    /** Fluent builder for {@link DatabaseRegistry}. Obtain via {@link #databaseRegistry()}. */
    public static final class Builder {

        private final Map<ShardIdentifier, TransactionManager> transactionManagers = new LinkedHashMap<>();
        private Optional<ShardIdentifier> defaultShard = Optional.empty();
        private UnknownShardPolicy unknownShardPolicy = UnknownShardPolicy.USE_DEFAULT_SHARD;

        private Builder() {}

        /** {@return a fresh builder for {@link DatabaseRegistry}} */
        public static Builder databaseRegistry() {
            return new Builder();
        }

        /**
         * Registers a non-default shard.
         *
         * @param tm the transaction manager for this shard.
         * @return this builder, for chaining.
         */
        public Builder withDatabase(TransactionManager tm) {
            this.transactionManagers.put(tm.shardIdentifier, tm);
            return this;
        }

        /**
         * Registers the default shard - the one the executor routes to when an action doesn't
         * specify. Exactly one default is required (and is implicit when only one database is
         * registered).
         *
         * @param tm the transaction manager for the default shard.
         * @return this builder, for chaining.
         */
        public Builder withDefaultDatabase(TransactionManager tm) {
            this.transactionManagers.put(tm.shardIdentifier, tm);
            this.defaultShard = Optional.of(tm.shardIdentifier);
            return this;
        }

        /**
         * Sets what to do with a shard that is not registered here. Optional; omitting it
         * selects {@link UnknownShardPolicy#USE_DEFAULT_SHARD}.
         *
         * @param unknownShardPolicy the policy.
         * @return this builder, for chaining.
         */
        public Builder withUnknownShardPolicy(UnknownShardPolicy unknownShardPolicy) {
            this.unknownShardPolicy =
                    unknownShardPolicy == null ? UnknownShardPolicy.USE_DEFAULT_SHARD : unknownShardPolicy;
            return this;
        }

        /** {@return a configured {@link DatabaseRegistry}} */
        public DatabaseRegistry build() {
            return new DatabaseRegistry(this);
        }
    }
}
