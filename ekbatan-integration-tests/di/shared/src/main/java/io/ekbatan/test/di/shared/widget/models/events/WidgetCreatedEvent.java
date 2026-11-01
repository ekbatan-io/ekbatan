package io.ekbatan.test.di.shared.widget.models.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.test.di.shared.widget.models.Widget;

public class WidgetCreatedEvent extends ModelEvent<Widget> {
    public final String name;
    public final String color;

    public WidgetCreatedEvent(Id<Widget> widgetId, String name, String color) {
        super(widgetId.getValue().toString(), Widget.class);
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
