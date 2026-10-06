# Ekbatan documentation

Deep-dive docs linked from the top-level [README](../README.md). Pick a category — each category has its own index linking to focused topic pages.

- **[Wiring](wiring/README.md)** — full end-to-end app setup: plain Java or Spring Boot / Quarkus / Micronaut
- **[Core](concepts/README.md)** — the outbox, actions, models and entities, sagas
- **[Database](database/README.md)** — repositories, TransactionManager, framework tables, sharding, connecting (credentials, driver settings), pessimistic locks, dialect support
- **[Gradle](gradle/README.md)** — per-stack `build.gradle.kts` blocks, annotation-processor wiring, `dev.monosoul.jooq-docker` codegen plugin
- **[Maven](maven/README.md)** — `pom.xml` structure, dependencies, jOOQ codegen via the `fabric8 docker + flyway-maven + jooq-codegen-maven` chain
- **[Jobs](jobs/README.md)** — distributed background jobs (cluster-exclusive scheduling)
- **[Events out](events/README.md)** — in-process event handlers, Debezium → Kafka
- **[Runtime](runtime/README.md)** — OpenTelemetry tracing, GraalVM native-image

For the full architecture, contribution guidelines, and conventions, see [AGENTS.md](../AGENTS.md).
