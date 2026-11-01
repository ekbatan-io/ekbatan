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
