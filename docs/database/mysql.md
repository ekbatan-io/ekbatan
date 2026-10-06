# MySQL

MySQL is the busiest dialect to wire up: no native `UUID`, no partial indexes, and no schemas (eventlog becomes a separate database). The framework supports it the same way it supports MariaDB plus an extra forced-type entry for UUID columns. This page is the one-stop setup reference. Cross-dialect background: [Multi-database](multi-database.md). Framework table reference: [Framework tables](tables.md).

## Quick reference

| Java type | SQL column type | jOOQ converter (in `withForcedTypes`) |
|---|---|---|
| `java.util.UUID` | `CHAR(36) CHARACTER SET ascii` | `UuidStringConverter` (MySQL only) |
| `tools.jackson.databind.node.ObjectNode` | `JSON` | `JSONObjectNodeConverter` (no `B`) |
| `tools.jackson.databind.node.ArrayNode` | `JSON` | `JSONArrayNodeConverter` |
| `java.time.Instant` | `DATETIME(6)` | `InstantConverter` |
| `String` | `VARCHAR(N)` / `TEXT` | none |
| `Boolean` | `BOOLEAN` (alias for `TINYINT(1)`) | none |
| `Long` / `Integer` | `BIGINT` / `INT` | none |
| `BigDecimal` | `DECIMAL(p, s)` | none |

The `(6)` on `DATETIME` is fractional-second precision — match Postgres's `TIMESTAMP` resolution. Never use `TIMESTAMP` for app-written columns; reserve it for db-scheduler's table.

A `UuidBinaryConverter` (`BINARY(16)` → `UUID`) also exists under `io.ekbatan.core.persistence.jooq.converter.mysql`. It's not used in the project today — the `CHAR(36)` form is preferred for grep-ability in logs and JDBC dumps. See [why `CHAR(36)` over `BINARY(16)`](multi-database.md#why-mysql-uuid-converter-is-char36-shaped-not-binary16).

## Migration order

MySQL has no schemas, so `eventlog` is its own database — created by the first migration. The connecting user usually only has rights on the main database, so cross-database `GRANT`s must come from an init script (which runs as root before Flyway connects).

```
src/main/resources/
  mysql_init.sql                                  -- root-only GRANTs; mounted into the container at startup
  db/migration/
    V0000__create_eventlog_database.sql           -- CREATE DATABASE IF NOT EXISTS eventlog;
    V0001__eventlog.sql                           -- events + event_notifications + indexes (in eventlog.*)
    V0002__scheduled_tasks.sql                    -- only if you use ekbatan-distributed-jobs
    V0003__<domain>.sql                           -- your domain tables
```

### `mysql_init.sql`

Grants the connecting user cross-database privileges. The container runs every `.sql` in `/docker-entrypoint-initdb.d/` as root before becoming ready — that's the only place this can live (Flyway connects as the named user, which can't grant itself anything).

```sql
GRANT ALL PRIVILEGES ON *.* TO 'wallet'@'%';
FLUSH PRIVILEGES;
```

Mount it via Testcontainers or docker-compose:

```java
.withCopyFileToContainer(
    MountableFile.forClasspathResource("mysql_init.sql"),
    "/docker-entrypoint-initdb.d/mysql_init.sql"
)
```

See [Schema vs database](multi-database.md#schema-vs-database) for the full idiom.

### `V0000__create_eventlog_database.sql`

```sql
CREATE DATABASE IF NOT EXISTS eventlog;
```

## Framework tables

### `eventlog.events` (always required)

```sql
CREATE TABLE eventlog.events (
    id              CHAR(36) CHARACTER SET ascii NOT NULL,
    namespace       VARCHAR(255) NOT NULL,
    action_id       CHAR(36) CHARACTER SET ascii NOT NULL,
    action_name     VARCHAR(255) NOT NULL,
    action_params   JSON         NOT NULL,
    started_date    DATETIME(6)  NOT NULL,
    completion_date DATETIME(6)  NOT NULL,
    model_id        VARCHAR(255),
    model_type      VARCHAR(255),
    event_type      VARCHAR(255),
    payload         JSON,
    event_date      DATETIME(6)  NOT NULL,
    delivered       BOOLEAN      NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_events_action_id ON eventlog.events(action_id);

-- No `WHERE delivered = FALSE` partial index — MySQL doesn't support partial indexes.
-- Keep the fan-out predicate columns in the full index instead.
CREATE INDEX events_pending_fanout ON eventlog.events (delivered, event_type, event_date);
```

### `eventlog.event_notifications` (only with `ekbatan-events-local-event-handler`)

```sql
CREATE TABLE eventlog.event_notifications (
    id              CHAR(36) CHARACTER SET ascii NOT NULL,
    event_id        CHAR(36) CHARACTER SET ascii NOT NULL,
    handler_name    VARCHAR(255) NOT NULL,
    namespace       VARCHAR(255) NOT NULL,
    action_id       CHAR(36) CHARACTER SET ascii NOT NULL,
    action_name     VARCHAR(255) NOT NULL,
    action_params   JSON         NOT NULL,
    started_date    DATETIME(6)  NOT NULL,
    completion_date DATETIME(6)  NOT NULL,
    model_id        VARCHAR(255),
    model_type      VARCHAR(255),
    event_type      VARCHAR(255) NOT NULL,
    payload         JSON,
    event_date      DATETIME(6)  NOT NULL,
    state           VARCHAR(24)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_retry_at   DATETIME(6)  NOT NULL,
    created_date    DATETIME(6)  NOT NULL,
    updated_date    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (event_id, handler_name)
);

CREATE INDEX event_notifications_due ON eventlog.event_notifications (next_retry_at);
```

### `scheduled_tasks` (only with `ekbatan-distributed-jobs`)

db-scheduler's required table — verbatim from [its repo](https://github.com/kagkarlsson/db-scheduler/blob/master/db-scheduler/src/main/resources/mysql_tables.sql). MySQL and MariaDB share the same DDL.

```sql
create table scheduled_tasks (
    task_name VARCHAR(100) NOT NULL,
    task_instance VARCHAR(100) NOT NULL,
    task_data BLOB,
    execution_time TIMESTAMP(6) NOT NULL,
    picked BOOLEAN NOT NULL,
    picked_by VARCHAR(50),
    last_success TIMESTAMP(6) NULL,
    last_failure TIMESTAMP(6) NULL,
    consecutive_failures INT,
    last_heartbeat TIMESTAMP(6) NULL,
    version BIGINT NOT NULL,
    priority SMALLINT,
    PRIMARY KEY (task_name, task_instance),
    INDEX execution_time_idx (execution_time),
    INDEX last_heartbeat_idx (last_heartbeat),
    INDEX priority_execution_time_idx (priority, execution_time)
);
```

## Domain tables

`AbstractRepository` requires two columns on every domain table — `version` and `state` — and fails at construction if either is missing. `ModelRepository` additionally requires `created_date` and `updated_date`; an `Entity` table backed by `EntityRepository` needs neither, since the framework does not track timestamps for entities. Reads automatically filter `state <> 'DELETED'`; updates carry `WHERE version = ?` and increment.

| Column | Type | Role |
|---|---|---|
| `id` | `CHAR(36) CHARACTER SET ascii` (UUID) or your aggregate ID type | Primary key |
| `version` | `BIGINT NOT NULL` | Optimistic-locking counter — required, no default |
| `state` | `VARCHAR(N) NOT NULL` | Soft-delete discriminator. Set to `'DELETED'` for soft deletes (no separate `deleted_at` column) |
| `created_date` | `DATETIME(6) NOT NULL` | Set once on insert |
| `updated_date` | `DATETIME(6) NOT NULL` | Bumped on every update alongside `version` |

A minimal example:

```sql
CREATE TABLE wallets (
    id           CHAR(36) CHARACTER SET ascii NOT NULL,
    version      BIGINT         NOT NULL,
    state        VARCHAR(24)    NOT NULL,
    owner_id     CHAR(36) CHARACTER SET ascii NOT NULL,
    currency     CHAR(3)        NOT NULL,
    balance      DECIMAL(19, 4) NOT NULL,
    created_date DATETIME(6)    NOT NULL,
    updated_date DATETIME(6)    NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_wallets_owner_id ON wallets(owner_id);
```

`CHARACTER SET ascii` keeps each char at one byte (vs. 3–4 under `utf8mb4`) and tightens index locality — UUIDs are pure 7-bit ASCII so the wider charset adds no value.

## jOOQ codegen

Explicit `jooq { withContainer { … } }` is required. The MySQL block adds a third `ForcedType` for `CHAR(36)` → `UUID`. Full reference: [JOOQ codegen on Gradle → MySQL](../gradle/jooq-codegen.md#mysql). **On Maven?** See [JOOQ codegen on Maven → MySQL](../maven/jooq-codegen.md#mysql) — same three `ForcedType` entries (Instant, JSON, CHAR(36)→UUID) expressed as `<forcedType>` elements in a `pom.xml`.

```kotlin
jooq {
    withContainer {
        image {
            name = "mysql:9.4.0"
            envVars = mapOf(
                "MYSQL_ROOT_PASSWORD" to "root",
                "MYSQL_DATABASE" to "wallet",
            )
        }
        db {
            username = "root"; password = "root"; name = "wallet"; port = 3306
            jdbc { schema = "jdbc:mysql"; driverClassName = "com.mysql.cj.jdbc.Driver" }
        }
    }
}

tasks {
    generateJooqClasses {
        schemas.set(listOf("wallet"))
        basePackageName.set("io.example.<your_app>.generated.jooq")
        migrationLocations.setFromFilesystem("src/main/resources/db/migration")
        outputDirectory.set(project.layout.buildDirectory.dir("generated-jooq"))
        flywayProperties.put("flyway.placeholderReplacement", "false")
        includeFlywayTable.set(false)
        outputSchemaToDefault.add("wallet")
        usingJavaConfig {
            database.withForcedTypes(
                ForcedType()
                    .withUserType("java.time.Instant")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.InstantConverter")
                    .withIncludeTypes("(?i:DATETIME|TIMESTAMP)")
                    .withIncludeExpression(".*"),
                ForcedType()
                    .withUserType("tools.jackson.databind.node.ObjectNode")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.JSONObjectNodeConverter")
                    .withIncludeTypes("(?i:JSON)")
                    .withIncludeExpression(".*"),
                ForcedType()
                    .withUserType("java.util.UUID")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.mysql.UuidStringConverter")
                    .withIncludeTypes("CHAR\\(36\\)")
                    .withIncludeExpression(".*\\.id|.*_id"),
            )
        }
    }
}
```

The UUID entry deliberately narrows scope to columns named `id` or ending in `_id` — without that, the converter would bind to unrelated `CHAR(36)` columns (handler names, status enums) and break codegen. The `\\(36\\)` escape is required because parentheses are regex metacharacters.

Don't forget the build-time dependencies:

```kotlin
dependencies {
    implementation("com.mysql:mysql-connector-j:${project.property("mysqlConnectorVersion")}")
    jooqCodegen("com.mysql:mysql-connector-j:${project.property("mysqlConnectorVersion")}")
    implementation("org.flywaydb:flyway-mysql:${project.property("flywayVersion")}")
    jooqCodegen("org.flywaydb:flyway-mysql:${project.property("flywayVersion")}")
}
```

## MySQL-specific gotchas

- **No native UUID — pick the column shape.** MySQL has no UUID type. The framework uses `CHAR(36) CHARACTER SET ascii` because it's grep-able in logs and JDBC dumps. `BINARY(16)` (with `UuidBinaryConverter`) is more compact but harder to debug — switch only if you have a concrete reason. Both converters live under `io.ekbatan.core.persistence.jooq.converter.mysql`.
- **`CHARACTER SET ascii` on UUID columns** keeps each char at one byte and tightens index locality. Don't drop it.
- **No partial indexes.** Drop the `WHERE delivered = FALSE` and `WHERE state IN (…)` clauses from the Postgres migrations when porting, and put the predicate columns in the full indexes instead, e.g. `(delivered, event_type, event_date)` for fan-out.
- **`JSON`, not `JSONB`.** Use `JSONObjectNodeConverter` / `JSONArrayNodeConverter` (without the `B`).
- **`DATETIME(6)`, not `TIMESTAMP`.** App-written columns use `DATETIME(6)`. `TIMESTAMP` has a smaller value range, implicit `ON UPDATE CURRENT_TIMESTAMP` quirks, and gets weird with `NULL` — reserve it for db-scheduler's table.
- **eventlog as a separate database.** Two consequences: a `V0000__create_eventlog_database.sql` migration, and an init script granting the connecting user cross-database rights. Don't try to put `GRANT` statements in Flyway migrations.
- **Charset/collation on non-UUID strings.** Default to `utf8mb4` / `utf8mb4_unicode_ci` for general text columns. The framework doesn't enforce a specific collation but stay consistent across tables.
- **Keep the server in UTC.** `withEnv("TZ", "UTC")` on the container. Ekbatan's `DATETIME(6)` values come out right on a server in any time zone, since `InstantConverter` writes and reads them as UTC; the server's own clock does not. On a server outside UTC, `NOW()` and `CURRENT_TIMESTAMP` in your SQL read local time, hours away from the values Ekbatan wrote. Where the server's zone can't be changed, set each connection's under [`data-source-properties`](connecting.md#data-source-properties): `sessionVariables: "time_zone='+00:00'"`, in the same list as any other session variable (`innodb_lock_wait_timeout=10,time_zone='+00:00'`). Not `serverTimezone` or `connectionTimeZone`: with the JVM outside UTC, MySQL's driver then stores Ekbatan's values shifted by the JVM's offset, nine hours early on Tokyo time, while reading them back still looks right.
- **Forced-type include patterns use `(?i:...)`.** The JDBC driver reports type names inconsistently in case (`JSON` vs `json`, `DATETIME` vs `datetime`); case-insensitive regex avoids chasing those.

## See also

- [Multi-database](multi-database.md) — cross-dialect background, column-type cheatsheet, init scripts, repository field-definition pattern
- [`eventlog.events`](tables/events.md) — the logical shape of the event table
- [Repositories on JOOQ](repositories.md) — how `AbstractRepository` consumes the generated classes
- [JOOQ codegen](jooq-codegen.md) — what codegen generates, the converters, per-dialect modeling rationale
- [JOOQ codegen on Gradle](../gradle/jooq-codegen.md) — the full `build.gradle.kts` reference for all dialects
- [JOOQ codegen on Maven](../maven/jooq-codegen.md) — the equivalent `pom.xml` plugin chain
- [Keyed locks](keyed-locks.md) — MySQL `GET_LOCK()`-backed pessimistic locking
- Worked example: [`ekbatan-integration-tests/local-event-handler/mysql`](../../ekbatan-integration-tests/local-event-handler/mysql)

← Back to [Database](README.md) · [docs index](../README.md)
