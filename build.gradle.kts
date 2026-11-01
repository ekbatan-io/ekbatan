// Spotless 8.10.4 pulls in JGit 7.8.0; JReleaser 1.26.0 still asks for JGit 5.13 and calls
// `org.eclipse.jgit.lib.GpgObjectSigner` which JGit 7.x removed. Pin JGit to the latest 6.x
// release (still has GpgObjectSigner; Spotless's git-diff usage is fine on 6.x) so both plugins
// can coexist on the buildscript classpath.
buildscript {
    configurations.classpath {
        resolutionStrategy {
            force("org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r")
        }
    }
}

plugins {
    id("com.diffplug.spotless") version "8.10.4"
    id("ekbatan.module-conventions") apply false
    id("ekbatan.smt-conventions") apply false
    id("ekbatan.native-test-conventions") apply false
    id("org.jreleaser") version "1.26.0"
}

spotless {
    java {
        target("**/*.java")
        // ekbatan-examples/* are independent Gradle builds with their own settings.gradle.kts
        // and their own (simpler) formatting conventions. Don't impose Palantir on them from
        // here - the root glob would otherwise reach across the build boundary.
        targetExclude("ekbatan-examples/**/*.java")
        palantirJavaFormat("2.81.0")
    }
    format("misc") {
        target("*.md", ".gitignore", ".gitattributes", "*.yaml", "*.yml", "*.json")
        trimTrailingWhitespace()
        leadingTabsToSpaces(4)
        endWithNewline()
    }
}

allprojects {
    // Single groupId across the repo. Coordinate uniqueness comes from each project's name,
    // which `settings.gradle.kts` flattens from the full path. Publishable modules override
    // the published artifactId via `ekbatanPublishing.artifactId` in the publishing plugin.
    //
    // The Maven groupId (`io.github.ekbatan-io`) intentionally differs from the Java package
    // names in the source tree (`io.ekbatan.*`) — Maven Central's namespace is reverse-DNS of
    // the GitHub org, but Java packages don't need to match the groupId. Many libraries
    // (picocli, lombok, ...) ship this way.
    group = "io.github.ekbatan-io"

    repositories {
        mavenCentral()
    }
}

subprojects {
    val isSmt = project.path.startsWith(":ekbatan-events-streaming-debezium-smt")
    val skipNative = project.path == ":ekbatan-annotation-processor"
            || isSmt
            || project.path == ":ekbatan-di-quarkus-runtime"
            || project.path == ":ekbatan-di-quarkus-deployment"
            || project.path == ":ekbatan-integration-tests-di-quarkus"
            // -Pmicronaut5 runs only this module's JVM tests. The Micronaut 5 Gradle plugin reads the
            // native-image JDK the native-test conventions ask for as soon as it is applied, so
            // with them it fails on a machine without GraalVM, before any test runs.
            || (project.path == ":ekbatan-integration-tests-di-micronaut" && project.hasProperty("micronaut5"))

    if (isSmt) {
        apply(plugin = "ekbatan.smt-conventions")
    } else {
        apply(plugin = "ekbatan.module-conventions")
    }

    if (!skipNative) {
        apply(plugin = "ekbatan.native-test-conventions")
    }
}

// =====================================================================================
// JReleaser — orchestrates the release flow:
//   1. Maven Central via Central Portal (deploy.maven.mavenCentral.sonatype) — the 16
//      publishable jars + their sources/javadoc jars + POMs are staged from
//      build/staging-deploy/ (populated by `./gradlew publish`), signed with GPG, and
//      uploaded to Sonatype's staging area. `active = RELEASE` means stage-and-confirm:
//      JReleaser uploads but doesn't auto-publish — log into central.sonatype.com and
//      click "Publish" (or "Drop" if something's wrong) once you've verified.
//   2. GitHub Releases (release.github) — creates a tagged release with the two SMT
//      shadow jars attached as assets. Independent of the Maven Central path.
//
// Secrets pulled from env vars (set in GitHub Actions repo secrets, or ~/.jreleaser/
// config.toml for local releases):
//   JRELEASER_MAVENCENTRAL_USERNAME, JRELEASER_MAVENCENTRAL_PASSWORD — Sonatype user
//     token (generated in the Central Portal UI, not your account login)
//   JRELEASER_GPG_PUBLIC_KEY, JRELEASER_GPG_SECRET_KEY, JRELEASER_GPG_PASSPHRASE —
//     ASCII-armored exports of `gpg --armor --export[-secret-keys] <KEY_ID>`
//   JRELEASER_GITHUB_TOKEN — auto-provided in GitHub Actions; for local releases use
//     a personal access token with `repo` scope.
//
// Validate locally without uploading anything:
//   ./gradlew jreleaserConfig    # parses + reports the resolved config
//   ./gradlew jreleaserAssemble  # builds release artifacts to build/jreleaser/
// Full release (signs + uploads + creates GH release):
//   ./gradlew publish && ./gradlew jreleaserFullRelease
// =====================================================================================
jreleaser {
    project {
        name.set("ekbatan")
        description.set(
            "Java persistence and action framework with a first-class transactional outbox — an alternative to Hibernate, Spring Data, and JPA when the outbox pattern matters.",
        )
        longDescription.set(
            """
            Ekbatan is a Java persistence and action framework, an alternative to Hibernate,
            Spring Data, JPA, MyBatis, and similar stacks. Its central idea: in most business
            applications, you don't just want to save data — you want to save what happened
            alongside the data itself, atomically, so the database always tells a complete and
            consistent story.

            The transactional outbox is a first-class concept, not an afterthought. Actions stage
            domain changes on an ActionPlan; the ActionExecutor commits the domain rows AND the
            corresponding events to the eventlog in a single transaction per shard. There's no
            second table to remember to write to, no dual-write problem, no event loss — the
            outbox is built into every action by construction. Tail the eventlog with Debezium /
            CDC, or poll it and forward to Kafka / Pulsar / any message broker, and you have
            reliable event-driven architecture without the usual outbox plumbing burden.

            Other features: per-shard transactional execution, optimistic locking on every
            update, sharding strategies (single-DB or embedded-bits UUID), distributed jobs
            backed by db-scheduler, and drop-in DI integrations for Spring Boot, Quarkus, and
            Micronaut.
            """
                .trimIndent(),
        )
        authors.set(listOf("Hossein Bakhtiari Ziabari"))
        license.set("Apache-2.0")
        inceptionYear.set("2026")
        copyright.set("2026 Hossein Bakhtiari Ziabari")
        links {
            homepage.set("https://github.com/ekbatan-io/ekbatan")
            license.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            documentation.set("https://github.com/ekbatan-io/ekbatan#readme")
            bugTracker.set("https://github.com/ekbatan-io/ekbatan/issues")
            vcsBrowser.set("https://github.com/ekbatan-io/ekbatan")
        }
        languages {
            java {
                groupId.set("io.github.ekbatan-io")
                version.set("25")
                multiProject.set(true)
            }
        }
    }

    signing {
        active.set(org.jreleaser.model.Active.ALWAYS)
        // `armored` / `verify` / `mode` moved under signing.pgp { } in JReleaser 1.24.
        // MEMORY mode (the default) reads the key material from the JRELEASER_GPG_* env vars
        // directly — no GPG keyring file needed on the runner.
        pgp {
            armored.set(true)
            verify.set(true)
        }
    }

    release {
        github {
            repoOwner.set("ekbatan-io")
            name.set("ekbatan")
            tagName.set("v{{projectVersion}}")
            releaseName.set("Ekbatan {{projectVersion}}")
            draft.set(false)
            // Allow re-runs to replace an existing GitHub Release for the same tag — natural for
            // stage-and-confirm flows where a release may be staged, dropped on Sonatype, and
            // re-staged before final publish. Maven Central immutability is enforced separately.
            overwrite.set(true)
            skipTag.set(false)
            changelog {
                formatted.set(org.jreleaser.model.Active.ALWAYS)
                preset.set("conventional-commits")
                contributors {
                    enabled.set(false)
                }
            }
        }
    }

    // The two Debezium SMT shadow jars ride along with the GitHub Release as drop-in
    // assets for Kafka Connect operators. Not published to Maven Central. The
    // `{{projectVersion}}` template pins to the exact version being released — a literal `*`
    // would attach any stale jars left in build/libs/ from a previous version.
    files {
        glob {
            pattern.set(
                "ekbatan-events/streaming/debezium-smt/avro/build/libs/ekbatan-debezium-smt-avro-{{projectVersion}}.jar",
            )
        }
        glob {
            pattern.set(
                "ekbatan-events/streaming/debezium-smt/protobuf/build/libs/ekbatan-debezium-smt-protobuf-{{projectVersion}}.jar",
            )
        }
    }

    deploy {
        maven {
            mavenCentral {
                create("sonatype") {
                    // `active = RELEASE` is a CONDITION on when the deployer runs (non-SNAPSHOT
                    // versions only); it does NOT control auto-publish vs stage. The publish-mode
                    // knob is `stage` (below) — UPLOAD = stage-and-confirm, PUBLISH/FULL = auto.
                    active.set(org.jreleaser.model.Active.RELEASE)
                    url.set("https://central.sonatype.com/api/v1/publisher")
                    stagingRepository("build/staging-deploy")
                    applyMavenCentralRules.set(true)
                    namespace.set("io.github.ekbatan-io")
                    // Stage-and-confirm: upload signed artifacts to Sonatype's validation window
                    // and STOP there. Verify on central.sonatype.com → Deployments, then click
                    // Publish (or Drop). Switch to PUBLISH (or FULL) once we trust the pipeline
                    // and want releases to auto-publish without a human click.
                    stage.set(org.jreleaser.model.api.deploy.maven.MavenCentralMavenDeployer.Stage.UPLOAD)
                }
            }
        }
    }
}

// =====================================================================================
// Enumerates the modules that compile a native test image, for the Heavy Verification
// matrix. Derived from the build rather than hardcoded in YAML: a hand-maintained list
// silently drops a newly added module out of native coverage, which is a worse failure
// than a slow job - nothing goes red, the module simply stops being verified.
//
// Prints one module path per line, without the leading colon so the value is usable as a
// GitHub Actions artifact-name component.
// =====================================================================================
tasks.register("nativeTestModules") {
    group = "verification"
    description = "Lists subprojects that have a nativeTest task, one per line."
    val paths = provider {
        subprojects
            .filter { it.plugins.hasPlugin("org.graalvm.buildtools.native") }
            // The native JUnit launcher cannot compute selectors for these Debezium/Kafka
            // suites; they run on the JVM instead. Excluded here rather than on the CI
            // command line so the matrix and the exclusion stay in one place.
            .filterNot {
                it.path.startsWith(":ekbatan-integration-tests-event-pipeline-debezium-kafka-json") ||
                    it.path.startsWith(":ekbatan-integration-tests-event-pipeline-debezium-kafka-avro-smt") ||
                    it.path.startsWith(":ekbatan-integration-tests-event-pipeline-debezium-kafka-protobuf-smt")
            }
            .map { it.path.removePrefix(":") }
            .sorted()
    }
    doLast { paths.get().forEach { println(it) } }
}
