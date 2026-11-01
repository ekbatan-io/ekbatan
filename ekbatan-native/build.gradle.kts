plugins {
    `java-library`
    id("ekbatan.publishing")
}

ekbatanPublishing {
    artifactId.set("ekbatan-native")
    description.set("GraalVM native-image features for Ekbatan (Jackson 3 records, Kafka clients, testcontainers, Avro).")
}

java.sourceCompatibility = JavaVersion.VERSION_25
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Set an explicit Automatic-Module-Name in the manifest. Without this, the JPMS
// module name is auto-derived from the JAR file name ("ekbatan-native") as
// "ekbatan.native" — and `native` is a Java reserved keyword, which makes the
// auto-derived name invalid. Tools that consult JPMS metadata emit a warning or
// refuse to load it. Picking a non-keyword tail keeps everything happy.
tasks.named<Jar>("jar") {
    manifest {
        attributes("Automatic-Module-Name" to "io.ekbatan.graalvm")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // GraalVM hosted API (Feature, RuntimeReflection) — provided by native-image at build
    // time, never on the JVM runtime classpath.
    compileOnly("org.graalvm.nativeimage:svm:25.0.5")

    // ClassGraph powers the build-time classpath scan inside Jackson3RecordsFeature.
    // It must reach the native-image build classpath, so it ships transitively.
    api("io.github.classgraph:classgraph:6.0.0")
}
