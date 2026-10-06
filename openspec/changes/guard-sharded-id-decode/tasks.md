# Tasks

## 1. Investigate (before any change)

- [ ] 1.1 Reproduce: a test that resolves a `ShardedId` wrapping a version 4 UUID and shows the
      decoded coordinate, where the same UUID as a plain `Id` resolves to `Optional.empty()`.
- [ ] 1.2 Reproduce both outcomes in `design.md` against a sharded database: an insert with an
      unsharded `ShardedId`, and an update of a pre-sharding row read back as a `ShardedId`.
- [ ] 1.3 List every path that builds a `ShardedId` from an existing UUID: framework code, docs,
      examples, and the patterns applications are told to copy.
- [ ] 1.4 Write the query that finds rows in a non-default shard whose ids are not version 7, or
      whose decoded coordinate is not the shard they sit in.
- [ ] 1.5 Make decisions 1 to 3 in `design.md`.

## 2. Fix (after section 1)

- [ ] 2.1 Route the `ShardedId` branch of `resolveShardIdentifier(Persistable)` through
      `resolveShardIdentifierById(sid.getId())`.
- [ ] 2.2 Tests: a version 4 id resolves to `Optional.empty()` by both routes; a `ShardedUUID`
      still resolves to its coordinate by both.
- [ ] 2.3 Integration test on the sharded repository suite: a pre-sharding aggregate is read,
      updated and read again on the default shard.
- [ ] 2.4 Apply whatever decisions 1 to 3 settled.

## 3. Data and docs

- [ ] 3.1 Describe how to find and move rows misplaced by the old behaviour, for deployments that
      ran it.
- [ ] 3.2 Say on the sharding page what happens to ids without shard bits.
- [ ] 3.3 Correct the bit layout in `add-sharding/specs/sharding-strategy/spec.md`.
