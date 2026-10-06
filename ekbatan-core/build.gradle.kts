plugins {
    `java-library`
    id("ekbatan.publishing")
}

ekbatanPublishing {
    artifactId.set("ekbatan-core")
    description.set("Core action/repository/persister framework for Ekbatan.")
}

java.sourceCompatibility = JavaVersion.VERSION_25
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {

    // jOOQ runtime DSL. The codegen-time artifacts (jooq-meta, jooq-codegen) are deliberately
    // NOT declared here: consumers run jOOQ codegen via a Gradle/Maven plugin (e.g.
    // dev.monosoul.jooq-docker, org.jooq:jooq-codegen-maven), whose plugin classpath provides
    // them - they're not needed on the framework's transitive api graph.
    //
    // r2dbc-spi stays as a transitive (we don't use it at runtime, but jOOQ's DSL.using has
    // overloads typed against io.r2dbc.spi.ConnectionFactory; excluding it breaks overload
    // resolution at consumer compile time, even for pure JDBC callers).
    api("org.jooq:jooq:${project.property("jooqVersion")}")

    // PostgreSQL JDBC driver — testImplementation only. ekbatan-core itself doesn't reference
    // Postgres APIs; downstream apps bring their own JDBC driver matching their dialect. Pinning
    // it as `implementation` here would force every MariaDB/MySQL consumer to also inherit
    // Postgres on their runtime classpath, which clutters the artifact graph and (more sharply)
    // trips GraalVM native-image with NoClassDefFoundError on Postgres's SSPI/JNA stack when the
    // reachability-metadata repo registers those classes against a classpath that doesn't have
    // JNA. Test fixtures still need the driver for the integration-test runners.
    testImplementation("org.postgresql:postgresql:${project.property("postgresqlVersion")}")
    // The other two supported drivers, so the check that a URL carries no password is tested with
    // each driver's own URL parser - the one that would send it.
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client:${project.property("mariadbJavaClientVersion")}")
    testRuntimeOnly("com.mysql:mysql-connector-j:${project.property("mysqlConnectorVersion")}")

    // HikariCP is compileOnly: the framework calls Hikari-specific APIs internally
    // (HikariConfig, HikariDataSource#evictConnection), but does not expose Hikari types on its
    // public API, and does NOT ship Hikari on consumers' runtime classpath. Standalone JDK
    // consumers must declare HikariCP themselves; the Spring Boot starter, Quarkus extension,
    // and Micronaut module bring it in for users of those DI integrations.
    compileOnly("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")
    testImplementation("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testImplementation("org.mockito:mockito-core:${project.property("mockitoVersion")}")
    testImplementation("net.javacrumbs.json-unit:json-unit-assertj:${project.property("jsonUnitVersion")}")
    testImplementation("tools.jackson.dataformat:jackson-dataformat-yaml:${project.property("jacksonDatabindVersion")}")
    // The reader every DI integration binds the sharding config with, for the round-trip test of
    // driver setting names.
    testImplementation("tools.jackson.dataformat:jackson-dataformat-properties:${project.property("jacksonDatabindVersion")}")
    testImplementation(project(":ekbatan-test-support"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Jackson for JSON serialization
    api("tools.jackson.core:jackson-databind:${project.property("jacksonDatabindVersion")}")

    // SLF4J API (no-op when no backend is present)
    api("org.slf4j:slf4j-api:${project.property("slf4jVersion")}")

    // OpenTelemetry API (no-op when no SDK is present)
    api("io.opentelemetry:opentelemetry-api:${project.property("opentelemetryVersion")}")

    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:${project.property("opentelemetryVersion")}")

    // YAML support for ShardingConfig deserialization sanity-check tests
    testImplementation("tools.jackson.dataformat:jackson-dataformat-yaml:${project.property("jacksonDatabindVersion")}")

    // GraalVM SVM API for the @TargetClass / @Substitute fix to jOOQ's Internal.arrayType.
    // compileOnly because the API is provided by native-image at AOT time and is never on
    // the JVM runtime classpath; the substitution class is invisible to JVM execution.
    compileOnly("org.graalvm.nativeimage:svm:25.0.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Tracing tests require a separate JVM fork because the OTel SDK must be registered
// before any instrumented class loads its static Tracer field via GlobalOpenTelemetry.
tasks.register<Test>("tracingTest") {
    useJUnitPlatform {
        includeTags("tracing")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("tracing")
    }
    finalizedBy("tracingTest")
}
