package io.ekbatan.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import io.ekbatan.core.config.DriverSettingsLedger.Row;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The URL check against the ledger: every setting of every driver whose URL Ekbatan accepts is
 * refused when it holds a user name or a secret, and accepted when it does not - in every form a
 * driver reads a URL's settings in.
 */
class JdbcUrlCredentialsTest {

    private static final String VALUE = "Zq7-v4lue";

    /**
     * A URL carrying the setting, in each form an accepted driver reads: the query, a
     * host list MySQL's own reader skips, MySQL's two host forms, the {@code ;} form of DataDirect's
     * and CData's drivers, CData's first setting right after the scheme, and wrapper drivers.
     */
    private static Map<String, String> urlsCarrying(String name) {
        var urls = new LinkedHashMap<String, String>();
        urls.put("query", "jdbc:postgresql://db1:5432/orders?" + name + "=" + VALUE);
        urls.put(
                "query, later",
                "jdbc:postgresql://db1:5432/orders?ssl=true&" + name + "=" + VALUE + "&ApplicationName=x");
        urls.put("two hosts", "jdbc:mysql://h1:3306,h2:3306/orders?" + name + "=" + VALUE);
        urls.put("host form", "jdbc:mysql://(host=db1,port=3306," + name + "=" + VALUE + ")/orders");
        urls.put("address form", "jdbc:mysql://address=(host=db1)(port=3306)(" + name + "=" + VALUE + ")/orders");
        urls.put("semicolons", "jdbc:datadirect:postgresql://db1:5432;DatabaseName=orders;" + name + "=" + VALUE);
        urls.put("first after scheme", "jdbc:postgresql:" + name + "=" + VALUE + ";Server=db1;Port=5432;");
        urls.put("spaces", "jdbc:postgresql:Server=db1; " + name + " = " + VALUE + ";");
        urls.put(
                "aws wrapper",
                "jdbc:aws-wrapper:postgresql://db1:5432/orders?wrapperPlugins=iam&" + name + "=" + VALUE);
        urls.put("testcontainers", "jdbc:tc:postgresql:17:///orders?TC_REUSABLE=true&" + name + "=" + VALUE);
        return urls;
    }

    /**
     * Names one driver gives another meaning than every other driver does. The check gives a name
     * one meaning, so it refuses these too, with the advice that fits every other driver:
     * jdbc-sshj's {@code password} is its SSH tunnel's, where every other driver's is the database
     * login's.
     */
    private static final Set<String> OTHER_MEANING = Set.of("jdbc-sshj: password");

    private static boolean otherMeaning(Row row, String name) {
        return OTHER_MEANING.contains(row.driver() + ": " + name);
    }

    private static Stream<Arguments> refusedNames() {
        return DriverSettingsLedger.settings().filter(Row::refused).flatMap(row -> row.examples().stream()
                .map(name -> Arguments.of(
                        name,
                        otherMeaning(row, name)
                                ? JdbcUrlCredentials.kindOf(name).orElseThrow()
                                : row.checkKind(),
                        row.driver())));
    }

    @ParameterizedTest(name = "{2}: {0}")
    @MethodSource("refusedNames")
    void every_setting_that_holds_a_user_name_or_a_secret_is_refused_in_every_form(
            String name, JdbcUrlCredentials.Kind kind, String driver) {
        // GIVEN - the name as written, in capitals, in small letters, and with one letter escaped
        var spellings = List.of(
                name,
                name.toUpperCase(Locale.ROOT),
                name.toLowerCase(Locale.ROOT),
                "%" + Integer.toHexString(name.charAt(0)).toUpperCase(Locale.ROOT) + name.substring(1));

        // WHEN - each refused, pointing where the value goes, never repeating it or the URL
        var wrong = new ArrayList<String>();
        for (var spelling : spellings) {
            for (var url : urlsCarrying(spelling).entrySet()) {
                var shown = spelling.startsWith("%") ? name : spelling;
                var problem = problemWithTheRefusal(url.getValue(), shown, kind);
                if (problem != null) {
                    wrong.add(url.getKey() + ", " + spelling + ": " + problem);
                }
            }
        }

        // THEN
        assertThat(wrong).isEmpty();
    }

    /** What is wrong with how a URL carrying the setting is refused, or {@code null} if nothing is. */
    private static String problemWithTheRefusal(String url, String shown, JdbcUrlCredentials.Kind kind) {
        try {
            JdbcUrlCredentials.requireNoneIn(url);
            return "accepted";
        } catch (IllegalArgumentException e) {
            var message = e.getMessage();
            if (!message.contains("'" + shown + "' setting")) {
                return "does not name the setting: " + message;
            }
            if (!message.contains(insteadOf(kind))) {
                return "does not say where it goes: " + message;
            }
            if (message.contains(VALUE)) {
                return "repeats the value";
            }
            if (message.contains("db1")) {
                return "repeats the URL";
            }
            return null;
        }
    }

    private static String insteadOf(JdbcUrlCredentials.Kind kind) {
        return switch (kind) {
            case LOGIN_USER -> "Give it to username(...) instead";
            case LOGIN_PASSWORD -> "Give it to password(...) instead";
            case USER_NAME, SECRET -> "Put it under dataSourceProperties (data-source-properties in YAML) instead";
        };
    }

    @Test
    void every_other_setting_of_every_driver_is_accepted_in_every_form() {
        // GIVEN - every setting the ledger says holds neither a user name nor a secret
        var names = DriverSettingsLedger.settings()
                .filter(row -> !row.refused())
                .flatMap(row -> row.examples().stream())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(names).hasSizeGreaterThan(700);

        // WHEN
        var refused = new ArrayList<String>();
        for (var name : names) {
            for (var url : urlsCarrying(name).entrySet()) {
                try {
                    JdbcUrlCredentials.requireNoneIn(url.getValue());
                } catch (IllegalArgumentException e) {
                    refused.add(name + " (" + url.getKey() + ")");
                }
            }
        }

        // THEN
        assertThat(refused).isEmpty();
    }

    @Test
    void no_name_holds_a_secret_for_one_driver_and_nothing_for_another() {
        // GIVEN - names compared as the check compares them, ignoring case
        Function<Row, Stream<String>> lowerNames =
                row -> row.examples().stream().map(name -> name.toLowerCase(Locale.ROOT));
        var refused = DriverSettingsLedger.settings()
                .filter(Row::refused)
                .flatMap(lowerNames)
                .collect(Collectors.toSet());

        // WHEN
        var both = DriverSettingsLedger.settings()
                .filter(row -> !row.refused())
                .filter(row -> lowerNames.apply(row).anyMatch(refused::contains))
                .map(row -> row.driver() + ": " + row.setting())
                .toList();

        // THEN - otherwise the ledger would have to say which one a URL means
        assertThat(both).isEmpty();
    }

    @Test
    void the_check_refuses_exactly_what_the_ledger_marks() {
        // GIVEN
        var ledger = DriverSettingsLedger.settings()
                .filter(Row::refused)
                .flatMap(row -> row.examples().stream().map(name -> Map.entry(name.toLowerCase(Locale.ROOT), row)))
                .toList();

        // WHEN / THEN - every marked name is refused as what the ledger says it holds
        var wrong = new ArrayList<String>();
        for (var entry : ledger) {
            var name = entry.getValue().driver() + ": " + entry.getKey();
            var kind = JdbcUrlCredentials.kindOf(entry.getKey());
            if (kind.isEmpty()) {
                wrong.add(name + " is not refused");
            } else if (!otherMeaning(entry.getValue(), entry.getKey())
                    && kind.get() != entry.getValue().checkKind()) {
                wrong.add(name + " is refused as " + kind.get() + ", the ledger says "
                        + entry.getValue().checkKind());
            }
        }

        // AND - every name the check refuses is in the ledger, with the same kind
        var marked = ledger.stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().checkKind(), (a, b) -> a));
        for (var setting : JdbcUrlCredentials.SETTINGS.entrySet()) {
            if (marked.get(setting.getKey()) != setting.getValue()) {
                wrong.add(setting.getKey() + " is refused as " + setting.getValue() + ", the ledger says "
                        + marked.get(setting.getKey()));
            }
        }
        assertThat(wrong).isEmpty();

        // AND - so is every prefix
        assertThat(JdbcUrlCredentials.PASSED_ON_PREFIXES)
                .containsExactlyInAnyOrderElementsOf(DriverSettingsLedger.prefixes());
    }

    private static Stream<Arguments> prefixedNames() {
        return JdbcUrlCredentials.PASSED_ON_PREFIXES.stream().flatMap(prefix -> DriverSettingsLedger.settings()
                .filter(Row::refused)
                .flatMap(row -> row.examples().stream())
                .distinct()
                .map(name -> Arguments.of(prefix, name)));
    }

    @ParameterizedTest(name = "{0}{1}")
    @MethodSource("prefixedNames")
    void a_user_name_or_secret_passed_on_by_a_prefix_is_refused(String prefix, String name) {
        // GIVEN - the AWS wrapper hands it, prefix removed, to its monitoring connections
        var url = "jdbc:aws-wrapper:postgresql://db1:5432/orders?wrapperPlugins=efm2&" + prefix + name + "=" + VALUE;

        // WHEN / THEN - it is not this configuration's login, so it goes to data-source-properties
        assertThatThrownBy(() -> JdbcUrlCredentials.requireNoneIn(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'" + prefix + name + "' setting")
                .hasMessageContaining("data-source-properties")
                .hasMessageNotContaining(VALUE);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "monitoring-connectTimeout=10000",
                "topology-monitoring-socketTimeout=5000",
                "blue-green-monitoring-connectTimeout=3000",
                "limitless-router-monitor-loginTimeout=3000",
                "frt-connectTimeout=10",
                "cp-maximumPoolSize=10"
            })
    void a_prefix_on_an_ordinary_setting_is_accepted(String setting) {
        // GIVEN
        var url = "jdbc:aws-wrapper:postgresql://db1:5432/orders?wrapperPlugins=efm2&" + setting;

        // WHEN / THEN
        assertThatCode(() -> JdbcUrlCredentials.requireNoneIn(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:mysql://app:" + VALUE + "@db1:3306/orders",
                "jdbc:mysql://app:" + VALUE + "@h1:3306,app:" + VALUE + "@h2:3306/orders",
                // a user on one host, and a password on the other
                "jdbc:mysql://app@h1:3306,app:" + VALUE + "@h2:3306/orders",
                "jdbc:mariadb://app:" + VALUE + "@db1:3306/orders",
                "jdbc:postgresql://app:" + VALUE + "@db1:5432/orders",
                "jdbc:aws-wrapper:mysql://app:" + VALUE + "@db1:3306/orders",
                "jdbc:p6spy:mysql://app:" + VALUE + "@db1:3306/orders",
                "jdbc:datadirect:mysql://app:" + VALUE + "@db1:3306;DatabaseName=orders"
            })
    void a_user_and_password_before_an_at_sign_are_refused(String url) {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> JdbcUrlCredentials.requireNoneIn(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries a user name and a password before an '@'")
                .hasMessageContaining("username(...) and password(...)")
                .hasMessageNotContaining(VALUE)
                .hasMessageNotContaining("app");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:mysql://app@db1:3306/orders",
                // the colon before the comma is a port, not a password
                "jdbc:mysql://h1:3306,app@h2:3306/orders",
                "jdbc:mysql://app@db1"
            })
    void a_user_before_an_at_sign_is_refused(String url) {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> JdbcUrlCredentials.requireNoneIn(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries a user name before an '@'")
                .hasMessageContaining("Give it to username(...) instead")
                .hasMessageNotContaining("app");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:mysql://:" + VALUE + "@db1:3306/orders",
                // the colon before the comma is a port
                "jdbc:mysql://h1:3306,:" + VALUE + "@h2:3306/orders"
            })
    void a_password_without_a_user_before_an_at_sign_is_refused(String url) {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> JdbcUrlCredentials.requireNoneIn(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries a password before an '@'")
                .hasMessageContaining("Give it to password(...) instead")
                .hasMessageNotContaining("carries a user name")
                .hasMessageNotContaining(VALUE);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // a bare '@': MySQL's driver reads neither a user nor a password from it
                "jdbc:mysql://@db1:3306/orders",
                // an empty password carries nothing
                "jdbc:mysql://:@db1:3306/orders",
                // an '@' in a value, in the database name, or inside MySQL's host form
                "jdbc:postgresql://db1:5432/orders?ApplicationName=team@orders",
                "jdbc:postgresql://db1:5432/team@orders",
                "jdbc:mysql://address=(protocol=unix)(path=/run/team@mysql.sock)/orders",
                // a '//' that only starts inside a value
                "jdbc:postgresql:orders?sslrootcert=file://team@host/root.crt",
                "jdbc:postgresql://[::1]:5432/orders"
            })
    void an_at_sign_outside_the_host_part_is_not_a_user(String url) {
        // GIVEN / WHEN / THEN
        assertThatCode(() -> JdbcUrlCredentials.requireNoneIn(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // client certificate login, from the connecting page
                "jdbc:postgresql://orders-db.internal:5432/orders?sslmode=verify-full"
                        + "&sslcert=/run/secrets/db/client.crt&sslkey=/run/secrets/db/client.pk8",
                // values holding '=', ',', ';', ':', spaces and escapes
                "jdbc:postgresql://db1/orders?options=-c%20statement_timeout=30000",
                "jdbc:postgresql://db1/orders?options=-c search_path=app,public -c lock_timeout=5s",
                "jdbc:mysql://db1/orders?sessionVariables=sql_mode='STRICT_ALL_TABLES',time_zone='+00:00'"
                        + "&connectionAttributes=team:orders,env:prod",
                "jdbc:mariadb://db1/orders?sessionVariables=wait_timeout=10;innodb_lock_wait_timeout=5",
                "jdbc:postgresql://db1/orders?sslfactory=org.postgresql.ssl.SingleCertValidatingFactory"
                        + "&sslfactoryarg=classpath:certs/server.crt",
                // look-alike names that hold no secret
                "jdbc:mysql://db1/orders?passwordCharacterEncoding=UTF-8&disconnectOnExpiredPasswords=false"
                        + "&allowPublicKeyRetrieval=true&useConfigs=maxPerformance",
                // AWS: IAM, Secrets Manager and federated logins without their user names and secrets
                "jdbc:aws-wrapper:postgresql://db.cluster-xyz.us-east-1.rds.amazonaws.com:5432/orders"
                        + "?wrapperPlugins=iam,failover&iamRegion=us-east-1",
                "jdbc:aws-wrapper:mysql://db1:3306/orders?wrapperPlugins=awsSecretsManager"
                        + "&secretsManagerSecretId=arn:aws:secretsmanager:us-east-1:123456789012:secret:db"
                        + "&secretsManagerRegion=us-east-1",
                "jdbc:aws-wrapper:postgresql://db1:5432/orders?wrapperPlugins=federatedAuth&idpEndpoint=ec2.example.com"
                        + "&iamRoleArn=arn:aws:iam::123456789012:role/r&iamIdpArn=arn:aws:iam::123456789012:saml-provider/p",
                "jdbc:mariadb://db1/orders?credentialType=AWS-IAM&region=us-east-1&accessKeyId=AKIAIOSFODNN7EXAMPLE",
                // Azure managed identity, GCP Cloud SQL IAM, Kerberos
                "jdbc:postgresql://orders.postgres.database.azure.com:5432/orders?sslmode=require"
                        + "&authenticationPluginClassName=com.azure.identity.extensions.jdbc.postgresql"
                        + ".AzurePostgresqlAuthenticationPlugin&azure.clientId=00000000-0000-0000-0000-000000000000",
                "jdbc:postgresql:///orders?cloudSqlInstance=project:region:instance"
                        + "&socketFactory=com.google.cloud.sql.postgres.SocketFactory&enableIamAuth=true"
                        + "&ipTypes=PRIVATE,PUBLIC&cloudSqlTargetPrincipal=sa@project.iam.gserviceaccount.com",
                "jdbc:postgresql://db1/orders?gsslib=gssapi&kerberosServerName=postgres&jaasApplicationName=pgjdbc",
                // host lists and host forms without a login in them
                "jdbc:mysql://address=(host=db1)(port=3306)(type=primary),address=(host=db2)(port=3306)(type=replica)/orders",
                "jdbc:mysql://(host=db1,port=3306,sslMode=REQUIRED),(host=db2,port=3306)/orders",
                "jdbc:mariadb:replication://address=(host=db1)(port=3306)(type=primary),address=(host=db2)/orders",
                // wrapper drivers
                "jdbc:tc:postgresql:17:///orders?TC_INITSCRIPT=file:src/test/resources/init.sql&TC_TMPFS=/testtmpfs:rw",
                "jdbc:otel:postgresql://db1:5432/orders",
                "jdbc:p6spy:mysql://db1:3306/orders",
                "jdbc:log4jdbc:mariadb://db1:3306/orders",
                "jdbc-secretsmanager:postgresql://db1:5432/orders",
                "jdbc:pgsql://db1:5432/orders?ssl.mode=verify-full&ssl.key.file=/run/secrets/db/client.key",
                // databases named like a setting
                "jdbc:postgresql:password",
                "jdbc:postgresql://db1/user",
                "jdbc:mysql://db1/password?useSSL=true"
            })
    void a_url_without_a_user_name_or_a_secret_is_accepted(String url) {
        // GIVEN / WHEN / THEN
        assertThatCode(() -> JdbcUrlCredentials.requireNoneIn(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SSH Password", "OAuth Client Secret", "Firewall Password", "Proxy User", "SSH User"})
    void a_name_written_with_spaces_inside_is_refused(String name) {
        // GIVEN - CData's documentation writes its setting names with spaces
        var url = "jdbc:postgresql:Server=db1;" + name + "=" + VALUE + ";";

        // WHEN / THEN - named as written
        assertThatThrownBy(() -> JdbcUrlCredentials.requireNoneIn(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'" + name + "' setting")
                .hasMessageNotContaining(VALUE);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // a whole connection string held by one setting: its names are read like the URL's own
                "jdbc:mysql:Server=db1;CacheConnection='jdbc:mysql:Server=cache;User=root;Password=" + VALUE + "';",
                "jdbc:postgresql:Server=db1;Other=\"SSHPassword=" + VALUE + ";UseSSH=true\";",
                "jdbc:datadirect:postgresql://db1:5432;AlternateServers=(db2:5432;Password=" + VALUE + ")"
            })
    void a_secret_inside_another_settings_value_is_refused(String url) {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> JdbcUrlCredentials.requireNoneIn(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(VALUE);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // hosts and databases named like a setting
                "jdbc:postgresql://password:5432/user",
                "jdbc:mysql://user,password/secret",
                "jdbc:mysql://user:3306,password:3307/orders",
                "jdbc:mysql://(host=password,port=3306),(host=user)/orders",
                "jdbc:mysql://address=(host=password)(port=3306)/user",
                "jdbc:datadirect:postgresql://password:5432;DatabaseName=user",
                "jdbc:postgresql:password",
                "jdbc:postgresql://db1/password?ApplicationName=user",
                // values holding a watched word after a character that separates settings elsewhere
                "jdbc:postgresql://db1/orders?ApplicationName=team(user=batch)",
                "jdbc:postgresql://db1/orders?ApplicationName=svc,user=batch",
                "jdbc:postgresql://db1/orders?ApplicationName=a;password=x",
                "jdbc:postgresql://db1/orders?ApplicationName=what?user=x",
                "jdbc:postgresql://db1/orders?ApplicationName='password=x'",
                "jdbc:postgresql://db1/orders?options=-c user=batch",
                "jdbc:tracing:postgresql://db1/orders?ignoreForTracing=\"user=?\"",
                "jdbc:mysql://db1/orders?sessionVariables=x=1,password=2",
                "jdbc:mysql://db1/orders?connectionAttributes=env:prod,user:alice,owner:password=none",
                "jdbc:datadirect:postgresql://db1:5432;InitializationString=(SET application_name='user=batch')"
            })
    void a_watched_word_outside_a_setting_is_not_a_setting(String url) {
        // GIVEN / WHEN / THEN - only where a driver reads a setting's name does the name count
        assertThatCode(() -> JdbcUrlCredentials.requireNoneIn(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:postgresql://db1/orders?password=",
                "jdbc:postgresql://db1/orders?user=&password=",
                "jdbc:postgresql://db1/orders?password",
                "jdbc:mysql://db1/orders?ssl=true&user",
                "jdbc:mysql://(host=db1,password=)/orders",
                "jdbc:mysql://address=(host=db1)(password= )/orders",
                "jdbc:datadirect:postgresql://db1:5432;Password=;User="
            })
    void a_setting_with_no_value_carries_nothing_and_is_accepted(String url) {
        // GIVEN / WHEN / THEN
        assertThatCode(() -> JdbcUrlCredentials.requireNoneIn(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // an SSH tunnel's real URL, after ';;;'
                "jdbc:sshj://bastion?remote=db1:5432;;;jdbc:postgresql://{{host}}:{{port}}/orders?password=" + VALUE,
                "jdbc:sshj://bastion?remote=db1:3306;;;jdbc:mysql://app:" + VALUE + "@{{host}}:{{port}}/orders",
                // a proxy's real URL after url=, and RmiJdbc's after its host
                "jdbc:lisasim:driver=com.mysql.cj.jdbc.Driver;state=watch;url=jdbc:mysql://db1/orders?password="
                        + VALUE,
                "jdbc:rmi://rmi-host/jdbc:mysql://app:" + VALUE + "@db1/orders",
                // CData's cache, a whole URL in one setting
                "jdbc:mysql:Server=db1;CacheConnection='jdbc:mysql:Server=cache;User=root;Password=" + VALUE + "';",
                // Druid's settings before the real URL
                "jdbc:wrap-jdbc:filters=stat:name=orders:jdbc:mysql://db1/orders?password=" + VALUE
            })
    void a_url_inside_the_url_is_read_the_same_way(String url) {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> JdbcUrlCredentials.requireNoneIn(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(VALUE);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // MySQL's host values may hold ';' in both forms, and ',' in its address form
                "jdbc:mysql://address=(host=db1)(Server=a;password=g)/orders",
                "jdbc:mysql://(host=db1,options=a;password=b)/orders",
                "jdbc:mysql://address=(host=db1)(sslMode=a,clientuser=b)/orders",
                // the ';' forms have no query: a '?' there is part of a value
                "jdbc:postgresql:Server=db1;connectTimeout=what?sshuser=h;",
                "jdbc:datadirect:postgresql://db1:5432;ApplicationName=what?user=x"
            })
    void a_separator_inside_a_value_stays_in_the_value(String url) {
        // GIVEN / WHEN / THEN - each form split only where its driver splits it
        assertThatCode(() -> JdbcUrlCredentials.requireNoneIn(url)).doesNotThrowAnyException();
    }

    @Test
    void a_first_cdata_setting_whose_value_holds_two_slashes_is_still_read() {
        // GIVEN - no host part here: a setting comes right after the scheme
        var url = "jdbc:postgresql:Password=a//" + VALUE + ";Server=db1";

        // WHEN / THEN
        assertThatThrownBy(() -> JdbcUrlCredentials.requireNoneIn(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'Password' setting")
                .hasMessageNotContaining(VALUE);
    }

    @Test
    void in_mysqls_list_form_a_comma_does_separate_settings() {
        // GIVEN - (host=...,a=1,b=2) separates its settings with ','; address=(...)(...) does not
        var url = "jdbc:mysql://(host=db1,sslMode=a,user=b)/orders";

        // WHEN / THEN
        assertThatThrownBy(() -> JdbcUrlCredentials.requireNoneIn(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'user' setting");
    }

    @Test
    void a_huge_or_deeply_nested_url_is_read_quickly_and_never_breaks_the_check() {
        // GIVEN - 60000 URLs nested in one, and a name behind 20000 passed-on prefixes
        var nested = new StringBuilder("jdbc:mysql://");
        for (var i = 0; i < 60_000; i++) {
            nested.append("jdbc:mysql://h").append(i).append("/d?k=v&");
        }
        var prefixed = "jdbc:aws-wrapper:postgresql://db1/orders?" + "monitoring-".repeat(20_000) + "password=" + VALUE;

        // WHEN / THEN - linear, and no stack to overflow
        assertTimeout(Duration.ofSeconds(5), () -> {
            assertThatCode(() -> JdbcUrlCredentials.requireNoneIn(nested.toString()))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> JdbcUrlCredentials.requireNoneIn(prefixed))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(VALUE);
        });
    }

    @Test
    void random_text_never_breaks_the_check_and_never_shows_a_value() {
        // GIVEN - text made of every character the URL forms give a meaning to
        var random = new Random(20261005);
        var alphabet = "?&;()=,:@/#'\"[]% \tabcdefgPASSWORDuser0123456789jdbc:mysql:postgresql:mariadb:";
        var watched = new ArrayList<>(JdbcUrlCredentials.SETTINGS.keySet());

        // WHEN / THEN - refused or accepted, nothing else, and the value never in a message
        for (var n = 0; n < 50_000; n++) {
            var text = new StringBuilder(random.nextBoolean() ? "jdbc:mysql://" : "");
            for (var length = random.nextInt(100); length > 0; length--) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            if (random.nextInt(4) == 0) {
                text.append(watched.get(random.nextInt(watched.size())))
                        .append('=')
                        .append(VALUE);
            }
            try {
                JdbcUrlCredentials.requireNoneIn(text.toString());
            } catch (IllegalArgumentException refused) {
                assertThat(refused.getMessage()).as(text.toString()).doesNotContain(VALUE);
            }
        }
    }

    @Test
    void every_refused_setting_is_named_once_with_where_it_goes() {
        // GIVEN - the same login on two MySQL hosts
        var url = "jdbc:mysql://(host=h1,user=app,password=" + VALUE + "),(host=h2,user=app,password=" + VALUE
                + ")/orders";

        // WHEN / THEN - one message, each setting once
        assertThatThrownBy(() -> JdbcUrlCredentials.requireNoneIn(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("The JDBC URL carries the login user (its 'user' setting) - give it to"
                        + " username(...); the login password (its 'password' setting) - give it to password(...): a URL"
                        + " is logged")
                .hasMessageNotContaining(VALUE)
                .hasMessageNotContaining("app");
    }

    @Test
    void a_login_before_an_at_and_other_secrets_are_named_together() {
        // GIVEN
        var url = "jdbc:mysql://app:" + VALUE + "@db1:3306/orders?sslpassword=" + VALUE + "&idpUsername=jane";

        // WHEN / THEN
        assertThatThrownBy(() -> JdbcUrlCredentials.requireNoneIn(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "a user name and a password before an '@' - give them to username(...) and" + " password(...)")
                .hasMessageContaining("a secret (its 'sslpassword' setting) - put it under dataSourceProperties")
                .hasMessageContaining("a user name (its 'idpUsername' setting) - put it under dataSourceProperties")
                .hasMessageNotContaining(VALUE)
                .hasMessageNotContaining("jane");
    }

    @Test
    void only_names_are_read_never_values() {
        // GIVEN - values that spell refused names, escaped and raw
        var url = "jdbc:postgresql://db1/orders?ApplicationName=password%3Dx%26user%3Dy&options=-c%20user=app";

        // WHEN
        var names = JdbcUrlCredentials.read(url).settings.stream()
                .map(JdbcUrlCredentials.Setting::name)
                .toList();

        // THEN
        assertThat(names).containsExactly("ApplicationName", "options");
    }
}
