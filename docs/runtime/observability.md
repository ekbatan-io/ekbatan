# OpenTelemetry tracing and metrics

Ekbatan instruments its action execution pipeline with the **OpenTelemetry API** (`opentelemetry-api`). The library depends only on the API — no SDK. When no OTel SDK is registered at runtime, all tracing calls are no-ops with zero overhead. Consumers bring their own `opentelemetry-sdk` and exporters.

The instrumentation scope is `io.ekbatan.core` version `1.1.2`, obtained from `GlobalOpenTelemetry.get().getTracer(...)`.

## Span hierarchy

Each `executor.execute(...)` call produces a span tree of the following shape:

```
[ekbatan.action.execute]                    ActionExecutor.execute()
├── [ekbatan.action.perform]                Action.perform()
└── [ekbatan.action.persist]                ActionExecutor.persistChanges()
    └── [ekbatan.transaction]               TransactionManager.inTransactionChecked() (per shard)
        ├── [ekbatan.repository]            AbstractRepository.addAllNoResult / updateAllNoResult
        └── [ekbatan.event.persist]         EventPersister.persistActionEvents()
```

A single action that touches one shard produces one of each span. A cross-shard action produces one transaction + nested repo + event-persist spans **per involved shard**.

## Attributes

| Attribute | Type | Span | Description |
|---|---|---|---|
| `ekbatan.action.name` | string | action.execute | Simple class name of the action |
| `ekbatan.action.principal` | string | action.execute | Principal name |
| `ekbatan.action.outcome` | string | action.execute | `"success"` or `"error"` |
| `ekbatan.action.retry.count` | long | action.execute | Total retries (0 if none) |
| `ekbatan.shard.cross_shard` | boolean | action.persist | Present (and `true`) when changes span multiple shards |
| `ekbatan.shard.group` | long | transaction | Shard group identifier |
| `ekbatan.shard.member` | long | transaction | Shard member identifier |
| `db.operation.name` | string | repository | `"BATCH_INSERT"` or `"BATCH_UPDATE"` — set by `addAllNoResult` / `updateAllNoResult`, which are the only repository methods the executor calls. |
| `ekbatan.entity.type` | string | repository | Simple class name of the domain object |
| `ekbatan.batch.size` | long | repository | Number of records in the batch |
| `ekbatan.event.count` | long | event.persist | Number of model events persisted |

`db.operation.name` uses the OTel semantic-convention *key*, but its values are Ekbatan-specific (`BATCH_INSERT` / `BATCH_UPDATE`) because the executor always writes through the batch methods. All other attributes use the `ekbatan.*` namespace.

## Retry events

Each retry attempt adds a span event named `"retry"` to the action span with attributes `retry.count` (int) and `retry.exception` (the matched retryable exception class name, which may come from the cause chain). Retries are **not** their own spans — each retry re-executes `perform` + `persist`, so the child spans of each attempt already appear under the action span; a separate retry span would just add a redundant level.

## Errors

On failure, spans are marked with `StatusCode.ERROR` and the exception is recorded via `span.recordException()`.

## Context propagation

Since actions execute single-threaded (ScopedValue-based transactions), context flows naturally via `Span.makeCurrent()` / `Scope`. No async context passing is needed. Each `TransactionManager` instance knows its own `ShardIdentifier` (set at construction time, defaults to `ShardIdentifier.DEFAULT`), so `inTransactionChecked()` automatically sets shard attributes on the transaction span without requiring the shard to be passed per-call.

## Metrics

Metrics come from the **local event handler** only. `ekbatan-core` emits spans but no metrics — action execution is traced, not counted. The instrumentation scope is `io.ekbatan.events.localeventhandler`, and as with tracing, every instrument is a no-op until an SDK is registered.

| Instrument | Type | Unit | Tags | Emitted by |
|---|---|---|---|---|
| `ekbatan.events.handled` | counter | `{notification}` | `outcome`, `handler` | `EventHandlingJob` |
| `ekbatan.events.handler.duration` | histogram | `s` | `handler`, `outcome` | `EventHandlingJob` |
| `ekbatan.events.delivery.lag` | histogram | `s` | `handler` | `EventHandlingJob` |
| `ekbatan.events.fanned_out` | counter | `{event}` | — | `EventFanoutJob` |
| `ekbatan.events.notifications_created` | counter | `{notification}` | — | `EventFanoutJob` |

### What each one answers

- **`ekbatan.events.handled`** — throughput and failure rate. The `handler` tag is what lets you tell one broken handler from a general problem: without it, a spike in `failed_retry` could be any of your handlers. Sum across `handler` to get the per-outcome totals.
- **`ekbatan.events.handler.duration`** — which handler is *slow*, as opposed to which is failing. A handler degrading from 5ms to 5s shows up here long before it starts throwing.
- **`ekbatan.events.delivery.lag`** — how far behind delivery is running. This is the instrument to alert on: a shard that is falling behind still reports perfectly healthy success counts, so no counter can reveal a growing backlog. Recorded on success only, so it measures time-to-delivery rather than time-spent-failing.
- **`ekbatan.events.fanned_out` / `notifications_created`** — fan-out throughput. One event produces one `fanned_out` and one `notifications_created` per subscribed handler.

### `outcome` values

`ekbatan.events.handled` carries one of four:

| Value | Meaning |
|---|---|
| `succeeded` | Handler returned normally |
| `failed_retry` | Handler threw; a retry was scheduled with backoff |
| `expired_preflight` | Row was already past `retentionWindow` when the batch was claimed — the handler was never invoked |
| `expired_postfailure` | Handler threw, and the proposed retry would land past the deadline |

`ekbatan.events.handler.duration` uses a deliberately narrower `outcome`: just `succeeded` or `failed`. At the moment a handler returns, the only known fact is whether it threw — whether that failure becomes a retry or an expiry is decided afterwards, against the post-invocation clock. `expired_preflight` never appears in the histogram at all, because no handler ran.

### What to alert on

Two of these are worth a rule rather than a dashboard panel.

**`ekbatan.events.delivery.lag`** rising steadily is the one that matters most. A shard falling behind still reports healthy `handled{outcome="succeeded"}` counts, so no counter can tell you delivery is drifting - only the lag can.

**`ekbatan.events.handled{outcome="expired_postfailure"}` above zero means events were discarded.** A notification only reaches this state after failing repeatedly for the whole `retentionWindow` (7 days by default), so any occurrence is a delivery that was permanently given up on. Each one is also logged at ERROR with the handler name and an example event id.

A steady stream of `handled{outcome="failed_retry"}` concentrated on a **single** `handler` value usually means that handler cannot succeed at all - the classic case being a `NoClassDefFoundError` from a missing optional dependency, which no amount of retrying will fix. Alerting on that catches the problem within minutes, whereas the discard alert above only fires a week later, once the events are already gone.

### Known caveat

`ekbatan.events.fanned_out` currently counts rows **read** from the read replica, not rows written to the primary. On a deployment with a real replica and non-zero replication lag, a round can re-read events it has already delivered, so the counter over-reports. Treat it as an upper bound until that is fixed.

## Which shard a migration log line is about

Flyway names only the schema in what it logs - `Migrating schema "public" to version "2"` - and
every PostgreSQL shard is usually `public`. While `FlywayMigrator` migrates each target it puts the
shard and the target's name in the SLF4J MDC, under `ekbatanShard` (`1:0`) and `ekbatanTarget`
(`mexico/member-0`), so every line Flyway writes for that target carries both wherever your log
format or encoder includes MDC values (`%X{ekbatanShard}` in a Logback pattern; JSON encoders
usually include the MDC by default). It also logs one line before and after each target:

```
INFO  i.e.flyway.FlywayMigrator - Migrating mexico/member-0 (1:0)
INFO  i.e.flyway.FlywayMigrator - mexico/member-0 (1:0) is at version 4 (2 applied)
```

Neither the lines nor the MDC values ever contain a URL or a credential - only the shard and the
name. Values an application already had under those two keys are put back afterwards.

## Bringing your own SDK

The framework only declares the `opentelemetry-api` dependency. To actually export traces, your application brings the SDK and exporters:

```kotlin
implementation("io.opentelemetry:opentelemetry-sdk")
implementation("io.opentelemetry:opentelemetry-exporter-otlp")
implementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")
```

Then configure via standard OTel system properties or environment variables — `otel.exporter.otlp.endpoint`, `otel.service.name`, etc. The framework picks up whatever `GlobalOpenTelemetry` returns, for both traces and metrics.

In tests, register an in-memory exporter via `opentelemetry-sdk-testing` to assert on emitted spans:

```kotlin
testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
```

The `:ekbatan-core:tracingTest` Gradle task forks a separate JVM specifically because the OTel SDK must be registered before any instrumented class loads its static `Tracer` field via `GlobalOpenTelemetry`.

## Read-path tracing is not instrumented

`AbstractRepository`'s read methods (`findById`, `findAllWhere`, `count`, etc.) don't produce their own spans. They run inside `Action.perform()` which already has a span, and instrumenting every read would be noisy for actions that do many lookups. If you need fine-grained read tracing, instrument those calls in your repository subclass.

## See also

- [Actions, ActionPlan, ActionExecutor](../concepts/actions.md) — the source of `action.execute` / `action.perform` / `action.persist`
- [Sharding](../database/sharding.md) — `ekbatan.shard.*` attributes come from the per-shard `TransactionManager`
- [Repositories on JOOQ](../database/repositories.md) — what the `ekbatan.repository` span wraps
