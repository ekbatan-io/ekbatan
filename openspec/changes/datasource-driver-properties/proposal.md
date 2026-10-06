## Why

`DataSourceConfig` exposes a fixed set of pool knobs (`jdbcUrl`, `username`, `password`,
`driverClassName`, `maximumPoolSize`, `minimumIdle`, `idleTimeout`, `leakDetectionThreshold`) and
nothing else. Anything the **JDBC driver** understands can only be set today by hand-appending query
parameters to the JDBC URL.

That works, but it is the worst of the available options:

- **Dialect-specific string munging.** `?socketTimeout=30&tcpKeepAlive=true` on PostgreSQL,
  `?useLocalSessionState=true&rewriteBatchedStatements=true` on MySQL. The URL becomes a
  configuration channel with no schema, no typing and no validation.
- **Awkward to template.** Deployments that build the URL from host/port/database fragments (Helm,
  Terraform, `application-*.yml` profiles) have to concatenate query strings, and every consumer
  that parses the URL has to cope with them.
- **Mixes concerns.** Secrets and tuning end up in the same opaque string.
- **Peer frameworks all expose it properly**, which sets the expectation:
  `spring.datasource.hikari.data-source-properties.*`,
  `quarkus.datasource.jdbc.additional-jdbc-properties.*`,
  `datasources.*.data-source-properties.*` on Micronaut.

Two motivations are specific to Ekbatan rather than generic:

1. **`docs/database/keyed-locks.md` already documents a known limitation whose stated mitigation is
   a driver property.** A network partition landing mid-segment parks the caller, and the docs say
   this "is not lock-specific and is left to `socketTimeout` on the lock pool's JDBC URL". Today
   that means hand-editing a URL. The lock pool is configured through `DataSourceConfig` like
   everything else, so the mitigation ought to be expressible there.
2. **Batch-rewrite settings are a direct win for this framework's write path.** `ActionExecutor`
   always writes through `addAllNoResult` / `updateAllNoResult`, so PostgreSQL's
   `reWriteBatchedInserts=true` and MySQL/MariaDB's `rewriteBatchedStatements=true` apply to
   essentially every action Ekbatan performs. A framework that batches by design should make the
   driver-side batching switch reachable.

Also worth recording, from the `autoCommit` investigation: `useLocalSessionState=true` removes the
`SET autocommit=0` / `=1` round-trip pair that MySQL/MariaDB pay per transaction. That is the safe
way to recover that cost - unlike a pool-level `autoCommit=false`, which was investigated and
rejected because it silently discards every write made outside an explicit transaction.

## What Changes

- `DataSourceConfig` gains a map of driver properties, defaulting to empty.
- The settings reach the driver beside the URL - `Driver.connect(url, properties)` - through a
  `DriverDataSource` Ekbatan builds itself and hands to Hikari (`HikariConfig.setDataSource`),
  never through Hikari's `dataSourceProperties`, whose DEBUG output prints every entry except one
  named exactly `password`. `FlywayMigrator` connects the same way.
- Added while building: `username` and `password` become optional, and a JDBC URL that carries a
  user name or a secret is refused (see `tasks.md`, section 5).
- Documentation in `docs/database/` and the website mirror, including the `socketTimeout` and
  batch-rewrite examples above.

Verified while writing this proposal: because Ekbatan always configures Hikari with `jdbcUrl` (never
`dataSourceClassName`), `PoolBase.setupDataSource` (HikariCP 7.0.2, line 336) constructs a
`DriverDataSource`, whose `getConnection()` (line 127) is `driver.connect(jdbcUrl, driverProperties)`.
So Hikari's `dataSourceProperties` reach the JDBC driver directly on this path - the feature is a
pass-through, not an emulation. (The build does not use this path, for the DEBUG output above; its
own `DriverDataSource` makes the same `driver.connect` call.)

The three DI integrations need **no code changes**: all of them bind `ekbatan.sharding.*` through
the same Jackson-hybrid path (flat property walk, kebab->camel canonicalization, strict
`JavaPropsMapper`), so a new builder method is picked up automatically in Spring YAML, Quarkus
properties and Micronaut YAML. (This turned out wrong: Jackson ignores its `\.` escape in a key
holding `[0]`, so the readers now split keys at `/` - `PropertyKeyNormalizer.toReaderKey` - and
Micronaut needs a walk over its raw view, where a YAML list is only an aggregate.)

## Capabilities

### New Capabilities

- `datasource-driver-properties`: arbitrary JDBC driver properties expressible through
  `DataSourceConfig` rather than through JDBC URL query parameters.

### Modified Capabilities

<!-- None -->

## Priority

**Low.** No correctness defect; every property this would expose is reachable today by editing the
JDBC URL. Two things would raise it to medium:

- if the keyed-lock network-partition limitation is judged worth closing properly, since
  `socketTimeout` is its documented mitigation;
- if batch-rewrite is measured to matter on the write path, since it applies to nearly every action.

## Impact

- `ekbatan-core`: `DataSourceConfig` (field, constructor, builder field, builder method),
  `ConnectionProvider.hikariConnectionProvider`.
- Tests: `ShardingConfigYamlTest`'s hand-rolled `toDataSourceConfig(Map)` needs a branch for the new
  field or it will silently drop the key while appearing to pass; binding tests in the three DI
  suites.
- Docs: a datasource-options reference, plus the website mirror.

### Backward compatibility

The map itself is compatible: it defaults to empty, and nothing is passed when it is. What was
added while building is not:

- `DataSourceConfig.username` and `password` are `Optional<String>`, so code reading either as a
  `String` no longer compiles.
- A JDBC URL that carries a user name or a secret - `?user=`, `?password=`, `app:secret@`, a
  keystore password - was accepted and is now refused when the configuration is built, so an
  application configured that way fails at startup until the two move to `username` and
  `password`, and any other secret to `data-source-properties`.

## Open questions

All five are settled; `tasks.md` records each answer: `dataSourceProperties`; `Map<String, String>`
of text values; left to the driver, measured and documented; `user` and `password` refused in any
case; and no allow-list - a name containing `/`, `[` or `]` is refused, since no supported driver
has one.

1. **Naming.** `driverProperties` (what they are), `dataSourceProperties` (Hikari's term, but
   misleading here since Ekbatan never uses `dataSourceClassName`), or `properties` (terse).
2. **Value type.** `Map<String, String>` binds cleanly through the strict `JavaPropsMapper`;
   `Map<String, Object>` matches Hikari's signature but complicates binding. String is probably
   right, since JDBC driver properties are string-valued in practice.
3. **Precedence against URL query parameters.** Both channels reach the driver -
   `driver.connect(jdbcUrl, props)`. Which wins is **driver-specific and must be tested per dialect**
   rather than assumed; the answer belongs in the docs.
4. **`user` / `password` keys.** `DriverDataSource` special-cases both, applying the configured
   credentials only when the corresponding property is absent. Decide whether to reject those two
   keys outright (recommended - two sources of truth for credentials is a trap) or document the
   precedence.
5. **Validation.** Probably none: an allow-list would need maintaining per driver and would block
   legitimate settings. An unknown property fails at connection time with a driver error, which is
   an acceptable and clear failure mode.
