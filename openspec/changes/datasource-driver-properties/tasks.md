# Tasks

Built 2026-10-02 to 2026-10-05, not yet committed. Where the build differs from the plan, the task
says so; section 5 lists what was added while building.

## 0. Decide the open questions first

- [x] 0.1 Settle the field name: `driverProperties` / `dataSourceProperties` / `properties`. This is
      the external config key, so it is effectively permanent once documented. Note the Jackson
      property name is derived from the **builder method name** because of
      `@JsonPOJOBuilder(withPrefix = "")`.
      Settled: `dataSourceProperties`, written `data-source-properties` in YAML and properties
      files - the word Hikari, Spring Boot and Micronaut use.
- [x] 0.2 Settle the value type. `Map<String, String>` binds cleanly through the strict
      `JavaPropsMapper`; verify a nested map actually round-trips through the flat-property walk all
      three DI integrations use, since every existing field is a scalar and a map is new shape.
      Settled: `Map<String, String>`. The builder takes `Map<String, ?>` and keeps each value's
      text; a nested block or a list is refused. The round trip did need DI changes: Jackson
      ignores its `\.` escape in a key holding `[0]`, so the readers split keys at `/` instead
      (`PropertyKeyNormalizer.toReaderKey`), and Micronaut needs a walk over the raw view, since
      a YAML list is only an aggregate there.
- [x] 0.3 Decide the `user` / `password` policy: reject those two keys, or document precedence.
      Recommendation is reject - two sources of truth for credentials is a trap.
      Settled: rejected, in any case - MySQL's and MariaDB's drivers read `USER` and `Password`
      as the same two settings.

## 1. Core

- [x] 1.1 Add the field to `DataSourceConfig`: public final field, constructor assignment, builder
      field defaulting to an empty map, builder method. Copy the structure of an existing optional
      field.
- [x] 1.2 Forward in `ConnectionProvider.hikariConnectionProvider`, next to the existing
      `cfg.leakDetectionThreshold.ifPresent(...)` calls, via
      `HikariConfig.addDataSourceProperty(key, value)`.
      Done differently: not through `addDataSourceProperty`, since Hikari's DEBUG output prints
      every entry of that map except one named exactly `password`. Ekbatan builds its own
      `DriverDataSource` with the settings and hands it to Hikari (`HikariConfig.setDataSource`);
      `FlywayMigrator` gets its connections from the same place.
- [x] 1.3 Defensive copy on construction, so the caller cannot mutate the map afterwards - matches
      the immutability the rest of the config objects hold to.

## 2. Tests

- [x] 2.1 `ShardingConfigYamlTest` hand-rolls `toDataSourceConfig(Map)` with an explicit branch per
      optional field and does **not** use Jackson. Without a branch for the new field the key is
      silently dropped and the test still passes while asserting nothing. Add the branch.
      Added there and in `ShardingConfigJacksonBindingTest`, which binds through Jackson.
- [x] 2.2 Binding tests in all three DI suites (kebab and camel spellings). Note the asymmetry:
      Micronaut has three optional-field tests, Quarkus two, Spring none - so Spring will need a
      nested `OptionalFields` class if the coverage is to be even.
      Both spellings in all three suites, plus a test that a URL carrying a password fails the
      binding and that the printed failure never shows the password.
- [x] 2.3 Integration test proving a property actually reaches the driver. `socketTimeout` on
      PostgreSQL is a good probe: set it low, block the connection, assert the timeout fires. A
      binding test alone proves only that the value was stored, not that it was forwarded.
      Done with settings each database reports back on the live connection instead:
      PostgreSQL (`FlywayMigratorConnectionIntegrationTest`, both a migration's connection and
      the pool's; `DataSourcePropertiesCertificateLoginIntegrationTest`, a client-certificate
      login with an encrypted key opened through `sslpassword`), MySQL and MariaDB
      (`...DataSourcePropertiesIntegrationTest`, MariaDB's with a login plugin), and the
      configuration files of the Spring Boot, Quarkus and Micronaut test applications. Every
      example sets one setting; 34 of the 40 assert it.
- [x] 2.4 Test the `user`/`password` policy chosen in 0.3. `DataSourceConfigCredentialsTest`.

## 3. Docs

- [x] 3.1 There is currently **no page that tabulates `DataSourceConfig` options at all** - the
      fields appear only inside YAML samples, plus two prose sentences at
      `docs/database/keyed-locks.md:182` and its website mirror that enumerate accepted leaf
      spellings. Consider adding a proper reference table as part of this change; it is the natural
      home for the new field.
      `docs/database/connecting.md`, "The fields".
- [x] 3.2 Document the per-dialect precedence between URL query parameters and driver properties,
      **after testing it** on each driver rather than assuming.
      Measured: PostgreSQL's and MariaDB's drivers use the URL's value, MySQL's the other one.
      Documented as "give a setting in one place only"; left to the driver, as JDBC, Hikari,
      Agroal and Flyway leave it, with no warning (decided 2026-10-05).
- [ ] 3.3 Worked examples worth including, all Ekbatan-specific rather than generic:
      - `socketTimeout` (PostgreSQL) - the documented mitigation for the keyed-lock
        network-partition limitation in `docs/database/keyed-locks.md`, currently described as
        something you set on the JDBC URL by hand.
      - `reWriteBatchedInserts=true` (PostgreSQL) / `rewriteBatchedStatements=true` (MySQL,
        MariaDB) - `ActionExecutor` always writes through the batch methods, so these apply to
        nearly every action.
      - `useLocalSessionState=true` (MySQL, MariaDB) - removes the per-transaction
        `SET autocommit=0` / `=1` round-trip pair. This is the safe way to recover that cost; a
        pool-level `autoCommit=false` was investigated and rejected because it silently discards
        every write made outside an explicit transaction.
      Only `socketTimeout` is done (see 4.1). The batch-rewrite and `useLocalSessionState`
      claims are not documented until a measurement backs them.
- [x] 3.4 Mirror everything to `website/src/pages/`, per the docs-sync rule.

## 4. Follow-on

- [x] 4.1 Once this lands, revisit `docs/database/keyed-locks.md`'s known-limitation wording: the
      mitigation stops being "edit the JDBC URL" and becomes a first-class config option.
      It now names `socketTimeout` under the lock pool's `data-source-properties`, with each
      driver's unit.

## 5. Added while building

- [x] 5.1 `username` and `password` are optional (`Optional<String>`), for logins that need
      neither: an IAM token, a client certificate, Kerberos, a driver plugin. A blank `username`
      is refused, the empty password stays a valid password, and a pool built without a
      `username` logs a warning. Source-breaking for code that read either as a `String`.
- [x] 5.2 A JDBC URL that carries a user name or a secret is refused (`JdbcUrlCredentials`). The
      names come from `driver-settings.tsv`, a ledger of every setting of every driver whose URL
      Ekbatan accepts; the error names every refused setting and never repeats the URL or a
      value.
- [x] 5.3 A blank `driverClassName` means none, as when it is left out.
- [x] 5.4 `FlywayMigrator` connects from the same `DataSourceConfig` as the pools, settings
      included, and puts the shard and target being migrated in the MDC.
