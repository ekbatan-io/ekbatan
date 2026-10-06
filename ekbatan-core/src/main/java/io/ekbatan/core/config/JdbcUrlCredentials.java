package io.ekbatan.core.config;

import static java.util.Map.entry;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Refuses a JDBC URL whose text carries a user name or a secret.
 *
 * <p>A URL is logged and repeated - Flyway logs it at startup, drivers repeat it in their errors -
 * so it carries neither: the login user goes to {@code username}, the login password to
 * {@code password}, and every other user name or secret to {@code data-source-properties}.
 *
 * <p>The check reads the URL's own text, which is what gets logged, rather than asking the URL's
 * driver what it carries. A driver's answer mixes in values from elsewhere - PostgreSQL's driver
 * adds the password from {@code .pgpass} and a service file's user and password - and MySQL's
 * driver answers nothing at all for a URL with two hosts.
 *
 * <p>Which setting names hold a user name or a secret is decided once, for every setting of every
 * driver whose URL Ekbatan accepts, in the ledger
 * {@code ekbatan-core/src/test/resources/io/ekbatan/core/config/driver-settings.tsv}; the names
 * here are its refused part, and a test fails when the two disagree. Names are read only where
 * the drivers read a setting's name (see {@link #read}), and matched ignoring case and spaces, as
 * MySQL's, MariaDB's and CData's drivers read them; a setting with no value carries nothing.
 */
final class JdbcUrlCredentials {

    /** What a refused setting holds, which decides where its value goes instead. */
    enum Kind {
        /** The user this configuration logs in as. */
        LOGIN_USER,
        /** This configuration's login password. */
        LOGIN_PASSWORD,
        /** Any other user name: an identity provider's, a cache's, a pool's, a monitoring connection's. */
        USER_NAME,
        /** Any other secret: a key or keystore password, a secret key, a client secret. */
        SECRET
    }

    /** The refused setting names of the ledger, lower-cased. */
    static final Map<String, Kind> SETTINGS = Map.<String, Kind>ofEntries(
            // every driver that logs in itself
            entry("user", Kind.LOGIN_USER),
            entry("password", Kind.LOGIN_PASSWORD),
            // PostgreSQL; pgjdbc-ng's alternate name for ssl.key.password
            entry("sslpassword", Kind.SECRET),
            // MySQL, and MariaDB's aliases for its own store passwords
            entry("clientcertificatekeystorepassword", Kind.SECRET),
            entry("trustcertificatekeystorepassword", Kind.SECRET),
            entry("xdevapi.ssl-keystore-password", Kind.SECRET),
            entry("xdevapisslkeystorepassword", Kind.SECRET),
            entry("xdevapi.ssl-truststore-password", Kind.SECRET),
            entry("xdevapissltruststorepassword", Kind.SECRET),
            // MariaDB, including its AWS-IAM login plugin's secret key
            entry("keystorepassword", Kind.SECRET),
            entry("truststorepassword", Kind.SECRET),
            entry("keypassword", Kind.SECRET),
            entry("secretkey", Kind.SECRET),
            // AWS Advanced JDBC Wrapper, including the settings its cp- prefix hands to its own pool
            entry("dbuser", Kind.USER_NAME),
            entry("idpusername", Kind.USER_NAME),
            entry("idppassword", Kind.SECRET),
            entry("cacheusername", Kind.USER_NAME),
            entry("cachepassword", Kind.SECRET),
            entry("cp-username", Kind.USER_NAME),
            entry("cp-user", Kind.USER_NAME),
            entry("cp-overridedefaultuser", Kind.USER_NAME),
            entry("cp-password", Kind.SECRET),
            entry("cp-overridedefaultpassword", Kind.SECRET),
            // Azure identity extensions
            entry("azure.username", Kind.USER_NAME),
            entry("azure.password", Kind.SECRET),
            entry("azure.clientsecret", Kind.SECRET),
            entry("azure.clientcertificatepassword", Kind.SECRET),
            // pgjdbc-ng, and DataDirect's drivers for clientuser
            entry("ssl.key.password", Kind.SECRET),
            entry("clientuser", Kind.USER_NAME),
            // CData's and DataDirect's drivers: tunnels, proxies, OAuth, AWS, licence key
            entry("sshuser", Kind.USER_NAME),
            entry("sshpassword", Kind.SECRET),
            entry("sshclientcert", Kind.SECRET),
            entry("sshclientcertpassword", Kind.SECRET),
            entry("firewalluser", Kind.USER_NAME),
            entry("firewallpassword", Kind.SECRET),
            entry("proxyuser", Kind.USER_NAME),
            entry("proxypassword", Kind.SECRET),
            entry("kerberosuser", Kind.USER_NAME),
            entry("integrateduser", Kind.USER_NAME),
            entry("sslclientcert", Kind.SECRET),
            entry("sslclientcertpassword", Kind.SECRET),
            entry("certificatepassword", Kind.SECRET),
            entry("oauthclientsecret", Kind.SECRET),
            entry("oauthaccesstoken", Kind.SECRET),
            entry("oauthrefreshtoken", Kind.SECRET),
            entry("oauthverifier", Kind.SECRET),
            entry("oauthjwtcert", Kind.SECRET),
            entry("oauthjwtcertpassword", Kind.SECRET),
            entry("oauthencryptkey", Kind.SECRET),
            entry("mfatoken", Kind.SECRET),
            entry("awssecretkey", Kind.SECRET),
            entry("rtk", Kind.SECRET),
            // SSH tunnel drivers that keep the real URL inside theirs (jdbc-sshj, jdbc-ssh, jdbc-ssh-tunnel)
            entry("username", Kind.USER_NAME),
            entry("private.key.password", Kind.SECRET),
            entry("jdbc.ssh.username", Kind.USER_NAME),
            entry("jdbc.ssh.password", Kind.SECRET),
            entry("jdbc.ssh.passphrase", Kind.SECRET),
            entry("sshpassphrase", Kind.SECRET),
            // Kingbase's pgjdbc forks
            entry("ukpwdpassword", Kind.SECRET),
            entry("usbkeypin", Kind.SECRET),
            // MySQL Connector/MXJ, which starts an embedded server
            entry("server.initialize-user.user", Kind.USER_NAME),
            entry("server.initialize-user.password", Kind.SECRET));

    /**
     * {@code password2}, {@code password3}, ...: MySQL's multi-factor passwords
     * ({@code password1} to {@code password3}) and MariaDB's PAM passwords, read for as many
     * prompts as the server makes.
     */
    private static final Pattern NUMBERED_PASSWORD = Pattern.compile("password[0-9]+");

    /**
     * The AWS wrapper's prefixes that pass a setting on, prefix removed, to its monitoring
     * connections: {@code monitoring-password} is the password of those connections.
     */
    static final List<String> PASSED_ON_PREFIXES = List.of(
            "topology-monitoring-", "blue-green-monitoring-", "limitless-router-monitor-", "monitoring-", "frt-");

    private static final Pattern SPACES = Pattern.compile("\\s+");

    private static final String WHY =
            ": a URL is logged and repeated where user names and secrets must never be. The URL is not repeated here.";

    private JdbcUrlCredentials() {}

    /**
     * Throws when the URL's text carries a user name or a secret. The message names every one -
     * each setting once - and where each goes instead, and never repeats the URL or a value.
     *
     * @param jdbcUrl the configured JDBC URL.
     * @throws IllegalArgumentException if the URL carries a user name or a secret.
     */
    static void requireNoneIn(String jdbcUrl) {
        var found = read(jdbcUrl);
        var refusals = new ArrayList<Refusal>();
        if (found.user || found.password) {
            refusals.add(Refusal.beforeAnAt(found.user, found.password));
        }
        var named = new HashSet<String>();
        for (var setting : found.settings) {
            if (setting.hasValue()) {
                var kind = kindOf(setting.name());
                if (kind.isPresent() && named.add(setting.name().toLowerCase(Locale.ROOT))) {
                    refusals.add(Refusal.of(kind.get(), setting.name()));
                }
            }
        }
        if (refusals.size() == 1) {
            var refusal = refusals.get(0);
            throw new IllegalArgumentException("The JDBC URL carries " + refusal.what() + ". "
                    + Character.toUpperCase(refusal.move().charAt(0))
                    + refusal.move().substring(1) + " instead"
                    + WHY);
        }
        if (refusals.size() > 1) {
            var list = new StringBuilder();
            for (var refusal : refusals) {
                list.append(list.isEmpty() ? "" : "; ")
                        .append(refusal.what())
                        .append(" - ")
                        .append(refusal.move());
            }
            throw new IllegalArgumentException("The JDBC URL carries " + list + WHY);
        }
    }

    /** One thing the URL must not carry, and where it goes instead. */
    private record Refusal(String what, String move) {

        static Refusal of(Kind kind, String setting) {
            return switch (kind) {
                case LOGIN_USER ->
                    new Refusal("the login user (its '" + setting + "' setting)", "give it to username(...)");
                case LOGIN_PASSWORD ->
                    new Refusal("the login password (its '" + setting + "' setting)", "give it to password(...)");
                case USER_NAME ->
                    new Refusal("a user name (its '" + setting + "' setting)", "put it under " + PROPERTIES);
                case SECRET -> new Refusal("a secret (its '" + setting + "' setting)", "put it under " + PROPERTIES);
            };
        }

        static Refusal beforeAnAt(boolean user, boolean password) {
            if (user && password) {
                return new Refusal(
                        "a user name and a password before an '@'", "give them to username(...) and password(...)");
            }
            return user
                    ? new Refusal("a user name before an '@'", "give it to username(...)")
                    : new Refusal("a password before an '@'", "give it to password(...)");
        }
    }

    private static final String PROPERTIES = "dataSourceProperties (data-source-properties in YAML)";

    /**
     * What the setting of this name holds, if it is refused. Spaces inside the name are ignored,
     * since CData's drivers document names such as {@code SSH Password}. A name behind one of
     * {@link #PASSED_ON_PREFIXES} holds what the name without it holds, for another connection
     * than this configuration's login.
     */
    static Optional<Kind> kindOf(String name) {
        var lower = SPACES.matcher(name).replaceAll("").toLowerCase(Locale.ROOT);
        // each passed-on prefix hands the setting to another connection than this one's login
        var offset = 0;
        var passedOn = false;
        for (var stripped = true; stripped; ) {
            stripped = false;
            for (var prefix : PASSED_ON_PREFIXES) {
                if (lower.startsWith(prefix, offset) && lower.length() > offset + prefix.length()) {
                    offset += prefix.length();
                    passedOn = true;
                    stripped = true;
                    break;
                }
            }
        }
        var setting = lower.substring(offset);
        var kind = SETTINGS.get(setting);
        if (kind == null && NUMBERED_PASSWORD.matcher(setting).matches()) {
            kind = Kind.SECRET;
        }
        if (kind == null || !passedOn) {
            return Optional.ofNullable(kind);
        }
        return Optional.of(
                switch (kind) {
                    case LOGIN_USER, USER_NAME -> Kind.USER_NAME;
                    case LOGIN_PASSWORD, SECRET -> Kind.SECRET;
                });
    }

    /** A setting the URL's text carries: its name as written, and whether a value follows it. */
    record Setting(String name, boolean hasValue) {}

    /** What a URL's text carries: its settings, and a user or a password before an {@code @}. */
    static final class Found {
        final List<Setting> settings = new ArrayList<>();
        boolean user;
        boolean password;
    }

    /**
     * Reads a URL's text where the accepted drivers read settings, and nowhere else: a word such
     * as {@code password} in a host name, a database name, or inside another setting's value is
     * not a setting. A URL can hold another - an SSH tunnel's or a proxy's real URL, CData's
     * {@code CacheConnection} - and each one it holds is read the same way. Only names are read,
     * and whether a value follows them; values themselves never are.
     *
     * @param jdbcUrl the configured JDBC URL.
     * @return what the URL carries.
     */
    static Found read(String jdbcUrl) {
        var found = new Found();
        readOne(jdbcUrl, found);
        // each URL held inside is read on its own, from its jdbc: to the next one's
        var nested = indexOfJdbc(jdbcUrl, 1);
        while (nested >= 0) {
            var next = indexOfJdbc(jdbcUrl, nested + 1);
            readOne(jdbcUrl.substring(nested, next < 0 ? jdbcUrl.length() : next), found);
            nested = next;
        }
        return found;
    }

    private static int indexOfJdbc(String text, int from) {
        for (var i = from; i + JDBC.length() <= text.length(); i++) {
            if (text.regionMatches(true, i, JDBC, 0, JDBC.length())) {
                return i;
            }
        }
        return -1;
    }

    private static final String JDBC = "jdbc:";

    /**
     * One URL, read in the form it is written in:
     *
     * <ul>
     *   <li>with a host part after {@code //}: a user or password before an {@code @}, MySQL's
     *       host settings inside parentheses, then either DataDirect's settings separated by
     *       {@code ;} right after the host part, or the query after the first {@code ?} - settings
     *       separated by {@code &}, as every accepted driver reads them, a value never split
     *       further - and settings after a {@code ;} in the path, as jdbcdslog writes them;
     *   <li>with settings right after the scheme, {@code jdbc:postgresql:User=x;Password=y} - CData's
     *       form, and Broadcom DevTest's: settings separated by {@code ;}, to the end;
     *   <li>otherwise, pgjdbc's short form {@code jdbc:postgresql:db?...}: the query.
     * </ul>
     *
     * In the {@code ;} forms there is no query: a {@code ?} there is part of a value.
     */
    private static void readOne(String url, Found found) {
        var slashes = url.indexOf("//");
        var question = url.indexOf('?');
        var semicolon = url.indexOf(';');
        // a host part only right after the scheme: in CData's form a setting comes first, and its
        // value may hold a '//'
        if (slashes >= 0
                && (question < 0 || slashes < question)
                && (semicolon < 0 || slashes < semicolon)
                && url.lastIndexOf('=', slashes) < 0) {
            var end = readHosts(url, slashes + 2, found);
            if (end < url.length() && url.charAt(end) == ';') {
                readSemicolonSettings(url.substring(end + 1), found);
                return;
            }
            var query = url.indexOf('?', end);
            var path = query < 0 ? url.substring(end) : url.substring(end, query);
            var pathSemicolon = path.indexOf(';');
            if (pathSemicolon >= 0) {
                readSemicolonSettings(path.substring(pathSemicolon + 1), found);
            }
            if (query >= 0) {
                readQuery(url.substring(query + 1), found);
            }
            return;
        }
        var stop = question < 0 ? semicolon : semicolon < 0 ? question : Math.min(question, semicolon);
        if ((stop < 0 ? url : url.substring(0, stop)).indexOf('=') >= 0) {
            readSemicolonSettings(url, found);
        } else if (question >= 0) {
            readQuery(url.substring(question + 1), found);
        }
    }

    private static void readQuery(String query, Found found) {
        for (var piece : query.split("&", -1)) {
            add(piece, found);
        }
    }

    private static void readSemicolonSettings(String settings, Found found) {
        for (var piece : settings.split(";", -1)) {
            readSemicolonPiece(piece, true, found);
        }
    }

    /**
     * The host part, read as MySQL's driver reads it: it ends at the first {@code /}, {@code ?} or
     * {@code #}, or at a {@code ;} or the end outside parentheses; the index it ends at is
     * returned. Each host of a list ({@code h1,h2}) can carry its own user and password before an
     * {@code @}: a name before the {@code :} is a user, a non-empty text after it a password. Inside
     * parentheses are MySQL's host settings: separated by {@code ,} in {@code (host=h1,user=a)},
     * one to each pair of parentheses in {@code address=(host=h1)(user=a)}, where a value may hold
     * a {@code ,}. An {@code @} inside parentheses belongs to a setting's value.
     */
    private static int readHosts(String url, int start, Found found) {
        var depth = 0;
        var hostStart = start;
        var at = -1;
        var parenthesis = -1;
        var i = start;
        for (; i < url.length(); i++) {
            var c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#' || (depth == 0 && (c == ';' || c == ','))) {
                if (depth > 0) {
                    // an unclosed parenthesis: what follows it is still read as settings
                    readHostSettings(url, hostStart, at, url.substring(parenthesis, i), found);
                    depth = 0;
                }
                if (at >= 0) {
                    readUserInfo(url.substring(hostStart, at), found);
                }
                if (c != ',') {
                    return i;
                }
                hostStart = i + 1;
                at = -1;
            } else if (c == '(') {
                if (depth == 0) {
                    parenthesis = i + 1;
                }
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
                if (depth == 0) {
                    readHostSettings(url, hostStart, at, url.substring(parenthesis, i), found);
                }
            } else if (depth == 0 && c == '@' && at < 0) {
                at = i;
            }
        }
        if (depth > 0) {
            readHostSettings(url, hostStart, at, url.substring(parenthesis), found);
        }
        if (at >= 0) {
            readUserInfo(url.substring(hostStart, at), found);
        }
        return i;
    }

    /** One pair of parentheses of a host: one setting in MySQL's address form, a list in its other form. */
    private static void readHostSettings(String url, int hostStart, int at, String inside, Found found) {
        var form = at >= 0 ? at + 1 : hostStart;
        while (form < url.length() && (url.charAt(form) == ' ' || url.charAt(form) == '[')) {
            form++;
        }
        if (url.regionMatches(true, form, "address=", 0, "address=".length())) {
            add(inside, found);
        } else {
            for (var piece : inside.split(",", -1)) {
                add(piece, found);
            }
        }
    }

    /** What comes before an {@code @}: a user before the {@code :}, a password after it. */
    private static void readUserInfo(String userInfo, Found found) {
        var info = userInfo.strip();
        var colon = info.indexOf(':');
        if (!(colon < 0 ? info : info.substring(0, colon)).isBlank()) {
            found.user = true;
        }
        if (colon >= 0 && !info.substring(colon + 1).isBlank()) {
            found.password = true;
        }
    }

    /**
     * One piece of the {@code ;} settings: a name before its {@code =}, after any {@code :} -
     * CData writes the first setting right after the scheme. A value that opens with a quote or a
     * parenthesis can hold a setting of its own, as CData's {@code Other="SSHPassword=x;..."} and
     * DataDirect's {@code AlternateServers=(db2:5432;Password=x)} do; that one is read too, one
     * level deep. A piece with no {@code =} is not a setting.
     */
    private static void readSemicolonPiece(String piece, boolean outer, Found found) {
        var equals = piece.indexOf('=');
        if (equals < 0) {
            return;
        }
        var name = piece.substring(0, equals);
        add(name.substring(name.lastIndexOf(':') + 1) + piece.substring(equals), found);
        var value = piece.substring(equals + 1).stripLeading();
        if (outer && !value.isEmpty() && "\"'(".indexOf(value.charAt(0)) >= 0) {
            readSemicolonPiece(value.substring(1), false, found);
        }
    }

    /**
     * A {@code name=value} piece, or a bare name: the name with its {@code %XX} escapes decoded -
     * MySQL's driver decodes them - and its spaces trimmed. A name holding {@code /} is part of a
     * path, never a setting.
     */
    private static void add(String piece, Found found) {
        var equals = piece.indexOf('=');
        var name = decoded(equals < 0 ? piece : piece.substring(0, equals)).strip();
        if (name.isEmpty() || name.indexOf('/') >= 0) {
            return;
        }
        found.settings.add(
                new Setting(name, equals >= 0 && !piece.substring(equals + 1).isBlank()));
    }

    /** Decodes {@code %XX} escapes as UTF-8, leaving {@code +} as it is; a malformed escape leaves the name as written. */
    private static String decoded(String name) {
        if (name.indexOf('%') < 0) {
            return name;
        }
        try {
            return URLDecoder.decode(name.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return name;
        }
    }
}
