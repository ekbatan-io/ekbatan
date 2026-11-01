import org.jooq.meta.jaxb.ForcedType

plugins {
    java
    // Micronaut application plugin — produces a runnable shadow jar, registers the
    // micronaut-inject-java annotation processor, and adds `./gradlew run` (the Micronaut
    // equivalent of Spring Boot's `bootRun`).
    id("io.micronaut.application") version "5.0.2"
    id("dev.monosoul.jooq-docker") version "9.0.9"
    id("com.diffplug.spotless") version "8.10.4"
}

group = "io.example"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}

val ekbatanVersion: String by project
val micronautVersion = "5.2.4"
val flywayVersion = "13.10.3"

micronaut {
    version(micronautVersion)
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        // Tells Micronaut's AP where to scan for our own beans. Without this it scans only the
        // default `groovy/java` packages and would miss io.example.wallet.* classes that aren't
        // annotated with stock Micronaut stereotypes (our @EkbatanAction etc.).
        annotations("io.example.wallet.*")
    }
}

application {
    mainClass.set("io.example.wallet.Application")
}

dependencies {
    // ── Ekbatan ─────────────────────────────────────────────────────────────
    // ekbatan-micronaut pulls ekbatan-core, the local-event-handler,
    // and distributed-jobs transitively.
    implementation("io.github.ekbatan-io:ekbatan-micronaut:$ekbatanVersion")
    implementation("io.github.ekbatan-io:ekbatan-flyway:$ekbatanVersion")
    // Required on the AP path: EkbatanStereotypeVisitor needs to be visible to micronaut-inject-java
    // when this module's @EkbatanAction / @EkbatanRepository / @EkbatanEventHandler classes are
    // compiled. Without this, those annotations don't lift to @Singleton and Micronaut treats the
    // classes as plain non-beans.
    annotationProcessor("io.github.ekbatan-io:ekbatan-micronaut:$ekbatanVersion")
    // The @AutoBuilder processor — same dual-path pattern as the Spring/Quarkus examples.
    compileOnly("io.github.ekbatan-io:ekbatan-annotation-processor:$ekbatanVersion")
    annotationProcessor("io.github.ekbatan-io:ekbatan-annotation-processor:$ekbatanVersion")
    // micronaut-serde-processor generates compile-time `Serializer`/`Deserializer`
    // beans for any class annotated with `@Serdeable`. Required by `micronaut-serde-jackson`.
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")

    // Used by the domain classes for Validate.notNull / Validate.isTrue style guards.
    implementation("org.apache.commons:commons-lang3:3.21.0")

    // ── Micronaut runtime ───────────────────────────────────────────────────
    // JSON (de)serialization for request/response bodies. The `runtime("netty")` declaration
    // only wires the server; we still need a `JsonMapper` bean for body handlers.
    // micronaut-serde-jackson — Jackson-compatible (de)serialization using
    // compile-time generated serdes instead of Jackson Databind's reflection. This is
    // the native-image-friendly path (no reflection metadata for HTTP DTOs). Standard
    // Jackson annotations (@JsonProperty, @JsonCreator) are honoured; types must carry
    // @Serdeable to opt in.
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    runtimeOnly("ch.qos.logback:logback-classic")
    // application.yml — Micronaut's inspectRuntimeClasspath task requires snakeyaml whenever any
    // *.yml is shipped, even though our config keys live under ekbatan.* not micronaut.*.
    runtimeOnly("org.yaml:snakeyaml")

    // ── Flyway support ───────────────────────────────────────────────────────
    // ekbatan-flyway runs migrations from an eager startup bean using typed ShardingConfig.
    // micronaut-flyway stays on the classpath for Flyway and native-image support.
    implementation("io.micronaut.flyway:micronaut-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // ── PostgreSQL ──────────────────────────────────────────────────────────
    // Explicit version: the Spring sibling lets Spring Boot's BOM manage it, but Micronaut's
    // BOM doesn't pin Postgres and the codegen plugin needs a concrete coordinate.
    runtimeOnly("org.postgresql:postgresql:42.7.14")
    jooqCodegen("org.postgresql:postgresql:42.7.14")

    // ── Tests ───────────────────────────────────────────────────────────────
    // micronaut-test-junit5 is wired automatically by the Gradle plugin via testRuntime("junit5").
    // micronaut-http-client isn't pulled by the server-only runtime("netty") declaration — add it
    // explicitly so the integration test can use @Client to call its own controller.
    testImplementation("io.micronaut:micronaut-http-client")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Note: in testcontainers 2.x the modules were renamed:
    //   org.testcontainers:postgresql       → org.testcontainers:testcontainers-postgresql
    //   org.testcontainers:junit-jupiter    → org.testcontainers:testcontainers-junit-jupiter
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat("2.81.0")
    }
    format("misc") {
        target("*.md", ".gitignore", ".gitattributes", "*.yaml", "*.yml", "*.json")
        trimTrailingWhitespace()
        leadingTabsToSpaces(4)
        endWithNewline()
    }
}

tasks {
    generateJooqClasses {
        schemas.set(listOf("public", "eventlog"))
        basePackageName.set("io.example.wallet.generated.jooq")
        migrationLocations.setFromFilesystem("src/main/resources/db/migration")
        outputDirectory.set(project.layout.buildDirectory.dir("generated-jooq"))
        flywayProperties.put("flyway.placeholderReplacement", "false")
        includeFlywayTable.set(false)
        outputSchemaToDefault.add("public")
        schemaToPackageMapping.put("public", "public_schema")
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

    withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-parameters")
    }

    test {
        useJUnitPlatform()
    }
}

sourceSets {
    main {
        java {
            srcDir(tasks.generateJooqClasses.flatMap { it.outputDirectory })
        }
    }
}

// Force Flyway to the requested version even when the framework's BOM (Quarkus's
// quarkus-bom, Spring Boot's spring-boot-dependencies, Micronaut's micronaut-bom) pins
// an older one — they all do, to varying degrees:
//   - Quarkus 3.40.1.1 pins flyway-core to 12.0.0
//   - Spring Boot 4.1.2    pins flyway-core to 12.4.0
// Without this force, `org.flywaydb:flyway-core` gets resolved DOWN to the BOM's choice
// and the runtime + jOOQ-codegen versions drift apart. Read from gradle.properties
// directly so Spring Boot projects (which don't declare a local `flywayVersion` val,
// since they let the BOM manage the version) work too.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.flywaydb") {
            useVersion(flywayVersion)
            because("ekbatan tracks Flyway $flywayVersion across all examples")
        }
    }
}
