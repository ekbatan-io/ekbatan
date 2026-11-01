import dev.monosoul.jooq.RecommendedVersions
import org.jooq.meta.jaxb.ForcedType


plugins {
    id("java")
    id("java-test-fixtures")
    id("dev.monosoul.jooq-docker") version "9.0.9"
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

tasks {
    generateJooqClasses {
        schemas.set(listOf("public"))
        basePackageName.set("io.ekbatan.core.test.generated.jooq")
        migrationLocations.setFromFilesystem("src/test/resources/db/migration")
        outputDirectory.set(project.layout.buildDirectory.dir("generated-jooq"))
        flywayProperties.put("flyway.placeholderReplacement", "false")
        includeFlywayTable.set(false)
        outputSchemaToDefault.add("public")
        schemaToPackageMapping.put("public", "")
        usingJavaConfig {
            database.withForcedTypes(
                ForcedType()
                    .withUserType("com.google.gson.JsonElement")
                    .withBinding("com.example.PostgresJSONGsonBinding")
                    .withIncludeTypes("JSONB"),
                ForcedType()
                    .withUserType("java.time.Instant")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.InstantConverter")
                    .withIncludeTypes("TIMESTAMP")
                    .withIncludeExpression(".*"),
            )
        }
    }
}

dependencies {
    testFixturesApi(project(":ekbatan-core"))

    testFixturesApi("org.jooq:jooq:${RecommendedVersions.JOOQ_VERSION}")
    jooqCodegen("org.postgresql:postgresql:${project.property("postgresqlVersion")}")

    // Flyway for database migrations
    testFixturesApi("org.flywaydb:flyway-core:${project.property("flywayVersion")}")
    testFixturesApi("org.flywaydb:flyway-database-postgresql:${project.property("flywayVersion")}")

    testFixturesApi("org.testcontainers:testcontainers-junit-jupiter:${project.property("testcontainersVersion")}")
    testFixturesApi(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testFixturesApi("org.junit.jupiter:junit-jupiter:${project.property("junitJupiterVersion")}")
    testFixturesApi("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")
    testFixturesRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")

    testFixturesApi("org.testcontainers:testcontainers:${project.property("testcontainersVersion")}")
    testFixturesApi("org.testcontainers:testcontainers-postgresql:${project.property("testcontainersVersion")}")

    testFixturesCompileOnly(project(":ekbatan-annotation-processor"))
    testFixturesAnnotationProcessor(project(":ekbatan-annotation-processor"))

    testFixturesApi("io.mockk:mockk:${project.property("mockkVersion")}")
    testFixturesApi("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testFixturesApi("net.javacrumbs.json-unit:json-unit-assertj:${project.property("jsonUnitVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
