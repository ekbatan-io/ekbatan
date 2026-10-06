# Database

The persistence layer — everything that touches the actual SQL database. Where domain code goes through `Repository` and `TransactionManager`; where shards live; where the outbox table sits on disk; where dialect quirks and JOOQ codegen are configured.

For the framework's *concept* of the outbox (the atomic state + events story), see [The outbox: atomic state + events](../concepts/outbox.md). This category covers everything else about the database.

- **[Repositories on JOOQ](repositories.md)** — `db()` / `readonlyDb()` / `txDb()` / `txDbElseDb()`, soft delete, custom queries
- **[TransactionManager](transaction-manager.md)** — direct transactional access outside the Action pipeline (admin scripts, batch jobs, custom multi-step reads)
- **[Framework tables](tables.md)** — table map and optionality matrix
  - **[`eventlog.events`](tables/events.md)** — base event table DDL, columns, sentinel rows, dialect types
  - **[`eventlog.event_notifications`](tables/event-notifications.md)** — local-handler delivery table DDL, columns, states, handler names
  - **[`scheduled_tasks`](tables/scheduled-tasks.md)** — db-scheduler table DDL, columns, optionality
- **[Sharding](sharding.md)** — group + member, `ShardedUUID`, custom `ShardingStrategy`, cross-shard rules
- **[Connecting to the database](connecting.md)** — `username`, `password`, `data-source-properties`, and every way to log in: passwords, client certificates, IAM, Kerberos, driver plugins
- **[Pessimistic locking via `KeyedLockProvider`](keyed-locks.md)** — four backends (Postgres, MySQL, MariaDB, Redis), reentrancy contract, the `lockConfig` slot
- **[Multi-database (PostgreSQL / MySQL / MariaDB)](multi-database.md)** — dialect cheatsheet, init scripts, partial indexes, the `dialect.family()` switch pattern
  - **[PostgreSQL setup](postgresql.md)** — required tables, DDL, codegen block, gotchas (one-stop reference)
  - **[MariaDB setup](mariadb.md)** — required tables, DDL, codegen block, init script, gotchas
  - **[MySQL setup](mysql.md)** — required tables, DDL, codegen block, init script, gotchas
- **[JOOQ codegen](jooq-codegen.md)** — what codegen generates, the framework's seven converters, when to use each, per-dialect modeling rationale (build-tool agnostic). For the plugin syntax itself, see [Gradle](../gradle/jooq-codegen.md) / [Maven](../maven/jooq-codegen.md).

← Back to [docs index](../README.md) · [Top README](../../README.md)
