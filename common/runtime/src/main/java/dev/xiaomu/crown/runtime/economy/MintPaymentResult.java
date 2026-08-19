package dev.xiaomu.crown.runtime.economy;

import java.util.Objects;
import java.util.UUID;

/** Crown 只依赖的 Mint 交易归一化结果。 */
public record MintPaymentResult(
        UUID transactionId,
        boolean successful,
        long amountMinor,
        String failureCode,
        String message
) {
    public MintPaymentResult {
        transactionId = Objects.requireNonNull(
                transactionId, "transactionId");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException(
                    "Mint payment amount must be positive");
        }
        failureCode = Objects.requireNonNull(
                failureCode, "failureCode");
        message = Objects.requireNonNull(message, "message");
        if (successful && !failureCode.equals("NONE")) {
            throw new IllegalArgumentException(
                    "Successful Mint payment has a failure code");
        }
        if (!successful && failureCode.equals("NONE")) {
            throw new IllegalArgumentException(
                    "Failed Mint payment requires a failure code");
        }
    }

    public static MintPaymentResult success(
            UUID transactionId,
            long amountMinor
    ) {
        return new MintPaymentResult(
                transactionId, true, amountMinor, "NONE", "");
    }

    public static MintPaymentResult failure(
            UUID transactionId,
            long amountMinor,
            String failureCode,
            String message
    ) {
        return new MintPaymentResult(
                transactionId,
                false,
                amountMinor,
                failureCode,
                message);
    }
}