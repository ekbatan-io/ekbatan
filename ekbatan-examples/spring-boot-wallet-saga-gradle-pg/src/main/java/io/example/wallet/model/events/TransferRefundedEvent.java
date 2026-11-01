package io.example.wallet.model.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.domain.ModelId;
import io.example.wallet.model.Wallet;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Saga compensation - emitted by {@code RefundTransferAction} on the source wallet after a
 * {@link TransferFailedEvent} is observed. Marks the saga as terminated with the source's
 * balance restored. {@code transferId} matches the original {@link TransferInitiatedEvent} so
 * the full chain (initiated -> failed -> refunded) is correlated.
 *
 * <p>Terminal event - no handler runs on receipt.
 */
public class TransferRefundedEvent extends ModelEvent<Wallet> {

    public final UUID transferId;
    public final UUID fromWalletId;
    public final BigDecimal amount;
    public final BigDecimal newSourceBalance;

    public TransferRefundedEvent(
            ModelId<UUID> sourceWalletId,
            UUID transferId,
            UUID fromWalletId,
            BigDecimal amount,
            BigDecimal newSourceBalance) {
        super(sourceWalletId.getId().toString(), Wallet.class);
        this.transferId = transferId;
        this.fromWalletId = fromWalletId;
        this.amount = amount;
        this.newSourceBalance = newSourceBalance;
    }

    @JsonCreator
    TransferRefundedEvent(
            @JsonProperty("modelId") String modelId,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("transferId") UUID transferId,
            @JsonProperty("fromWalletId") UUID fromWalletId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("newSourceBalance") BigDecimal newSourceBalance) {
        super(modelId, Wallet.class);
        this.transferId = transferId;
        this.fromWalletId = fromWalletId;
        this.amount = amount;
        this.newSourceBalance = newSourceBalance;
    }
}
