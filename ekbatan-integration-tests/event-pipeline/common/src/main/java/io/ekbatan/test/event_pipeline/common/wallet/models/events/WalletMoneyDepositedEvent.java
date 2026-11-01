package io.ekbatan.test.event_pipeline.common.wallet.models.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.test.event_pipeline.common.wallet.models.Wallet;
import java.math.BigDecimal;

public class WalletMoneyDepositedEvent extends ModelEvent<Wallet> {
    public final BigDecimal amount;

    public WalletMoneyDepositedEvent(Id<Wallet> walletId, BigDecimal amount) {
        super(walletId.getId().toString(), Wallet.class);
        this.amount = amount;
    }

    @JsonCreator
    WalletMoneyDepositedEvent(
            @JsonProperty("modelId") String modelId,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("amount") BigDecimal amount) {
        super(modelId, Wallet.class);
        this.amount = amount;
    }
}
