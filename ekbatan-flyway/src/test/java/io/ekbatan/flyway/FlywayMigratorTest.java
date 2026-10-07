package io.ekbatan.flyway;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.config.ShardGroupConfig.Builder.shardGroupConfig;
import static io.ekbatan.core.config.ShardMemberConfig.Builder.shardMemberConfig;
import static io.ekbatan.core.config.ShardingConfig.Builder.shardingConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.shard.ShardIdentifier;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.core.api.resource.LoadableResource;
import org.flywaydb.core.internal.sqlscript.SqlScriptMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlywayMigratorTest {

    @Test
    void should_detect_native_image_runtime_only() {
        var key = "org.graalvm.nativeimage.imagecode";
        var originalValue = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertThat(NativeImageFlywayResourceProvider.inNativeImage()).isFalse();

            System.setProperty(key, "buildtime");
            assertThat(NativeImageFlywayResourceProvider.inNativeImage()).isFalse();

            System.setProperty(key, "runtime");
            assertThat(NativeImageFlywayResourceProvider.inNativeImage()).isTrue();
        } finally {
            if (originalValue == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, originalValue);
            }
        }
    }

    @Test
    void should_build_the_native_scanner_from_the_locations_the_customizer_left_behind() {
        // Goes through configure(...) rather than installNativeResourceProvider(...) directly: the
        // defect was the ordering of those two steps, so a test that performs them in its own order
        // proves nothing. Asserting on the scanner's captured locations, not the configuration's -
        // the configuration was always updated; the scanner was what kept the stale copy.
        var cfg = Flyway.configure().locations("classpath:db/migration");

        FlywayMigrator.applyCustomizerThenProvider(cfg, c -> c.locations("classpath:db/other"), true);

        assertThat(cfg.getResourceProvider()).isInstanceOf(NativeImageFlywayResourceProvider.class);
        var scanner = (NativeImageFlywayResourceProvider) cfg.getResourceProvider();
        assertThat(scanner.locations).extracting(Location::getDescriptor).containsExactly("classpath:db/other");
    }

    @Test
    void should_keep_a_resource_provider_the_customizer_supplied() {
        // The documented escape hatch. A plain reorder would have overwritten it.
        var cfg = Flyway.configure().locations("classpath:db/migration");
        ResourceProvider explicit = new ResourceProvider() {
            @Override
            public LoadableResource getResource(String name) {
                return null;
            }

            @Override
            public java.util.Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
                return java.util.List.of();
            }
        };
        cfg.resourceProvider(explicit);

        FlywayMigrator.installNativeResourceProvider(cfg, true);

        assertThat(cfg.getResourceProvider()).isSameAs(explicit);
    }

    @Test
    void should_not_install_the_native_scanner_outside_a_native_image() {
        var cfg = Flyway.configure().locations("classpath:db/migration");

        FlywayMigrator.installNativeResourceProvider(cfg, false);

        assertThat(cfg.getResourceProvider()).isNull();
    }

    @Test
    void should_read_a_filesystem_location_in_a_native_image(@TempDir Path folder) throws Exception {
        // GIVEN - migrations in a folder on disk, beside a file that is not one
        Files.writeString(folder.resolve("V1__first.sql"), "SELECT 1;");
        Files.writeString(folder.resolve("R__view.sql"), "SELECT 2;");
        Files.writeString(folder.resolve("notes.txt"), "not a migration");
        var scanner = new NativeImageFlywayResourceProvider(
                Flyway.configure().locations("filesystem:" + folder),
                getClass().getClassLoader(),
                StandardCharsets.UTF_8);

        // WHEN
        var versioned = scanner.getResources("V", new String[] {".sql"});
        var repeatable = scanner.getResources("R", new String[] {".sql"});

        // THEN - each migration found, and read from the disk
        assertThat(versioned).extracting(LoadableResource::getFilename).containsExactly("V1__first.sql");
        assertThat(repeatable).extracting(LoadableResource::getFilename).containsExactly("R__view.sql");
        try (var reader = new BufferedReader(versioned.iterator().next().read())) {
            assertThat(reader.readLine()).isEqualTo("SELECT 1;");
        }
    }

    @Test
    void should_answer_flyway_when_it_asks_for_a_migrations_conf_file_on_disk(@TempDir Path folder) throws Exception {
        // GIVEN - two migrations in a folder on disk, the second with a .conf file beside it
        Files.writeString(folder.resolve("V1__first.sql"), "SELECT 1;");
        Files.writeString(folder.resolve("V2__second.sql"), "SELECT 2;");
        Files.writeString(folder.resolve("V2__second.sql.conf"), "executeInTransaction=false");
        var scanner = new NativeImageFlywayResourceProvider(
                Flyway.configure().locations("filesystem:" + folder),
                getClass().getClassLoader(),
                StandardCharsets.UTF_8);
        var migrations = List.copyOf(scanner.getResources("V", new String[] {".sql"}));
        assertThat(migrations)
                .extracting(LoadableResource::getFilename)
                .containsExactly("V1__first.sql", "V2__second.sql");

        // WHEN - Flyway asks for each one's .conf file, through its own lookup
        var firstConf = SqlScriptMetadata.getMetadataResource(scanner, migrations.get(0));
        var secondConf = SqlScriptMetadata.getMetadataResource(scanner, migrations.get(1));

        // THEN - none for the first, and the second's read from the disk
        assertThat(firstConf).isNull();
        assertThat(secondConf.getFilename()).isEqualTo("V2__second.sql.conf");
        try (var reader = new BufferedReader(secondConf.read())) {
            assertThat(reader.readLine()).isEqualTo("executeInTransaction=false");
        }
    }

    @Test
    void should_refuse_a_location_a_native_image_cannot_read() {
        // GIVEN - a kind of location only a Flyway plugin adds - s3: comes from flyway-locations-s3 -
        // so with no plugin here, a configuration is made to hold one
        var withS3 = new ClassicConfiguration() {
            @Override
            public Location[] getLocations() {
                return new Location[] {Location.fromPath("s3:", "migrations-bucket/db")};
            }
        };
        var scanner =
                new NativeImageFlywayResourceProvider(withS3, getClass().getClassLoader(), StandardCharsets.UTF_8);

        // WHEN / THEN - refused, never skipped in silence
        assertThatThrownBy(() -> scanner.getResources("V", new String[] {".sql"}))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("s3:migrations-bucket/db cannot be read in a native image");
    }

    @Test
    void should_build_targets_from_sharding_config_in_group_member_order() {
        var globalPrimary = dataSourceConfig()
                .jdbcUrl("jdbc:postgresql://global:5432/wallet")
                .username("app")
                .password("secret")
                .build();
        var mexicoPrimary = dataSourceConfig()
                .jdbcUrl("jdbc:postgresql://mexico:5432/wallet")
                .username("app")
                .password("secret")
                .build();

        var config = shardingConfig()
                .defaultShard(ShardIdentifier.of(0, 0))
                .withGroup(shardGroupConfig()
                        .group(0)
                        .name("global")
                        .withMember(shardMemberConfig()
                                .member(0)
                                .name("eu")
                                .primaryConfig(globalPrimary)
                                .build())
                        .build())
                .withGroup(shardGroupConfig()
                        .group(1)
                        .name("mexico")
                        .withMember(shardMemberConfig()
                                .member(0)
                                .primaryConfig(mexicoPrimary)
                                .build())
                        .build())
                .build();

        var targets = FlywayMigrator.targets(config);

        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).shard()).isEqualTo(ShardIdentifier.of(0, 0));
        assertThat(targets.get(0).name()).isEqualTo("global/eu");
        assertThat(targets.get(0).dataSourceConfig()).isSameAs(globalPrimary);
        assertThat(targets.get(1).shard()).isEqualTo(ShardIdentifier.of(1, 0));
        assertThat(targets.get(1).name()).isEqualTo("mexico/member-0");
        assertThat(targets.get(1).dataSourceConfig()).isSameAs(mexicoPrimary);
    }

    @Test
    void should_fail_when_no_targets_are_configured() {
        assertThatThrownBy(() -> FlywayMigrator.builder().migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void should_reject_blank_locations() {
        assertThatThrownBy(() -> FlywayMigrator.builder().locations(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("locations");
    }
}
