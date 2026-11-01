package io.example.wallet.model.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.ModelEvent;
import io.example.wallet.model.Wallet;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

public class WalletCreatedEvent extends ModelEvent<Wallet> {

    public final UUID ownerId;
    public final String currency;
    public final BigDecimal initialBalance;

    public WalletCreatedEvent(Id<Wallet> walletId, UUID ownerId, Currency currency, BigDecimal initialBalance) {
        super(walletId.getValue().toString(), Wallet.class);
        this.ownerId = ownerId;
        this.currency = currency.getCurrencyCode();
        this.initialBalance = initialBalance;
    }

    @JsonCreator
    WalletCreatedEvent(
            @JsonProperty("modelId") String modelId,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("ownerId") UUID ownerId,
            @JsonProperty("currency") String currency,
            @JsonProperty("initialBalance") BigDecimal initialBalance) {
        super(modelId, Wallet.class);
        this.ownerId = ownerId;
        this.currency = currency;
        this.initialBalance = initialBalance;
    }
}
