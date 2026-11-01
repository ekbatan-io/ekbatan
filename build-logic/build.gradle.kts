plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.10.4")
    implementation("org.graalvm.buildtools:native-gradle-plugin:1.1.18")

    // Powers the `jandexIndex` Gradle task wired into `ekbatan.publishing` (see
    // build-logic/src/main/kotlin/JandexIndexTask.kt). We invoke `org.jboss.jandex.Indexer`
    // directly from a custom task rather than going through the `org.kordamp.gradle.jandex`
    // plugin, because the kordamp plugin (1.x and 2.x) trips a `ClassCastException` on
    // `Banner$Inject_` under Gradle 9 the moment the plugin is applied to more than one
    // subproject - Gradle's per-subproject classloader isolation in 9.x conflicts with the
    // plugin's Guice-injected internals. Bypassing the plugin and indexing in-process from
    // build-logic has no such issue.
    //
    // Pinned to Jandex 3.1.8 (last 3.1.x) because Quarkus 3.x's IndexReader supports index
    // format versions 2-3 and 6-11; Jandex 3.2+ bumps the on-disk format to v12, which
    // Quarkus 3.x rejects (see Quarkus discussion #41492). 3.1.8 produces format v11.
    implementation("io.smallrye:jandex:3.1.8")
}
