plugins {
    id("java")
    id("com.gradleup.shadow") version "9.6.1"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

// The real published ActionEvent descriptor. The tests encode and then decode against it rather
// than a hand-built stand-in, so a change to ActionEvent.proto that outruns the column binding
// table fails here instead of in production.
val actionEventDescriptorFile: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    actionEventDescriptorFile(
        project(
            mapOf(
                "path" to ":ekbatan-events-streaming-action-event-protobuf",
                "configuration" to "actionEventDescriptor",
            ),
        ),
    )

    // Provided by the Kafka Connect worker at runtime
    compileOnly("org.apache.kafka:connect-api:${project.property("kafkaClientsVersion")}")
    // Provided by the Connect worker; never bundled.
    compileOnly("org.slf4j:slf4j-api:${project.property("slf4jVersion")}")

    // Shared Debezium value conversions, bundled into the fat JAR by Shadow.
    implementation(project(":ekbatan-events-streaming-debezium-smt-common"))

    implementation("com.google.protobuf:protobuf-java:${project.property("protobufVersion")}")
    implementation("com.google.protobuf:protobuf-java-util:${project.property("protobufVersion")}")

    testImplementation("org.apache.kafka:connect-api:${project.property("kafkaClientsVersion")}")
    testRuntimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
    inputs.files(actionEventDescriptorFile)
    doFirst {
        systemProperty("ekbatan.actionEventDescriptor", actionEventDescriptorFile.singleFile.absolutePath)
    }
}

tasks.shadowJar {
    // Keep every duplicate META-INF path rather than the first. Gradle's default drops later
    // copies before Shadow's transformers ever see them, and the effect was not merely untidy:
    // eight bundled libraries ship a NOTICE and only one survived, while META-INF/LICENSE.txt held
    // four *distinct* licence texts of which three were discarded. Apache-2.0 section 4 requires
    // both the licence and the attribution notices to travel with redistributed code.
    //
    // Safe here because nothing else collides: every class has a unique path once Shadow has
    // relocated the bundled libraries, so this produces no duplicate .class entries. The cost is a
    // few identical licence copies, which is the right side of the trade against losing distinct ones.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    archiveBaseName.set("ekbatan-debezium-smt-protobuf")
    archiveClassifier.set("")
    mergeServiceFiles()

    // No NOTICE merging here, deliberately. Nothing bundled in this jar ships a META-INF/NOTICE -
    // protobuf-java and protobuf-java-util ship none, and Guava ships only a LICENSE - so the
    // Apache notice transformer would create an empty NOTICE rather than merge anything.
    //
    // ApacheLicenseResourceTransformer is also absent on purpose: it *removes* bundled licence
    // files rather than merging them, on the assumption the top-level project supplies its own.
    // Applying it here deleted Guava's Apache-2.0 text and checker-qual's MIT text, which is a
    // worse compliance position than the duplication it was meant to tidy up.
    relocate("com.google.protobuf", "io.ekbatan.shaded.protobuf")
    relocate("com.google.gson", "io.ekbatan.shaded.gson")
}

tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

configurations {
    runtimeElements {
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.shadowJar)
    }
    // apiElements too, not just runtimeElements. Gradle serves the compile classpath from this
    // variant, and it still advertised build/libs/<project-name>-<version>.jar - a file that has
    // never existed here, both because `tasks.jar` is disabled and because the shadow jar carries
    // a different archiveBaseName. Depending on this project for compilation therefore pointed at
    // nothing, while the runtime side worked, which is a confusing pair of symptoms.
    //
    // Nobody should be compiling against a Connect plugin, so this is a signpost with no traffic
    // rather than a live defect. Pointing both variants at the one artifact that exists costs
    // nothing and removes the trap.
    apiElements {
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.shadowJar)
    }
}
