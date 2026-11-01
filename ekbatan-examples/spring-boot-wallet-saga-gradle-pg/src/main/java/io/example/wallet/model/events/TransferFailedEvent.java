package io.example.wallet.model.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.domain.ModelId;
import io.example.wallet.model.Wallet;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Saga step 2 (failure path) - emitted by {@code CompleteTransferAction} on the <em>source</em>
 * wallet when the destination wallet can't receive the funds (missing, closed, wrong currency,
 * etc.). The source wallet's balance was already debited by step 1; emitting this event on the
 * source attaches a clear failure marker to the source's event trail without rolling back the
 * debit (compensation does that, see {@link TransferRefundedEvent}).
 *
 * <p>{@code TransferFailedEventHandler} picks this up via the local-event-handler and invokes
 * {@code RefundTransferAction} to compensate.
 */
public class TransferFailedEvent extends ModelEvent<Wallet> {

    public final UUID transferId;
    public final UUID fromWalletId;
    public final UUID toWalletId;
    public final BigDecimal amount;
    public final String reason;

    public TransferFailedEvent(
            ModelId<UUID> sourceWalletId,
            UUID transferId,
            UUID fromWalletId,
            UUID toWalletId,
            BigDecimal amount,
            String reason) {
        super(sourceWalletId.getId().toString(), Wallet.class);
        this.transferId = transferId;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amount = amount;
        this.reason = reason;
    }

    @JsonCreator
    TransferFailedEvent(
            @JsonProperty("modelId") String modelId,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("transferId") UUID transferId,
            @JsonProperty("fromWalletId") UUID fromWalletId,
            @JsonProperty("toWalletId") UUID toWalletId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("reason") String reason) {
        super(modelId, Wallet.class);
        this.transferId = transferId;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amount = amount;
        this.reason = reason;
    }
}
