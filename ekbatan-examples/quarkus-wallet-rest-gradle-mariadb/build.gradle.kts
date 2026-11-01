import org.jooq.meta.jaxb.ForcedType

plugins {
    java
    id("io.quarkus") version "3.40.1.1"
    id("dev.monosoul.jooq-docker") version "9.0.9"
    id("com.diffplug.spotless") version "8.10.4"
}

group = "io.example"
version = "1.0.0-SNAPSHOT"

// jOOQ codegen ships 3.21.x; pin runtime jOOQ to match.
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
val quarkusVersion = "3.40.1.1"
// Not MariaDB's driver 4: Quarkus 3.40 builds and tests quarkus-jdbc-mariadb with 3.5.10, and its
// enforced platform keeps that version at runtime anyway.
val mariadbDriverVersion = "3.5.11"
val flywayVersion = "13.10.3"

// ── jOOQ codegen container ─────────────────────────────────────────────────────────
// The plugin spins up a throwaway MariaDB container at build time, runs the Flyway migrations
// against it, then introspects the live schema and generates the JOOQ classes. The runtime
// app classpath uses the same MariaDB JDBC driver below.
jooq {
    withContainer {
        image {
            name = "mariadb:11.8"
            envVars =
                mapOf(
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

dependencies {
    // ── Quarkus BOM ─────────────────────────────────────────────────────────
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:$quarkusVersion"))

    // ── Ekbatan ─────────────────────────────────────────────────────────────
    // The Quarkus extension pulls ekbatan-core, the local-event-handler,
    // and distributed-jobs transitively.
    implementation("io.github.ekbatan-io:ekbatan-quarkus:$ekbatanVersion")
    implementation("io.github.ekbatan-io:ekbatan-flyway:$ekbatanVersion")
    // @AutoBuilder is compile-time only: compileOnly exposes the annotation to javac,
    // annotationProcessor runs the processor that emits *Builder classes.
    compileOnly("io.github.ekbatan-io:ekbatan-annotation-processor:$ekbatanVersion")
    annotationProcessor("io.github.ekbatan-io:ekbatan-annotation-processor:$ekbatanVersion")

    // Used by the domain classes for Validate.notNull / Validate.isTrue style guards.
    implementation("org.apache.commons:commons-lang3:3.21.0")

    // ── Quarkus REST ────────────────────────────────────────────────────────
    // quarkus-rest is the modern (formerly RESTEasy-Reactive) JAX-RS impl;
    // quarkus-rest-jackson plugs Jackson into the request/response (de)serializers.
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")

    // ── MariaDB ─────────────────────────────────────────────────────────────
    // Ekbatan's ConnectionProvider runs Hikari itself — we don't use Quarkus's data source —
    // but the driver still needs to be on the classpath for Hikari's Class.forName(...).
    implementation("org.mariadb.jdbc:mariadb-java-client:$mariadbDriverVersion")
    jooqCodegen("org.mariadb.jdbc:mariadb-java-client:$mariadbDriverVersion")

    // ── Flyway ──────────────────────────────────────────────────────────────
    // EkbatanShardFlywayMigrator runs Flyway over every shard from ekbatan.sharding.*.
    // quarkus-flyway stays on the classpath for Flyway/native-image integration.
    implementation("io.quarkus:quarkus-flyway")
    // Flyway needs flyway-mysql to recognize the MariaDB JDBC URL.
    implementation("org.flywaydb:flyway-mysql")
    // JDBC driver: quarkus-jdbc-mariadb pulls the driver JAR + registers the class
    // reflectively for native (no-op in JVM but harmless).
    implementation("io.quarkus:quarkus-jdbc-mariadb")
    jooqCodegen("org.flywaydb:flyway-mysql:$flywayVersion")

    // ── Tests ───────────────────────────────────────────────────────────────
    testImplementation("io.quarkus:quarkus-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.awaitility:awaitility:4.3.0")
    // Note: in testcontainers 2.x the modules were renamed:
    //   org.testcontainers:mariadb          → org.testcontainers:testcontainers-mariadb
    //   org.testcontainers:junit-jupiter    → org.testcontainers:testcontainers-junit-jupiter
    testImplementation("org.testcontainers:testcontainers-mariadb:2.0.5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
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
        // Only generate for the main 'wallet' database — the eventlog tables are accessed
        // through the framework's own field constants, no JOOQ codegen for them needed.
        schemas.set(listOf("wallet"))
        basePackageName.set("io.example.wallet.generated.jooq")
        migrationLocations.setFromFilesystem("src/main/resources/db/migration")
        outputDirectory.set(project.layout.buildDirectory.dir("generated-jooq"))
        flywayProperties.put("flyway.placeholderReplacement", "false")
        includeFlywayTable.set(false)
        // No subpackage for the default schema on MariaDB/MySQL — table classes land at
        // io.example.wallet.generated.jooq.tables.Wallets etc.
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
                // No UUID forced type needed — MariaDB has a native UUID type (10.7+) and
                // jOOQ maps it to java.util.UUID directly. Contrast with the MySQL example,
                // which has to convert CHAR(36) → UUID via UuidStringConverter.
            )
        }
    }

    withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-parameters")
    }

    withType<Test> {
        useJUnitPlatform()
        // Quarkus uses jboss-logmanager — telling JUL to delegate to it keeps test logs sane.
        systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
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
