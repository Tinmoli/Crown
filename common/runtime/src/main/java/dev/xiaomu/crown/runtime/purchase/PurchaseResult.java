package dev.xiaomu.crown.runtime.purchase;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 单次购买或恢复的归一化结果。 */
public record PurchaseResult(
        PurchaseStatus status,
        UUID orderId,
        UUID entryId,
        String failureCode
) {
    public PurchaseResult {
        status = Objects.requireNonNull(status, "status");
        orderId = Objects.requireNonNull(orderId, "orderId");
        if ((status == PurchaseStatus.GRANTED)
                != (entryId != null)) {
            throw new IllegalArgumentException(
                    "Only a granted purchase has an entry ID");
        }
        if (failureCode != null
                && (failureCode.isBlank()
                || failureCode.length() > 128
                || failureCode.codePoints().anyMatch(
                Character::isISOControl))) {
            throw new IllegalArgumentException(
                    "Purchase failure code is invalid");
        }
    }

    public static PurchaseResult granted(
            UUID orderId,
            UUID entryId
    ) {
        return new PurchaseResult(
                PurchaseStatus.GRANTED,
                orderId,
                Objects.requireNonNull(entryId, "entryId"),
                null);
    }

    public static PurchaseResult of(
            PurchaseStatus status,
            UUID orderId
    ) {
        return of(status, orderId, null);
    }

    public static PurchaseResult of(
            PurchaseStatus status,
            UUID orderId,
            String failureCode
    ) {
        if (status == PurchaseStatus.GRANTED) {
            throw new IllegalArgumentException(
                    "Use the granted result factory");
        }
        return new PurchaseResult(
                status, orderId, null, failureCode);
    }

    public Optional<UUID> entry() {
        return Optional.ofNullable(entryId);
    }

    public Optional<String> failure() {
        return Optional.ofNullable(failureCode);
    }
}