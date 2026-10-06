package io.ekbatan.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.postgresql.PGProperty;

/**
 * The ledger lists every setting the drivers on this module's test classpath have - the versions
 * Ekbatan builds against. A driver upgrade that brings a new setting fails here until the ledger
 * says whether it holds a user name or a secret.
 */
class DriverSettingsLedgerTest {

    /** Every name the ledger lists for the driver, setting names and aliases, lower-cased. */
    private static Set<String> ledgerNames(String driver) {
        return DriverSettingsLedger.ROWS.stream()
                .filter(row -> row.driver().equals(driver))
                .flatMap(row -> row.names().stream())
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private static List<String> missing(String driver, List<String> driverNames) {
        var listed = ledgerNames(driver);
        return driverNames.stream()
                .filter(name -> !listed.contains(name.toLowerCase(Locale.ROOT)))
                .toList();
    }

    @Test
    void every_postgresql_setting_is_in_the_ledger() {
        // GIVEN - every setting the driver has
        var names = Arrays.stream(PGProperty.values()).map(PGProperty::getName).toList();
        assertThat(names).hasSizeGreaterThan(80);

        // WHEN / THEN
        assertThat(missing("postgresql", names)).isEmpty();
    }

    @Test
    void every_mysql_setting_and_alias_is_in_the_ledger() throws Exception {
        // GIVEN - every setting the driver has, and the alias it reads each by
        var propertyKey = Class.forName("com.mysql.cj.conf.PropertyKey");
        var names = new ArrayList<String>();
        for (var key : propertyKey.getEnumConstants()) {
            names.add((String) propertyKey.getMethod("getKeyName").invoke(key));
            var alias = (String) propertyKey.getMethod("getCcAlias").invoke(key);
            if (alias != null) {
                names.add(alias);
            }
        }
        assertThat(names).hasSizeGreaterThan(250);

        // WHEN / THEN
        assertThat(missing("mysql", names)).isEmpty();
    }

    @Test
    void every_mariadb_setting_and_alias_is_in_the_ledger() throws Exception {
        // GIVEN - every option the driver has, and its aliases
        var builder = Class.forName("org.mariadb.jdbc.Configuration$Builder");
        var names = new ArrayList<String>();
        for (var field : builder.getDeclaredFields()) {
            if (!field.getName().startsWith("_") && !Modifier.isStatic(field.getModifiers())) {
                names.add(field.getName());
            }
        }
        var aliases = (Map<?, ?>) Class.forName("org.mariadb.jdbc.util.options.OptionAliases")
                .getField("OPTIONS_ALIASES")
                .get(null);
        aliases.keySet().forEach(alias -> names.add(alias.toString()));
        assertThat(names).hasSizeGreaterThan(90);

        // WHEN / THEN
        assertThat(missing("mariadb", names)).isEmpty();
    }
}
