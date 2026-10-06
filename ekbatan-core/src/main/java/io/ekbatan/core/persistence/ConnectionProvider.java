package io.ekbatan.core.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.ekbatan.core.config.DataSourceConfig;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Owns a HikariCP connection pool and exposes acquire / release / evict / close on top of it.
 * Each {@link TransactionManager} holds a pair (primary + optional read-replica secondary);
 * the framework wraps individual {@code Connection}s through {@code acquire()} / {@code
 * release()} rather than handing the underlying {@link DataSource} out directly so connection
 * lifecycle is centralised here.
 *
 * <p>Construct via {@link #hikariConnectionProvider(DataSourceConfig)} - the canonical
 * factory wires {@code jdbcUrl}, {@code username}, {@code password}, pool sizes, and leak-
 * detection threshold straight from a {@link DataSourceConfig}. The constructor is private,
 * so the factory method is the only public construction path; the pool implementation is
 * HikariCP and not pluggable from outside this class. The public {@link #getDataSource()}
 * accessor returns {@link DataSource} (the JDBC standard interface) so {@code HikariDataSource}
 * never appears in this class's public API surface; consumers who need pool-specific tuning
 * may downcast, but must then declare HikariCP in their own build.
 *
 * <p>The pool draws every connection from {@link DataSources#driverDataSource(DataSourceConfig)},
 * which a one-off job such as a migration uses directly, so the two connect the same way.
 *
 * <p>{@link #close()} is idempotent; the registry closes every provider on application
 * shutdown.
 */
public class ConnectionProvider implements AutoCloseable {

    private final HikariDataSource pool;

    private ConnectionProvider(HikariDataSource pool) {
        this.pool = pool;
    }

    /** {@return a fresh JDBC {@link Connection} drawn from the underlying pool} */
    public Connection acquire() {
        try {
            return pool.getConnection();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire connection", e);
        }
    }

    /**
     * Returns a connection to the pool. The underlying Hikari {@code ProxyConnection.close()}
     * recycles the physical connection rather than actually closing it.
     *
     * @param connection a connection previously obtained via {@link #acquire()}.
     */
    public void release(Connection connection) {
        try {
            // Hikari have ProxyConnection which implements Connection interface,
            // close function in that class would return connection to the pool
            connection.close();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to release connection", e);
        }
    }

    /**
     * Forcibly removes the connection from the pool instead of returning it. The underlying
     * physical connection is closed, and the pool will create a replacement to maintain its
     * configured size. Use this when a connection is suspected to be in a corrupted or unknown
     * state (e.g., a SQL error left it with leftover session settings, or a release operation
     * itself failed).
     *
     * @param connection a connection previously obtained via {@link #acquire()}.
     */
    public void evict(Connection connection) {
        pool.evictConnection(connection);
    }

    /**
     * {@return the underlying {@link DataSource}; rarely needed - prefer acquire/release}
     *
     * <p>The runtime implementation is {@code HikariDataSource}, but the declared return type
     * is the JDBC standard interface so HikariCP does not leak into this class's public API.
     * Callers needing pool-specific tuning may downcast - that downcast couples their build
     * to HikariCP, which they then must declare for themselves.
     */
    public DataSource getDataSource() {
        return pool;
    }

    /**
     * {@return the JDBC URL this pool was configured with}
     *
     * <p>Exposed because the dialect can be resolved from it <em>without opening a connection</em>.
     * That matters wherever a component must choose dialect-specific behaviour at construction
     * time: pools are built with {@code initializationFailTimeout = -1} so an application can
     * start before its database is reachable, which means a connection obtained at that moment may
     * not exist yet. Anything that decides on the strength of such a connection risks deciding
     * wrongly and keeping that decision. Reading the URL cannot fail that way.
     */
    public String jdbcUrl() {
        return pool.getJdbcUrl();
    }

    /**
     * Closes the underlying Hikari pool. Idempotent - safe to call multiple times.
     * After {@code close()}, no further {@link #acquire()} calls are valid.
     */
    @Override
    public void close() {
        pool.close();
    }

    /**
     * Canonical factory: builds a Hikari-backed provider from a {@link DataSourceConfig}.
     *
     * @param cfg the data-source configuration.
     * @return a fresh provider with its own pool; the caller owns lifecycle and must {@link #close()} it.
     */
    public static ConnectionProvider hikariConnectionProvider(DataSourceConfig cfg) {
        var hikari = new HikariConfig();
        // The pool draws every connection from the data source a migration uses directly. Hikari
        // ignores the URL when it is given a data source; it is set only so jdbcUrl() can report it.
        hikari.setDataSource(DataSources.driverDataSource(cfg));
        hikari.setJdbcUrl(cfg.jdbcUrl);
        hikari.setMaximumPoolSize(cfg.maximumPoolSize);
        hikari.setInitializationFailTimeout(-1);
        cfg.minimumIdle.ifPresent(hikari::setMinimumIdle);
        cfg.idleTimeout.ifPresent(hikari::setIdleTimeout);
        cfg.leakDetectionThreshold.ifPresent(hikari::setLeakDetectionThreshold);
        return new ConnectionProvider(new HikariDataSource(hikari));
    }
}
