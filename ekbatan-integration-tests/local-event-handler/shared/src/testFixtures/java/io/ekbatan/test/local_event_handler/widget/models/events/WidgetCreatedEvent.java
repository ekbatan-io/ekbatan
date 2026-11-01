package io.ekbatan.test.local_event_handler.widget.models.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.domain.ModelId;
import io.ekbatan.test.local_event_handler.widget.models.Widget;
import java.util.UUID;

public class WidgetCreatedEvent extends ModelEvent<Widget> {
    public final String name;
    public final String color;

    public WidgetCreatedEvent(ModelId<UUID> widgetId, String name, String color) {
        super(widgetId.getId().toString(), Widget.class);
        this.name = name;
        this.color = color;
    }

    @JsonCreator
    WidgetCreatedEvent(
            @JsonProperty("modelId") String modelId,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("name") String name,
            @JsonProperty("color") String color) {
        super(modelId, Widget.class);
        this.name = name;
        this.color = color;
    }
}
