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
import org.testcontainers.mysql.MySQLContainer;

/**
 * Ekbatan's timestamps on a MySQL server outside UTC, written by a JVM outside UTC too - the
 * server on Tehran time, the JVM on Tokyo time, neither with daylight saving - and what the docs
 * say about them:
 *
 * <ul>
 *   <li>Ekbatan stores UTC and reads back what it wrote, with no driver setting, whatever either
 *       zone is: {@code InstantConverter} turns an {@code Instant} into UTC wall time in Java.
 *   <li>The database's own clock is the server's: {@code NOW()} reads Tehran time, hours from
 *       Ekbatan's values, until {@code sessionVariables} sets the session's zone to UTC.
 *   <li>{@code serverTimezone=UTC} makes MySQL's driver store Ekbatan's values shifted by the
 *       JVM's offset, while reading them back looks right.
 * </ul>
 *
 * <p>The JVM's zone is switched for this class only and restored after it, so the test means the
 * same on a machine or a CI runner that runs in UTC.
 */
@Testcontainers
class MysqlTimeZoneIntegrationTest {

    @Container
    private static final MySQLContainer DB = new MySQLContainer("mysql:9.4.0")
            .withDatabaseName("zones")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "Asia/Tehran");

    private static final Instant WRITTEN = Instant.parse("2026-01-15T12:00:00.123456Z");

    private static final Currency EUR = Currency.getInstance("EUR");

    private static TimeZone jvmZone;

    @BeforeAll
    static void runTheJvmOnTokyoTime() {
        jvmZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
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
            var transactions = new TransactionManager(pool, pool, SQLDialect.MYSQL);
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
                    statement.setString(1, dummy.getId().getValue().toString());
                }
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getString(1);
                }
            }
        }

        /** The created date exactly as the server holds it. */
        String storedCreatedDate(Dummy dummy) throws SQLException {
            return text("SELECT CAST(created_date AS CHAR) FROM dummies WHERE id = ?", dummy);
        }

        /** Whole minutes from the created date to the database's own clock. */
        long minutesToTheDatabaseClock(Dummy dummy) throws SQLException {
            return Long.parseLong(
                    text("SELECT TIMESTAMPDIFF(MINUTE, created_date, NOW(6)) FROM dummies WHERE id = ?", dummy));
        }

        @Override
        public void close() {
            transactions.close();
        }
    }

    @Test
    void ekbatan_stores_utc_and_reads_back_what_it_wrote_whatever_the_zones() throws Exception {
        try (var ekbatan = Ekbatan.with(Map.of())) {
            // GIVEN - the JVM on Tokyo time, and the server's clock three and a half hours from UTC
            assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Tokyo");
            assertThat(ekbatan.text("SELECT TIMESTAMPDIFF(MINUTE, UTC_TIMESTAMP(), NOW())", null))
                    .isEqualTo("210");
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
    void the_database_clock_is_the_servers_until_the_session_runs_in_utc() throws Exception {
        try (var plain = Ekbatan.with(Map.of());
                var utc = Ekbatan.with(Map.of("sessionVariables", "time_zone='+00:00'"))) {
            // GIVEN - a row written now
            var now = createDummy(randomUUID(), EUR, BigDecimal.TEN, Instant.now())
                    .build();
            plain.repository().add(now);

            // WHEN / THEN - NOW() reads Tehran time, 210 minutes from Ekbatan's value
            assertThat(plain.minutesToTheDatabaseClock(now)).isEqualTo(210);

            // AND - with the session's zone set as the docs show, it agrees
            assertThat(utc.text("SELECT @@session.time_zone", null)).isEqualTo("+00:00");
            assertThat(utc.minutesToTheDatabaseClock(now)).isZero();
        }
    }

    @Test
    void server_timezone_stores_ekbatans_values_shifted_by_the_jvms_offset() throws Exception {
        // GIVEN - the setting the docs warn against
        try (var ekbatan = Ekbatan.with(Map.of("serverTimezone", "UTC"))) {
            var dummy = createDummy(randomUUID(), EUR, BigDecimal.TEN, WRITTEN).build();

            // WHEN
            ekbatan.repository().add(dummy);

            // THEN - stored nine hours early, Tokyo's offset
            assertThat(ekbatan.storedCreatedDate(dummy)).isEqualTo("2026-01-15 03:00:00.123456");

            // AND - read back through the same setting it looks right, so nothing shows it
            assertThat(ekbatan.repository().getById(dummy.getId().getValue()).createdDate)
                    .isEqualTo(WRITTEN);
        }
    }
}
