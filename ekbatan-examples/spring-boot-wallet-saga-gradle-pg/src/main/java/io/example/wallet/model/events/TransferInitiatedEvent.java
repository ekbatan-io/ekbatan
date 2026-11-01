package io.example.wallet.model.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.domain.ModelId;
import io.example.wallet.model.Wallet;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Saga step 1 - emitted by {@code InitiateTransferAction} on the source wallet. Records that
 * money has been debited and a transfer is in flight. The {@code transferId} correlates this
 * event with the rest of the saga's chain (the credit, optional failure, optional refund).
 *
 * <p>{@code TransferInitiatedEventHandler} picks this up via the local-event-handler and
 * invokes {@code CompleteTransferAction} to perform step 2.
 */
public class TransferInitiatedEvent extends ModelEvent<Wallet> {

    public final UUID transferId;
    public final UUID fromWalletId;
    public final UUID toWalletId;
    public final BigDecimal amount;
    public final BigDecimal newSourceBalance;

    public TransferInitiatedEvent(
            ModelId<UUID> sourceWalletId,
            UUID transferId,
            UUID fromWalletId,
            UUID toWalletId,
            BigDecimal amount,
            BigDecimal newSourceBalance) {
        super(sourceWalletId.getId().toString(), Wallet.class);
        this.transferId = transferId;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amount = amount;
        this.newSourceBalance = newSourceBalance;
    }

    @JsonCreator
    TransferInitiatedEvent(
            @JsonProperty("modelId") String modelId,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("transferId") UUID transferId,
            @JsonProperty("fromWalletId") UUID fromWalletId,
            @JsonProperty("toWalletId") UUID toWalletId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("newSourceBalance") BigDecimal newSourceBalance) {
        super(modelId, Wallet.class);
        this.transferId = transferId;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amount = amount;
        this.newSourceBalance = newSourceBalance;
    }
}
