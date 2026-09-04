package io.ekbatan.core.action;

import static io.ekbatan.core.action.ActionExecutor.Builder.actionExecutor;
import static io.ekbatan.core.action.ActionRegistry.Builder.actionRegistry;
import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.zaxxer.hikari.HikariDataSource;
import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.domain.GenericState;
import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.Repository;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.testsupport.time.VirtualClock;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.ObjectMapper;

// Mockito + GraalVM native image is currently blocked upstream on JDK 25:
//   - GraalVM CE/Oracle 25.0.x bundle ASM 9.7.1 which can't read JDK 25 class files,
//     surfacing as "Unsupported class file major version 69" in
//     ClassPredefinitionFeature when the agent's predefined-class blobs are loaded
//     (oracle/graal#12723, GR-72123 - backport not high priority).
//   - GraalVM 26 EA fixes that via the new ClassFileAPI but trips a different
//     ClassFileAPI bug ("Could not resolve class ChannelMessage") on agent-recorded
//     bytecode.
// JVM coverage stays full; the JDBC contract for the framework's keyed-lock providers
// is exercised end-to-end by Testcontainers in
// ekbatan-integration-tests:keyed-lock-provider:{pg,mysql,mariadb}. Remove this
// annotation once GraalVM ships ASM 9.8 or a stable ClassFileAPI predefinition path.
@DisabledInNativeImage
class ActionExecutorTest {

    // --- Test model ---

    static class ItemEvent extends ModelEvent<Item> {
        ItemEvent(Id<Item> id) {
            super(id.getValue().toString(), Item.class);
        }
    }

    static class Item extends Model<Item, Id<Item>, GenericState> {
        public final String name;

        Item(Builder builder) {
            super(builder);
            this.name = builder.name;
        }

        @Override
        public Builder copy() {
            return Builder.item().copyBase(this).name(name);
        }

        static Builder createItem(String name, Instant createdDate) {
            var id = Id.random(Item.class);
            return Builder.item()
                    .id(id)
                    .state(GenericState.ACTIVE)
                    .name(name)
                    .createdDate(createdDate)
                    .withInitialVersion()
                    .withEvent(new ItemEvent(id));
        }

        static class Builder extends Model.Builder<Id<Item>, Builder, Item, GenericState> {
            String name;

            static Builder item() {
                return new Builder();
            }

            Builder name(String name) {
                this.name = name;
                return self();
            }

            @Override
            public Item build() {
                return new Item(this);
            }
        }
    }

    // --- Test actions ---

    static class CreateItemAction extends Action<CreateItemAction.Params, Item> {
        record Params(String name) {}

        CreateItemAction(java.time.Clock clock) {
            super(clock);
        }

        @Override
        protected Item perform(Principal principal, Params params) {
            return plan().add(Item.createItem(params.name, clock.instant()).build());
        }
    }

    static class FailingItemAction extends Action<FailingItemAction.Params, Item> {
        private final AtomicInteger attempts;

        record Params(int failUntilAttempt) {}

        FailingItemAction(java.time.Clock clock, AtomicInteger attempts) {
            super(clock);
            this.attempts = attempts;
        }

        @Override
        protected Item perform(Principal principal, Params params) {
            if (attempts.incrementAndGet() <= params.failUntilAttempt) {
                throw new io.ekbatan.core.repository.exception.StaleRecordException("stale", null);
            }
            return plan().add(Item.createItem("recovered", clock.instant()).build());
        }
    }

    // --- Recording test doubles ---

    static class RecordingRepository implements Repository<Item> {
        final List<Item> added = new ArrayList<>();
        final List<Item> updated = new ArrayList<>();

        @Override
        public io.ekbatan.core.shard.ShardingStrategy<?> shardingStrategy() {
            return new io.ekbatan.core.shard.NoShardingStrategy<>();
        }

        @Override
        public Item add(Item model) {
            added.add(model);
            return model;
        }

        @Override
        public void addNoResult(Item model) {
            added.add(model);
        }

        @Override
        public List<Item> addAll(Collection<Item> models) {
            added.addAll(models);
            return List.copyOf(models);
        }

        @Override
        public void addAllNoResult(Collection<Item> models) {
            added.addAll(models);
        }

        @Override
        public Item update(Item model) {
            updated.add(model);
            return model;
        }

        @Override
        public void updateNoResult(Item model) {
            updated.add(model);
        }

        @Override
        public List<Item> updateAll(Collection<Item> models) {
            updated.addAll(models);
            return List.copyOf(models);
        }

        @Override
        public void updateAllNoResult(Collection<Item> models) {
            updated.addAll(models);
        }

        @Override
        public List<Item> findAll() {
            return List.of();
        }
    }

    static class RecordingEventPersister implements EventPersister {
        final List<String> actionNames = new ArrayList<>();
        final List<Collection<ModelEvent<?>>> allModelEvents = new ArrayList<>();

        @Override
        public void persistActionEvents(
                String namespace,
                String actionName,
                Instant startedDate,
                Instant completionDate,
                Object actionParams,
                Collection<ModelEvent<?>> modelEvents,
                io.ekbatan.core.shard.ShardIdentifier shard,
                java.util.UUID actionEventId) {
            actionNames.add(actionName);
            allModelEvents.add(modelEvents);
        }
    }

    // --- Test setup ---

    private TransactionManager transactionManager;
    private DatabaseRegistry databaseRegistry;
    private VirtualClock clock;

    @BeforeEach
    void setUp() throws Exception {
        var mockPrimaryProvider = mock(ConnectionProvider.class);
        var mockSecondaryProvider = mock(ConnectionProvider.class);
        var mockDataSource = mock(HikariDataSource.class);
        org.mockito.Mockito.when(mockPrimaryProvider.getDataSource()).thenReturn(mockDataSource);
        org.mockito.Mockito.when(mockSecondaryProvider.getDataSource()).thenReturn(mockDataSource);
        transactionManager = org.mockito.Mockito.spy(
                new TransactionManager(mockPrimaryProvider, mockSecondaryProvider, SQLDialect.POSTGRES));
        databaseRegistry = databaseRegistry().withDatabase(transactionManager).build();
        clock = new VirtualClock();

        // Make inTransactionChecked execute the block directly (no real DB)
        doAnswer(invocation -> {
                    TransactionManager.CheckedConsumer<DSLContext> consumer = invocation.getArgument(0);
                    consumer.accept(null);
                    return null;
                })
                .when(transactionManager)
                .inTransactionChecked(ArgumentMatchers.<TransactionManager.CheckedConsumer<DSLContext>>any());
    }

    private ActionExecutor buildExecutor(
            RecordingRepository repo, RecordingEventPersister eventPersister, ActionRegistry actionRegistry) {
        return actionExecutor()
                .namespace("test.namespace")
                .databaseRegistry(databaseRegistry)
                .objectMapper(new ObjectMapper())
                .repositoryRegistry(repositoryRegistry()
                        .withModelRepository(Item.class, repo)
                        .build())
                .actionRegistry(actionRegistry)
                .eventPersister(eventPersister)
                .clock(clock)
                .build();
    }

    // --- Tests ---

    @Test
    void execute_calls_action_perform_and_returns_result() throws Exception {
        // GIVEN
        var repo = new RecordingRepository();
        var eventPersister = new RecordingEventPersister();
        var executor = buildExecutor(
                repo,
                eventPersister,
                actionRegistry()
                        .withAction(CreateItemAction.class, new CreateItemAction(clock))
                        .build());

        // WHEN
        var result = executor.execute(() -> "user", CreateItemAction.class, new CreateItemAction.Params("wallet"));

        // THEN
        assertThat(result).isNotNull();
        assertThat(result.name).isEqualTo("wallet");
    }

    @Test
    void execute_persists_additions_via_repository() throws Exception {
        // GIVEN
        var repo = new RecordingRepository();
        var eventPersister = new RecordingEventPersister();
        var executor = buildExecutor(
                repo,
                eventPersister,
                actionRegistry()
                        .withAction(CreateItemAction.class, new CreateItemAction(clock))
                        .build());

        // WHEN
        executor.execute(() -> "user", CreateItemAction.class, new CreateItemAction.Params("wallet"));

        // THEN
        assertThat(repo.added).hasSize(1);
        assertThat(repo.added.getFirst().name).isEqualTo("wallet");
    }

    @Test
    void execute_persists_events_via_event_persister() throws Exception {
        // GIVEN
        var repo = new RecordingRepository();
        var eventPersister = new RecordingEventPersister();
        var executor = buildExecutor(
                repo,
                eventPersister,
                actionRegistry()
                        .withAction(CreateItemAction.class, new CreateItemAction(clock))
                        .build());

        // WHEN
        executor.execute(() -> "user", CreateItemAction.class, new CreateItemAction.Params("wallet"));

        // THEN
        assertThat(eventPersister.actionNames).containsExactly("CreateItemAction");
        assertThat(eventPersister.allModelEvents).hasSize(1);
        assertThat(eventPersister.allModelEvents.getFirst()).hasSize(1);
        assertThat(eventPersister.allModelEvents.getFirst().iterator().next()).isInstanceOf(ItemEvent.class);
    }

    @Test
    void execute_wraps_persistence_in_transaction() throws Exception {
        // GIVEN
        var repo = new RecordingRepository();
        var eventPersister = new RecordingEventPersister();
        var executor = buildExecutor(
                repo,
                eventPersister,
                actionRegistry()
                        .withAction(CreateItemAction.class, new CreateItemAction(clock))
                        .build());

        // WHEN
        executor.execute(() -> "user", CreateItemAction.class, new CreateItemAction.Params("wallet"));

        // THEN
        verify(transactionManager, times(1))
                .inTransactionChecked(ArgumentMatchers.<TransactionManager.CheckedConsumer<DSLContext>>any());
    }

    @Test
    void execute_uses_virtual_clock_for_created_date() throws Exception {
        // GIVEN
        clock.pauseAt(Instant.parse("2025-06-01T12:00:00Z"));
        var repo = new RecordingRepository();
        var eventPersister = new RecordingEventPersister();
        var executor = buildExecutor(
                repo,
                eventPersister,
                actionRegistry()
                        .withAction(CreateItemAction.class, new CreateItemAction(clock))
                        .build());

        // WHEN
        var result = executor.execute(() -> "user", CreateItemAction.class, new CreateItemAction.Params("wallet"));

        // THEN
        assertThat(result.createdDate).isEqualTo(Instant.parse("2025-06-01T12:00:00Z"));
    }

    @Test
    void execute_retries_on_stale_record_exception() throws Exception {
        // GIVEN
        var attempts = new AtomicInteger(0);
        var repo = new RecordingRepository();
        var eventPersister = new RecordingEventPersister();
        var executor = buildExecutor(
                repo,
                eventPersister,
                actionRegistry()
                        .withAction(FailingItemAction.class, new FailingItemAction(clock, attempts))
                        .build());

        // WHEN
        var result = executor.execute(() -> "user", FailingItemAction.class, new FailingItemAction.Params(1));

        // THEN
        assertThat(result).isNotNull();
        assertThat(result.name).isEqualTo("recovered");
        assertThat(attempts.get()).isEqualTo(2); // 1 failure + 1 success
    }

    @Test
    void execute_throws_after_retry_exhausted() {
        // GIVEN
        var attempts = new AtomicInteger(0);
        var repo = new RecordingRepository();
        var eventPersister = new RecordingEventPersister();
        var executor = buildExecutor(
                repo,
                eventPersister,
                actionRegistry()
                        .withAction(FailingItemAction.class, new FailingItemAction(clock, attempts))
                        .build());

        // WHEN / THEN - default config retries StaleRecordException 1 time, so failing 2 times exhausts retries
        assertThatThrownBy(
                        () -> executor.execute(() -> "user", FailingItemAction.class, new FailingItemAction.Params(5)))
                .isInstanceOf(io.ekbatan.core.repository.exception.StaleRecordException.class);
    }

    @Test
    void execute_with_custom_execution_configuration() throws Exception {
        // GIVEN
        var attempts = new AtomicInteger(0);
        var repo = new RecordingRepository();
        var eventPersister = new RecordingEventPersister();
        var executor = buildExecutor(
                repo,
                eventPersister,
                actionRegistry()
                        .withAction(FailingItemAction.class, new FailingItemAction(clock, attempts))
                        .build());

        var config = ExecutionConfiguration.Builder.executionConfiguration()
                .withRetry(
                        io.ekbatan.core.repository.exception.StaleRecordException.class,
                        new RetryConfig(3, Duration.ZERO))
                .build();

        // WHEN
        var result = executor.execute(() -> "user", FailingItemAction.class, new FailingItemAction.Params(3), config);

        // THEN
        assertThat(result.name).isEqualTo("recovered");
        assertThat(attempts.get()).isEqualTo(4); // 3 failures + 1 success
    }

    @Test
    void execute_with_no_retry_configuration() {
        // GIVEN
        var attempts = new AtomicInteger(0);
        var repo = new RecordingRepository();
        var eventPersister = new RecordingEventPersister();
        var executor = buildExecutor(
                repo,
                eventPersister,
                actionRegistry()
                        .withAction(FailingItemAction.class, new FailingItemAction(clock, attempts))
                        .build());

        var config = ExecutionConfiguration.Builder.executionConfiguration()
                .noRetry()
                .build();

        // WHEN / THEN
        assertThatThrownBy(() -> executor.execute(
                        () -> "user", FailingItemAction.class, new FailingItemAction.Params(1), config))
                .isInstanceOf(io.ekbatan.core.repository.exception.StaleRecordException.class);

        // AND
        assertThat(attempts.get()).isEqualTo(1); // no retry
    }

    @Test
    void execute_rejects_null_action_class() {
        // GIVEN
        var repo = new RecordingRepository();
        var eventPersister = new RecordingEventPersister();
        var executor = buildExecutor(
                repo,
                eventPersister,
                actionRegistry()
                        .withAction(CreateItemAction.class, new CreateItemAction(clock))
                        .build());

        // WHEN / THEN
        assertThatThrownBy(() -> executor.execute(() -> "user", null, new CreateItemAction.Params("test")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actionClass cannot be null");
    }

    // --- Builder validation ---

    @Test
    void build_rejects_null_databaseRegistry() {
        // WHEN / THEN
        assertThatThrownBy(() -> actionExecutor()
                        .namespace("test.namespace")
                        .objectMapper(new ObjectMapper())
                        .repositoryRegistry(repositoryRegistry().build())
                        .actionRegistry(actionRegistry().build())
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("databaseRegistry is required");
    }

    @Test
    void execute_names_the_type_when_no_repository_is_registered() {
        // GIVEN an executor that knows about no repository at all
        var executor = actionExecutor()
                .namespace("test.namespace")
                .databaseRegistry(databaseRegistry)
                .objectMapper(new ObjectMapper())
                .repositoryRegistry(repositoryRegistry().build())
                .actionRegistry(actionRegistry()
                        .withAction(CreateItemAction.class, new CreateItemAction(clock))
                        .build())
                .eventPersister(new RecordingEventPersister())
                .clock(clock)
                .build();

        // WHEN & THEN the message names the type, rather than the null it was dereferenced on.
        // Grouping needs the ShardingStrategy before ChangePersister ever runs, so this used to
        // surface as "Cannot invoke Repository.shardingStrategy() because repository is null".
        assertThatThrownBy(() ->
                        executor.execute(() -> "user", CreateItemAction.class, new CreateItemAction.Params("wallet")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No repository found for entity type")
                .hasMessageContaining("Item")
                .hasMessageContaining("withModelRepository");
    }
}
