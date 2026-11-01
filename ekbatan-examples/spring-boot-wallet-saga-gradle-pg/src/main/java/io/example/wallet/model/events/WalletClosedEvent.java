package io.example.wallet.model.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.ModelEvent;
import io.example.wallet.model.Wallet;

public class WalletClosedEvent extends ModelEvent<Wallet> {

    public WalletClosedEvent(Id<Wallet> walletId) {
        super(walletId.getValue().toString(), Wallet.class);
    }

    @JsonCreator
    WalletClosedEvent(@JsonProperty("modelId") String modelId, @JsonProperty("modelName") String modelName) {
        super(modelId, Wallet.class);
    }
}
