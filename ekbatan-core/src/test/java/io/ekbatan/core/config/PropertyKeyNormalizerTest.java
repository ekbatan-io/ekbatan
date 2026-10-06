package io.ekbatan.core.config;

import static io.ekbatan.core.config.PropertyKeyNormalizer.kebabToCamel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Covers the behaviour callers of {@link PropertyKeyNormalizer} actually rely on: kebab -> camel
 * translation, camelCase pass-through, and the structural characters (brackets, dots, colons)
 * that show up in our property key paths must not be disturbed.
 */
class PropertyKeyNormalizerTest {

    @Test
    void leavesPlainCamelCaseUntouched() {
        assertThat(kebabToCamel("defaultShard")).isEqualTo("defaultShard");
        assertThat(kebabToCamel("jdbcUrl")).isEqualTo("jdbcUrl");
        assertThat(kebabToCamel("groups[0].members[0]")).isEqualTo("groups[0].members[0]");
    }

    @Test
    void leavesEmptyAndSingleSegmentUntouched() {
        assertThat(kebabToCamel("")).isEqualTo("");
        assertThat(kebabToCamel("group")).isEqualTo("group");
    }

    @Test
    void foldsSingleHyphen() {
        assertThat(kebabToCamel("default-shard")).isEqualTo("defaultShard");
        assertThat(kebabToCamel("jdbc-url")).isEqualTo("jdbcUrl");
    }

    @Test
    void foldsMultipleHyphensPerSegment() {
        assertThat(kebabToCamel("handling-max-backoff-cap")).isEqualTo("handlingMaxBackoffCap");
        assertThat(kebabToCamel("handling-retention-window")).isEqualTo("handlingRetentionWindow");
    }

    @Test
    void preservesDotSeparatedPaths() {
        // The dot separator and bracket-index markers are structural, not casing - leave them alone.
        assertThat(kebabToCamel("default-shard.group")).isEqualTo("defaultShard.group");
        assertThat(kebabToCamel("groups[0].members[0].configs.primary-config.jdbc-url"))
                .isEqualTo("groups[0].members[0].configs.primaryConfig.jdbcUrl");
    }

    @Test
    void handlesMixedKebabAndCamelInSamePath() {
        // Mixed-form paths are the whole point of the helper - both shapes resolve to the same
        // canonical camelCase string.
        assertThat(kebabToCamel("default-shard.member")).isEqualTo("defaultShard.member");
        assertThat(kebabToCamel("defaultShard.member")).isEqualTo("defaultShard.member");
        assertThat(kebabToCamel("configs.primary-config.jdbcUrl")).isEqualTo("configs.primaryConfig.jdbcUrl");
        assertThat(kebabToCamel("configs.primaryConfig.jdbc-url")).isEqualTo("configs.primaryConfig.jdbcUrl");
    }

    @Test
    void uppercasesOnlyTheCharacterAfterTheHyphen() {
        // The state machine only uppercases the immediate next character; subsequent letters
        // stay as written. Confirms we don't accidentally upper-case the whole tail.
        assertThat(kebabToCamel("a-bc")).isEqualTo("aBc");
        assertThat(kebabToCamel("foo-barBaz")).isEqualTo("fooBarBaz");
    }

    @Test
    void leavesNumericSegmentsAlone() {
        // Property paths can include numeric suffixes (e.g., wallet shard names primary-eu-west-1)
        // - the digit after the hyphen survives as-is.
        assertThat(kebabToCamel("primary-eu-west-1")).isEqualTo("primaryEuWest1");
    }

    @Test
    void leavesDriverSettingNamesInsideDataSourcePropertiesExactlyAsWritten() {
        // A driver reads its setting names exactly as written, so the container segment is folded
        // like any other and everything after it is not - hyphens, dots and case alike.
        assertThat(kebabToCamel("configs.primary-config.data-source-properties.keyStorePassword"))
                .isEqualTo("configs.primaryConfig.dataSourceProperties.keyStorePassword");
        assertThat(kebabToCamel("configs.primary-config.data-source-properties.ha.enableJMX"))
                .isEqualTo("configs.primaryConfig.dataSourceProperties.ha.enableJMX");
        assertThat(kebabToCamel("configs.primary-config.data-source-properties.some-driver-setting"))
                .isEqualTo("configs.primaryConfig.dataSourceProperties.some-driver-setting");
        assertThat(kebabToCamel("configs.primaryConfig.dataSourceProperties.some-driver-setting"))
                .isEqualTo("configs.primaryConfig.dataSourceProperties.some-driver-setting");
    }

    @Test
    void readerKeyTurnsTheStructureDotsIntoSlashesAndLeavesTheSettingNameAlone() {
        assertThat(PropertyKeyNormalizer.toReaderKey(
                        "groups[0].members[0].configs.primaryConfig.dataSourceProperties.ha.enableJMX"))
                .isEqualTo("groups[0]/members[0]/configs/primaryConfig/dataSourceProperties/ha.enableJMX");
        assertThat(PropertyKeyNormalizer.toReaderKey("configs.primaryConfig.dataSourceProperties.a.b.c"))
                .isEqualTo("configs/primaryConfig/dataSourceProperties/a.b.c");
        assertThat(PropertyKeyNormalizer.toReaderKey("configs.primaryConfig.dataSourceProperties.ApplicationName"))
                .isEqualTo("configs/primaryConfig/dataSourceProperties/ApplicationName");
        assertThat(PropertyKeyNormalizer.toReaderKey("dataSourceProperties.kms.region"))
                .isEqualTo("dataSourceProperties/kms.region");
    }

    @Test
    void readerKeyTurnsEveryDotOfAnyOtherKeyIntoASlash() {
        assertThat(PropertyKeyNormalizer.toReaderKey("groups[0].members[0].configs.primaryConfig.jdbcUrl"))
                .isEqualTo("groups[0]/members[0]/configs/primaryConfig/jdbcUrl");
        assertThat(PropertyKeyNormalizer.toReaderKey("defaultShard.group")).isEqualTo("defaultShard/group");
        assertThat(PropertyKeyNormalizer.toReaderKey("groups[1].name")).isEqualTo("groups[1]/name");
    }

    @Test
    void readerKeySplitsAtTheFirstDataSourcePropertiesSegmentOnly() {
        // a setting whose own name contains the word is still one name
        assertThat(PropertyKeyNormalizer.toReaderKey(
                        "configs.primaryConfig.dataSourceProperties.x.dataSourceProperties.y"))
                .isEqualTo("configs/primaryConfig/dataSourceProperties/x.dataSourceProperties.y");
    }

    @ParameterizedTest
    @ValueSource(strings = {"odd/name", "x[0]", "x[", "y]"})
    void readerKeyRefusesASettingNameTheReaderWouldTreatAsStructure(String name) {
        assertThatThrownBy(
                        () -> PropertyKeyNormalizer.toReaderKey("configs.primaryConfig.dataSourceProperties." + name))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("which no driver setting has");
    }

    @Test
    void readerKeyRefusesAConfigNamedDataSourceProperties() {
        assertThatThrownBy(() ->
                        PropertyKeyNormalizer.toReaderKey("groups[0].members[0].configs.dataSourceProperties.jdbcUrl"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be named 'dataSourceProperties'");
    }

    @Test
    void readerKeyRefusesAStructuralKeyHoldingTheSeparator() {
        assertThatThrownBy(() -> PropertyKeyNormalizer.toReaderKey("groups[0].members[0].configs.odd/config.jdbcUrl"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contains '/'");
    }

    @Test
    void readerKeysCopyTheValuesUntouched() {
        // GIVEN
        var canonical = new java.util.Properties();
        canonical.setProperty("configs.primaryConfig.dataSourceProperties.ha.enableJMX", "true");
        canonical.setProperty("configs.primaryConfig.dataSourceProperties.options", "-c search_path=a.b  ");
        canonical.setProperty("configs.primaryConfig.jdbcUrl", "jdbc:postgresql://h.example:5432/db?x=1/2");

        // WHEN
        var forReader = PropertyKeyNormalizer.toReaderKeys(canonical);

        // THEN
        assertThat(forReader).hasSize(3);
        assertThat(forReader.getProperty("configs/primaryConfig/dataSourceProperties/ha.enableJMX"))
                .isEqualTo("true");
        assertThat(forReader.getProperty("configs/primaryConfig/dataSourceProperties/options"))
                .isEqualTo("-c search_path=a.b  ");
        assertThat(forReader.getProperty("configs/primaryConfig/jdbcUrl"))
                .isEqualTo("jdbc:postgresql://h.example:5432/db?x=1/2");
    }
}
