package io.ekbatan.events.streaming.actionevent.avro;

import org.apache.avro.util.ClassSecurityValidator;

/**
 * Puts a class on Avro's allow-list: the classes Avro may decode into.
 *
 * <p>Since 1.12.2 Avro decodes only into classes on that list - by default a few basic Java types,
 * plus whatever the {@code org.apache.avro.SERIALIZABLE_PACKAGES} and
 * {@code org.apache.avro.SERIALIZABLE_CLASSES} system properties name - and refuses any other with a
 * {@code SecurityException}. {@code ActionEvent} is generated, so this module's build adds a call to
 * {@link #allow} to its static setup, and a consumer reads one with nothing configured.
 *
 * <p>An Avro before 1.12.2 has no list, and nothing needs adding to it: there this does nothing. The
 * calls into the list are in {@link Listed}, which is loaded only once the list is known to exist,
 * so an older Avro never meets them.
 */
final class AvroAllowList {

    private AvroAllowList() {}

    /** Adds {@code type} to Avro's allow-list, keeping everything already on it. */
    static void allow(Class<?> type) {
        try {
            Class.forName("org.apache.avro.util.ClassSecurityValidator", false, AvroAllowList.class.getClassLoader());
        } catch (ClassNotFoundException olderAvro) {
            return;
        }
        Listed.add(type);
    }

    /** The calls into Avro's allow-list, kept apart so that an Avro without one never loads them. */
    private static final class Listed {

        private Listed() {}

        static void add(Class<?> type) {
            ClassSecurityValidator.setGlobal(ClassSecurityValidator.composite(
                    ClassSecurityValidator.getGlobal(),
                    ClassSecurityValidator.builder().add(type.getName()).build()));
        }
    }
}
