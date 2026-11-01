import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    `java-library`
    checkstyle
    id("com.diffplug.spotless")
}

repositories {
    mavenCentral()
}

configure<CheckstyleExtension> {
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    toolVersion = "14.3.0"
    isIgnoreFailures = false
    isShowViolations = true
    reportsDir = layout.buildDirectory.dir("reports/checkstyle").get().asFile
}

configure<SpotlessExtension> {
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
        trimTrailingWhitespace()
        leadingTabsToSpaces(4)
        endWithNewline()
    }
    format("misc") {
        target("*.md", ".gitignore", ".gitattributes", "*.yaml", "*.yml", "*.json")
        trimTrailingWhitespace()
        leadingTabsToSpaces(4)
        endWithNewline()
    }
}

dependencies {
    // ekbatan-annotation-processor is NOT wired here: @AutoBuilder is a consumer concern
    // (applied to concrete domain classes like Wallet/Order), not a framework concern. No
    // published framework module applies the annotation. Modules that DO use @AutoBuilder
    // (integration tests with domain models, every ekbatan-examples app) declare the AP
    // themselves with `compileOnly + annotationProcessor`.

    "testImplementation"(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    "testImplementation"("org.junit.jupiter:junit-jupiter:${project.property("junitJupiterVersion")}")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")
    "testImplementation"("io.mockk:mockk:${project.property("mockkVersion")}")
    "testImplementation"("org.assertj:assertj-core:${project.property("assertjVersion")}")
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "25"
    targetCompatibility = "25"
    options.isIncremental = true
    options.isWarnings = true
    options.isDeprecation = true
    options.compilerArgs.add("-Xlint:unchecked")
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named("build") {
    dependsOn("spotlessApply")
}

tasks.register("checkFormat") {
    group = "verification"
    description = "Check code formatting without applying changes"
    dependsOn("spotlessCheck")
}
