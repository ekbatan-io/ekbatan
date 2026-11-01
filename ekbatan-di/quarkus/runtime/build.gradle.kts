plugins {
    `java-library`
    id("ekbatan.publishing")
    id("io.quarkus.extension") version "3.40.1.1"
}

ekbatanPublishing {
    artifactId.set("ekbatan-quarkus")
    description.set("Quarkus extension runtime for Ekbatan — single-dep entry point for Quarkus apps.")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

// Quarkus's extension annotation processor generates AsciiDoc config-reference docs (a feature
// for the Quarkus website's docs build). Without a Maven pom.xml it can't detect the GAV and
// emits "We could not detect the groupId and artifactId of this module" on every build.
// We don't publish to the Quarkus website, so disable the config-doc generator explicitly.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-AgenerateDoc=false")
}

quarkusExtension {
    // Under flat project paths, the plugin's default "sibling project named `deployment`"
    // lookup doesn't apply — the deployment project lives at a top-level path. Tell the
    // plugin both the project path and the Maven coordinate explicitly. The artifactId here
    // must match the published artifactId of the deployment module (`ekbatan-quarkus-deployment`,
    // set via `ekbatanPublishing.artifactId` in the deployment module's build.gradle.kts), NOT
    // the flat Gradle project name (`ekbatan-di-quarkus-deployment`) — otherwise the runtime
    // jar's META-INF/quarkus-extension.properties advertises a coordinate Maven Central doesn't
    // host, and downstream Quarkus apps fail to resolve the deployment companion at build time.
    deploymentModule.set(":ekbatan-di-quarkus-deployment")
    deploymentArtifact.set("${project.group}:ekbatan-quarkus-deployment:${project.version}")
}

dependencies {
    api(project(":ekbatan-core"))
    api(project(":ekbatan-di-annotations"))

    // Must stay `api`: Quarkus's split classloader pins extension-runtime types to the parent
    // ('app') loader, but user-app deps land on the local QuarkusClassLoader. If these are
    // `compileOnly` here, the extension's EkbatanLocalEventHandlerConfiguration still imports
    // `EventHandler`/`EventHandlerRegistry`, the JVM resolves them via the parent loader, while
    // the user's `@EkbatanEventHandler` bean (e.g. via :di:shared) loads on QuarkusClassLoader
    // — two copies, ClassCastException at `@All List<EventHandler<?>>` injection. The
    // `isClassPresentAtRuntime` checks in EkbatanProcessor remain as a guard for users who
    // explicitly exclude these modules from their Quarkus app classpath.
    api(project(":ekbatan-events-local-event-handler"))
    api(project(":ekbatan-distributed-jobs"))

    api("io.quarkus:quarkus-core:${project.property("quarkusVersion")}")
    api("io.quarkus:quarkus-arc:${project.property("quarkusVersion")}")

    // ekbatan-core declares HikariCP as compileOnly so its public API stays Hikari-free; the
    // Quarkus extension declares Hikari as implementation so Quarkus apps using this extension
    // get a working framework out of the box without an extra dep line - on the consumer's
    // compile classpath (not just runtime), so user code that constructs HikariDataSource
    // directly compiles cleanly.
    implementation("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")

    implementation("tools.jackson.dataformat:jackson-dataformat-yaml:${project.property("jacksonDatabindVersion")}")
    // Binds ekbatan.sharding.* from SmallRye's flat (dotted-key + [idx]) property output directly
    // into ShardingConfig — JavaPropsMapper's default schema (dot separator, [idx] array notation)
    // is an exact match for what SmallRye emits.
    implementation("tools.jackson.dataformat:jackson-dataformat-properties:${project.property("jacksonDatabindVersion")}")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    // The Quarkus BOM aligns the SmallRye Config version with the one quarkus-core uses at
    // runtime so the binding tests exercise the exact same SmallRye behavior production sees.
    testImplementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:${project.property("quarkusVersion")}"))
    // Builds a real SmallRyeConfig from in-memory PropertiesConfigSource so the binding tests
    // exercise the same path production code takes — ConfigProvider.getConfig() → JavaPropsMapper
    // → ShardingConfig, and SmallRye's @ConfigMapping proxy for EkbatanProperties.
    testImplementation("io.smallrye.config:smallrye-config")
    // SmallRye's @ConfigMapping materialiser uses ASM to generate the proxy class at runtime; the
    // Quarkus BOM excludes asm from smallrye-config-core's transitive graph (Arc supplies it via
    // its own deployment-time bytecode-generation channel) so the test classpath needs it back.
    testImplementation("org.ow2.asm:asm")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
