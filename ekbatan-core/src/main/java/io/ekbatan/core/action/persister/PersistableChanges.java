package io.ekbatan.core.action.persister;

import io.ekbatan.core.domain.Persistable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The per-type staging area inside an {@link io.ekbatan.core.action.ActionPlan}: a map of IDs
 * to "to be added" entities and a separate map of IDs to "to be updated" entities, both
 * insertion-ordered.
 *
 * <p>Re-registering the same ID - whether for add or update - raises
 * {@link IllegalStateException}. This is deliberate: an action writes each row once, so a
 * second staging cannot mean a second write. Accepting it would have to either overwrite the
 * first silently - leaving the value that {@code plan().update(...)} already handed back
 * describing a row nobody is going to write - or guard the write against a version the
 * database has never held, which surfaces later as an optimistic-lock failure with nothing
 * concurrent anywhere near it. Refusing at the point of the mistake is better than either.
 *
 * @param <ID> the identifier type of the persistable entity.
 * @param <E> the concrete {@link Persistable} entity type being staged.
 */
public class PersistableChanges<ID extends Comparable<ID>, E extends Persistable<ID>> {

    /** Default no-arg constructor; framework instantiates per entity type lazily inside the plan. */
    public PersistableChanges() {}

    private final Map<ID, E> additions = new LinkedHashMap<>();
    private final Map<ID, E> updates = new LinkedHashMap<>();

    private void checkNotRegistered(E entity, String operation) {
        final var id = entity.getId();
        final var existingOperation =
                switch (id) {
                    case ID _ when additions.containsKey(id) -> "addition";
                    case ID _ when updates.containsKey(id) -> "update";
                    default -> null;
                };

        if (existingOperation != null) {
            // Names the type as well as the id, and says what to do instead. An error that
            // only reports what went wrong leaves the reader to work out the rest, and the
            // reader here has just written something that looked entirely reasonable.
            throw new IllegalStateException(entity.getClass().getSimpleName() + " " + id
                    + " is already registered for " + existingOperation
                    + " operation, cannot " + operation
                    + ". An action writes each row once, so two changes to one row must be"
                    + " composed before they are staged - wallet.deposit(10).withdraw(5) -"
                    + " and then staged with a single plan().update(...), rather than staged"
                    + " one after another.");
        }
    }

    /**
     * Stages an entity for insert; raises if the ID is already registered for any operation.
     *
     * @param entity the entity to insert.
     */
    public void add(E entity) {
        checkNotRegistered(entity, "add");
        additions.put(entity.getId(), entity);
    }

    /**
     * Stages an entity for update; raises if the ID is already registered for any operation.
     *
     * @param entity the entity to update.
     */
    public void update(E entity) {
        checkNotRegistered(entity, "update");
        updates.put(entity.getId(), entity);
    }

    /** {@return an immutable view of the additions staged so far, in insertion order} */
    public Map<ID, E> additions() {
        return Collections.unmodifiableMap(additions);
    }

    /** {@return an immutable view of the updates staged so far, in insertion order} */
    public Map<ID, E> updates() {
        return Collections.unmodifiableMap(updates);
    }

    /** {@return {@code true} if any additions or updates have been staged} */
    public boolean hasChanges() {
        return !(additions.isEmpty() && updates.isEmpty());
    }
}
