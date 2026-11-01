package io.ekbatan.test.local_event_handler.note.models.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.domain.ModelId;
import io.ekbatan.test.local_event_handler.note.models.Note;
import java.util.UUID;

public class NoteCreatedEvent extends ModelEvent<Note> {
    public final String widgetId;
    public final String text;

    public NoteCreatedEvent(ModelId<UUID> noteId, String widgetId, String text) {
        super(noteId.getId().toString(), Note.class);
        this.widgetId = widgetId;
        this.text = text;
    }

    @JsonCreator
    NoteCreatedEvent(
            @JsonProperty("modelId") String modelId,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("widgetId") String widgetId,
            @JsonProperty("text") String text) {
        super(modelId, Note.class);
        this.widgetId = widgetId;
        this.text = text;
    }
}
