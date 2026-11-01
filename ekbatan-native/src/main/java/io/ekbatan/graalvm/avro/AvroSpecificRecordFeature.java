package io.ekbatan.graalvm.avro;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * GraalVM Native Image {@link Feature} that registers Apache Avro generated
 * {@code SpecificRecord} classes for full reflective access.
 *
 * <p><b>Why</b>: Avro's {@code SpecificDatumReader} / {@code SpecificDatumWriter} read
 * the static {@code SCHEMA$} field reflectively, instantiate records via
 * no-arg constructors, and walk the class hierarchy with
 * {@code SpecificRecord.class.isAssignableFrom(c)}. None of that works on native image
 * unless the generated classes are registered for reflection. The upstream Avro RMR
 * doesn't cover user-generated classes (it can't - they're project-specific).
 *
 * <p><b>What it registers</b>: every class found under the configured scan roots that
 * either subclasses {@code org.apache.avro.specific.SpecificRecordBase} or implements
 * {@code org.apache.avro.specific.SpecificRecord}.
 *
 * <p><b>When does it run?</b> Auto-loaded for every native-image build via the sibling
 * {@code META-INF/native-image/.../native-image.properties}, but it <b>no-ops</b> when
 * Avro itself is not on the classpath.
 *
 * <p><b>Scope override</b>: defaults to {@code io.ekbatan} (covers framework + project
 * code). Users with Avro records under a different namespace can override with
 * {@code -Dio.ekbatan.graalvm.avro.scan.packages=io.ekbatan,com.acme}.
 */
public final class AvroSpecificRecordFeature implements Feature {

    /** Required by GraalVM's native-image SPI; instantiated reflectively when registered via {@code META-INF/native-image}. */
    public AvroSpecificRecordFeature() {}

    private static final String SCAN_PACKAGES_PROPERTY = "io.ekbatan.graalvm.avro.scan.packages";
    private static final String DEFAULT_SCAN_PACKAGES = "io.ekbatan";

    private static final String SPECIFIC_RECORD_INTERFACE = "org.apache.avro.specific.SpecificRecord";

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        Class<?> specificRecord = access.findClassByName(SPECIFIC_RECORD_INTERFACE);
        if (specificRecord == null) {
            return;
        }

        String[] scanRoots = System.getProperty(SCAN_PACKAGES_PROPERTY, DEFAULT_SCAN_PACKAGES)
                .split("\\s*,\\s*");
        String[] classpath = access.getApplicationClassPath().stream()
                .map(p -> p.toAbsolutePath().toString())
                .toArray(String[]::new);

        int registered = 0;
        try (ScanResult scan = new ClassGraph()
                .enableClasspathEntries((Object[]) classpath)
                .enableClassInfo()
                // Asked about an interface outside the scanned packages, ClassGraph 4 also returned
                // the outside classes it reached, such as Avro's SpecificRecordBase; 6 does so only
                // when told to, and this keeps them registered as before.
                .enableExternalClasses()
                .ignoreClassVisibility()
                .acceptPackages(scanRoots)
                .scan()) {

            // ClassGraph already gives us the interface-implements relation cheaply via
            // getAllClassesImplementing - no need for a per-class Class.forName lookup of the
            // SpecificRecord interface.
            for (var ci : scan.getAllClassesImplementing(SPECIFIC_RECORD_INTERFACE)) {
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
        System.out.println("[ekbatan-native] AvroSpecificRecordFeature: registered " + registered
                + " Avro SpecificRecord classes for full reflection");
    }

    /**
     * Both bulk and per-element registration are required: on GraalVM 25,
     * {@code registerAllDeclared*} only allows the QUERY API (so
     * {@code Class.getDeclaredMethods()} returns them) - explicit
     * {@code RuntimeReflection.register(method)} / {@code register(ctor)} adds the
     * invocation path. Avro's {@code SpecificDatumReader} instantiates records via the
     * no-arg constructor and reads {@code SCHEMA$} via {@code Field.get}, which requires
     * the per-element registration.
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
