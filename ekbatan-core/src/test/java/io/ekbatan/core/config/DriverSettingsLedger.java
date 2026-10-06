package io.ekbatan.core.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The ledger {@code driver-settings.tsv}: every setting of every driver whose URL Ekbatan accepts,
 * each marked with what it holds. See the README beside it for how each driver's list was made.
 */
final class DriverSettingsLedger {

    /** One setting of one driver. */
    record Row(String driver, String versions, String setting, List<String> aliases, String kind, String note) {

        /** The setting's own name, then its aliases. */
        List<String> names() {
            return Stream.concat(Stream.of(setting), aliases.stream()).toList();
        }

        /** Whether the URL check refuses it: it holds a user name or a secret. */
        boolean refused() {
            return kind.equals("login-user")
                    || kind.equals("login-password")
                    || kind.equals("user-name")
                    || kind.equals("secret");
        }

        /** What the URL check calls it. */
        JdbcUrlCredentials.Kind checkKind() {
            return switch (kind) {
                case "login-user" -> JdbcUrlCredentials.Kind.LOGIN_USER;
                case "login-password" -> JdbcUrlCredentials.Kind.LOGIN_PASSWORD;
                case "user-name" -> JdbcUrlCredentials.Kind.USER_NAME;
                case "secret" -> JdbcUrlCredentials.Kind.SECRET;
                default -> throw new IllegalStateException(setting + " is not refused");
            };
        }

        /**
         * Real names for the setting's names: a name with a {@code <...>} part stands for many,
         * so a few are made from it.
         */
        List<String> examples() {
            return names().stream()
                    .flatMap(name -> switch (name) {
                        case "password<N>" -> Stream.of("password2", "password3", "password4", "password10");
                        case "TC_<NAME>" -> Stream.of("TC_MY_CNF");
                        case "server.<mysqld option>" -> Stream.of("server.basedir", "server.port");
                        // a driver that has no setting of its own
                        case "(none)" -> Stream.<String>empty();
                        default -> Stream.of(name);
                    })
                    .toList();
        }
    }

    static final List<Row> ROWS = read();

    private DriverSettingsLedger() {}

    /** The rows of one kind, other than the prefix rows. */
    static Stream<Row> settings() {
        return ROWS.stream().filter(row -> !row.kind().equals("prefix"));
    }

    /** The prefixes the ledger lists, such as {@code monitoring-}. */
    static List<String> prefixes() {
        return ROWS.stream()
                .filter(row -> row.kind().equals("prefix"))
                .map(row -> row.setting().replace("<setting>", "").toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private static List<Row> read() {
        try (var in = DriverSettingsLedger.class.getResourceAsStream("driver-settings.tsv")) {
            if (in == null) {
                throw new IllegalStateException("driver-settings.tsv is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .skip(1)
                    .filter(line -> !line.isBlank())
                    .map(line -> {
                        var cells = line.split("\t", -1);
                        if (cells.length != 6) {
                            throw new IllegalStateException("not six columns: " + line);
                        }
                        var aliases = cells[3].isEmpty() ? List.<String>of() : Arrays.asList(cells[3].split(","));
                        return new Row(cells[0], cells[1], cells[2], aliases, cells[4], cells[5]);
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
