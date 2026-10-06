# Fix the defects found while porting to Python

## Why

Porting the framework to Python file by file (`ekbatan-py`, `docs/JAVA-PARITY-LEDGER.md`) means
reading every Java source in full and then making a second implementation agree with it. That is a
different lens from an audit: it does not look for bugs, it looks for *disagreement*, and the
disagreements it surfaces are the ones where the Java behaviour and the Java documentation are not
the same thing.

Three such disagreements came out of it. Two were verified by running - one by compiling the real
Java classes, one by timing real MySQL and MariaDB containers - and one by reading. None of them
overlap `fix-audited-source-defects`, which is still open. A fourth candidate, `Model.equals`
ignoring subclass fields, turned out to be deliberate and is recorded as such in `design.md`
rather than filed.

The most consequential is a missing version guard in `EmbeddedBitsShardingStrategy`: a
pre-sharding `UUID` wrapped in a `ShardedId` decodes to a well-formed and meaningless shard
coordinate. It has moved to its own change, `guard-sharded-id-decode`, to be investigated - including
whether deployed data is already affected - before it is fixed.

## What Changes

- Correct the `MySQLKeyedLockProvider` javadoc: a sub-second `maxWait` is truncated to zero on
  MySQL, not rounded up. Consider whether the behaviour should change as well as the text.
- Refuse a null collection in `ActionPlan.addAll` / `updateAll` rather than treating it as "staged
  nothing".

## Impact

- `ekbatan-core`: `ActionPlan`, `MySQLKeyedLockProvider`.
- No public API changes are required by findings 1 or 2. Finding 3 changes behaviour a caller
  could depend on and is pinned by an existing test, so it is proposed as a decision.
- The Python port already behaves as this proposal asks in all three places, so the conformance
  suite will agree with the Java side afterwards rather than needing per-port carve-outs.
