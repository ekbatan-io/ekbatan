package io.example.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.domain.ShardedId;
import io.ekbatan.di.EkbatanAction;
import io.example.wallet.model.Wallet;
import io.example.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;

/**
 * Updates the wallet's balance and emits a {@code WalletMoneyDepositedEvent}.
 *
 * <p>The wallet id is shard-aware, so {@code walletRepository.getById(...)} and
 * {@code plan().update(...)} both route to the wallet's physical shard. This remains a
 * single-shard action even when the application has multiple shards configured.
 */
@EkbatanAction
public class WalletDepositMoneyAction extends Action<WalletDepositMoneyAction.Params, Wallet> {

    public record Params(ShardedId<Wallet> walletId, BigDecimal amount, String recipient) {}

    private final WalletRepository walletRepository;

    public WalletDepositMoneyAction(Clock clock, WalletRepository walletRepository) {
        super(clock);
        this.walletRepository = walletRepository;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) {
        final var wallet = walletRepository.getById(params.walletId().getValue());
        final var updatedWallet = wallet.deposit(params.amount());
        return plan().update(updatedWallet);
    }
}
