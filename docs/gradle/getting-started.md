# Getting started with Gradle

What you write in a `build.gradle.kts` to consume Ekbatan from Maven Central, on any of the supported stacks. Mirrors the Java-level setup in [`wiring/spring.md`](../wiring/spring.md), [`wiring/quarkus.md`](../wiring/quarkus.md), and [`wiring/micronaut.md`](../wiring/micronaut.md) — the per-stack pages cover the DI wiring, this page covers the build descriptor.

For jOOQ codegen wiring, jump to [jOOQ codegen on Gradle](jooq-codegen.md) — that part is involved enough to deserve its own page.

## Minimum `build.gradle.kts`

Pick the dependency block for your stack. Everything below it (compiler flag, codegen plugin, source-set wiring) is identical across stacks.

### Spring Boot

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.6"
    id("dev.monosoul.jooq-docker") version "8.0.9"
}

// (1) Spring Boot 4.0.x's BOM pins jOOQ to 3.19.x; Ekbatan needs 3.20.x.
extra["jooq.version"] = "3.20.10"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // (2) The starter — pulls ekbatan-core, the local event handler,
    // and distributed jobs transitively.
    implementation("io.github.ekbatan-io:ekbatan-spring-boot-starter:1.1.1")
    // Programmatic shard-aware Flyway migration from ekbatan.sharding.*.
    implementation("io.github.ekbatan-io:ekbatan-flyway:1.1.1")

    // (3) @AutoBuilder is compile-time only: compileOnly exposes the annotation to javac,
    // annotationProcessor runs the processor that emits *Builder.java.
    compileOnly("io.github.ekbatan-io:ekbatan-annotation-processor:1.1.1")
    annotationProcessor("io.github.ekbatan-io:ekbatan-annotation-processor:1.1.1")

    implementation("org.springframework.boot:spring-boot-starter-web")

    // Flyway runtime. Ekbatan's Spring starter filters Boot's single-datasource
    // Flyway auto-run; EkbatanShardFlywayMigrator calls FlywayMigrator instead.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Your DB driver, plus a jooqCodegen() classpath entry for the driver so the
    // build-time container can be introspected. See jooq-codegen.md.
    runtimeOnly("org.postgresql:postgresql")
    jooqCodegen("org.postgresql:postgresql")
}

tasks.withType<JavaCompile>().configureEach {
    // (4) Required: framework reflection reads constructor parameter names.
    options.compilerArgs.add("-parameters")
}
```

### Quarkus

```kotlin
plugins {
    java
    id("io.quarkus") version "3.34.6"
    id("dev.monosoul.jooq-docker") version "8.0.9"
}

// (1) The Quarkus platform BOM doesn't pin jOOQ, but the codegen plugin does — pin
// explicitly so the runtime classpath matches what the generated classes were compiled
// against.
extra["jooq.version"] = "3.20.10"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.34.6"))

    // (2) The Quarkus extension — pulls ekbatan-core + the local-event-handler +
    // distributed-jobs transitively.
    implementation("io.github.ekbatan-io:ekbatan-quarkus:1.1.1")
    // Programmatic shard-aware Flyway migration from ekbatan.sharding.*.
    implementation("io.github.ekbatan-io:ekbatan-flyway:1.1.1")

    // (3) @AutoBuilder is compile-time only: compileOnly + annotationProcessor.
    compileOnly("io.github.ekbatan-io:ekbatan-annotation-processor:1.1.1")
    annotationProcessor("io.github.ekbatan-io:ekbatan-annotation-processor:1.1.1")

    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")

    // Flyway runtime. Quarkus' datasource/Flyway startup config is not used here;
    // EkbatanShardFlywayMigrator calls FlywayMigrator instead.
    implementation("io.quarkus:quarkus-flyway")
    implementation("org.flywaydb:flyway-mysql")     // MariaDB/MySQL dialect module
    implementation("io.quarkus:quarkus-jdbc-mariadb")

    // Driver + jooqCodegen entry.
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.7")
    jooqCodegen("org.mariadb.jdbc:mariadb-java-client:3.5.7")
    jooqCodegen("org.flywaydb:flyway-mysql:12.0.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}
```

### Micronaut

```kotlin
plugins {
    java
    // The Micronaut Gradle plugin registers `micronaut-inject-java` as an annotation
    // processor and provides `./gradlew run`.
    id("io.micronaut.application") version "4.6.1"
    id("dev.monosoul.jooq-docker") version "8.0.9"
}

repositories {
    mavenLocal()
    mavenCentral()
}

micronaut {
    version("4.10.7")
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        // Tells Micronaut's AP where to scan for *your* beans. Required when classes are
        // marked with our @EkbatanAction / @EkbatanRepository (not stock Micronaut
        // stereotypes) — the visitor lifts them to @Singleton at compile time.
        annotations("com.your.package.*")
    }
}

dependencies {
    // (2) The integration jar — pulls ekbatan-core + handlers + jobs transitively.
    implementation("io.github.ekbatan-io:ekbatan-micronaut:1.1.1")
    // Programmatic shard-aware Flyway migration from ekbatan.sharding.*.
    implementation("io.github.ekbatan-io:ekbatan-flyway:1.1.1")
    // (5) REQUIRED on Micronaut: the EkbatanStereotypeVisitor lives in ekbatan-micronaut
    // and must be on the annotationProcessor classpath so it sees @EkbatanAction etc.
    // during *your* compile. Without this, no BeanDefinition is generated for your
    // annotated classes and Micronaut treats them as plain non-beans at runtime.
    annotationProcessor("io.github.ekbatan-io:ekbatan-micronaut:1.1.1")

    // (3) @AutoBuilder is compile-time only: compileOnly + annotationProcessor.
    compileOnly("io.github.ekbatan-io:ekbatan-annotation-processor:1.1.1")
    annotationProcessor("io.github.ekbatan-io:ekbatan-annotation-processor:1.1.1")

    implementation("io.micronaut:micronaut-jackson-databind")

    // Flyway runtime. micronaut-flyway remains on the classpath for Flyway/native
    // integration, but the startup @Context bean calls FlywayMigrator itself.
    implementation("io.micronaut.flyway:micronaut-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    runtimeOnly("org.postgresql:postgresql:42.7.10")
    jooqCodegen("org.postgresql:postgresql:42.7.10")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}
```

### Plain Java (no DI container)

```kotlin
plugins {
    java
    id("dev.monosoul.jooq-docker") version "8.0.9"
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // ── Required ────────────────────────────────────────────────────────────
    implementation("io.github.ekbatan-io:ekbatan-core:1.1.1")
    implementation("io.github.ekbatan-io:ekbatan-flyway:1.1.1") // FlywayMigrator

    // ── Optional capabilities — add only what you need ──────────────────────

    // @AutoBuilder code generation — generates *Builder classes for Models/Entities.
    // Skip if you'd rather write the builders by hand.
    compileOnly("io.github.ekbatan-io:ekbatan-annotation-processor:1.1.1")
    annotationProcessor("io.github.ekbatan-io:ekbatan-annotation-processor:1.1.1")

    // In-process event handlers (fan-out + handling jobs over the eventlog).
    implementation("io.github.ekbatan-io:ekbatan-local-event-handler:1.1.1")

    // Distributed background jobs (db-scheduler facade; cluster-exclusive scheduling).
    implementation("io.github.ekbatan-io:ekbatan-distributed-jobs:1.1.1")

    implementation("org.flywaydb:flyway-core:12.0.0")
    implementation("org.flywaydb:flyway-database-postgresql:12.0.0")
    runtimeOnly("org.postgresql:postgresql:42.7.10")
    jooqCodegen("org.postgresql:postgresql:42.7.10")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}
```

Each numbered item below is the reason this `build.gradle.kts` differs from a vanilla Spring Boot / Quarkus / Micronaut starter project.

## (1) Override the jOOQ version on Spring Boot

Spring Boot 4.0.x's BOM pins `jooq.version=3.19.x`. Ekbatan compiles against jOOQ 3.20.x and the generated classes call 3.20.x-only APIs. If the BOM wins, you get `NoSuchMethodError` at runtime the first time Ekbatan's code touches a 3.20-only method.

The fix is one line:

```kotlin
extra["jooq.version"] = "3.20.10"
```

Quarkus' BOM doesn't pin jOOQ, but the codegen plugin's default version may not match what Ekbatan was built against — pin explicitly anyway, the cost is one line and the failure mode if you skip it is the same `NoSuchMethodError`. Micronaut's BOM also doesn't pin jOOQ; explicit pin is good hygiene there too. Plain-Java projects have no BOM and pull jOOQ transitively from `ekbatan-core` — no override needed.

## (2) The integration dependency

| Stack | Coordinate | Pulls transitively |
|---|---|---|
| Spring Boot | `io.github.ekbatan-io:ekbatan-spring-boot-starter` | `ekbatan-core`, `ekbatan-local-event-handler`, `ekbatan-distributed-jobs` |
| Quarkus | `io.github.ekbatan-io:ekbatan-quarkus` | same set |
| Micronaut | `io.github.ekbatan-io:ekbatan-micronaut` | same set |
| Plain Java | `io.github.ekbatan-io:ekbatan-core` (+ each optional module explicitly) | nothing transitively — see below |

The four `@Ekbatan*` annotations (`@EkbatanAction`, `@EkbatanRepository`, `@EkbatanEventHandler`, `@EkbatanDistributedJob`) live in `ekbatan-di-annotations` and come with the integration jars. You don't add them explicitly.

## (3) The annotation processor dual-path

`@AutoBuilder` (used on every `Model` / `Entity`) is declared in `ekbatan-annotation-processor`. Keep it compile-time only by putting it on **both** of these Gradle configurations:

```kotlin
// compile classpath only — so javac sees @AutoBuilder without adding the jar at runtime
compileOnly("io.github.ekbatan-io:ekbatan-annotation-processor:1.1.1")

// annotation processor classpath — so the processor actually runs and emits *Builder.java
annotationProcessor("io.github.ekbatan-io:ekbatan-annotation-processor:1.1.1")
```

This isn't redundant — `annotationProcessor` is isolated from the main compile classpath. Dropping one or the other breaks differently:

- Without `compileOnly` → compile fails with `cannot find symbol: class AutoBuilder`.
- Without `annotationProcessor` → compile succeeds, processor never runs, you find out at the first reference to `WalletBuilder.wallet()` (`cannot find symbol: class WalletBuilder`).

The processor runs as part of normal compilation — any task that triggers `javac` (`compileJava`, `build`, `test`, `assemble`) also runs it. There is no separate code-generation task. The generated `*Builder.java` files land under `build/generated/sources/annotationProcessor/java/main/`.

## (4) `-parameters` is mandatory

Ekbatan reflects over constructor *parameter names* for two things: the `@AutoBuilder` processor names builder methods after them, and Jackson 3's `RecordsModule` uses them to bind JSON fields to record components. Without `-parameters` the bytecode stores them as `arg0`, `arg1`, … and the runtime fails with "no setter for property X" or "missing required field arg0".

Gradle's `java` plugin defaults to omitting it; add the flag explicitly:

```kotlin
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}
```

Same requirement applies on Maven (`<parameters>true</parameters>` under `maven-compiler-plugin`).

## (5) Micronaut needs `annotationProcessor("…ekbatan-micronaut")`

This one bites. The `ekbatan-micronaut` jar ships an `EkbatanStereotypeVisitor` that hooks into Micronaut's compile-time annotation processor and lifts your `@Ekbatan*`-annotated classes to `@Singleton`. **The visitor only runs on classes compiled with the jar on the annotation-processor classpath.**

If you only have `implementation("io.github.ekbatan-io:ekbatan-micronaut:…")` and forget the `annotationProcessor` line:

- Build succeeds.
- Runtime: Micronaut finds no `BeanDefinition` for your `@EkbatanAction` / `@EkbatanRepository` / `@EkbatanEventHandler` / `@EkbatanDistributedJob` classes.
- You get `UnsatisfiedDependencyException` (for repositories injected into actions) or "no candidates for `WalletDepositAction`" when `ActionExecutor.execute(...)` runs.

Spring Boot and Quarkus don't need a separate annotation-processor line for their integration jars — Spring uses runtime classpath scan + AOT, Quarkus uses Jandex at the deployment phase. Micronaut is the one that does *all* its DI work at compile time, so its discovery requires the visitor to be on the AP path.

## (6) Optional add-ons

The DI integration dependencies pull the common runtime modules such as the local event handler and distributed jobs. `ekbatan-annotation-processor` stays explicit and compile-time only. The following are deliberately *not* pulled; add them only when you need them:

```kotlin
// Programmatic Flyway migration from one DataSource or every primary shard in ShardingConfig.
implementation("io.github.ekbatan-io:ekbatan-flyway:1.1.1")

// Redis-backed distributed KeyedLockProvider (Redisson under the hood).
implementation("io.github.ekbatan-io:ekbatan-keyed-lock-redis:1.1.1")

// GraalVM native-image Features (auto-loaded; include only if you build native binaries).
implementation("io.github.ekbatan-io:ekbatan-native:1.1.1")

// ActionSpec, ActionAssert, VirtualClock, and classpath-resource Testcontainers helpers.
testImplementation("io.github.ekbatan-io:ekbatan-test-support:1.1.1")

// Wire-format DTOs for Kafka consumer apps reading from the eventlog. Pick the one
// matching your Kafka serializer; NOT needed in the producer app itself.
implementation("io.github.ekbatan-io:ekbatan-action-event-json:1.1.1")
implementation("io.github.ekbatan-io:ekbatan-action-event-avro:1.1.1")
implementation("io.github.ekbatan-io:ekbatan-action-event-protobuf:1.1.1")
```

The wire-format jars are tagged "not needed in the producer app" because the framework writes events to the outbox using its own internal payload format — the wire-format DTOs are POJOs you deserialize *back into* on the consumer side (whatever app reads Kafka or polls the eventlog from outside the producing JVM).

## (7) The jOOQ codegen plugin

Every example uses `dev.monosoul.jooq-docker`:

```kotlin
plugins {
    id("dev.monosoul.jooq-docker") version "8.0.9"
}
```

The plugin starts a throwaway DB container at build time, runs your Flyway migrations against it, introspects the live schema, and writes Java classes into `build/generated-jooq/`. The dialect-specific `jooq { withContainer { … } }` block, the `generateJooqClasses` task config, and the `sourceSets.main.java.srcDir(…)` line that puts generated classes on the compile path all live in their own page: [jOOQ codegen on Gradle](jooq-codegen.md).

## `gradle.properties`

Most examples keep the Ekbatan version (and dialect-specific driver versions) in `gradle.properties` rather than hard-coding them in `build.gradle.kts`:

```properties
ekbatanVersion=1.1.1
```

Read in the build script via:

```kotlin
val ekbatanVersion: String by project
```

That gives one place to bump the version across a multi-module repo.

## Gradle wrapper

Use the wrapper so users don't need a system Gradle install:

```
./gradlew wrapper --gradle-version 9.0.0
```

Commit `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, and `gradlew.bat`. The wrapper bootstraps the right Gradle version on first run.

## Build commands

| Command | Purpose |
|---|---|
| `./gradlew build` | Compile + test + assemble. Runs `generateJooqClasses` as a `compileJava` prerequisite. |
| `./gradlew test` | Tests only. Still triggers `generateJooqClasses` if anything under `src/main/resources/db/migration/` changed. |
| `./gradlew generateJooqClasses` | Just the codegen step — useful when iterating on a Flyway migration before writing application code. |
| `./gradlew bootRun` | Spring Boot run-in-place. With `developmentOnly("…spring-boot-docker-compose")` and a `compose.yaml` next to `build.gradle.kts`, it brings up + tears down the DB. |
| `./gradlew quarkusDev` | Quarkus dev mode (continuous testing, live reload). |
| `./gradlew run` | Micronaut's equivalent — set `application { mainClass.set("…") }`. |
| `./gradlew spotlessApply` | Format Java + Markdown + YAML in place (every example wires the [Spotless plugin](https://github.com/diffplug/spotless)). |
| `./gradlew spotlessCheck` | CI-friendly check without rewriting. |

## See also

- [jOOQ codegen on Gradle](jooq-codegen.md) — the `dev.monosoul.jooq-docker` plugin, per-dialect container/forced-type blocks, source-set wiring
- [Wiring with Spring Boot](../wiring/spring.md) / [Quarkus](../wiring/quarkus.md) / [Micronaut](../wiring/micronaut.md) — what these dependencies enable on the Java side
- [Database → PostgreSQL](../database/postgresql.md) / [MariaDB](../database/mariadb.md) / [MySQL](../database/mysql.md) — per-dialect column types, framework tables, gotchas
- [`ekbatan-examples/`](../../ekbatan-examples/) — runnable Gradle and Maven examples

← Back to [Gradle](README.md) · [docs index](../README.md)
