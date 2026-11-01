package io.ekbatan.graalvm.testcontainers;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * GraalVM Native Image {@link Feature} that registers Testcontainers' shaded
 * docker-java types AND the non-shaded {@code com.github.dockerjava.*} types
 * (transitively pulled in by Testcontainers extensions like
 * {@code debezium-testing-testcontainers}) for full reflection.
 *
 * <p><b>Why a Feature and not just a vendored reachability-metadata.json?</b> We tried that.
 * The upstream GraalVM RMR ships {@code testcontainers/1.19.8/reachability-metadata.json}
 * (3364 lines, tested up to 2.0.5) plus {@code docker-java-transport/3.2.13} (17 lines), and
 * those cover single-container test setups - e.g. a lone Postgres container via
 * {@code @SpringBootTest} works fine on native with just the vendored JSON. But our
 * event-pipeline tests use Testcontainers' multi-container topology (Postgres + Kafka +
 * Debezium connect linked via Docker network aliases), which exercises a much wider
 * docker-java surface - network commands, container inspection, exec, copy-archive,
 * the {@code Network} / {@code ContainerNetwork} / {@code NetworkSettings} model classes,
 * etc. Each of those needs reflection metadata for Jackson 2 serialization, and the
 * upstream RMR misses many of them.
 *
 * <p>We tried a focused supplement-JSON approach (vendor the upstream RMR + add a few
 * specific {@code *CmdImpl} classes that broke). It became whack-a-mole: every new test
 * pattern hit a different missing class. The broad-scan Feature's defensiveness is the
 * right tool for Testcontainers specifically - the API surface is wide and the upstream
 * RMR is conservative about what it lists.
 *
 * <p><b>When does it run?</b> The Feature is auto-loaded for every native-image build
 * by virtue of its {@code META-INF/native-image/.../native-image.properties}, but it
 * <b>no-ops</b> when {@code org.testcontainers.DockerClientFactory} is not reachable -
 * i.e. for production native binaries that don't pull Testcontainers in. Native-image's
 * analyser then prunes this Feature's bytecode from the final image.
 */
public final class TestcontainersDockerJavaFeature implements Feature {

    /** Required by GraalVM's native-image SPI; instantiated reflectively when registered via {@code META-INF/native-image}. */
    public TestcontainersDockerJavaFeature() {}

    /**
     * Scan roots - packages where Jackson 2 actually walks docker-java types: the API
     * command interfaces, the API model (response) types, and the core CmdImpl classes.
     * Both the non-shaded variants (used by docker-java directly) and the shaded variants
     * Testcontainers re-bundles under {@code org.testcontainers.shaded}.
     */
    private static final String[] SCAN_PACKAGES = {
        "com.github.dockerjava.api.command",
        "com.github.dockerjava.api.model",
        "com.github.dockerjava.core.command",
        "org.testcontainers.shaded.com.github.dockerjava.api.command",
        "org.testcontainers.shaded.com.github.dockerjava.api.model",
        "org.testcontainers.shaded.com.github.dockerjava.core.command",
    };

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        // No-op when Testcontainers is not on the classpath. Production native builds
        // that don't pull Testcontainers see this Feature get pruned by the analyser.
        if (access.findClassByName("org.testcontainers.DockerClientFactory") == null) {
            return;
        }

        String[] classpath = access.getApplicationClassPath().stream()
                .map(p -> p.toAbsolutePath().toString())
                .toArray(String[]::new);

        int registered = 0;
        try (ScanResult scan = new ClassGraph()
                .enableClasspathEntries((Object[]) classpath)
                .enableClassInfo()
                .ignoreClassVisibility()
                .acceptPackages(SCAN_PACKAGES)
                .scan()) {

            for (var ci : scan.getAllClasses()) {
                Class<?> cls;
                try {
                    cls = access.findClassByName(ci.getName());
                } catch (Throwable ignored) {
                    continue;
                }
                if (cls == null) continue;
                if (registerForFullReflection(cls)) {
                    registered++;
                }
            }
        }
        System.out.println("[ekbatan-native] TestcontainersDockerJavaFeature: registered " + registered
                + " docker-java classes for full reflection");
    }

    /**
     * Best-effort full-reflection registration. {@code getDeclaredConstructors} /
     * {@code getDeclaredMethods} can throw {@link NoClassDefFoundError} on classes whose
     * declared parameter / return types aren't loadable on the build classpath
     * (common in shaded jars whose optional deps aren't pulled in) - skip them, they
     * almost certainly aren't reachable from the application anyway.
     *
     * <p>Both bulk and per-element registration are required: on GraalVM 25,
     * {@code registerAllDeclared*} only allows the QUERY API (so
     * {@code Class.getDeclaredMethods()} returns them) - explicit
     * {@code RuntimeReflection.register(method)} adds the invocation path. Jackson 2
     * uses {@code Method.invoke} on docker-java getters during response deserialization,
     * which requires the per-element registration.
     */
    private static boolean registerForFullReflection(Class<?> cls) {
        try {
            RuntimeReflection.register(cls);
            RuntimeReflection.registerAllDeclaredFields(cls);
            RuntimeReflection.registerAllDeclaredMethods(cls);
            RuntimeReflection.registerAllDeclaredConstructors(cls);
            for (var ctor : cls.getDeclaredConstructors()) {
                RuntimeReflection.register(ctor);
            }
            for (var method : cls.getDeclaredMethods()) {
                RuntimeReflection.register(method);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
