package io.ekbatan.core.persistence;

import io.ekbatan.core.internal.Validate;
import io.ekbatan.core.shard.ShardIdentifier;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.sql.Connection;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-shard transaction boundary. Owns the primary + secondary {@link ConnectionProvider}s,
 * the SQL dialect, and the {@link ScopedValue} that binds the current transaction to the
 * calling thread so nested calls inside the same {@code inTransaction*} block share it.
 *
 * <p>{@link io.ekbatan.core.action.ActionExecutor} opens exactly one transaction per shard
 * via {@link #inTransactionChecked(CheckedFunction)} (or {@link #inTransactionChecked(CheckedConsumer)}
 * for void blocks) and persists every staged change within it. Repository code reads the
 * current {@link DSLContext} via {@link #currentTransactionDbContext()} - when called outside
 * a transaction, the {@code Optional} is empty and the repository's protected
 * {@code db}/{@code txDbElseDb} accessors fall back to non-transactional execution.
 *
 * <p>Each transaction is emitted as an {@code ekbatan.transaction} OpenTelemetry span tagged
 * with the shard's group/member; rollback paths set the span status to {@code ERROR} and
 * record the exception.
 *
 * <p>The unchecked variants ({@link #inTransaction(java.util.function.Function)} /
 * {@link #inTransaction(java.util.function.Consumer)}) wrap any checked exception in a
 * {@code RuntimeException}; use the {@code Checked} variants when the block can throw a
 * meaningful checked exception that should propagate.
 */
public class TransactionManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionManager.class);
    private static final Tracer TRACER = GlobalOpenTelemetry.get().getTracer("io.ekbatan.core", "1.1.0");

    /** The SQL dialect for this shard; consulted by repositories for dialect-specific SQL. */
    public final SQLDialect dialect;

    /** The shard this transaction manager belongs to. */
    public final ShardIdentifier shardIdentifier;

    /** Primary (writer) connection provider for this shard. */
    public final ConnectionProvider primaryConnectionProvider;

    /** Secondary (read-replica) connection provider; falls back to primary if no replica is configured. */
    public final ConnectionProvider secondaryConnectionProvider;

    private final ScopedValue<Transaction> currentTransaction;

    /**
     * Convenience constructor that defaults the shard identifier to {@link ShardIdentifier#DEFAULT}.
     *
     * @param primaryConnectionProvider primary (writer) connection provider.
     * @param secondaryConnectionProvider secondary (read-replica) connection provider.
     * @param dialect the SQL dialect.
     */
    public TransactionManager(
            ConnectionProvider primaryConnectionProvider,
            ConnectionProvider secondaryConnectionProvider,
            SQLDialect dialect) {
        this(primaryConnectionProvider, secondaryConnectionProvider, dialect, ShardIdentifier.DEFAULT);
    }

    /**
     * Primary constructor.
     *
     * @param primaryConnectionProvider primary (writer) connection provider.
     * @param secondaryConnectionProvider secondary (read-replica) connection provider; pass the
     *     same instance as {@code primaryConnectionProvider} if no replica is configured.
     * @param dialect the SQL dialect.
     * @param shardIdentifier the shard this transaction manager belongs to.
     */
    public TransactionManager(
            ConnectionProvider primaryConnectionProvider,
            ConnectionProvider secondaryConnectionProvider,
            SQLDialect dialect,
            ShardIdentifier shardIdentifier) {
        this.dialect = Validate.notNull(dialect, "dialect should not be null");
        this.primaryConnectionProvider =
                Validate.notNull(primaryConnectionProvider, "primaryConnectionProvider should not be null");
        this.secondaryConnectionProvider =
                Validate.notNull(secondaryConnectionProvider, "secondaryConnectionProvider should not be null");
        this.shardIdentifier = Validate.notNull(shardIdentifier, "shardIdentifier should not be null");
        this.currentTransaction = ScopedValue.newInstance();
    }

    /**
     * Runs {@code block} inside a fresh transaction; commits on normal return, rolls back on
     * any thrown exception. Checked exceptions thrown from {@code block} propagate unchanged.
     *
     * @param block the unit of work; receives the in-transaction {@link DSLContext}.
     * @param <R> the block's return type.
     * @return whatever {@code block} returned.
     * @throws Exception thrown by {@code block}; the transaction is rolled back.
     */
    public <R> R inTransactionChecked(CheckedFunction<DSLContext, R> block) throws Exception {
        final var span = TRACER.spanBuilder("ekbatan.transaction")
                .setAttribute("ekbatan.shard.group", shardIdentifier.group)
                .setAttribute("ekbatan.shard.member", shardIdentifier.member)
                .startSpan();
        try (var _ = span.makeCurrent()) {
            return executeInTransaction(block, span);
        } finally {
            span.end();
        }
    }

    /**
     * Like {@link #inTransactionChecked(CheckedFunction)} but for void blocks.
     *
     * @param block the unit of work; receives the in-transaction {@link DSLContext}.
     * @throws Exception thrown by {@code block}; the transaction is rolled back.
     */
    public void inTransactionChecked(CheckedConsumer<DSLContext> block) throws Exception {
        inTransactionChecked(dslContext -> {
            block.accept(dslContext);
            return null;
        });
    }

    /**
     * Unchecked-exception variant of {@link #inTransactionChecked(CheckedFunction)}: any
     * checked exception thrown by {@code block} is wrapped in {@code RuntimeException}.
     *
     * @param block the unit of work.
     * @param <R> the block's return type.
     * @return whatever {@code block} returned.
     */
    public <R> R inTransaction(Function<DSLContext, R> block) {
        try {
            return inTransactionChecked(block::apply);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Unchecked-exception variant of {@link #inTransactionChecked(CheckedConsumer)}: any
     * checked exception thrown by {@code block} is wrapped in {@code RuntimeException}.
     *
     * @param block the unit of work.
     */
    public void inTransaction(Consumer<DSLContext> block) {
        try {
            inTransactionChecked(block::accept);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private <R> R executeInTransaction(CheckedFunction<DSLContext, R> block, Span span) throws Exception {
        Connection newConn = null;
        Transaction transaction = null;
        try {
            newConn = primaryConnectionProvider.acquire();
            transaction = new Transaction(newConn, dialect);
            final var transactionRef = transaction;
            return ScopedValue.where(currentTransaction, transaction).call(() -> {
                try {
                    transactionRef.begin();
                    LOG.debug("Transaction started [shard=({},{})]", shardIdentifier.group, shardIdentifier.member);
                    final var result = block.apply(transactionRef.dslContext());
                    transactionRef.commit();
                    LOG.debug("Transaction committed [shard=({},{})]", shardIdentifier.group, shardIdentifier.member);
                    return result;
                } catch (Throwable t) {
                    // Throwable, not Exception. An Error from the block - a NoClassDefFoundError
                    // from a missing optional dependency, an AssertionError under -ea, a
                    // StackOverflowError from recursive domain code - is still a failed
                    // transaction. Caught as Exception it skipped every line below: no rollback,
                    // so `dirty` stayed false and the finally RELEASED a connection carrying an
                    // open transaction with autoCommit=false (HikariCP's own close() rolls that
                    // back, so nothing corrupted - but the framework was relying on pool internals
                    // rather than its own contract); no WARN; and, worst of the three, no
                    // span.setStatus(ERROR) or recordException, so the trace showed a transaction
                    // that had died as though it had succeeded.
                    // Nothing is swallowed: `throw t` rethrows unchanged.
                    transactionRef.rollback();
                    LOG.warn(
                            "Transaction rolled back [shard=({},{})]: {}",
                            shardIdentifier.group,
                            shardIdentifier.member,
                            t.getClass().getSimpleName());
                    span.setStatus(StatusCode.ERROR, t.getMessage());
                    span.recordException(t);
                    throw t;
                }
            });
        } finally {
            if (newConn != null) {
                if (transaction != null && transaction.isDirty()) {
                    LOG.warn(
                            "Evicting connection from pool - rollback or autocommit-reset failed leaving it in unknown state [shard=({},{})]",
                            shardIdentifier.group,
                            shardIdentifier.member);
                    primaryConnectionProvider.evict(newConn);
                } else {
                    primaryConnectionProvider.release(newConn);
                }
            }
        }
    }

    /**
     * {@return the in-flight transaction's {@link DSLContext} for this shard, or empty if no
     *     transaction is currently bound to the calling thread}
     */
    public Optional<DSLContext> currentTransactionDbContext() {
        return currentTransaction.isBound()
                ? Optional.of(currentTransaction.get().dslContext())
                : Optional.empty();
    }

    /**
     * Closes the primary and (distinct) secondary connection providers. Safe to call multiple
     * times - Hikari's {@code close()} is idempotent.
     */
    @Override
    public void close() {
        primaryConnectionProvider.close();
        if (secondaryConnectionProvider != primaryConnectionProvider) {
            secondaryConnectionProvider.close();
        }
    }

    /**
     * Variant of {@link Consumer} that may throw a checked exception.
     *
     * @param <T> the input type.
     */
    @FunctionalInterface
    public interface CheckedConsumer<T> {
        /**
         * Performs the operation on {@code t}.
         *
         * @param t the input.
         * @throws Exception any checked exception thrown by the operation.
         */
        void accept(T t) throws Exception;
    }

    /**
     * Variant of {@link Function} that may throw a checked exception.
     *
     * @param <T> the input type.
     * @param <R> the return type.
     */
    @FunctionalInterface
    public interface CheckedFunction<T, R> {
        /**
         * Applies the function to {@code t}.
         *
         * @param t the input.
         * @return the function result.
         * @throws Exception any checked exception thrown by the function.
         */
        R apply(T t) throws Exception;
    }
}
