import org.graalvm.buildtools.gradle.dsl.GraalVMReachabilityMetadataRepositoryExtension
import org.jooq.meta.jaxb.ForcedType

plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("dev.monosoul.jooq-docker") version "9.0.9"
    // GraalVM native-image support. Provides `nativeCompile`, `nativeRun`, and `nativeTest`.
    // The Spring Boot plugin auto-applies its AOT integration once this is on the classpath.
    id("org.graalvm.buildtools.native") version "1.1.18"
    id("com.diffplug.spotless") version "8.10.4"
}

group = "io.example"
version = "1.0.0-SNAPSHOT"

// Flyway version held as a local val so the resolutionStrategy.force block (below)
// has something to reference. Spring Boot BOM-managed deps don\'t need an inline version on
// the `implementation(...)` call — but the force block has to know what to force to.
val flywayVersion = "13.10.3"

// Spring Boot 4.1.2's bom pins jOOQ to 3.21.9, the version Ekbatan (and our codegen plugin)
// target. Pin the family explicitly anyway, so the generated record classes and the runtime
// jOOQ classpath keep agreeing when the bom moves.
extra["jooq.version"] = "3.21.9"

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
    implementation("io.github.ekbatan-io:ekbatan-flyway:$ekbatanVersion")
    // @AutoBuilder is compile-time only: compileOnly exposes the annotation to javac,
    // annotationProcessor runs the processor that emits *Builder classes.
    compileOnly("io.github.ekbatan-io:ekbatan-annotation-processor:$ekbatanVersion")
    annotationProcessor("io.github.ekbatan-io:ekbatan-annotation-processor:$ekbatanVersion")

    // Used by the domain classes for Validate.notNull / Validate.isTrue style guards.
    implementation("org.apache.commons:commons-lang3:3.21.0")

    // Native-image extras: ships GraalVM Features for Ekbatan runtime metadata
    // (Jackson 3 records, builders, generated jOOQ classes, HikariCP/JDBC metadata, etc.).
    implementation("io.github.ekbatan-io:ekbatan-native:$ekbatanVersion")

    // ── Spring Boot ─────────────────────────────────────────────────────────
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Brings up the compose.yaml services on `./gradlew bootRun` and tears them
    // down on shutdown. Tests don't go through this — they use Testcontainers.
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    // ── Flyway ──────────────────────────────────────────────────────────────
    // Spring Boot's own Flyway auto-config wants spring.datasource.*, but Ekbatan's
    // connection config lives under ekbatan.sharding.* — we don't want to duplicate
    // credentials in two property trees. Instead we run Flyway programmatically
    // (see FlywayConfiguration), reading the same ShardingConfig Ekbatan uses.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // ── PostgreSQL ──────────────────────────────────────────────────────────
    runtimeOnly("org.postgresql:postgresql")
    jooqCodegen("org.postgresql:postgresql")

    // ── Tests ───────────────────────────────────────────────────────────────
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Gradle 9 + Spring Boot 4's BOM no longer pulls junit-platform-launcher transitively, so
    // it has to be declared explicitly here. Without it the test runner fails at startup with
    // "Failed to load JUnit Platform".
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

// ── GraalVM native-image ────────────────────────────────────────────────────────────
// Use a Java 25 toolchain that can run native-image. Bundle migration SQL files into the
// image so FlywayMigrator can discover classpath migrations at runtime.
graalvmNative {
    toolchainDetection.set(true)
    binaries.all {
        javaLauncher.set(
            javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(25))
                nativeImageCapable.set(true)
            },
        )
        resources.includedPatterns.add("db/migration/.*\\.sql")
        buildArgs.add("-Dio.ekbatan.graalvm.scan.packages=io.ekbatan,io.example")
    }
    binaries.named("test") {
        quickBuild.set(true)
    }
    (this as ExtensionAware)
        .extensions
        .configure<GraalVMReachabilityMetadataRepositoryExtension>("metadataRepository") {
            enabled.set(true)
            version.set("1.0.0")
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
