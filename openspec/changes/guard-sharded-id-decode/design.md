# Design

## The finding

`EmbeddedBitsShardingStrategy.resolveShardIdentifier(Persistable)`:

```java
if (id instanceof ShardedId<?> sid) {
    return Optional.of(sid.resolveShardIdentifier());        // no version check
}
if (id instanceof Id<?> regularId) {
    return resolveShardIdentifierById(regularId.getValue()); // checks version() != 7
}
```

`ShardedUUID` lays its shard out in `rand_b`: an 8-bit group and a 6-bit member. Read from any
other UUID, those 14 bits are random, and every value is a legal coordinate - group 0..255, member
0..63 - so `ShardIdentifier.of` cannot refuse it.

Measured while porting, compiling the real classes and decoding five random version 4 UUIDs:

```
v4 58869e62-d68a-4fa5-9455-8045fca9f3ce  ->  ShardIdentifier(81, 21)
v4 73e6b6c8-988c-4363-8685-d621828f3206  ->  ShardIdentifier(26, 5)
v4 cd069a41-41ef-4736-83c5-189179333bf3  ->  ShardIdentifier(15, 5)
v4 dfe191e1-e076-45d1-9e44-020e76df4562  ->  ShardIdentifier(121, 4)
v4 3a239756-bdaf-431e-b2a4-ae14f9b74d13  ->  ShardIdentifier(202, 36)
nil uuid                                 ->  ShardIdentifier(0, 0)
```

## How an id reaches the unchecked branch

- **Reading rows back.** The documented sharded repository maps a record with
  `ShardedId.of(Wallet.class, ShardedUUID.from(record.getId()))` (`docs/database/sharding.md`, the
  sharding website page, every sharded example). Any row whose id has no shard bits takes this
  path on every later update.
- **Ids from requests.** The example controllers build `ShardedId.of(Wallet.class,
  ShardedUUID.from(id))` from the path. A client can send any UUID.
- **Version 7 ids made elsewhere.** A version 7 UUID from another generator passes the version
  check, but its bits at the shard offsets are random too. The proposed check does not catch it.

## What to find out before fixing

1. **What happens today, per path.** Two outcomes are possible, and each needs a test against a
   sharded database to confirm:
   - a row *inserted* with a `ShardedId` that wraps an unsharded UUID lands in the decoded shard,
     and every later read by that id goes there too - consistent, and wrong;
   - a row written before sharding, in the default shard, is read back as a `ShardedId`; its
     updates and lookups by id are then routed to the decoded shard, where the row is not - a
     failed update or a "not found".
2. **Whether deployed data is already affected.** A row whose id is not a `ShardedUUID` but which
   sits in a non-default shard was placed there by this. Finding them needs a query per shard over
   ids whose version is not 7, or whose decoded coordinate does not match the shard they are in.
3. **What the fix does to such rows.** Once routing changes, a row in a decoded shard is looked up
   in the default one. Rows found in step 2 must move before the fix ships, or the fix must ship
   with a way to find them.

## Decisions to make

1. **Validate at construction too?** `ShardedUUID.from(UUID)` could refuse a UUID that is not
   version 7, moving the failure to the line that made the wrong id. But reading pre-sharding rows
   back would then fail, and those rows are legitimate. Leaving `from` lenient and guarding routing
   keeps old rows readable; refusing turns them into errors.
2. **Version 7 ids from other generators.** The version check cannot tell a `ShardedUUID` from any
   other version 7 UUID. Decide whether that case matters for real deployments, and if it does,
   how a `ShardedUUID` could be recognized - a marker bit, or a registry-side check that the
   decoded coordinate is a registered shard before trusting it.
3. **Client-supplied ids.** Decide whether a controller should refuse an id that carries no shard,
   rather than route it to the default shard.

## Side note

`add-sharding/specs/sharding-strategy/spec.md` describes the layout as "4-bit group + 8-bit
member". The code is 8-bit group + 6-bit member (`ShardedUUID.GROUP_BITS`, `MEMBER_BITS`). Correct
the old spec when this change is done.
