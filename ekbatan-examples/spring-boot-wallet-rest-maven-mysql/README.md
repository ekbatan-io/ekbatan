# spring-boot-wallet-rest-maven-mysql

A standalone Spring Boot example that uses Ekbatan from Maven Central, backed by **MySQL**. The MySQL dialect of the Maven Spring Boot triple — siblings are [`spring-boot-wallet-rest-maven-pg`](../spring-boot-wallet-rest-maven-pg) and [`spring-boot-wallet-rest-maven-mariadb`](../spring-boot-wallet-rest-maven-mariadb). Source code is byte-identical to the MariaDB sibling apart from the UUID column type and the extra `UuidStringConverter` `<forcedType>`; all dialect differences are concentrated in the build descriptor + migrations + init script.

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

The framework wiring (the listen-to-yourself path, the outbox guarantees, etc.) is identical to the PG sibling — see [`spring-boot-wallet-rest-maven-pg`](../spring-boot-wallet-rest-maven-pg/README.md) or the Gradle counterpart [`spring-boot-wallet-rest-gradle-pg`](../spring-boot-wallet-rest-gradle-pg/README.md#how-the-listen-to-yourself-path-lands) for the walkthrough.

## MySQL-specific bits

Concentrated in six places. The first five mirror the MariaDB sibling; the sixth is unique to MySQL.

| Concern | What changes vs. PG |
|---|---|
| Migration types | **`CHAR(36) CHARACTER SET ascii`** for UUID columns (no native UUID type); `JSON` for payloads; `DATETIME(6)` for timestamps; no partial indexes |
| `eventlog` | a separate **database**, not a schema; created by **`V0000__create_eventlog_database.sql`** before any other migration |
| Cross-database privilege | `src/main/resources/mysql_init.sql` grants the `wallet` user `ALL PRIVILEGES ON *.*`; mounted to `/docker-entrypoint-initdb.d/` and runs as root on container startup |
| `<forcedType>` entries in `pom.xml` | `InstantConverter` for `(?i:DATETIME|TIMESTAMP)`, `JSONObjectNodeConverter` for `(?i:JSON)`, **plus** `UuidStringConverter` for `CHAR(36)` columns matching `.*\.id|.*_id` |
| Generated jOOQ package | `io.example.wallet.generated.jooq.tables.*` (single-database codegen, no `_schema` subpackage) |
| **`UuidStringConverter`** (MySQL-only) | MySQL has no native `UUID` type. Every UUID column is `CHAR(36) CHARACTER SET ascii`, and `UuidStringConverter` bridges back to `java.util.UUID` at the jOOQ layer. The `includeExpression` scopes the converter to columns named `id` or ending in `_id` — without that, unrelated `CHAR(36)` columns would also bind to `UUID` and break at runtime. |

## How the jOOQ codegen works on Maven

The Gradle equivalents use [`dev.monosoul.jooq-docker`](https://github.com/monosoul/jooq-gradle-plugin) which bundles container + Flyway + codegen into a single plugin. There's no Maven equivalent, so this project composes three independent plugins instead — all running before `compile`:

| Phase | Plugin | What it does |
|---|---|---|
| `initialize` | `io.fabric8:docker-maven-plugin` | Starts an ephemeral `mysql:9.4.0` container on port 13307. |
| `initialize` | `org.flywaydb:flyway-maven-plugin` | Runs `src/main/resources/db/migration/*.sql` against that container. Includes `flyway-mysql`. |
| `generate-sources` | `org.jooq:jooq-codegen-maven` | Introspects the migrated schema and writes generated Java classes to `target/generated-sources/jooq`. The three `<forcedType>` entries land here. |
| `prepare-package` | `io.fabric8:docker-maven-plugin` | Stops + removes the container. |

The codegen container connects as **root** so the `V0000` migration's `CREATE DATABASE eventlog` succeeds without needing the `mysql_init.sql` GRANT script. The runtime / test containers use the `wallet` user *with* the init-script GRANT.

See [`docs/database/mysql.md`](../../docs/database/mysql.md) for the per-dialect column-type cheatsheet (including why `CHAR(36)` over `BINARY(16)` for UUIDs), and [`docs/maven/jooq-codegen.md`](../../docs/maven/jooq-codegen.md) for the full codegen-chain reference.

## Run locally

You only need Docker installed. The wrapper script handles Maven itself — first run downloads Maven 3.10.0 into `~/.m2/wrapper/dists`.

```bash
./mvnw spring-boot:run
```

This brings up the MySQL container declared in `compose.yaml` via Spring Boot's docker-compose integration and tears it down on shutdown. Flyway runs the migrations on startup; the API is at `http://localhost:8080/wallets`.

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

The integration test boots Spring with a Testcontainers `MySQLContainer`, mounts `mysql_init.sql` into `/docker-entrypoint-initdb.d/`, runs migrations, hits each endpoint, and asserts that the `Wallet` is updated and the `Notification` row eventually appears (the handler is async, so we use Awaitility to poll up to 15 seconds).

## Maven-specific notes

Identical to the PG sibling — same `-parameters` flag, same annotation-processor wiring, same `mavenLocal` + jOOQ-version override, same Spotless wiring. See [`spring-boot-wallet-rest-maven-pg`](../spring-boot-wallet-rest-maven-pg/README.md#maven-specific-notes) for the full set.

## See also

- [`spring-boot-wallet-rest-maven-pg`](../spring-boot-wallet-rest-maven-pg) — the PG sibling in the Maven triple
- [`spring-boot-wallet-rest-maven-mariadb`](../spring-boot-wallet-rest-maven-mariadb) — the MariaDB sibling
- [`spring-boot-wallet-rest-gradle-mysql`](../spring-boot-wallet-rest-gradle-mysql) — the Gradle counterpart, same source code, different build tool
- [Ekbatan docs › Wiring with Spring Boot](../../docs/wiring/spring.md)
- [Ekbatan docs › Database › MySQL](../../docs/database/mysql.md) — column types, the `CHAR(36)` UUID rationale, framework tables
- [Ekbatan docs › JOOQ codegen on Maven](../../docs/maven/jooq-codegen.md) — the codegen chain in detail
- [Ekbatan docs › JOOQ codegen on Gradle](../../docs/gradle/jooq-codegen.md) — the Gradle-flavored equivalent
