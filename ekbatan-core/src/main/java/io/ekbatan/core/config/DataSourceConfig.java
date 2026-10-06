package io.ekbatan.core.config;

import io.ekbatan.core.internal.Validate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jooq.SQLDialect;
import org.jooq.tools.jdbc.JDBCUtils;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

/**
 * HikariCP-compatible configuration with auto-resolved dialect from JDBC URL.
 */
@JsonDeserialize(builder = DataSourceConfig.Builder.class)
public final class DataSourceConfig {

    /** The JDBC connection URL (e.g. {@code jdbc:postgresql://...}). */
    public final String jdbcUrl;

    /**
     * Database username; empty when something else supplies it - a driver plugin named in
     * {@link #dataSourceProperties} (MariaDB's {@code credentialType}, the AWS wrapper's Secrets
     * Manager plugin). Never blank: an empty value is far more often an unset variable than a
     * choice. With no username and nothing to supply one, every supported driver falls back to the
     * operating-system user. The URL may not carry the user: a URL is logged.
     */
    public final Optional<String> username;

    /**
     * Database password; empty for a login that has none - IAM tokens, client certificates,
     * Kerberos, or a driver plugin that supplies it. The empty string stays a valid password.
     */
    public final Optional<String> password;

    /**
     * Extra settings handed to the driver exactly as written - names are case-sensitive and may
     * contain dots ({@code ha.enableJMX}) - such as a client key's {@code sslpassword}, a keystore
     * password, or a driver's own login plugin. Every value is treated as a secret and never
     * printed. {@code user} and {@code password} are refused among them, in any case: there they
     * would silently win over {@link #username} and {@link #password}.
     */
    public final Map<String, String> dataSourceProperties;

    /** jOOQ {@link SQLDialect} resolved from {@link #jdbcUrl}; consulted by the framework for dialect-specific SQL. */
    public final SQLDialect dialect;

    /** Optional explicit driver class name; HikariCP resolves automatically when absent. */
    public final Optional<String> driverClassName;

    /** Maximum size of the Hikari pool (default 10). */
    public final int maximumPoolSize;

    /** Optional minimum number of idle connections kept warm. */
    public final Optional<Integer> minimumIdle;

    /** Optional idle timeout in milliseconds. */
    public final Optional<Long> idleTimeout;

    /** Optional leak-detection threshold in milliseconds; logs a warning when a held connection exceeds it. */
    public final Optional<Long> leakDetectionThreshold;

    private DataSourceConfig(Builder builder) {
        this.jdbcUrl = Validate.notBlank(builder.jdbcUrl, "jdbcUrl is required");
        this.username = Optional.ofNullable(builder.username);
        this.username.ifPresent(value -> Validate.isTrue(
                !value.isBlank(),
                "username cannot be empty; leave it out when the driver or a plugin supplies the user"));
        this.password = Optional.ofNullable(builder.password);
        this.dataSourceProperties = Collections.unmodifiableMap(new LinkedHashMap<>(builder.dataSourceProperties));
        this.dialect = resolveDialect(this.jdbcUrl);
        JdbcUrlCredentials.requireNoneIn(this.jdbcUrl);
        this.driverClassName = builder.driverClassName;
        // The numeric fields were unchecked while every string beside them was validated, so a
        // typo here was accepted and surfaced somewhere else entirely. maximumPoolSize = 0 is the
        // worst of them: Hikari rejects it, but only when the pool is built, and a deployment that
        // catches startup failures sees a hang on the first query rather than a bad setting.
        Validate.isTrue(builder.maximumPoolSize >= 1, "maximumPoolSize must be at least 1");
        this.maximumPoolSize = builder.maximumPoolSize;
        builder.minimumIdle.ifPresent(value -> {
            Validate.isTrue(value >= 0, "minimumIdle cannot be negative");
            // Hikari silently clamps a minimumIdle above the maximum instead of complaining, which
            // leaves the pool sized differently from the configuration that describes it.
            Validate.isTrue(
                    value <= builder.maximumPoolSize,
                    "minimumIdle cannot exceed maximumPoolSize (%s)",
                    builder.maximumPoolSize);
        });
        this.minimumIdle = builder.minimumIdle;
        builder.idleTimeout.ifPresent(value -> Validate.isTrue(value >= 0, "idleTimeout cannot be negative"));
        this.idleTimeout = builder.idleTimeout;
        // Zero is Hikari's own way of saying "off" and stays valid. Anything between 1 and 1999 is
        // not: Hikari logs "leakDetectionThreshold is less than 2000ms ... disabling it" and turns
        // the feature off, so the operator who asked for leak detection does not get it and only a
        // log line says so. A negative value is disabled just as quietly, without even the line.
        builder.leakDetectionThreshold.ifPresent(value -> Validate.isTrue(
                value == 0 || value >= 2_000,
                "leakDetectionThreshold must be 0 (disabled) or at least 2000 ms; Hikari disables"
                        + " anything smaller"));
        this.leakDetectionThreshold = builder.leakDetectionThreshold;
    }

    /**
     * Resolves the dialect with jOOQ's own {@link JDBCUtils#dialect(String)} rather than scanning
     * the URL for driver names.
     *
     * <p>The previous implementation asked whether the URL {@code contains} "postgresql", then
     * "mysql", then "mariadb". Because that searched the whole string - host, database, query
     * parameters - and checked MySQL first, a perfectly correct MariaDB URL pointing at a host
     * carried over from a migration resolved to {@link SQLDialect#MYSQL}:
     * {@code jdbc:mariadb://mysql-01.prod/app}, or the very common Kubernetes case
     * {@code jdbc:mariadb://mysql.default.svc.cluster.local/db}. Nothing failed - jOOQ simply
     * rendered MySQL-flavoured SQL against MariaDB, which mostly works, except where the two have
     * diverged. This framework leans on exactly those places: {@code AbstractRepository} uses
     * {@code RETURNING} on four write paths, which MariaDB 10.5+ supports and MySQL does not.
     *
     * <p>Using jOOQ's resolver keeps one source of truth (the library that consumes the dialect
     * also decides it), is maintained upstream as new URL forms appear, and already handles shapes
     * a hand-rolled scan gets wrong - multi-host failover lists, {@code jdbc:mysql:aurora://},
     * {@code jdbc:mariadb:sequential://}.
     *
     * @param jdbcUrl the configured JDBC URL.
     * @return the resolved dialect family.
     * @throws IllegalArgumentException if the URL is unrecognised, or names a database Ekbatan does
     *     not support.
     */
    private static SQLDialect resolveDialect(String jdbcUrl) {
        // family() because jOOQ's commercial editions carry versioned dialects (POSTGRES_15 and
        // friends); the family normalises those onto the constants used throughout the framework.
        final var dialect = JDBCUtils.dialect(jdbcUrl).family();
        // Neither message repeats the URL: a JDBC URL can carry the password itself
        // (`?password=`, `user:password@`), and an exception message ends up in logs, tickets and
        // exception trackers.
        return switch (dialect) {
            case POSTGRES, MYSQL, MARIADB -> dialect;
            // Distinguished on purpose: "could not tell" and "told, but unsupported" are
            // different mistakes and want different fixes.
            case DEFAULT ->
                throw new IllegalArgumentException(
                        "Cannot determine the database dialect from the URL. A JDBC URL looks like jdbc:postgresql://host:5432/db.");
            default ->
                throw new IllegalArgumentException("URL resolves to " + dialect
                        + ", which Ekbatan does not support. Supported dialects: PostgreSQL, MySQL, MariaDB.");
        };
    }

    /** Fluent builder for {@link DataSourceConfig}. Obtain via {@link #dataSourceConfig()}. */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {

        private String jdbcUrl;
        private String username;
        private String password;
        private final Map<String, String> dataSourceProperties = new LinkedHashMap<>();
        private Optional<String> driverClassName = Optional.empty();
        private int maximumPoolSize = 10;
        private Optional<Integer> minimumIdle = Optional.empty();
        private Optional<Long> idleTimeout = Optional.empty();
        private Optional<Long> leakDetectionThreshold = Optional.empty();

        private Builder() {}

        /** {@return a fresh builder for {@link DataSourceConfig}} */
        public static Builder dataSourceConfig() {
            return new Builder();
        }

        /**
         * Sets the JDBC URL. Required.
         *
         * @param jdbcUrl the JDBC URL.
         * @return this builder, for chaining.
         */
        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        /**
         * Sets the database username. Leave it out only when the driver or a plugin supplies the
         * user; it cannot be blank.
         *
         * @param username the database username.
         * @return this builder, for chaining.
         */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /**
         * Sets the database password. Leave it out for a login that has none; the empty string is
         * a valid password.
         *
         * @param password the database password.
         * @return this builder, for chaining.
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /**
         * Sets the extra settings handed to the driver, replacing any set before.
         *
         * <p>Every name is kept exactly as given, and every value becomes its text - a driver
         * reads its settings as text, as it would read them from the URL's query string. A nested
         * map or a list is refused: no driver setting holds either, and a nested block is how a
         * dotted name such as {@code ha.enableJMX} looks when it was split by mistake. {@code user}
         * and {@code password} are refused too, in any case - MySQL's and MariaDB's drivers read
         * {@code USER} as {@code user} - since they belong in {@link #username(String)} and
         * {@link #password(String)}.
         *
         * @param dataSourceProperties setting name to value.
         * @return this builder, for chaining.
         */
        public Builder dataSourceProperties(Map<String, ?> dataSourceProperties) {
            Validate.notNull(dataSourceProperties, "dataSourceProperties cannot be null");
            var accepted = new LinkedHashMap<String, String>();
            for (var entry : dataSourceProperties.entrySet()) {
                var name = Validate.notNull(entry.getKey(), "a dataSourceProperties name cannot be null");
                Validate.isTrue(!name.isBlank(), "a dataSourceProperties name cannot be blank");
                var login = "user".equalsIgnoreCase(name) ? "username" : "password";
                Validate.isTrue(
                        !"user".equalsIgnoreCase(name) && !"password".equalsIgnoreCase(name),
                        "'%s' does not belong in dataSourceProperties, where it would silently win over %s;"
                                + " set %s instead",
                        name,
                        login,
                        login);
                accepted.put(name, text(name, entry.getValue()));
            }
            this.dataSourceProperties.clear();
            this.dataSourceProperties.putAll(accepted);
            return this;
        }

        private static String text(String name, Object value) {
            return switch (value) {
                case null -> throw new IllegalArgumentException("dataSourceProperties '" + name + "' has no value");
                case Map<?, ?> nested ->
                    throw new IllegalArgumentException("dataSourceProperties '" + name
                            + "' holds a nested block, which no driver setting can; write each setting as"
                            + " one name, such as ha.enableJMX");
                case Iterable<?> list ->
                    throw new IllegalArgumentException(
                            "dataSourceProperties '" + name + "' must be a single value, not a list");
                default -> String.valueOf(value);
            };
        }

        /**
         * Names the JDBC driver class, instead of finding the driver from the URL. A blank name
         * means none, as when it is left out - it is what an unset environment variable behind a
         * placeholder gives, and finding the driver from the URL is the safe default. (A blank
         * username, by contrast, is refused: its fallback is the operating-system user.)
         *
         * @param driverClassName the JDBC driver class name.
         * @return this builder, for chaining.
         */
        public Builder driverClassName(String driverClassName) {
            Validate.notNull(driverClassName, "driverClassName cannot be null");
            this.driverClassName = driverClassName.isBlank() ? Optional.empty() : Optional.of(driverClassName.strip());
            return this;
        }

        /**
         * Sets the Hikari maximum pool size (default 10).
         *
         * @param maximumPoolSize the maximum pool size.
         * @return this builder, for chaining.
         */
        public Builder maximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
            return this;
        }

        /**
         * Sets the Hikari minimum idle connection count.
         *
         * @param minimumIdle the minimum idle count.
         * @return this builder, for chaining.
         */
        public Builder minimumIdle(int minimumIdle) {
            this.minimumIdle = Optional.of(minimumIdle);
            return this;
        }

        /**
         * Sets the Hikari idle timeout in milliseconds.
         *
         * @param idleTimeout the idle timeout in milliseconds.
         * @return this builder, for chaining.
         */
        public Builder idleTimeout(long idleTimeout) {
            this.idleTimeout = Optional.of(idleTimeout);
            return this;
        }

        /**
         * Sets the Hikari leak-detection threshold in milliseconds.
         *
         * <p>Pass {@code 0} to switch leak detection off - Hikari's own way of disabling it, and
         * the only accepted way here. Any other value must be at least {@code 2000}: Hikari
         * disables anything smaller and says so only in a log line, which reads as "detection is
         * on" to everyone who does not see it.
         *
         * @param leakDetectionThreshold the threshold in milliseconds; {@code 0} to disable, or
         *     {@code >= 2000}.
         * @return this builder, for chaining.
         */
        public Builder leakDetectionThreshold(long leakDetectionThreshold) {
            this.leakDetectionThreshold = Optional.of(leakDetectionThreshold);
            return this;
        }

        /** {@return a configured {@link DataSourceConfig}; throws if a field is invalid} */
        public DataSourceConfig build() {
            return new DataSourceConfig(this);
        }
    }
}
