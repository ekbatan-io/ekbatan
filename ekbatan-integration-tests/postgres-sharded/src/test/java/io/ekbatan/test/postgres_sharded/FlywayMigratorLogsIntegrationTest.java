package io.ekbatan.test.postgres_sharded;

import static io.ekbatan.core.config.ShardGroupConfig.Builder.shardGroupConfig;
import static io.ekbatan.core.config.ShardMemberConfig.Builder.shardMemberConfig;
import static io.ekbatan.core.config.ShardingConfig.Builder.shardingConfig;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.config.ShardingConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.DataSources;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.flyway.FlywayMigrator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What a sharded migration writes to the log, against two real PostgreSQL shards.
 *
 * <p>Both shards' schema is {@code public}, which is the case Flyway's own lines cannot tell apart:
 * {@code Migrating schema "public" to version "1"} is the same sentence for either. The migrator
 * puts the shard and the target in the MDC while each one migrates; a Flyway callback reads the MDC
 * from inside Flyway's own run, on Flyway's thread, which is where every Flyway log line is
 * written. And the database password - a distinctive one - is looked for in everything written,
 * by Flyway, by Hikari (whose connection maker the migration connects through) and by the
 * framework, at every level, DEBUG included.
 */
@Testcontainers
class FlywayMigratorLogsIntegrationTest {

    private static final String SECRET = "Zq7-s3cret-java-pw";
    private static final String WRONG = "Wr0ng-Zq7-java-pw";
    private static final String KEY_SECRET = "K3y-Zq7-java-pw";

    @Container
    private static final PostgreSQLContainer globalDb = new PostgreSQLContainer("postgres:latest")
            .withDatabaseName("global_logs")
            .withUsername("app")
            .withPassword(SECRET)
            .withEnv("TZ", "UTC");

    @Container
    private static final PostgreSQLContainer mexicoDb = new PostgreSQLContainer("postgres:latest")
            .withDatabaseName("mexico_logs")
            .withUsername("app")
            .withPassword(SECRET)
            .withEnv("TZ", "UTC");

    private static final ShardIdentifier GLOBAL = ShardIdentifier.of(0, 0);
    private static final ShardIdentifier MEXICO = ShardIdentifier.of(1, 0);

    /** What the MDC held each time Flyway was about to apply a migration. */
    private static final class MdcRecorder extends BaseCallback {
        final List<String> seen = new CopyOnWriteArrayList<>();

        @Override
        public void handle(Event event, Context context) {
            if (event == Event.BEFORE_EACH_MIGRATE) {
                seen.add(MDC.get(FlywayMigrator.MDC_SHARD) + " " + MDC.get(FlywayMigrator.MDC_TARGET));
            }
        }
    }

    private static DataSourceConfig config(PostgreSQLContainer db, String jdbcUrl, String password) {
        return DataSourceConfig.Builder.dataSourceConfig()
                .jdbcUrl(jdbcUrl)
                .username(db.getUsername())
                .password(password)
                // a secret among the driver settings too, which Hikari's own settings map would print
                // in its debug dump; unused here, since nothing asks for an encrypted client key
                .dataSourceProperties(Map.of("sslpassword", KEY_SECRET))
                .build();
    }

    private static ShardingConfig layout(DataSourceConfig global, DataSourceConfig mexico) {
        return shardingConfig()
                .defaultShard(GLOBAL)
                .withGroup(shardGroupConfig()
                        .group(GLOBAL.group)
                        .name("global")
                        .withMember(shardMemberConfig()
                                .member(GLOBAL.member)
                                .primaryConfig(global)
                                .build())
                        .build())
                .withGroup(shardGroupConfig()
                        .group(MEXICO.group)
                        .name("mexico")
                        .withMember(shardMemberConfig()
                                .member(MEXICO.member)
                                .primaryConfig(mexico)
                                .build())
                        .build())
                .build();
    }

    /**
     * Everything Flyway, Hikari and the framework log while {@code work} runs - at every level,
     * DEBUG included - as java.util.logging receives it from slf4j-jdk14.
     */
    private static String logOf(Runnable work) {
        var lines = new StringBuilder();
        var handler = new Handler() {
            @Override
            public synchronized void publish(LogRecord record) {
                lines.append(record.getLevel())
                        .append(' ')
                        .append(record.getLoggerName())
                        .append(": ")
                        .append(record.getMessage())
                        .append('\n');
                if (record.getThrown() != null) {
                    lines.append(record.getThrown()).append('\n');
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(Level.ALL);
        var loggers = List.of(
                Logger.getLogger("org.flywaydb"),
                Logger.getLogger("com.zaxxer.hikari"),
                Logger.getLogger("io.ekbatan"));
        var levels = loggers.stream().map(Logger::getLevel).toList();
        loggers.forEach(logger -> {
            logger.setLevel(Level.ALL);
            logger.addHandler(handler);
        });
        try {
            work.run();
        } finally {
            for (var i = 0; i < loggers.size(); i++) {
                loggers.get(i).removeHandler(handler);
                loggers.get(i).setLevel(levels.get(i));
            }
        }
        return lines.toString();
    }

    private static <T> T capture(Supplier<T> work, StringBuilder log) {
        var result = new Object[1];
        log.append(logOf(() -> result[0] = work.get()));
        @SuppressWarnings("unchecked")
        var typed = (T) result[0];
        return typed;
    }

    @Test
    void every_flyway_line_of_a_shard_carries_its_shard_and_no_line_carries_the_password() {
        // GIVEN
        var recorder = new MdcRecorder();
        var global = config(globalDb, globalDb.getJdbcUrl(), SECRET);
        var mexico = config(mexicoDb, mexicoDb.getJdbcUrl(), SECRET);
        var log = new StringBuilder();

        // WHEN
        var results = capture(
                () -> FlywayMigrator.builder()
                        .withShardingConfig(layout(global, mexico))
                        .customize(cfg -> cfg.callbacks(recorder))
                        .migrate(),
                log);

        // THEN - each migration Flyway applied ran with its own shard in the MDC
        var applied = results.get(0).migrateResult().migrationsExecuted;
        assertThat(applied).isPositive();
        assertThat(recorder.seen).hasSize(applied * 2).containsOnly("0:0 global/member-0", "1:0 mexico/member-0");
        assertThat(recorder.seen.subList(0, applied)).containsOnly("0:0 global/member-0");

        // AND - a plain log says where each shard starts and ends
        assertThat(log)
                .contains("Migrating global/member-0 (0:0)")
                .contains("global/member-0 (0:0) is at version")
                .contains("Migrating mexico/member-0 (1:0)")
                .contains("Migrating schema \"public\"");

        // AND - the MDC is left as it was found
        assertThat(MDC.get(FlywayMigrator.MDC_SHARD)).isNull();
        assertThat(MDC.get(FlywayMigrator.MDC_TARGET)).isNull();

        // AND - the log is the whole of it: Flyway's lines, the URL among them, and Hikari's
        assertThat(log).contains("Database: jdbc:postgresql").contains("com.zaxxer.hikari");

        // AND - and no line, ours, Flyway's or Hikari's, at any level, carries the password, nor
        // the secret among the driver settings
        assertThat(log).doesNotContain(SECRET).doesNotContain(KEY_SECRET);
    }

    @Test
    void a_refused_login_neither_logs_nor_throws_the_password_it_tried() {
        // GIVEN
        var global = config(globalDb, globalDb.getJdbcUrl(), WRONG);
        var mexico = config(mexicoDb, mexicoDb.getJdbcUrl(), WRONG);
        var log = new StringBuilder();
        var thrown = new Throwable[1];

        // WHEN
        log.append(logOf(() -> {
            try {
                FlywayMigrator.builder()
                        .withShardingConfig(layout(global, mexico))
                        .migrate();
            } catch (RuntimeException e) {
                thrown[0] = e;
            }
        }));

        // THEN
        assertThat(thrown[0]).isInstanceOf(RuntimeException.class);
        assertThat(log).contains("Migrating global/member-0 (0:0) failed").contains("Stopped at global/member-0 (0:0)");

        // AND
        assertThat(log).doesNotContain(WRONG).doesNotContain(KEY_SECRET);
        var chain = new StringBuilder();
        for (var cause = thrown[0]; cause != null; cause = cause.getCause()) {
            chain.append(cause).append('\n');
        }
        assertThat(chain).doesNotContain(WRONG);
    }

    @Test
    void a_missing_username_is_warned_about_without_repeating_the_url() {
        // GIVEN - no username: the driver would fall back to the operating-system user
        var config = DataSourceConfig.Builder.dataSourceConfig()
                .jdbcUrl(globalDb.getJdbcUrl())
                .password(SECRET)
                .build();

        // WHEN
        var log = logOf(() -> DataSources.driverDataSource(config));

        // THEN - (Hikari's own DEBUG line names the URL, with any password in it masked; ours does not)
        var warning = log.lines()
                .filter(line -> line.startsWith("WARNING io.ekbatan.core.persistence.DataSources"))
                .toList();
        assertThat(warning)
                .singleElement()
                .asString()
                .contains("No username is configured")
                .doesNotContain(globalDb.getJdbcUrl());
        assertThat(log).doesNotContain(SECRET);
    }

    @Test
    void the_application_pool_logs_neither_the_password_nor_a_driver_secret() {
        // GIVEN
        var config = config(globalDb, globalDb.getJdbcUrl(), SECRET);

        // WHEN - Hikari dumps its whole configuration at DEBUG when the pool starts
        var log = logOf(() -> {
            try (var pool = ConnectionProvider.hikariConnectionProvider(config)) {
                pool.release(pool.acquire());
            }
        });

        // THEN - the dump was captured, so the search below has something to search
        assertThat(log).contains("dataSource.....").contains("dataSourceProperties");

        // AND
        assertThat(log).doesNotContain(SECRET).doesNotContain(KEY_SECRET);
    }
}
