package io.ekbatan.core.repository;

import static java.lang.String.format;

import io.ekbatan.core.domain.Persistable;
import io.ekbatan.core.internal.CollectionUtils;
import io.ekbatan.core.internal.Validate;
import io.ekbatan.core.repository.exception.EntityNotFoundException;
import io.ekbatan.core.repository.exception.StaleRecordException;
import io.ekbatan.core.shard.CrossShardException;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.NoShardingStrategy;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.core.shard.ShardingStrategy;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.RowN;
import org.jooq.SQLDialect;
import org.jooq.Select;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableRecord;
import org.jooq.Update;
import org.jooq.UpdateConditionStep;
import org.jooq.UpdateSetMoreStep;
import org.jooq.UpdateSetStep;
import org.jooq.impl.DSL;

/**
 * Abstract base repository implementation using jOOQ. Subclasses provide the persistable type,
 * jOOQ generated record type, jOOQ generated table type, and database-side ID type as the four
 * type parameters; the framework supplies the CRUD machinery, optimistic locking, sharding-
 * aware routing, and a rich query surface.
 *
 * @param <PERSISTABLE> the domain object type this repository persists (a {@link Persistable}).
 * @param <RECORD>      the jOOQ-generated {@link TableRecord} type for the table.
 * @param <TABLE>       the jOOQ-generated {@link Table} type.
 * @param <DB_ID>       the database-side identifier type (e.g. {@code UUID}, {@code Long}); typed
 *                      separately from the domain-side ID so the framework can carry custom wrapper types like
 *                      {@code Id<Wallet>} on the domain object while still emitting plain UUIDs to jOOQ.
 */
public abstract class AbstractRepository<
                PERSISTABLE extends Persistable<?>,
                RECORD extends TableRecord<?>,
                TABLE extends Table<RECORD>,
                DB_ID extends Comparable<?>>
        implements Repository<PERSISTABLE> {

    /**
     * The registry of per-shard connection pools and transaction managers; supplied by the application.
     */
    public final DatabaseRegistry databaseRegistry;

    /**
     * Routes each row to a shard; defaults to {@link NoShardingStrategy} (single-shard) when not specified.
     */
    public final ShardingStrategy<DB_ID> shardingStrategy;

    /**
     * The jOOQ {@link Table} this repository reads/writes; typically a generated {@code Tables.MY_TABLE} singleton.
     */
    protected final TABLE table;

    /**
     * The jOOQ {@link TableField} for the primary key; used by {@link #findById}, {@link #getById}, etc.
     */
    public final TableField<RECORD, DB_ID> idField;

    /**
     * The jOOQ {@link TableField} for the optimistic-locking version column ({@code version}).
     */
    protected final TableField<RECORD, Long> versionField;

    /**
     * The jOOQ {@link TableField} for the discriminator/state column ({@code state}).
     */
    protected final TableField<RECORD, String> stateField;

    /**
     * The runtime {@link Class} of the persistable type; needed for reflective {@code nextVersion} and event emission.
     */
    public final Class<PERSISTABLE> domainClass;

    private static final Tracer TRACER = GlobalOpenTelemetry.get().getTracer("io.ekbatan.core", "1.1.2");

    private static final String VERSION_FIELD_NAME = "version";
    private static final String STATE_FIELD_NAME = "state";

    /**
     * Convenience constructor for single-shard repositories - equivalent to passing
     * {@link NoShardingStrategy}.
     *
     * @param domainClass      runtime {@link Class} of the persistable type.
     * @param table            the jOOQ-generated table.
     * @param idField          the jOOQ-generated id field.
     * @param databaseRegistry the registry of connection pools / transaction managers.
     */
    protected AbstractRepository(
            Class<PERSISTABLE> domainClass,
            TABLE table,
            TableField<RECORD, DB_ID> idField,
            DatabaseRegistry databaseRegistry) {
        this(domainClass, table, idField, databaseRegistry, new NoShardingStrategy<>());
    }

    /**
     * Primary constructor for sharded repositories.
     *
     * @param domainClass      runtime {@link Class} of the persistable type.
     * @param table            the jOOQ-generated table.
     * @param idField          the jOOQ-generated id field.
     * @param databaseRegistry the registry of connection pools / transaction managers.
     * @param shardingStrategy strategy that maps each row's ID to a {@link ShardIdentifier}.
     */
    protected AbstractRepository(
            Class<PERSISTABLE> domainClass,
            TABLE table,
            TableField<RECORD, DB_ID> idField,
            DatabaseRegistry databaseRegistry,
            ShardingStrategy<DB_ID> shardingStrategy) {
        this.databaseRegistry = Validate.notNull(databaseRegistry, "databaseRegistry cannot be null");
        this.shardingStrategy = Validate.notNull(shardingStrategy, "shardingStrategy cannot be null");
        this.table = table;
        this.idField = idField;
        this.domainClass = Validate.notNull(domainClass, "domainClass cannot be null");

        this.versionField = Validate.notNull(
                resolveField(table, VERSION_FIELD_NAME, Long.class),
                "Table " + table.getName() + " must have a 'version' field for optimistic locking");

        this.stateField = Validate.notNull(
                resolveField(table, STATE_FIELD_NAME, String.class),
                "Table " + table.getName() + " must have a 'state' field");
    }

    @Override
    public ShardingStrategy<DB_ID> shardingStrategy() {
        return shardingStrategy;
    }

    /**
     * {@return the human-readable name of the domain type, used in error messages}
     */
    protected abstract String getDomainTypeName();

    /**
     * Maps a jOOQ-generated record into a domain object.
     *
     * @param record the database row.
     * @return the corresponding domain object.
     */
    public abstract PERSISTABLE fromRecord(RECORD record);

    /**
     * Maps a domain object into a jOOQ-generated record.
     *
     * @param domainObject the domain object.
     * @return a record ready to insert/update.
     */
    public abstract RECORD toRecord(PERSISTABLE domainObject);

    // --- db() variants ---

    /**
     * {@return the primary writer {@link DSLContext} for the default shard (non-transactional)}
     */
    protected DSLContext db() {
        return databaseRegistry.primary.get(databaseRegistry.defaultShard);
    }

    /**
     * Resolves the primary writer context for the shard that owns the given ID.
     *
     * @param id the database-side identifier.
     * @return the primary {@link DSLContext} for the resolved shard.
     */
    protected DSLContext db(DB_ID id) {
        return db(effectiveShard(id));
    }

    /**
     * Resolves the primary writer context for the shard that owns the given domain object.
     *
     * @param p the domain object.
     * @return the primary {@link DSLContext} for the resolved shard.
     */
    protected DSLContext db(PERSISTABLE p) {
        return db(effectiveShard(p));
    }

    /**
     * Returns the primary writer context for the given shard explicitly.
     *
     * @param shard the target shard.
     * @return the primary {@link DSLContext}.
     */
    protected DSLContext db(ShardIdentifier shard) {
        return databaseRegistry.primary.get(shard);
    }

    /**
     * {@return every primary writer context, one per shard (scatter-gather entrypoint for queries spanning shards)}
     */
    protected Collection<DSLContext> dbs() {
        if (shardingStrategy instanceof NoShardingStrategy<?>) {
            return List.of(db());
        }
        return databaseRegistry.primary.values();
    }

    // --- readonlyDb() variants ---

    /**
     * {@return the read-replica {@link DSLContext} for the default shard; falls back to primary if no replica is configured}
     */
    protected DSLContext readonlyDb() {
        return databaseRegistry.secondary.get(databaseRegistry.defaultShard);
    }

    /**
     * Resolves the read-replica context for the shard that owns the given ID.
     *
     * @param id the database-side identifier.
     * @return the secondary {@link DSLContext}.
     */
    protected DSLContext readonlyDb(DB_ID id) {
        return readonlyDb(effectiveShard(id));
    }

    /**
     * Returns the read-replica context for the given shard explicitly.
     *
     * @param shard the target shard.
     * @return the secondary {@link DSLContext}.
     */
    protected DSLContext readonlyDb(ShardIdentifier shard) {
        return databaseRegistry.secondary.get(shard);
    }

    /**
     * {@return every read-replica context, one per shard}
     */
    protected Collection<DSLContext> readonlyDbs() {
        if (shardingStrategy instanceof NoShardingStrategy<?>) {
            return List.of(readonlyDb());
        }
        return databaseRegistry.secondary.values();
    }

    // --- txDb() variants ---

    /**
     * {@return the in-flight transaction's {@link DSLContext} for the default shard, or empty if no transaction is bound}
     */
    protected Optional<DSLContext> txDb() {
        return databaseRegistry.defaultTransactionManager().currentTransactionDbContext();
    }

    /**
     * Returns the in-flight transaction's context for the shard owning the given ID, if any.
     *
     * @param id the database-side identifier.
     * @return the bound transactional {@link DSLContext}, or empty.
     */
    protected Optional<DSLContext> txDb(DB_ID id) {
        return txDb(effectiveShard(id));
    }

    /**
     * Returns the in-flight transaction's context for the shard owning the given domain object, if any.
     *
     * @param p the domain object.
     * @return the bound transactional {@link DSLContext}, or empty.
     */
    protected Optional<DSLContext> txDb(PERSISTABLE p) {
        return txDb(effectiveShard(p));
    }

    /**
     * Returns the in-flight transaction's context for the given shard, if any.
     *
     * @param shard the target shard.
     * @return the bound transactional {@link DSLContext}, or empty.
     */
    protected Optional<DSLContext> txDb(ShardIdentifier shard) {
        return databaseRegistry.transactionManager(shard).currentTransactionDbContext();
    }

    // --- txDbElseDb() variants ---

    /**
     * {@return the in-flight transaction's context for the default shard if bound, otherwise a non-transactional primary context}
     */
    protected DSLContext txDbElseDb() {
        return txDb().orElseGet(this::db);
    }

    /**
     * Prefers the in-flight transaction's context; falls back to a non-transactional primary context.
     *
     * @param id the database-side identifier.
     * @return the chosen {@link DSLContext}.
     */
    protected DSLContext txDbElseDb(DB_ID id) {
        return txDb(id).orElseGet(() -> db(id));
    }

    /**
     * Prefers the in-flight transaction's context; falls back to a non-transactional primary context.
     *
     * @param p the domain object.
     * @return the chosen {@link DSLContext}.
     */
    protected DSLContext txDbElseDb(PERSISTABLE p) {
        return txDb(p).orElseGet(() -> db(p));
    }

    /**
     * Prefers the in-flight transaction's context; falls back to a non-transactional primary context.
     *
     * @param shard the target shard.
     * @return the chosen {@link DSLContext}.
     */
    protected DSLContext txDbElseDb(ShardIdentifier shard) {
        return txDb(shard).orElseGet(() -> db(shard));
    }

    // --- dialect helper ---

    private SQLDialect dialect(ShardIdentifier shard) {
        return databaseRegistry.transactionManager(shard).dialect;
    }

    // --- CRUD operations ---

    @Override
    public PERSISTABLE add(PERSISTABLE domainObject) {
        Validate.notNull(domainObject, getDomainTypeName() + " cannot be null");
        var shard = effectiveShard(domainObject);
        var dialect = dialect(shard);

        if (dialect.equals(SQLDialect.MYSQL)) {
            txDbElseDb(shard).insertInto(table).set(toRecord(domainObject)).execute();
            return domainObject;
        }

        return txDbElseDb(shard)
                .insertInto(table)
                .set(toRecord(domainObject))
                .returning()
                .fetchOptional()
                .map(this::fromRecord)
                .orElseThrow(() -> new IllegalStateException(
                        "Failed to insert " + getDomainTypeName() + " with ID: " + domainObject.getId()));
    }

    @Override
    public void addNoResult(PERSISTABLE domainObject) {
        Validate.notNull(domainObject, getDomainTypeName() + " cannot be null");
        var shard = effectiveShard(domainObject);
        txDbElseDb(shard).insertInto(table).set(toRecord(domainObject)).execute();
    }

    @Override
    public List<PERSISTABLE> addAll(Collection<PERSISTABLE> domainObjects) {
        Validate.notNull(domainObjects, getDomainTypeName() + "s collection cannot be null");

        if (CollectionUtils.isEmpty(domainObjects)) {
            return List.of();
        }

        if (domainObjects.size() == 1) {
            return List.of(add(domainObjects.iterator().next()));
        }

        final var records = domainObjects.stream()
                .map(d -> {
                    Validate.notNull(d, getDomainTypeName() + " in collection cannot be null");
                    return toRecord(d);
                })
                .toList();

        final var shard = effectiveShard(domainObjects);
        final var dialect = dialect(shard);
        final var fields = records.getFirst().fields();
        final var insert = txDbElseDb(shard).insertInto(table, fields);

        for (RECORD record : records) {
            final var values = Arrays.stream(fields).map(record::get).toArray();
            insert.values(values);
        }

        if (dialect.equals(SQLDialect.MYSQL)) {
            insert.execute();
            return List.copyOf(domainObjects);
        }

        final var addedObjects = insert.returning().fetch().map(this::fromRecord);

        if (addedObjects.size() != domainObjects.size()) {
            throw new IllegalStateException(format(
                    "%s %ss were queried for insert, while %s rows were inserted",
                    domainObjects.size(), getDomainTypeName(), addedObjects.size()));
        }

        return addedObjects;
    }

    @Override
    public void addAllNoResult(Collection<PERSISTABLE> domainObjects) {
        Validate.notNull(domainObjects, getDomainTypeName() + "s collection cannot be null");
        if (CollectionUtils.isEmpty(domainObjects)) {
            return;
        }

        final var span = TRACER.spanBuilder("ekbatan.repository")
                .setAttribute("db.operation.name", "BATCH_INSERT")
                .setAttribute("ekbatan.entity.type", domainClass.getSimpleName())
                .setAttribute("ekbatan.batch.size", domainObjects.size())
                .startSpan();
        try (var _ = span.makeCurrent()) {
            final var records = domainObjects.stream().map(this::toRecord).toList();
            final var fields = records.getFirst().fields();
            final var shard = effectiveShard(domainObjects);
            final var insert = txDbElseDb(shard).insertInto(table, fields);

            records.forEach(record ->
                    insert.values(Arrays.stream(fields).map(record::get).toArray()));
            insert.execute();
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public PERSISTABLE update(PERSISTABLE domainObject) {
        Validate.notNull(domainObject, getDomainTypeName() + " cannot be null");
        Validate.notNull(domainObject.getId(), getDomainTypeName() + " ID cannot be null for update");
        Long currentVersion = domainObject.getVersion();
        Validate.notNull(currentVersion, getDomainTypeName() + " must have a non-null version for optimistic locking");

        final RECORD record = toRecord(domainObject);
        final Long newVersion = currentVersion + 1;
        record.set(versionField, newVersion);
        var shard = effectiveShard(domainObject);
        var dialect = dialect(shard);

        if (dialect.equals(SQLDialect.MYSQL)) {
            final var affectedRows = txDbElseDb(shard)
                    .update(table)
                    .set(record)
                    .where(idField.eq(record.get(idField)))
                    .and(versionField.eq(currentVersion))
                    .execute();

            if (affectedRows == 0) {
                throw new StaleRecordException(format(
                        "%s %s[id=%s, version=%d] was concurrently modified or not found",
                        getDomainTypeName(), domainClass.getSimpleName(), domainObject.getId(), currentVersion));
            }
            return (PERSISTABLE) domainObject.nextVersion();
        }

        var updatedRecord = txDbElseDb(shard)
                .update(table)
                .set(record)
                .where(idField.eq(record.get(idField)))
                .and(versionField.eq(currentVersion))
                .returning()
                .fetchOptional();

        if (updatedRecord.isEmpty()) {
            throw new StaleRecordException(format(
                    "%s %s[id=%s, version=%d] was concurrently modified or not found",
                    getDomainTypeName(), domainClass.getSimpleName(), domainObject.getId(), currentVersion));
        }

        return updatedRecord.map(this::fromRecord).orElseThrow();
    }

    @Override
    public void updateNoResult(PERSISTABLE domainObject) {
        Validate.notNull(domainObject, getDomainTypeName() + " cannot be null");
        Validate.notNull(domainObject.getId(), getDomainTypeName() + " ID cannot be null for update");
        Long currentVersion = domainObject.getVersion();
        Validate.notNull(currentVersion, getDomainTypeName() + " must have a non-null version for optimistic locking");

        final RECORD record = toRecord(domainObject);
        final Long newVersion = currentVersion + 1;
        record.set(versionField, newVersion);

        final var shard = effectiveShard(domainObject);

        int affectedRows = txDbElseDb(shard)
                .update(table)
                .set(record)
                .where(idField.eq(record.get(idField)))
                .and(versionField.eq(currentVersion))
                .execute();

        if (affectedRows > 1) {
            throw new IllegalStateException("More than one row was updated, which was not expected");
        }

        if (affectedRows < 1) {
            throw new StaleRecordException(format(
                    "%s %s[id=%s, version=%d] was concurrently modified or not found",
                    getDomainTypeName(), domainClass.getSimpleName(), domainObject.getId(), currentVersion));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PERSISTABLE> updateAll(Collection<PERSISTABLE> domainObjects) {
        Validate.notNull(domainObjects, getDomainTypeName() + "s collection cannot be null");
        if (domainObjects.isEmpty()) {
            return List.of();
        }
        if (domainObjects.size() == 1) {
            return List.of(update(domainObjects.iterator().next()));
        }

        final var shard = effectiveShard(domainObjects);
        final var dialect = dialect(shard);
        final List<PERSISTABLE> updatedObjects;
        if (dialect.equals(SQLDialect.MARIADB) || dialect.equals(SQLDialect.MYSQL)) {
            int affectedRows = buildUpdateAllQueryMariadb(shard, domainObjects).execute();

            if (affectedRows < domainObjects.size()) {
                throw new StaleRecordException(format(
                        "Expected to update %d records but only updated %d", domainObjects.size(), affectedRows));
            }

            updatedObjects = domainObjects.stream()
                    .map(d -> (PERSISTABLE) d.nextVersion())
                    .toList();
        } else {
            var updateQuery = buildUpdateAllQuery(shard, domainObjects).returning();
            updatedObjects = updateQuery.fetch().map(this::fromRecord);
        }

        if (updatedObjects.size() != domainObjects.size()) {
            throw new StaleRecordException(format(
                    "Expected to update %d records but only updated %d", domainObjects.size(), updatedObjects.size()));
        }

        return updatedObjects;
    }

    @Override
    public void updateAllNoResult(Collection<PERSISTABLE> domainObjects) {
        Validate.notNull(domainObjects, getDomainTypeName() + "s collection cannot be null");
        if (domainObjects.isEmpty()) {
            return;
        }

        final var span = TRACER.spanBuilder("ekbatan.repository")
                .setAttribute("db.operation.name", "BATCH_UPDATE")
                .setAttribute("ekbatan.entity.type", domainClass.getSimpleName())
                .setAttribute("ekbatan.batch.size", (long) domainObjects.size())
                .startSpan();
        try (var _ = span.makeCurrent()) {
            if (domainObjects.size() == 1) {
                updateNoResult(domainObjects.iterator().next());
                return;
            }

            int affectedRows;
            var shard = effectiveShard(domainObjects);
            var dialect = dialect(shard);
            if (dialect.equals(SQLDialect.MARIADB) || dialect.equals(SQLDialect.MYSQL)) {
                affectedRows = buildUpdateAllQueryMariadb(shard, domainObjects).execute();
            } else {
                affectedRows = buildUpdateAllQuery(shard, domainObjects).execute();
            }

            if (affectedRows != domainObjects.size()) {
                throw new StaleRecordException(format(
                        "Expected to update %d records but only updated %d", domainObjects.size(), affectedRows));
            }
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private UpdateConditionStep<RECORD> buildUpdateAllQuery(
            ShardIdentifier shard, Collection<PERSISTABLE> domainObjects) {
        final var records = domainObjects.stream().map(this::toRecord).toList();
        final var fields = records.getFirst().fields();

        // Bind each value against its target field rather than letting DSL.row(Object...) re-infer
        // a DataType from the value's runtime class - that inference discards the field's declared
        // DataType and any attached Converter. DSL.row passes Fields through untouched, so wrapping
        // each value in DSL.val(value, field) preserves both. Mirrors buildUpdateAllQueryMariadb.
        final var rows = records.stream()
                .map(record -> DSL.row(Arrays.stream(fields)
                        .map(field -> (Object) DSL.val(record.get(field), field))
                        .toArray()))
                .toArray(RowN[]::new);

        final var columnNames = Arrays.stream(fields).map(Field::getName).toArray(String[]::new);

        var values = DSL.values(rows).as("v", columnNames);

        var update = txDbElseDb(shard)
                .update(table)
                .set(versionField, values.field(versionField).plus(1));

        for (var field : fields) {
            if (!field.equals(versionField)) {
                update = setField(update, field, values);
            }
        }

        return update.from(values)
                .where(idField.eq(values.field(idField)))
                .and(versionField.eq(values.field(versionField)));
    }

    @SuppressWarnings("unchecked")
    private Update<?> buildUpdateAllQueryMariadb(ShardIdentifier shard, Collection<PERSISTABLE> domainObjects) {
        final var records = domainObjects.stream().map(this::toRecord).toList();
        final var fields = records.getFirst().fields();

        Select<Record> subquery = null;

        for (var record : records) {
            final var selectFields = new ArrayList<Field<?>>(fields.length);
            for (var field : fields) {
                selectFields.add(DSL.val(record.get(field), field).as(field.getName()));
            }
            final var select = DSL.select(selectFields);
            subquery = (subquery == null) ? select : subquery.unionAll(select);
        }

        final var v = subquery.asTable("v");

        final var joinCondition = idField.eq(v.field(idField.getName(), idField.getType()))
                .and(versionField.eq(v.field(versionField.getName(), versionField.getType())));

        final var joinedTable = table.join(v).on(joinCondition);

        var update = txDbElseDb(shard).update(joinedTable);

        var vVersion = v.field(versionField.getName(), versionField.getType());
        var step = update.set(versionField, vVersion.plus(1));

        for (var field : fields) {
            if (!field.equals(versionField)) {
                step = setField(step, field, v);
            }
        }

        return step;
    }

    /**
     * Looks up a single row by primary key on the shard that owns it. Soft-deleted rows (state =
     * {@code "DELETED"}) are excluded.
     *
     * @param id the primary key value.
     * @return the matching domain object, or empty if not found / soft-deleted.
     */
    public Optional<PERSISTABLE> findById(DB_ID id) {
        return Optional.ofNullable(db(id).selectFrom(table)
                        .where(idField.eq(id).and(notDeleted()))
                        .fetchOne())
                .map(this::fromRecord);
    }

    /**
     * Like {@link #findById} but raises {@link EntityNotFoundException} instead of returning empty.
     *
     * @param id the primary key value.
     * @return the matching domain object.
     */
    public PERSISTABLE getById(DB_ID id) {
        return findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        format("No entity found with id: %s[id=%s]", domainClass.getSimpleName(), id)));
    }

    /**
     * Batched lookup by primary key. Groups the IDs by shard so each shard's read is a single
     * round-trip; uses a dialect-appropriate {@code IN} or {@code = ANY(?)} clause. Returns
     * matching rows in arbitrary order; missing IDs are silently omitted.
     *
     * @param ids the primary key values.
     * @return the matching domain objects.
     */
    public List<PERSISTABLE> findAllByIds(Collection<DB_ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        final var uniqueIds = new LinkedHashSet<>(ids);
        var idsByShard = groupIdsByShard(uniqueIds);

        return idsByShard.entrySet().stream()
                .flatMap(entry -> {
                    var shard = entry.getKey();
                    var dialect = dialect(shard);
                    var ctx = db(shard);
                    var shardIds = entry.getValue();
                    if (dialect.equals(SQLDialect.MYSQL)) {
                        return ctx
                                .selectFrom(table)
                                .where(idField.in(shardIds))
                                .and(notDeleted())
                                .fetch()
                                .map(this::fromRecord)
                                .stream();
                    }
                    return ctx
                            .selectFrom(table)
                            .where(idField.eq(DSL.any(
                                    DSL.val(shardIds, idField.getDataType().getArrayDataType()))))
                            .and(notDeleted())
                            .fetch()
                            .map(this::fromRecord)
                            .stream();
                })
                .toList();
    }

    @Override
    public List<PERSISTABLE> findAll() {
        return dbs().stream()
                .flatMap(ctx -> ctx.selectFrom(table).where(notDeleted()).fetch().map(this::fromRecord).stream())
                .toList();
    }

    /**
     * Existence check by primary key (does not load the row).
     *
     * @param id the primary key value.
     * @return {@code true} if a non-soft-deleted row with this id exists on its owning shard.
     */
    public boolean existsById(DB_ID id) {
        final var shard = effectiveShard(id);
        var ctx = db(shard);
        return ctx.fetchExists(ctx.selectOne().from(table).where(idField.eq(id).and(notDeleted())));
    }

    /**
     * {@return count of non-soft-deleted rows in this table, summed across all shards}
     */
    public long count() {
        return dbs().stream()
                .mapToLong(ctx -> ctx.fetchCount(table, notDeleted()))
                .sum();
    }

    /**
     * Count of non-soft-deleted rows matching a custom condition, summed across all shards.
     *
     * @param condition the jOOQ {@link Condition} to AND with the soft-delete filter.
     * @return the total count.
     */
    public long countWhere(Condition condition) {
        return dbs().stream()
                .mapToLong(ctx -> ctx.fetchCount(table, condition.and(notDeleted())))
                .sum();
    }

    /**
     * Returns the first non-soft-deleted row matching the condition, querying shards in registry
     * order and short-circuiting on the first match. Useful for unique-secondary-index lookups
     * where you know at most one row matches across shards.
     *
     * @param condition the jOOQ {@link Condition}.
     * @return the matching domain object, or empty.
     */
    public Optional<PERSISTABLE> findOneWhere(Condition condition) {
        return dbs().stream()
                .map(ctx ->
                        ctx.selectFrom(table).where(condition.and(notDeleted())).fetchOne())
                .filter(Objects::nonNull)
                .map(this::fromRecord)
                .findFirst();
    }

    /**
     * Returns all non-soft-deleted rows matching the condition, scatter-gathered across shards.
     *
     * @param condition the jOOQ {@link Condition}.
     * @return the matching domain objects (per-shard order, then flattened).
     */
    public List<PERSISTABLE> findAllWhere(Condition condition) {
        return dbs().stream()
                .flatMap(ctx ->
                        ctx.selectFrom(table).where(condition.and(notDeleted())).fetch().map(this::fromRecord).stream())
                .toList();
    }

    /**
     * Existence check by custom condition, short-circuiting across shards.
     *
     * @param condition the jOOQ {@link Condition}.
     * @return {@code true} if any shard has a matching non-soft-deleted row.
     */
    public boolean existsWhere(Condition condition) {
        return dbs().stream()
                .anyMatch(ctx -> ctx.fetchExists(ctx.selectOne().from(table).where(condition.and(notDeleted()))));
    }

    /**
     * Resolves which shard owns a single domain object via the configured {@link ShardingStrategy};
     * falls back to the default shard when the strategy declines to commit.
     *
     * @param domainObject the domain object.
     * @return the effective {@link ShardIdentifier}.
     */
    protected ShardIdentifier effectiveShard(PERSISTABLE domainObject) {
        var shard = shardingStrategy.resolveShardIdentifier(domainObject).orElse(databaseRegistry.defaultShard);
        return databaseRegistry.effectiveShard(shard);
    }

    /**
     * Resolves which shard owns a row given only its ID. Only strategies that advertise
     * ID-based resolution (e.g. {@code EmbeddedBitsShardingStrategy}) can answer this - others
     * raise {@link UnsupportedOperationException}.
     *
     * @param id the database-side identifier.
     * @return the effective {@link ShardIdentifier}.
     */
    protected ShardIdentifier effectiveShard(DB_ID id) {
        rejectNonIdBasedStrategy();
        var shard = shardingStrategy.resolveShardIdentifierById(id).orElse(databaseRegistry.defaultShard);
        return databaseRegistry.effectiveShard(shard);
    }

    /**
     * Resolves the shard for a collection of domain objects, requiring they all land on the same
     * shard. Mixed-shard collections raise {@link CrossShardException} since the action would
     * otherwise span shards and break the single-transaction-per-shard invariant.
     *
     * @param domainObjects the domain objects.
     * @return the common {@link ShardIdentifier}.
     */
    protected ShardIdentifier effectiveShard(Collection<PERSISTABLE> domainObjects) {
        if (shardingStrategy instanceof NoShardingStrategy<?>) {
            return databaseRegistry.defaultShard;
        }
        ShardIdentifier first = null;
        for (var p : domainObjects) {
            var shard = databaseRegistry.effectiveShard(
                    shardingStrategy.resolveShardIdentifier(p).orElse(databaseRegistry.defaultShard));
            if (first == null) {
                first = shard;
            } else if (!first.equals(shard)) {
                throw new CrossShardException(first, shard);
            }
        }
        return first != null ? first : databaseRegistry.defaultShard;
    }

    private Map<ShardIdentifier, Collection<DB_ID>> groupIdsByShard(Collection<DB_ID> ids) {
        var result = new LinkedHashMap<ShardIdentifier, Collection<DB_ID>>();
        for (var id : ids) {
            final var shard = effectiveShard(id);
            result.computeIfAbsent(shard, _ -> new ArrayList<>()).add(id);
        }
        return result;
    }

    private void rejectNonIdBasedStrategy() {
        if (shardingStrategy instanceof NoShardingStrategy<?>) {
            return;
        }
        if (!shardingStrategy.usesShardAwareId()) {
            throw new UnsupportedOperationException(
                    "Sharding strategy does not support ID-based shard resolution. Override this method in your repository subclass.");
        }
    }

    private Condition notDeleted() {
        return stateField.ne("DELETED");
    }

    /**
     * Looks up a typed {@link TableField} by name on a jOOQ generated table, returning null if
     * the field isn't present or doesn't match the requested type. Used by the constructor to
     * resolve {@code version} and {@code state} fields without subclasses having to pass them
     * explicitly.
     *
     * @param table     the jOOQ table.
     * @param fieldName the field's column name.
     * @param fieldType the expected Java type.
     * @param <F>       the field's Java type.
     * @return the matching {@link TableField}, or {@code null} if absent / mistyped.
     */
    @SuppressWarnings("unchecked")
    protected <F> TableField<RECORD, F> resolveField(Table<RECORD> table, String fieldName, Class<F> fieldType) {
        Field<?> field = table.field(fieldName);
        if (field == null) {
            return null;
        }

        if (field instanceof TableField) {
            final var tableField = (TableField<RECORD, ?>) field;
            if (fieldType.isAssignableFrom(tableField.getType())) {
                return (TableField<RECORD, F>) tableField;
            }
        }

        return null;
    }

    private <T, R extends Record> UpdateSetMoreStep<R> setField(
            UpdateSetStep<R> update, Field<T> targetField, Table<?> values) {
        final var sourceField = values.field(targetField.getName(), targetField.getType());
        return update.set(targetField, sourceField);
    }
}
