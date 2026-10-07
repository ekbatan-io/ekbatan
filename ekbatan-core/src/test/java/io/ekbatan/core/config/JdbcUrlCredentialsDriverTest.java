package io.ekbatan.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.postgresql.Driver;

/**
 * The URL check against the drivers' own URL readers - MySQL's, MariaDB's and pgjdbc's - over
 * URLs built from every host form and setting position they read: whatever login or secret a
 * driver reads, the check refuses; and a URL built only from harmless settings, or from empty
 * ones, is accepted, whatever its host and database are named.
 */
// Reads the drivers' own URL readers by reflection, which a native image blocks unless every method
// is registered. What it proves - the check agrees with the drivers - is the same in a native
// image, where JdbcUrlCredentialsTest runs the check itself.
@DisabledInNativeImage
class JdbcUrlCredentialsDriverTest {

    private static final String SECRET = "Zq7v";

    /** Setting names a driver reads a login or a secret from, in the spellings it accepts. */
    private static final List<String> REFUSED = List.of("user", "password", "PASSWORD", "pass%77ord", "password1");

    /** Harmless settings, and names a host or a database may have. */
    private static final List<String> HARMLESS = List.of("sslMode", "connectTimeout", "ha.enableJMX");

    private static final List<String> NAMES = List.of("h1", "password", "user");

    private static boolean refused(String url) {
        try {
            JdbcUrlCredentials.requireNoneIn(url);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /** MySQL's URLs: plain hosts, both host forms, a user before an '@', host lists, the query. */
    private static List<String> mysqlUrls(String key, String value) {
        var urls = new ArrayList<String>();
        for (var host : NAMES) {
            for (var other : NAMES) {
                var setting = key + "=" + value;
                urls.add("jdbc:mysql://(host=" + host + ",port=3306," + setting + ")/" + other);
                urls.add("jdbc:mysql://( host = " + host + " , " + key + " = " + value + " ),(host=" + other + ")/db");
                urls.add("jdbc:mysql://(host=" + host + "),(host=" + other + "," + setting + ")/db");
                urls.add("jdbc:mysql://address=(host=" + host + ")(port=3306)(" + setting + ")/" + other);
                urls.add("jdbc:mysql:loadbalance://address=(host=" + host + ")(" + setting + "),address=(host=" + other
                        + ")/db");
                urls.add("jdbc:mysql://" + host + ":3306," + other + ":3307/db?" + setting);
                urls.add("jdbc:mysql://" + host + "/" + other + "?sslMode=REQUIRED&" + setting);
            }
        }
        return urls;
    }

    /** What MySQL's own reader takes from the URL as a login or a secret, non-empty. */
    @SuppressWarnings("unchecked")
    private static List<String> mysqlReads(String url) throws Exception {
        var connectionUrl = Class.forName("com.mysql.cj.conf.ConnectionUrl");
        var parsed = connectionUrl
                .getMethod("getConnectionUrlInstance", String.class, Properties.class)
                .invoke(null, url, new Properties());
        var reads = new ArrayList<String>();
        for (var host : (List<?>) connectionUrl.getMethod("getHostsList").invoke(parsed)) {
            var user = (String) host.getClass().getMethod("getUser").invoke(host);
            var password = (String) host.getClass().getMethod("getPassword").invoke(host);
            if (user != null && !user.isBlank()) {
                reads.add("user");
            }
            if (password != null && !password.isBlank()) {
                reads.add("password");
            }
            var properties = (Map<String, String>)
                    host.getClass().getMethod("getHostProperties").invoke(host);
            properties.forEach((name, value) -> {
                if (value != null
                        && !value.isBlank()
                        && JdbcUrlCredentials.kindOf(name).isPresent()) {
                    reads.add(name);
                }
            });
        }
        return reads;
    }

    @Test
    void every_login_or_secret_mysql_reads_is_refused() throws Exception {
        // GIVEN / WHEN
        var missed = new ArrayList<String>();
        var count = 0;
        for (var key : REFUSED) {
            for (var url : mysqlUrls(key, SECRET)) {
                count++;
                if (!mysqlReads(url).isEmpty() && !refused(url)) {
                    missed.add(url);
                }
            }
        }

        // THEN
        assertThat(count).isGreaterThan(300);
        assertThat(missed).isEmpty();
    }

    @Test
    void a_mysql_url_with_only_harmless_or_empty_settings_is_accepted() throws Exception {
        // GIVEN - harmless settings with values, and every refused one with none
        var urls = new ArrayList<String>();
        for (var key : HARMLESS) {
            urls.addAll(mysqlUrls(key, SECRET));
        }
        for (var key : REFUSED) {
            urls.addAll(mysqlUrls(key, ""));
        }

        // WHEN
        var wronglyRefused = new ArrayList<String>();
        for (var url : urls) {
            // MySQL agrees: nothing in it is a login or a secret
            assertThat(mysqlReads(url)).as(url).isEmpty();
            if (refused(url)) {
                wronglyRefused.add(url);
            }
        }

        // THEN - hosts and databases named password or user included
        assertThat(urls).hasSizeGreaterThan(500);
        assertThat(wronglyRefused).isEmpty();
    }

    /** What MariaDB's own reader takes from the URL as a login or a secret, non-empty. */
    private static List<String> mariadbReads(String url) throws Exception {
        var configuration = Class.forName("org.mariadb.jdbc.Configuration");
        var parsed =
                configuration.getMethod("parse", String.class, Properties.class).invoke(null, url, new Properties());
        var reads = new ArrayList<String>();
        for (var getter : List.of("user", "password", "keyStorePassword", "trustStorePassword", "keyPassword")) {
            var value = configuration.getMethod(getter).invoke(parsed);
            if (value != null && !value.toString().isBlank()) {
                reads.add(getter);
            }
        }
        var nonMapped = (Properties) configuration.getMethod("nonMappedOptions").invoke(parsed);
        for (var name : nonMapped.stringPropertyNames()) {
            if (!nonMapped.getProperty(name).isBlank()
                    && JdbcUrlCredentials.kindOf(name).isPresent()) {
                reads.add(name);
            }
        }
        return reads;
    }

    @Test
    void mariadb_and_the_check_agree_on_every_query_setting() throws Exception {
        // GIVEN - MariaDB reads names in any case, and plugin settings outside its option list;
        // the harmless settings here take any text, since MariaDB checks some values as it reads
        var keys = List.of(
                "user",
                "USER",
                "password",
                "keyStorePassword",
                "trustcertificatekeystorepassword",
                "keyPassword",
                "secretKey",
                "password2",
                "poolName",
                "connectionAttributes");
        var disagree = new ArrayList<String>();
        for (var key : keys) {
            for (var value : List.of(SECRET, "")) {
                for (var url : List.of(
                        "jdbc:mariadb://password:3306/user?" + key + "=" + value,
                        "jdbc:mariadb:sequential://h1,h2/db?sslMode=disable&" + key + "=" + value,
                        "jdbc:mariadb://address=(host=user)(port=3306)(type=primary)/password?" + key + "=" + value)) {
                    // WHEN
                    var reads = !mariadbReads(url).isEmpty();

                    // THEN - refused exactly when MariaDB reads a login or a secret from it
                    if (reads != refused(url)) {
                        disagree.add(url);
                    }
                }
            }
        }
        assertThat(disagree).isEmpty();
    }

    @Test
    void pgjdbc_and_the_check_agree_on_every_query_setting() {
        // GIVEN - pgjdbc reads names exactly as written; and no .pgpass of this machine, which
        // pgjdbc would add a password from
        var pgpass = System.setProperty("org.postgresql.pgpassfile", "/nonexistent/ekbatan-test-pgpass");
        try {
            assertPgjdbcAgrees();
        } finally {
            if (pgpass == null) {
                System.clearProperty("org.postgresql.pgpassfile");
            } else {
                System.setProperty("org.postgresql.pgpassfile", pgpass);
            }
        }
    }

    private static void assertPgjdbcAgrees() {
        var disagree = new ArrayList<String>();
        for (var key : List.of("user", "password", "sslpassword", "sslmode", "ApplicationName", "options")) {
            for (var value : List.of(SECRET, "", "-c%20search_path%3Dapp")) {
                for (var url : List.of(
                        "jdbc:postgresql://password:5432/user?" + key + "=" + value,
                        "jdbc:postgresql://h1:5432,h2:5433/db?ssl=true&" + key + "=" + value,
                        "jdbc:postgresql:password?" + key + "=" + value)) {
                    // WHEN
                    var parsed = Driver.parseURL(url, null);
                    var reads = parsed != null
                            && parsed.stringPropertyNames().stream()
                                    .anyMatch(name -> !parsed.getProperty(name).isBlank()
                                            && JdbcUrlCredentials.kindOf(name).isPresent());

                    // THEN
                    if (reads != refused(url)) {
                        disagree.add(url);
                    }
                }
            }
        }
        assertThat(disagree).isEmpty();
    }
}
