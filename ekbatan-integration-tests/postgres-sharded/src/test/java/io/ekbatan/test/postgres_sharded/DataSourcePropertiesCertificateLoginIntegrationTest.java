package io.ekbatan.test.postgres_sharded;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.flyway.FlywayMigrator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

/**
 * A TLS client-certificate login whose private key is encrypted, against a real PostgreSQL that
 * accepts no other kind of login - the case {@code data-source-properties} exists for.
 *
 * <p>There is no password anywhere: the certificate is the login. What opens the client key is the
 * key's own password, and the only place it may go is {@code data-source-properties}, as the
 * driver's {@code sslpassword} setting: a URL that carries it is refused, and {@code password} is
 * the login password, which this login does not have. The server's {@code pg_hba.conf} allows
 * only {@code hostssl ... cert}, so a connection that gets in has presented the client certificate,
 * which it can only do with the key opened.
 *
 * <p>The certificates are made fresh for each run by {@link TestCertificates}; nothing secret is
 * committed. Every log line written meanwhile - the driver's, Hikari's, Flyway's and the
 * framework's, at every level - is searched for the key password.
 */
class DataSourcePropertiesCertificateLoginIntegrationTest {

    private static final String USER = "orders_app";
    private static final String KEY_PASSWORD = "K3y-Zq7-cert-pw";
    private static final String WRONG = "Wr0ng-Zq7-cert-pw";

    /** Only TLS logins with a client certificate; the local socket stays open for initdb. */
    private static final String PG_HBA = "local all all trust\nhostssl all all all cert\n";

    private static final String INIT_SQL =
            "CREATE ROLE " + USER + " LOGIN;\nCREATE DATABASE orders OWNER " + USER + ";\n";

    /**
     * The key file must belong to the server's user and be private to it, and files copied into
     * a container belong to root: so they are copied again, owned and locked down, before the
     * image's own entrypoint starts the server with TLS on.
     */
    private static final String ENTRYPOINT = "set -e; mkdir -p /certs; cp /certs-in/* /certs/;"
            + " chown postgres:postgres /certs/*; chmod 600 /certs/server.key;"
            + " exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/certs/server.crt"
            + " -c ssl_key_file=/certs/server.key -c ssl_ca_file=/certs/ca.crt -c hba_file=/certs/pg_hba.conf";

    @TempDir
    static Path directory;

    private static TestCertificates.Material tls;
    private static GenericContainer<?> db;
    private static String location;

    @BeforeAll
    static void startServer() throws Exception {
        tls = TestCertificates.write(directory, USER, KEY_PASSWORD.toCharArray());
        db = new GenericContainer<>("postgres:17-alpine")
                // only initdb's own socket login; pg_hba.conf above decides every network login
                .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
                .withCopyFileToContainer(MountableFile.forHostPath(tls.serverCertificate()), "/certs-in/server.crt")
                .withCopyFileToContainer(MountableFile.forHostPath(tls.serverKey()), "/certs-in/server.key")
                .withCopyFileToContainer(MountableFile.forHostPath(tls.caCertificate()), "/certs-in/ca.crt")
                .withCopyToContainer(Transferable.of(PG_HBA), "/certs-in/pg_hba.conf")
                .withCopyToContainer(Transferable.of(INIT_SQL), "/docker-entrypoint-initdb.d/init.sql")
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh", "-c", ENTRYPOINT))
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2)
                        .withStartupTimeout(Duration.ofSeconds(90)));
        db.start();

        var migrations = Files.createDirectories(directory.resolve("migrations"));
        Files.writeString(
                migrations.resolve("V1__seen.sql"),
                "CREATE TABLE seen AS SELECT current_user AS who,"
                        + " (SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()) AS tls,"
                        + " (SELECT client_dn FROM pg_stat_ssl WHERE pid = pg_backend_pid()) AS client_dn;");
        location = "filesystem:" + migrations;
    }

    @AfterAll
    static void stopServer() {
        if (db != null) {
            db.stop();
        }
    }

    /** The URL a certificate login needs: verify the server, present the client certificate. */
    private static String url() {
        return "jdbc:postgresql://" + db.getHost() + ":" + db.getMappedPort(5432) + "/orders"
                + "?sslmode=verify-full"
                + "&sslrootcert=" + tls.caCertificate()
                + "&sslcert=" + tls.clientCertificate()
                + "&sslkey=" + tls.clientKey();
    }

    private static DataSourceConfig config(Map<String, String> driverSettings) {
        return dataSourceConfig()
                .jdbcUrl(url())
                .username(USER)
                .dataSourceProperties(driverSettings)
                .build();
    }

    @Test
    void a_certificate_login_with_an_encrypted_key_works_for_migrations_and_the_application_pool() throws Exception {
        // GIVEN - no password: the certificate is the login, and only data-source-properties
        // holds what opens its key
        var config = config(Map.of("sslpassword", KEY_PASSWORD));
        assertThat(config.password).isEmpty();
        var log = new StringBuilder();

        // WHEN
        var migrated = capture(log, () -> FlywayMigrator.migrate(config, location));

        // THEN - the migration logged in as the certificate's user, over TLS
        assertThat(migrated.migrationsExecuted).isEqualTo(1);
        try (var pool = capture(log, () -> ConnectionProvider.hikariConnectionProvider(config))) {
            var connection = capture(log, pool::acquire);
            try (var rows = connection
                    .createStatement()
                    .executeQuery("SELECT who, tls, client_dn, current_user,"
                            + " (SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()) FROM seen")) {
                rows.next();
                assertThat(rows.getString("who")).isEqualTo(USER);
                assertThat(rows.getBoolean("tls")).isTrue();
                assertThat(rows.getString("client_dn")).contains("CN=" + USER);

                // AND - so did the application pool's connection
                assertThat(rows.getString(4)).isEqualTo(USER);
                assertThat(rows.getBoolean(5)).isTrue();
            } finally {
                pool.release(connection);
            }
        }

        // AND - no log line, at any level, carries the key password
        assertThat(log).isNotEmpty().doesNotContain(KEY_PASSWORD);
    }

    @Test
    void without_the_key_password_the_login_fails() {
        // GIVEN - the certificate and key are named in the URL, but nothing can open the key
        var config = config(Map.of());

        // WHEN
        var thrown = catchThrowable(() -> FlywayMigrator.migrate(config, location));

        // THEN - the driver had no password to open the key with
        assertThat(thrown).isInstanceOf(RuntimeException.class);
        assertThat(chainOf(thrown)).contains("Could not read password for SSL key file");
    }

    @Test
    void a_wrong_key_password_fails_and_is_never_repeated() {
        // GIVEN
        var config = config(Map.of("sslpassword", WRONG));
        var log = new StringBuilder();

        // WHEN
        var thrown = catchThrowable(() -> capture(log, () -> FlywayMigrator.migrate(config, location)));

        // THEN - the password reached the driver, which could not open the key with it
        assertThat(thrown).isInstanceOf(RuntimeException.class);
        assertThat(chainOf(thrown)).contains("Could not decrypt SSL key file");
        assertThat(chainOf(thrown)).doesNotContain(WRONG);
        assertThat(log).doesNotContain(WRONG);
    }

    @Test
    void the_key_password_in_the_url_is_refused_before_anything_connects() {
        // GIVEN - the same password, put where a URL would log it
        var urlWithKeyPassword = url() + "&sslpassword=" + KEY_PASSWORD;

        // WHEN / THEN - refused, pointed at data-source-properties, and not repeated
        assertThatThrownBy(() -> dataSourceConfig()
                        .jdbcUrl(urlWithKeyPassword)
                        .username(USER)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'sslpassword' setting")
                .hasMessageContaining("data-source-properties")
                .hasMessageNotContaining(KEY_PASSWORD)
                .hasMessageNotContaining(tls.clientKey().toString());
    }

    @Test
    void this_server_accepts_no_login_but_a_certificate() {
        // GIVEN - control: a plain password login, the kind the passing test did not use
        var config = dataSourceConfig()
                .jdbcUrl("jdbc:postgresql://" + db.getHost() + ":" + db.getMappedPort(5432) + "/orders?sslmode=disable")
                .username(USER)
                .password("anything")
                .build();

        // WHEN
        var thrown = catchThrowable(() -> FlywayMigrator.migrate(config, location));

        // THEN
        assertThat(chainOf(thrown)).contains("no pg_hba.conf entry");
    }

    /** Runs {@code work} with every relevant logger at ALL, appending what they write to {@code log}. */
    private static <T> T capture(StringBuilder log, ThrowingSupplier<T> work) throws Exception {
        var handler = new Handler() {
            @Override
            public synchronized void publish(LogRecord record) {
                log.append(record.getLevel())
                        .append(' ')
                        .append(record.getLoggerName())
                        .append(": ")
                        .append(record.getMessage())
                        .append('\n');
                if (record.getThrown() != null) {
                    log.append(chainOf(record.getThrown()));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(Level.ALL);
        var loggers = List.of(
                Logger.getLogger("org.postgresql"),
                Logger.getLogger("org.flywaydb"),
                Logger.getLogger("com.zaxxer.hikari"),
                Logger.getLogger("io.ekbatan"));
        var levels = loggers.stream().map(Logger::getLevel).toList();
        loggers.forEach(logger -> {
            logger.setLevel(Level.ALL);
            logger.addHandler(handler);
        });
        try {
            return work.get();
        } finally {
            for (var i = 0; i < loggers.size(); i++) {
                loggers.get(i).removeHandler(handler);
                loggers.get(i).setLevel(levels.get(i));
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static String chainOf(Throwable thrown) {
        var chain = new StringBuilder();
        for (var cause = thrown; cause != null; cause = cause.getCause()) {
            chain.append(cause).append('\n');
        }
        return chain.toString();
    }
}
