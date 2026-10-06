package io.ekbatan.flyway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.shard.ShardIdentifier;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Which database a migration's log lines are about.
 *
 * <p>Flyway names only the schema, and every PostgreSQL shard is usually {@code public}, so the
 * migrator puts the shard and the target's name in the MDC while each target migrates, and logs a
 * line before and after it. What Flyway itself writes under that context, against real shards,
 * is {@code ekbatan-integration-tests/postgres-sharded}.
 */
class FlywayMigratorLogContextTest {

    private static final ShardIdentifier MEXICO = ShardIdentifier.of(1, 0);

    /** The migrator's log, as java.util.logging receives it from slf4j-jdk14. */
    private static final Logger JUL = Logger.getLogger(FlywayMigrator.class.getName());

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            lines.add(record.getLevel() + " " + record.getMessage());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };

    @BeforeEach
    void captureTheLog() {
        handler.setLevel(Level.ALL);
        JUL.addHandler(handler);
    }

    @AfterEach
    void clearMdcAndStopCapturing() {
        MDC.clear();
        JUL.removeHandler(handler);
    }

    private static MigrateResult applied(String version, int count) {
        var result = new MigrateResult();
        result.targetSchemaVersion = version;
        result.migrationsExecuted = count;
        return result;
    }

    @Test
    void the_mdc_in_this_test_run_is_a_real_one() {
        // GIVEN / WHEN / THEN - without a backend SLF4J's MDC drops everything, and every test
        // below would pass without proving anything.
        MDC.put("probe", "here");
        assertThat(MDC.get("probe")).isEqualTo("here");
    }

    @Test
    void puts_the_shard_and_the_target_in_the_mdc_while_the_target_migrates() {
        // GIVEN
        var seen = new HashMap<String, String>();

        // WHEN
        FlywayMigrator.inLogContext(MEXICO, "mexico/member-0", () -> {
            seen.put("shard", MDC.get(FlywayMigrator.MDC_SHARD));
            seen.put("target", MDC.get(FlywayMigrator.MDC_TARGET));
            return applied("3", 2);
        });

        // THEN
        assertThat(seen).containsEntry("shard", "1:0").containsEntry("target", "mexico/member-0");

        // AND
        assertThat(MDC.get(FlywayMigrator.MDC_SHARD)).isNull();
        assertThat(MDC.get(FlywayMigrator.MDC_TARGET)).isNull();
    }

    @Test
    void puts_back_what_the_caller_had_under_the_same_keys_even_when_the_migration_fails() {
        // GIVEN
        MDC.put(FlywayMigrator.MDC_SHARD, "caller-shard");
        MDC.put(FlywayMigrator.MDC_TARGET, "caller-target");

        // WHEN / THEN
        assertThatThrownBy(() -> FlywayMigrator.inLogContext(MEXICO, "mexico/member-0", () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        // AND
        assertThat(MDC.get(FlywayMigrator.MDC_SHARD)).isEqualTo("caller-shard");
        assertThat(MDC.get(FlywayMigrator.MDC_TARGET)).isEqualTo("caller-target");
    }

    @Test
    void logs_a_line_before_and_after_each_target_naming_it() {
        // GIVEN / WHEN
        FlywayMigrator.inLogContext(MEXICO, "mexico/member-0", () -> applied("3", 2));

        // THEN
        assertThat(lines)
                .containsExactly(
                        "INFO Migrating mexico/member-0 (1:0)",
                        "INFO mexico/member-0 (1:0) is at version 3 (2 applied)");
    }

    @Test
    void reports_the_version_it_found_when_nothing_was_applied() {
        // GIVEN
        var nothing = new MigrateResult();
        nothing.initialSchemaVersion = "3";

        // WHEN
        FlywayMigrator.inLogContext(MEXICO, "mexico/member-0", () -> nothing);

        // THEN
        assertThat(lines).contains("INFO mexico/member-0 (1:0) is at version 3 (0 applied)");
    }

    @Test
    void logs_a_failure_naming_the_target_and_nothing_from_the_exception() {
        // GIVEN / WHEN
        assertThatThrownBy(() -> FlywayMigrator.inLogContext(MEXICO, "mexico/member-0", () -> {
                    throw new IllegalStateException("password=Zq7-s3cret was rejected");
                }))
                .isInstanceOf(IllegalStateException.class);

        // THEN - the exception goes to the caller as it was; the migrator's own line repeats none
        // of it, since an exception message can carry what a log must not
        assertThat(lines)
                .containsExactly(
                        "INFO Migrating mexico/member-0 (1:0)", "SEVERE Migrating mexico/member-0 (1:0) failed");
    }
}
