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
        basePackageName.set("io.ekbatan.test.event_pipeline.common.generated.jooq")
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
}

dependencies {
    api(project(":ekbatan-core"))
    api(project(":ekbatan-events-streaming-action-event-json"))
    api("org.jooq:jooq:${RecommendedVersions.JOOQ_VERSION}")

    jooqCodegen("org.postgresql:postgresql:${project.property("postgresqlVersion")}")
    api("org.postgresql:postgresql:${project.property("postgresqlVersion")}")
    api("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")

    api("org.flywaydb:flyway-core:${project.property("flywayVersion")}")
    api("org.flywaydb:flyway-database-postgresql:${project.property("flywayVersion")}")

    api("org.apache.commons:commons-lang3:${project.property("commonsLang3Version")}")
    api("org.apache.kafka:kafka-clients:${project.property("kafkaClientsVersion")}")
    api("tools.jackson.core:jackson-databind:${project.property("jacksonDatabindVersion")}")

    compileOnly(project(":ekbatan-annotation-processor"))
    annotationProcessor(project(":ekbatan-annotation-processor"))
}
