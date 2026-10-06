plugins {
    id("java")
    id("io.quarkus") version "3.34.6"
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

// The runtime jar's META-INF/quarkus-extension.properties points at the Maven coordinate
// io.github.ekbatan-io:ekbatan-quarkus-deployment:1.1.0. The actual artifact lives at the
// in-build subproject :ekbatan-di:quarkus:deployment whose implicit publication name is
// "deployment" — those don't match, so Gradle's automatic project substitution can't bridge
// them. Map them explicitly. With this rule, the Quarkus Gradle plugin's specialized deployment
// classpath resolves the GAV to the local project jar and never reaches a Maven repo, keeping
// the build hermetic (no mavenLocal publish step, no ~/.m2 pollution).
configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.ekbatan:ekbatan-di-quarkus-runtime"))
            .using(project(":ekbatan-di-quarkus-runtime"))
        substitute(module("io.ekbatan:ekbatan-di-quarkus-deployment"))
            .using(project(":ekbatan-di-quarkus-deployment"))
    }
}

dependencies {
    // Quarkus BOM aligns transitive Quarkus + SmallRye Config + Arc versions across the test app.
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:${project.property("quarkusVersion")}"))

    // Widget domain + jOOQ-generated tables + Flyway migrations.
    //
    // We exclude every Ekbatan + transitively-shared dep from `:di:shared`'s graph so the test
    // app's runtime classpath gets each of those jars from EXACTLY ONE path: the Quarkus
    // extension's `api()` graph below. Without these excludes, Gradle resolves the same jar via
    // two paths (shared AND extension), Quarkus places one copy on QuarkusClassLoader's local
    // set + one on the parent classloader, and you get `ClassCastException` at `@All` injection
    // points — with a long `parentFirstArtifacts` list as the only escape hatch.
    //
    // Spring Boot and Micronaut consumers don't have a split classloader, so they inherit
    // `:di:shared`'s full transitive graph as-is — these excludes are scoped to the Quarkus
    // consumer only.
    implementation(project(":ekbatan-integration-tests-di-shared")) {
        exclude(group = "io.github.ekbatan-io", module = "ekbatan-core")
        exclude(group = "io.github.ekbatan-io", module = "ekbatan-di-annotations")
        exclude(group = "io.github.ekbatan-io", module = "ekbatan-events-local-event-handler")
        exclude(group = "io.github.ekbatan-io", module = "ekbatan-distributed-jobs")
    }
    // All Ekbatan internal modules + jOOQ + Jackson + JDBC etc. come transitively via this
    // jar's `api()` graph (the extension is the single source of truth for those deps; the
    // `:di:shared` excludes above prevent the user-side path from re-introducing them).
    implementation(project(":ekbatan-di-quarkus-runtime"))

    // REST surface for the native integration test. @QuarkusIntegrationTest runs the packaged
    // binary out-of-process, so @Inject can't bridge to the test JVM — the test must drive
    // beans via HTTP. EkbatanTestEndpoint exposes ActionExecutor / WidgetRepository /
    // WidgetCreatedCounterHandler over /test/* for RestAssured to hit.
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")

    // ekbatan-native ships Jackson3RecordsFeature + KafkaClientsFeature + etc., auto-loaded by
    // native-image via META-INF/native-image/.../native-image.properties. Quarkus packages the
    // production classpath into its native binary (not the test classpath the way GraalVM Build
    // Tools' nativeTest does), so this has to be `implementation` for the Features to be picked
    // up at native-image build time. Without it the binary boots with @AutoBuilder Builder
    // classes' build() methods unregistered for reflection -> Jackson startup failure on
    // ShardingConfig deserialization.
    implementation(project(":ekbatan-native"))

    // Postgres JDBC driver reflection metadata for the native binary. Hikari calls
    // Class.forName("org.postgresql.Driver") because of `driverClassName` config; without this
    // extension's auto-registered reachability hint the native binary fails at first
    // ConnectionProvider creation with "Failed to load class org.postgresql.Driver".
    // We don't use Quarkus's data source — Ekbatan's ConnectionProvider runs Hikari itself —
    // but we still need the driver registered for reflection.
    implementation("io.quarkus:quarkus-jdbc-postgresql")

    // Test
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation(project(":ekbatan-native"))
    testImplementation(project(":ekbatan-flyway"))
    testImplementation("io.quarkus:quarkus-test-common")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testImplementation("org.testcontainers:testcontainers:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${project.property("testcontainersVersion")}")
    testImplementation("org.awaitility:awaitility:${project.property("awaitilityVersion")}")
    testImplementation("io.rest-assured:rest-assured")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}

// Failsafe-style split: regular `test` only runs JVM tests (*Test.java); `quarkusIntTest` /
// `testNative` run integration tests (*IT.java) against the packaged jar / native binary. By
// default JUnit Platform discovers BOTH from src/test/java, which would make the regular `test`
// task try to run the @QuarkusIntegrationTest classes — they fail with "Connection refused"
// because the packaged app isn't running. Excluding *IT.class restores the canonical split.
tasks.named<Test>("test") {
    exclude("**/*IT.class")
}

// Quarkus's integrationTest source set (where *IT.java lives) compiles separately from the
// main `test` source set; by default it doesn't inherit test source set's compiled outputs.
// PostgresTestResource lives in src/test/java because it's also used by the JVM @QuarkusTest;
// without the line below, @QuarkusTestResource silently can't load it at IT launch time and
// the native binary boots without ekbatan.sharding config -> startup fails.
sourceSets.named("integrationTest") {
    compileClasspath += sourceSets.named("test").get().output
    runtimeClasspath += sourceSets.named("test").get().output
}
