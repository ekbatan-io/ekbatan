# spring-boot-wallet-rest-maven-mariadb

A standalone Spring Boot example that uses Ekbatan from Maven Central, backed by **MariaDB**. The MariaDB dialect of the Maven Spring Boot triple — siblings are [`spring-boot-wallet-rest-maven-pg`](../spring-boot-wallet-rest-maven-pg) and [`spring-boot-wallet-rest-maven-mysql`](../spring-boot-wallet-rest-maven-mysql). Source code is byte-identical to the PG sibling apart from one detail (the generated jOOQ package layout); all dialect differences are concentrated in the build descriptor + migrations + init script.

## What it shows

| Surface | Class |
|---|---|
| `Model` (event-emitting) | `Wallet` |
| `Entity` (no events) | `Notification` |
| `Action` | `WalletCreateAction`, `WalletDepositMoneyAction`, `WalletCloseAction`, `CreateNotificationAction` |
| `EventHandler` | `WalletMoneyDepositedEventHandler` |
| `Repository` | `WalletRepository`, `NotificationRepository` |
| REST | `WalletController` |
| Flyway migration | `FlywayConfiguration` — runs migrations through Ekbatan's `ShardingConfig`, single source of truth for DB credentials |
| Integration test | `WalletControllerIntegrationTest` (Testcontainers + Awaitility) |

The framework wiring (the listen-to-yourself path, the outbox guarantees, etc.) is identical to the PG sibling — see that project's [README](../spring-boot-wallet-rest-maven-pg/README.md) or the Gradle sibling [`spring-boot-wallet-rest-gradle-pg`](../spring-boot-wallet-rest-gradle-pg/README.md#how-the-listen-to-yourself-path-lands) for the walkthrough.

## MariaDB-specific bits

Concentrated in five places. Everything else is identical to the PG sibling.

| Concern | What changes vs. PG |
|---|---|
| Migration types | `UUID` (native, 10.7+) for IDs; `JSON` for payloads; `DATETIME(6)` for timestamps; no partial indexes |
| `eventlog` | a separate **database**, not a schema; created by **`V0000__create_eventlog_database.sql`** before any other migration |
| Cross-database privilege | `src/main/resources/mariadb_init.sql` grants the `wallet` user `ALL PRIVILEGES ON *.*`; mounted to `/docker-entrypoint-initdb.d/` and runs as root on container startup |
| `<forcedType>` entries in `pom.xml` | `InstantConverter` for `(?i:DATETIME|TIMESTAMP)`, `JSONObjectNodeConverter` for `(?i:JSON)`; **no UUID converter** — MariaDB has a native `UUID` type |
| Generated jOOQ package | `io.example.wallet.generated.jooq.tables.*` (single-database codegen, no `_schema` subpackage) |

## How the jOOQ codegen works on Maven

The Gradle equivalents use [`dev.monosoul.jooq-docker`](https://github.com/monosoul/jooq-gradle-plugin) which bundles container + Flyway + codegen into a single plugin. There's no Maven equivalent, so this project composes three independent plugins instead — all running before `compile`:

| Phase | Plugin | What it does |
|---|---|---|
| `initialize` | `io.fabric8:docker-maven-plugin` | Starts an ephemeral `mariadb:11.8` container on port 13306. |
| `initialize` | `org.flywaydb:flyway-maven-plugin` | Runs `src/main/resources/db/migration/*.sql` against that container. Includes `flyway-mysql` (handles MariaDB too). |
| `generate-sources` | `org.jooq:jooq-codegen-maven` | Introspects the migrated schema and writes generated Java classes to `target/generated-sources/jooq`. |
| `prepare-package` | `io.fabric8:docker-maven-plugin` | Stops + removes the container. |

The codegen container connects as **root** so the `V0000` migration's `CREATE DATABASE eventlog` succeeds without needing the `mariadb_init.sql` GRANT script. The runtime / test containers use the `wallet` user *with* the init-script GRANT — different setup, same V0000 outcome.

See [`docs/database/mariadb.md`](../../docs/database/mariadb.md) for the per-dialect column-type cheatsheet, and [`docs/maven/jooq-codegen.md`](../../docs/maven/jooq-codegen.md) for the full codegen-chain reference.

## Run locally

You only need Docker installed. The wrapper script handles Maven itself — first run downloads Maven 3.10.0 into `~/.m2/wrapper/dists`.

```bash
./mvnw spring-boot:run
```

This brings up the MariaDB container declared in `compose.yaml` via Spring Boot's docker-compose integration and tears it down on shutdown. Flyway runs the migrations on startup; the API is at `http://localhost:8080/wallets`.

```bash
# Create
curl -X POST http://localhost:8080/wallets \
    -H 'Content-Type: application/json' \
    -d '{"ownerId":"00000000-0000-0000-0000-000000000001","currency":"USD","initialBalance":"0.00"}'

# Deposit
curl -X POST http://localhost:8080/wallets/<id>/deposits \
    -H 'Content-Type: application/json' \
    -d '{"amount":"25.50","recipient":"alice@example.com"}'

# Read
curl http://localhost:8080/wallets/<id>

# Close
curl -X POST http://localhost:8080/wallets/<id>/close
```

## Test

```bash
./mvnw verify
```

The integration test boots Spring with a Testcontainers `MariaDBContainer`, mounts `mariadb_init.sql` into `/docker-entrypoint-initdb.d/`, runs migrations, hits each endpoint, and asserts that the `Wallet` is updated and the `Notification` row eventually appears (the handler is async, so we use Awaitility to poll up to 15 seconds).

## Maven-specific notes

Identical to the PG sibling — same `-parameters` flag, same annotation-processor wiring, same `mavenLocal` + jOOQ-version override, same Spotless wiring. See [`spring-boot-wallet-rest-maven-pg`](../spring-boot-wallet-rest-maven-pg/README.md#maven-specific-notes) for the full set.

## See also

- [`spring-boot-wallet-rest-maven-pg`](../spring-boot-wallet-rest-maven-pg) — the PG sibling in the Maven triple
- [`spring-boot-wallet-rest-maven-mysql`](../spring-boot-wallet-rest-maven-mysql) — the MySQL sibling
- [`quarkus-wallet-rest-gradle-mariadb`](../quarkus-wallet-rest-gradle-mariadb) — the Gradle Quarkus + MariaDB counterpart
- [Ekbatan docs › Wiring with Spring Boot](../../docs/wiring/spring.md)
- [Ekbatan docs › Database › MariaDB](../../docs/database/mariadb.md) — column types, converters, framework tables
- [Ekbatan docs › JOOQ codegen on Maven](../../docs/maven/jooq-codegen.md) — the codegen chain in detail
- [Ekbatan docs › JOOQ codegen on Gradle](../../docs/gradle/jooq-codegen.md) — the Gradle-flavored equivalent
