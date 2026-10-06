package io.example.wallet;

import io.ekbatan.core.config.ShardingConfig;
import io.ekbatan.flyway.FlywayMigrator;
import java.util.Arrays;
import java.util.stream.Stream;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs Flyway migrations against the same Postgres Ekbatan reads from {@code ekbatan.sharding.*}.
 * One bean for both production startup and Testcontainers-based integration tests - under tests
 * the {@link ShardingConfig} reflects whatever {@code DynamicPropertyRegistrar} injected (the
 * testcontainer's URL), so the same migration code path works without a separate test-side run.
 *
 * <p>The {@link BeanFactoryPostProcessor} below augments the framework-defined
 * {@code ekbatanJobRegistry} bean definition with a {@code dependsOn} edge pointing at
 * {@code flywayMigration} - that way db-scheduler's {@code start()} can't poll
 * {@code scheduled_tasks} before migrations have created the table. We can't put
 * {@code @DependsOn} on a bean we don't define, so we mutate its definition during the BFPP
 * phase instead.
 */
@Configuration
public class FlywayConfiguration {

    @Bean
    public FlywayMigration flywayMigration(ShardingConfig shardingConfig) {
        // FlywayMigrator connects the way the application's pools do - same driver, same
        // dataSourceProperties - rather than handing Flyway a bare URL, user and password.
        var primary = shardingConfig.groups.getFirst().members.getFirst().primaryConfig();
        FlywayMigrator.migrate(primary, "classpath:db/migration");
        return new FlywayMigration();
    }

    @Bean
    public static BeanFactoryPostProcessor jobRegistryDependsOnFlyway() {
        return beanFactory -> {
            if (!beanFactory.containsBeanDefinition("ekbatanJobRegistry")) {
                return;
            }
            var bd = beanFactory.getBeanDefinition("ekbatanJobRegistry");
            var existing = bd.getDependsOn();
            var merged = existing == null
                    ? new String[] {"flywayMigration"}
                    : Stream.concat(Arrays.stream(existing), Stream.of("flywayMigration"))
                            .toArray(String[]::new);
            bd.setDependsOn(merged);
        };
    }

    /** Marker - its only purpose is to be a Spring bean other beans can {@code dependsOn}. */
    public static final class FlywayMigration {}
}
