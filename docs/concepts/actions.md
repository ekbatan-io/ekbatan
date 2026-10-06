# Actions, ActionPlan, ActionExecutor

An **Action** is a unit of business work — `WalletDepositAction`, `OrderShipAction`, `SubscriptionRenewAction`. It does **not** write to the database directly. Instead, it stages new and updated domain objects onto its `ActionPlan`. Only after the action's `perform(...)` returns does the **`ActionExecutor`** open a single transaction, flush every staged change, write the outbox rows, and commit.

This split is what makes the outbox pattern implicit: domain rows and event rows are written together, atomically, by the framework — never by your code.

## The two-phase lifecycle

```
        executor.execute(WalletDepositAction.class, params)
                              │
                              ▼
   ┌─── Phase 1 — Action.perform()  (no DB transaction yet) ────┐
   │                                                            │
   │   1. Read from repositories (primary or readonly DB)       │
   │   2. Build new immutable Models / Entities                 │
   │   3. Attach Events to the Models                           │
   │   4. Stage them on the ActionPlan:                         │
   │         plan.add(newOrder)                                 │
   │         plan.update(updatedWallet)                         │
   │   5. Return a result value                                 │
   │                                                            │
   │   No database writes in this phase.                        │
   └────────────────────────────────────────────────────────────┘
                              │
                              ▼
   ┌─── Phase 2 — Executor.persistChanges()  (one atomic TX) ───┐
   │                                                            │
   │   1. Group plan changes by ShardIdentifier                 │
   │   2. TransactionManager.inTransaction(shard, () -> {       │
   │        Repository.addAll / updateAll  →  domain rows       │
   │        EventPersister.persistActionEvents  →  outbox rows  │
   │        commit  ─or─  rollback                              │
   │      });                                                   │
   │   3. On StaleRecordException → retry whole action          │
   │                                                            │
   │   All writes land together, or none at all.                │
   └────────────────────────────────────────────────────────────┘
                              │
                              ▼
                  result returned to the caller
```

Phase 1 is pure construction — reads are allowed, but no writes happen. Phase 2 is the only place the framework opens a transaction, and it always wraps every staged change plus the matching event rows together. Anything that throws inside Phase 2 rolls the whole transaction back.

## Writing an action

```java
@EkbatanAction
public class WalletDepositAction extends Action<WalletDepositAction.Params, Wallet> {

    public record Params(Id<Wallet> walletId, BigDecimal amount) {}

    private final WalletRepository walletRepository;

    public WalletDepositAction(Clock clock, WalletRepository walletRepository) {
        super(clock);
        this.walletRepository = walletRepository;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) {
        var wallet  = walletRepository.getById(params.walletId().getValue());
        var updated = wallet.deposit(params.amount());
        return plan().update(updated);
    }
}
```

The action receives the principal, takes a typed `Params` record, reads from a repository, builds a new immutable `Wallet` instance with an event attached (via `wallet.deposit(...)`), and stages the result with `plan().update(...)`. No database write happens in `perform()`.

Calling it:

```java
Wallet result = executor.execute(
        () -> "alice",
        WalletDepositAction.class,
        new WalletDepositAction.Params(walletId, new BigDecimal("25.50")));
```

The executor opens one transaction, writes the new wallet row with its incremented version, writes the `WalletMoneyDepositedEvent` row into `eventlog.events`, and commits. If anything throws, both rows are rolled back together.

## ActionPlan

`ActionPlan` is a single-writer, in-memory accumulator. Inside `perform()`, code reaches it via `plan()` (a protected helper on `Action`):

```java
plan().add(entity);          // staged for INSERT
plan().update(entity);       // staged for UPDATE; returns entity.nextVersion()
plan().addAll(collection);
plan().updateAll(collection);
```

A few rules:

- One id can be staged at most once per action. Re-staging the same id (whether `add` then `update` or twice as `add`) throws `IllegalStateException`.
- `update(entity)` returns `entity.nextVersion()` so subsequent reads inside the same `perform()` see the incremented version.
- `null` is refused with a `NullPointerException`: an entity, a collection, or a `null` inside a collection.
- The plan is **not thread-safe**. If `perform()` spawns parallel work to gather data, join the children before mutating the plan.

## Singletons + per-call state via ScopedValue

Every `Action` subclass is a **singleton**. Exactly one instance per class lives for the lifetime of the application; that instance is shared across every concurrent invocation. The instance's only state is the `Clock` and constructor-injected dependencies.

Per-execution mutable state — specifically the `ActionPlan` — is bound by the framework into a `java.lang.ScopedValue` for the duration of `perform()`, and accessed via the protected `plan()` method. Because of this:

- **Action subclasses must not have mutable instance state.** Anything beyond what's set in the constructor breaks under concurrency.
- **`plan()` must be called from the thread that invoked `perform()`.** Spawning a child thread inside `perform()` does not inherit the scoped binding; calling `plan()` from there throws `IllegalStateException`.

## Plan is single-writer; spawning threads is allowed but not encouraged

Spawning parallel threads inside `Action.perform()` is **allowed but not encouraged** — the action's *plan* is single-writer, not the action itself. Prefer fanning out *before* `executor.execute(...)` (see the end of this section). If you do spawn threads, two hard rules apply:

1. **Only the main thread (the one that invoked `perform()`) may call `plan().add(...)` or `plan().update(...)`.** `ActionPlan` is a plain `LinkedHashMap` internally — concurrent mutations from spawned threads are a data race. Even a single read from a spawned thread is unsupported because the `ScopedValue` binding doesn't propagate, so `plan()` will throw `IllegalStateException`.
2. **Don't share the action's transactional `Connection` across threads.** The `TransactionManager` binds it via `ScopedValue` to the main thread; spawned children don't see it (and a JDBC `Connection` isn't thread-safe anyway).

The supported pattern: fan out the work on virtual threads, **join** the results, and **only then** mutate the plan from the main thread:

```java
@Override
protected Order perform(Principal principal, Params params) throws Exception {
    // Spawn parallel reads — none of these touch plan()
    var customerThread = Thread.startVirtualThread(() -> customerService.fetch(params.customerId()));
    var pricingThread  = Thread.startVirtualThread(() -> pricingService.quote(params.lineItems()));
    customerThread.join();
    pricingThread.join();

    // Back on the main thread — now it's safe to mutate the plan
    var order = createOrder(/* aggregated results from the joined threads */).build();
    return plan().add(order);
}
```

For *reads*, prefer doing the parallel fan-out **before** calling `executor.execute(...)` — i.e. at the caller layer — so the parallel work uses connections from the regular pool rather than the action's transactional connection. Inside `perform()`, keep parallelism limited to work that doesn't need the action's transaction (external API calls, replica-only reads via fresh `DSLContext`s, computation).

## No nested actions inside `perform()`

Actions must **not** invoke other actions inside `perform()`. The framework intentionally does not support nesting or composition — an action is a self-contained unit of business work that produces a single atomic transaction. Nesting blurs transaction boundaries, creates hidden coupling, and makes the execution flow hard to reason about.

If two operations must happen together, they belong in a single action. If they are independent, execute them separately from the caller. If one must follow the other, orchestrate the sequence at the service / application layer above the framework.

The exception is post-commit chaining: a local-event-handler `EventHandler`, broker consumer, or worker can react after the source action has committed and start the next action or service step. This is the shape used by [sagas](sagas.md): one committed action emits an event, and a post-commit consumer starts the next step in its own transaction.

## Retries on optimistic-lock conflicts

Every persistable carries a `version`. Updates always include `WHERE version = ?`. If another transaction modified the same row in the meantime, the update affects zero rows and the framework throws `StaleRecordException`, unwinding the entire transaction.

The default `ExecutionConfiguration` retries `StaleRecordException` once with a 100ms delay. You can tune the policy — or remove it — per executor or per call:

```java
ExecutionConfiguration.Builder.executionConfiguration()
        .withRetry(StaleRecordException.class, new RetryConfig(3, Duration.ofMillis(50)))
        .build();
```

Retry matching is exact by exception class. Ekbatan also checks the cause chain, so a configured `StaleRecordException` retry still applies if that exception is wrapped. Superclass matching is not used: a config for `RuntimeException.class` does not retry an `IllegalStateException` unless the thrown exception is exactly `RuntimeException`.

The retry replays the entire action from Phase 1 with a fresh plan. Side effects in `perform()` outside the plan (logging, counters, external API calls) will replay too — keep `perform()` pure.

## Cross-shard actions

Actions that touch multiple shards are **rejected by default**: the executor throws `CrossShardException` if changes span shards. Opt in per call:

```java
var config = ExecutionConfiguration.Builder.executionConfiguration()
        .allowCrossShard(true)
        .build();

executor.execute(principal, MyAction.class, params, config);
```

When enabled, each involved shard gets its own transaction (commits independently — there is no 2PC), and the action's row in `eventlog.events` is duplicated to every shard with the same UUID so each shard contains the full action context.

Treat `allowCrossShard(true)` as an escape hatch, not as a normal transfer primitive. The default rejection is intentional: once multiple shards are involved, Ekbatan cannot guarantee one all-or-nothing commit. Prefer the per-call override so the risk is visible at the call site. Use an executor default only for a dedicated executor whose actions are all designed for per-shard commits and eventual consistency. If partial commits would require compensation, model the workflow as a [saga](sagas.md) instead of one cross-shard action.

See [Sharding](../database/sharding.md) for the full picture.

## See also

- [Models and Entities](models-and-entities.md) — what `plan().add(...)` and `plan().update(...)` consume
- [Repositories on JOOQ](../database/repositories.md) — how reads inside `perform()` work
- [The outbox: atomic state + events](outbox.md) — what Phase 2 writes
- [Sagas: chaining committed actions](sagas.md) — multi-step workflows and compensation
- [Sharding](../database/sharding.md) — cross-shard behavior
- [OpenTelemetry tracing](../runtime/observability.md) — the spans the executor produces
