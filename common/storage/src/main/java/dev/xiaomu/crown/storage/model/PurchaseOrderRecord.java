package dev.xiaomu.crown.storage.model;

import dev.xiaomu.crown.domain.catalog.DefinitionId;
import dev.xiaomu.crown.domain.catalog.PaymentType;
import dev.xiaomu.crown.domain.order.PurchaseOrderState;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 可幂等恢复的购买订单持久化快照。 */
public record PurchaseOrderRecord(
        UUID orderId,
        UUID playerId,
        ProductType productType,
        DefinitionId definitionId,
        PaymentType paymentType,
        long amountMinor,
        String titleSnapshotJson,
        PurchaseOrderState state,
        UUID entryId,
        String failureCode,
        boolean inventoryReserved,
        Instant createdAt,
        Instant updatedAt
) {
    public PurchaseOrderRecord {
        orderId = Objects.requireNonNull(orderId, "orderId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        productType = Objects.requireNonNull(
                productType, "productType");
        paymentType = Objects.requireNonNull(
                paymentType, "paymentType");
        titleSnapshotJson = Objects.requireNonNull(
                titleSnapshotJson, "titleSnapshotJson");
        state = Objects.requireNonNull(state, "state");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "Order updated time precedes creation");
        }
        if (titleSnapshotJson.isBlank()
                || titleSnapshotJson.length() > 1_048_576) {
            throw new IllegalArgumentException(
                    "Order title snapshot is blank or too large");
        }
        if (amountMinor < 0) {
            throw new IllegalArgumentException(
                    "Order amount cannot be negative");
        }
        if (paymentType == PaymentType.FREE && amountMinor != 0) {
            throw new IllegalArgumentException(
                    "Free order amount must be zero");
        }
        if (paymentType != PaymentType.FREE && amountMinor <= 0) {
            throw new IllegalArgumentException(
                    "Paid order amount must be positive");
        }
        if (productType == ProductType.CATALOG && definitionId == null) {
            throw new IllegalArgumentException(
                    "Catalog order requires definition ID");
        }
        if (productType == ProductType.CUSTOM && definitionId != null) {
            throw new IllegalArgumentException(
                    "Custom order cannot have definition ID");
        }
        if (failureCode != null
                && (failureCode.isBlank() || failureCode.length() > 128)) {
            throw new IllegalArgumentException(
                    "Invalid order failure code");
        }
        if (state == PurchaseOrderState.GRANTED && entryId == null) {
            throw new IllegalArgumentException(
                    "Granted order requires entry ID");
        }
    }

    public Optional<DefinitionId> definition() {
        return Optional.ofNullable(definitionId);
    }

    public Optional<UUID> grantedEntryId() {
        return Optional.ofNullable(entryId);
    }

    public Optional<String> failure() {
        return Optional.ofNullable(failureCode);
    }
}
