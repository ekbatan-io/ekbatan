import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("java")
    // Spring Boot's plugin is applied here purely for the AOT story: SpringBootAotPlugin
    // (auto-applied by org.springframework.boot) registers the `processTestAot` task and
    // wires it into `nativeTest`, generating AotTestContextInitializers + RuntimeHints
    // for every @SpringBootTest context. Without this, BootstrapUtils.<clinit> hits
    // Class.forName at runtime and the native test image fails before any test starts.
    id("org.springframework.boot") version "4.1.2"
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

// This module produces no runnable application - it's only an integration-test harness.
// Keep the regular jar; disable bootJar so Gradle doesn't complain about a missing main.
// `processAot` is the production-side Spring AOT task (auto-wired into `build` by the
// Spring Boot plugin); disable it too — it requires a main class and fails `clean build`
// otherwise. The test-side `processTestAot` stays enabled (that's what `nativeTest` needs).
tasks.named<BootJar>("bootJar") { enabled = false }
tasks.named<Jar>("jar") { enabled = true }
tasks.named("processAot") { enabled = false }

// The root build.gradle.kts already sets `toolchainDetection.set(true)` on the GraalVM
// extension, but applying the org.springframework.boot plugin reorders extension
// configuration and the Spring Boot AOT integration ends up reading nativeTest's
// runtimeArgs through the env-var fallback (~/.sdkman/.../current/bin/native-image).
// Re-asserting the Java 25 native-image-capable toolchain here puts the detection
// back in front without depending on one exact vendor label.
extensions.configure<org.graalvm.buildtools.gradle.dsl.GraalVMExtension> {
    toolchainDetection.set(true)
    binaries.all {
        javaLauncher.set(
            javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(25))
                nativeImageCapable.set(true)
            },
        )
    }
}

dependencies {
    // Pulls in widget domain + jOOQ-generated tables + Flyway migrations + the Ekbatan core /
    // local-event-handler / distributed-jobs jars.
    implementation(project(":ekbatan-integration-tests-di-shared"))
    implementation(project(":ekbatan-di-spring-starter"))

    // Test
    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation(project(":ekbatan-native"))
    testImplementation(project(":ekbatan-flyway"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testImplementation("org.testcontainers:testcontainers:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${project.property("testcontainersVersion")}")
    testImplementation("org.springframework.boot:spring-boot-starter-test:${project.property("springBootVersion")}")
    testImplementation("org.awaitility:awaitility:${project.property("awaitilityVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
