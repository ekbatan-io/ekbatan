# Sharded id routing

## ADDED Requirements

### Requirement: An id without shard bits routes to the default shard by every route

`EmbeddedBitsShardingStrategy` SHALL resolve a shard only from a UUID that carries shard bits. An
id without them SHALL resolve to `Optional.empty()` - the default shard - whether it reaches the
strategy as a plain `Id`, as a `ShardedId`, or as a bare UUID.

#### Scenario: A pre-sharding id wrapped in a ShardedId

- **GIVEN** a version 4 UUID wrapped with `ShardedId.of(Wallet.class, ShardedUUID.from(uuid))`
- **WHEN** `resolveShardIdentifier(persistable)` is called for a persistable with that id
- **THEN** it SHALL return `Optional.empty()`

#### Scenario: The same id as a plain Id

- **GIVEN** the same version 4 UUID as a plain `Id`
- **WHEN** `resolveShardIdentifier(persistable)` is called
- **THEN** it SHALL return `Optional.empty()`, as it does today

#### Scenario: A sharded id still resolves to its coordinate

- **GIVEN** an id made with `ShardedId.generate(Wallet.class, ShardIdentifier.of(2, 5))`
- **WHEN** `resolveShardIdentifier(persistable)` is called
- **THEN** it SHALL return `Optional.of(ShardIdentifier.of(2, 5))`
