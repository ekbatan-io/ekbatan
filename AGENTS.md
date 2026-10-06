# Agents Guide

This document provides detailed architectural context for AI agents working on the Ekbatan codebase.

## Project Overview

Ekbatan is a **Java persistence and action framework** built as an alternative to Spring Data. It is a **library**, not a standalone application. Its central idea is that in most business applications, you don't just want to save data — you want to save *what happened* alongside the data itself, atomically, so that the database always tells a complete and consistent story.

The framework is structured around the concept of an **Action** — a unit of business work. An Action doesn't write anything to the database directly. Instead, it declares its intent: what should be created, what should be updated. These declarations accumulate in an **ActionPlan** — a staging area that holds all planned changes without touching the database. Only after the action's logic completes successfully does the **ActionExecutor** step in, open a single database transaction, and flush everything at once: the domain objects to their tables, and the event records to the `eventlog` schema. If any part fails, nothing is written. This all-or-nothing guarantee means the `eventlog.events` table naturally becomes a reliable **outbox** — a stream of committed business facts that can be tailed by a CDC connector or polled and forwarded to Kafka, Pulsar, or any message broker, enabling event-driven architectures without dual-write problems.

The framework draws a deliberate line between two kinds of persistent objects. A **Model** is a domain aggregate whose changes matter — every mutation produces a `ModelEvent` that gets persisted in the eventlog alongside the action that caused it. You extend `Model` when downstream systems need to know what happened: a wallet was created, money was deposited, an order was shipped. An **Entity** is simpler — it gets persisted and version-tracked, but its changes are not recorded as events. You extend `Entity` for supporting data where the history of individual mutations is irrelevant: lookup tables, configuration records, auxiliary references that participate in an action but don't need their own event trail.

Both `Model` and `Entity` are **immutable**. A deposit on a wallet doesn't mutate the wallet — it returns a new wallet instance with the updated balance and a `WalletMoneyDepositedEvent` attached. This immutability is not incidental; it's what makes the two-phase execution model safe. During the `perform` phase, the action can read from repositories and construct new states freely, attaching events as it goes. Nothing is committed, so there's no risk of partial writes. The ActionPlan simply collects references to these immutable snapshots. When the executor flushes, it knows exactly what the final state should be.

**Optimistic locking** is baked into every persistable object through a mandatory `version` column. The framework never acquires row-level locks. Instead, every update carries a `WHERE version = ?` clause. If another transaction modified the same row in the meantime, the update affects zero rows and a `StaleRecordException` is thrown, unwinding the entire transaction. This makes the system safe under concurrency without pessimistic locking overhead, and keeps the write path simple and predictable.

The repository layer is built directly on **JOOQ** rather than JPA or Hibernate. There is no object-relational mapping magic, no lazy loading, no session cache. Each repository explicitly defines how to convert between a domain object and a JOOQ record (`fromRecord` / `toRecord`), making the persistence boundary visible and debuggable. The framework handles dialect differences (PostgreSQL, MySQL, MariaDB) internally — different SQL strategies for batch updates, different type representations for UUIDs and JSON — so that application code stays database-agnostic while the SQL remains correct and efficient.

## Module Structure

```
ekbatan/
├── ekbatan-core/                          # Core framework library
│   ├── src/main/java/io/ekbatan/core/
│   │   ├── domain/                        # Domain abstractions (Model, Entity, ModelEvent, Id, TypedValue)
│   │   ├── repository/                    # Repository pattern (AbstractRepository, ModelRepository, EntityRepository)
│   │   ├── action/                        # Action/Command pattern (Action, ActionExecutor, ActionPlan)
│   │   │   └── persister/                 # Change & event persistence
│   │   │       └── event/single_table_json/  # Single-table JSON event store implementation
│   │   ├── shard/                         # Sharding: ShardIdentifier, DatabaseRegistry, ShardingStrategy
│   │   ├── concurrent/                    # KeyedLockProvider family (Postgres, MariaDB, MySQL) + KeyedReentrantHolder
│   │   ├── persistence/                   # Transaction management, JOOQ converters
│   │   └── config/                        # DataSourceConfig
├── ekbatan-test-support/                  # Public test helpers (ActionSpec, VirtualClock, Testcontainers utilities)
├── ekbatan-annotation-processor/          # @AutoBuilder annotation processor (JavaPoet)
├── ekbatan-distributed-jobs/              # Distributed background jobs (db-scheduler facade)
│   └── src/main/java/io/ekbatan/distributedjobs/
│       ├── DistributedJob.java            # Abstract class — name, schedule, execute
│       └── JobRegistry.java               # Builder-driven facade over db-scheduler Scheduler
├── ekbatan-keyed-lock-redis/              # Distributed KeyedLockProvider backed by Redisson
│   └── src/main/java/io/ekbatan/keyedlock/redis/
│       └── RedisKeyedLockProvider.java    # Redisson RLock wrapper preserving Ekbatan reentry contract
├── ekbatan-integration-tests/              # End-to-end integration tests (package: io.ekbatan.test)
│   ├── core-repo/                         # Testcontainer-based repository tests across PG/MySQL/MariaDB
│   │   ├── shared/                        # Shared BaseRepositoryTest, dummy models (test fixtures)
│   │   ├── pg/                            # PostgreSQL runners (repository, denormalized-events)
│   │   ├── mysql/                         # MySQL runners
│   │   └── mariadb/                       # MariaDB runners
│   ├── postgres-simple/                   # Simple end-to-end wallet flow (PostgreSQL)
│   ├── postgres-sharded/                  # Sharded wallet with cross-shard tests (PostgreSQL)
│   ├── keyed-lock-provider/               # KeyedLockProvider integration tests
│   │   ├── pg/                            # PostgresKeyedLockProvider
│   │   ├── mariadb/                       # MariaDBKeyedLockProvider
│   │   ├── mysql/                         # MySQLKeyedLockProvider
│   │   └── redis/                         # RedisKeyedLockProvider (Redisson + Redis testcontainer)
│   ├── distributed-jobs-pg/               # JobRegistry integration tests (PostgreSQL)
│   └── event-pipeline/                    # Debezium → Kafka JSON/Avro/Protobuf event streaming
├── build-logic/                           # Gradle build conventions (included build)
└── config/checkstyle/                     # Checkstyle rules
```

## Architecture Layers

### Domain Layer (`ekbatan-core/...core/domain/`)

The foundation. Two kinds of domain objects:

- **`Model`** — Event-sourced aggregate with `id`, `state`, `version`, `events`, `createdDate`, `updatedDate`. Immutable. Business methods return new instances via builders. Events are accumulated on the model instance.
- **`Entity`** — Simpler stateful object with `id`, `state`, `version`. No events. Immutable.
- **`ModelEvent<M>`** — Base class for domain events. Carries `modelId` and `modelName`. Serializable.
- **`Id<T extends Identifiable>`** — Type-safe UUID wrapper. Prevents mixing IDs of different types at compile time.
- **`TypedValue<T>`** — Base value object wrapper for type safety.
- **`Persistable`** — Interface adding `version` and `nextVersion()` for optimistic locking.
- **`GenericState`** — Default state enum (ACTIVE, DELETED) for entities without custom states.

Key pattern: Domain objects are **immutable**. Mutations create new instances. Models accumulate events during mutations (e.g., `wallet.deposit(amount)` returns a new Wallet with a `WalletMoneyDepositedEvent` attached).

### Repository Layer (`ekbatan-core/...core/repository/`)

JOOQ-based persistence:

- **`AbstractRepository`** — Core implementation with full CRUD, optimistic locking (version field), soft deletion (filters DELETED state), dialect-aware SQL for MySQL vs MariaDB vs PostgreSQL, and shard-aware routing (scatter-gather reads, ID-based writes, batch single-shard validation).
- **`ModelRepository`** — Adds `created_date`/`updated_date` field validation.
- **`EntityRepository`** — Simpler variant for entities.
- **`RepositoryRegistry`** — Type-safe map of `Class -> Repository` with builder pattern.

Subclasses must implement `fromRecord(RECORD)` and `toRecord(PERSISTABLE)` to map between JOOQ records and domain objects.

Exceptions in `repository/exception/` — there are exactly two; there is no `PersistenceException`
base type and no `ModelAware` / `ConstraintViolation` interfaces:
- `StaleRecordException` — Optimistic locking failure (zero rows matched `WHERE version = ?`)
- `EntityNotFoundException` — Lookup miss, raised by `getById` (`findById` returns `Optional` instead)

Elsewhere in core: `io.ekbatan.core.shard.CrossShardException` (action spans shards with
`allowCrossShard=false`) and `io.ekbatan.core.concurrent.LockAcquisitionException`
(`KeyedLockProvider` backend failure — never contention).

### Action Layer (`ekbatan-core/...core/action/`)

Command pattern for business operations:

- **`Action<PARAM, RESULT>`** — Base class. Has an `ActionPlan`. Subclasses implement `perform(principal, params)`.
- **`ActionPlan`** — Accumulates additions and updates to domain objects during action execution.
- **`ActionExecutor`** — Orchestrates: get action from registry -> call perform -> wrap in transaction -> persist changes.
- **`ActionRegistry`** — Maps action classes to suppliers (factories).
- **`ChangePersister`** — Iterates plan changes, extracts events from models, persists via repositories + event persister.
- **`EventPersister`** — Interface for event storage strategy.
- **`SingleTableJsonEventPersister`** — Default implementation using a single `eventlog.events` table with JSONB payload.

### Persistence Layer (`ekbatan-core/...core/persistence/`)

- **`TransactionManager`** — Uses Java 21+ `ScopedValue<Transaction>` for thread-safe transaction context. Provides `inTransaction()` and `inTransactionChecked()`.
- **`ConnectionProvider`** — HikariCP wrapper. Supports primary (master) and secondary (read-replica) connections.
- **`Transaction`** — Wraps a JDBC Connection with begin/commit/rollback lifecycle.
- **JOOQ Converters** — `InstantConverter`, `JSONObjectNodeConverter`, `JSONBObjectNodeConverter`, `UuidBinaryConverter`, `UuidStringConverter`.

### Concurrency Layer (`ekbatan-core/...core/concurrent/`)

Keyed mutual-exclusion primitives for cross-thread (and cross-JVM) coordination:

- **`KeyedLockProvider`** — interface. `acquire(key, maxHold)` returns a `Lease`; `tryAcquire(key, maxWait, maxHold)` is the bounded-wait variant. Closing the lease releases the lock.
- **`PostgresKeyedLockProvider` / `MariaDBKeyedLockProvider` / `MySQLKeyedLockProvider`** — cross-JVM via session-scoped advisory locks (`pg_advisory_lock` / `GET_LOCK`). Each acquire borrows its own JDBC connection; per-DB quirks (timeout precision, Galera caveat, wait-forever sentinel) are documented in each class's Javadoc.
- **`KeyedReentrantHolder`** — shared internal helper. Owns the per-`(thread, key)` counter, watchdog thread, and release-arbitration CAS so each provider only has to define backend acquire/release.

**Reentrancy contract (uniform across all providers).** Same thread + same key acquires re-enter without blocking; the underlying backend lock is released only when the *outermost* lease is closed (or `maxHold` watchdog fires). The first acquire's `maxHold` governs the watchdog — re-entries' `maxHold` arguments are ignored. This is stricter than Redisson/Hazelcast's "last-call-wins" convention and prevents an inner re-entry from shortening the outer holder's commitment. Reentrancy is per-thread, not per-call-stack: a child thread spawned inside a held region is a different identity and will block.

**Where to acquire — caller-side, not inside `Action.perform()`.** For "concurrent writes on the same key produce a consistent result" use cases (the most common), acquire the lock in the **caller** that invokes the action (controller, job, event handler) so the lease wraps the entire `executor.execute(...)` call. Acquiring inside `Action.perform()` releases the lease before `ActionExecutor.persistChanges()` commits — leaving a race window where a concurrent acquirer reads pre-commit state and fails the framework's optimistic-locking version check. Inside-`perform()` is only appropriate for **side-effect coordination** (e.g. serializing an outbound webhook call where the DB write is incidental), not for commit serialization. See [`docs/database/keyed-locks.md#where-to-acquire-the-lock`](./docs/database/keyed-locks.md#where-to-acquire-the-lock) for the timeline diagram and full rationale. The caller-side pattern is demonstrated in every base wallet REST example — each project (per `(stack × build tool × dialect)`) ships a dialect-appropriate `KeyedLockProvider` bean + `lockConfig` slot and wraps its deposit endpoint with `try (var lease = lockProvider.acquire(...))`.

### Distributed Lock Layer (`ekbatan-keyed-lock-redis/`)

- **`RedisKeyedLockProvider`** — `KeyedLockProvider` backed by Redisson's `RLock`. Reuses `KeyedReentrantHolder` to enforce Ekbatan's first-call-wins reentrancy on top of Redisson's last-call-wins default. Always passes `maxHold` as Redisson's `leaseTime` (disables Redisson's watchdog), uses the local virtual-thread watchdog instead. Builder takes a `RedissonClient` plus an optional `namespace` prefix (default `"ekbatan-lock"`). Single-master Redis only — not Redlock-based.

### Distributed Jobs Layer (`ekbatan-distributed-jobs/`)

Recurring background jobs that run on at-most-one instance across a cluster — thin opinionated facade over [db-scheduler](https://github.com/kagkarlsson/db-scheduler):

- **`DistributedJob`** — abstract class. Implementers provide `name()` (cluster-wide unique), `schedule()` (any db-scheduler `Schedule` — `FixedDelay`, `FixedRate`, `Cron`, `Daily`, etc.), and `execute(ExecutionContext)`.
- **`JobRegistry`** — builder over db-scheduler's `Scheduler`. Auto-sizes `threads(jobs.size())` for the polling batch, swaps in `Executors.newVirtualThreadPerTaskExecutor()` for workers, registers a JVM shutdown hook by default. Curated knobs: `pollInterval`, `heartbeatInterval`, `shutdownMaxWait`, `registerShutdownHook`. Escape hatch: `customizeScheduler(Consumer<SchedulerBuilder>)` runs last in `build()` for any advanced db-scheduler setting not exposed (e.g. `missedHeartbeatsLimit`, `deleteUnresolvedAfter`, custom polling strategy).

Coordination semantics are inherited from db-scheduler: every instance polls the shared `scheduled_tasks` table, only one wins the atomic claim per scheduled slot, dead executions are reclaimed via heartbeat staleness. The module needs a separate `scheduled_tasks` table provisioned in the application's database (db-scheduler's verbatim PG schema is in `ekbatan-integration-tests/distributed-jobs-pg/src/test/resources/db/migration/V0001__create_scheduled_tasks.sql`).

### Annotation Processor (`ekbatan-annotation-processor/`)

- **`@AutoBuilder`** — Marks a Model or Entity class for compile-time builder generation.
- **`AutoBuilderProcessor`** — Uses JavaPoet to generate builder classes that extend `Model.Builder` or `Entity.Builder`. Generated builders have fluent setters, getters, and a `build()` method.

## Key Design Patterns & Conventions

### Immutability
All domain objects are immutable. State changes produce new instances. Example:
```java
Wallet updated = wallet.deposit(amount); // returns NEW wallet with event attached
plan.update(updated);                    // registers for persistence
```

### Single-Writer Action Execution
Spawning threads inside `Action.perform()` is **allowed but not encouraged**. The plan is
single-writer, not the action. Two hard rules make it safe:

1. **Only the thread that invoked `perform()` may call `plan()`.** `ActionPlan` is a plain
   `LinkedHashMap` internally, so concurrent mutation is a data race. It is also bound via
   `ScopedValue`, which does not propagate to spawned threads — calling `plan()` from a child
   thread throws `IllegalStateException` rather than corrupting anything.
2. **Never share the action's transactional `Connection` across threads.** `TransactionManager`
   binds it via `ScopedValue` to the invoking thread, and a JDBC `Connection` is not thread-safe.

The supported shape is fan out, **join**, then mutate the plan from the main thread:

```java
var customerThread = Thread.startVirtualThread(() -> customerService.fetch(params.customerId()));
var pricingThread  = Thread.startVirtualThread(() -> pricingService.quote(params.lineItems()));
customerThread.join();
pricingThread.join();

// back on the invoking thread — only now is it safe to touch the plan
return plan().add(buildOrder(/* aggregated results */));
```

**Preferred alternative:** do the parallel fan-out *before* calling `executor.execute(...)`, build
the action's `Params` from the aggregated data, and keep `perform()` sequential. That uses ordinary
pool connections rather than the action's transactional one, and keeps actions focused on declaring
business intent. Inside `perform()`, limit parallelism to work that does not need the action's
transaction — external API calls, replica-only reads on a fresh `DSLContext`, pure computation.

### No Composable / Nested Actions
Actions must **not** invoke other actions within their `perform()` method. The framework intentionally does not support nested or composable action execution. An action is a self-contained unit of business work that produces a single atomic transaction — nesting actions blurs transaction boundaries, creates hidden coupling between business operations, and makes the execution flow harder to reason about. If two operations need to happen together, they belong in a single action. If they are independent, execute them separately from the caller. If one must follow the other, orchestrate that sequence at the service/application layer above the framework.

### Optimistic Locking
Every persistable has a `version` field. On update, the repository checks that the version matches. If not, `StaleRecordException` is thrown. `nextVersion()` increments the version.

### Soft Deletion
Records are never physically deleted. The `state` field is set to `DELETED`. Queries automatically filter out deleted records.

### Idempotency
Ekbatan does **not** provide a framework-level idempotency key abstraction (no dedicated idempotency column on `eventlog.events`, no `idempotencyKey` field on action params). The framework's atomic transactions already guarantee that if a unique constraint fails, the entire transaction rolls back — no partial writes. Action designers who need idempotency choose their own domain-appropriate field name (e.g., `referenceId`, `transactionId`, `depositId`) and enforce uniqueness via a constraint on their domain table. This keeps the framework simple and avoids prescribing a single field/column name that may not fit every domain context.

### Event Sourcing (Single-Table, JSON)
Models accumulate `ModelEvent` instances. When persisted via `ChangePersister`, events are extracted and stored in a single denormalized table:
- `eventlog.events` — One row per emitted event, carrying action metadata (action_id, action_name, action_params), model metadata (model_id, model_type, event_type), and a JSONB `payload`. One sentinel row per action (no event_type) captures actions that emit zero events.

### Event IDs and Sharding
Only domain entity IDs (e.g., `wallets.id`) use `ShardedUUID` for shard routing. `eventlog.events.id` is a regular UUID, co-located with the action/model it describes — for cross-shard actions (when `allowCrossShard=true`), rows are written to each involved shard so that every shard has a self-contained picture. No cross-shard foreign keys.

### Sharding

Ekbatan supports horizontal sharding with a two-level hierarchy: **group** (business/regulatory constraint, e.g., "Mexico data stays in Mexico") and **member** (performance scaling within a group).

#### Core Concepts

- **`ShardIdentifier(int group, int member)`** — Numeric shard address. `DEFAULT = (0, 0)`. Fixed 8-bit group + 6-bit member, supporting up to 256 groups x 64 members.
- **`ShardedUUID`** — UUID v7 with shard bits (group + member) encoded in `rand_b`. Self-describing: the shard can be decoded from the UUID without any lookup.
- **`ShardedId<T>`** — Type-safe ID wrapper around `ShardedUUID`, independent from `Id<T>`. Implements `ShardAwareId`.
- **`DatabaseRegistry`** — Unified entry point for all database access. Maps `ShardIdentifier → TransactionManager`. Provides `primary`/`secondary` DSLContext maps and `effectiveShard()` for logical-to-physical shard mapping.
- **`ShardingStrategy<DB_ID>`** — Pluggable interface on `Repository`. `NoShardingStrategy` (default, zero impact) and `EmbeddedBitsShardingStrategy` (decodes shard from UUID v7 bits).
- **`CrossShardException`** — Thrown when a batch or action spans multiple shards without `allowCrossShard=true`.

#### Shard Routing in AbstractRepository

AbstractRepository resolves shards via `effectiveShard()` overloads, which combine strategy resolution with `DatabaseRegistry.effectiveShard()` for logical-to-physical mapping. This means unregistered shards (e.g., Australia using group 2 when only default and Mexico are deployed) automatically fall back to the default shard.

**DB access methods** — four tiers, each with overloads for `()`, `(DB_ID)`, `(PERSISTABLE)`, `(ShardIdentifier)`:
- `db()` / `readonlyDb()` — primary/secondary DSLContext
- `txDb()` — current transaction context (returns `Optional`)
- `txDbElseDb()` — transaction context or fallback to non-transactional
- `dbs()` / `readonlyDbs()` — all shard contexts (strategy-aware: returns only default for `NoShardingStrategy`)

**Write routing** — single-entity methods use `effectiveShard(domainObject)`. Batch methods use `effectiveShard(Collection)` which validates all entities resolve to the same effective shard or throws `CrossShardException`.

**Read routing** — ID-based methods (`findById`, `existsById`) route to the specific shard via `effectiveShard(id)`. Condition-based methods (`findAll`, `findAllWhere`, `count`, `countWhere`, `findOneWhere`, `existsWhere`) scatter-gather across `dbs()`. `findAllByIds` groups IDs by shard and queries each shard with its subset.

**Dialect resolution** — each CRUD method resolves dialect from the target shard via `dialect(shard)` rather than assuming a global dialect, so mixed-dialect setups are theoretically supported.

**No offset/limit methods** — `findAll(offset, limit)` and `findAllWhere(condition, offset, limit)` are intentionally not provided. Offset/limit pagination doesn't work correctly with scatter-gather. Sharded systems should use cursor-based (keyset/temporal) pagination, implemented in concrete repository subclasses.

**ID-based strategy guard** — `rejectNonIdBasedStrategy()` is centralized inside `effectiveShard(DB_ID)`. It allows `NoShardingStrategy` through (no constraint needed) and rejects custom strategies that don't support `usesShardAwareId()`.

#### Cross-Shard Enforcement in ActionExecutor

`ActionExecutor.persistChanges()` groups plan changes by shard using each repository's `shardingStrategy()`. If changes span multiple shards and `ExecutionConfiguration.allowCrossShard` is `false` (the default), `CrossShardException` is thrown. When allowed, each shard gets its own transaction, and action events are duplicated to all involved shards with the same UUID.

### Timezone Convention — Always UTC
All timestamps in Ekbatan should be stored and processed in **UTC**. This applies to:

- **SQL column type — use `TIMESTAMP`, never `TIMESTAMPTZ`.** Every timestamp column in every Ekbatan migration is `TIMESTAMP` (without time zone). This is enforced project-wide and is not negotiable per-table. `InstantConverter` turns each `Instant` into UTC wall time in Java and back, so plain `TIMESTAMP` round-trips with Java `Instant` whatever zone the database server or the JVM runs in. Do not introduce `TIMESTAMPTZ` even if it seems like a "best practice" elsewhere — mixing the two within Ekbatan creates subtle JOOQ codegen and converter inconsistencies.
- **Database server timezone** — set the database machine or container to `TZ=UTC`. Ekbatan uses `java.time.Instant` (always UTC) for `created_date` and `updated_date` fields, and stores them right on a server in any zone; what a zone outside UTC changes is the database's own clock (`NOW()`, `CURRENT_TIMESTAMP`, `LOCALTIMESTAMP`), which then reads local time, hours away from the values Ekbatan wrote. On MySQL/MariaDB that clock follows the server's zone; on PostgreSQL it follows the JVM's.
- **Table column values** — all timestamps persisted by the framework represent UTC instants. Do not store local times. If a business requirement needs a local time representation, store it as a separate field alongside the UTC instant.
- **TestContainers** — always configure with `.withEnv("TZ", "UTC")` to avoid timezone-dependent test failures (e.g., daylight saving time shifts).
- **JDBC connections** — when connecting to PostgreSQL, MySQL, or MariaDB, ensure the session timezone is UTC. On PostgreSQL the driver sets each session to the JVM's zone when it connects, over the server's and over `options=-c TimeZone=...`, so run the JVM in UTC (`-Duser.timezone=UTC`). On MySQL/MariaDB, if the server can't run in UTC, set `sessionVariables: "time_zone='+00:00'"` under `data-source-properties`. Never `serverTimezone` or `connectionTimeZone`: with the JVM outside UTC, MySQL's driver then stores Ekbatan's values shifted by the JVM's offset, and MariaDB's driver ignores `serverTimezone`. `MysqlTimeZoneIntegrationTest`, `MariadbTimeZoneIntegrationTest` and `PgTimeZoneIntegrationTest` in `ekbatan-integration-tests/core-repo` pin all of this.

> **For agents writing new migrations:** before writing schema, open one existing Flyway migration in `ekbatan-integration-tests/.../db/migration/` and copy its column-type conventions verbatim. The existing migrations are the source of truth — not memory of "what's typical" elsewhere, and not example fragments scattered through other docs.

### DDL Defaults — Recommendation: set values in code, not in SQL

**Recommendation, not a hard rule.** Ekbatan migrations generally avoid `DEFAULT` clauses on column declarations: required columns are `NOT NULL` and the framework code (persisters, repositories, builders) supplies the value on every INSERT explicitly.

Why this is the preferred default:
- One source of truth for "what gets written when nothing is supplied" — the Java builder/persister, not a SQL clause that's invisible from the call site.
- Cross-dialect parity: PostgreSQL / MySQL / MariaDB all express defaults slightly differently (boolean literals, sequences, function calls); keeping defaults out of DDL reduces the dialect-conditional surface.
- Hand-written test SQL and one-off admin queries surface missing-column bugs immediately rather than silently inheriting a default that drifts from what the framework writes.

Example: `eventlog.events.delivered` is declared `BOOLEAN NOT NULL` (no `DEFAULT FALSE`). `SingleTableJsonEventPersister` always writes it as `false`; the local-event-handler fan-out flips it to `true`. Hand-written SQL must include the column.

It's fine to use `DEFAULT` when it actually buys clarity — e.g. counters that always start at zero, like `attempts INT NOT NULL DEFAULT 0` in `event_notifications`, where the framework is the only writer and the meaning is unambiguous. When in doubt, lean toward writing the value in code.

### Repository connection helpers (`db` / `readonlyDb` / `txDb` / `txDbElseDb`)

`AbstractRepository` exposes four families of `protected` helpers for pulling a JOOQ `DSLContext` for the right shard. Custom queries in repository subclasses (or any standalone repository like `EventEntityRepository` and `EventNotificationRepository` in `local-event-handler`) should use these instead of poking at `databaseRegistry.primary` / `secondary` directly.

| Family | Returns | When to use |
|---|---|---|
| `db()` / `db(id)` / `db(persistable)` / `db(shard)` / `dbs()` | Primary `DSLContext` (writes go here, strongly-consistent reads go here). | Direct primary access — when you know there is **no** outer transaction and you want primary, e.g. ad-hoc admin scripts, fallback path of `txDbElseDb`. |
| `readonlyDb()` / `readonlyDb(id)` / `readonlyDb(shard)` / `readonlyDbs()` | Secondary/replica `DSLContext`. | Custom read-only queries that explicitly accept replica lag — e.g. dispatch's `findDue`, fan-out's `findUndelivered`, list/search endpoints. Reading from the replica offloads primary and lets the framework remain at-least-once even when replication lags. |
| `txDb()` / `txDb(id)` / `txDb(persistable)` / `txDb(shard)` | `Optional<DSLContext>` — the active transaction's context if one is open on this shard, else empty. | When you must explicitly assert "there must be an open transaction" and fail loudly otherwise. Rarely needed directly; usually paired via `txDbElseDb`. |
| `txDbElseDb()` / `txDbElseDb(id)` / `txDbElseDb(persistable)` / `txDbElseDb(shard)` | Active transaction's context if open, else `db()` (primary). | **All writes** in custom queries. Inside an action's transaction, this hooks into the action's atomic write path; outside, it falls back to primary so ad-hoc writes still work. |

**Rules of thumb:**
- Inherited CRUD reads (`findById`, `getById`, `findAllWhere`, `count`, etc.) use primary connections by design. They favor committed, primary-consistent business reads over implicit replica offload.
- Custom primary-consistent reads → `db(...)` / `dbs(...)`.
- Custom eventually-consistent reads where replica lag is acceptable → `readonlyDb(...)` / `readonlyDbs(...)`.
- Custom reads that must see uncommitted writes in the current manual transaction → `txDbElseDb(...)` or the transaction's own `DSLContext`.
- Writes → `txDbElseDb(...)`. This automatically reuses the action's transactional connection when called from inside `ActionExecutor.persistChanges()` or `tm.inTransaction(...)`, so the write is atomic with the rest of the action. Outside any transaction, it falls back to primary.
- Iterating all shards (scatter-gather) → use `dbs()` (primary) or `readonlyDbs()` (replica reads). Both are sharding-strategy-aware: with `NoShardingStrategy` they collapse to a single-element collection.
- `id`-based and `persistable`-based overloads internally call `effectiveShard(id)` / `effectiveShard(persistable)` — they're shorthand for "give me the connection for the shard this entity belongs to." Available only on `AbstractRepository` subclasses (which know their `ShardingStrategy`); standalone repositories like `EventEntityRepository` use the `(ShardIdentifier shard)` overloads explicitly.

**Idempotency-on-INSERT note:** when the same logical row could be inserted twice (e.g. a fanout job re-reading rows from a lagging replica), prefer making the INSERT itself idempotent via JOOQ's `.onConflictDoNothing()` rather than catching the constraint exception in the application loop. `onConflictDoNothing()` translates to `ON CONFLICT DO NOTHING` on Postgres and `INSERT IGNORE` on MySQL/MariaDB, so it works cross-dialect without dispatching on `dialect.family()`.

### Multi-Database Support
The framework supports PostgreSQL, MySQL, and MariaDB. Dialect differences are handled in:
- `AbstractRepository` — Different SQL strategies for upsert/update
- JOOQ converters — `JSONB` (PostgreSQL) vs `JSON` (MySQL/MariaDB)
- UUID handling — Native UUID (PostgreSQL/MariaDB) vs `CHAR(36)` + converter (MySQL)
- Test infrastructure — Separate test subprojects per database

#### Column-type cheatsheet

The reference for what DDL type, what `SQLDataType`, and what JOOQ converter to use for each Java type, per dialect. **Always consult this table before writing migrations or repository field definitions.**

| Java type | PostgreSQL DDL | PG `SQLDataType` | MariaDB DDL | MariaDB `SQLDataType` | MySQL DDL | MySQL `SQLDataType` | Converter |
|---|---|---|---|---|---|---|---|
| `UUID` | `UUID` | `UUID.class` | `UUID` | `UUID.class` | `CHAR(36) CHARACTER SET ascii` | `SQLDataType.CHAR(36).asConvertedDataType(new UuidStringConverter())` | `UuidStringConverter` (MySQL only) |
| `ObjectNode` | `JSONB` | `SQLDataType.JSONB.asConvertedDataType(new JSONBObjectNodeConverter())` | `JSON` | `SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter())` | `JSON` | `SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter())` | `JSONBObjectNodeConverter` (PG) / `JSONObjectNodeConverter` (MariaDB+MySQL) |
| `Instant` | `TIMESTAMP` | `SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter())` | `DATETIME(6)` | same | `DATETIME(6)` | same | `InstantConverter` (all dialects) |
| `String` | `VARCHAR(N)` / `TEXT` | `String.class` | same | same | same | same | none |
| `Boolean` | `BOOLEAN` | `Boolean.class` | `BOOLEAN` (alias for `TINYINT(1)`) | `Boolean.class` | `BOOLEAN` (alias for `TINYINT(1)`) | `Boolean.class` | none |
| `Long` | `BIGINT` | `Long.class` | `BIGINT` | `Long.class` | `BIGINT` | `Long.class` | none |
| `Integer` | `INT` | `Integer.class` | `INT` | `Integer.class` | `INT` | `Integer.class` | none |
| `BigDecimal` | `DECIMAL(p, s)` | `BigDecimal.class` | `DECIMAL(p, s)` | `BigDecimal.class` | `DECIMAL(p, s)` | `BigDecimal.class` | none |

**Why MySQL needs `CHARACTER SET ascii` on UUID columns:** UUID strings are pure 7-bit ASCII (8-4-4-4-12 hex with hyphens). Pinning the charset to ASCII keeps each char at one byte (vs. 3–4 under `utf8mb4`), tightens index locality, and avoids accidental collation rules being applied. PostgreSQL's native `UUID` and MariaDB's `UUID` (≥ 10.7) bypass charset entirely.

**Why MariaDB JSON columns need a converter despite JSON being a "real" type:** MariaDB stores JSON as `LONGTEXT` with a CHECK constraint internally, and the JDBC driver reports the type accordingly.

Note the two contexts differ, and only the second is affected:

- **Hand-declared fields** (as in `EventEntityRepository`) write `SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter())`. This *tells* jOOQ the type instead of asking the database, so MariaDB's `LONGTEXT` reporting never enters the picture. The table above describes this case.
- **Code generation** takes the type the driver reports. On MariaDB that is `LONGTEXT` → `CLOB` → `DataType<String>`, so matching on `withIncludeTypes("(?i:JSON)")` never fires, and widening it to `(?i:JSON|LONGTEXT)` matches but then fails to compile: the shared converters are `Converter<JSON, ..>` and cannot attach to a `DataType<String>` field.

The forced-type entry must therefore add `withName("JSON")`, which rewrites the generated `DataType` back to `JSON` so the shared converter fits, and match by column expression rather than by type:

```kotlin
ForcedType()
    .withName("JSON")                                    // CLOB -> JSON, so the converter can attach
    .withUserType("tools.jackson.databind.node.ObjectNode")
    .withConverter("io.ekbatan.core.persistence.jooq.converter.JSONObjectNodeConverter")
    .withIncludeExpression(".*\\.my_json_column")        // by name: the reported type is not "JSON"
```

Match each JSON column by name rather than forcing `(?i:LONGTEXT)` wholesale, which would also retype legitimate text columns. This applies to MariaDB only — MySQL reports `JSON` properly and uses `withIncludeTypes("(?i:JSON)")` unchanged. Working reference: `ekbatan-integration-tests/core-repo/mariadb/repository/build.gradle.kts`.

**Why MySQL UUID converter is `CHAR(36)`-shaped, not `BINARY(16)`:** Ekbatan picks the human-readable form to keep query logs, raw JDBC dumps, and cross-dialect IDs grep-able. The `BINARY(16)` form would be more compact but isn't currently used anywhere in the project.

#### Schema vs database

In PostgreSQL, `eventlog` is a *schema* inside the connected database — created via `CREATE SCHEMA IF NOT EXISTS eventlog;` in a Flyway migration. No init script needed.

In MariaDB and MySQL, "schema" and "database" are synonyms; there is no second-level grouping inside a database. The `eventlog` namespace becomes a separate database (e.g. `eventlog.events` is read as `<database>.<table>`). Two consequences:

1. The container's named database (e.g. `testdb`) is created by `MARIADB_DATABASE` / `MYSQL_DATABASE` env var. The `eventlog` database must be created separately. Ekbatan does this via a Flyway migration:
   ```sql
   -- V0000__create_eventlog_schema.sql
   CREATE DATABASE IF NOT EXISTS eventlog;
   ```
2. The named test user (e.g. `test`) only has rights on the named database by default. Use a docker-entrypoint init script mounted at `/docker-entrypoint-initdb.d/` to grant cross-database privileges, e.g.:
   ```sql
   -- mariadb_init.sql / mysql_init.sql
   GRANT ALL PRIVILEGES ON *.* TO 'test'@'%';
   FLUSH PRIVILEGES;
   ```
   Mount via `MariaDBContainer.withCopyFileToContainer(MountableFile.forClasspathResource("mariadb_init.sql"), "/docker-entrypoint-initdb.d/mariadb_init.sql")`. The script runs as root before the container becomes ready, so subsequent migrations run as `test` with cross-database access.

#### Partial indexes

PostgreSQL only. The framework's PG migrations use them to keep "due / pending" sweep queries cheap:
```sql
CREATE INDEX events_pending_fanout
    ON eventlog.events (event_date)
    WHERE delivered = FALSE;
CREATE INDEX event_notifications_due
    ON eventlog.event_notifications (next_retry_at)
    WHERE state IN ('PENDING', 'FAILED');
```
For the MariaDB/MySQL equivalents, **drop the `WHERE` clause** and accept a full index. The selectivity loss is small in practice (the polling query already filters on `next_retry_at <= now()` plus state, and the index covers the leading column).

#### Repository field-definition pattern (cross-dialect repos)

When a repository targets multiple dialects, define field constants in three parallel sets — `PG_*`, `MARIADB_*`, `MYSQL_*` — but only for fields whose `SQLDataType` actually differs (UUID and JSON columns). Keep dialect-neutral fields (`String`, `Instant`, `Boolean`, `Integer`, `Long`) as a single shared constant.

In the constructor, switch on `dialect.family()`:
```java
if (defaultTm.dialect.family() == SQLDialect.MYSQL) {
    this.idField = MYSQL_ID;
    this.payloadField = MYSQL_PAYLOAD;
    // …
} else if (defaultTm.dialect.family() == SQLDialect.MARIADB) {
    this.idField = MARIADB_ID;
    this.payloadField = MARIADB_PAYLOAD;
    // …
} else {
    this.idField = PG_ID;
    this.payloadField = PG_PAYLOAD;
    // …
}
```
Reference implementations: `ekbatan-core/.../single_table_json/EventEntityRepository` and `ekbatan-events/local-event-handler/.../EventEntityRepository`.

#### jOOQ codegen `build.gradle.kts` per dialect

The framework uses the [`dev.monosoul.jooq-docker`](https://github.com/monosoul/jooq-gradle-plugin) plugin to spin up a throwaway DB container at build time, run the Flyway migrations against it, then introspect the live schema and generate JOOQ classes. Each dialect module needs three blocks: the plugin declaration, the container config, and the codegen task. **The container config differs per dialect; the rest is largely uniform.** Reference patterns:

**PostgreSQL** — no `jooq { withContainer { … } }` block needed; the plugin's default container is Postgres.
```kotlin
plugins {
    id("java")
    id("dev.monosoul.jooq-docker") version "8.0.9"
}

tasks {
    generateJooqClasses {
        schemas.set(listOf("public", "eventlog"))
        basePackageName.set("io.ekbatan.test.<your_module>.generated.jooq")
        migrationLocations.setFromFilesystem("src/test/resources/db/migration")
        outputDirectory.set(project.layout.buildDirectory.dir("generated-jooq"))
        flywayProperties.put("flyway.placeholderReplacement", "false")
        includeFlywayTable.set(false)
        outputSchemaToDefault.add("public")
        schemaToPackageMapping.put("public", "public_schema")
        schemaToPackageMapping.put("eventlog", "eventlog_schema")
        usingJavaConfig {
            database.withForcedTypes(
                ForcedType()
                    .withUserType("java.time.Instant")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.InstantConverter")
                    .withIncludeTypes("TIMESTAMP")
                    .withIncludeExpression(".*"),
                ForcedType()
                    .withUserType("tools.jackson.databind.node.ObjectNode")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.JSONBObjectNodeConverter")
                    .withIncludeTypes("JSONB")
                    .withIncludeExpression(".*"),
            )
        }
    }
}

dependencies {
    jooqCodegen("org.postgresql:postgresql:${project.property("postgresqlVersion")}")
    // …
}
```

**MariaDB** — explicit `jooq { withContainer { … } }`; the JSON entry cannot use a type regex at all, because MariaDB reports `JSON` columns as `LONGTEXT`. Match the column by name and add `withName("JSON")` — see [Column-type cheatsheet](#column-type-cheatsheet) above.
```kotlin
jooq {
    withContainer {
        image {
            name = "mariadb:11.8"
            envVars = mapOf(
                "MARIADB_ROOT_PASSWORD" to "root",
                "MARIADB_DATABASE" to "testdb",
            )
        }
        db {
            username = "root"; password = "root"; name = "testdb"; port = 3306
            jdbc { schema = "jdbc:mariadb"; driverClassName = "org.mariadb.jdbc.Driver" }
        }
    }
}

tasks {
    generateJooqClasses {
        schemas.set(listOf("testdb"))                                  // only generate for tables you'll use
        basePackageName.set("io.ekbatan.test.<your_module>_mariadb.generated.jooq")
        migrationLocations.setFromFilesystem("src/test/resources/db/migration")
        outputDirectory.set(project.layout.buildDirectory.dir("generated-jooq"))
        flywayProperties.put("flyway.placeholderReplacement", "false")
        includeFlywayTable.set(false)
        outputSchemaToDefault.add("testdb")          // generate at root; no `<schema>/` subpackage on MariaDB/MySQL
        usingJavaConfig {
            database.withForcedTypes(
                ForcedType()
                    .withUserType("java.time.Instant")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.InstantConverter")
                    .withIncludeTypes("(?i:DATETIME|TIMESTAMP)")
                    .withIncludeExpression(".*"),
                ForcedType()
                    .withName("JSON")                    // MariaDB reports JSON as LONGTEXT; retype so the converter attaches
                    .withUserType("tools.jackson.databind.node.ObjectNode")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.JSONObjectNodeConverter")
                    .withIncludeExpression(".*\\.my_json_column"),   // by name; the reported type is not "JSON"
            )
        }
    }
}

dependencies {
    implementation("org.mariadb.jdbc:mariadb-java-client:${project.property("mariadbJavaClientVersion")}")
    jooqCodegen("org.mariadb.jdbc:mariadb-java-client:${project.property("mariadbJavaClientVersion")}")
    jooqCodegen("org.flywaydb:flyway-mysql:${project.property("flywayVersion")}")
    implementation("org.flywaydb:flyway-mysql:${project.property("flywayVersion")}")
    // …
}
```

**MySQL** — same shape as MariaDB but adds the UUID forced-type entry (CHAR(36) → UUID via `UuidStringConverter`).
```kotlin
jooq {
    withContainer {
        image {
            name = "mysql:9.4.0"
            envVars = mapOf("MYSQL_ROOT_PASSWORD" to "root", "MYSQL_DATABASE" to "testdb")
        }
        db {
            username = "root"; password = "root"; name = "testdb"; port = 3306
            jdbc { schema = "jdbc:mysql"; driverClassName = "com.mysql.cj.jdbc.Driver" }
        }
    }
}

tasks {
    generateJooqClasses {
        schemas.set(listOf("testdb"))
        // … same Instant + JSON forced types as MariaDB, plus:
        usingJavaConfig {
            database.withForcedTypes(
                // … Instant, JSON entries …
                ForcedType()
                    .withUserType("java.util.UUID")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.mysql.UuidStringConverter")
                    .withIncludeTypes("CHAR\\(36\\)")
                    .withIncludeExpression(".*\\.id|.*_id"),       // narrow scope: UUID columns only
            )
        }
    }
}

dependencies {
    implementation("com.mysql:mysql-connector-j:${project.property("mysqlConnectorVersion")}")
    jooqCodegen("com.mysql:mysql-connector-j:${project.property("mysqlConnectorVersion")}")
    jooqCodegen("org.flywaydb:flyway-mysql:${project.property("flywayVersion")}")
    implementation("org.flywaydb:flyway-mysql:${project.property("flywayVersion")}")
    // …
}
```

**Why `schemas.set(listOf("testdb"))` excludes `eventlog` for MariaDB/MySQL but PG includes both:** generated classes are only useful where the repository will actually reference them. Modules that manually define `Field<UUID>`/`Field<ObjectNode>` constants for some tables (e.g. the `event_notifications` table in `local-event-handler`) don't need those tables generated — and including them tends to surface dialect-specific JDBC type quirks that aren't worth fighting (e.g. MariaDB JSON↔LONGTEXT confusion, MySQL UUID↔CHAR(36) needing a column-name-narrowed UUID converter). Generate only what your repos consume; let the manual field definitions handle the rest.

**Why the MySQL UUID forced type uses `withIncludeExpression(".*\\.id|.*_id")`:** unlike Postgres/MariaDB, MySQL has no native UUID type — every UUID column is just `CHAR(36)`. Without an expression filter, the converter would also bind to unrelated `CHAR(36)` columns (handler names, status enums, etc.). Restrict to columns whose name ends in `id` or is named `id`.

#### Test container init scripts

For MariaDB/MySQL TestContainers, place init SQL in `src/test/resources/<dialect>_init.sql` and mount it via `withCopyFileToContainer(MountableFile.forClasspathResource("mariadb_init.sql"), "/docker-entrypoint-initdb.d/mariadb_init.sql")`. The container's entrypoint runs every `.sql` in that directory as root before the DB becomes ready, which is the right place to issue cross-database `GRANT`s and any one-time setup the test user lacks privilege for. Don't put privilege grants in Flyway migrations — they require root, and Flyway connects as the test user.

### OpenTelemetry Tracing

Ekbatan instruments its action execution pipeline using the **OpenTelemetry API** (`opentelemetry-api`). The library depends only on the API — no SDK. When no OTel SDK is registered at runtime, all tracing calls are no-ops with zero overhead. Consumers bring their own `opentelemetry-sdk` and exporters.

**Instrumentation scope:** `io.ekbatan.core` version `1.1.0`, obtained from `GlobalOpenTelemetry.get().getTracer(...)`.

**Span hierarchy:**
```
[ekbatan.action.execute]                    ActionExecutor.execute()
├── [ekbatan.action.perform]                Action.perform()
└── [ekbatan.action.persist]                ActionExecutor.persistChanges()
    └── [ekbatan.transaction]               TransactionManager.inTransactionChecked() (per shard)
        ├── [ekbatan.repository]            AbstractRepository addAllNoResult/updateAllNoResult
        └── [ekbatan.event.persist]         EventPersister.persistActionEvents()
```

**Attributes:**

| Attribute | Type | Span | Description |
|---|---|---|---|
| `ekbatan.action.name` | string | action.execute | Simple class name of the action |
| `ekbatan.action.principal` | string | action.execute | Principal name |
| `ekbatan.action.outcome` | string | action.execute | `"success"` or `"error"` |
| `ekbatan.action.retry.count` | long | action.execute | Total retries (0 if none) |
| `ekbatan.shard.cross_shard` | boolean | action.persist | Present when changes span multiple shards |
| `ekbatan.shard.group` | long | transaction | Shard group identifier |
| `ekbatan.shard.member` | long | transaction | Shard member identifier |
| `db.operation.name` | string | repository | `"BATCH_INSERT"` or `"BATCH_UPDATE"` (set by `addAllNoResult` / `updateAllNoResult`) |
| `ekbatan.entity.type` | string | repository | Simple class name of the domain object |
| `ekbatan.batch.size` | long | repository | Number of records in the batch |
| `ekbatan.event.count` | long | event.persist | Number of model events persisted |

**Retry events:** Each retry attempt adds a span event named `"retry"` to the action span with attributes `retry.attempt` (int) and `retry.exception` (exception class name).

**Error handling:** On failure, spans are marked with `StatusCode.ERROR` and the exception is recorded via `span.recordException()`.

**Context propagation:** Since actions execute single-threaded (ScopedValue-based transactions), context flows naturally via `Span.makeCurrent()` / `Scope`. No async context passing is needed. Each `TransactionManager` instance knows its own `ShardIdentifier` (set at construction time, defaults to `ShardIdentifier.DEFAULT`), so `inTransactionChecked()` automatically sets shard attributes on the transaction span without requiring the shard to be passed per-call.

### Builder Pattern

The project uses the Builder pattern extensively. There are two categories: **infrastructure builders** (for framework classes like `ActionExecutor`, `ExecutionConfiguration`, registries) and **domain builders** (for `Model` and `Entity` subclasses). Both follow the same core principles but differ in structure.

> **Strongly recommended: builder-POJOs everywhere, not Java records.** Every immutable in-memory representation in this codebase — domain `Model`/`Entity` subclasses, infrastructure config classes, *and DB-row-representing types like* `EventEntity` *and* `EventNotification` — uses the same builder-POJO style: `public final` fields, private constructor taking a Builder, fluent Builder with a static factory, validation in the constructor. Do **not** introduce Java `record` types for these. Records can't carry the validation conventions, can't extend the `Model`/`Entity` base classes, can't be incrementally constructed via fluent setters, and produce noticeably different call sites — mixing the two styles makes the codebase inconsistent and harder to read. Use records only for genuinely transient internal carriers if absolutely necessary, never for anything that represents a row, an event, or a domain concept.

#### Core Principles

1. **Target class fields are `public final`** — the target class is immutable, so fields are exposed directly. No getters needed. Builder fields are `private` — they are internal to the builder.
2. **Builder is a `static final class` nested inside the target class** (or a separate generated class for `@AutoBuilder` domain objects).
3. **Private constructor on the target class** — only the Builder can instantiate it. The constructor takes the Builder as its sole argument.
4. **Private constructor on the Builder** — instantiation goes through a static factory method.
5. **Static factory method** — named after the thing being built, returns a new Builder instance.
6. **Setter methods just assign** — no validation, and no guards on prior state (e.g. "already called", "call order"). Builders are often returned partially-configured from factories, and a downstream caller may legitimately override an earlier value — restricting that breaks composability. Last call wins, same as repeated `Map.put`. Setters return `this` (or `self()` for generic builders) for fluent chaining.
7. **All validation happens in the target class constructor** — the constructor reads builder fields and validates using `Validate.notNull()`, `Validate.isTrue()`, etc. This is the single place where invariants are enforced. **The constructor makes no assumption about builder fields, even when the Builder declares a field-level default** — a caller can pass `null` (or a nonsensical value like `-1`, `Duration.ZERO`) to a setter and overwrite the default. Apply `Validate.notNull` to every required non-primitive field, and `Validate.isTrue` for any semantic constraint (e.g. `batchSize > 0`, `pollDelay` is positive), regardless of whether the Builder has a default for it. The only exception is fields whose default is resolved in the constructor itself (see point 9) — the resolution is its own null-handling.
8. **Default values are set on the builder field declaration** — not resolved with ternary logic in the constructor. If a field has a sensible default, assign it at the field level in the Builder. The constructor still validates per point 7; the default just means callers don't *have* to set the field for validation to pass.
9. **Fields with dependent defaults** — when a default depends on other builder state (another field, or the final set of registered entries at build time), the default cannot be set at the field level. In this case, declare the builder field as `Optional<T>` initialized to `Optional.empty()`, and resolve it in the constructor with `orElseGet(...)`. Avoid raw `null` in builder fields — express absence explicitly with `Optional`.

#### Infrastructure Builder Example

```java
public final class Foo {

    public final Bar bar;                       // required field
    public final Baz baz;                       // required field
    public final int maxSize;                   // primitive with default
    public final Optional<Retry> retryConfig;         // optional field
    public final List<String> tags;             // collection with default
    public final Qux qux;                       // field with dependent default

    private Foo(Builder builder) {
        // Required fields — validate in constructor
        this.bar = Validate.notNull(builder.bar, "bar is required");
        this.baz = Validate.notNull(builder.baz, "baz is required");

        // Primitive with default — just assign (default set on builder field)
        this.maxSize = builder.maxSize;

        // Optional — just assign (default set on builder field)
        this.retryConfig = builder.retryConfig;

        // Collection with default — just assign (default set on builder field)
        this.tags = List.copyOf(builder.tags);

        // Dependent default — resolve here because it depends on another field
        this.qux = builder.qux.orElseGet(() -> new DefaultQux(this.bar));
    }

    public static final class Builder {
        // Required fields — no default, left null
        private Bar bar;
        private Baz baz;

        // Primitive with default — set at field level
        private int maxSize = 100;

        // Optional field — use Optional, set default at field level
        private Optional<Retry> retryConfig = Optional.of(StaleRecordFixedRetry.DEFAULT);

        // Collection with default — initialize at field level
        private List<String> tags = new ArrayList<>();

        // Dependent default — Optional.empty() until set, resolved in constructor
        private Optional<Qux> qux = Optional.empty();

        private Builder() {}

        public static Builder foo() {
            return new Builder();
        }

        // Setter for required object field — just assign, no validation
        public Builder bar(Bar bar) {
            this.bar = bar;
            return this;
        }

        public Builder baz(Baz baz) {
            this.baz = baz;
            return this;
        }

        // Setter for primitive field
        public Builder maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        // Setter for Optional field — wraps in Optional.of()
        public Builder retryConfig(Retry retryConfig) {
            this.retryConfig = Optional.of(retryConfig);
            return this;
        }

        // Explicit "unset" method for Optional field — sets to Optional.empty()
        public Builder noRetry() {
            this.retryConfig = Optional.empty();
            return this;
        }

        // Setter for collection — replaces the list
        public Builder tags(List<String> tags) {
            this.tags = new ArrayList<>(tags);
            return this;
        }

        // Additive method for collection — uses "with" prefix
        public Builder withTag(String tag) {
            this.tags.add(tag);
            return this;
        }

        // Setter for field with dependent default — wraps in Optional.of()
        public Builder qux(Qux qux) {
            this.qux = Optional.of(qux);
            return this;
        }

        public Foo build() {
            return new Foo(this);
        }
    }
}
```

#### Field Type Rules

| Field type | Builder field type | Builder default | Setter pattern | Constructor handling |
|---|---|---|---|---|
| Required object (no default) | `T` | `null` (no default) | `this.field = value` | `Validate.notNull(builder.field, "...")` |
| Required object (with default) | `T` | `DEFAULT_X` | `this.field = value` | `Validate.notNull(builder.field, "...")` — default doesn't excuse the check; caller can `.field(null)` |
| Required primitive | `int`, `long`, etc. | explicit default or `0` | `this.field = value` | `Validate.isTrue(...)` for any semantic bound (e.g. `> 0`); the default doesn't excuse the check |
| Optional object | `Optional<T>` | `Optional.of(default)` or `Optional.empty()` | `this.field = Optional.of(value)` | `this.field = builder.field` (setter's `Optional.of` already rejects null) |
| Collection | `List<T>` | `new ArrayList<>()` | Replace: `new ArrayList<>(value)`, Add: `this.list.add(value)` | `List.copyOf(builder.field)` (rejects null) |
| Field with dependent default | `Optional<T>` | `Optional.empty()` | `this.field = Optional.of(value)` | `builder.field.orElseGet(() -> computeDefault())` |

#### Naming Conventions for Builder Methods

- **Static factory**: named after the thing being built — `foo()`, `actionExecutor()`, `executionConfiguration()`
- **Setter**: named after the field — `bar(Bar bar)`, `maxSize(int maxSize)`
- **Additive (collections)**: `with` prefix — `withTag(String tag)`, `withEvent(ModelEvent event)`, `withAction(Class action, Supplier supplier)`
- **Replace (collections)**: named after the field — `tags(List<String> tags)`, `events(List<ModelEvent> events)`
- **Unset (Optional)**: `no` prefix — `noRetry()`

#### Domain Builder (Model/Entity)

Domain builders for `Model` and `Entity` subclasses differ from infrastructure builders in structure:

- They extend `Model.Builder<ID, B, M, STATE>` or `Entity.Builder<ID, B, E, STATE>` — abstract generic base classes that provide `id`, `state`, `version`, and (for Model) `events`, `createdDate`, `updatedDate`.
- They use the **`self()` pattern** for fluent chaining across the generic hierarchy. Each setter in the base class returns `self()` (which casts `this` to the concrete builder type `B`) instead of `this`, so the return type stays as e.g. `WalletBuilder` rather than falling back to `Model.Builder`.
- The domain class constructor takes its builder as the sole argument, calls `super(builder)` to initialize base fields, then reads and validates domain-specific fields from the builder.
- Domain classes also define a `copy()` method that creates a builder pre-populated with the current state, enabling immutable updates.
- All the same core principles apply: `public final` fields on the domain class, validation in the constructor, defaults on builder field declarations, setters just assign.

```java
// Domain class
public final class Wallet extends Model<Wallet, Id<Wallet>, WalletState> {

    public final UUID ownerId;
    public final Currency currency;
    public final BigDecimal balance;

    Wallet(WalletBuilder builder) {
        super(builder);
        this.ownerId = Validate.notNull(builder.ownerId, "ownerId cannot be null");
        this.currency = Validate.notNull(builder.currency, "currency cannot be null");
        this.balance = Validate.notNull(builder.balance, "balance cannot be null");
    }

    // Creation factory method — the "official" way to create a new Wallet.
    // Named createXxx() where Xxx matches the class name, so it reads clearly with static imports:
    //     import static ...Wallet.createWallet;
    //     createWallet(ownerId, currency, balance);
    // This method sets up id, state, initial version, AND emits the creation event.
    // Use this ONLY when you want the full creation ceremony including the event.
    // If you need a Wallet instance WITHOUT the creation event (e.g., test fixtures
    // simulating a model already loaded from the DB), use WalletBuilder.wallet()
    // (or Wallet.Builder.wallet() if the builder is a nested class) directly.
    public static WalletBuilder createWallet(UUID ownerId, Currency currency, BigDecimal balance) {
        final var id = Id.random(Wallet.class);
        return WalletBuilder.wallet()
                .id(id)
                .state(OPENED)
                .ownerId(ownerId)
                .currency(currency)
                .balance(balance)
                .withInitialVersion()
                .withEvent(new WalletCreatedEvent(id, ownerId, currency, balance));
    }

    // copy() enables immutable updates
    @Override
    public WalletBuilder copy() {
        return WalletBuilder.wallet()
                .copyBase(this)
                .ownerId(ownerId)
                .currency(currency)
                .balance(balance);
    }

    // Business method — returns a new immutable instance
    public Wallet deposit(BigDecimal amount) {
        return copy()
                .balance(balance.add(amount))
                .increaseVersion()
                .withEvent(new WalletMoneyDepositedEvent(id, amount))
                .build();
    }
}

// Domain builder — extends Model.Builder
public final class WalletBuilder extends Model.Builder<Id<Wallet>, WalletBuilder, Wallet, WalletState> {

    UUID ownerId;
    Currency currency;
    BigDecimal balance;

    private WalletBuilder() {}

    public static WalletBuilder wallet() {
        return new WalletBuilder();
    }

    public WalletBuilder ownerId(UUID ownerId) {
        this.ownerId = ownerId;
        return this;
    }

    public WalletBuilder currency(Currency currency) {
        this.currency = currency;
        return this;
    }

    public WalletBuilder balance(BigDecimal balance) {
        this.balance = balance;
        return this;
    }

    @Override
    public Wallet build() {
        return new Wallet(this);
    }
}
```

Note that domain builder fields are **package-private** (not `private`), because the domain class in the same package reads them directly in its constructor.

**Inherited methods from `Model.Builder`:** `id()`, `state()`, `version()`, `withInitialVersion()`, `increaseVersion()`, `withEvent()`, `events()`, `createdDate()`, `updatedDate()`, `copyBase()`, `self()`, and abstract `build()`.

**Inherited methods from `Entity.Builder`:** `id()`, `state()`, `version()`, `withInitialVersion()`, `increaseVersion()`, `copyBase()`, `self()`, and abstract `build()`. Entity has no events, createdDate, or updatedDate.

#### @AutoBuilder (Code Generation)

For domain classes (`Model` and `Entity` subclasses), writing the builder by hand is repetitive — the builder just mirrors the domain class fields with setters and getters. The `@AutoBuilder` annotation processor eliminates this boilerplate by generating the builder class at compile time.

**When to use `@AutoBuilder`:** Use it for standard domain classes where the builder is a straightforward mirror of the domain fields. If a domain builder needs custom logic (conditional defaults, computed fields, specialized setter behavior), write the builder manually instead.

**How it works:**

1. Annotate the domain class with `@AutoBuilder`:
   ```java
   @AutoBuilder
   public final class Wallet extends Model<Wallet, Id<Wallet>, WalletState> {
       public final UUID ownerId;
       public final Currency currency;
       public final BigDecimal balance;
       public final List<String> aliases;

       Wallet(WalletBuilder builder) {
           super(builder);
           this.ownerId = Validate.notNull(builder.ownerId, "ownerId cannot be null");
           this.currency = Validate.notNull(builder.currency, "currency cannot be null");
           this.balance = Validate.notNull(builder.balance, "balance cannot be null");
           this.aliases = Objects.requireNonNullElse(builder.aliases, List.of());
       }

       // ... createWallet(), copy(), business methods ...
   }
   ```

2. The annotation processor generates a `WalletBuilder` class at compile time (in `build/generated/sources/annotationProcessor/`):
   ```java
   @Generated("io.ekbatan.core.processor.AutoBuilderProcessor")
   public final class WalletBuilder extends Model.Builder<Id<Wallet>, WalletBuilder, Wallet, WalletState> {
       UUID ownerId;
       Currency currency;
       BigDecimal balance;
       List<String> aliases;

       private WalletBuilder() {}

       public static WalletBuilder wallet() { return new WalletBuilder(); }

       public WalletBuilder ownerId(UUID ownerId) { this.ownerId = ownerId; return this; }
       public UUID ownerId() { return this.ownerId; }

       public WalletBuilder currency(Currency currency) { this.currency = currency; return this; }
       public Currency currency() { return this.currency; }

       // ... same for balance, aliases ...

       @Override
       public Wallet build() { return new Wallet(this); }
   }
   ```

**What @AutoBuilder generates:**

- A `final` class named `<ModelName>Builder` extending `Model.Builder` or `Entity.Builder` with the correct type parameters.
- **Package-private fields** for each non-static field declared in the domain class (not inherited fields — those come from the base builder).
- **Fluent setter** for each field — assigns the value and returns `this`.
- **Getter** for each field — returns the current value.
- **Private constructor** — enforces instantiation through the static factory method.
- **Static factory method** — named after the domain class in lowercase (e.g., `wallet()` for `Wallet`, `product()` for `Product`).
- **`build()` override** — calls `new ModelClass(this)`.
- **`@Generated` annotation** — marks the class as processor-generated.

**What @AutoBuilder does NOT generate:**

- The domain class itself — you write that manually.
- The domain class constructor — you write that with validation logic.
- Business methods (`deposit()`, `withdraw()`, `delete()`) — those live on the domain class.
- The `copy()` method — you write that on the domain class.
- Factory methods (`createWallet()`) — you write those on the domain class.
- Any custom setter behavior — if you need special setters, write the builder manually.

**Constraints:**

- The annotated class must directly extend `Model` or `Entity`.
- The annotation has source retention (`@Retention(RetentionPolicy.SOURCE)`) — it is erased from compiled bytecode.
- Generated setters do not deep-copy mutable fields (e.g., `List`). If the domain class needs defensive copies, handle it in the domain class constructor.
- The generated builder follows all standard builder conventions: package-private fields, private constructor, static factory, setters just assign, no validation.

### Naming Conventions
- Java: `camelCase` fields, `PascalCase` classes
- SQL: `snake_case` columns and tables
- Packages: `lowercase` with underscores for multi-word directories (e.g., `denormalized`)
- Actions: `Model[Verb]Action` — e.g., `WalletCreateAction`, `WalletDepositMoneyAction` (not `CreateWalletAction`)
- Commit messages: `EKB-XXXX` ticket prefix

## Testing

### Test Infrastructure
- **`BaseRepositoryTest`** — Large abstract test class in `ekbatan-integration-tests/core-repo/shared` covering all CRUD operations, optimistic locking, soft deletion
- **`BaseShardedRepositoryTest`** — Abstract test class covering shard-aware operations: scatter-gather reads, ID-based routing, cross-shard batch rejection, shard isolation
- **`Dummy` model** — Test-only model mirroring the Wallet structure
- **Database-specific runners** — Separate subprojects for PostgreSQL, MySQL, MariaDB using TestContainers
- **Flyway migrations** — Per-database test migrations in each test subproject

### Test Stack
- JUnit 5 (Jupiter)
- Mockito (mocking in ekbatan-core), MockK (mocking in core-repo tests)
- AssertJ (fluent assertions)
- JsonUnit (JSON assertions)
- TestContainers (database containers)

### Test Structure — GIVEN / WHEN / THEN

All tests use `// GIVEN`, `// WHEN`, `// THEN` comments to separate setup, action, and assertion phases. This makes the test's intent immediately clear. Combine or extend phases as appropriate:

```java
// Standard form
@Test
void should_create_wallet() throws Exception {
    // GIVEN
    var clock = new VirtualClock();
    clock.pauseAt(Instant.parse("2025-01-01T00:00:00Z"));

    // WHEN
    var result = ActionSpec.of(new CreateAction(clock))
            .withPrincipal(() -> "user")
            .execute(new CreateAction.Params("wallet"));

    // THEN
    result.assertAdded(Wallet.class, w -> {
        assertThat(w.name).isEqualTo("wallet");
    });
}

// Combined GIVEN / WHEN — when setup and action are trivially interleaved
@Test
void constructor_rejects_null() {
    // GIVEN / WHEN / THEN
    assertThatThrownBy(() -> new Foo(null))
            .isInstanceOf(NullPointerException.class);
}

// Combined WHEN / THEN — when action and assertion are a single expression
@Test
void returns_empty_for_unknown_type() {
    // GIVEN
    var registry = repositoryRegistry().build();

    // WHEN / THEN
    assertThat(registry.repository(Wallet.class)).isNull();
}

// Multiple rounds — when a test exercises a sequence of actions
@Test
void retry_recovers_after_transient_failure() throws Exception {
    // GIVEN
    var attempts = new AtomicInteger(0);
    var retry = Retry.with(Map.of(IllegalStateException.class, new RetryConfig(2, Duration.ZERO)));

    // WHEN
    var result = retry.execute(() -> {
        if (attempts.incrementAndGet() <= 1) throw new IllegalStateException("transient");
        return "ok";
    });

    // THEN
    assertThat(result).isEqualTo("ok");

    // AND
    assertThat(attempts.get()).isEqualTo(2);
}
```

**Rules:**
- Every test must have at least `// GIVEN / WHEN / THEN` or `// GIVEN`, `// WHEN`, `// THEN` as separate comments.
- Use `// AND` for additional assertions that verify a different aspect of the result.
- Use `// GIVEN / WHEN / THEN` (combined on one line) only for single-expression tests like null-rejection checks.
- Use `// WHEN / THEN` when the action and assertion are a single fluent call.
- Phases can repeat (`// WHEN`, `// THEN`, `// AND`, `// WHEN`, `// THEN`) for multi-step scenarios.

### Running Tests
```bash
./gradlew test                                    # All tests
./gradlew :ekbatan-integration-tests:core-repo:pg:repository:test    # PostgreSQL only
./gradlew :ekbatan-integration-tests:core-repo:mysql:repository:test # MySQL only
./gradlew :ekbatan-integration-tests:core-repo:mariadb:repository:test # MariaDB only
./gradlew :ekbatan-integration-tests:test          # Integration tests
```

## Build & Tooling

- **Java 25** — Uses records, ScopedValue, modern features
- **Gradle** (Kotlin DSL) — Multi-project build (the framework repo itself)
- **Spotless** — Palantir Java Format 2.81.0, auto-applied before build
- **Checkstyle** — Custom rules in `config/checkstyle/checkstyle.xml`
- **JOOQ Docker Plugin** (Gradle) — Generates type-safe SQL classes from Flyway migrations via Docker
- **Dependency versions** — Centralized in `gradle.properties`

The framework repo itself is Gradle-only — `build-logic/` convention plugins, the `ekbatan.publishing` plugin, and the `dev.monosoul.jooq-docker` integration assume Gradle. **Downstream consumers, however, can use either Gradle or Maven** — the 17 published JARs have plain Maven POMs with no Gradle-only metadata. The runnable proof is the Maven wallet matrix under `ekbatan-examples/`: 18 projects covering Spring Boot, Quarkus, and Micronaut across PostgreSQL/MariaDB/MySQL, with JVM and GraalVM native-image variants for each:

- **Spring Boot:** [`spring-boot-wallet-rest-maven-pg`](./ekbatan-examples/spring-boot-wallet-rest-maven-pg), [`-mariadb`](./ekbatan-examples/spring-boot-wallet-rest-maven-mariadb), [`-mysql`](./ekbatan-examples/spring-boot-wallet-rest-maven-mysql)
- **Spring Boot + GraalVM native-image:** [`spring-boot-wallet-rest-maven-native-pg`](./ekbatan-examples/spring-boot-wallet-rest-maven-native-pg), [`-mariadb`](./ekbatan-examples/spring-boot-wallet-rest-maven-native-mariadb), [`-mysql`](./ekbatan-examples/spring-boot-wallet-rest-maven-native-mysql)
- **Quarkus:** [`quarkus-wallet-rest-maven-pg`](./ekbatan-examples/quarkus-wallet-rest-maven-pg), [`-mariadb`](./ekbatan-examples/quarkus-wallet-rest-maven-mariadb), [`-mysql`](./ekbatan-examples/quarkus-wallet-rest-maven-mysql)
- **Quarkus + GraalVM native-image:** [`quarkus-wallet-rest-maven-native-pg`](./ekbatan-examples/quarkus-wallet-rest-maven-native-pg), [`-mariadb`](./ekbatan-examples/quarkus-wallet-rest-maven-native-mariadb), [`-mysql`](./ekbatan-examples/quarkus-wallet-rest-maven-native-mysql)
- **Micronaut:** [`micronaut-wallet-rest-maven-pg`](./ekbatan-examples/micronaut-wallet-rest-maven-pg), [`-mariadb`](./ekbatan-examples/micronaut-wallet-rest-maven-mariadb), [`-mysql`](./ekbatan-examples/micronaut-wallet-rest-maven-mysql)
- **Micronaut + GraalVM native-image:** [`micronaut-wallet-rest-maven-native-pg`](./ekbatan-examples/micronaut-wallet-rest-maven-native-pg), [`-mariadb`](./ekbatan-examples/micronaut-wallet-rest-maven-native-mariadb), [`-mysql`](./ekbatan-examples/micronaut-wallet-rest-maven-native-mysql)

All Maven wallet examples consume Ekbatan from Maven Central and use the `fabric8 docker-maven-plugin` + `flyway-maven-plugin` + `jooq-codegen-maven` chain for codegen (the Maven equivalent of the Gradle plugin). Stack-specific Maven nuances: the **Spring Boot** variants use `spring-boot-starter-parent` + `spring-boot-maven-plugin`; the **Quarkus** variants use `quarkus-bom` import (no parent) + `quarkus-maven-plugin` with `<extensions>true</extensions>`; the **Micronaut** variants use `micronaut-parent` + `micronaut-maven-plugin` and demonstrate `<annotationProcessorPaths combine.children="append">` for keeping the parent POM's `micronaut-inject-java` AP entry. The examples run migrations via a startup migrator calling `FlywayMigrator.migrate(shardingConfig)`. Native variants also add `ekbatan-native` for native metadata; `ekbatan-flyway` owns the programmatic Flyway migrator and its native-image classpath scanning. Maven-flavored consumer docs live at [docs/maven/](./docs/maven/README.md). Don't add Maven build files anywhere else in the framework — Maven is a *consumer-side* concern, not a framework-side one.

### Build Commands
```bash
./gradlew build          # Build all (includes spotlessApply)
./gradlew spotlessApply  # Format code
./gradlew checkFormat    # Check formatting without applying
./gradlew test           # Run tests
```

## Adding New Features

### New Domain Model
1. Create model class extending `Model<M, Id<M>, STATE>` with `@AutoBuilder`
2. Create state enum
3. Create domain events extending `ModelEvent<M>`
4. Create Flyway migration SQL
5. Generate JOOQ classes (via Docker plugin)
6. Create repository extending `ModelRepository`
7. Register in `RepositoryRegistry`

### New Entity
1. Create entity class extending `Entity<E, ID, STATE>` with `@AutoBuilder`
2. Create Flyway migration
3. Generate JOOQ classes
4. Create repository extending `EntityRepository`
5. Register in `RepositoryRegistry`

### New Action
1. Create action class extending `Action<PARAM, RESULT>`
2. Implement `perform(principal, params)` using `plan.add()` / `plan.update()`
3. Register in `ActionRegistry`

### New Database Support
1. Add test subproject under `ekbatan-integration-tests/core-repo`
2. Create database-specific Flyway migrations
3. Add `DummyRepository` implementation with dialect-specific converters
4. Create test runner extending `BaseRepositoryTest`
5. Handle dialect differences in `AbstractRepository` if needed

## Release & Publishing

Ekbatan is published on Maven Central under groupId `io.github.ekbatan-io`. Note that Maven groupId and Java package names are deliberately not aligned — source imports remain `io.ekbatan.*` while the published coordinate is `io.github.ekbatan-io:*`. This split is normal practice (Picocli ships as `info.picocli` with `picocli.*` packages, Lombok as `org.projectlombok` with `lombok.*`).

Full release procedure — one-time Sonatype/GPG setup, per-release workflow, troubleshooting, GPG keyring recovery — lives in [RELEASE.md](./RELEASE.md). Load-bearing facts for agents working in the codebase:

- **17 modules are published** (anything applying the `ekbatan.publishing` Gradle convention plugin). The 2 Debezium SMT shadow jars ship via GitHub Releases only, not Maven Central. Adding the `ekbatan.publishing` plugin to a new module makes it part of the published surface.
- **Tag-driven releases**. Pushing `vX.Y.Z` to `main` fires `.github/workflows/release.yml` → builds → JReleaser signs every artifact and uploads to Sonatype's validation window → **stops there**. A human verifies on `central.sonatype.com` and clicks Publish (or Drop). Controlled by `stage.set(UPLOAD)` in `build.gradle.kts`'s `jreleaser` block; switching to `Stage.FULL` would re-enable auto-publish.
- **Coordinates are permanent**. Once `<groupId>:<artifactId>:<version>` lands on Maven Central it cannot be removed, modified, or overwritten. Public API changes in a published module are visible to every downstream consumer at that exact coordinate forever — treat each release as final.
- **Client-side git hooks**. `.githooks/pre-commit` blocks commits when `./gradlew spotlessCheck` fails. `.githooks/pre-push` blocks `vX.Y.Z` tag pushes when `gradle.properties` `version` at the tagged commit doesn't match. Opt in once per clone via `git config core.hooksPath .githooks`; the release workflow re-checks the tag/version condition as a safety net.
- **GPG signing material lives in 3 GitHub Actions secrets** (`GPG_PUBLIC_KEY` / `GPG_PRIVATE_KEY` / `GPG_PASSPHRASE`) plus the Sonatype User Token in 2 more (`MAVENCENTRAL_USERNAME` / `MAVENCENTRAL_PASSWORD`). They are NOT in the repo. JReleaser reads them via `JRELEASER_*` env vars set in `release.yml`.
