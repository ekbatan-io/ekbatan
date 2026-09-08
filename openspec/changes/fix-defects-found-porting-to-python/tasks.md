# Tasks

Ordered by consequence. Every item cites its finding in `design.md`.

## 1. Guard the shard decode (design.md finding 1)

- [ ] 1.1 Route the `ShardedId` branch of
      `EmbeddedBitsShardingStrategy.resolveShardIdentifier(Persistable)` through
      `resolveShardIdentifierById(sid.getId())`, so the existing `version() != 7` check covers
      both branches rather than one.
- [ ] 1.2 Test: a `ShardedId` built from a version 4 UUID via `ShardedUUID.from(...)` resolves to
      `Optional.empty()`. Fails against the current code, which returns a legal-looking
      coordinate - see the measured output in `design.md`.
- [ ] 1.3 Decide whether `ShardedUUID.from(UUID)` should validate at construction instead of, or
      as well as, at resolution. Its javadoc currently promises it does not, and
      `ShardedId.of(Class, ShardedUUID)` is the only caller inside the framework; validating there
      would move the failure to the line that made the mistake.
- [ ] 1.4 Integration test on the sharded repository suite: an aggregate whose id predates
      sharding routes to the default shard rather than to a decoded one.

## 2. Correct the GET_LOCK timeout note (design.md finding 2)

- [ ] 2.1 Replace the "rounds up to the next whole second" paragraph in
      `MySQLKeyedLockProvider`'s javadoc with the measured table: MySQL 8.4.11 truncates below a
      second and rounds up above it; MariaDB 11.4 honours the fraction.
- [ ] 2.2 Check whether `MariaDBKeyedLockProvider` repeats the claim, and correct it there too.
- [ ] 2.3 Decide whether the behaviour changes as well as the text. Passing the value through is
      the current choice in both ports; rounding up inside the provider would make a 50ms wait
      take a second, and polling would trade a bounded imprecision for database load.
- [ ] 2.4 Test: with the lock held, `tryAcquire(key, 500ms, hold)` returns empty in well under a
      second on MySQL and in about half a second on MariaDB. Pins the divergence so a future
      server version changing it is visible.

## 3. Decide what Model equality means (design.md finding 3)

- [ ] 3.1 Decide: does `equals` mean "the same row at the same version", or "the same values"?
- [ ] 3.2 If the former, say so on `Model.equals` and `Entity.equals` - the current javadoc
      describes which fields are compared but not why, and a reader assumes the omission is an
      oversight.
- [ ] 3.3 If the latter, include subclass state in both `equals` and `hashCode`, and expect
      `ModelTest.hashCode_based_on_id` to need rewriting.

## 4. Refuse a null collection on the plan (design.md finding 4)

- [ ] 4.1 `Validate.notNull` on `ActionPlan.addAll` and `updateAll`.
- [ ] 4.2 Update `ActionPlanTest.addAll_with_null_returns_empty` and
      `updateAll_with_null_returns_empty` to assert the refusal.

## 5. Close the loop

- [ ] 5.1 Re-run the conformance suite against both ports; all four findings are places where
      `ekbatan-py` already behaves as this proposes, so the two should agree afterwards without
      per-port carve-outs.
- [ ] 5.2 Remove the corresponding rows from the "Noticed in the Java while porting" table in
      `ekbatan-py/docs/JAVA-PARITY-LEDGER.md`.
