package dev.xiaomu.crown.storage.model;

import dev.xiaomu.crown.domain.catalog.DefinitionId;

import java.util.Objects;

/** 商品已经售出和正在被订单预留的全局计数。 */
public record SaleCounterRecord(
        DefinitionId definitionId,
        long soldCount,
        long reservedCount,
        long revision
) {
    public SaleCounterRecord {
        definitionId = Objects.requireNonNull(
                definitionId, "definitionId");
        if (soldCount < 0 || reservedCount < 0 || revision < 0) {
            throw new IllegalArgumentException(
                    "Sale counter values cannot be negative");
        }
    }

    public long occupiedStock() {
        try {
            return Math.addExact(soldCount, reservedCount);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                    "Sale counter stock overflow", exception);
        }
    }
}