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
val flywayVersion = "12.0.0"
val mysqlConnectorVersion = "9.4.0"

// jOOQ codegen needs a running MySQL container to introspect the live schema. The plugin spins
// one up at build time, runs Flyway against it, then walks the schema and writes Java classes
// into build/generated-jooq/. Container config has to match application.yml's runtime expectation
// of where the eventlog database lives — both are MySQL, identical dialect.
jooq {
    withContainer {
        image {
            name = "mysql:9.4.0"
            envVars =
                mapOf(
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

dependencies {
    // ── Ekbatan ─────────────────────────────────────────────────────────────
    implementation("io.github.ekbatan-io:ekbatan-spring-boot-starter:$ekbatanVersion")
    implementation("io.github.ekbatan-io:ekbatan-flyway:$ekbatanVersion")
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

    // ── Flyway via Spring Boot's official starter ─────────────────────────
    // spring-boot-starter-flyway keeps Flyway on the classpath; the Ekbatan
    // starter filters Boot's single-datasource auto-run, and
    // EkbatanShardFlywayMigrator runs migrations over every shard from ekbatan.sharding.*. The
    // flyway-mysql artifact also covers MariaDB JDBC URLs (the name is historical).
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-mysql")

    // ── MySQL ───────────────────────────────────────────────────────────────
    // Explicit version: Spring Boot 4.0.x's BOM pins it but we may want a newer connector for
    // the latest TLS behaviour.
    runtimeOnly("com.mysql:mysql-connector-j:$mysqlConnectorVersion")
    jooqCodegen("com.mysql:mysql-connector-j:$mysqlConnectorVersion")
    // The codegen task migrates the container with Flyway, so it needs flyway-mysql too.
    jooqCodegen("org.flywaydb:flyway-mysql:$flywayVersion")

    // ── Tests ───────────────────────────────────────────────────────────────
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Gradle 9 + Spring Boot 4's BOM no longer pulls junit-platform-launcher transitively,
    // so it has to be declared explicitly. Without it the test runner fails at startup with
    // "Failed to load JUnit Platform".
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Note: in testcontainers 2.x the modules were renamed:
    //   org.testcontainers:mysql            → org.testcontainers:testcontainers-mysql
    //   org.testcontainers:junit-jupiter    → org.testcontainers:testcontainers-junit-jupiter
    testImplementation("org.testcontainers:testcontainers-mysql:2.0.5")
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
        // MySQL has no schemas (schema = database), so only the main 'wallet' DB is generated.
        // The eventlog tables are accessed through the framework's own field constants — no
        // user-facing codegen for them needed.
        schemas.set(listOf("wallet"))
        basePackageName.set("io.example.wallet.generated.jooq")
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
                // MySQL has no native UUID type; columns are CHAR(36) CHARACTER SET ascii.
                // UuidStringConverter maps them back to java.util.UUID at the jOOQ layer so
                // application code stays dialect-agnostic. Restrict the converter to columns
                // named `id` or ending in `_id` — without that, unrelated CHAR(36) columns
                // (handler names, status enums) would also get bound to UUID and break.
                ForcedType()
                    .withUserType("java.util.UUID")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.mysql.UuidStringConverter")
                    .withIncludeTypes("CHAR\\(36\\)")
                    .withIncludeExpression(".*\\.id|.*_id"),
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
