import org.jooq.meta.jaxb.ForcedType

plugins {
    java
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.6"
    id("dev.monosoul.jooq-docker") version "8.0.9"
    id("com.diffplug.spotless") version "8.1.0"
}

group = "io.example"
version = "1.0.0-SNAPSHOT"

// Flyway version held as a local val so the resolutionStrategy.force block (below)
// has something to reference. Spring Boot BOM-managed deps don\'t need an inline version on
// the `implementation(...)` call — but the force block has to know what to force to.
val flywayVersion = "12.0.0"

// Spring Boot 4.0.x's bom pins jOOQ to 3.19.x, but Ekbatan (and our codegen plugin) target
// 3.20.x. Pin the family explicitly so the generated record classes and the runtime jOOQ
// classpath agree.
extra["jooq.version"] = "3.20.10"

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

dependencies {
    // ── Ekbatan ─────────────────────────────────────────────────────────────
    // The Spring Boot starter pulls ekbatan-core, the local-event-handler,
    // and distributed-jobs transitively.
    implementation("io.github.ekbatan-io:ekbatan-spring-boot-starter:$ekbatanVersion")
    // @AutoBuilder is compile-time only: compileOnly exposes the annotation to javac,
    // annotationProcessor runs the processor that emits *Builder classes.
    compileOnly("io.github.ekbatan-io:ekbatan-annotation-processor:$ekbatanVersion")
    annotationProcessor("io.github.ekbatan-io:ekbatan-annotation-processor:$ekbatanVersion")

    // Used by the domain classes for Validate.notNull / Validate.isTrue style guards.
    implementation("org.apache.commons:commons-lang3:3.20.0")

    // ── Spring Boot ─────────────────────────────────────────────────────────
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Brings up the compose.yaml services on `./gradlew bootRun` and tears them
    // down on shutdown. Tests don't go through this — they use Testcontainers.
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    // ── Flyway ──────────────────────────────────────────────────────────────
    // Spring Boot's own Flyway auto-config wants spring.datasource.*, but Ekbatan's
    // connection config lives under ekbatan.sharding.* — we don't want to duplicate
    // credentials in two property trees. Instead we run Flyway programmatically through
    // ekbatan-flyway (see FlywayConfiguration), from the same ShardingConfig Ekbatan uses - so
    // migrations connect exactly as the application does, dataSourceProperties included.
    implementation("io.github.ekbatan-io:ekbatan-flyway:$ekbatanVersion")
    implementation("org.flywaydb:flyway-database-postgresql")

    // ── PostgreSQL ──────────────────────────────────────────────────────────
    runtimeOnly("org.postgresql:postgresql")
    jooqCodegen("org.postgresql:postgresql")

    // ── Tests ───────────────────────────────────────────────────────────────
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Gradle 9 + Spring Boot 4's BOM no longer pulls junit-platform-launcher transitively in
    // some configurations. Add it explicitly so the test runner can start.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Note: in testcontainers 2.x the modules were renamed:
    //   org.testcontainers:postgresql       → org.testcontainers:testcontainers-postgresql
    //   org.testcontainers:junit-jupiter    → org.testcontainers:testcontainers-junit-jupiter
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("org.awaitility:awaitility:4.3.0")
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
//   - Quarkus 3.34.6 pins flyway-core to 12.0.0
//   - Spring Boot 4.x      pins flyway-core to 11.14.1
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
