package io.ekbatan.core.persistence;

import com.zaxxer.hikari.util.DriverDataSource;
import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.internal.Validate;
import java.util.Properties;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How Ekbatan opens a connection from a {@link DataSourceConfig} - the one place a connection is
 * made, whoever uses it. A {@link ConnectionProvider}'s pool draws its connections from here, and a
 * one-off job, a migration above all, uses them directly.
 */
public final class DataSources {

    private static final Logger LOG = LoggerFactory.getLogger(DataSources.class);

    private DataSources() {}

    /**
     * A {@link DataSource} that opens a new connection through the configured driver on every
     * {@code getConnection()} - same driver, same settings, same login - and hands out the driver's
     * own connection, so closing it closes it for good. Nothing is kept or reused here: keeping
     * connections is a pool's job, and Ekbatan's pool ({@link
     * ConnectionProvider#hikariConnectionProvider}) draws every connection it keeps from this.
     *
     * <p>Used directly, it suits a one-off job that must connect the way the application does
     * without sharing the application's connections, a migration above all. A pool is the wrong
     * tool for that job, twice over. It retries a refused login until its connection timeout -
     * against PostgreSQL with a wrong password, 30 seconds and 14 refused logins, where this fails at
     * the first attempt, as a driver does. And whatever the job sets on a connection - a migration's
     * {@code SET statement_timeout} - stays on it, in a pool the application then draws from.
     *
     * <p>The driver settings travel inside it rather than in Hikari's own
     * {@code dataSourceProperties}: Hikari's debug dump of its configuration masks only a setting
     * named {@code password}, so a client key's {@code sslpassword} there would be printed. Hikari
     * logs this object only by its class name. A setting Hikari applies itself as it opens each
     * pooled connection - a {@code HikariCredentialsProvider} is one - must be applied here as well,
     * for a job that uses this directly, with no pool to do it.
     *
     * <p>A warning is logged when no username is configured: the driver or a plugin must then supply
     * one, or the driver logs in as the operating-system user.
     *
     * @param cfg the data-source configuration.
     * @return a data source with nothing to close; each connection it opens is the caller's to
     *     close.
     */
    public static DataSource driverDataSource(DataSourceConfig cfg) {
        Validate.notNull(cfg, "dataSourceConfig cannot be null");
        if (cfg.username.isEmpty()) {
            LOG.warn("No username is configured: the driver or a plugin must supply one, or the driver"
                    + " logs in as the operating-system user");
        }
        var driverSettings = new Properties();
        driverSettings.putAll(cfg.dataSourceProperties);
        return new DriverDataSource(
                cfg.jdbcUrl,
                cfg.driverClassName.orElse(null),
                driverSettings,
                cfg.username.orElse(null),
                cfg.password.orElse(null));
    }
}
