package io.ekbatan.core.test.repository;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static io.ekbatan.core.test.model.Dummy.createDummy;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.test.model.Dummy;
import io.ekbatan.flyway.FlywayMigrator;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Ekbatan's timestamps on a PostgreSQL server outside UTC, written by a JVM outside UTC too - the
 * server on Tehran time, the JVM on Tokyo time, neither with daylight saving - and what the docs
 * say about them:
 *
 * <ul>
 *   <li>Ekbatan stores UTC and reads back what it wrote, whatever either zone is:
 *       {@code InstantConverter} turns an {@code Instant} into UTC wall time in Java.
 *   <li>A session takes the JVM's zone, not the server's: PostgreSQL's driver sends it when it
 *       connects, over the server's and over {@code options=-c TimeZone=...}. So the database's own
 *       clock ({@code LOCALTIMESTAMP}) reads Tokyo time, hours from Ekbatan's values, while the
 *       JVM is outside UTC, and UTC once it runs in UTC.
 * </ul>
 *
 * <p>The JVM's zone is switched for this class only and restored after it, so the test means the
 * same on a machine or a CI runner that runs in UTC.
 */
@Testcontainers
class PgTimeZoneIntegrationTest {

    @Container
    private static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:latest")
            .withDatabaseName("zones")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "Asia/Tehran");

    private static final Instant WRITTEN = Instant.parse("2026-01-15T12:00:00.123456Z");

    private static final Currency EUR = Currency.getInstance("EUR");

    private static final TimeZone TOKYO = TimeZone.getTimeZone("Asia/Tokyo");

    private static TimeZone jvmZone;

    @BeforeAll
    static void runTheJvmOnTokyoTime() {
        jvmZone = TimeZone.getDefault();
        TimeZone.setDefault(TOKYO);
        FlywayMigrator.migrate(config(Map.of()));
    }

    @AfterAll
    static void restoreTheJvmZone() {
        TimeZone.setDefault(jvmZone);
    }

    private static DataSourceConfig config(Map<String, String> settings) {
        return dataSourceConfig()
                .jdbcUrl(DB.getJdbcUrl())
                .username(DB.getUsername())
                .password(DB.getPassword())
                .dataSourceProperties(settings)
                .build();
    }

    /** Ekbatan's repository over its own pool, with the given driver settings. */
    private record Ekbatan(ConnectionProvider pool, TransactionManager transactions, DummyRepository repository)
            implements AutoCloseable {

        static Ekbatan with(Map<String, String> settings) {
            var pool = ConnectionProvider.hikariConnectionProvider(config(settings));
            var transactions = new TransactionManager(pool, pool, SQLDialect.POSTGRES);
            return new Ekbatan(
                    pool,
                    transactions,
                    new DummyRepository(
                            databaseRegistry().withDatabase(transactions).build()));
        }

        String text(String sql, Dummy dummy) throws SQLException {
            try (var connection = pool.getDataSource().getConnection();
                    var statement = connection.prepareStatement(sql)) {
                if (dummy != null) {
                    statement.setObject(1, dummy.getId().getValue());
                }
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getString(1);
                }
            }
        }

        /** The created date exactly as the server holds it. */
        String storedCreatedDate(Dummy dummy) throws SQLException {
            return text("SELECT CAST(created_date AS TEXT) FROM dummies WHERE id = ?", dummy);
        }

        /** Whole minutes from the created date to the given clock. */
        long minutesTo(String clock, Dummy dummy) throws SQLException {
            return Long.parseLong(text(
                    "SELECT CAST(floor(EXTRACT(EPOCH FROM (" + clock + " - created_date)) / 60) AS BIGINT)"
                            + " FROM dummies WHERE id = ?",
                    dummy));
        }

        @Override
        public void close() {
            transactions.close();
        }
    }

    @Test
    void ekbatan_stores_utc_and_reads_back_what_it_wrote_whatever_the_zones() throws Exception {
        try (var ekbatan = Ekbatan.with(Map.of())) {
            // GIVEN - the JVM on Tokyo time, and the server's own zone Tehran
            assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Tokyo");
            assertThat(ekbatan.text("SELECT current_setting('log_timezone')", null))
                    .isEqualTo("Asia/Tehran");
            var single = createDummy(randomUUID(), EUR, BigDecimal.TEN, WRITTEN).build();
            var batch = List.of(
                    createDummy(randomUUID(), EUR, BigDecimal.TEN, WRITTEN).build(),
                    createDummy(randomUUID(), EUR, BigDecimal.TEN, WRITTEN).build());

            // WHEN - written alone and in a batch, then updated both ways
            ekbatan.repository().add(single);
            ekbatan.repository().addAll(batch);
            ekbatan.repository().update(single.withdraw(BigDecimal.ONE));
            ekbatan.repository()
                    .updateAll(
                            batch.stream().map(d -> d.withdraw(BigDecimal.ONE)).toList());

            // THEN - every row holds the UTC wall time, and reads back as written
            for (var dummy : List.of(single, batch.get(0), batch.get(1))) {
                assertThat(ekbatan.storedCreatedDate(dummy)).isEqualTo("2026-01-15 12:00:00.123456");
                assertThat(ekbatan.repository().getById(dummy.getId().getValue()).createdDate)
                        .isEqualTo(WRITTEN);
            }
        }
    }

    @Test
    void a_session_takes_the_jvms_zone_not_the_servers() throws Exception {
        try (var plain = Ekbatan.with(Map.of());
                var withOptions = Ekbatan.with(Map.of("options", "-c TimeZone=UTC"))) {
            // GIVEN - a row written now
            var now = createDummy(randomUUID(), EUR, BigDecimal.TEN, Instant.now())
                    .build();
            plain.repository().add(now);

            // WHEN / THEN - the session is on Tokyo time, though the server is on Tehran time
            assertThat(plain.text("SELECT current_setting('TimeZone')", null)).isEqualTo("Asia/Tokyo");

            // AND - so the database's local clock reads 540 minutes from Ekbatan's value, and so
            // does NOW(), which reads the column as session time; its UTC clock agrees
            assertThat(plain.minutesTo("LOCALTIMESTAMP", now)).isEqualTo(540);
            assertThat(plain.minutesTo("NOW()", now)).isEqualTo(540);
            assertThat(plain.minutesTo("(NOW() AT TIME ZONE 'UTC')", now)).isZero();

            // AND - options=-c TimeZone=UTC does not change it: the driver's zone wins
            assertThat(withOptions.text("SELECT current_setting('TimeZone')", null))
                    .isEqualTo("Asia/Tokyo");
        }
    }

    @Test
    void with_the_jvm_in_utc_the_session_and_its_clock_are_utc() throws Exception {
        // GIVEN - the JVM in UTC, as the docs advise, while the server stays on Tehran time
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try (var ekbatan = Ekbatan.with(Map.of())) {
            var now = createDummy(randomUUID(), EUR, BigDecimal.TEN, Instant.now())
                    .build();

            // WHEN
            ekbatan.repository().add(now);

            // THEN - the session runs in UTC, and the database's local clock agrees with Ekbatan
            assertThat(ekbatan.text("SELECT current_setting('TimeZone')", null)).isEqualTo("UTC");
            assertThat(ekbatan.minutesTo("LOCALTIMESTAMP", now)).isZero();
        } finally {
            TimeZone.setDefault(TOKYO);
        }
    }
}
