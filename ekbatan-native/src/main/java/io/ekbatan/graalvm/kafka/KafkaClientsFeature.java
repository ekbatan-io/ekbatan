package io.ekbatan.graalvm.kafka;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * GraalVM Native Image {@link Feature} that registers Apache Kafka client classes for
 * reflective {@code Class.forName} lookup at runtime - required because every
 * {@code KafkaConsumer} / {@code KafkaProducer} / {@code AdminClient} construction walks
 * its {@code ConfigDef} and validates each config key whose default is a class name by
 * calling {@code Class.forName} on it.
 *
 * <p><b>Why</b>: the upstream Kafka client RMR
 * ({@code oracle/graalvm-reachability-metadata}, latest metadata-version 3.5.1) is years
 * stale. Kafka 4.x added new SASL/OAuth defaults (e.g. {@code DefaultJwtRetriever},
 * {@code DefaultJwtValidator}) and reorganised existing security classes - none of those
 * are in the published RMR, so a stock native build of any Kafka 4 client fails at
 * {@code KafkaConsumer.&lt;init&gt;} with {@code ConfigException: Class ... could not be
 * found}. Scanning the security/login namespaces and registering everything is a
 * one-shot fix until the RMR catches up.
 *
 * <p><b>When does it run?</b> Auto-loaded for every native-image build via the sibling
 * {@code META-INF/native-image/.../native-image.properties}, but it <b>no-ops</b> when
 * {@code org.apache.kafka.clients.consumer.KafkaConsumer} is not reachable. Production
 * native binaries that don't talk to Kafka pay nothing.
 */
public final class KafkaClientsFeature implements Feature {

    /** Required by GraalVM's native-image SPI; instantiated reflectively when registered via {@code META-INF/native-image}. */
    public KafkaClientsFeature() {}

    /**
     * Scan roots - only the namespaces where Kafka 4.x ConfigDef defaults can name a class:
     * SASL / OAuth / SSL login + login modules, and the {@code serialization} package whose
     * serializers/deserializers are used as ProducerConfig / ConsumerConfig defaults. The
     * specific top-level classes from {@code clients.*} that ARE ConfigDef defaults are
     * registered explicitly via {@link #EXPLICIT_CLIENT_CLASSES}.
     */
    private static final String[] SCAN_PACKAGES = {
        "org.apache.kafka.common.security", "org.apache.kafka.common.serialization"
    };

    /**
     * Public top-level partitioner / assignor classes referenced by name from
     * ProducerConfig / ConsumerConfig defaults. Kafka resolves these via Class.forName at
     * client construction. Internal helpers under {@code clients.*.internals} are NOT
     * ConfigDef-referenced and don't need explicit registration.
     */
    private static final String[] EXPLICIT_CLIENT_CLASSES = {
        "org.apache.kafka.clients.producer.RoundRobinPartitioner",
        "org.apache.kafka.clients.producer.UniformStickyPartitioner",
        "org.apache.kafka.clients.consumer.RangeAssignor",
        "org.apache.kafka.clients.consumer.RoundRobinAssignor",
        "org.apache.kafka.clients.consumer.StickyAssignor",
        "org.apache.kafka.clients.consumer.CooperativeStickyAssignor",
    };

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        // No-op when Kafka clients aren't on the classpath. The Feature class itself is
        // pruned by the analyser when KafkaConsumer is unreachable.
        if (access.findClassByName("org.apache.kafka.clients.consumer.KafkaConsumer") == null) {
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
        // Register the explicit top-level partitioner/assignor classes from clients.*
        int explicit = 0;
        for (String fqn : EXPLICIT_CLIENT_CLASSES) {
            Class<?> cls = access.findClassByName(fqn);
            if (cls != null && registerForFullReflection(cls)) {
                explicit++;
            }
        }
        System.out.println("[ekbatan-native] KafkaClientsFeature: registered " + registered
                + " classes (security + serialization) + " + explicit + " explicit client classes");
    }

    /**
     * {@code getDeclaredConstructors} / {@code getDeclaredMethods} can throw
     * {@link NoClassDefFoundError} when a class references types not on the build
     * classpath (common with Kafka's optional integrations like Kerberos / IBM-only
     * security providers). Skip those silently - they aren't reachable at runtime.
     *
     * <p>Both bulk and per-element registration are required: on GraalVM 25,
     * {@code registerAllDeclared*} only allows the QUERY API (so
     * {@code Class.getDeclaredMethods()} returns them) - explicit
     * {@code RuntimeReflection.register(method)} / {@code register(ctor)} adds the
     * invocation path. Kafka's {@code ConfigDef} resolves classes by name and instantiates
     * them via the no-arg constructor, which requires the per-element registration.
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
