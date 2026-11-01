package io.example.wallet.model.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.domain.ShardedId;
import io.example.wallet.model.Wallet;
import java.math.BigDecimal;

public class WalletMoneyDepositedEvent extends ModelEvent<Wallet> {

    public final BigDecimal amount;
    public final BigDecimal newBalance;

    public WalletMoneyDepositedEvent(ShardedId<Wallet> walletId, BigDecimal amount, BigDecimal newBalance) {
        super(walletId.getValue().toString(), Wallet.class);
        this.amount = amount;
        this.newBalance = newBalance;
    }

    @JsonCreator
    WalletMoneyDepositedEvent(
            @JsonProperty("modelId") String modelId,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("newBalance") BigDecimal newBalance) {
        super(modelId, Wallet.class);
        this.amount = amount;
        this.newBalance = newBalance;
    }
}
