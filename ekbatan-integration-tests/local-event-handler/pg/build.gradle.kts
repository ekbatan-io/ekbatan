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
        schemas.set(listOf("public", "eventlog"))
        basePackageName.set("io.ekbatan.test.local_event_handler_pg.generated.jooq")
        migrationLocations.setFromFilesystem("src/test/resources/db/migration")
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
}

dependencies {
    implementation(project(":ekbatan-core"))
    implementation(project(":ekbatan-distributed-jobs"))
    implementation(project(":ekbatan-events-local-event-handler"))

    implementation("org.jooq:jooq:${RecommendedVersions.JOOQ_VERSION}")

    implementation("org.postgresql:postgresql:${project.property("postgresqlVersion")}")
    jooqCodegen("org.postgresql:postgresql:${project.property("postgresqlVersion")}")

    implementation("org.flywaydb:flyway-core:${project.property("flywayVersion")}")
    implementation("org.flywaydb:flyway-database-postgresql:${project.property("flywayVersion")}")

    implementation("org.apache.commons:commons-lang3:${project.property("commonsLang3Version")}")

    testImplementation(testFixtures(project(":ekbatan-integration-tests-local-event-handler-shared")))
    testImplementation(project(":ekbatan-native"))
    testImplementation(project(":ekbatan-flyway"))

    testImplementation("org.testcontainers:testcontainers-postgresql:${project.property("testcontainersVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
