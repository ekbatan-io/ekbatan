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
// Not MariaDB's driver 4: Quarkus 3.40 builds and tests quarkus-jdbc-mariadb with 3.5.10, and its
// enforced platform keeps that version at runtime anyway.
val mariadbDriverVersion = "3.5.11"
val flywayVersion = "13.10.3"

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
    implementation("io.github.ekbatan-io:ekbatan-quarkus:$ekbatanVersion")
    implementation("io.github.ekbatan-io:ekbatan-flyway:$ekbatanVersion")
    compileOnly("io.github.ekbatan-io:ekbatan-annotation-processor:$ekbatanVersion")
    annotationProcessor("io.github.ekbatan-io:ekbatan-annotation-processor:$ekbatanVersion")

    implementation("org.apache.commons:commons-lang3:3.21.0")

    // Native-image extras: ships GraalVM Features (Jackson 3 records, builders, generated
    // jOOQ classes, etc.) plus HikariCP reachability metadata. Only needed because we want
    // a native binary; the JVM-only sibling doesn't include it.
    implementation("io.github.ekbatan-io:ekbatan-native:$ekbatanVersion")

    // ── Quarkus REST ────────────────────────────────────────────────────────
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")

    // ── MariaDB ─────────────────────────────────────────────────────────────
    implementation("org.mariadb.jdbc:mariadb-java-client:$mariadbDriverVersion")
    jooqCodegen("org.mariadb.jdbc:mariadb-java-client:$mariadbDriverVersion")
    // quarkus-jdbc-mariadb registers the org.mariadb.jdbc.Driver reflectively for native-image.
    // We don't use Quarkus's data source — Ekbatan's ConnectionProvider runs Hikari itself —
    // but the driver still needs to be reachable when Flyway / Hikari look it up via the
    // Class.forName(...) path inside a native binary.
    implementation("io.quarkus:quarkus-jdbc-mariadb")
    // EkbatanShardFlywayMigrator runs Flyway over every shard from ekbatan.sharding.*.
    // quarkus-flyway stays on the classpath for Flyway/native-image integration.
    implementation("io.quarkus:quarkus-flyway")

    // ── Flyway ──────────────────────────────────────────────────────────────
    implementation("org.flywaydb:flyway-mysql")
    jooqCodegen("org.flywaydb:flyway-mysql:$flywayVersion")

    // ── Tests ───────────────────────────────────────────────────────────────
    testImplementation("io.quarkus:quarkus-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.awaitility:awaitility:4.3.0")
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

    // Quarkus's standard split: regular `test` runs JVM-mode @QuarkusTest classes from
    // src/test/java; `quarkusIntTest` (alias for `testNative` when native is enabled) runs
    // *IT.java from src/integrationTest/java against the packaged jar / native binary. By
    // default JUnit Platform would discover *IT.java from src/test/java as well — exclude
    // them so the regular `test` task doesn't try to connect to a packaged app that isn't up.
    named<Test>("test") {
        exclude("**/*IT.class")
        failOnNoDiscoveredTests = false
    }
}

sourceSets {
    main {
        java {
            srcDir(tasks.generateJooqClasses.flatMap { it.outputDirectory })
        }
    }
}

// Quarkus's integrationTest source set compiles separately from the main `test` source set.
// MariaDBTestResource lives in src/test/java because it's also used by the JVM @QuarkusTest;
// without the line below, @QuarkusTestResource silently can't load it at IT launch time and
// the native binary boots without ekbatan.sharding config → startup fails.
sourceSets.named("integrationTest") {
    compileClasspath += sourceSets.named("test").get().output
    runtimeClasspath += sourceSets.named("test").get().output
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
