# Wiring without DI

Ekbatan does not require Spring, Quarkus, Micronaut, or any DI container. Everything the framework needs is reachable through a small set of builders. You can wire it up by hand from a plain `main()`, a CLI, a Servlet listener, or any plain-JVM bootstrap path. The integration tests under [`ekbatan-integration-tests/postgres-simple/`](../../ekbatan-integration-tests/postgres-simple) do exactly this — no Spring on the classpath.

This page walks through the **complete** wiring of one running app. Domain classes (the Wallet model, three actions, the repository) are taken straight from [`postgres-simple`](../../ekbatan-integration-tests/postgres-simple); the wiring step at the end is what you'd typically run from `main()` or from a once-per-startup hook in your own framework.

If you'd rather have most of this generated for you, jump to your stack's guide: [Spring Boot](spring.md), [Quarkus](quarkus.md), or [Micronaut](micronaut.md).

## What we're wiring

- A `Wallet` model with a `deposit(...)` mutation that emits `WalletMoneyDepositedEvent`
- Three actions: `WalletCreateAction`, `WalletDepositAction`, `WalletCloseAction`
- A `WalletRepository`
- One `EventHandler<WalletMoneyDepositedEvent>` (in-process listen-to-yourself path)
- One `DistributedJob` (cluster-exclusive scheduled work)

Once wired, calling `executor.execute(...)` from anywhere — a controller, a CLI, a queue worker — runs the action atomically with its outbox row.

## The domain (unchanged whether you use DI or not)

```java
@AutoBuilder
public final class Wallet extends Model<Wallet, Id<Wallet>, WalletState> {

    public final UUID ownerId;
    public final Currency currency;
    public final BigDecimal balance;

    Wallet(WalletBuilder builder) {
        super(builder);
        this.ownerId  = Validate.notNull(builder.ownerId,  "ownerId cannot be null");
        this.currency = Validate.notNull(builder.currency, "currency cannot be null");
        this.balance  = Validate.notNull(builder.balance,  "balance cannot be null");
    }

    public static WalletBuilder createWallet(UUID ownerId, Currency currency, BigDecimal balance, Instant createdDate) {
        final var id = Id.random(Wallet.class);
        return WalletBuilder.wallet()
                .id(id)
                .state(OPENED)
                .ownerId(ownerId)
                .currency(currency)
                .balance(balance)
                .createdDate(createdDate)
                .withInitialVersion()
                .withEvent(new WalletCreatedEvent(id, ownerId, currency, balance));
    }

    @Override
    public WalletBuilder copy() {
        return WalletBuilder.wallet()
                .copyBase(this)
                .ownerId(ownerId)
                .currency(currency)
                .balance(balance);
    }

    public Wallet deposit(BigDecimal amount) {
        Validate.notNull(amount, "amount cannot be null");
        Validate.isTrue(amount.compareTo(BigDecimal.ZERO) > 0, "Deposit amount must be positive");

        final var newBalance = balance.add(amount);
        return copy()
                .withEvent(new WalletMoneyDepositedEvent(id, amount, newBalance))
                .balance(newBalance)
                .build();
    }

    public Wallet close() {
        if (state.equals(CLOSED)) {
            return this;
        }
        return copy()
                .withEvent(new WalletClosedEvent(id))
                .state(CLOSED)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        Wallet wallet = (Wallet) o;
        return ownerId.equals(wallet.ownerId)
                && currency.equals(wallet.currency)
                && balance.compareTo(wallet.balance) == 0;
    }
}
```

```java
public class WalletDepositAction extends Action<WalletDepositAction.Params, Wallet> {
    public record Params(Id<Wallet> walletId, BigDecimal amount) {}

    private final WalletRepository walletRepository;

    public WalletDepositAction(Clock clock, WalletRepository walletRepository) {
        super(clock);
        this.walletRepository = walletRepository;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) {
        var wallet  = walletRepository.getById(params.walletId().getValue());
        var updated = wallet.deposit(params.amount());
        return plan().update(updated);
    }
}
```

```java
public class WalletRepository extends ModelRepository<Wallet, WalletsRecord, Wallets, UUID> {

    public WalletRepository(DatabaseRegistry databaseRegistry) {
        super(Wallet.class, WALLETS, WALLETS.ID, databaseRegistry);
    }

    @Override
    public Wallet fromRecord(WalletsRecord r) {
        return WalletBuilder.wallet()
                .id(Id.of(Wallet.class, r.getId()))
                .version(r.getVersion())
                .state(WalletState.valueOf(r.getState()))
                .ownerId(r.getOwnerId())
                .currency(Currency.getInstance(r.getCurrency()))
                .balance(r.getBalance())
                .createdDate(r.getCreatedDate())
                .updatedDate(r.getUpdatedDate())
                .build();
    }

    @Override
    public WalletsRecord toRecord(Wallet w) {
        return new WalletsRecord(
                w.id.getValue(), w.version, w.state.name(),
                w.ownerId, w.currency.getCurrencyCode(), w.balance,
                w.createdDate, w.updatedDate);
    }
}
```

> **No `@EkbatanAction`, no `@EkbatanRepository` here.** Those annotations are *only* hints to a DI container. Without DI, the framework discovers nothing reflectively — you hand it instances directly via the registry builders below.

(An event handler and a distributed job are added below as plain classes too.)

## The full wiring

This is the part that DI normally does for you. Read it top to bottom — every line corresponds to one concept introduced elsewhere in the docs.

```java
import static io.ekbatan.core.action.ActionExecutor.Builder.actionExecutor;
import static io.ekbatan.core.action.ActionRegistry.Builder.actionRegistry;
import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;
import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static io.ekbatan.distributedjobs.JobRegistry.jobRegistry;
import static io.ekbatan.events.localeventhandler.EventHandlerRegistry.eventHandlerRegistry;
import static io.ekbatan.events.localeventhandler.job.EventFanoutJob.eventFanoutJob;
import static io.ekbatan.events.localeventhandler.job.EventHandlingJob.eventHandlingJob;
import io.ekbatan.flyway.FlywayMigrator;
import java.util.Map;
// …

public class Application {

    public static void main(String[] args) throws Exception {

        // ─── 1. Datasources ──────────────────────────────────────────────────
        // Two pools for the application data — primary for writes + transactional
        // reads, secondary for non-transactional reads (point at a read replica
        // in production, or at the same DB if you don't have one yet).
        var primary = dataSourceConfig()
                .jdbcUrl("jdbc:postgresql://primary:5432/wallets")
                .username("wallets_app")
                .password(System.getenv("APP_DB_PASSWORD"))
                // extra driver settings, each name as the driver spells it; passed to the
                // driver beside the URL and never printed (see "Connecting to the database")
                .dataSourceProperties(Map.of("ApplicationName", "wallet-service"))
                .maximumPoolSize(20)
                .build();

        var secondary = dataSourceConfig()
                .jdbcUrl("jdbc:postgresql://replica:5432/wallets")
                .username("wallets_app_ro")
                .password(System.getenv("APP_DB_PASSWORD"))
                .maximumPoolSize(20)
                .build();

        // A separate, smaller pool for db-scheduler — keeps polling traffic
        // off the application pool. Optional but recommended.
        var jobsPool = dataSourceConfig()
                .jdbcUrl("jdbc:postgresql://primary:5432/wallets")
                .username("wallets_app")
                .password(System.getenv("APP_DB_PASSWORD"))
                .maximumPoolSize(5)
                .build();

        // ─── 2. Run Flyway migrations ────────────────────────────────────────
        // FlywayMigrator connects the way the pools above do - same driver, same
        // settings - but on connections of its own, closed when it is done. It is
        // also GraalVM-native-image-aware.
        FlywayMigrator.migrate(primary);

        // ─── 3. TransactionManager + DatabaseRegistry ────────────────────────
        // Each shard gets its own TransactionManager. With one shard (the default),
        // the registry has a single entry. Sharding-aware code goes through
        // DatabaseRegistry; non-sharded code touches it the same way.
        var tm = new TransactionManager(
                hikariConnectionProvider(primary),
                hikariConnectionProvider(secondary),
                SQLDialect.POSTGRES);

        var databaseRegistry = databaseRegistry().withDatabase(tm).build();

        // ─── 4. Repositories ─────────────────────────────────────────────────
        var walletRepository = new WalletRepository(databaseRegistry);

        var repositoryRegistry = repositoryRegistry()
                .withModelRepository(Wallet.class, walletRepository)
                .build();

        // ─── 5. Actions ──────────────────────────────────────────────────────
        var clock        = Clock.systemUTC();
        var objectMapper = new ObjectMapper();

        var actionRegistry = actionRegistry()
                .withAction(WalletCreateAction.class,  new WalletCreateAction(clock))
                .withAction(WalletDepositAction.class, new WalletDepositAction(clock, walletRepository))
                .withAction(WalletCloseAction.class,   new WalletCloseAction(clock, walletRepository))
                .build();

        // ─── 6. Event handlers (optional — listen-to-yourself path) ──────────
        var depositHandler = new WalletMoneyDepositedEventHandler(notificationService);

        var eventHandlerRegistry = eventHandlerRegistry()
                .withHandler(depositHandler)
                .build();

        // ─── 7. ActionExecutor ───────────────────────────────────────────────
        // The thing application code calls. Owns the namespace (stamped on every
        // outbox row), the database registry, the registries above, and an
        // ObjectMapper for JSON serialization.
        //
        // The default eventPersister (SingleTableJsonEventPersister) writes every
        // event row with delivered=false — that's exactly what the in-process
        // fan-out path picks up, so no override is needed for the listen-to-yourself
        // wiring below. Call .eventPersister(...) only if you want a custom sink
        // (e.g. an encrypted-payload variant or an alternate outbox table).
        var executor = actionExecutor()
                .namespace("com.example.wallets")
                .databaseRegistry(databaseRegistry)
                .objectMapper(objectMapper)
                .repositoryRegistry(repositoryRegistry)
                .actionRegistry(actionRegistry)
                .build();

        // ─── 8. Distributed jobs (optional — cluster-exclusive scheduling) ───
        var jobRegistry = jobRegistry()
                .connectionProvider(hikariConnectionProvider(jobsPool))
                .withJob(new DailyWalletReportJob(reportService))
                // The fan-out and handling jobs are themselves DistributedJobs.
                // Register them here so the local-event-handler path actually runs.
                .withJob(eventFanoutJob()
                        .databaseRegistry(databaseRegistry)
                        .eventHandlerRegistry(eventHandlerRegistry)
                        .clock(clock)
                        .build())
                .withJob(eventHandlingJob()
                        .databaseRegistry(databaseRegistry)
                        .eventHandlerRegistry(eventHandlerRegistry)
                        .objectMapper(objectMapper)
                        .clock(clock)
                        .build())
                .pollInterval(Duration.ofSeconds(10))
                .heartbeatInterval(Duration.ofSeconds(30))
                .build();   // installs a JVM shutdown hook by default

        jobRegistry.start();

        // ─── 9. Use it ───────────────────────────────────────────────────────
        // From here on, anything that has a reference to `executor` can run actions.
        var deposited = executor.execute(
                () -> "alice",
                WalletDepositAction.class,
                new WalletDepositAction.Params(walletId, new BigDecimal("25.50")));

        System.out.println("New balance: " + deposited.balance);
    }
}
```

## What each block does

| Block | Concept | Doc |
|---|---|---|
| 1. Datasources | `DataSourceConfig` carries the login, the driver settings and the Hikari knobs, and resolves the dialect from the JDBC URL | [Connecting to the database](../database/connecting.md) covers every field; [Sharding](../database/sharding.md) the broader topology |
| 2. Migrations | `FlywayMigrator.migrate(...)` runs Flyway, native-image-aware | [Native image](../runtime/native-image.md) |
| 3. TM + registry | One `TransactionManager` per shard; `DatabaseRegistry` indexes them by `ShardIdentifier` | [The outbox](../concepts/outbox.md), [Sharding](../database/sharding.md) |
| 4. Repositories | One repository instance per persistable type, registered into a `RepositoryRegistry` | [Repositories](../database/repositories.md) |
| 5. Actions | One `Action` instance per concrete subclass — they're singletons, per-call state lives on the plan | [Actions](../concepts/actions.md) |
| 6. Event handlers | Built into an `EventHandlerRegistry` consumed by the fan-out + handling jobs | [Local event handler](../events/local-event-handler.md) |
| 7. `ActionExecutor` | The framework's central entry point — wraps every call in one transaction | [Actions](../concepts/actions.md), [The outbox](../concepts/outbox.md) |
| 8. `JobRegistry` | db-scheduler facade. The fan-out and handling jobs are themselves `DistributedJob`s registered here. Calling `.start()` is what kicks the schedulers off. | [Distributed jobs](../jobs/distributed-jobs.md) |
| 9. Use it | Application code calls `executor.execute(...)` exactly the same way regardless of how the executor was wired. | — |

## What changes if you skip a feature

The 9 blocks are arranged so you can drop any **optional** piece by deleting a single section:

- **No replica?** Pass the same `hikariConnectionProvider(primary)` to both arguments of `TransactionManager`.
- **No event handlers?** Drop block 6 and drop the `eventFanoutJob()` / `eventHandlingJob()` registrations in block 8. The `ActionExecutor` keeps writing events with `delivered=false`; without the fan-out job they simply sit in the outbox unread, which is the intended state for deployments that consume the outbox externally (e.g. via Debezium/Kafka).
- **No background jobs at all?** Drop block 8 entirely. (Drop block 6 too — without the jobs there's no dispatch.)
- **No sharding?** That's already what's shown above. The `DatabaseRegistry` has one entry; the framework treats this as the default shard with zero overhead.
- **Multiple shards?** Repeat block 3 with extra `TransactionManager`s and `withDatabase(...)` calls. Set `withDefaultDatabase(...)` to control the fallback shard. Switch repositories to `EmbeddedBitsShardingStrategy` (or your own custom strategy). See [Sharding](../database/sharding.md).

## Same thing with less code

Most of the wiring above (datasources, registry construction, lifecycle) is mechanical glue your application doesn't really care about. If you're already running on Spring Boot, Quarkus, or Micronaut, the framework's DI integration replaces blocks 1–8 with a small dependency block and four `@Ekbatan*` annotations on the classes you write. See [Spring Boot](spring.md), [Quarkus](quarkus.md), or [Micronaut](micronaut.md).

## See also

- [Wiring with Spring Boot](spring.md) / [Quarkus](quarkus.md) / [Micronaut](micronaut.md) — the same end state, in roughly 10% of the code
- [Actions](../concepts/actions.md) — what `ActionExecutor` actually does when you call `execute(...)`
- [Sharding](../database/sharding.md) — extending block 3 to multiple shards
- [Local event handler](../events/local-event-handler.md) — the fan-out / dispatch jobs registered in block 8
- [Distributed jobs](../jobs/distributed-jobs.md) — `JobRegistry` and `DistributedJob`
