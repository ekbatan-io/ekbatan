# Fix the defects found while porting to Python

## Why

Porting the framework to Python file by file (`ekbatan-py`, `docs/JAVA-PARITY-LEDGER.md`) means
reading every Java source in full and then making a second implementation agree with it. That is a
different lens from an audit: it does not look for bugs, it looks for *disagreement*, and the
disagreements it surfaces are the ones where the Java behaviour and the Java documentation are not
the same thing.

Four such disagreements came out of it. Two were verified by running - one by compiling the real
Java classes, one by timing real MySQL and MariaDB containers - and two by reading. None of them
overlap `fix-audited-source-defects`, which is still open.

The most consequential is a missing version guard in `EmbeddedBitsShardingStrategy`: a
pre-sharding `UUID` wrapped in a `ShardedId` decodes to a well-formed and meaningless shard
coordinate, silently, and the row is then written to the wrong database and read back from it.

## What Changes

- Guard the `ShardedId` branch of `EmbeddedBitsShardingStrategy.resolveShardIdentifier` with the
  same `version() != 7` check the `Id` branch already has, so an id that carries no shard falls
  back to the default rather than naming a random one.
- Correct the `MySQLKeyedLockProvider` javadoc: a sub-second `maxWait` is truncated to zero on
  MySQL, not rounded up. Consider whether the behaviour should change as well as the text.
- Decide whether `Model.equals` excluding subclass fields is intended, and document it either way.
- Refuse a null collection in `ActionPlan.addAll` / `updateAll` rather than treating it as "staged
  nothing".

## Impact

- `ekbatan-core`: `EmbeddedBitsShardingStrategy`, `Model`, `ActionPlan`, `MySQLKeyedLockProvider`.
- No public API changes are required by findings 1 or 2. Findings 3 and 4 change behaviour that
  callers may currently depend on, so both are proposed as decisions rather than fixes.
- The Python port already behaves as this proposal asks in all four places, so the conformance
  suite will agree with the Java side afterwards rather than needing per-port carve-outs.
