# jOOQ codegen on Maven

The Gradle examples use [`dev.monosoul.jooq-docker`](https://github.com/monosoul/jooq-gradle-plugin), one plugin that bundles container + Flyway + jOOQ codegen. Maven has no equivalent single plugin, so we compose three independent ones in lifecycle order. End result: same `target/generated-sources/jooq/**` output as the Gradle sibling.

For the Gradle-flavored version of this page, see [docs/gradle/jooq-codegen.md](../gradle/jooq-codegen.md). The two pages are intentionally parallel. For the *what & why* of codegen — what classes come out, which framework converter to reach for, the per-dialect modeling rationale — see [JOOQ codegen](../database/jooq-codegen.md).

## What the chain does

```
┌── ./mvnw generate-sources ─────────────────────────────────────────────┐
│                                                                        │
│  initialize:     1. docker-maven-plugin    pulls image + starts container│
│                  2. flyway-maven-plugin    runs Flyway migrations      │
│  generate-sources: 3. jooq-codegen-maven   connects, introspects, emits│
│  prepare-package: 4. docker-maven-plugin   stops + removes container   │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘

       output: target/generated-sources/jooq/<basePackage>/tables/*.java
               target/generated-sources/jooq/<basePackage>/tables/records/*.java
```

## The chain

| Phase | Plugin | What it does |
|---|---|---|
| `initialize` | `io.fabric8:docker-maven-plugin` | Starts an ephemeral DB container on a host port. |
| `initialize` | `org.flywaydb:flyway-maven-plugin` | Runs your `src/main/resources/db/migration/*.sql` against that container. |
| `generate-sources` | `org.jooq:jooq-codegen-maven` | Introspects the migrated schema and writes Java classes to `target/generated-sources/jooq/`. |
| `prepare-package` | `io.fabric8:docker-maven-plugin` | Stops + removes the container. |

Plugins in the same lifecycle phase run in **declaration order in the POM**. The order above (docker `start`, then flyway `migrate`, then jooq `generate`) is what gets you a migrated schema before codegen runs.

## Runtime classpath vs codegen classpath

The codegen step has to talk to the throwaway container to introspect it — it needs the JDBC driver and (on MySQL/MariaDB) `flyway-mysql`. Those go inside the codegen plugin's own `<dependencies>` block, **not** the project-level `<dependencies>`. Same coordinate, two places:

```xml
<dependencies>
    <!-- The driver on the APPLICATION runtime classpath -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <version>${postgresql.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.jooq</groupId>
            <artifactId>jooq-codegen-maven</artifactId>
            <dependencies>
                <!-- The driver on the CODEGEN PLUGIN's classpath -->
                <dependency>
                    <groupId>org.postgresql</groupId>
                    <artifactId>postgresql</artifactId>
                    <version>${postgresql.version}</version>
                </dependency>
            </dependencies>
        </plugin>
    </plugins>
</build>
```

The plugin classpath is isolated from the project classpath — forgetting the inner `<dependency>` gives `Driver class not found` during `mvn generate-sources`, not at app boot. The Gradle equivalent of this split is the `jooqCodegen("…")` vs `runtimeOnly("…")` configurations.

The `flyway-maven-plugin` has the same shape: project-level `<dependencies>` for the Flyway runtime your app uses programmatically, plus a `<dependencies>` inside the plugin block for `flyway-database-postgresql` / `flyway-mysql` so the plugin itself can recognize the JDBC URL.

## Why these three plugins, not [`testcontainers-jooq-codegen-maven-plugin`](https://github.com/testcontainers/testcontainers-jooq-codegen-maven-plugin)

The testcontainers community plugin bundles all three concerns the same way the Gradle plugin does, and on the surface looks like a cleaner choice. As of May 2026 it's pre-1.0, with its last release from April 2024 and no commits in two years. The three official plugins ship on a regular cadence (fabric8 0.48.1 in Feb 2026, flyway-maven-plugin tracks Flyway core, `jooq-codegen-maven` ships with every jOOQ release), are maintained by independent teams, and can be upgraded independently. The extra ~30 lines of POM is the entire trade-off.

## PostgreSQL

```xml
<properties>
    <fabric8-docker-plugin-version>0.49.0</fabric8-docker-plugin-version>
    <!-- On Quarkus 3.40, both Flyway versions are 12.0.0: its BOM keeps Flyway on 12. -->
    <flyway-maven-plugin-version>13.10.3</flyway-maven-plugin-version>
    <jooq-codegen-maven-plugin-version>3.21.9</jooq-codegen-maven-plugin-version>
    <flyway-version>13.10.3</flyway-version>
    <postgresql.version>42.7.14</postgresql.version>

    <!-- Single source of truth for the codegen container — used by docker (to start it),
         flyway (to migrate), and jooq (to introspect). -->
    <codegen.db.port>15432</codegen.db.port>
    <codegen.db.name>wallet_codegen</codegen.db.name>
    <codegen.db.user>codegen</codegen.db.user>
    <codegen.db.password>codegen</codegen.db.password>
    <codegen.db.url>jdbc:postgresql://localhost:${codegen.db.port}/${codegen.db.name}</codegen.db.url>
</properties>

<build>
    <plugins>
        <!-- 1. Postgres container lifecycle. -->
        <plugin>
            <groupId>io.fabric8</groupId>
            <artifactId>docker-maven-plugin</artifactId>
            <version>${fabric8-docker-plugin-version}</version>
            <configuration>
                <images>
                    <image>
                        <name>postgres:16</name>
                        <alias>postgres-codegen</alias>
                        <run>
                            <ports>
                                <port>${codegen.db.port}:5432</port>
                            </ports>
                            <env>
                                <POSTGRES_DB>${codegen.db.name}</POSTGRES_DB>
                                <POSTGRES_USER>${codegen.db.user}</POSTGRES_USER>
                                <POSTGRES_PASSWORD>${codegen.db.password}</POSTGRES_PASSWORD>
                                <TZ>UTC</TZ>
                            </env>
                            <!-- The postgres image logs "ready to accept connections" twice — once
                                 during the temp init server, once when the real server starts.
                                 Match both occurrences with (?s).* to wait for the real one. -->
                            <wait>
                                <log>(?s)database system is ready to accept connections.*database system is ready to accept connections</log>
                                <time>30000</time>
                            </wait>
                        </run>
                    </image>
                </images>
            </configuration>
            <executions>
                <execution>
                    <id>start-codegen-postgres</id>
                    <phase>initialize</phase>
                    <goals><goal>start</goal></goals>
                </execution>
                <execution>
                    <id>stop-codegen-postgres</id>
                    <phase>prepare-package</phase>
                    <goals><goal>stop</goal></goals>
                </execution>
            </executions>
        </plugin>

        <!-- 2. Run Flyway against the freshly-started container. -->
        <plugin>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-maven-plugin</artifactId>
            <version>${flyway-maven-plugin-version}</version>
            <configuration>
                <url>${codegen.db.url}</url>
                <user>${codegen.db.user}</user>
                <password>${codegen.db.password}</password>
                <schemas>
                    <schema>public</schema>
                    <schema>eventlog</schema>
                </schemas>
                <locations>
                    <location>filesystem:src/main/resources/db/migration</location>
                </locations>
                <placeholderReplacement>false</placeholderReplacement>
            </configuration>
            <dependencies>
                <!-- Flyway 10+ requires the per-dialect plugin module on the build classpath. -->
                <dependency>
                    <groupId>org.flywaydb</groupId>
                    <artifactId>flyway-database-postgresql</artifactId>
                    <version>${flyway-version}</version>
                </dependency>
            </dependencies>
            <executions>
                <execution>
                    <id>codegen-migrate</id>
                    <phase>initialize</phase>
                    <goals><goal>migrate</goal></goals>
                </execution>
            </executions>
        </plugin>

        <!-- 3. Introspect the live schema and generate Java classes. -->
        <plugin>
            <groupId>org.jooq</groupId>
            <artifactId>jooq-codegen-maven</artifactId>
            <version>${jooq-codegen-maven-plugin-version}</version>
            <dependencies>
                <dependency>
                    <groupId>org.postgresql</groupId>
                    <artifactId>postgresql</artifactId>
                    <version>${postgresql.version}</version>
                </dependency>
            </dependencies>
            <executions>
                <execution>
                    <id>jooq-codegen</id>
                    <phase>generate-sources</phase>
                    <goals><goal>generate</goal></goals>
                    <configuration>
                        <jdbc>
                            <driver>org.postgresql.Driver</driver>
                            <url>${codegen.db.url}</url>
                            <user>${codegen.db.user}</user>
                            <password>${codegen.db.password}</password>
                        </jdbc>
                        <generator>
                            <database>
                                <name>org.jooq.meta.postgres.PostgresDatabase</name>
                                <includes>.*</includes>
                                <excludes>flyway_schema_history</excludes>
                                <schemata>
                                    <schema>
                                        <inputSchema>public</inputSchema>
                                        <outputSchema>public_schema</outputSchema>
                                        <outputSchemaToDefault>true</outputSchemaToDefault>
                                    </schema>
                                    <schema>
                                        <inputSchema>eventlog</inputSchema>
                                        <outputSchema>eventlog_schema</outputSchema>
                                    </schema>
                                </schemata>
                                <forcedTypes>
                                    <forcedType>
                                        <userType>java.time.Instant</userType>
                                        <converter>io.ekbatan.core.persistence.jooq.converter.InstantConverter</converter>
                                        <includeTypes>TIMESTAMP</includeTypes>
                                        <includeExpression>.*</includeExpression>
                                    </forcedType>
                                    <forcedType>
                                        <userType>tools.jackson.databind.node.ObjectNode</userType>
                                        <converter>io.ekbatan.core.persistence.jooq.converter.JSONBObjectNodeConverter</converter>
                                        <includeTypes>JSONB</includeTypes>
                                        <includeExpression>.*</includeExpression>
                                    </forcedType>
                                </forcedTypes>
                            </database>
                            <target>
                                <packageName>com.example.generated.jooq</packageName>
                                <directory>${project.build.directory}/generated-sources/jooq</directory>
                            </target>
                        </generator>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

## MariaDB

Three deltas vs. the Postgres block:

1. **Image** — `mariadb:11.8` with `MARIADB_*` env vars.
2. **Driver** — `org.mariadb.jdbc:mariadb-java-client` on both `<dependencies>` (for the codegen plugin's `<dependency>` block) and `flyway-mysql` for Flyway plugin support (despite the name, `flyway-mysql` handles MariaDB).
3. **Forced types** — `(?i:DATETIME|TIMESTAMP)` regex for `InstantConverter`; for JSON, `<name>JSON</name>` plus an `<includeExpression>` naming the column, bound to `JSONObjectNodeConverter` (no `B`) — a type regex cannot match, since MariaDB reports `JSON` columns as `LONGTEXT`. No UUID forced type (MariaDB 10.7+ has native UUID).

The container block:

```xml
<plugin>
    <groupId>io.fabric8</groupId>
    <artifactId>docker-maven-plugin</artifactId>
    <version>${fabric8-docker-plugin-version}</version>
    <configuration>
        <images>
            <image>
                <name>mariadb:11.8</name>
                <alias>mariadb-codegen</alias>
                <run>
                    <ports>
                        <port>${codegen.db.port}:3306</port>
                    </ports>
                    <env>
                        <MARIADB_ROOT_PASSWORD>root</MARIADB_ROOT_PASSWORD>
                        <MARIADB_DATABASE>${codegen.db.name}</MARIADB_DATABASE>
                        <MARIADB_USER>${codegen.db.user}</MARIADB_USER>
                        <MARIADB_PASSWORD>${codegen.db.password}</MARIADB_PASSWORD>
                        <TZ>UTC</TZ>
                    </env>
                    <wait>
                        <log>(?s)ready for connections.*ready for connections</log>
                        <time>60000</time>
                    </wait>
                </run>
            </image>
        </images>
    </configuration>
    <!-- executions same as Postgres -->
</plugin>
```

The Flyway plugin needs cross-database `GRANT`s for the connecting user (so it can `CREATE DATABASE IF NOT EXISTS eventlog` from your `V0000__create_eventlog_database.sql`). Mount the init script into the container:

```xml
<run>
    <!-- … env, ports, wait … -->
    <volumes>
        <bind>
            <volume>${project.basedir}/src/main/resources/mariadb_init.sql:/docker-entrypoint-initdb.d/mariadb_init.sql:ro</volume>
        </bind>
    </volumes>
</run>
```

The forced types in the jOOQ generator block change to:

```xml
<forcedTypes>
    <forcedType>
        <userType>java.time.Instant</userType>
        <converter>io.ekbatan.core.persistence.jooq.converter.InstantConverter</converter>
        <includeTypes>(?i:DATETIME|TIMESTAMP)</includeTypes>
        <includeExpression>.*</includeExpression>
    </forcedType>
    <!-- MariaDB reports JSON as LONGTEXT, so codegen sees CLOB. <name>JSON</name> rewrites the
         generated type back so the converter can attach, and the column is matched by name
         because the reported type is not "JSON". -->
    <forcedType>
        <name>JSON</name>
        <userType>tools.jackson.databind.node.ObjectNode</userType>
        <converter>io.ekbatan.core.persistence.jooq.converter.JSONObjectNodeConverter</converter>
        <includeExpression>.*\.my_json_column</includeExpression>
    </forcedType>
</forcedTypes>
```

For the full dialect background — why `(?i:DATETIME|TIMESTAMP)`, why `JSONObjectNodeConverter` (no `B`), the MariaDB JSON-is-internally-LONGTEXT note — see [docs/database/mariadb.md](../database/mariadb.md).

## MySQL

Identical to MariaDB except the image, env, driver, and one **additional** `<forcedType>` for `CHAR(36)` → `UUID` (MySQL has no native UUID type):

```xml
<image>
    <name>mysql:9.4.0</name>
    <alias>mysql-codegen</alias>
    <run>
        <env>
            <MYSQL_ROOT_PASSWORD>root</MYSQL_ROOT_PASSWORD>
            <MYSQL_DATABASE>${codegen.db.name}</MYSQL_DATABASE>
            <MYSQL_USER>${codegen.db.user}</MYSQL_USER>
            <MYSQL_PASSWORD>${codegen.db.password}</MYSQL_PASSWORD>
            <TZ>UTC</TZ>
        </env>
        <!-- ports, volumes, wait same shape as MariaDB -->
    </run>
</image>
```

Driver coordinates:

```xml
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <version>${mysql-connector-version}</version>
</dependency>
```

Extra forced type:

```xml
<forcedType>
    <userType>java.util.UUID</userType>
    <converter>io.ekbatan.core.persistence.jooq.converter.mysql.UuidStringConverter</converter>
    <includeTypes>CHAR\(36\)</includeTypes>
    <!-- Narrow scope to columns named `id` or ending in `_id` — without this, unrelated CHAR(36)
         columns (handler names, status enums, etc.) would also get bound to UUID and break. -->
    <includeExpression>.*\.id|.*_id</includeExpression>
</forcedType>
```

See [docs/database/mysql.md](../database/mysql.md) for why `CHAR(36)` over `BINARY(16)` and the full column-type background.

## ForcedType reference

| Column type | Java type | Converter | Used for |
|---|---|---|---|
| `TIMESTAMP` (PG) / `DATETIME`+`TIMESTAMP` (MariaDB/MySQL) | `java.time.Instant` | `io.ekbatan.core.persistence.jooq.converter.InstantConverter` | Every timestamped framework column (`created_date`, `updated_date`, etc.) |
| `JSONB` (PG) | `tools.jackson.databind.node.ObjectNode` | `io.ekbatan.core.persistence.jooq.converter.JSONBObjectNodeConverter` | Event payloads, custom JSON columns |
| `JSON` (MariaDB/MySQL) | `tools.jackson.databind.node.ObjectNode` | `io.ekbatan.core.persistence.jooq.converter.JSONObjectNodeConverter` (no `B`) | Same as above |
| `CHAR(36)` (MySQL only, `id` / `*_id` only) | `java.util.UUID` | `io.ekbatan.core.persistence.jooq.converter.mysql.UuidStringConverter` | Every UUID column on MySQL |

The Postgres-side `JSONBObjectNodeConverter` and the MariaDB/MySQL-side `JSONObjectNodeConverter` are *different* classes — note the `B`. They differ in how they bind to the JDBC driver (`PGObject` on Postgres, plain `String` on MariaDB/MySQL).

For the dialect-level explanation — *why* each forced type is the way it is — see [PostgreSQL setup](../database/postgresql.md), [MariaDB setup](../database/mariadb.md), [MySQL setup](../database/mysql.md).

## Container init scripts (MariaDB/MySQL)

If your `V0000__create_eventlog_database.sql` does a cross-database `CREATE DATABASE eventlog`, the connecting user needs the privilege. The fabric8 plugin's codegen container connects as `root` (per the `MARIADB_ROOT_PASSWORD` / `MYSQL_ROOT_PASSWORD` env vars in the example), so codegen works. **Test containers** spun up by Testcontainers in your `@Test` classes typically connect as a less-privileged user and need an init script that grants the privilege before that user logs in.

Put the script in `src/main/resources/<dialect>_init.sql`:

```sql
-- mariadb_init.sql
GRANT ALL PRIVILEGES ON *.* TO 'wallet'@'%';
FLUSH PRIVILEGES;
```

…and either:

- **Codegen container** (fabric8): bind-mount it via `<volumes><bind><volume>…</volume></bind></volumes>` on the `<run>` block (the MariaDB section above shows the shape).
- **Test container** (Testcontainers): `withCopyFileToContainer(MountableFile.forClasspathResource("mariadb_init.sql"), "/docker-entrypoint-initdb.d/mariadb_init.sql")`.

The container's entrypoint runs every `.sql` in `/docker-entrypoint-initdb.d/` as root before the DB becomes ready. See [Schema vs database](../database/multi-database.md#schema-vs-database) for the full grant idiom.

## Troubleshooting

### The schema-to-package mapping is more limited than Gradle's

The Gradle plugin lets you do two things at once:

```kotlin
outputSchemaToDefault.add("public")              // SQL: no schema qualifier in generated table refs
schemaToPackageMapping.put("public", "public_schema")  // Java: classes go to `<base>.public_schema.tables.*`
```

The Maven plugin's per-schema `<outputSchemaToDefault>true</outputSchemaToDefault>` **also** forces the Java package to the literal `default_schema` — it doesn't honor an explicit `<outputSchema>public_schema</outputSchema>` set in the same block. So your two choices on Maven are:

1. `<outputSchemaToDefault>true</outputSchemaToDefault>` (no `<outputSchema>`) → SQL is clean (`SELECT * FROM wallets`), but generated classes land under `<base>.default_schema.tables.*`. Your Java imports must use `default_schema`.
2. `<outputSchema>public_schema</outputSchema>` (no `outputSchemaToDefault`) → Java package is `public_schema`, but generated SQL becomes `SELECT * FROM public_schema.wallets`, which fails at runtime because that schema doesn't exist in Postgres.

Pick option 1. The runnable example uses `default_schema` in the Java imports; that's the mechanical difference from the Gradle sibling. The eventlog schema uses option 2 (no `outputSchemaToDefault`) because the framework explicitly qualifies its outbox writes with the eventlog schema — there, the package name doubles as the SQL schema name, which is what you want.

### `flyway.*` Maven properties collide with the plugin

`flyway-maven-plugin` scans system properties at startup and rejects any `flyway.*` key it doesn't recognize as a Flyway config. Use hyphenated names — never `<flyway.maven.plugin.version>`, always `<flyway-maven-plugin-version>`. Same caution for `jooq.*`, `quarkus.*`, and most plugin namespaces. (Covered in [getting-started.md → Maven-property namespace pitfalls](getting-started.md#maven-property-namespace-pitfalls).)

### Don't set `<autoRemove>true</autoRemove>` on the fabric8 `<run>` block

When `autoRemove=true` is set on the container `<run>` config, Docker schedules the container for removal on stop. The `stop` mojo then also tries to remove it explicitly, and you get a 409 race:

```
Unable to remove container [abc123…]: removal of container abc123… is already in progress
```

Leave `autoRemove` off — the `stop` mojo cleans up by default.

### Postgres logs "ready to accept connections" twice

The official `postgres:*` Docker images log that message once during the temp-server init phase and once when the real server starts. If you wait on a single occurrence, Flyway may try to connect during the transition window and fail. The `(?s)…ready to accept connections.*ready to accept connections` regex above matches the second occurrence — which is the one you actually want.

MariaDB / MySQL log `ready for connections` twice the same way; the same `(?s)…X.*X` idiom applies.

### `Driver class not found` during `mvn generate-sources`

The JDBC driver is missing from the `jooq-codegen-maven` plugin's own `<dependencies>` block (not the project-level `<dependencies>`). The plugin classpath is isolated — adding the driver to only one of the two places fails differently. Re-read [Runtime classpath vs codegen classpath](#runtime-classpath-vs-codegen-classpath) above.

The Flyway plugin has the same shape: `flyway-database-postgresql` or `flyway-mysql` must be inside `flyway-maven-plugin`'s `<dependencies>`, or Flyway aborts with "no Flyway database plugin found for the JDBC URL".

### Generated classes don't show up on the compile classpath

The jOOQ codegen plugin writes into `target/generated-sources/jooq/`. Maven only treats a directory as a source root if a plugin tells it to — `jooq-codegen-maven` does this automatically via its `<addProjectClasspath>` / `<addJaxbDependenciesToClasspath>` magic for most configurations, but in some setups (custom `<sourceDirectory>` overrides, packaging plugins that reset source roots) it doesn't take.

If you see `cannot find symbol: class Wallets` after `mvn generate-sources` ran cleanly, force the source root via `build-helper-maven-plugin`:

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>build-helper-maven-plugin</artifactId>
    <version>3.6.0</version>
    <executions>
        <execution>
            <id>add-jooq-source</id>
            <phase>generate-sources</phase>
            <goals><goal>add-source</goal></goals>
            <configuration>
                <sources>
                    <source>${project.build.directory}/generated-sources/jooq</source>
                </sources>
            </configuration>
        </execution>
    </executions>
</plugin>
```

IntelliJ picks this up after a Maven re-import.

### `cannot find symbol: class WalletsRecord` after a migration change

The codegen step is *incremental* — both fabric8 (skips the container restart if a container with the same alias is running) and `jooq-codegen-maven` (skips if the output is newer than the inputs it tracks). Adding a column to a migration doesn't always invalidate the cache they look at. Force a clean codegen:

```bash
./mvnw clean
./mvnw generate-sources
```

If the container is still running from a previous build (`docker ps | grep codegen`), `./mvnw clean` doesn't stop it. Either let the `prepare-package` stop step run (`./mvnw verify`), or `docker stop <alias>` it manually. A stale container with the *old* schema is the most common cause of "I just added a column, where is it" confusion.

### MariaDB / MySQL: `Access denied for user 'wallet'` during Flyway

The codegen container connects as root by configuration. If you see this error during Flyway's `migrate` phase, the `<user>` in `flyway-maven-plugin`'s `<configuration>` was pointed at an unprivileged user. Either point it at root (the codegen container has no real security concern), or add a `mariadb_init.sql` / `mysql_init.sql` that grants the necessary privileges — see [Container init scripts](#container-init-scripts-mariadbmysql) above.

### Postgres: `database "eventlog" does not exist`

For Postgres, `eventlog` is a *schema* (not a database) — it gets created by `V0001__eventlog.sql`'s `CREATE SCHEMA IF NOT EXISTS eventlog`. If you see this on Postgres, check that V0001 ran. (On MariaDB/MySQL, `eventlog` *is* a separate database created by `V0000__create_eventlog_database.sql` — different idiom, see [Schema vs database](../database/multi-database.md#schema-vs-database).)

## Adding to an existing Maven project

If you already have a Maven project and want to add Ekbatan + codegen:

1. Add the three `<plugin>` blocks above to your `<build><plugins>` section.
2. Add the matching `<properties>` entries (database port/user/password, plugin versions, Flyway version).
3. Make sure your Flyway migrations live in `src/main/resources/db/migration/` and follow the naming convention (`V<n>__<description>.sql`).
4. Run `./mvnw clean compile` and check `target/generated-sources/jooq/` — that's where your `Wallets.java`, `WalletsRecord.java`, etc. land.
5. If you're using an IDE, mark `target/generated-sources/jooq` as a source root (IntelliJ does this automatically via the `build-helper-maven-plugin` if you add `<addSources>` to it; alternatively just refresh the Maven project after the first generate).

## See also

- [Getting started with Maven](getting-started.md) — the rest of the Maven surface (POM structure, annotation processor wiring, `-parameters`, Spring Boot BOM override)
- [docs/gradle/jooq-codegen.md](../gradle/jooq-codegen.md) — the Gradle-flavored equivalent of this page
- [docs/database/postgresql.md](../database/postgresql.md) / [mariadb.md](../database/mariadb.md) / [mysql.md](../database/mysql.md) — per-dialect column types, framework tables, gotchas
- [docs/database/multi-database.md](../database/multi-database.md) — cross-dialect concept layer (which converter for which type)
- [`ekbatan-examples/spring-boot-wallet-rest-maven-pg/pom.xml`](../../ekbatan-examples/spring-boot-wallet-rest-maven-pg/pom.xml) — the runnable Spring-Boot + Postgres reference; every PG snippet on this page comes from it
- [`ekbatan-examples/spring-boot-wallet-rest-maven-mariadb/pom.xml`](../../ekbatan-examples/spring-boot-wallet-rest-maven-mariadb/pom.xml) — Spring-Boot + MariaDB
- [`ekbatan-examples/spring-boot-wallet-rest-maven-mysql/pom.xml`](../../ekbatan-examples/spring-boot-wallet-rest-maven-mysql/pom.xml) — Spring-Boot + MySQL
- [`ekbatan-examples/quarkus-wallet-rest-maven-pg/pom.xml`](../../ekbatan-examples/quarkus-wallet-rest-maven-pg/pom.xml) — Quarkus + Postgres
- [`ekbatan-examples/quarkus-wallet-rest-maven-mariadb/pom.xml`](../../ekbatan-examples/quarkus-wallet-rest-maven-mariadb/pom.xml) — Quarkus + MariaDB
- [`ekbatan-examples/quarkus-wallet-rest-maven-mysql/pom.xml`](../../ekbatan-examples/quarkus-wallet-rest-maven-mysql/pom.xml) — Quarkus + MySQL
- [`ekbatan-examples/micronaut-wallet-rest-maven-pg/pom.xml`](../../ekbatan-examples/micronaut-wallet-rest-maven-pg/pom.xml) — Micronaut + Postgres
- [`ekbatan-examples/micronaut-wallet-rest-maven-mariadb/pom.xml`](../../ekbatan-examples/micronaut-wallet-rest-maven-mariadb/pom.xml) — Micronaut + MariaDB
- [`ekbatan-examples/micronaut-wallet-rest-maven-mysql/pom.xml`](../../ekbatan-examples/micronaut-wallet-rest-maven-mysql/pom.xml) — Micronaut + MySQL
- [`ekbatan-examples/micronaut-wallet-rest-maven-native-pg/pom.xml`](../../ekbatan-examples/micronaut-wallet-rest-maven-native-pg/pom.xml) — Micronaut + Postgres + GraalVM native-image
- [`ekbatan-examples/micronaut-wallet-rest-maven-native-mariadb/pom.xml`](../../ekbatan-examples/micronaut-wallet-rest-maven-native-mariadb/pom.xml) — Micronaut + MariaDB + GraalVM
- [`ekbatan-examples/micronaut-wallet-rest-maven-native-mysql/pom.xml`](../../ekbatan-examples/micronaut-wallet-rest-maven-native-mysql/pom.xml) — Micronaut + MySQL + GraalVM

← Back to [Maven](README.md) · [docs index](../README.md)
