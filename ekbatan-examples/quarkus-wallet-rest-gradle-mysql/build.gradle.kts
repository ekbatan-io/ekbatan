import org.jooq.meta.jaxb.ForcedType

plugins {
    java
    id("io.quarkus") version "3.40.1.1"
    id("dev.monosoul.jooq-docker") version "9.0.9"
    id("com.diffplug.spotless") version "8.10.4"
}

group = "io.example"
version = "1.0.0-SNAPSHOT"

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
val mysqlConnectorVersion = "26.10.0"
val flywayVersion = "13.10.3"

// The jooq-docker plugin needs a MySQL container at codegen time. Run as root so V0000's
// `CREATE DATABASE eventlog` succeeds without an init script.
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
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:$quarkusVersion"))

    implementation("io.github.ekbatan-io:ekbatan-quarkus:$ekbatanVersion")
    implementation("io.github.ekbatan-io:ekbatan-flyway:$ekbatanVersion")
    compileOnly("io.github.ekbatan-io:ekbatan-annotation-processor:$ekbatanVersion")
    annotationProcessor("io.github.ekbatan-io:ekbatan-annotation-processor:$ekbatanVersion")

    implementation("org.apache.commons:commons-lang3:3.21.0")

    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")

    implementation("com.mysql:mysql-connector-j:$mysqlConnectorVersion")
    jooqCodegen("com.mysql:mysql-connector-j:$mysqlConnectorVersion")

    // EkbatanShardFlywayMigrator runs Flyway over every shard from ekbatan.sharding.*.
    // quarkus-flyway stays on the classpath for Flyway/native-image integration.
    implementation("io.quarkus:quarkus-flyway")
    implementation("org.flywaydb:flyway-mysql")
    // JDBC driver: quarkus-jdbc-mysql pulls the driver JAR + registers the class
    // reflectively for native (no-op in JVM but harmless).
    implementation("io.quarkus:quarkus-jdbc-mysql")
    jooqCodegen("org.flywaydb:flyway-mysql:$flywayVersion")

    testImplementation("io.quarkus:quarkus-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("org.testcontainers:testcontainers-mysql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
}

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat("2.81.0")
    }
    format("misc") {
        target("*.md", ".gitignore", ".gitattributes", "*.yaml", "*.yml", "*.json", "*.properties")
        trimTrailingWhitespace()
        leadingTabsToSpaces(4)
        endWithNewline()
    }
}

tasks {
    generateJooqClasses {
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
                // MySQL has no native UUID type — every UUID column is CHAR(36) CHARACTER SET ascii.
                // UuidStringConverter bridges back to java.util.UUID. Scope to id/*_id columns only.
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

    withType<Test> {
        useJUnitPlatform()
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
