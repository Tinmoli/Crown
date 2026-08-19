package dev.xiaomu.crown.storage.model;

import java.util.Objects;

/** 一次成功称号币变更的余额与流水结果。 */
public record CoinAdjustmentResult(
        long balanceBefore,
        long balanceAfter,
        TitleCoinLedgerRecord ledger
) {
    public CoinAdjustmentResult {
        if (balanceBefore < 0 || balanceAfter < 0) {
            throw new IllegalArgumentException(
                    "Coin balances cannot be negative");
        }
        ledger = Objects.requireNonNull(ledger, "ledger");
        if (ledger.balanceBefore() != balanceBefore
                || ledger.balanceAfter() != balanceAfter) {
            throw new IllegalArgumentException(
                    "Ledger and result balances differ");
        }
    }
}