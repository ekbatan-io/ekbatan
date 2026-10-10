package io.ekbatan.flyway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.resource.LoadableResource;
import org.flywaydb.core.internal.resource.classpath.ClassPathResource;
import org.flywaydb.core.internal.scanner.filesystem.FileSystemScanner;

/**
 * Flyway {@link ResourceProvider} for a native image, where Flyway cannot find the migrations packed
 * into the program by itself. A {@code classpath:} location is walked on GraalVM Substrate's
 * {@code resource:/} NIO file system; a {@code filesystem:} location - a folder on disk - is handed
 * to Flyway's own scanner, which reads it in a native image as it does on the JVM. Any other
 * kind of location - one a Flyway plugin adds, such as {@code s3:} - is refused rather than skipped,
 * so a native image never migrates nothing in silence.
 *
 * <p>It runs on whichever Flyway the application ends up with, not only the one Ekbatan is built
 * against: Spring Boot's Maven parent picks Flyway 11, and Micronaut's picks Flyway 10. So it uses
 * nothing that is in Flyway 12 alone.
 *
 * <p>Application code calls {@link FlywayMigrator}; the migrator installs this provider
 * automatically when the process is running as a native image. This class is intentionally
 * package-private so the public API stays centered on {@code FlywayMigrator}.
 */
final class NativeImageFlywayResourceProvider implements ResourceProvider {

    private final Configuration configuration;
    private final ClassLoader classLoader;
    private final Charset encoding;

    /**
     * The locations captured at construction. Package-private so a test can assert which locations
     * the scanner was actually built from - the distinction between honouring a customizer's
     * {@code locations(...)} and silently scanning the pre-customizer ones.
     */
    final Location[] locations;

    /**
     * The files in the {@code filesystem:} locations, found by Flyway's own scanner the first time
     * they are needed and kept, as Flyway's own scanner keeps what it finds.
     */
    private List<LoadableResource> onDisk;

    /**
     * Constructs a provider bound to the Flyway configuration's effective locations and classloader.
     *
     * @param configuration the Flyway configuration whose locations are scanned.
     * @param classLoader the classloader to read resources through.
     * @param encoding the migration-file character encoding.
     */
    NativeImageFlywayResourceProvider(Configuration configuration, ClassLoader classLoader, Charset encoding) {
        this.configuration = configuration;
        this.locations = configuration.getLocations();
        this.classLoader = classLoader;
        this.encoding = encoding;
    }

    /** {@return true when the current process is running as a GraalVM native image} */
    static boolean inNativeImage() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
    }

    @Override
    public LoadableResource getResource(String name) {
        if (classLoader.getResource(name) != null) {
            return new ClassPathResource(null, name, classLoader, encoding);
        }
        // a file on disk is named by its path under its location, as Flyway's own scanner names it and
        // matches it - Flyway asks this way for each migration's .conf file
        return onDisk().stream()
                .filter(resource -> resource.getRelativePath().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    @Override
    public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
        // resource:/ exists only inside a native image, so it is opened only when a classpath
        // location needs it
        if (Arrays.stream(locations).noneMatch(NativeImageFlywayResourceProvider::isClassPath)) {
            return collect(null, prefix, suffixes);
        }
        try (FileSystem image = FileSystems.newFileSystem(URI.create("resource:/"), Map.of())) {
            return collect(image, prefix, suffixes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<LoadableResource> collect(FileSystem image, String prefix, String[] suffixes) {
        List<LoadableResource> out = new ArrayList<>();
        for (Location location : locations) {
            if (isClassPath(location)) {
                out.addAll(inTheImage(image, location, prefix, suffixes));
            } else if (!isFileSystem(location)) {
                // a kind a Flyway plugin adds - s3: from flyway-locations-s3, gcs: - refused rather than
                // skipped, so a native image never migrates nothing in silence
                throw new FlywayException("Migrations at " + location + " cannot be read in a native image: only"
                        + " classpath: and filesystem: locations can");
            }
        }
        for (LoadableResource resource : onDisk()) {
            if (matches(resource.getFilename(), prefix, suffixes)) {
                out.add(resource);
            }
        }
        return out;
    }

    private List<LoadableResource> inTheImage(FileSystem image, Location location, String prefix, String[] suffixes) {
        Path root = image.getPath("/", location.getRootPath());
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> matches(p.getFileName().toString(), prefix, suffixes))
                    .map(p -> (LoadableResource) new ClassPathResource(
                            null, image.getPath("/").relativize(p).toString(), classLoader, encoding))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean matches(String fileName, String prefix, String[] suffixes) {
        return fileName.startsWith(prefix) && Arrays.stream(suffixes).anyMatch(fileName::endsWith);
    }

    private List<LoadableResource> onDisk() {
        if (onDisk == null) {
            var scanner = fileSystemScanner();
            onDisk = Arrays.stream(locations)
                    .filter(NativeImageFlywayResourceProvider::isFileSystem)
                    .flatMap(location -> scanner.scanForResources(location).stream())
                    .toList();
        }
        return onDisk;
    }

    /**
     * Flyway's own folder reader. Flyway 12 builds it from the configuration alone, where Flyway 10
     * and 11 take the stream setting first, so the constructor this Flyway has is looked up.
     */
    private FileSystemScanner fileSystemScanner() {
        try {
            try {
                return FileSystemScanner.class
                        .getConstructor(Configuration.class)
                        .newInstance(configuration);
            } catch (NoSuchMethodException beforeFlyway12) {
                return FileSystemScanner.class
                        .getConstructor(boolean.class, Configuration.class)
                        .newInstance(configuration.isStream(), configuration);
            }
        } catch (InvocationTargetException e) {
            throw new FlywayException("Flyway's folder reader could not be made", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new FlywayException("This Flyway version's folder reader is not one Ekbatan knows", e);
        }
    }

    // The prefix is compared, rather than Location.isClassPath() - deprecated in Flyway 12 - or
    // CoreLocationPrefix, which Flyway 10 and 11 do not have
    private static boolean isClassPath(Location location) {
        return "classpath:".equals(location.getPrefix());
    }

    private static boolean isFileSystem(Location location) {
        return "filesystem:".equals(location.getPrefix());
    }
}
