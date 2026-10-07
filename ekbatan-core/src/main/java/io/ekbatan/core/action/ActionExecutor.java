package io.ekbatan.core.action;

import static io.ekbatan.core.action.ExecutionConfiguration.Builder.executionConfiguration;

import io.ekbatan.core.action.persister.ChangePersister;
import io.ekbatan.core.action.persister.PersistableChanges;
import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.action.persister.event.single_table_json.SingleTableJsonEventPersister;
import io.ekbatan.core.domain.Persistable;
import io.ekbatan.core.internal.MapUtils;
import io.ekbatan.core.internal.Validate;
import io.ekbatan.core.repository.Repository;
import io.ekbatan.core.repository.RepositoryRegistry;
import io.ekbatan.core.shard.CrossShardException;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Executes {@link Action}s atomically against the configured shards. The framework's main
 * entry point - typical application code calls
 * {@code actionExecutor.execute(principal, MyAction.class, params)} and never touches an
 * {@code Action} directly.
 *
 * <h2>What "atomically" means</h2>
 *
 * <p>Each call: opens a fresh {@link ActionPlan} bound via {@code ScopedValue}, invokes
 * {@code Action.perform(...)} (which stages additions, updates, and events on the plan),
 * groups the staged changes by shard via each repository's
 * {@link io.ekbatan.core.shard.ShardingStrategy}, and writes everything in
 * {@link io.ekbatan.core.persistence.TransactionManager#inTransactionChecked} per shard. If
 * any per-shard transaction fails, that shard rolls back. Within a single shard the domain
 * rows and the corresponding {@code eventlog.events} rows are committed in the same transaction
 * - so the outbox is always consistent with the data it describes.
 *
 * <h2>Cross-shard behaviour</h2>
 *
 * <p>By default an action that touches more than one shard is rejected with
 * {@link io.ekbatan.core.shard.CrossShardException}. Set
 * {@link ExecutionConfiguration#allowCrossShard} to {@code true} to opt in to per-shard
 * commits (each shard commits independently - there is no 2PC). The framework logs and
 * traces the cross-shard count and shard set when this happens.
 *
 * <h2>Retries</h2>
 *
 * <p>Each {@code execute(...)} call is wrapped in a {@link Retry} driver keyed on the
 * configured {@link RetryConfig}s. Matching is exact by exception class and also checks the
 * cause chain; superclass matching is not used. The default {@link ExecutionConfiguration}
 * retries {@link io.ekbatan.core.repository.exception.StaleRecordException} once after 100ms
 * - enough to absorb a transient optimistic-lock conflict without hiding a deeper problem. A
 * retry builds a brand-new {@code ActionPlan} so each attempt is logically independent.
 *
 * <h2>Tracing</h2>
 *
 * <p>Every execution emits an {@code ekbatan.action.execute} OpenTelemetry span with nested
 * {@code ekbatan.action.perform} and {@code ekbatan.action.persist} spans, tagged with action
 * name, principal, outcome, and (when relevant) the cross-shard flag and shard set.
 *
 * <h2>EventPersister</h2>
 *
 * <p>{@link #eventPersister} is exposed as a {@code public final} field so applications that
 * want to write events outside of an action (e.g. a backfill job replaying historical state)
 * can reuse the configured persister rather than building one ad-hoc.
 */
public class ActionExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ActionExecutor.class);
    private static final Tracer TRACER = GlobalOpenTelemetry.get().getTracer("io.ekbatan.core", "1.1.1");

    private final String namespace;
    private final DatabaseRegistry databaseRegistry;
    private final ActionRegistry actionRegistry;
    private final RepositoryRegistry repositoryRegistry;
    /** The event persister installed in this executor; exposed so tests can verify its identity. */
    public final EventPersister eventPersister;

    private final ChangePersister changePersister;
    private final Clock clock;
    private final ExecutionConfiguration defaultExecutionConfiguration;

    private ActionExecutor(Builder builder) {
        this.namespace = requireValidNamespace(builder.namespace);
        this.databaseRegistry = Validate.notNull(builder.databaseRegistry, "databaseRegistry is required");
        final var objectMapper = Validate.notNull(builder.objectMapper, "objectMapper is required");
        this.actionRegistry = Validate.notNull(builder.actionRegistry, "actionRegistry is required");
        this.clock = builder.clock;

        this.repositoryRegistry = Validate.notNull(builder.repositoryRegistry, "repositoryRegistry is required");

        this.eventPersister = builder.eventPersister != null
                ? builder.eventPersister
                : new SingleTableJsonEventPersister(builder.databaseRegistry, objectMapper);

        this.changePersister = new ChangePersister(repositoryRegistry, eventPersister, clock);
        this.defaultExecutionConfiguration =
                Validate.notNull(builder.defaultExecutionConfiguration, "defaultExecutionConfiguration is required");
    }

    /**
     * Dot-separated identifiers, the same shape as a Java package. Anchored, so the whole value
     * must match rather than merely contain a match.
     */
    private static final java.util.regex.Pattern NAMESPACE =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

    /**
     * The namespace is not free text: it is a naming root, so it has to be spellable everywhere it
     * is used downstream.
     *
     * <p>It reaches Kafka topic names ({@code ekbatan.{namespace}}) and, for binary streaming, the
     * schema package a payload's {@code .proto} or {@code .avsc} declares. Protobuf's grammar is
     * {@code ident = letter { letter | decimalDigit | "_" }} - so a hyphen is a syntax error, not a
     * style preference: {@code package test.local-event-handler;} fails in protoc with
     * {@code Expected ";"}. Accepting one here would defer that failure to whoever later tried to
     * write a schema for the events.
     *
     * <p>A service name is the natural thing to put here and is conventionally hyphenated, so this
     * rejects rather than silently rewriting: turning {@code my-svc} into {@code my_svc} would
     * change the topic name and the value stored on every outbox row without the operator asking.
     */
    private static String requireValidNamespace(String namespace) {
        Validate.notBlank(namespace, "namespace is required");
        Validate.isTrue(
                NAMESPACE.matcher(namespace).matches(),
                "namespace '%s' must be dot-separated identifiers, like a Java package"
                        + " (letters, digits and underscore; each segment starting with a letter or"
                        + " underscore). Hyphens are not legal in a protobuf package or Avro namespace,"
                        + " and the namespace is used as one - write 'my_service' rather than 'my-service'.",
                namespace);
        return namespace;
    }

    /**
     * Executes an action with the configured default {@link ExecutionConfiguration}. The
     * action's plan and any emitted events are committed in a single per-shard transaction
     * after {@code perform} returns.
     *
     * @param principal the caller principal (may be null).
     * @param actionClass the action class to dispatch.
     * @param params typed input for the action.
     * @param <P> the action's parameter type.
     * @param <R> the action's result type.
     * @param <A> the concrete action type.
     * @return the action's typed result.
     * @throws Exception thrown by the action's {@code perform}; the plan is rolled back.
     */
    public <P, R, A extends Action<P, R>> R execute(Principal principal, Class<A> actionClass, P params)
            throws Exception {
        return execute(principal, actionClass, params, defaultExecutionConfiguration);
    }

    /**
     * Executes an action with an explicit per-call {@link ExecutionConfiguration} (retry policy,
     * cross-shard allowance, etc.).
     *
     * @param principal the caller principal (may be null).
     * @param actionClass the action class to dispatch.
     * @param params typed input for the action.
     * @param executionConfiguration per-call execution policy.
     * @param <P> the action's parameter type.
     * @param <R> the action's result type.
     * @param <A> the concrete action type.
     * @return the action's typed result.
     * @throws Exception thrown by the action's {@code perform}; the plan is rolled back.
     */
    public <P, R, A extends Action<P, R>> R execute(
            Principal principal, Class<A> actionClass, P params, ExecutionConfiguration executionConfiguration)
            throws Exception {
        Validate.notNull(actionClass, "actionClass cannot be null");
        Validate.notNull(executionConfiguration, "executionConfiguration cannot be null");

        final var action = actionRegistry.get(actionClass);
        final var actionName = actionClass.getSimpleName();
        final var principalName = principal != null ? principal.getName() : "";
        final var startTime = clock.instant();

        LOG.info("Executing {} [principal={}]", actionName, principalName);

        final var actionSpan = TRACER.spanBuilder("ekbatan.action.execute")
                .setAttribute("ekbatan.action.name", actionName)
                .setAttribute("ekbatan.action.principal", principalName)
                .startSpan();
        try (var _ = actionSpan.makeCurrent()) {
            final var result = Retry.<R>with(executionConfiguration.retryConfigs, actionName)
                    .execute(() -> {
                        final var actionStartDate = clock.instant();

                        // Per-call plan - fresh on each execute() (and on each retry attempt).
                        // Action is a singleton; the plan is never stored on it.
                        final var plan = new ActionPlan();

                        final var performSpan =
                                TRACER.spanBuilder("ekbatan.action.perform").startSpan();
                        final R performResult;
                        try (var _ = performSpan.makeCurrent()) {
                            performResult = action.runIn(plan, principal, params);
                        } catch (Throwable t) {
                            // Throwable: runIn -> Action.perform is arbitrary user code, and an
                            // Error from it is still a failed action. Caught as Exception it
                            // skipped both lines below while the finally still ended the span, so
                            // a perform that died was exported looking successful.
                            performSpan.setStatus(StatusCode.ERROR, t.getMessage());
                            performSpan.recordException(t);
                            throw t;
                        } finally {
                            performSpan.end();
                        }

                        persistChanges(action, plan, params, actionStartDate, executionConfiguration);
                        return performResult;
                    });

            actionSpan.setAttribute("ekbatan.action.outcome", "success");
            LOG.info(
                    "{} completed in {}ms [principal={}]",
                    actionName,
                    Duration.between(startTime, clock.instant()).toMillis(),
                    principalName);
            return result;
        } catch (Throwable t) {
            // An Error killing an action must still be reported. Caught as Exception it skipped the
            // outcome attribute, the span status, the recorded exception AND the error log, leaving
            // no trace anywhere that the action had failed.
            actionSpan.setAttribute("ekbatan.action.outcome", "error");
            actionSpan.setStatus(StatusCode.ERROR, t.getMessage());
            actionSpan.recordException(t);
            LOG.error("{} failed: {}: {}", actionName, t.getClass().getSimpleName(), t.getMessage());
            throw t;
        } finally {
            actionSpan.end();
        }
    }

    private void persistChanges(
            Action<?, ?> action, ActionPlan plan, Object params, Instant actionStartDate, ExecutionConfiguration config)
            throws Exception {
        final var persistSpan = TRACER.spanBuilder("ekbatan.action.persist").startSpan();
        try (var _ = persistSpan.makeCurrent()) {
            var changesByShard = groupChangesByShard(plan);

            if (!changesByShard.isEmpty()) {
                LOG.debug(
                        "Resolved {} entity types across {} shards [shards={}]",
                        plan.changes().size(),
                        changesByShard.size(),
                        changesByShard.keySet());
            }

            enforceSingleShard(action.getClass().getSimpleName(), changesByShard.keySet(), config);

            if (changesByShard.size() > 1) {
                persistSpan.setAttribute("ekbatan.shard.cross_shard", true);
                LOG.warn(
                        "{} spans {} shards: {} [allowCrossShard=true]",
                        action.getClass().getSimpleName(),
                        changesByShard.size(),
                        changesByShard.keySet());
            }

            if (changesByShard.isEmpty()) {
                changesByShard = Map.of(databaseRegistry.defaultShard, plan.changes());
            }

            var actionEventId = java.util.UUID.randomUUID();
            var committedShards = new ArrayList<ShardIdentifier>();

            for (var entry : changesByShard.entrySet()) {
                var shard = entry.getKey();
                var shardChanges = entry.getValue();
                try {
                    databaseRegistry.transactionManager(shard).inTransactionChecked(_ -> {
                        changePersister.persist(
                                namespace,
                                action.getClass().getSimpleName(),
                                params,
                                actionStartDate,
                                shardChanges,
                                shard,
                                actionEventId);
                    });
                    committedShards.add(shard);
                } catch (Throwable t) {
                    // The most consequential of these: this is the partial-commit alarm. A
                    // cross-shard action that commits shard A and fails on shard B does NOT roll
                    // back automatically, and this log plus its span attributes are the only
                    // notice anyone gets. Caught as Exception, an Error on shard B produced
                    // partially-committed data in silence.
                    if (!committedShards.isEmpty()) {
                        LOG.error(
                                "CRITICAL: Cross-shard action {} PARTIALLY COMMITTED! "
                                        + "Committed shards: {}, Failed shard: {}. Error: {}: {}. "
                                        + "Data on committed shards remains persisted and will NOT roll back automatically.",
                                action.getClass().getSimpleName(),
                                committedShards,
                                shard,
                                t.getClass().getSimpleName(),
                                t.getMessage(),
                                t);
                        persistSpan.setAttribute("ekbatan.shard.partial_commit_failure", true);
                        persistSpan.setAttribute("ekbatan.shard.committed_shards", committedShards.toString());
                        persistSpan.setAttribute("ekbatan.shard.failed_shard", shard.toString());
                    }
                    throw t;
                }
            }
        } catch (Throwable t) {
            persistSpan.setStatus(StatusCode.ERROR, t.getMessage());
            persistSpan.recordException(t);
            throw t;
        } finally {
            persistSpan.end();
        }
    }

    private Map<ShardIdentifier, Map<Class<? extends Persistable<?>>, PersistableChanges<?, ?>>> groupChangesByShard(
            ActionPlan plan) {
        var result =
                new LinkedHashMap<ShardIdentifier, Map<Class<? extends Persistable<?>>, PersistableChanges<?, ?>>>();

        if (!plan.hasChanges()) {
            return result;
        }

        for (var entry : plan.changes().entrySet()) {
            var entityClass = entry.getKey();
            var changes = entry.getValue();
            var repository = repositoryRegistry.repository(entityClass);
            if (repository == null) {
                // Checked here rather than left to ChangePersister, which says the same thing
                // further down. Grouping needs the repository's ShardingStrategy first, so an
                // unregistered type reached that line as a NullPointerException naming
                // shardingStrategy() - and the message written for exactly this case was
                // unreachable through the executor.
                throw new IllegalStateException("No repository found for entity type: " + entityClass.getName()
                        + ". Register one with RepositoryRegistry.Builder.withModelRepository("
                        + entityClass.getSimpleName() + ".class, ...) - an action cannot stage a change to a"
                        + " type the executor has no way to write.");
            }
            groupEntityChangesByShard(repository, entityClass, changes, result);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private <ID extends Comparable<ID>, E extends Persistable<ID>> void groupEntityChangesByShard(
            Repository<?> repository,
            Class<? extends Persistable<?>> entityClass,
            PersistableChanges<?, ?> changes,
            Map<ShardIdentifier, Map<Class<? extends Persistable<?>>, PersistableChanges<?, ?>>> result) {

        var typedChanges = (PersistableChanges<ID, E>) changes;

        if (MapUtils.isNotEmpty(typedChanges.additions())) {
            for (var mapEntry : typedChanges.additions().entrySet()) {
                var shard = resolveShard(repository, mapEntry.getValue());
                var shardChanges = (PersistableChanges<ID, E>) result.computeIfAbsent(shard, _ -> new LinkedHashMap<>())
                        .computeIfAbsent(entityClass, _ -> new PersistableChanges<>());
                shardChanges.add(mapEntry.getValue());
            }
        }

        if (MapUtils.isNotEmpty(typedChanges.updates())) {
            for (var mapEntry : typedChanges.updates().entrySet()) {
                var shard = resolveShard(repository, mapEntry.getValue());
                var shardChanges = (PersistableChanges<ID, E>) result.computeIfAbsent(shard, _ -> new LinkedHashMap<>())
                        .computeIfAbsent(entityClass, _ -> new PersistableChanges<>());
                shardChanges.update(mapEntry.getValue());
            }
        }
    }

    private ShardIdentifier resolveShard(Repository<?> repository, Persistable<?> persistable) {
        var shard = repository
                .shardingStrategy()
                .resolveShardIdentifier(persistable)
                .orElse(databaseRegistry.defaultShard);
        return databaseRegistry.effectiveShard(shard);
    }

    private void enforceSingleShard(
            String actionName, Set<ShardIdentifier> shards, ExecutionConfiguration executionConfiguration) {
        if (shards.size() > 1 && !executionConfiguration.allowCrossShard) {
            LOG.error("{} rejected: spans {} shards {} but cross-shard is disabled", actionName, shards.size(), shards);
            var iterator = shards.iterator();
            throw new CrossShardException(iterator.next(), iterator.next());
        }
    }

    /** Fluent builder for {@link ActionExecutor}. Obtain via {@link #actionExecutor()}. */
    public static final class Builder {
        private String namespace;
        private DatabaseRegistry databaseRegistry;
        private ObjectMapper objectMapper;
        private RepositoryRegistry repositoryRegistry;
        private ActionRegistry actionRegistry;
        private EventPersister eventPersister;
        private Clock clock = Clock.systemUTC();
        private ExecutionConfiguration defaultExecutionConfiguration =
                executionConfiguration().build();

        private Builder() {}

        /**
         * Sets the namespace recorded on every persisted event. Required.
         *
         * @param namespace logical namespace string.
         * @return this builder, for chaining.
         */
        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        /** {@return a fresh builder for {@link ActionExecutor}} */
        public static Builder actionExecutor() {
            return new Builder();
        }

        /**
         * Sets the database registry. Required.
         *
         * @param databaseRegistry the registry of per-shard pools / transaction managers.
         * @return this builder, for chaining.
         */
        public Builder databaseRegistry(DatabaseRegistry databaseRegistry) {
            this.databaseRegistry = databaseRegistry;
            return this;
        }

        /**
         * Sets the Jackson mapper used to serialize event payloads. Required.
         *
         * @param objectMapper a configured Jackson {@link ObjectMapper}.
         * @return this builder, for chaining.
         */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        /**
         * Sets the repository registry. Required.
         *
         * @param repositoryRegistry the registry of {@code @EkbatanRepository}s.
         * @return this builder, for chaining.
         */
        public Builder repositoryRegistry(RepositoryRegistry repositoryRegistry) {
            this.repositoryRegistry = repositoryRegistry;
            return this;
        }

        /**
         * Sets the action registry. Required.
         *
         * @param actionRegistry the registry of {@code @EkbatanAction}s.
         * @return this builder, for chaining.
         */
        public Builder actionRegistry(ActionRegistry actionRegistry) {
            this.actionRegistry = actionRegistry;
            return this;
        }

        /**
         * Overrides the default {@link EventPersister} (single-table JSON). Useful for callers
         * that need to encrypt payloads or write to a custom table layout.
         *
         * @param eventPersister a custom persister; pass null to keep the default.
         * @return this builder, for chaining.
         */
        public Builder eventPersister(EventPersister eventPersister) {
            this.eventPersister = eventPersister;
            return this;
        }

        /**
         * Sets the clock used for event timestamps. Defaults to {@link Clock#systemUTC()}.
         *
         * @param clock the clock.
         * @return this builder, for chaining.
         */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Sets the default per-call execution configuration (retry policy, cross-shard policy).
         *
         * @param defaultExecutionConfiguration the default configuration.
         * @return this builder, for chaining.
         */
        public Builder defaultExecutionConfiguration(ExecutionConfiguration defaultExecutionConfiguration) {
            this.defaultExecutionConfiguration = defaultExecutionConfiguration;
            return this;
        }

        /** {@return a configured {@link ActionExecutor}; throws if required fields are unset} */
        public ActionExecutor build() {
            return new ActionExecutor(this);
        }
    }
}
