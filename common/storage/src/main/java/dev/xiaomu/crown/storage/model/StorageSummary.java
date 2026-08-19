package dev.xiaomu.crown.storage.model;

/** 存储状态、迁移空目标保护和迁移后校验使用的汇总。 */
public record StorageSummary(
        int schemaVersion,
        long playerCount,
        long ownedTitleCount,
        long purchaseOrderCount,
        long titleCoinLedgerCount,
        long titleCoinTotal,
        long saleCounterCount,
        long cardCount,
        long auditCount
) {
    public StorageSummary {
        if (schemaVersion < 0
                || playerCount < 0
                || ownedTitleCount < 0
                || purchaseOrderCount < 0
                || titleCoinLedgerCount < 0
                || titleCoinTotal < 0
                || saleCounterCount < 0
                || cardCount < 0
                || auditCount < 0) {
            throw new IllegalArgumentException(
                    "Storage summary values cannot be negative");
        }
    }

    public boolean hasBusinessData() {
        return playerCount != 0
                || ownedTitleCount != 0
                || purchaseOrderCount != 0
                || titleCoinLedgerCount != 0
                || titleCoinTotal != 0
                || saleCounterCount != 0
                || cardCount != 0
                || auditCount != 0;
    }
}