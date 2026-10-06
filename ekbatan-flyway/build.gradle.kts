plugins {
    `java-library`
    id("ekbatan.publishing")
}

ekbatanPublishing {
    artifactId.set("ekbatan-flyway")
    description.set("Flyway migration utilities for single-shard and sharded Ekbatan applications.")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":ekbatan-core"))
    api("org.flywaydb:flyway-core:${project.property("flywayVersion")}")

    // Migrations connect through DataSources.driverDataSource, which is Hikari's own
    // DriverDataSource. ekbatan-core keeps HikariCP compileOnly; this module needs it at runtime
    // and ships it there, so a migration works without an extra dependency line - and, being
    // runtimeOnly, keeps Hikari off the consumer's compile classpath.
    runtimeOnly("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")

    // A real SLF4J backend for the tests only. Without one the MDC is a no-op, and a test of what
    // the migrator puts in it would pass without proving anything.
    testRuntimeOnly("org.slf4j:slf4j-jdk14:${project.property("slf4jVersion")}")
}
