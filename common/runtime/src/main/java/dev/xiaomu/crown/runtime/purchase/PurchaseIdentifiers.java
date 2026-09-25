package dev.xiaomu.crown.runtime.purchase;

import java.util.Objects;
import java.util.UUID;

/** 同一确认会话复用订单和仓库条目 ID，防止重复购买。 */
public record PurchaseIdentifiers(UUID orderId, UUID entryId) {
    public PurchaseIdentifiers {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(entryId, "entryId");
    }
    public static PurchaseIdentifiers create() {
        return new PurchaseIdentifiers(UUID.randomUUID(), UUID.randomUUID());
    }
}
