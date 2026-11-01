package io.example.wallet.model.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.domain.ModelId;
import io.example.wallet.model.Wallet;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Saga step 2 (happy path) - emitted by {@code CompleteTransferAction} on the <em>destination</em>
 * wallet when the credit succeeds. Carries the {@code transferId} so consumers can correlate
 * with the corresponding {@link TransferInitiatedEvent} on the source.
 *
 * <p>This is a terminal event for the saga's happy path - no further handler runs on receipt.
 * Downstream systems (analytics, notification dispatchers, audit) can subscribe if they want.
 */
public class TransferCompletedEvent extends ModelEvent<Wallet> {

    public final UUID transferId;
    public final UUID fromWalletId;
    public final UUID toWalletId;
    public final BigDecimal amount;
    public final BigDecimal newDestinationBalance;

    public TransferCompletedEvent(
            ModelId<UUID> destinationWalletId,
            UUID transferId,
            UUID fromWalletId,
            UUID toWalletId,
            BigDecimal amount,
            BigDecimal newDestinationBalance) {
        super(destinationWalletId.getId().toString(), Wallet.class);
        this.transferId = transferId;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amount = amount;
        this.newDestinationBalance = newDestinationBalance;
    }

    @JsonCreator
    TransferCompletedEvent(
            @JsonProperty("modelId") String modelId,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("transferId") UUID transferId,
            @JsonProperty("fromWalletId") UUID fromWalletId,
            @JsonProperty("toWalletId") UUID toWalletId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("newDestinationBalance") BigDecimal newDestinationBalance) {
        super(modelId, Wallet.class);
        this.transferId = transferId;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amount = amount;
        this.newDestinationBalance = newDestinationBalance;
    }
}
