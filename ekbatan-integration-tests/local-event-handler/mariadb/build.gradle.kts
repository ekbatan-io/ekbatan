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

jooq {
    withContainer {
        image {
            name = "mariadb:11.8"
            envVars =
                mapOf(
                    "MARIADB_ROOT_PASSWORD" to "root",
                    "MARIADB_DATABASE" to "testdb",
                )
        }
        db {
            username = "root"
            password = "root"
            name = "testdb"
            port = 3306
            jdbc {
                schema = "jdbc:mariadb"
                driverClassName = "org.mariadb.jdbc.Driver"
            }
        }
    }
}

tasks {
    generateJooqClasses {
        schemas.set(listOf("testdb"))
        basePackageName.set("io.ekbatan.test.local_event_handler_mariadb.generated.jooq")
        migrationLocations.setFromFilesystem("src/test/resources/db/migration")
        outputDirectory.set(project.layout.buildDirectory.dir("generated-jooq"))
        flywayProperties.put("flyway.placeholderReplacement", "false")
        includeFlywayTable.set(false)
        outputSchemaToDefault.add("testdb")
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
}

dependencies {
    implementation(project(":ekbatan-core"))
    implementation(project(":ekbatan-distributed-jobs"))
    implementation(project(":ekbatan-events-local-event-handler"))

    implementation("org.jooq:jooq:${RecommendedVersions.JOOQ_VERSION}")

    implementation("org.mariadb.jdbc:mariadb-java-client:${project.property("mariadbJavaClientVersion")}")
    jooqCodegen("org.mariadb.jdbc:mariadb-java-client:${project.property("mariadbJavaClientVersion")}")
    jooqCodegen("org.flywaydb:flyway-mysql:${project.property("flywayVersion")}")

    implementation("org.flywaydb:flyway-core:${project.property("flywayVersion")}")
    implementation("org.flywaydb:flyway-mysql:${project.property("flywayVersion")}")

    implementation("org.apache.commons:commons-lang3:${project.property("commonsLang3Version")}")

    testImplementation(testFixtures(project(":ekbatan-integration-tests-local-event-handler-shared")))
    testImplementation(project(":ekbatan-native"))
    testImplementation(project(":ekbatan-flyway"))
    testImplementation(project(":ekbatan-test-support"))

    testImplementation("org.testcontainers:testcontainers-mariadb:${project.property("testcontainersVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
