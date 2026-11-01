# jOOQ codegen on Gradle

Ekbatan uses [`dev.monosoul.jooq-docker`](https://github.com/monosoul/jooq-gradle-plugin) to generate type-safe SQL classes (the `WALLETS` table reference, the `WalletsRecord` type, etc.) from your Flyway migrations. The plugin spins up a throwaway DB container at build time, runs the migrations against it, introspects the live schema, and writes Java classes into `build/generated-jooq/`.

This page walks the plugin in detail. For the per-stack dependency blocks that surround it, see [Getting started with Gradle](getting-started.md). For the *what & why* of codegen — what classes come out, which framework converter to reach for, the per-dialect modeling rationale — see [JOOQ codegen](../database/jooq-codegen.md). **On Maven?** The same content reorganized around `pom.xml` lives at [docs/maven/jooq-codegen.md](../maven/jooq-codegen.md).

## What the plugin does

Three concerns, one plugin:

```
┌── ./gradlew generateJooqClasses ──────────────────────────────────────┐
│                                                                       │
│  1. Pull image + start container          ← jooq { withContainer { } }│
│  2. Run Flyway migrations                 ← migrationLocations.set... │
│  3. Connect, introspect, emit Java        ← generateJooqClasses { }   │
│  4. Stop + remove container                                           │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘

       output: build/generated-jooq/<basePackage>/tables/*.java
               build/generated-jooq/<basePackage>/tables/records/*.java
```

Two configuration scopes:

- **`jooq { withContainer { … } }`** — top-level extension. The image, env vars, port, and JDBC driver class. Plugin's default is PostgreSQL; explicit for everything else.
- **`tasks.generateJooqClasses { … }`** — the task that runs Flyway + codegen. Schemas to scan, base package, output directory, ForcedType entries.

These are deliberately separate blocks. Beginners often nest `image { … }` inside `generateJooqClasses { … }`; that fails with `unresolved reference: image`. The plugin's reference: [github.com/monosoul/jooq-gradle-plugin](https://github.com/monosoul/jooq-gradle-plugin).

## Wiring generated classes onto the source path

`build/generated-jooq/` isn't a Gradle source root by default — your code won't compile against the generated classes until you add:

```kotlin
sourceSets {
    main {
        java {
            srcDir(tasks.generateJooqClasses.flatMap { it.outputDirectory })
        }
    }
}
```

Using `flatMap { it.outputDirectory }` (rather than a literal path) wires the task dependency for free — `compileJava` automatically depends on `generateJooqClasses`. IntelliJ + the Gradle plugin in IntelliJ recognize this as a generated-source root.

## The `jooqCodegen` configuration

The codegen task runs Flyway against the container — Flyway needs the JDBC driver, and for MySQL / MariaDB it also needs `flyway-mysql`. Both go on the special `jooqCodegen` configuration:

```kotlin
dependencies {
    runtimeOnly("org.postgresql:postgresql")
    jooqCodegen("org.postgresql:postgresql")
}
```

The `runtimeOnly` puts the driver on the *application*'s runtime classpath; `jooqCodegen` puts it on the *codegen plugin*'s classpath. Same coordinate, two configurations. Forgetting `jooqCodegen` gives `Driver class not found` during build, not at app boot.

## PostgreSQL

The plugin's default container is Postgres — no explicit `jooq { withContainer { … } }` block is needed.

```kotlin
import org.jooq.meta.jaxb.ForcedType

plugins {
    id("dev.monosoul.jooq-docker") version "8.0.9"
}

tasks {
    generateJooqClasses {
        schemas.set(listOf("public", "eventlog"))
        basePackageName.set("com.example.generated.jooq")
        migrationLocations.setFromFilesystem("src/main/resources/db/migration")
        outputDirectory.set(project.layout.buildDirectory.dir("generated-jooq"))
        flywayProperties.put("flyway.placeholderReplacement", "false")
        includeFlywayTable.set(false)
        outputSchemaToDefault.add("public")                            // SQL: no schema qualifier
        schemaToPackageMapping.put("public", "public_schema")          // Java: <base>.public_schema.tables.*
        schemaToPackageMapping.put("eventlog", "eventlog_schema")
        usingJavaConfig {
            database.withForcedTypes(
                ForcedType()
                    .withUserType("java.time.Instant")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.InstantConverter")
                    .withIncludeTypes("TIMESTAMP")
                    .withIncludeExpression(".*"),
                ForcedType()
                    .withUserType("tools.jackson.databind.node.ObjectNode")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.JSONBObjectNodeConverter")
                    .withIncludeTypes("JSONB")
                    .withIncludeExpression(".*"),
            )
        }
    }
}

dependencies {
    runtimeOnly("org.postgresql:postgresql")
    jooqCodegen("org.postgresql:postgresql")
}

sourceSets {
    main {
        java {
            srcDir(tasks.generateJooqClasses.flatMap { it.outputDirectory })
        }
    }
}
```

**Notes on the Postgres block:**

- `outputSchemaToDefault.add("public")` strips the `public.` schema qualifier from generated SQL — your queries read `SELECT * FROM wallets`, not `SELECT * FROM public.wallets`. Optional, but cleaner.
- `schemaToPackageMapping.put("public", "public_schema")` keeps the Java package name from colliding with Java's `public` keyword. The result: classes land at `com.example.generated.jooq.public_schema.tables.Wallets`.
- The eventlog schema isn't `outputSchemaToDefault.add`-ed — the framework explicitly writes outbox rows with the `eventlog.` qualifier, so the SQL needs to keep it.
- `flywayProperties.put("flyway.placeholderReplacement", "false")` — turn off Flyway's `${variable}` placeholder substitution, which interferes with JSON literals in seed scripts and PL/pgSQL `$$ … $$` quoting.
- `includeFlywayTable.set(false)` — don't generate a Java class for `flyway_schema_history`. You'll never touch it from app code.

## MariaDB

Explicit `jooq { withContainer { … } }` is required.

```kotlin
jooq {
    withContainer {
        image {
            name = "mariadb:11.8"
            envVars = mapOf(
                "MARIADB_ROOT_PASSWORD" to "root",
                "MARIADB_DATABASE" to "wallet",
            )
        }
        db {
            username = "root"
            password = "root"
            name = "wallet"
            port = 3306
            jdbc {
                schema = "jdbc:mariadb"
                driverClassName = "org.mariadb.jdbc.Driver"
            }
        }
    }
}

tasks {
    generateJooqClasses {
        // MySQL/MariaDB have no schemas (the "schema" IS the database). Only generate
        // for the main 'wallet' database. The eventlog tables are accessed via the
        // framework's own field constants, so we don't need to codegen them.
        schemas.set(listOf("wallet"))
        basePackageName.set("com.example.generated.jooq")
        migrationLocations.setFromFilesystem("src/main/resources/db/migration")
        outputDirectory.set(project.layout.buildDirectory.dir("generated-jooq"))
        flywayProperties.put("flyway.placeholderReplacement", "false")
        includeFlywayTable.set(false)
        // No subpackage for the default schema. Classes land at
        // com.example.generated.jooq.tables.Wallets (NOT .wallet.tables.Wallets).
        outputSchemaToDefault.add("wallet")
        usingJavaConfig {
            database.withForcedTypes(
                ForcedType()
                    .withUserType("java.time.Instant")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.InstantConverter")
                    .withIncludeTypes("(?i:DATETIME|TIMESTAMP)")
                    .withIncludeExpression(".*"),
                ForcedType()
                    // MariaDB reports JSON as LONGTEXT, so codegen sees CLOB. withName rewrites
                    // the generated type back to JSON so the converter can attach; matching is by
                    // column name because the reported type is not "JSON".
                    .withName("JSON")
                    .withUserType("tools.jackson.databind.node.ObjectNode")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.JSONObjectNodeConverter")
                    .withIncludeExpression(".*\\.my_json_column"),
                // No UUID forced type needed — MariaDB 10.7+ has a native UUID type and
                // jOOQ maps it directly. Contrast MySQL, below.
            )
        }
    }
}

dependencies {
    // On Quarkus 3.40, driver 3.5.11: Quarkus tests its MariaDB support with 3.5.
    implementation("org.mariadb.jdbc:mariadb-java-client:4.0.0")
    jooqCodegen("org.mariadb.jdbc:mariadb-java-client:4.0.0")
    // The codegen task runs Flyway against the container, and `flyway-mysql` is what
    // Flyway uses to recognize MariaDB JDBC URLs (the artifact name is historical — it
    // covers MariaDB too).
    jooqCodegen("org.flywaydb:flyway-mysql:13.10.3")
    implementation("org.flywaydb:flyway-mysql:13.10.3")
}
```

Three deltas vs. Postgres:

| Item | Postgres | MariaDB |
|---|---|---|
| `withContainer { image }` | implicit (plugin default) | explicit, with `MARIADB_*` env vars |
| Instant ForcedType `includeTypes` | `TIMESTAMP` | `(?i:DATETIME|TIMESTAMP)` |
| JSON ForcedType | `JSONB` + `JSONBObjectNodeConverter`, matched by type | `withName("JSON")` + `JSONObjectNodeConverter` (no `B`), matched by **column expression** - see below |

**Why the case-insensitive `(?i:...)` regex** — the MariaDB / MySQL JDBC drivers aren't consistent about case in reported type names (`JSON` vs `json`, `DATETIME` vs `datetime`). The case-insensitive group spares you from chasing those.

**Why MariaDB's JSON entry doesn't use a type regex at all** — MariaDB implements `JSON` as an alias for `LONGTEXT`, and that is the type the driver reports, so codegen generates `DataType<String>`. No type regex helps: `(?i:JSON)` never matches, and `(?i:JSON|LONGTEXT)` matches but then fails to compile (`incompatible equality constraints JSON,String`), because `JSONObjectNodeConverter` is a `Converter<JSON, ..>`. Matching the column by name and adding `withName("JSON")` rewrites the generated type so the shared converter attaches. MySQL needs none of this.

**Why no UUID converter on MariaDB** — MariaDB 10.7+ ships a native `UUID` type that jOOQ maps to `java.util.UUID` directly. Contrast with MySQL below, which has no native UUID and needs `CHAR(36)` → `UUID` conversion.

## MySQL

Same shape as MariaDB, **plus** a third ForcedType: `CHAR(36)` → `UUID` via `UuidStringConverter`.

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
            username = "root"
            password = "root"
            name = "wallet"
            port = 3306
            jdbc {
                schema = "jdbc:mysql"
                driverClassName = "com.mysql.cj.jdbc.Driver"
            }
        }
    }
}

tasks {
    generateJooqClasses {
        schemas.set(listOf("wallet"))
        basePackageName.set("com.example.generated.jooq")
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
                // MySQL has no native UUID type — every UUID column is CHAR(36). Bind
                // CHAR(36) → java.util.UUID at the jOOQ layer so application code stays
                // dialect-agnostic. Restrict to columns named `id` or ending in `_id` —
                // without that, unrelated CHAR(36) columns (handler names, status enums)
                // would also get bound to UUID and break.
                ForcedType()
                    .withUserType("java.util.UUID")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.mysql.UuidStringConverter")
                    .withIncludeTypes("CHAR\\(36\\)")
                    .withIncludeExpression(".*\\.id|.*_id"),
            )
        }
    }
}

dependencies {
    implementation("com.mysql:mysql-connector-j:26.10.0")
    jooqCodegen("com.mysql:mysql-connector-j:26.10.0")
    jooqCodegen("org.flywaydb:flyway-mysql:13.10.3")
    implementation("org.flywaydb:flyway-mysql:13.10.3")
}
```

The `.withIncludeExpression(".*\\.id|.*_id")` is the key safety net — without it, a `CHAR(36)` column called e.g. `notification_kind` or `handler_name` would also be bound to `UUID`, and the runtime would explode on the first read.

## ForcedType reference

| Column type | Java type | Converter | Used for |
|---|---|---|---|
| `TIMESTAMP` (PG) / `DATETIME`+`TIMESTAMP` (MariaDB/MySQL) | `java.time.Instant` | `io.ekbatan.core.persistence.jooq.converter.InstantConverter` | Every timestamped framework column (`created_date`, `updated_date`, etc.) |
| `JSONB` (PG) | `tools.jackson.databind.node.ObjectNode` | `io.ekbatan.core.persistence.jooq.converter.JSONBObjectNodeConverter` | Event payloads, custom JSON columns |
| `JSON` (MariaDB/MySQL) | `tools.jackson.databind.node.ObjectNode` | `io.ekbatan.core.persistence.jooq.converter.JSONObjectNodeConverter` (no `B`) | Same as above |
| `CHAR(36)` (MySQL only, `id`/`*_id` only) | `java.util.UUID` | `io.ekbatan.core.persistence.jooq.converter.mysql.UuidStringConverter` | Every UUID column on MySQL |

The Postgres-side `JSONBObjectNodeConverter` and the MariaDB/MySQL-side `JSONObjectNodeConverter` are *different* classes — note the `B`. They differ in how they bind to the JDBC driver (`PGObject` on Postgres, plain `String` on MariaDB/MySQL).

For the dialect-level explanation — *why* each forced type is the way it is — see [PostgreSQL setup](../database/postgresql.md), [MariaDB setup](../database/mariadb.md), [MySQL setup](../database/mysql.md).

## Container init scripts (MariaDB/MySQL)

If your `V0000__create_eventlog_database.sql` does a cross-database `CREATE DATABASE eventlog`, the connecting user needs the privilege. The plugin's codegen container connects as root (because `username = "root"` in the `withContainer` block), so codegen works. **Test containers** (Testcontainers in your `@Test` classes) typically connect as a less-privileged user — they need an init script that grants the privilege before the connecting user logs in.

Put the script in `src/main/resources/<dialect>_init.sql`:

```sql
-- mariadb_init.sql
GRANT ALL PRIVILEGES ON *.* TO 'wallet'@'%';
FLUSH PRIVILEGES;
```

And bind-mount it via Testcontainers' `withCopyFileToContainer`:

```java
new MariaDBContainer("mariadb:11.8")
    .withCopyFileToContainer(
        MountableFile.forClasspathResource("mariadb_init.sql"),
        "/docker-entrypoint-initdb.d/mariadb_init.sql");
```

The container's entrypoint runs every `.sql` in `/docker-entrypoint-initdb.d/` as root before the DB becomes ready. See [Schema vs database](../database/multi-database.md#schema-vs-database) for the full grant idiom.

## Why these dialect choices, recap

A few of the deltas above don't have an obvious motivation. Quick justifications:

**Why `schemas.set(listOf("wallet"))` excludes `eventlog` on MariaDB/MySQL but PG includes both** — generated classes are only useful where your code will actually reference them via the generated table constants. The framework's own outbox writes use manually-defined field constants on MariaDB/MySQL (because they don't have the schema concept the PG codegen relies on) and don't need a generated `EventsRecord` class. Generate only what your repositories consume; let the framework's field constants handle the rest.

**Why the MySQL UUID forced type uses `withIncludeExpression(".*\\.id|.*_id")`** — without an expression filter, the converter binds to every `CHAR(36)` column. Some `CHAR(36)` columns aren't UUIDs (handler names, status enums). The expression restricts the converter to columns whose name is `id` or ends in `_id`, which is the framework's convention for UUID columns.

**Why the Instant forced type matches `(?i:DATETIME|TIMESTAMP)` on MariaDB but only `TIMESTAMP` on PG** — PG's `TIMESTAMP` maps to `SQLDataType.LOCALDATETIME` cleanly; MariaDB / MySQL report `DATETIME(6)` as `DATETIME` and need the explicit pattern. The case-insensitive flag handles the case wobble in driver-reported names.

## Troubleshooting

### `unresolved reference: image` / `repository` / `tag` / `envVars` / `testQuery`

The `image { … }` block goes inside the **top-level** `jooq { withContainer { … } }`, not inside `generateJooqClasses { }`. Common pitfall when nesting feels intuitive:

```kotlin
// ✗ WRONG — fails with unresolved reference
tasks.generateJooqClasses {
    image { name = "mariadb:11.8" }
}

// ✓ RIGHT — top-level jooq extension
jooq {
    withContainer {
        image { name = "mariadb:11.8" }
    }
}
```

`image` also uses `name = "<repo>:<tag>"` as a single property, **not** `repository = "…"` + `tag = "…"` (those don't exist in this plugin's DSL).

### `Driver class not found` during `generateJooqClasses`

The JDBC driver is missing from the `jooqCodegen` configuration. Add it:

```kotlin
dependencies {
    runtimeOnly("org.postgresql:postgresql")
    jooqCodegen("org.postgresql:postgresql")   // ← this line
}
```

### Generated classes don't show up on the compile classpath

You skipped the `sourceSets.main.java.srcDir(…)` block. Add it (top of this page).

### `cannot find symbol: class WalletsRecord` after migration change

`generateJooqClasses` is incremental — it tracks `migrationLocations` and the schema list. If a generated class still references an old column, run `./gradlew clean generateJooqClasses` to force a clean codegen.

### MariaDB / MySQL: `Access denied for user 'wallet'` during Flyway

Codegen connects as root (per the `withContainer.db.username = "root"` block in the example). If you've copied this for a runtime app and the *runtime* user is `wallet` without the `CREATE DATABASE` privilege, see the container init scripts section above.

### Postgres: `database "eventlog" does not exist`

For Postgres, `eventlog` is a *schema* (not a database) — it gets created by `V0001__eventlog.sql`'s `CREATE SCHEMA IF NOT EXISTS eventlog`. If you see this on Postgres, check that V0001 ran. (On MariaDB/MySQL, `eventlog` *is* a separate database created by `V0000__create_eventlog_database.sql` — different idiom, see [Schema vs database](../database/multi-database.md#schema-vs-database).)

## See also

- [Getting started with Gradle](getting-started.md) — the rest of the Gradle surface
- [PostgreSQL setup](../database/postgresql.md) / [MariaDB setup](../database/mariadb.md) / [MySQL setup](../database/mysql.md) — per-dialect column types, framework tables, gotchas
- [Multi-database](../database/multi-database.md) — column types, converters, partial indexes, the `dialect.family()` switch pattern
- [Repositories on JOOQ](../database/repositories.md) — what the generated classes feed into
- [docs/maven/jooq-codegen.md](../maven/jooq-codegen.md) — the Maven-flavored equivalent of this page
- The plugin's reference: [github.com/monosoul/jooq-gradle-plugin](https://github.com/monosoul/jooq-gradle-plugin)

← Back to [Gradle](README.md) · [docs index](../README.md)
