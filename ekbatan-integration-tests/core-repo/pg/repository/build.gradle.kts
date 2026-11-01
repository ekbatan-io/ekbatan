import dev.monosoul.jooq.RecommendedVersions
import org.jooq.meta.jaxb.ForcedType


plugins {
    id("java")
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
                    .withUserType("tools.jackson.databind.node.ArrayNode")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.JSONBArrayNodeConverter")
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
    implementation(project(":ekbatan-core"))

    implementation("org.jooq:jooq:${RecommendedVersions.JOOQ_VERSION}")
    implementation("org.postgresql:postgresql:${project.property("postgresqlVersion")}")
    jooqCodegen("org.postgresql:postgresql:${project.property("postgresqlVersion")}")

    // Add explicit dependency on the JOOQ API

    // Flyway for database migrations
    implementation("org.flywaydb:flyway-core:${project.property("flywayVersion")}")
    implementation("org.flywaydb:flyway-database-postgresql:${project.property("flywayVersion")}")

    testImplementation("org.testcontainers:testcontainers-junit-jupiter:${project.property("testcontainersVersion")}")
    testImplementation(project(":ekbatan-native"))
    testImplementation(project(":ekbatan-flyway"))
    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")

    testImplementation(testFixtures(project(":ekbatan-integration-tests-core-repo-shared")))
    testImplementation("org.testcontainers:testcontainers:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${project.property("testcontainersVersion")}")

    testCompileOnly(project(":ekbatan-annotation-processor"))
    testAnnotationProcessor(project(":ekbatan-annotation-processor"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}
