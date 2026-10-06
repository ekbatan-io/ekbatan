package io.ekbatan.core.config;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Dialect resolution from the JDBC URL.
 *
 * <p>The cases that matter are the ones where the driver named in the scheme disagrees with a name
 * appearing elsewhere in the URL. A team migrating MySQL to MariaDB keeps its hostnames and its
 * Kubernetes Service names, so {@code jdbc:mariadb://mysql-01/app} is ordinary rather than
 * contrived - and it used to resolve to MySQL, after which jOOQ rendered MySQL-flavoured SQL
 * against MariaDB without anything failing.
 */
class DataSourceConfigDialectTest {

    private static SQLDialect dialectOf(String jdbcUrl) {
        return dataSourceConfig().jdbcUrl(jdbcUrl).username("u").password("p").build().dialect;
    }

    // Semicolon-delimited: several of these URLs contain commas (multi-host failover lists), which
    // is exactly the shape a strict URI parser also rejects - hence jOOQ's resolver rather than one.
    @ParameterizedTest
    @CsvSource(
            delimiter = ';',
            value = {
                // plain forms
                "jdbc:postgresql://localhost:5432/db; POSTGRES",
                "jdbc:mysql://localhost:3306/db; MYSQL",
                "jdbc:mariadb://localhost:3306/db; MARIADB",
                // the scheme must win over any other driver name elsewhere in the URL
                "jdbc:mariadb://mysql-01.prod.internal/app; MARIADB",
                "jdbc:mariadb://mysql.default.svc.cluster.local/db; MARIADB",
                "jdbc:mysql://mariadb-host/db; MYSQL",
                "jdbc:postgresql://mysql-proxy/db; POSTGRES",
                "jdbc:mariadb://host/mysql_compat; MARIADB",
                // shapes a naive scan or a strict URI parser would trip on
                "jdbc:mysql://h1:3306,h2:3306/db; MYSQL",
                "jdbc:mariadb:sequential://h1,h2/db; MARIADB",
                "jdbc:mysql:aurora://cluster.rds.amazonaws.com/db; MYSQL",
                "jdbc:postgresql://h/db?ssl=true&ApplicationName=x; POSTGRES",
            })
    void resolves_the_dialect_from_the_scheme_not_from_the_rest_of_the_url(String jdbcUrl, SQLDialect expected) {
        assertThat(dialectOf(jdbcUrl.trim())).isEqualTo(expected);
    }

    @Test
    void rejects_a_url_it_cannot_recognise() {
        assertThatThrownBy(() -> dialectOf("not-a-jdbc-url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot determine the database dialect");
    }

    @Test
    void rejects_a_recognised_but_unsupported_database_with_a_distinct_message() {
        // "could not tell" and "told, but unsupported" are different mistakes; H2 is recognisable,
        // so saying "cannot determine" would send the reader looking in the wrong place.
        assertThatThrownBy(() -> dialectOf("jdbc:h2:mem:test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support")
                .hasMessageContaining("PostgreSQL, MySQL, MariaDB");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = ';',
            value = {
                // unrecognisable, with the password in it
                "not-a-jdbc-url?user=app&password=Zq7-s3cret",
                "app:Zq7-s3cret@db1/orders",
                // recognised but unsupported, with the password in the query and inline
                "jdbc:h2:mem:test;USER=app;PASSWORD=Zq7-s3cret",
                "jdbc:oracle:thin:app/Zq7-s3cret@db1:1521/orders",
                "jdbc:sqlserver://db1;user=app;password=Zq7-s3cret",
            })
    void never_repeats_a_refused_url_since_it_can_carry_the_password(String jdbcUrl) {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> dialectOf(jdbcUrl.trim()))
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .doesNotContain("Zq7-s3cret");
    }

    @Test
    void still_names_the_scheme_of_a_refused_url() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> dialectOf("jdbc:h2:mem:test;PASSWORD=Zq7-s3cret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("H2")
                .hasMessageNotContaining("Zq7-s3cret");
    }
}
