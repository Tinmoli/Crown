package dev.xiaomu.crown.runtime.purchase;

import dev.xiaomu.crown.domain.catalog.PaymentType;

import java.util.Objects;
import java.util.UUID;

/** 一次确认会话生成并复用的稳定订单、仓库条目及 Mint 事务 ID。 */
public record PurchaseIdentifiers(
        UUID orderId,
        UUID entryId,
        UUID mintTransactionId
) {
    public PurchaseIdentifiers {
        orderId = Objects.requireNonNull(orderId, "orderId");
        entryId = Objects.requireNonNull(entryId, "entryId");
    }

    public static PurchaseIdentifiers create(
            PaymentType paymentType
    ) {
        Objects.requireNonNull(paymentType, "paymentType");
        return new PurchaseIdentifiers(
                UUID.randomUUID(),
                UUID.randomUUID(),
                paymentType == PaymentType.MINT
                        ? UUID.randomUUID()
                        : null);
    }

    public void requirePaymentType(PaymentType paymentType) {
        Objects.requireNonNull(paymentType, "paymentType");
        if ((paymentType == PaymentType.MINT)
                != (mintTransactionId != null)) {
            throw new IllegalArgumentException(
                    "Stable IDs do not match payment type");
        }
    }
}