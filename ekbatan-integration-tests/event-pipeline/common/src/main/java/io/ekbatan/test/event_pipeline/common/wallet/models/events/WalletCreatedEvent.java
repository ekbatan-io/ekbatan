package io.ekbatan.test.event_pipeline.common.wallet.models.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.test.event_pipeline.common.wallet.models.Wallet;

public class WalletCreatedEvent extends ModelEvent<Wallet> {
    public final String name;

    public WalletCreatedEvent(Id<Wallet> walletId, String name) {
        super(walletId.getId().toString(), Wallet.class);
        this.name = name;
    }

    @JsonCreator
    WalletCreatedEvent(
            @JsonProperty("modelId") String modelId,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("name") String name) {
        super(modelId, Wallet.class);
        this.name = name;
    }
}
