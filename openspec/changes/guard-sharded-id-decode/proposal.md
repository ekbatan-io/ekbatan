# Guard the shard decode of a ShardedId

**Status: parked for investigation.** Nothing here is to be built until the investigation in
`tasks.md` section 1 is done and its decisions are made.

## Why

`EmbeddedBitsShardingStrategy` picks the database for a row from shard bits embedded in its id.
Only ids made by `ShardedUUID.generate` carry those bits. Of the strategy's two routes, only one
checks for them:

- `resolveShardIdentifierById(UUID)` refuses anything that is not a version 7 UUID and returns
  `Optional.empty()`, which means "use the default shard".
- `resolveShardIdentifier(Persistable)` with a `ShardedId` reads the bits without that check.

A `ShardedId` can wrap any UUID: `ShardedUUID.from(UUID)` is public and promises not to check. And
it is the documented way to read ids back - every sharded repository in the docs and examples
builds `ShardedId.of(Wallet.class, ShardedUUID.from(record.getId()))`, and every example controller
builds one from the id in the request. So any id without shard bits - a row written before
sharding was turned on, or an id a client sends - decodes to a well-formed, meaningless shard
coordinate.

When that coordinate is not a registered shard, the registry falls back to the default and nothing
is wrong. When it is one - about 8 ids in 16,384 for an eight-shard layout - the row is routed to
the wrong database, silently.

Found while porting the framework to Python; first written up as finding 1 of
`fix-defects-found-porting-to-python`, and moved here to be investigated on its own.

## What Changes

To be decided by the investigation. The candidate fix is one line: route the `ShardedId` branch
through `resolveShardIdentifierById(sid.getId())`, so both routes share the version check. It must
not be deployed before the data question below is answered: rows the current behaviour has already
placed in a decoded shard would become unreachable once routing changes.

## Impact

- `ekbatan-core`: `EmbeddedBitsShardingStrategy`, possibly `ShardedUUID.from`.
- Deployed data: possibly rows in the wrong shard, to find and move before the fix ships.
- Docs: the sharding page's repository pattern, and the stale bit layout in `add-sharding`.
