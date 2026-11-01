package io.ekbatan.events.streaming.actionevent.avro;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Avro 1.12.2 decodes only into classes on its allow-list ({@code org.apache.avro.SERIALIZABLE_PACKAGES}
 * or {@code ClassSecurityValidator}), and refused {@code ActionEvent} with a {@code SecurityException}
 * unless every consumer listed it. {@code ActionEvent} now puts itself on that list, so a consumer
 * reads one with nothing set - which this test does, in a JVM where no allow-list is configured.
 */
class ActionEventTest {

    @Test
    void an_action_event_round_trips_with_no_avro_allow_list_set() throws Exception {
        var started = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var original = ActionEvent.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setNamespace("acme.orders")
                .setActionId(UUID.randomUUID().toString())
                .setActionName("WalletCreateAction")
                .setActionParams("{\"name\":\"Main\"}")
                .setStartedDate(started)
                .setCompletionDate(started.plusMillis(5))
                .setModelId(UUID.randomUUID().toString())
                .setModelType("Wallet")
                .setEventType("WalletCreatedEvent")
                .setPayload(ByteBuffer.wrap("payload".getBytes(StandardCharsets.UTF_8)))
                .setEventDate(started.plusMillis(3))
                .setDelivered(false)
                .build();

        assertThat(System.getProperty("org.apache.avro.SERIALIZABLE_PACKAGES")).isNull();
        assertThat(System.getProperty("org.apache.avro.SERIALIZABLE_CLASSES")).isNull();

        var restored = ActionEvent.fromByteBuffer(original.toByteBuffer());

        assertThat(restored).isEqualTo(original);
    }
}
