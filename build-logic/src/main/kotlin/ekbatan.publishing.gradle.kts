plugins {
    `java-library`
    `maven-publish`
}

val ekbatanPublishing = extensions.create<EkbatanPublishingExtension>("ekbatanPublishing")

java {
    withJavadocJar()
    withSourcesJar()
}

// Since JDK 23, javadoc copies its web fonts - about 3.9 MB - into every javadoc jar, which made
// up almost all of each release's size. Maven Central counts release size against a monthly
// limit, so the fonts are left out; the pages use the browser's own fonts instead.
tasks.named<Javadoc>("javadoc") {
    (options as StandardJavadocDocletOptions).addBooleanOption("-no-fonts", true)
}

// Generate a Jandex bean-archive index (META-INF/jandex.idx) inside every published
// ekbatan-* JAR. Quarkus, Spring, and Micronaut all consume `META-INF/jandex.idx` when
// present, skipping their own on-the-fly indexing — which makes consumption transparent
// (no per-app `quarkus.index-dependency.*` config required for non-extension deps).
//
// Implemented as a custom Gradle task (see JandexIndexTask) rather than the
// `org.kordamp.gradle.jandex` plugin: kordamp's plugin (1.x and 2.x) trips a Gradle 9
// `ClassCastException` on `Banner$Inject_` the moment it's applied to more than one
// subproject in the same build — fatal here because both ekbatan-quarkus and
// ekbatan-native need indexes. Our custom task uses `org.jboss.jandex.Indexer` directly
// from build-logic's classpath, sidestepping the plugin entirely.
//
// Output goes to `build/jandex/META-INF/jandex.idx` (a dedicated dir, NOT
// `build/resources/main/META-INF/...`) — Gradle 9.x's strict task-dependency validator
// flags implicit cross-project reads when sibling projects (e.g. the protobuf plugin's
// `extractIncludeProto`) read from each other's `resources/main`. Adding the dedicated
// dir as an extra `from(...)` source on `jar` keeps the index isolated.
val jandexIndex = tasks.register<JandexIndexTask>("jandexIndex") {
    classesDir.set(layout.buildDirectory.dir("classes/java/main"))
    resourcesDir.set(layout.buildDirectory.dir("jandex"))
    dependsOn("classes")
}
tasks.named<Jar>("jar") {
    from(jandexIndex.flatMap { it.resourcesDir })
}

afterEvaluate {
    val artifactId = ekbatanPublishing.artifactId.orNull
        ?: error("Module ${project.path} applies 'ekbatan.publishing' but doesn't set ekbatanPublishing.artifactId")
    val description = ekbatanPublishing.description.orNull
        ?: error("Module ${project.path} applies 'ekbatan.publishing' but doesn't set ekbatanPublishing.description")

    base.archivesName.set(artifactId)

    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                groupId = "io.github.ekbatan-io"
                this.artifactId = artifactId
                // Gradle's "capability" concept (used by java-test-fixtures) has no POM equivalent.
                // We don't publish test fixtures to Maven coordinates, so silence the warning.
                suppressPomMetadataWarningsFor("testFixturesApiElements")
                suppressPomMetadataWarningsFor("testFixturesRuntimeElements")
                pom {
                    name.set(artifactId)
                    this.description.set(description)
                    // The documentation site, not the repository: this is the link Maven Central
                    // renders as the project's home, and it is the more useful landing page for
                    // someone deciding whether to depend on this. The repository is still reachable
                    // through <scm> below.
                    url.set("https://ekbatan-io.github.io/ekbatan/")
                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("unikzforce")
                            name.set("Hossein Bakhtiari Ziabari")
                            email.set("h.bakhtiary.z@gmail.com")
                            url.set("https://github.com/unikzforce")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/ekbatan-io/ekbatan.git")
                        developerConnection.set("scm:git:ssh://git@github.com/ekbatan-io/ekbatan.git")
                        url.set("https://github.com/ekbatan-io/ekbatan")
                        // For released versions, point at the matching tag (v<version>); for
                        // snapshots, use Maven's "HEAD" convention since snapshots aren't tagged.
                        val v = project.version.toString()
                        tag.set(if (v.endsWith("-SNAPSHOT")) "HEAD" else "v$v")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "staging"
                url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
            }
        }
    }
}
