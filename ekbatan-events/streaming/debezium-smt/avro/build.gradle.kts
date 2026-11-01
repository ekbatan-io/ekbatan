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

// The real published ActionEvent schema. The tests encode and then decode against it rather than
// a hand-built stand-in, so a change to ActionEvent.avsc that outruns the column binding table
// fails here instead of in production.
val actionEventSchemaFile: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    actionEventSchemaFile(
        project(
            mapOf(
                "path" to ":ekbatan-events-streaming-action-event-avro",
                "configuration" to "actionEventSchema",
            ),
        ),
    )

    // Provided by the Kafka Connect worker at runtime
    compileOnly("org.apache.kafka:connect-api:${project.property("kafkaClientsVersion")}")
    // compileOnly here, but the slf4j API *is* in the fat JAR regardless - Avro depends on it
    // transitively, so Shadow packs it. That is harmless: Kafka Connect's PluginUtils excludes
    // `org.slf4j` from plugin isolation, so the worker's copy always wins and this one is never
    // loaded. Only the API is present - no provider, no META-INF/services entry - so it cannot
    // hijack logging either.
    compileOnly("org.slf4j:slf4j-api:${project.property("slf4jVersion")}")

    // Shared Debezium value conversions, bundled into the fat JAR by Shadow.
    implementation(project(":ekbatan-events-streaming-debezium-smt-common"))

    implementation("org.apache.avro:avro:${project.property("avroVersion")}")

    // Declared, though Avro would supply it transitively anyway. OutboxToAvroTransform and
    // JsonToAvro use ObjectMapper and JsonNode directly, so borrowing them from Avro's dependency
    // graph left the build silent about a library the code cannot compile without - and the day
    // Avro dropped or moved it, the breakage would look like an Avro problem rather than ours.
    //
    // Jackson 2 rather than the Jackson 3 the framework core uses: Avro 1.12 needs Jackson 2
    // internally, so it is already 2 MB of this 5 MB fat JAR. Switching to Jackson 3 would not
    // replace it, only add a second JSON stack beside it, for one readTree call.
    implementation("com.fasterxml.jackson.core:jackson-databind:${project.property("jackson2Version")}")

    testImplementation("org.apache.kafka:connect-api:${project.property("kafkaClientsVersion")}")
    testRuntimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
    inputs.files(actionEventSchemaFile)
    doFirst {
        systemProperty("ekbatan.actionEventSchema", actionEventSchemaFile.singleFile.absolutePath)
    }
}

// Shadow produces the fat JAR (all runtime deps bundled) that Kafka Connect loads as a plugin.
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
    archiveBaseName.set("ekbatan-debezium-smt-avro")
    archiveClassifier.set("") // primary artifact (no "-all" suffix)
    mergeServiceFiles() // concatenate META-INF/services/* from all deps so ServiceLoader still finds everything

    // Every bundled library ships its own META-INF/NOTICE at the same path, so packing them into
    // one jar made them overwrite each other - only the last survived. Apache-2.0 section 4(d)
    // requires the attribution notices of redistributed work to travel with it, and Avro and the
    // commons-* libraries are all Apache-2.0. This transformer concatenates them instead.
    //
    // Merging rather than renaming: a per-library prefix would also avoid the collision, but
    // licence-scanning tooling looks for META-INF/NOTICE at its conventional path, so renaming
    // would hide the notices from the very thing meant to read them.
    //
    // Note there is deliberately no ApacheLicenseResourceTransformer. Despite the name it *removes*
    // bundled licence files rather than merging them, assuming the top-level project supplies its
    // own. Adding it here deleted META-INF/LICENSE, LICENSE.txt and NOTICE.txt from this jar -
    // strictly worse than the duplication it tidies. The remaining LICENSE collision is benign:
    // the colliding files are all the same Apache-2.0 text, and the differently-licensed ones
    // (FastDoubleParser, thirdparty) already carry distinct names and survive untouched.
    transform(com.github.jengelman.gradle.plugins.shadow.transformers.ApacheNoticeResourceTransformer::class.java) {
        projectName.set("Ekbatan Debezium Avro SMT")
    }
    // Relocate bundled libs into a unique package so our plugin's classloader can never clash
    // with Kafka Connect's own copies of Jackson/Avro/etc.
    relocate("com.fasterxml.jackson", "io.ekbatan.shaded.jackson")
    relocate("org.apache.avro", "io.ekbatan.shaded.avro")
}

// The thin jar has no standalone use — Connect needs the fat jar.
tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// Make consumers of this project (via `project(":ekbatan-events-streaming-debezium-smt-avro")`) receive the
// shadow jar rather than the disabled thin jar.
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
