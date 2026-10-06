package io.ekbatan.test.postgres_sharded;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * A driver of the kind Flyway has no plugin for: it answers to a URL prefix of its own and passes
 * every call on to PostgreSQL's driver, as AWS's wrapper and Secrets Manager drivers do. Not
 * registered with {@code DriverManager}, so it is only ever reached by its class name.
 */
public final class WrappingDriver implements Driver {

    static final String PREFIX = "jdbc:ekbatan-wrapped:";

    /** How many connections were made through this driver, by any instance. */
    static final AtomicInteger CONNECTIONS = new AtomicInteger();

    private final Driver postgres = new org.postgresql.Driver();

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        CONNECTIONS.incrementAndGet();
        return postgres.connect(unwrapped(url), info);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return postgres.getPropertyInfo(unwrapped(url), info);
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    private static String unwrapped(String url) {
        return "jdbc:" + url.substring(PREFIX.length());
    }
}
