rootProject.name = "ekbatan"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    // -Pmicronaut5 runs the Micronaut integration test as a Micronaut 5 app (see gradle.properties).
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.micronaut.library" && settings.providers.gradleProperty("micronaut5").isPresent) {
                useVersion(settings.providers.gradleProperty("micronaut5GradlePluginVersion").get())
            }
        }
    }
}

// Each project's path is its full directory path flattened with '-'. Disk layout is preserved
// via projectDir mappings below. With a single `group = "io.github.ekbatan-io"` in the root build,
// this gives every module a unique Maven coordinate (`io.github.ekbatan-io:<flat-name>:<version>`)
// without per-subtree group rules.
include(
    "ekbatan-core",
    "ekbatan-test-support",
    "ekbatan-native",
    "ekbatan-flyway",
    "ekbatan-events-streaming",
    "ekbatan-events-streaming-action-event-json",
    "ekbatan-events-streaming-action-event-avro",
    "ekbatan-events-streaming-action-event-protobuf",
    "ekbatan-events-streaming-debezium-smt",
    "ekbatan-events-streaming-debezium-smt-common",
    "ekbatan-events-streaming-debezium-smt-avro",
    "ekbatan-events-streaming-debezium-smt-protobuf",
    "ekbatan-events-local-event-handler",
    "ekbatan-integration-tests-core-repo-shared",
    "ekbatan-integration-tests-core-repo-mariadb-repository",
    "ekbatan-integration-tests-core-repo-mariadb-events",
    "ekbatan-integration-tests-core-repo-mysql-repository",
    "ekbatan-integration-tests-core-repo-mysql-events",
    "ekbatan-integration-tests-core-repo-pg-repository",
    "ekbatan-integration-tests-core-repo-pg-events",
    "ekbatan-integration-tests-postgres-simple",
    "ekbatan-integration-tests-postgres-sharded",
    "ekbatan-integration-tests-keyed-lock-provider-pg",
    "ekbatan-integration-tests-keyed-lock-provider-mariadb",
    "ekbatan-integration-tests-keyed-lock-provider-mysql",
    "ekbatan-integration-tests-keyed-lock-provider-redis",
    "ekbatan-integration-tests-event-pipeline",
    "ekbatan-integration-tests-event-pipeline-common",
    "ekbatan-integration-tests-event-pipeline-debezium-kafka-json",
    "ekbatan-integration-tests-event-pipeline-debezium-kafka-avro-smt",
    "ekbatan-integration-tests-event-pipeline-debezium-kafka-protobuf-smt",
    "ekbatan-integration-tests-event-pipeline-debezium-kafka-dialects-smt",
    "ekbatan-integration-tests-distributed-jobs-pg",
    "ekbatan-integration-tests-local-event-handler-shared",
    "ekbatan-integration-tests-local-event-handler-pg",
    "ekbatan-integration-tests-local-event-handler-mariadb",
    "ekbatan-integration-tests-local-event-handler-mysql",
    "ekbatan-annotation-processor",
    "ekbatan-distributed-jobs",
    "ekbatan-keyed-lock-redis",
    "ekbatan-di-annotations",
    "ekbatan-di-spring-autoconfigure",
    "ekbatan-di-spring-starter",
    "ekbatan-di-quarkus-runtime",
    "ekbatan-di-quarkus-deployment",
    "ekbatan-di-micronaut",
    "ekbatan-integration-tests-di-shared",
    "ekbatan-integration-tests-di-spring-boot-starter",
    "ekbatan-integration-tests-di-quarkus",
    "ekbatan-integration-tests-di-micronaut",
)

// Map flat project paths back to their on-disk directory layout.
project(":ekbatan-events-streaming").projectDir = file("ekbatan-events/streaming")
project(":ekbatan-events-streaming-action-event-json").projectDir = file("ekbatan-events/streaming/action-event/json")
project(":ekbatan-events-streaming-action-event-avro").projectDir = file("ekbatan-events/streaming/action-event/avro")
project(":ekbatan-events-streaming-action-event-protobuf").projectDir = file("ekbatan-events/streaming/action-event/protobuf")
project(":ekbatan-events-streaming-debezium-smt").projectDir = file("ekbatan-events/streaming/debezium-smt")
project(":ekbatan-events-streaming-debezium-smt-common").projectDir = file("ekbatan-events/streaming/debezium-smt/common")
project(":ekbatan-events-streaming-debezium-smt-avro").projectDir = file("ekbatan-events/streaming/debezium-smt/avro")
project(":ekbatan-events-streaming-debezium-smt-protobuf").projectDir = file("ekbatan-events/streaming/debezium-smt/protobuf")
project(":ekbatan-events-local-event-handler").projectDir = file("ekbatan-events/local-event-handler")
project(":ekbatan-integration-tests-core-repo-shared").projectDir = file("ekbatan-integration-tests/core-repo/shared")
project(":ekbatan-integration-tests-core-repo-mariadb-repository").projectDir = file("ekbatan-integration-tests/core-repo/mariadb/repository")
project(":ekbatan-integration-tests-core-repo-mariadb-events").projectDir = file("ekbatan-integration-tests/core-repo/mariadb/events")
project(":ekbatan-integration-tests-core-repo-mysql-repository").projectDir = file("ekbatan-integration-tests/core-repo/mysql/repository")
project(":ekbatan-integration-tests-core-repo-mysql-events").projectDir = file("ekbatan-integration-tests/core-repo/mysql/events")
project(":ekbatan-integration-tests-core-repo-pg-repository").projectDir = file("ekbatan-integration-tests/core-repo/pg/repository")
project(":ekbatan-integration-tests-core-repo-pg-events").projectDir = file("ekbatan-integration-tests/core-repo/pg/events")
project(":ekbatan-integration-tests-postgres-simple").projectDir = file("ekbatan-integration-tests/postgres-simple")
project(":ekbatan-integration-tests-postgres-sharded").projectDir = file("ekbatan-integration-tests/postgres-sharded")
project(":ekbatan-integration-tests-keyed-lock-provider-pg").projectDir = file("ekbatan-integration-tests/keyed-lock-provider/pg")
project(":ekbatan-integration-tests-keyed-lock-provider-mariadb").projectDir = file("ekbatan-integration-tests/keyed-lock-provider/mariadb")
project(":ekbatan-integration-tests-keyed-lock-provider-mysql").projectDir = file("ekbatan-integration-tests/keyed-lock-provider/mysql")
project(":ekbatan-integration-tests-keyed-lock-provider-redis").projectDir = file("ekbatan-integration-tests/keyed-lock-provider/redis")
project(":ekbatan-integration-tests-event-pipeline").projectDir = file("ekbatan-integration-tests/event-pipeline")
project(":ekbatan-integration-tests-event-pipeline-common").projectDir = file("ekbatan-integration-tests/event-pipeline/common")
project(":ekbatan-integration-tests-event-pipeline-debezium-kafka-json").projectDir = file("ekbatan-integration-tests/event-pipeline/debezium-kafka-json")
project(":ekbatan-integration-tests-event-pipeline-debezium-kafka-avro-smt").projectDir = file("ekbatan-integration-tests/event-pipeline/debezium-kafka-avro-smt")
project(":ekbatan-integration-tests-event-pipeline-debezium-kafka-protobuf-smt").projectDir = file("ekbatan-integration-tests/event-pipeline/debezium-kafka-protobuf-smt")
project(":ekbatan-integration-tests-event-pipeline-debezium-kafka-dialects-smt").projectDir = file("ekbatan-integration-tests/event-pipeline/debezium-kafka-dialects-smt")
project(":ekbatan-integration-tests-distributed-jobs-pg").projectDir = file("ekbatan-integration-tests/distributed-jobs-pg")
project(":ekbatan-integration-tests-local-event-handler-shared").projectDir = file("ekbatan-integration-tests/local-event-handler/shared")
project(":ekbatan-integration-tests-local-event-handler-pg").projectDir = file("ekbatan-integration-tests/local-event-handler/pg")
project(":ekbatan-integration-tests-local-event-handler-mariadb").projectDir = file("ekbatan-integration-tests/local-event-handler/mariadb")
project(":ekbatan-integration-tests-local-event-handler-mysql").projectDir = file("ekbatan-integration-tests/local-event-handler/mysql")
project(":ekbatan-di-annotations").projectDir = file("ekbatan-di/annotations")
project(":ekbatan-di-spring-autoconfigure").projectDir = file("ekbatan-di/spring/autoconfigure")
project(":ekbatan-di-spring-starter").projectDir = file("ekbatan-di/spring/starter")
project(":ekbatan-di-quarkus-runtime").projectDir = file("ekbatan-di/quarkus/runtime")
project(":ekbatan-di-quarkus-deployment").projectDir = file("ekbatan-di/quarkus/deployment")
project(":ekbatan-di-micronaut").projectDir = file("ekbatan-di/micronaut")
project(":ekbatan-integration-tests-di-shared").projectDir = file("ekbatan-integration-tests/di/shared")
project(":ekbatan-integration-tests-di-spring-boot-starter").projectDir = file("ekbatan-integration-tests/di/spring-boot-starter")
project(":ekbatan-integration-tests-di-quarkus").projectDir = file("ekbatan-integration-tests/di/quarkus")
project(":ekbatan-integration-tests-di-micronaut").projectDir = file("ekbatan-integration-tests/di/micronaut")

// Libraries with known security holes that our build tools and test setups pull in. For each, the
// first release that fixes them, one per version line. Nothing here ships to users.
val securityFixes = mapOf(
    // Avro Gradle plugin; Debezium's test library; JReleaser; the Quarkus Gradle plugins
    "com.fasterxml.jackson.core:jackson-core" to listOf("2.18.11", "2.21.7", "2.22.3"),
    "com.fasterxml.jackson.core:jackson-databind" to listOf("2.18.11", "2.21.7", "2.22.3"),
    // Avro Gradle plugin
    "org.apache.avro:avro" to listOf("1.11.5"),
    "org.apache.avro:avro-compiler" to listOf("1.11.5"),
    "org.apache.commons:commons-compress" to listOf("1.26.0"),
    // Avro Gradle plugin; Spring Boot Gradle plugin
    "org.apache.commons:commons-lang3" to listOf("3.18.0"),
    // JReleaser
    "org.apache.tika:tika-core" to listOf("3.2.2"),
    "org.apache.httpcomponents.client5:httpclient5" to listOf("5.6.3"),
    "org.apache.httpcomponents.core5:httpcore5-h2" to listOf("5.4.3"),
    // JReleaser (3.x); the Quarkus Gradle plugins (4.x)
    "org.codehaus.plexus:plexus-utils" to listOf("3.6.1", "4.0.3"),
    // Checkstyle; the Quarkus Gradle plugins and test app
    "com.google.guava:guava" to listOf("33.7.2-jre"),
    // Quarkus's version list pins it below what ekbatan-core asks for, in the Quarkus tests
    "io.opentelemetry:opentelemetry-api" to listOf("1.67.0"),
    // Debezium's test library. okhttp 4.12.0 rather than the first fix (4.9.2): it is the release
    // built for okio 3, the first okio line with a fix.
    "com.squareup.okhttp3:okhttp" to listOf("4.12.0"),
    "com.squareup.okio:okio" to listOf("3.4.0"),
    // Kafka's client, in tests
    "at.yawk.lz4:lz4-java" to listOf("1.11.4"),
    "org.xerial.snappy:snappy-java" to listOf("1.1.10.9"),
)

fun versionParts(version: String) = Regex("\\d+").findAll(version).map { it.value.toLong() }.toList()

fun compareVersions(a: List<Long>, b: List<Long>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val diff = a.getOrElse(i) { 0L }.compareTo(b.getOrElse(i) { 0L })
        if (diff != 0) return diff
    }
    return 0
}

// The fixed release `version` should move to, or null when it needs no fix. The fixes of its own
// minor line decide, else those of its major line; a line with no fix at all moves to the first
// fix after it.
fun securityFixFor(module: String, version: String): String? {
    val requested = versionParts(version)
    val fixes = securityFixes[module] ?: return null
    fun sameLine(fix: String, parts: Int) = versionParts(fix).take(parts) == requested.take(parts)
    val line = fixes.filter { sameLine(it, 2) }.ifEmpty { fixes.filter { sameLine(it, 1) } }.ifEmpty { fixes }
    return line.firstOrNull { compareVersions(versionParts(it), requested) > 0 }
}

// Raises those libraries to their fixed release in every plugin classpath and every configuration
// of every project - except the compileClasspath and runtimeClasspath of the published modules,
// which show what users get: those versions come from each module's own dependencies, and the
// weekly dependency check (.github/workflows/dependency-check.yml) must keep seeing them as they
// are. Drop an entry once the tools that pull the library in have moved past the fix themselves.
gradle.beforeProject {
    val raise = Action<DependencyResolveDetails> {
        val version = requested.version
        if (!version.isNullOrEmpty()) {
            securityFixFor("${requested.group}:${requested.name}", version)?.let {
                useVersion(it)
                because("known security holes before $it (settings.gradle.kts)")
            }
        }
    }
    val published = !path.startsWith(":ekbatan-integration-tests")
    buildscript.configurations.configureEach { resolutionStrategy.eachDependency(raise) }
    configurations.configureEach {
        if (!(published && name in setOf("compileClasspath", "runtimeClasspath"))) {
            resolutionStrategy.eachDependency(raise)
        }
    }
}
