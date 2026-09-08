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

**Severity: correctness, silent, low probability.** Measured.

`resolveShardIdentifier(Persistable)` has two branches and only one of them is guarded:

```java
if (id instanceof ShardedId<?> sid) {
    return Optional.of(sid.resolveShardIdentifier());        // no version check
}
if (id instanceof Id<?> regularId) {
    return resolveShardIdentifierById(regularId.getValue()); // checks version() != 7
}
```

`resolveShardIdentifierById` refuses anything that is not a version 7 UUID and returns
`Optional.empty()`, which the caller reads as "use the default shard". The `ShardedId` branch goes
straight to `ShardedUUID.resolveShardIdentifier()`, which reads the bits at the reserved offsets
without asking whether they mean anything.

Reachable because `ShardedUUID.from(UUID)` is public and its own javadoc invites exactly this:

> Wraps an arbitrary UUID (typically read back from storage) without re-checking that the shard
> bits are valid.

So an aggregate whose ids predate sharding - version 4, minted before the layout existed, read
back from its own table - produces a `ShardedId` that decodes to a coordinate with no meaning.

### Evidence

Compiling `ShardedUUID`, `ShardIdentifier`, `ShardAwareId` and `Validate` unmodified and running
five random version 4 UUIDs through `ShardedUUID.from(...).resolveShardIdentifier()`:

```
v4 58869e62-d68a-4fa5-9455-8045fca9f3ce  version=4  ->  ShardIdentifier(81, 21)
v4 73e6b6c8-988c-4363-8685-d621828f3206  version=4  ->  ShardIdentifier(26, 5)
v4 cd069a41-41ef-4736-83c5-189179333bf3  version=4  ->  ShardIdentifier(15, 5)
v4 dfe191e1-e076-45d1-9e44-020e76df4562  version=4  ->  ShardIdentifier(121, 4)
v4 3a239756-bdaf-431e-b2a4-ae14f9b74d13  version=4  ->  ShardIdentifier(202, 36)

nil uuid -> ShardIdentifier(0, 0)
```

No exception. `ShardIdentifier.of` cannot reject these: group is read from 8 bits so it is always
0..255, and member from 6 bits so it is always 0..63. Every possible decode is a legal coordinate.

### Why it is not worse than it looks

`DatabaseRegistry.effectiveShard` falls back to the default for any coordinate that is not
registered, so on a small deployment the garbage lands on nothing and is corrected. It bites only
when the decoded coordinate happens to be a *registered* shard - roughly 8 in 16,384 per id for an
eight-shard layout.

### Why it is not better than it looks

When it does bite it is silent and self-consistent. The row is written to the wrong database, and
every later read by the same id decodes the same wrong coordinate and finds it there. Nothing
fails. It surfaces as a scatter-gather that disagrees with a direct read, or as a shard migration
that leaves rows behind - a long way from the id that caused it.

### Suggested fix

One line: make the `ShardedId` branch go through the same check.

```java
if (id instanceof ShardedId<?> sid) {
    return resolveShardIdentifierById(sid.getId());
}
```

`ShardedId.getId()` already returns the underlying `UUID`, so this reuses the guard rather than
duplicating it. A test in `ShardIdentifierTest` or `EmbeddedBitsShardingStrategyTest` asserting
that a version 4 id resolves to `Optional.empty()` would have caught it.

### What the Python port does

`ShardedId.shard` raises with a message naming the version, and `EmbeddedBitsShardingStrategy`
returns `None` - which means "use the default". Refused or safely defaulted, never a wrong answer.

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

## Finding 3: `Model.equals` ignores subclass fields

**Severity: latent surprise.** Read.

`Model.equals` compares `id`, `state`, `version`, `createdDate` and `updatedDate`; `hashCode` is
`id.hashCode()` alone. Fields declared by the concrete model - a wallet's balance and currency -
take no part in either.

So two `Wallet` instances with the same id, state, version and timestamps are `equals` even when
their balances differ. In practice the version moves whenever the content does, so a pair produced
by the framework's own read and write paths will differ on version too. Where it shows is a test:

```java
assertThat(loadedWallet).isEqualTo(expectedWallet);   // passes with a different balance
```

`Entity.equals` has the same shape over its three fields.

### The decision to make

Either this is intended - equality means "the same row at the same version", and the javadoc
should say so, in which case a note on `equals` is the whole fix - or it is not, and both should
include subclass state. It is not obviously a bug and should not be changed without deciding
which.

The Python port compares every field, because a frozen dataclass does; the divergence is recorded
in `ekbatan-py/DIVERGENCE.md` under "Equality compares the whole row".

---

## Finding 4: `ActionPlan.addAll(null)` reports success

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

**Within-action event ordering.** `eventlog.events` has no ordinal column, `EventEntityRepository`
orders by `EVENT_DATE`, and every row of one action carries that action's completion instant - so
rows of a single action have no defined order, and a consumer rebuilding state from the stream can
see a deposit before the withdrawal that paid for it on any database that does not happen to
return insertion order.

This was raised during the port and is **not** filed as a defect: `event_date` is the ordering key
a consumer is meant to use, and the rows of one action are genuinely simultaneous because the
action committed once. `BaseSingleTableJsonEventPersisterTest` asserts set-wise for the same
reason. Recorded here so it is not rediscovered and refiled.
