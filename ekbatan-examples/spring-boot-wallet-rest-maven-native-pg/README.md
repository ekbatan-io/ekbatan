# spring-boot-wallet-rest-maven-native-pg

A standalone Spring Boot example that uses Ekbatan from Maven Central, backed by **PostgreSQL**. The PG dialect of the Maven Spring Boot triple — siblings are [`spring-boot-wallet-rest-maven-mariadb`](../spring-boot-wallet-rest-maven-mariadb) and [`spring-boot-wallet-rest-maven-mysql`](../spring-boot-wallet-rest-maven-mysql). Same domain and same wiring as the Gradle counterpart [`spring-boot-wallet-rest-gradle-pg`](../spring-boot-wallet-rest-gradle-pg); only `pom.xml` vs. `build.gradle.kts` differs.

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

The framework wiring (the listen-to-yourself path, the outbox guarantees, etc.) is identical to the Gradle sibling — see that project's [README](../spring-boot-wallet-rest-gradle-pg/README.md#how-the-listen-to-yourself-path-lands) for the walkthrough.

## How the jOOQ codegen works on Maven

The Gradle siblings use [`dev.monosoul.jooq-docker`](https://github.com/monosoul/jooq-gradle-plugin) which bundles container + Flyway + codegen into a single plugin. There's no equivalent on Maven, so this project composes three independent plugins instead — all running before `compile`:

| Phase | Plugin | What it does |
|---|---|---|
| `initialize` | `io.fabric8:docker-maven-plugin` | Starts an ephemeral `postgres:16` container on port 15432. |
| `initialize` | `org.flywaydb:flyway-maven-plugin` | Runs `src/main/resources/db/migration/*.sql` against that container. |
| `generate-sources` | `org.jooq:jooq-codegen-maven` | Introspects the migrated schema and writes generated Java classes to `target/generated-sources/jooq`. |
| `prepare-package` | `io.fabric8:docker-maven-plugin` | Stops + removes the container. |

Why this chain instead of [`testcontainers-jooq-codegen-maven-plugin`](https://github.com/testcontainers/testcontainers-jooq-codegen-maven-plugin): the testcontainers plugin hasn't shipped a commit since April 2024 and is still pre-1.0; fabric8 + flyway + jooq are independently maintained and on regular release cadence.

The jOOQ `ForcedType` blocks match the Gradle sibling exactly — `InstantConverter` for `TIMESTAMP`, `JSONBObjectNodeConverter` for `JSONB`. Postgres's native `UUID` type needs no converter. See [`docs/database/postgresql.md`](../../docs/database/postgresql.md) for the per-dialect cheatsheet.

## What changes between the three Maven siblings

Same `pom.xml` chain, same Spring Boot wiring, same Java domain code — the dialect deltas are concentrated in three places:

| Concern | PG (this project) | MariaDB sibling | MySQL sibling |
|---|---|---|---|
| Migration types | `UUID`, `JSONB`, `TIMESTAMP`, partial indexes | `UUID` (native, 10.7+), `JSON`, `DATETIME(6)`, no partial indexes | `CHAR(36) CHARACTER SET ascii`, `JSON`, `DATETIME(6)`, no partial indexes |
| `eventlog` | a *schema* inside the main DB; created by `V0001` | a separate *database*; created by `V0000`; needs `mariadb_init.sql` GRANT | a separate *database*; created by `V0000`; needs `mysql_init.sql` GRANT |
| `<forcedType>` entries | `InstantConverter`, `JSONBObjectNodeConverter` | `InstantConverter`, `JSONObjectNodeConverter` | `InstantConverter`, `JSONObjectNodeConverter`, **plus** `UuidStringConverter` (for `CHAR(36)` → `UUID`) |
| Generated package | `…public_schema.tables.*` / `…eventlog_schema.tables.*` | `…tables.*` (single-database codegen) | `…tables.*` (single-database codegen) |

## Run locally

You only need Docker installed. The wrapper script handles Maven itself — first run downloads Maven 3.10.0 into `~/.m2/wrapper/dists`.

```bash
./mvnw spring-boot:run
```

This brings up the Postgres container declared in `compose.yaml` via Spring Boot's docker-compose integration and tears it down on shutdown — no manual `docker run` step. Flyway runs the migrations on startup; the API is at `http://localhost:8080/wallets`.

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

The integration test boots Spring with a Testcontainers PostgreSQL, runs migrations, hits each endpoint, and asserts that the `Wallet` is updated and the `Notification` row eventually appears (the handler is async, so we use Awaitility to poll up to 15 seconds).

Note: `./mvnw test` won't run the codegen chain on its own (Maven binds codegen to `generate-sources`, which runs before `compile`, which runs before `test` — so it's fine in practice, the chain runs first). The container the chain uses is independent from the Testcontainers-managed one the integration test uses.

## Maven-specific notes

- **`-parameters` compiler flag** — Ekbatan reads parameter names via reflection (for `@AutoBuilder`-generated builders), and Maven doesn't enable this by default. The `maven-compiler-plugin` configuration in `pom.xml` turns it on.
- **Annotation processor wiring** — declared via `maven-compiler-plugin`'s `<annotationProcessorPaths>`, the Maven equivalent of Gradle's `annotationProcessor` configuration. Without this, `@AutoBuilder` would be silently ignored at compile time.
- **`mavenLocal` is enabled** — the POM lists `~/.m2/repository` ahead of Maven Central, so an in-progress local Ekbatan build (`./gradlew publishToMavenLocal` in the parent repo) takes precedence over the published version.
- **jOOQ version override** — Spring Boot 4.0.x's BOM pins jOOQ to 3.19.x, but Ekbatan targets 3.20.x. The `<jooq.version>` property in `pom.xml` overrides the BOM.
- **Spotless** — `spotless-maven-plugin` is wired in with Palantir Java Format 2.81.0, matching the framework's Gradle setup. `./mvnw spotless:check` (also runs during `compile`) fails on drift; `./mvnw spotless:apply` rewrites files in place.

## See also

- [`spring-boot-wallet-rest-maven-mariadb`](../spring-boot-wallet-rest-maven-mariadb) — the MariaDB sibling in the Maven triple
- [`spring-boot-wallet-rest-maven-mysql`](../spring-boot-wallet-rest-maven-mysql) — the MySQL sibling
- [`spring-boot-wallet-rest-gradle-pg`](../spring-boot-wallet-rest-gradle-pg) — the Gradle counterpart, same source code, different build tool
- [`spring-boot-wallet-rest-gradle-native-pg`](../spring-boot-wallet-rest-gradle-native-pg) — the GraalVM native-image variant (Gradle-only)
- [Ekbatan docs › Wiring with Spring Boot](../../docs/wiring/spring.md)
- [Ekbatan docs › Database › PostgreSQL](../../docs/database/postgresql.md) — column types, converters, framework tables
- [Ekbatan docs › JOOQ codegen on Maven](../../docs/maven/jooq-codegen.md) — the codegen chain in detail
- [Ekbatan docs › JOOQ codegen on Gradle](../../docs/gradle/jooq-codegen.md) — the Gradle-flavored equivalent
