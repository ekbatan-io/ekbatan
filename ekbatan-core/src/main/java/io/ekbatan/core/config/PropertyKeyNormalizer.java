package io.ekbatan.core.config;

import java.util.Properties;

/**
 * Folds hyphen-separated property key segments to camelCase so configuration written in either
 * casing convention binds the same way. Used by each DI's {@code EkbatanCoreConfiguration} when
 * copying flat property keys into the {@code Properties} fed to {@code JavaPropsMapper}: kebab
 * keys ({@code default-shard.group}, {@code configs.primary-config.jdbc-url}) become camelCase
 * ({@code defaultShard.group}, {@code configs.primaryConfig.jdbcUrl}), so Jackson can match
 * verbatim against builder method names and the {@code configs} map's reserved keys
 * ({@code primaryConfig}, {@code secondaryConfig}, ...) without a custom map-key deserializer or
 * a kebab-naming-strategy at the mapper level.
 *
 * <p>Values are not touched, only keys. The function is a simple state machine that uppercases
 * the character following each {@code -} (any other characters -- letters, digits, dots, brackets,
 * slashes -- pass through unchanged); it has no effect on keys that are already camelCase. Two
 * properties spelled in different cases under the same prefix collapse to the same canonical key
 * (later-iterated values override earlier ones unless callers de-duplicate first).
 */
public final class PropertyKeyNormalizer {

    private PropertyKeyNormalizer() {}

    /**
     * The one segment whose contents are never normalized: driver setting names, which a driver
     * reads exactly as written.
     */
    static final String VERBATIM_CONTAINER = "dataSourceProperties";

    /**
     * Translates kebab-case segments inside {@code key} to camelCase, leaving everything else
     * untouched - and leaving everything after a {@code data-source-properties} (or
     * {@code dataSourceProperties}) segment exactly as written, since those are driver setting
     * names: {@code ...data-source-properties.ha.enableJMX} becomes
     * {@code ...dataSourceProperties.ha.enableJMX}.
     *
     * @param key the raw configuration key (any path form: dot-separated, bracket-indexed, ...).
     * @return the normalized key -- same string if no hyphens are present.
     */
    public static String kebabToCamel(String key) {
        if (key.indexOf('-') < 0) {
            return key; // fast path - already camelCase or hyphen-free
        }
        var out = new StringBuilder(key.length());
        var start = 0;
        while (true) {
            var dot = key.indexOf('.', start);
            var segment = camel(key.substring(start, dot < 0 ? key.length() : dot));
            out.append(segment);
            if (dot < 0) {
                return out.toString();
            }
            out.append('.');
            start = dot + 1;
            if (segment.equals(VERBATIM_CONTAINER)) {
                return out.append(key, start, key.length()).toString();
            }
        }
    }

    /**
     * The level separator Jackson's properties reader must be given - with
     * {@code JavaPropsSchema.withPathSeparator} - when it reads keys passed through
     * {@link #toReaderKeys}.
     */
    public static final String READER_PATH_SEPARATOR = "/";

    private static final String DRIVER_SETTINGS = "." + VERBATIM_CONTAINER + ".";

    /**
     * Rewrites a canonical key for Jackson's properties reader so that a driver setting name
     * survives it whole: the dots of the structure become {@link #READER_PATH_SEPARATOR}, and the
     * driver setting name after {@code dataSourceProperties.} is left exactly as written.
     *
     * <pre>
     * groups[0].members[0].configs.primaryConfig.dataSourceProperties.ha.enableJMX
     * groups[0]/members[0]/configs/primaryConfig/dataSourceProperties/ha.enableJMX
     * </pre>
     *
     * <p>Read with dots as separators, {@code ha.enableJMX} would be split into a nested value,
     * and a name that begins another - {@code a} beside {@code a.b} - merged into one and lost.
     * Jackson's own escape for dots does not help: its reader ignores it in any key holding a list
     * position such as {@code [0]}, which every sharding key does.
     *
     * <p>The structure is everything up to the first {@code .dataSourceProperties.}: nothing in
     * front of the container can contain the word, so the first is the real one. Refused rather
     * than guessed at: a config named {@code dataSourceProperties}, a structural segment holding
     * the separator, and a driver setting name holding the separator or a bracket - which the
     * reader would treat as structure - none of which any supported driver's settings has.
     *
     * @param canonicalKey a key already passed through {@link #kebabToCamel}.
     * @return the key as Jackson's properties reader must see it.
     * @throws IllegalArgumentException for any of the refused shapes above.
     */
    public static String toReaderKey(String canonicalKey) {
        int at = canonicalKey.indexOf(DRIVER_SETTINGS);
        if (at < 0 && canonicalKey.startsWith(VERBATIM_CONTAINER + ".")) {
            at = -1; // the container itself starts the key
        } else if (at < 0) {
            return structure(canonicalKey, canonicalKey);
        }
        int nameStart = at + DRIVER_SETTINGS.length();
        var structure = canonicalKey.substring(0, nameStart - 1); // up to and including the container
        var name = canonicalKey.substring(nameStart);
        if (structure.endsWith(".configs." + VERBATIM_CONTAINER) || structure.equals("configs." + VERBATIM_CONTAINER)) {
            throw new IllegalArgumentException(
                    "A config cannot be named '" + VERBATIM_CONTAINER + "': that name is reserved for driver settings");
        }
        if (name.contains(READER_PATH_SEPARATOR) || name.indexOf('[') >= 0 || name.indexOf(']') >= 0) {
            throw new IllegalArgumentException("The data-source-properties name '" + name
                    + "' contains '/', '[' or ']', which no driver setting has");
        }
        return structure(structure, canonicalKey) + READER_PATH_SEPARATOR + name;
    }

    private static String structure(String structure, String canonicalKey) {
        if (structure.contains(READER_PATH_SEPARATOR)) {
            throw new IllegalArgumentException("The configuration key '" + canonicalKey + "' contains '/'");
        }
        return structure.replace(".", READER_PATH_SEPARATOR);
    }

    /**
     * {@link #toReaderKey} applied to every key of {@code canonical}; the values are copied
     * untouched.
     *
     * @param canonical properties whose keys are already canonical.
     * @return a new {@link Properties}, for Jackson's properties reader only.
     */
    public static Properties toReaderKeys(Properties canonical) {
        var forReader = new Properties();
        for (var key : canonical.stringPropertyNames()) {
            forReader.setProperty(toReaderKey(key), canonical.getProperty(key));
        }
        return forReader;
    }

    private static String camel(String segment) {
        var sb = new StringBuilder(segment.length());
        boolean upper = false;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '-') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
