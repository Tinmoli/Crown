package dev.xiaomu.crown.storage.model;

import java.util.Objects;
import java.util.Optional;

/** 内部支付状态及可选的称号币流水。 */
public record InternalPaymentResult(
        InternalPaymentStatus status,
        TitleCoinLedgerRecord ledgerRecord
) {
    public InternalPaymentResult {
        status = Objects.requireNonNull(status, "status");
        if (ledgerRecord != null
                && status != InternalPaymentStatus.COMMITTED) {
            throw new IllegalArgumentException(
                    "Only a newly committed payment has a ledger");
        }
    }

    public static InternalPaymentResult committed(
            TitleCoinLedgerRecord ledger
    ) {
        return new InternalPaymentResult(
                InternalPaymentStatus.COMMITTED, ledger);
    }

    public static InternalPaymentResult committedFree() {
        return committed(null);
    }

    public static InternalPaymentResult of(
            InternalPaymentStatus status
    ) {
        if (status == InternalPaymentStatus.COMMITTED) {
            throw new IllegalArgumentException(
                    "Use a committed result factory");
        }
        return new InternalPaymentResult(status, null);
    }

    public Optional<TitleCoinLedgerRecord> ledger() {
        return Optional.ofNullable(ledgerRecord);
    }
}