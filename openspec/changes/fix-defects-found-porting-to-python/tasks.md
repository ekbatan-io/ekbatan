# Tasks

Ordered by consequence. Every item cites its finding in `design.md`.

## 1. Guard the shard decode (design.md finding 1)

Moved to `openspec/changes/guard-sharded-id-decode`.

## 2. Correct the GET_LOCK timeout note (design.md finding 2)

Left as is (decided 2026-10-05): the behaviour is MySQL's, and the framework passes the wait
through unchanged. The javadoc still says the wait is rounded up; the measured table is in
`design.md` if the note is corrected later.

## 3. Refuse a null collection on the plan (design.md finding 3)

- [x] 3.1 `Validate.notNull` on `ActionPlan.addAll` and `updateAll` - and on `add` and `update`.
- [x] 3.2 Update `ActionPlanTest.addAll_with_null_returns_empty` and
      `updateAll_with_null_returns_empty` to assert the refusal.

## 4. Close the loop

- [ ] 4.1 Re-run the conformance suite against both ports; all four findings are places where
      `ekbatan-py` already behaves as this proposes, so the two should agree afterwards without
      per-port carve-outs.
- [ ] 4.2 Remove the corresponding rows from the "Noticed in the Java while porting" table in
      `ekbatan-py/docs/JAVA-PARITY-LEDGER.md`.
