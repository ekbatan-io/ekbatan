package io.ekbatan.core.action;

import io.ekbatan.core.action.persister.PersistableChanges;
import io.ekbatan.core.domain.Persistable;
import io.ekbatan.core.internal.Validate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The accumulator for one action execution's intended persistence changes.
 *
 * <p>An {@code ActionPlan} is created fresh per {@code ActionExecutor.execute(...)} call (or per
 * test-support {@code ActionSpec.execute(...)} in tests) and bound as a {@link
 * java.lang.ScopedValue} for the duration of {@link Action#perform(java.security.Principal,
 * Object)}. Inside the action, code reaches it via {@link Action#plan()}.
 *
 * <h2>Single-writer; not thread-safe</h2>
 *
 * <p>{@code ActionPlan} uses a plain {@link LinkedHashMap} internally and is intentionally
 * <b>not</b> thread-safe. Within an action's {@code perform()}, mutations via {@link #add},
 * {@link #update}, {@link #addAll}, and {@link #updateAll} must happen on the executing thread.
 *
 * <p>If the action spawns parallel work to gather data, join the children first and only then
 * mutate the plan from the thread that invoked {@code perform()}. Calling {@code plan.add(...)}
 * from inside a spawned task is undefined behavior - concurrent mutations of the underlying
 * map are a data race.
 */
public class ActionPlan {

    /** Constructed by the executor at the start of each {@code execute(...)} call. */
    public ActionPlan() {}

    private final Map<Class<? extends Persistable<?>>, PersistableChanges<?, ?>> changesByType = new LinkedHashMap<>();

    /**
     * Stages an entity for insert.
     *
     * @param entity the entity to insert.
     * @param <ID> the entity's identifier type.
     * @param <E> the entity type.
     * @return {@code entity} unchanged, for fluent use inside the action.
     */
    @SuppressWarnings("unchecked")
    public <ID extends Comparable<ID>, E extends Persistable<ID>> E add(E entity) {
        Validate.notNull(entity, "entity cannot be null");
        getOrCreateChanges((Class<E>) entity.getClass()).add(entity);
        return entity;
    }

    /**
     * Stages a collection of entities for insert. A null collection is refused rather than read
     * as "nothing to stage": it is a caller's mistake, and taken as empty it would commit an
     * action that staged nothing and looks like success.
     *
     * @param entities the entities to insert; not null, and holding no null.
     * @param <ID> the entity identifier type.
     * @return the same collection, or an empty one if {@code entities} was empty.
     */
    public <ID extends Comparable<ID>> Collection<? extends Persistable<ID>> addAll(
            Collection<? extends Persistable<ID>> entities) {
        Validate.notNull(entities, "entities cannot be null");
        if (entities.isEmpty()) {
            return Collections.emptyList();
        }
        entities.forEach(this::add);
        return entities;
    }

    /**
     * Stages an entity for update; returns the entity with {@code version + 1} so the action can
     * see post-update state in subsequent logic.
     *
     * @param entity the entity to update.
     * @param <ID> the entity identifier type.
     * @param <E> the entity type.
     * @return a copy of {@code entity} with {@code version + 1}.
     */
    @SuppressWarnings("unchecked")
    public <ID extends Comparable<ID>, E extends Persistable<ID>> E update(E entity) {
        Validate.notNull(entity, "entity cannot be null");
        getOrCreateChanges((Class<E>) entity.getClass()).update(entity);
        return entity.nextVersion();
    }

    /**
     * Stages a collection of entities for update; returns the versions-incremented copies. A null
     * collection is refused, as in {@link #addAll(Collection)}.
     *
     * @param entities the entities to update; not null, and holding no null.
     * @param <ID> the entity identifier type.
     * @return the version-incremented copies, in the same order.
     */
    public <ID extends Comparable<ID>> Collection<? extends Persistable<ID>> updateAll(
            Collection<? extends Persistable<ID>> entities) {
        Validate.notNull(entities, "entities cannot be null");
        if (entities.isEmpty()) {
            return Collections.emptyList();
        }

        return entities.stream().map(this::update).toList();
    }

    @SuppressWarnings("unchecked")
    private <ID extends Comparable<ID>, E extends Persistable<ID>> PersistableChanges<ID, E> getOrCreateChanges(
            Class<E> entityClass) {
        return (PersistableChanges<ID, E>) changesByType.computeIfAbsent(entityClass, _ -> new PersistableChanges<>());
    }

    /**
     * Reads back the additions staged for a specific entity type.
     *
     * @param entityClass the entity class to query.
     * @param <ID> the entity identifier type.
     * @param <E> the entity type.
     * @return id-keyed map of staged additions, or empty if nothing of this type was staged.
     */
    @SuppressWarnings("unchecked")
    public <ID extends Comparable<ID>, E extends Persistable<ID>> Map<ID, E> additions(Class<E> entityClass) {
        PersistableChanges<ID, E> changes = (PersistableChanges<ID, E>) changesByType.get(entityClass);
        return changes != null ? changes.additions() : Map.of();
    }

    /**
     * Reads back the updates staged for a specific entity type.
     *
     * @param entityClass the entity class to query.
     * @param <ID> the entity identifier type.
     * @param <E> the entity type.
     * @return id-keyed map of staged updates, or empty if nothing of this type was staged.
     */
    @SuppressWarnings("unchecked")
    public <ID extends Comparable<ID>, E extends Persistable<ID>> Map<ID, E> updates(Class<E> entityClass) {
        PersistableChanges<ID, E> changes = (PersistableChanges<ID, E>) changesByType.get(entityClass);
        return changes != null ? changes.updates() : Map.of();
    }

    /** {@return an immutable view of all staged changes, keyed by entity class} */
    public Map<Class<? extends Persistable<?>>, PersistableChanges<?, ?>> changes() {
        return Collections.unmodifiableMap(changesByType);
    }

    /** {@return true if anything was staged for add or update in this plan} */
    public boolean hasChanges() {
        return !changesByType.isEmpty() && changesByType.values().stream().anyMatch(PersistableChanges::hasChanges);
    }
}
