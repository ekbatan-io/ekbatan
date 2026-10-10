package io.ekbatan.core.action.persister.event.single_table_json;

import static io.ekbatan.core.action.persister.event.single_table_json.EventEntity.createEventEntity;

import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.internal.Validate;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Default {@link EventPersister}: writes every model event (and a sentinel row for actions
 * that emit none) into a single {@code eventlog.events} table with JSON payload. Action
 * params are also stored as JSON on every emitted row, so a single eventlog row carries the
 * full audit trail for its action.
 *
 * <p>This is the simplest event-storage shape - one table per shard, schema-less payloads
 * via JSON - and matches the default Flyway migration scripts. Applications that want
 * separate {@code action_events} / {@code model_events} tables, or non-JSON payloads, replace
 * this with their own {@link EventPersister} implementation passed to
 * {@link io.ekbatan.core.action.ActionExecutor.Builder#eventPersister(EventPersister)}.
 */
public class SingleTableJsonEventPersister implements EventPersister {

    private static final Tracer TRACER = GlobalOpenTelemetry.get().getTracer("io.ekbatan.core", "1.1.2");

    private final EventEntityRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, Class<?>> eventTypesBySimpleName = new ConcurrentHashMap<>();

    /**
     * Constructs the persister.
     *
     * @param databaseRegistry the registry of per-shard connection pools / transaction managers.
     * @param objectMapper the Jackson mapper used to serialize event payloads to JSON.
     */
    public SingleTableJsonEventPersister(DatabaseRegistry databaseRegistry, ObjectMapper objectMapper) {
        Validate.notNull(databaseRegistry, "databaseRegistry cannot be null");
        this.eventRepository = new EventEntityRepository(databaseRegistry);
        this.objectMapper = Validate.notNull(objectMapper, "objectMapper cannot be null");
    }

    @Override
    public void persistActionEvents(
            String namespace,
            String actionName,
            Instant startedDate,
            Instant completionDate,
            Object actionParams,
            Collection<ModelEvent<?>> modelEvents,
            ShardIdentifier shard,
            UUID actionEventId) {

        final var span = TRACER.spanBuilder("ekbatan.event.persist")
                .setAttribute("ekbatan.action.name", actionName)
                .setAttribute("ekbatan.event.count", modelEvents.size())
                .startSpan();
        try (var _ = span.makeCurrent()) {
            final var serializedParams = serializeActionParams(actionParams);

            final List<EventEntity> entities;
            if (modelEvents.isEmpty()) {
                entities = List.of(createEventEntity(
                                UUID.randomUUID(),
                                namespace,
                                actionEventId,
                                actionName,
                                serializedParams,
                                startedDate,
                                completionDate,
                                null,
                                null,
                                null,
                                null,
                                completionDate)
                        .build());
            } else {
                entities = modelEvents.stream()
                        .map(event -> createEventEntity(
                                        UUID.randomUUID(),
                                        namespace,
                                        actionEventId,
                                        actionName,
                                        serializedParams,
                                        startedDate,
                                        completionDate,
                                        event.modelId,
                                        event.modelName,
                                        eventTypeName(event),
                                        serializeEventPayload(event),
                                        completionDate)
                                .build())
                        .collect(java.util.stream.Collectors.toList());
            }

            eventRepository.addAllNoResult(entities, shard);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Serializes one model event to the JSON object the {@code payload} column holds.
     *
     * <p>The same rule as {@link #serializeActionParams}, and now the same error. Passing
     * {@code valueToTree(event)} straight into the {@code ObjectNode} parameter looked cast-free
     * but was not: {@code valueToTree} is generic in its return type, so the compiler inserted the
     * cast silently and an event that serialized to anything else failed as a bare
     * {@code ClassCastException} naming Jackson node classes, from inside the persister, with
     * nothing pointing at the event. An event carrying {@code @JsonValue} is the realistic way in -
     * Jackson then renders the whole event as that single value.
     *
     * @param event the model event being persisted.
     * @return the event serialized as a JSON object.
     */
    private ObjectNode serializeEventPayload(Object event) {
        final var serializedPayload = objectMapper.valueToTree(event);
        Validate.isInstanceOf(
                ObjectNode.class,
                serializedPayload,
                "event must serialize to a JSON object; check for @JsonValue or a custom serializer"
                        + " rendering it as a scalar/list");
        return (ObjectNode) serializedPayload;
    }

    private ObjectNode serializeActionParams(Object actionParams) {
        final var serializedParams = objectMapper.valueToTree(actionParams);
        Validate.isInstanceOf(
                ObjectNode.class,
                serializedParams,
                "actionParams must serialize to a JSON object; use a record/class params object, not null/scalar/list");
        return (ObjectNode) serializedParams;
    }

    private String eventTypeName(ModelEvent<?> event) {
        final var eventClass = event.getClass();
        final var eventTypeSimpleName =
                Validate.notBlank(eventClass.getSimpleName(), "event class simple name cannot be blank");

        final var previousEventClass = eventTypesBySimpleName.putIfAbsent(eventTypeSimpleName, eventClass);
        if (previousEventClass != null && !previousEventClass.equals(eventClass)) {
            throw new IllegalArgumentException("Event type simple name collision: "
                    + eventTypeSimpleName
                    + " maps to both "
                    + previousEventClass.getName()
                    + " and "
                    + eventClass.getName());
        }

        return eventTypeSimpleName;
    }
}
