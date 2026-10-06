# Design

## Method

These were not found by looking for bugs. They were found by porting the framework to Python file
by file - reading each Java source in full, writing the Python that has to agree with it, and then
running both. The lens is *disagreement*, and what it surfaces is places where the Java behaviour
and the Java documentation are not the same thing, or where two branches of one method do not
treat the same input alike.

Where a finding says "measured", it was produced by running: either by compiling the real Java
classes and printing what they return, or by timing real database containers. Where it says
"read", it comes from the source and has not been reproduced.

Findings are ordered by consequence, not by discovery.

---

## Finding 1: `EmbeddedBitsShardingStrategy` decodes shard bits it has not checked

Moved to its own change, `openspec/changes/guard-sharded-id-decode`, with the evidence, the paths
that reach it, and what to find out - including whether deployed data is affected - before it is
fixed.

---

## Finding 2: the `MySQLKeyedLockProvider` rounding note is inverted below one second

**Severity: documentation, misleading about real behaviour.** Measured.

The javadoc says:

> although `GET_LOCK` accepts a `DOUBLE` timeout, it rounds up to the next whole second
> internally (verified ...). Sub-second `maxWait` values are passed through to `GET_LOCK` as-is,
> but the actual wait will be at least ceil(maxWait) seconds.

That is true above one second and inverted below it. Holding the lock in one session and timing a
second session's `GET_LOCK` against the containers the port's suite runs:

| timeout asked | MySQL 8.4.11 | MariaDB 11.4.13 |
| --- | --- | --- |
| 0.25s | **0.00s** | 0.26s |
| 0.5s  | **0.00s** | 0.51s |
| 1s    | 1.01s | 1.01s |
| 1.5s  | **2.01s** | 1.51s |

So MySQL truncates a sub-second timeout to zero and rounds a fractional one above a second up;
MariaDB honours the fraction exactly. The two servers have diverged, which the javadoc says
elsewhere - but not in the direction the note claims.

The consequence is not cosmetic. A caller reading this expects `tryAcquire(key, 500ms, hold)` to
wait about a second on MySQL. It does not wait at all: it is a poll. Anyone using a short
`maxWait` as a bounded, best-effort attempt gets a different thing than the documentation
describes.

### Suggested fix

Correct the text, with the table. Then decide whether the behaviour should change: rounding up to
one second inside the provider would make a 50ms wait take a second, which is a surprise in the
other direction, and polling in a loop trades a bounded imprecision for load on the database. The
Python port passes the value through unchanged, as this does, and documents the measurement.

---

## Finding 3: `ActionPlan.addAll(null)` reports success

**Severity: low.** Read.

```java
public <ID extends Comparable<ID>> Collection<? extends Persistable<ID>> addAll(
        Collection<? extends Persistable<ID>> entities) {
    if (entities == null || entities.isEmpty()) {
        return Collections.emptyList();
    }
```

`updateAll` is the same. An empty collection returning empty is right. A *null* collection is a
caller bug, and returning empty lets it through to become "this unit of work staged nothing" -
which is a legal outcome that commits a sentinel row and looks like success. `ActionPlanTest` pins
the current behaviour in `addAll_with_null_returns_empty`, so changing it is a deliberate act.

### Suggested fix

`Validate.notNull(entities, "entities cannot be null")`, and update the two tests. Cheap, and it
turns a silent no-op into a stack trace at the line that made the mistake.

---

## Examined and deliberately not filed

**`Model.equals` ignoring subclass fields.** `Model.equals` compares `id`, `state`, `version`,
`createdDate` and `updatedDate`; `hashCode` is `id.hashCode()` alone. Fields declared by the
concrete model - a wallet's balance and currency - take no part in either, so two `Wallet`
instances with the same id and version are `equals` even when their balances differ.
`Entity.equals` has the same shape over its three fields.

This was raised during the port and is **deliberate**: equality means "the same row at the same
version", not "the same values". Recorded here so it is not refiled as an oversight - which is
exactly how it reads on a first pass, and how this document read it before asking.

The Python port compares every field, because a frozen dataclass does. That divergence is recorded
in `ekbatan-py/DIVERGENCE.md` under "Equality compares the whole row" and is not a defect on
either side.

**Within-action event ordering.** `eventlog.events` has no ordinal column, `EventEntityRepository`
orders by `EVENT_DATE`, and every row of one action carries that action's completion instant - so
rows of a single action have no defined order, and a consumer rebuilding state from the stream can
see a deposit before the withdrawal that paid for it on any database that does not happen to
return insertion order.

This was raised during the port and is **not** filed as a defect: `event_date` is the ordering key
a consumer is meant to use, and the rows of one action are genuinely simultaneous because the
action committed once. `BaseSingleTableJsonEventPersisterTest` asserts set-wise for the same
reason. Recorded here so it is not rediscovered and refiled.
