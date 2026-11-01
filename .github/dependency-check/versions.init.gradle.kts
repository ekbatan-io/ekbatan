// Used by .github/workflows/dependency-check.yml. Applies the dependency-updates check
// (https://github.com/ben-manes/gradle-versions-plugin) to every project from outside the build,
// so the build itself carries no extra plugin:
//
//     ./gradlew --init-script .github/dependency-check/versions.init.gradle.kts dependencyUpdates
//
// Each project writes build/dependencyUpdates/report.json, which report.py reads.
initscript {
    repositories { gradlePluginPortal() }
    dependencies { classpath("com.github.ben-manes:gradle-versions-plugin:0.54.0") }
}

val unstable = Regex("(?i).*[.-](alpha|beta|rc|cr|m|milestone|preview|snapshot|ea)[.\\d-]*$")

allprojects {
    apply<com.github.benmanes.gradle.versions.VersionsPlugin>()
    tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask>().configureEach {
        // Stable releases only: an alpha, beta, milestone or release candidate is offered only
        // in place of another one.
        rejectVersionIf { unstable.matches(candidate.version) && !unstable.matches(currentVersion) }
        outputFormatter = "json"
        checkForGradleUpdate = true
    }
}
