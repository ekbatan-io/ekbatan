plugins {
    id("java")
    id("com.google.protobuf") version "0.10.0"
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

// SMT fat-jar for mounting into the Debezium container
val smtPluginJar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// ActionEvent descriptor from action-event:protobuf
val actionEventDescriptorFile: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    smtPluginJar(project(":ekbatan-events-streaming-debezium-smt-protobuf")) {
        isTransitive = false
    }
    actionEventDescriptorFile(
        project(
            mapOf(
                "path" to ":ekbatan-events-streaming-action-event-protobuf",
                "configuration" to "actionEventDescriptor",
            ),
        ),
    )

    testImplementation(project(":ekbatan-events-streaming-action-event-protobuf"))
    testImplementation(project(":ekbatan-integration-tests-event-pipeline-common"))

    testImplementation("com.google.protobuf:protobuf-java:${project.property("protobufVersion")}")
    testImplementation("com.google.protobuf:protobuf-java-util:${project.property("protobufVersion")}")

    testImplementation(project(":ekbatan-native"))
    testImplementation(project(":ekbatan-flyway"))
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:${project.property("testcontainersVersion")}")
    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")

    testImplementation("org.testcontainers:testcontainers:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-kafka:${project.property("testcontainersVersion")}")

    // See debezium-kafka-json/build.gradle.kts for the exclusion rationale: Debezium's
    // test wrapper transitively pulls Quarkus + jboss-logmanager which break the native
    // test image. The two classes our tests use don't need them at runtime.
    testImplementation("io.debezium:debezium-testing-testcontainers:${project.property("debeziumVersion")}") {
        exclude(group = "io.quarkus")
        exclude(group = "io.quarkus.gizmo")
        exclude(group = "org.jboss.logmanager")
    }
    testImplementation("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${project.property("protobufVersion")}"
    }
    generateProtoTasks {
        all().configureEach {
            generateDescriptorSet = true
            descriptorSetOptions.includeImports = true
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    dependsOn(smtPluginJar, actionEventDescriptorFile, "generateTestProto")
    doFirst {
        systemProperty("smt.plugin.jar", smtPluginJar.singleFile.absolutePath)
        systemProperty(
            "smt.payload.descriptors",
            project.layout.buildDirectory
                .file("generated/sources/proto/test/descriptor_set.desc")
                .get()
                .asFile.absolutePath,
        )
        systemProperty("smt.action.event.descriptor", actionEventDescriptorFile.singleFile.absolutePath)
    }
}

// `nativeTest` is a NativeRunTask, not a Test task. Mirror the -D system properties
// via runtimeArgs so the test class's static initialiser can resolve them when running
// as a native binary.
extensions.configure<org.graalvm.buildtools.gradle.dsl.GraalVMExtension> {
    binaries.named("test") {
        runtimeArgs.addAll(
            provider {
                listOf(
                    "-Dsmt.plugin.jar=${smtPluginJar.singleFile.absolutePath}",
                    "-Dsmt.payload.descriptors=${project.layout.buildDirectory.file(
                        "generated/sources/proto/test/descriptor_set.desc",
                    ).get().asFile.absolutePath}",
                    "-Dsmt.action.event.descriptor=${actionEventDescriptorFile.singleFile.absolutePath}",
                )
            },
        )
    }
}
tasks.named("nativeTest") { dependsOn(smtPluginJar, actionEventDescriptorFile, "generateTestProto") }
