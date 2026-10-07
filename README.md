# Ekbatan

**Ekbatan is an event-driven Java persistence framework with built-in outbox pattern, atomic transactions with domain events, and support for Spring, Quarkus, and Micronaut or plain Java.**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.ekbatan-io/ekbatan-core?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.ekbatan-io/ekbatan-core)
[![Java 25+](https://img.shields.io/badge/Java-25%2B-orange)](https://openjdk.org/projects/jdk/25/)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue)](https://www.apache.org/licenses/LICENSE-2.0)

The **outbox pattern** stores domain events alongside state in the same atomic database transaction - both writes land together, or neither does - making the database the single source of truth for both. Downstream tools like Debezium can later publish those events to Kafka, Pulsar, or any other broker, without the dual-write trap.

Ekbatan is a Java library you embed in your application - Spring, Quarkus, Micronaut, or plain Java. It does **not** replace your full-stack framework; it replaces the persistence layer where Spring Data, Hibernate, JPA, or hand-rolled JDBC usually live.

Built for **Java 25+**, sitting directly on **JOOQ**, designed around **virtual threads**.

**Works with:** PostgreSQL · MariaDB · MySQL — and Spring Boot · Quarkus · Micronaut · plain Java. The same domain code compiles and runs identically against every database and DI container; only the wiring at the edges changes.

Read more in the Ekbatan documentation website: https://ekbatan-io.github.io/ekbatan/

---

## Add to your project

Ekbatan is published on [Maven Central](https://central.sonatype.com/namespace/io.github.ekbatan-io) under groupId `io.github.ekbatan-io`.

### Gradle (Kotlin DSL)

**Spring Boot:**

```kotlin
dependencies {
    implementation("io.github.ekbatan-io:ekbatan-spring-boot-starter:1.1.1")
}
```

**Quarkus:**

```kotlin
dependencies {
    implementation("io.github.ekbatan-io:ekbatan-quarkus:1.1.1")
}
```

**Micronaut** — the `annotationProcessor` line is required (without it, Micronaut won't generate `BeanDefinition`s for `@Ekbatan*` classes):

```kotlin
dependencies {
    implementation("io.github.ekbatan-io:ekbatan-micronaut:1.1.1")
    annotationProcessor("io.github.ekbatan-io:ekbatan-micronaut:1.1.1")
    annotationProcessor("io.micronaut:micronaut-inject-java")
}
```

**Plain Java (no DI container)** — the integration jars above pull most of these transitively; here every optional module is spelled out:

```kotlin
dependencies {
    // ── Required ────────────────────────────────────────────────────────────
    implementation("io.github.ekbatan-io:ekbatan-core:1.1.1")

    // ── Optional capabilities ───────────────────────────────────────────────

    // @AutoBuilder code generation — generates *Builder classes for Models/Entities
    // (skip if you'd rather write the builders by hand)
    compileOnly("io.github.ekbatan-io:ekbatan-annotation-processor:1.1.1")
    annotationProcessor("io.github.ekbatan-io:ekbatan-annotation-processor:1.1.1")

    // In-process event handlers (fanout + handling jobs over the eventlog)
    implementation("io.github.ekbatan-io:ekbatan-local-event-handler:1.1.1")

    // Distributed background jobs (db-scheduler facade; cluster-exclusive scheduling)
    implementation("io.github.ekbatan-io:ekbatan-distributed-jobs:1.1.1")

    // Redis-backed distributed KeyedLockProvider (Redisson under the hood)
    implementation("io.github.ekbatan-io:ekbatan-keyed-lock-redis:1.1.1")

    // GraalVM native-image Features (auto-loaded; include only if you build native binaries)
    implementation("io.github.ekbatan-io:ekbatan-native:1.1.1")

    // Testing helpers: ActionSpec, ActionAssert, VirtualClock, and Testcontainers utilities
    testImplementation("io.github.ekbatan-io:ekbatan-test-support:1.1.1")

    // ── Wire-format DTOs (only for Kafka consumer apps reading from the eventlog) ──
    // Pick the one matching your Kafka serializer; not needed in the producer app itself.
    implementation("io.github.ekbatan-io:ekbatan-action-event-json:1.1.1")
    implementation("io.github.ekbatan-io:ekbatan-action-event-avro:1.1.1")
    implementation("io.github.ekbatan-io:ekbatan-action-event-protobuf:1.1.1")
}
```

### Maven

Substitute the artifactId for your stack — `ekbatan-spring-boot-starter`, `ekbatan-quarkus`, `ekbatan-micronaut`, or `ekbatan-core`:

```xml
<dependency>
    <groupId>io.github.ekbatan-io</groupId>
    <artifactId>ekbatan-spring-boot-starter</artifactId>
    <version>1.1.1</version>
</dependency>
```

**Using Ekbatan with Maven?** [docs/maven/](docs/maven/README.md) is the dedicated guide.

Per-stack setup details: [Spring Boot](docs/wiring/spring.md) · [Quarkus](docs/wiring/quarkus.md) · [Micronaut](docs/wiring/micronaut.md) · [Plain Java](docs/wiring/without-di.md).

---

## Learn by Example: A Wallet

### Model & Entity

A wallet is a **Model** — a domain object whose changes produce events. Models are the right fit when a record's history matters downstream: every mutation returns a new immutable instance with a `ModelEvent` attached, and the framework persists state and events together in one transaction. The contrast is an **Entity** — same persistence and version tracking, but **no events**: use it for lookup tables, settings, or auxiliary records whose history isn't consumed by other systems. `Wallet` produces events (deposits, closures) and is a Model; a `Country` reference table would be an Entity.

Our `Wallet` has an owner, a currency, and a balance:

```java
@AutoBuilder
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

    public Wallet deposit(BigDecimal amount) {
        var newBalance = balance.add(amount);
        return copy()
                .withEvent(new WalletMoneyDepositedEvent(id, amount, newBalance))
                .balance(newBalance)
                .build();
    }
    // close(), copy()...
}
```

`deposit(...)` returns a new wallet with the corresponding event attached - no database call is made here.

### Action & ActionPlan

An **Action** is a unit of business work — deposit money, create order, ship parcel. It does **not** write to the database itself; its `perform(...)` method stages changes onto an **ActionPlan**, and the executor commits that plan in one atomic transaction afterwards. One class per operation, with a typed `Params` record and a `perform(...)` method:

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
        var wallet = walletRepository.getById(params.walletId().getValue());
        var updated = wallet.deposit(params.amount());
        return plan().update(updated);
    }
}
```

The `plan()` accessor inside `perform(...)` returns the **ActionPlan** — a per-action staging area for everything the framework should persist. Call `plan().add(newDomainObject)` for inserts and `plan().update(modifiedDomainObject)` for updates; the call above (`plan().update(updated)`) registers the deposited wallet and returns the same value as the action's result. Nothing is committed yet: the plan is just an in-memory list of intended writes. The executor flushes the whole plan, plus any attached events, in a single transaction once `perform(...)` returns — see [the two-phase lifecycle](#the-action-lifecycle) below.

### Action Executor

The **Action Executor** runs the action by class and parameters:

```java
Wallet result = executor.execute(
        () -> "alice",
        WalletDepositAction.class,
        new WalletDepositAction.Params(walletId, new BigDecimal("25.50")));
```

The executor opens a single transaction, writes the new wallet row, writes the `WalletMoneyDepositedEvent` row into the events table, and commits. If any step throws, both writes are rolled back together.

### Repository

The `WalletRepository` injected into the action above maps the domain to JOOQ records. The base class gives you full CRUD; you add custom queries - `readonlyDb()` for replica reads, `db()` for primary reads:

```java
@EkbatanRepository
public class WalletRepository extends ModelRepository<Wallet, WalletsRecord, Wallets, UUID> {

    public WalletRepository(DatabaseRegistry databaseRegistry) {
        super(Wallet.class, WALLETS, WALLETS.ID, databaseRegistry);
    }

    // Replica read - list / search queries that tolerate replication lag
    public List<Wallet> findAllByOwnerId(UUID ownerId) {
        return readonlyDb()
                .selectFrom(WALLETS)
                .where(WALLETS.OWNER_ID.eq(ownerId))
                .fetch(this::fromRecord);
    }

    // Primary read - strongly-consistent reads (e.g. immediately after a write)
    public Optional<Wallet> findByIdOnPrimary(UUID walletId) {
        return db()
                .selectFrom(WALLETS)
                .where(WALLETS.ID.eq(walletId))
                .fetchOptional(this::fromRecord);
    }

    @Override
    public Wallet fromRecord(WalletsRecord r) { /* … */ }

    @Override
    public WalletsRecord toRecord(Wallet w) { /* … */ }
}
```

### Distributed Job

A **Distributed Job** is periodic background work that should run on **at most one** instance across the cluster - daily reports, hourly cleanups, periodic reconciliations:

```java
@EkbatanDistributedJob
public class DailyWalletReportJob extends DistributedJob {

    private final ReportService reportService;

    public DailyWalletReportJob(ReportService reportService) {
        this.reportService = reportService;
    }

    @Override public String name()       { return "daily-wallet-report"; }   // cluster-wide unique
    @Override public Schedule schedule() { return Schedules.daily(LocalTime.of(2, 0)); }

    @Override
    public void execute(ExecutionContext ctx) {
        reportService.generateAndSend();
    }
}
```

### Event Handler

An **Event Handler** reacts to a specific event after the action commits - for sending notifications, writing audit rows, or triggering downstream workflows. It feels like an in-process callback but is fully backed by the database: the framework's fan-out and dispatch jobs pick up committed events and deliver them with retry and at-least-once semantics, surviving restarts and running on whichever service instance holds the job lease:

```java
@EkbatanEventHandler
public class WalletMoneyDepositedEventHandler implements EventHandler<WalletMoneyDepositedEvent> {

    private final NotificationService notificationService;

    public WalletMoneyDepositedEventHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override public String name()                                 { return "wallet-deposit-notification"; }
    @Override public Class<WalletMoneyDepositedEvent> eventType()  { return WalletMoneyDepositedEvent.class; }

    @Override
    public void handle(EventEnvelope<WalletMoneyDepositedEvent> envelope) {
        notificationService.notifyDeposit(envelope.event.modelId, envelope.event.amount);
    }
}
```

### The Action Lifecycle

Every `executor.execute(...)` call runs in two distinct phases - pure construction first, then a single atomic transaction:

```
        executor.execute(WalletDepositAction.class, params)
                              │
                              ▼
   ┌─── Phase 1 - Action.perform()  (no DB transaction yet) ────┐
   │                                                            │
   │   1. Read from repositories                                │
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
   ┌─── Phase 2 - Executor.persistChanges()  (one atomic TX) ───┐
   │                                                            │
   │   TransactionManager.inTransaction(() -> {                 │
   │     Repository.addAll / updateAll  →  domain rows          │
   │     EventPersister.persistActionEvents  →  outbox rows     │
   │     commit  ─or─  rollback                                 │
   │   });                                                      │
   │                                                            │
   │   All writes land together, or none at all.                │
   └────────────────────────────────────────────────────────────┘
                              │
                              ▼
                  result returned to the caller
```

Phase 1 is pure construction - reads are allowed, but no writes happen. Phase 2 is the only place the framework opens a transaction, and it always wraps every staged change plus the matching event rows together. Anything that throws inside Phase 2 rolls the whole transaction back; on optimistic-lock conflicts (`StaleRecordException`) the executor re-runs the entire action from Phase 1 with a fresh plan.

Supporting types - `ModelEvent`, `Repository`, `TransactionManager`, `DatabaseRegistry`, `ShardedUUID` - are introduced in the relevant sections below.

---


## The Big Picture

Every business change produces **two things at once** - a new state, and an event recording how it got there. They have to travel together:

```
                       ┌──▶  STATE:   balance = $0
  Sara opens wallet  ──┤
                       └──▶  EVENT:   WalletCreated

                       ┌──▶  STATE:   balance = $250
  Sara deposits $250 ──┤
                       └──▶  EVENT:   MoneyDeposited($250)

                       ┌──▶  STATE:   balance = $150
  Sara spends   $100 ──┤
                       └──▶  EVENT:   MoneySpent($100)
```

The **state** is what your application reads. The **events** are what downstream systems consume - audit logs, analytics, other services. Persisting them as **two separate writes** - state to the database, events to Kafka - is where things break:

<table>
<tr>
<td width="50%" align="center">
<strong>✗ Two writes — the dual-write problem</strong><br><br>
<img src="docs/article-assets/gifs/dual-write-broken.gif" alt="Two writes: state saved, publish failed, no events" width="100%">
<br><em>Crash between the two writes ⇒ DB and Kafka disagree</em>
</td>
<td width="50%" align="center">
<strong>✓ One write + outbox</strong><br><br>
<img src="docs/article-assets/gifs/outbox-ekbatan.gif" alt="One write plus outbox: app writes state and events in one transaction, CDC ships events to the broker" width="100%">
<br><em>CDC tails the outbox ⇒ events shipped later, always in sync with state</em>
</td>
</tr>
</table>

The **outbox pattern** - the right-hand side - is well known and not specific to Ekbatan. What Ekbatan adds is making it **easy to do**: the outbox table, the row schema, and the write path are part of the framework. Applications just attach events to their domain objects, and the framework persists state-and-events together.

Visually, every action's commit looks like this - one transaction can touch as many domain tables as the action needs, plus the outbox:

```
┌──────────────────  ONE DATABASE TRANSACTION  ──────────────────────┐
│                                                                    │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────────┐    │
│  │    wallets     │  │     orders     │  │  eventlog.events   │    │
│  │   (UPDATE)     │  │    (INSERT)    │  │     (INSERT)       │    │
│  ├────────────────┤  ├────────────────┤  ├────────────────────┤    │
│  │ id             │  │ id             │  │ id                 │    │
│  │ balance        │  │ wallet_id      │  │ action_id          │    │
│  │ version        │  │ amount         │  │ event_type         │    │
│  │ ...            │  │ status: placed │  │ payload (JSONB)    │    │
│  └────────────────┘  │ ...            │  │ ...                │    │
│                      └────────────────┘  └────────────────────┘    │
│         domain                domain               outbox          │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
       commit (all rows persist)  -or-  rollback (nothing persists)
```

Placing an order, for example, might insert a new `orders` row, debit the `wallets` row, and insert one `eventlog.events` row per emitted event - all in the same atomic write. Whatever the action stages on its `ActionPlan`, the executor flushes together.

Around this core, the framework provides multi-database support, optional horizontal sharding, configurable retry policies, and built-in observability. Each capability is opt-in and adds no overhead when unused.

---

## Capabilities

Each topic links to a focused deep-dive doc with the full surface area, schema, and examples.

### Get started
- [Wiring without DI](docs/wiring/without-di.md) - full plain-Java end-to-end snippet, every line explained
- [Domain classes & the `@Ekbatan*` annotations](docs/wiring/annotations.md) - the four annotations and five domain classes that span every DI container
- [Wiring with Spring Boot](docs/wiring/spring.md) - one starter dep + `@Ekbatan*` annotations
- [Wiring with Quarkus](docs/wiring/quarkus.md) - extension + build-step / native specifics
- [Wiring with Micronaut](docs/wiring/micronaut.md) - integration jar + compile-time visitor
- [Building with Gradle](docs/gradle/README.md) - per-stack `build.gradle.kts` blocks, AP wiring, `dev.monosoul.jooq-docker` plugin, optional add-ons
- [Building with Maven](docs/maven/README.md) - `pom.xml` walkthrough, AP wiring, the `fabric8 docker + flyway-maven + jooq-codegen-maven` chain

### Core
- [The outbox: atomic state + events](docs/concepts/outbox.md) - the framework's atomic state-and-events guarantee
- [Actions, ActionPlan, ActionExecutor](docs/concepts/actions.md) - the two-phase lifecycle, retries, no nesting, single-threaded perform
- [Models and Entities](docs/concepts/models-and-entities.md) - when to use which, immutability, `@AutoBuilder`, optimistic locking

### Database
- [Repositories on JOOQ](docs/database/repositories.md) - `db()` / `readonlyDb()` / `txDb()` / `txDbElseDb()`, soft delete, custom queries
- [TransactionManager](docs/database/transaction-manager.md) - direct transactional DB access outside the Action pipeline
- [Outbox schema](docs/database/outbox-schema.md) - the SQL DDL of `eventlog.events`, the `delivered` flag, `event_notifications`, indexes
- [Sharding](docs/database/sharding.md) - group + member, `ShardedUUID`, custom `ShardingStrategy`, cross-shard rules
- [Connecting to the database](docs/database/connecting.md) - `username`, `password`, `data-source-properties`, and every way to log in: passwords, client certificates, IAM, Kerberos, driver plugins
- [Pessimistic locking via `KeyedLockProvider`](docs/database/keyed-locks.md) - four backends (Postgres, MySQL, MariaDB, Redis), reentrancy contract, caller-side acquisition pattern
- [Multi-database (PostgreSQL / MySQL / MariaDB)](docs/database/multi-database.md) - cross-dialect cheatsheet: type mapping, init scripts, partial indexes
- [PostgreSQL setup](docs/database/postgresql.md) - native `UUID` + `JSONB`, real schemas, partial indexes — the smoothest fit
- [MariaDB setup](docs/database/mariadb.md) - native `UUID` (10.7+), `JSON`, `DATETIME(6)`, `eventlog` as a separate database
- [MySQL setup](docs/database/mysql.md) - `CHAR(36)` UUIDs via `UuidStringConverter`, cross-database GRANT for `eventlog`
- [JOOQ codegen](docs/database/jooq-codegen.md) - what codegen generates, the seven framework converters, per-dialect modeling rationale (plugin syntax: [Gradle](docs/gradle/jooq-codegen.md) · [Maven](docs/maven/jooq-codegen.md))

### Jobs
- [Distributed background jobs](docs/jobs/distributed-jobs.md) - `@EkbatanDistributedJob`, db-scheduler-backed cluster exclusivity

### Events out
- [Listen-to-yourself: in-process event handlers](docs/events/local-event-handler.md) - `@EkbatanEventHandler`, fan-out + dispatch jobs, retry & expiry, idempotency
- [Streaming via Debezium → Kafka](docs/events/event-streaming.md) - JSON / Avro / Protobuf SMTs, the router, topic naming

### Observability & native
- [OpenTelemetry tracing](docs/runtime/observability.md) - span hierarchy, attributes, retry events
- [GraalVM native-image](docs/runtime/native-image.md) - auto-loading Features, scan-package overrides, framework-specific notes

---

## Where Ekbatan fits

Ekbatan is **not** a replacement for Spring, Quarkus, or Micronaut. HTTP, dependency injection, configuration, security - those concerns remain with the host framework.

Ekbatan **is** a replacement for the persistence layer typically built with **Spring Data, Hibernate, JPA, MyBatis, or hand-rolled JDBC + transaction management**. It is intended for applications that need:

- writes to a relational database with strong transactional guarantees,
- a reliable audit trail of business changes,
- propagation of changes to downstream consumers - via Kafka (Debezium SMT pipeline) or in-process (fan-out + handling jobs) - without dual-write coordination.

---

## Non-goals

- **Nested or composable actions.** The action boundary *is* the transaction boundary; cross-action orchestration belongs above the framework.
- **Saga orchestration.** Cross-service workflows are the responsibility of the layer above this framework. Ekbatan can still be used to build [saga-style workflows](docs/concepts/sagas.md) from committed actions and outbox events.
- **Reactive runtime.** Concurrency is handled by Java 25 virtual threads.
- **Bridging to Spring's `@Transactional` / `PlatformTransactionManager`.** Ekbatan owns its own `TransactionManager`. Code outside an Action that needs database transactions should use the host framework's facilities directly on its own datasource.

---

## Stack & requirements

- **Java 25** - required (uses `ScopedValue`, records, recent language features). The Kafka Connect SMT plugins target Java 21 to match the Connect runtime; everything else targets 25.
- **JOOQ 3.20** - type-safe SQL.
- **HikariCP 7** - connection pooling.
- **PostgreSQL, MySQL, or MariaDB** - dialect differences handled internally.
- *(Optional)* **OpenTelemetry SDK** - for tracing.
- *(Optional)* **Debezium + Kafka Connect** - for event streaming.
- *(Optional)* **Redis (Redisson)** - for the distributed `KeyedLockProvider`.

---

## Examples & runnable references

Two directories, two audiences. Read this section if you want to copy code into your own app.

### [`ekbatan-examples/`](./ekbatan-examples) — start here

**Standalone runnable applications** that consume Ekbatan from Maven Central, exactly the way you would in your own project. Each subdirectory is its own Gradle/Maven project with its own wrapper; clone, `cd`, build, run. The layout is a uniform **3 stacks × 2 build tools × 3 dialects** grid — every combination of (Spring Boot / Quarkus / Micronaut) × (Gradle / Maven) × (PostgreSQL / MariaDB / MySQL), each with a JVM and a GraalVM-native variant — plus a handful of specialized examples for sharding, sagas, and a no-HTTP job worker.

| Pattern | Spring Boot wallet | Quarkus wallet | Micronaut wallet |
|---|---|---|---|
| Flyway | [`ekbatan-flyway`](./docs/wiring/spring.md#flyway--use-ekbatan-flyway--spring-boots-flyway-dependencies) + an `EkbatanShardFlywayMigrator` bean over every shard | [`ekbatan-flyway`](./docs/wiring/quarkus.md#flyway--use-ekbatan-flyway--a-startup-migrator) + a `StartupEvent` migrator over every shard | [`ekbatan-flyway`](./docs/wiring/micronaut.md#flyway--programmatic-context-bean) + an eager `@Context` migrator over every shard |
| Driver settings | [`dataSourceProperties`](./docs/database/connecting.md) on every `primaryConfig` in `application.yml`, read back from each shard's live connection | the same in `application.properties` | the same in `application.yml` |
| HTTP serialization | `spring-boot-starter-web` (Jackson via auto-config) | `quarkus-rest-jackson` (pulls `quarkus-jackson`) | [`micronaut-serde-jackson`](./docs/wiring/micronaut.md#serialization--use-micronaut-serde-jackson-not-micronaut-jackson-databind) + `@Serdeable` (compile-time serdes) |
| Native-image | `nativeTest` (Spring AOT + GraalVM Build Tools) | `testNative` (Quarkus native pipeline) | `nativeTest` (Micronaut + GraalVM Build Tools) |

Per-stack starting points (every one has 6 sibling DB / build-tool variants — see [`ekbatan-examples/README.md`](./ekbatan-examples/README.md) for the full grid):

| Example | What it demonstrates |
|---|---|
| [`spring-boot-wallet-rest-gradle-pg`](./ekbatan-examples/spring-boot-wallet-rest-gradle-pg) | Spring Boot wallet — REST + 4 Actions + listen-to-yourself handler + caller-side `KeyedLockProvider` + Testcontainers test, using `spring-boot-starter-flyway` |
| [`quarkus-wallet-rest-gradle-pg`](./ekbatan-examples/quarkus-wallet-rest-gradle-pg) | Quarkus wallet — same surface, using `quarkus-flyway` + a CDI `FlywayConfigurationCustomizer` |
| [`micronaut-wallet-rest-gradle-pg`](./ekbatan-examples/micronaut-wallet-rest-gradle-pg) | Micronaut wallet — same surface, using `micronaut-flyway` + a `@Named("default")` customizer and `micronaut-serde-jackson` |
| [`spring-boot-wallet-rest-gradle-sharded-pg`](./ekbatan-examples/spring-boot-wallet-rest-gradle-sharded-pg) | Multi-shard Spring Boot wallet — 2 Postgres instances, `ShardedUUID`, and `WalletTransferAction` as an `allowCrossShard(true)` mechanics demo with one independent transaction per shard; use the saga example for production transfer workflows |
| [`spring-boot-wallet-rest-gradle-native-sharded-pg`](./ekbatan-examples/spring-boot-wallet-rest-gradle-native-sharded-pg) | Native-image sibling of the multi-shard Spring Boot wallet — same two-shard runtime path, plus `ekbatan-native`, native migration resources, and `nativeTest` coverage |
| [`spring-boot-wallet-saga-gradle-pg`](./ekbatan-examples/spring-boot-wallet-saga-gradle-pg) | Saga pattern — `InitiateTransferAction` → `CompleteTransferAction` → `RefundTransferAction` chained by `@EkbatanEventHandler`s, forward-only compensation |
| [`spring-boot-job-worker-gradle-pg`](./ekbatan-examples/spring-boot-job-worker-gradle-pg) | `@EkbatanDistributedJob` as the primary feature — no HTTP, `spring.main.web-application-type=none`, two jobs running end-to-end |

**Use these as the template when wiring Ekbatan into your own project.** They show the canonical framework-native dep choices (`quarkus-flyway`, `spring-boot-starter-flyway`, or `ekbatan-flyway` when you need a programmatic one-or-many-shard migrator) and the hooks each framework uses to feed connection coordinates from `ekbatan.sharding.*` into Flyway.

### [`ekbatan-integration-tests/`](./ekbatan-integration-tests) — framework's own smoke tests

These are **not** examples in the "copy me" sense — they're the framework's own integration test suite, exercising `ekbatan-core`, `ekbatan-events:local-event-handler`, `ekbatan-distributed-jobs`, the four `KeyedLockProvider` backends, and the three Debezium SMT serializers directly. Each runs against real Testcontainers and produces real coverage; together they're the green-light check before a release. They deliberately **do not** use a DI framework (except where the test target is the DI integration itself), and they run Flyway programmatically via `FlywayMigrator.migrate(url, user, pass)` - which connects exactly as `migrate(dataSourceConfig)` does — see [Flyway on native](./docs/runtime/native-image.md#flyway-on-native) for why that's right for these tests and usually not the first choice for framework apps.

| Subproject | What it covers |
|---|---|
| `postgres-simple/` | Single-database wallet — the smallest end-to-end action+repository+executor smoke test |
| `postgres-sharded/` | The same wallet sharded across multiple Postgres instances, with cross-shard tests |
| `core-repo/{pg,mysql,mariadb}/` | Repository CRUD coverage across all three supported databases (81 tests per dialect) |
| `keyed-lock-provider/{pg,mariadb,mysql,redis}/` | `KeyedLockProvider` implementations and reentrancy/timeout coverage |
| `distributed-jobs-pg/` | `JobRegistry` + `DistributedJob` cluster-exclusive scheduling |
| `event-pipeline/` | End-to-end Debezium → Kafka pipeline with JSON, Avro (SMT), and Protobuf (SMT) variants |
| `local-event-handler/{shared,pg,mariadb,mysql}/` | In-process consumer (fan-out + handling jobs) on PG / MariaDB / MySQL |
| `di/{spring-boot-starter,quarkus,micronaut}/` | DI integration smoke tests for the framework's auto-config / extension / visitor |

Every test module also has a `nativeTest` task (Gradle) — the full 402-test suite passes against the compiled native binary, validating the GraalVM substitutions, reachability metadata, and `FlywayMigrator`'s native-image classpath migration path end-to-end.

---

## Building & testing

```bash
./gradlew build           # full build (includes spotlessApply)
./gradlew test            # all tests
./gradlew spotlessApply   # format
```

Tests use TestContainers and require Docker to be running.

---

## Documentation

- **[docs/](docs/README.md)** - capability deep-dives (linked from *Capabilities* above)
- **[AGENTS.md](AGENTS.md)** - full architecture, conventions, and contributor guide
