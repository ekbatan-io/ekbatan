import org.jooq.meta.jaxb.ForcedType

plugins {
    java
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
val mysqlConnectorVersion = "26.10.0"
val flywayVersion = "13.10.3"

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

micronaut {
    version(micronautVersion)
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("io.example.wallet.*")
    }
}

application {
    mainClass.set("io.example.wallet.Application")
}

dependencies {
    implementation("io.github.ekbatan-io:ekbatan-micronaut:$ekbatanVersion")
    implementation("io.github.ekbatan-io:ekbatan-flyway:$ekbatanVersion")
    annotationProcessor("io.github.ekbatan-io:ekbatan-micronaut:$ekbatanVersion")
    compileOnly("io.github.ekbatan-io:ekbatan-annotation-processor:$ekbatanVersion")
    annotationProcessor("io.github.ekbatan-io:ekbatan-annotation-processor:$ekbatanVersion")
    // micronaut-serde-processor generates compile-time `Serializer`/`Deserializer`
    // beans for any class annotated with `@Serdeable`. Required by `micronaut-serde-jackson`.
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")

    implementation("org.apache.commons:commons-lang3:3.21.0")

    // micronaut-serde-jackson — Jackson-compatible (de)serialization using
    // compile-time generated serdes instead of Jackson Databind's reflection. This is
    // the native-image-friendly path (no reflection metadata for HTTP DTOs). Standard
    // Jackson annotations (@JsonProperty, @JsonCreator) are honoured; types must carry
    // @Serdeable to opt in.
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")

    // ── Flyway support ───────────────────────────────────────────────────────
    // ekbatan-flyway runs migrations from an eager startup bean using typed ShardingConfig.
    // micronaut-flyway stays on the classpath for Flyway and native-image support.
    implementation("io.micronaut.flyway:micronaut-flyway")
    implementation("org.flywaydb:flyway-mysql")

    runtimeOnly("com.mysql:mysql-connector-j:$mysqlConnectorVersion")
    jooqCodegen("com.mysql:mysql-connector-j:$mysqlConnectorVersion")
    jooqCodegen("org.flywaydb:flyway-mysql:$flywayVersion")

    testImplementation("io.micronaut:micronaut-http-client")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.testcontainers:testcontainers-mysql:2.0.5")
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
                // MySQL has no native UUID type — CHAR(36) CHARACTER SET ascii. UuidStringConverter
                // bridges back to java.util.UUID. Scope to id/*_id columns only.
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
