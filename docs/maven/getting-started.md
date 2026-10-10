# Getting started with Maven

What you write in a `pom.xml` to consume Ekbatan from Maven Central, on any of the supported stacks. Mirrors the Java-level setup in [`wiring/spring.md`](../wiring/spring.md), [`wiring/quarkus.md`](../wiring/quarkus.md), and [`wiring/micronaut.md`](../wiring/micronaut.md) — the per-stack pages cover the DI wiring; this page covers the build descriptor.

For the Gradle equivalent, see [Getting started with Gradle](../gradle/getting-started.md). For jOOQ codegen wiring, jump to [jOOQ codegen on Maven](jooq-codegen.md) — that part is involved enough to deserve its own page.

## Minimum `pom.xml`

Pick the block for your stack. Everything below — the compiler flag, the codegen plugin chain, the property pitfalls, the wrapper — is identical across stacks.

### Spring Boot

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>my-service</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.6</version>
    </parent>

    <properties>
        <java.version>25</java.version>
        <maven.compiler.release>25</maven.compiler.release>

        <!-- (1) Spring Boot 4.0.x's BOM pins jOOQ to 3.19.x; Ekbatan needs 3.20.x. -->
        <jooq.version>3.20.10</jooq.version>

        <ekbatan.version>1.1.2</ekbatan.version>
    </properties>

    <dependencies>
        <!-- (2) One starter — pulls ekbatan-core, the local-event-handler,
             and distributed-jobs transitively. -->
        <dependency>
            <groupId>io.github.ekbatan-io</groupId>
            <artifactId>ekbatan-spring-boot-starter</artifactId>
            <version>${ekbatan.version}</version>
        </dependency>
        <dependency>
            <groupId>io.github.ekbatan-io</groupId>
            <artifactId>ekbatan-flyway</artifactId>
            <version>${ekbatan.version}</version>
        </dependency>
        <!-- (3) @AutoBuilder is compile-time only: provided lets javac see
             the annotation without packaging the processor at runtime. -->
        <dependency>
            <groupId>io.github.ekbatan-io</groupId>
            <artifactId>ekbatan-annotation-processor</artifactId>
            <version>${ekbatan.version}</version>
            <scope>provided</scope>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <!-- Flyway stays on the classpath. Ekbatan's Spring starter filters Boot's
             single-datasource auto-run; EkbatanShardFlywayMigrator calls FlywayMigrator. -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <release>${maven.compiler.release}</release>
                    <!-- (4) -parameters is required: the framework reflects over
                         constructor parameter names (@AutoBuilder + Jackson 3
                         record deserialization). -->
                    <parameters>true</parameters>
                    <!-- (3) @AutoBuilder dual-path, part 2: AP classpath so the
                         processor actually runs and emits *Builder.java. -->
                    <annotationProcessorPaths>
                        <path>
                            <groupId>io.github.ekbatan-io</groupId>
                            <artifactId>ekbatan-annotation-processor</artifactId>
                            <version>${ekbatan.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>

            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>

            <!-- (7) jOOQ codegen plugins go here — see jooq-codegen.md. -->
        </plugins>
    </build>
</project>
```

### Quarkus

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>my-service</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.release>25</maven.compiler.release>

        <!-- (1) Pin jOOQ to match the codegen plugin version, even though Quarkus' BOM
             doesn't pin jOOQ — the failure mode if you skip this is the same
             NoSuchMethodError as on Spring Boot. -->
        <jooq.version>3.20.10</jooq.version>

        <ekbatan.version>1.1.2</ekbatan.version>
        <quarkus.platform.version>3.34.6</quarkus.platform.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.quarkus.platform</groupId>
                <artifactId>quarkus-bom</artifactId>
                <version>${quarkus.platform.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- (2) Quarkus extension — pulls ekbatan-core + handlers + jobs. -->
        <dependency>
            <groupId>io.github.ekbatan-io</groupId>
            <artifactId>ekbatan-quarkus</artifactId>
            <version>${ekbatan.version}</version>
        </dependency>
        <dependency>
            <groupId>io.github.ekbatan-io</groupId>
            <artifactId>ekbatan-flyway</artifactId>
            <version>${ekbatan.version}</version>
        </dependency>
        <!-- (3) @AutoBuilder is compile-time only. -->
        <dependency>
            <groupId>io.github.ekbatan-io</groupId>
            <artifactId>ekbatan-annotation-processor</artifactId>
            <version>${ekbatan.version}</version>
            <scope>provided</scope>
        </dependency>

        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest-jackson</artifactId>
        </dependency>
        <!-- Quarkus datasource/Flyway startup config is not used here; the startup
             bean calls FlywayMigrator from ekbatan.sharding.*. These dependencies
             keep Flyway and the driver native-image integration available. -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-jdbc-mariadb</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>${quarkus.platform.group-id}</groupId>
                <artifactId>quarkus-maven-plugin</artifactId>
                <version>${quarkus.platform.version}</version>
                <extensions>true</extensions>
                <executions>
                    <execution>
                        <goals>
                            <goal>build</goal>
                            <goal>generate-code</goal>
                            <goal>generate-code-tests</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>

            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <release>${maven.compiler.release}</release>
                    <!-- (4) Required for the framework's reflection on parameter names. -->
                    <parameters>true</parameters>
                    <!-- (3) @AutoBuilder dual-path, part 2.  -->
                    <annotationProcessorPaths>
                        <path>
                            <groupId>io.github.ekbatan-io</groupId>
                            <artifactId>ekbatan-annotation-processor</artifactId>
                            <version>${ekbatan.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>

            <!-- (7) jOOQ codegen plugins go here — see jooq-codegen.md. -->
        </plugins>
    </build>
</project>
```

### Micronaut

The Micronaut parent POM pre-configures `maven-compiler-plugin` with `micronaut-inject-java` already on the annotation-processor path. **You append two more entries (Ekbatan's AP jar and `ekbatan-micronaut`) using `combine.children="append"`** — without that, Maven config merging *replaces* the parent's `<annotationProcessorPaths>` and you lose the inherited `micronaut-inject-java` line.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>my-service</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <parent>
        <groupId>io.micronaut.platform</groupId>
        <artifactId>micronaut-parent</artifactId>
        <version>4.10.7</version>
    </parent>

    <properties>
        <maven.compiler.release>25</maven.compiler.release>
        <jooq.version>3.20.10</jooq.version>
        <ekbatan.version>1.1.2</ekbatan.version>
        <micronaut.version>4.10.7</micronaut.version>
        <micronaut.runtime>netty</micronaut.runtime>
        <exec.mainClass>com.example.Application</exec.mainClass>
    </properties>

    <dependencies>
        <!-- (2) Integration jar — pulls ekbatan-core + handlers + jobs. -->
        <dependency>
            <groupId>io.github.ekbatan-io</groupId>
            <artifactId>ekbatan-micronaut</artifactId>
            <version>${ekbatan.version}</version>
        </dependency>
        <dependency>
            <groupId>io.github.ekbatan-io</groupId>
            <artifactId>ekbatan-flyway</artifactId>
            <version>${ekbatan.version}</version>
        </dependency>
        <!-- (3) @AutoBuilder is compile-time only. -->
        <dependency>
            <groupId>io.github.ekbatan-io</groupId>
            <artifactId>ekbatan-annotation-processor</artifactId>
            <version>${ekbatan.version}</version>
            <scope>provided</scope>
        </dependency>

        <dependency>
            <groupId>io.micronaut</groupId>
            <artifactId>micronaut-jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micronaut.serde</groupId>
            <artifactId>micronaut-serde-jackson</artifactId>
        </dependency>
        <!-- micronaut-flyway stays on the classpath for Flyway/native support, but
             the eager @Context bean calls FlywayMigrator itself. -->
        <dependency>
            <groupId>io.micronaut.flyway</groupId>
            <artifactId>micronaut-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <release>${maven.compiler.release}</release>
                    <!-- (4) Required for the framework's reflection on parameter names. -->
                    <parameters>true</parameters>
                    <!-- (3) + (5) The Micronaut parent already lists micronaut-inject-java
                         here; combine.children="append" KEEPS that entry and APPENDS ours.
                         Without "append", Maven replaces the inherited list and Micronaut's
                         own AP stops running — your @Controller etc. silently lose their
                         BeanDefinitions. -->
                    <annotationProcessorPaths combine.children="append">
                        <path>
                            <groupId>io.github.ekbatan-io</groupId>
                            <artifactId>ekbatan-annotation-processor</artifactId>
                            <version>${ekbatan.version}</version>
                        </path>
                        <!-- (5) Micronaut-specific: the EkbatanStereotypeVisitor lives in
                             ekbatan-micronaut and only runs on classes compiled with this
                             jar on the AP classpath. Without it, your @EkbatanAction /
                             @EkbatanRepository classes don't get lifted to @Singleton and
                             Micronaut generates no BeanDefinitions for them — runtime then
                             fails with UnsatisfiedDependencyException. -->
                        <path>
                            <groupId>io.github.ekbatan-io</groupId>
                            <artifactId>ekbatan-micronaut</artifactId>
                            <version>${ekbatan.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>

            <plugin>
                <groupId>io.micronaut.maven</groupId>
                <artifactId>micronaut-maven-plugin</artifactId>
            </plugin>

            <!-- (7) jOOQ codegen plugins go here — see jooq-codegen.md. -->
        </plugins>
    </build>
</project>
```

### Plain Java (no DI container)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>my-service</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.release>25</maven.compiler.release>
        <ekbatan.version>1.1.2</ekbatan.version>
    </properties>

    <dependencies>
        <!-- Required ----------------------------------------------------- -->
        <dependency>
            <groupId>io.github.ekbatan-io</groupId>
            <artifactId>ekbatan-core</artifactId>
            <version>${ekbatan.version}</version>
        </dependency>
        <dependency>
            <groupId>io.github.ekbatan-io</groupId>
            <artifactId>ekbatan-flyway</artifactId>
            <version>${ekbatan.version}</version>
        </dependency>

        <!-- (3) @AutoBuilder is compile-time only. Skip if you'd rather write
             builders by hand; without it, drop the AP path below too. -->
        <dependency>
            <groupId>io.github.ekbatan-io</groupId>
            <artifactId>ekbatan-annotation-processor</artifactId>
            <version>${ekbatan.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- (6) Optional capabilities — add only what you need ----------- -->

        <!-- In-process event handlers (fanout + handling jobs over the eventlog). -->
        <dependency>
            <groupId>io.github.ekbatan-io</groupId>
            <artifactId>ekbatan-local-event-handler</artifactId>
            <version>${ekbatan.version}</version>
        </dependency>

        <!-- Distributed background jobs (db-scheduler facade). -->
        <dependency>
            <groupId>io.github.ekbatan-io</groupId>
            <artifactId>ekbatan-distributed-jobs</artifactId>
            <version>${ekbatan.version}</version>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
            <version>12.0.0</version>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
            <version>12.0.0</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <release>${maven.compiler.release}</release>
                    <parameters>true</parameters>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>io.github.ekbatan-io</groupId>
                            <artifactId>ekbatan-annotation-processor</artifactId>
                            <version>${ekbatan.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>

            <!-- (7) jOOQ codegen plugins go here — see jooq-codegen.md. -->
        </plugins>
    </build>
</project>
```

Each numbered item below is the reason these POMs differ from a vanilla Spring Boot / Quarkus / Micronaut starter project.

## (1) Override the jOOQ version on Spring Boot

Spring Boot 4.0.x's BOM pins `jooq.version=3.19.x`. Ekbatan compiles against jOOQ 3.20.x and the generated record classes use 3.20-only APIs. If the BOM wins, runtime fails with `NoSuchMethodError` the first time Ekbatan code calls a 3.20 method.

The fix is one line under `<properties>`:

```xml
<jooq.version>3.20.10</jooq.version>
```

The property name must match exactly (`jooq.version`, lowercase, single dot) — that's Spring Boot's convention for BOM overrides, not an Ekbatan invention.

The Quarkus BOM doesn't pin jOOQ, but the codegen plugin's default may not match what Ekbatan was built against. Pin explicitly anyway; the cost is one line. Micronaut's BOM also doesn't pin jOOQ. Plain Java has no BOM and gets jOOQ transitively from `ekbatan-core` — no override needed.

## (2) The integration dependency

| Stack | Coordinate | Pulls transitively |
|---|---|---|
| Spring Boot | `io.github.ekbatan-io:ekbatan-spring-boot-starter` | `ekbatan-core`, `ekbatan-local-event-handler`, `ekbatan-distributed-jobs` |
| Quarkus | `io.github.ekbatan-io:ekbatan-quarkus` | same set |
| Micronaut | `io.github.ekbatan-io:ekbatan-micronaut` | same set |
| Plain Java | `io.github.ekbatan-io:ekbatan-core` (plus each optional module explicitly) | nothing transitively |

The four `@Ekbatan*` annotations (`@EkbatanAction`, `@EkbatanRepository`, `@EkbatanEventHandler`, `@EkbatanDistributedJob`) live in `ekbatan-di-annotations` and come with the integration jars. You don't add them explicitly.

## (3) The annotation processor dual-path

The `@AutoBuilder` annotation (used on every `Model` / `Entity`) lives in `ekbatan-annotation-processor`. Keep it compile-time only by putting it on **both** of these paths:

- A `<dependency>` with `<scope>provided</scope>` — so javac sees the `@AutoBuilder` symbol without adding the jar at runtime.
- An `<annotationProcessorPaths>` `<path>` — so the processor actually runs and emits `*Builder.java`.

These aren't redundant; `<annotationProcessorPaths>` is isolated from the main compile classpath. Dropping one or the other breaks differently:

- Without the provided `<dependency>` → compile fails with `cannot find symbol: class AutoBuilder`.
- Without the `<annotationProcessorPaths>` entry → compile succeeds, the processor never runs, you find out at the first reference to `WalletBuilder.wallet()` (`cannot find symbol: class WalletBuilder`).

The processor runs as part of normal compilation — any goal that triggers `javac` (`compile`, `package`, `install`, `test`) also runs it. There is no separate code-generation goal. The generated `*Builder.java` files land under `target/generated-sources/annotations/`.

This mirrors Gradle's `compileOnly` + `annotationProcessor` dual declaration.

## (4) `-parameters` is mandatory

Ekbatan reflects over constructor *parameter names* for two things: the `@AutoBuilder` processor names builder methods after them, and Jackson 3's `RecordsModule` uses them to bind JSON fields to record components. Without `-parameters` the bytecode stores them as `arg0`, `arg1`, … and the runtime fails with "no setter for property X" or "missing required field arg0".

Maven's default is off; turn it on:

```xml
<configuration>
    <parameters>true</parameters>
</configuration>
```

Same requirement applies on Gradle (`options.compilerArgs.add("-parameters")`).

## (5) Micronaut needs `ekbatan-micronaut` on the AP path

This one bites. The `ekbatan-micronaut` jar ships an `EkbatanStereotypeVisitor` that hooks into Micronaut's compile-time annotation processor and lifts your `@Ekbatan*`-annotated classes to `@Singleton`. **The visitor only runs on classes compiled with the jar on the annotation-processor classpath.**

```xml
<annotationProcessorPaths combine.children="append">
    <!-- @AutoBuilder, like every other stack -->
    <path>
        <groupId>io.github.ekbatan-io</groupId>
        <artifactId>ekbatan-annotation-processor</artifactId>
        <version>${ekbatan.version}</version>
    </path>
    <!-- Micronaut-specific: the EkbatanStereotypeVisitor -->
    <path>
        <groupId>io.github.ekbatan-io</groupId>
        <artifactId>ekbatan-micronaut</artifactId>
        <version>${ekbatan.version}</version>
    </path>
</annotationProcessorPaths>
```

Two things to notice:

- `combine.children="append"` keeps the parent POM's inherited `micronaut-inject-java` entry. Without it, Maven config merging replaces the parent's list, Micronaut's own processor stops running, and your `@Controller`s lose their `BeanDefinition`s.
- `ekbatan-micronaut` is a *runtime* dependency too (the `Configuration` factory classes live there), so it's listed under `<dependencies>` *and* under `<annotationProcessorPaths>`. Same dual-path pattern as `@AutoBuilder`.

If you forget the `<annotationProcessorPaths>` `<path>` for `ekbatan-micronaut`:

- Build succeeds.
- Runtime: Micronaut finds no `BeanDefinition` for your `@EkbatanAction` / `@EkbatanRepository` / `@EkbatanEventHandler` / `@EkbatanDistributedJob` classes.
- You get `UnsatisfiedDependencyException` (for repositories injected into actions) or "no candidates for `WalletDepositAction`" the first time `ActionExecutor.execute(...)` runs.

Spring Boot and Quarkus don't need a separate AP-path entry for their integration jars — Spring uses runtime classpath scan + AOT, Quarkus uses Jandex at deployment phase. Micronaut is the one stack that does all its DI work at compile time.

## (6) Optional add-ons

The integration jars (`ekbatan-spring-boot-starter` / `ekbatan-quarkus` / `ekbatan-micronaut`) pull the common runtime modules such as the local event handler and distributed jobs. `ekbatan-annotation-processor` stays explicit and compile-time only. The following are deliberately *not* pulled; add them only when you need them:

```xml
<!-- Programmatic Flyway migration from one DataSource or every primary shard in ShardingConfig. -->
<dependency>
    <groupId>io.github.ekbatan-io</groupId>
    <artifactId>ekbatan-flyway</artifactId>
    <version>${ekbatan.version}</version>
</dependency>

<!-- Redis-backed distributed KeyedLockProvider (Redisson under the hood). -->
<dependency>
    <groupId>io.github.ekbatan-io</groupId>
    <artifactId>ekbatan-keyed-lock-redis</artifactId>
    <version>${ekbatan.version}</version>
</dependency>

<!-- GraalVM native-image Features (auto-loaded; include only if you build native binaries). -->
<dependency>
    <groupId>io.github.ekbatan-io</groupId>
    <artifactId>ekbatan-native</artifactId>
    <version>${ekbatan.version}</version>
</dependency>

<!-- ActionSpec, ActionAssert, VirtualClock, and classpath-resource Testcontainers helpers. -->
<dependency>
    <groupId>io.github.ekbatan-io</groupId>
    <artifactId>ekbatan-test-support</artifactId>
    <version>${ekbatan.version}</version>
    <scope>test</scope>
</dependency>

<!-- Wire-format DTOs for Kafka consumer apps reading from the eventlog. Pick the
     one matching your Kafka serializer; NOT needed in the producer app. -->
<dependency>
    <groupId>io.github.ekbatan-io</groupId>
    <artifactId>ekbatan-action-event-json</artifactId>
    <version>${ekbatan.version}</version>
</dependency>
<!-- (or -avro, or -protobuf — pick one or coexist) -->
```

The wire-format jars are "not needed in the producer app" because the framework writes events to the outbox using its own internal payload format — the wire-format DTOs are POJOs you deserialize *back into* on the consumer side (whatever app reads Kafka or polls the eventlog from outside the producing JVM).

## (7) jOOQ codegen plugins

The Gradle examples use one plugin (`dev.monosoul.jooq-docker`) that bundles three concerns. The Maven equivalent is three plugins chained in lifecycle order — `io.fabric8:docker-maven-plugin` (starts the container) → `org.flywaydb:flyway-maven-plugin` (migrates) → `org.jooq:jooq-codegen-maven` (introspects + generates). See [jOOQ codegen on Maven](jooq-codegen.md) for the full chain.

## Application config

The runtime configuration is the same as on Gradle — `ekbatan.*` keys in `application.yml` (or `application.properties` if you prefer):

```yaml
ekbatan:
  namespace: com.example.wallets

  local-event-handler:
    handling:
      enabled: true

  sharding:
    defaultShard:
      group: 0
      member: 0
    groups:
      - group: 0
        name: default
        members:
          - member: 0
            configs:
              primaryConfig:
                jdbcUrl: jdbc:postgresql://primary:5432/wallets
                username: wallets_app
                password: ${APP_DB_PASSWORD}
                maximumPoolSize: 20
              jobsConfig:
                jdbcUrl: jdbc:postgresql://primary:5432/wallets
                username: wallets_app
                password: ${APP_DB_PASSWORD}
                maximumPoolSize: 5
```

The structure is documented in [docs/database/sharding.md](../database/sharding.md) and the [Wiring with Spring Boot](../wiring/spring.md#4-the-configuration) page; every field of a datasource entry - including `data-source-properties` for extra driver settings - in [Connecting to the database](../database/connecting.md).

**Time zones.** Ekbatan writes every timestamp as UTC itself, so what it stores is right whatever zone the database or the JVM runs in, and no time-zone setting goes in the URL. A zone does change the database clock your own SQL reads (`NOW()`, `CURRENT_TIMESTAMP`): on PostgreSQL it follows the JVM's zone, since the driver sets each session to it, so run the JVM in UTC (`-Duser.timezone=UTC`); on MySQL and MariaDB it follows the server's, so keep the server in UTC or set `sessionVariables: "time_zone='+00:00'"` under [`data-source-properties`](../database/connecting.md#data-source-properties). Not MySQL's `serverTimezone`: with the JVM outside UTC it shifts what is stored. See [Always UTC](../database/multi-database.md#always-utc).

The examples in this Maven page use camelCase, but the DI integrations also accept kebab-case: `default-shard` / `defaultShard`, `primary-config` / `primaryConfig`, `jobs-config` / `jobsConfig`, `lock-config` / `lockConfig`, and datasource leaves like `jdbc-url` / `jdbcUrl`. If Java code later reads a user-defined datasource via `member.configFor(...)`, use the camelCase key (`jobsConfig`, `lockConfig`), not the kebab-case spelling.

## Maven-property namespace pitfalls

A few `<properties>` keys conflict with plugin behavior. None of them is Ekbatan-specific, but they bite hard if you don't know.

### Avoid `flyway.*` keys

`flyway-maven-plugin` scans all system properties at startup and tries to interpret any key matching `flyway.*` as a Flyway configuration property. If you have:

```xml
<properties>
    <flyway.maven.plugin.version>12.0.0</flyway.maven.plugin.version>
</properties>
```

…the plugin sees `flyway.maven.plugin.version`, doesn't recognize it as a known Flyway config key, and aborts with:

```
Unknown configuration property: flyway.maven.plugin.version
```

Fix: use hyphenated names that don't start with `flyway.`:

```xml
<flyway-version>12.0.0</flyway-version>
<flyway-maven-plugin-version>12.0.0</flyway-maven-plugin-version>
```

The same caution applies to `jooq.*` (the codegen plugin scans its own namespace), `quarkus.*`, and most plugin namespaces. Hyphenated property names are the safe default.

### Don't use `<id>local</id>` for `<repositories>`

Maven reserves the literal id `local` for the local-repository configuration in `settings.xml`. Using it for a `<repositories>` entry triggers:

```
'repositories.repository.id' must not be 'local', this identifier is reserved for the local repository.
```

If you're enabling `~/.m2/repository` as a fallback (useful when consuming unreleased framework artifacts via `./mvnw install` in the parent repo), use any other id:

```xml
<repositories>
    <repository>
        <id>maven-local-fallback</id>
        <url>file://${user.home}/.m2/repository</url>
        <releases><enabled>true</enabled></releases>
        <snapshots><enabled>true</enabled></snapshots>
    </repository>
</repositories>
```

## Maven wrapper

Use the scriptless Maven wrapper so users don't need a system `mvn` install:

```
.mvn/wrapper/maven-wrapper.properties
mvnw                                     # POSIX shell wrapper
mvnw.cmd                                 # Windows wrapper
```

The `maven-wrapper.properties` file points to a published Maven distribution:

```properties
wrapperVersion=3.3.2
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip
```

If you have `mvn` installed, run `mvn wrapper:wrapper -Dtype=only-script` once to generate them; otherwise extract them from the [`maven-wrapper-distribution-only-script` zip on Maven Central](https://repo1.maven.org/maven2/org/apache/maven/wrapper/maven-wrapper-distribution/3.3.2/).

`distributionType=only-script` keeps the wrapper tiny — it uses curl / PowerShell to fetch Maven on first run instead of bundling a 30KB `maven-wrapper.jar`.

## Build commands

The Maven equivalents of the Gradle commands the rest of the docs reference:

| Gradle | Maven | Notes |
|---|---|---|
| `./gradlew build` | `./mvnw verify` | Maven's `package` doesn't run tests; `verify` does and runs `integration-test` too. |
| `./gradlew test` | `./mvnw test` | Doesn't run the jOOQ codegen chain on its own — but `generate-sources` is a prereq of `compile`, which is a prereq of `test`, so the chain runs anyway. |
| `./gradlew bootRun` | `./mvnw spring-boot:run` | Same `compose.yaml` auto-startup via `spring-boot-docker-compose` if it's on the classpath. |
| `./gradlew quarkusDev` | `./mvnw quarkus:dev` | Quarkus dev mode (continuous testing, live reload). |
| `./gradlew run` (Micronaut) | `./mvnw mn:run` | Micronaut's run goal via `micronaut-maven-plugin`. |
| `./gradlew clean build` | `./mvnw clean verify` | Same semantics. |
| `./gradlew spotlessApply` | `./mvnw spotless:apply` | If you wire up [`spotless-maven-plugin`](https://github.com/diffplug/spotless/tree/main/plugin-maven). The runnable example does. |
| `./gradlew spotlessCheck` | `./mvnw spotless:check` | Same — checks without rewriting. The example binds this to the `compile` phase so `mvn verify` fails on formatting drift. |

## See also

- [jOOQ codegen on Maven](jooq-codegen.md) — the three-plugin chain for build-time SQL class generation
- [Getting started with Gradle](../gradle/getting-started.md) — the Gradle equivalent of this page
- [Wiring with Spring Boot](../wiring/spring.md) / [Quarkus](../wiring/quarkus.md) / [Micronaut](../wiring/micronaut.md) — same Maven principles, different DI starters
- [Database → PostgreSQL](../database/postgresql.md) / [MariaDB](../database/mariadb.md) / [MySQL](../database/mysql.md) — per-dialect column types, framework tables, and gotchas
- Spring Boot Maven references: [`spring-boot-wallet-rest-maven-pg`](../../ekbatan-examples/spring-boot-wallet-rest-maven-pg) / [`-mariadb`](../../ekbatan-examples/spring-boot-wallet-rest-maven-mariadb) / [`-mysql`](../../ekbatan-examples/spring-boot-wallet-rest-maven-mysql) — one per dialect
- Quarkus Maven references: [`quarkus-wallet-rest-maven-pg`](../../ekbatan-examples/quarkus-wallet-rest-maven-pg) / [`-mariadb`](../../ekbatan-examples/quarkus-wallet-rest-maven-mariadb) / [`-mysql`](../../ekbatan-examples/quarkus-wallet-rest-maven-mysql) — one per dialect
- Micronaut Maven references: [`micronaut-wallet-rest-maven-pg`](../../ekbatan-examples/micronaut-wallet-rest-maven-pg) / [`-mariadb`](../../ekbatan-examples/micronaut-wallet-rest-maven-mariadb) / [`-mysql`](../../ekbatan-examples/micronaut-wallet-rest-maven-mysql) — one per dialect
- Micronaut Maven + GraalVM native-image: [`micronaut-wallet-rest-maven-native-pg`](../../ekbatan-examples/micronaut-wallet-rest-maven-native-pg) / [`-mariadb`](../../ekbatan-examples/micronaut-wallet-rest-maven-native-mariadb) / [`-mysql`](../../ekbatan-examples/micronaut-wallet-rest-maven-native-mysql) — one per dialect

← Back to [Maven](README.md) · [docs index](../README.md)
