package dev.xiaomu.crown.storage.migration;

import dev.xiaomu.crown.config.model.StorageSettings;
import dev.xiaomu.crown.storage.StorageException;
import dev.xiaomu.crown.storage.model.StorageSummary;

import java.util.Objects;

/** 按 storage.yml 的迁移校验开关比较源端和目标端摘要。 */
public final class StorageSummaryVerifier {
    private StorageSummaryVerifier() {
    }

    public static void verify(
            StorageSummary source,
            StorageSummary target,
            StorageSettings.Verification verification
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(verification, "verification");

        requireEqual(
                "schema version",
                source.schemaVersion(),
                target.schemaVersion());
        if (verification.playerCount()) {
            requireEqual(
                    "player count",
                    source.playerCount(),
                    target.playerCount());
        }
        if (verification.ownedTitleCount()) {
            requireEqual(
                    "owned title count",
                    source.ownedTitleCount(),
                    target.ownedTitleCount());
        }
        if (verification.titleCoinTotal()) {
            requireEqual(
                    "title coin total",
                    source.titleCoinTotal(),
                    target.titleCoinTotal());
        }
        if (verification.orderCount()) {
            requireEqual(
                    "purchase order count",
                    source.purchaseOrderCount(),
                    target.purchaseOrderCount());
        }
        if (verification.cardCount()) {
            requireEqual(
                    "card count",
                    source.cardCount(),
                    target.cardCount());
        }
        if (verification.auditCount()) {
            requireEqual(
                    "audit count",
                    source.auditCount(),
                    target.auditCount());
        }

        // 这两项没有独立配置开关；始终验证可发现漏复制流水或库存状态。
        requireEqual(
                "title coin ledger count",
                source.titleCoinLedgerCount(),
                target.titleCoinLedgerCount());
        requireEqual(
                "sale counter count",
                source.saleCounterCount(),
                target.saleCounterCount());
    }

    private static void requireEqual(
            String name,
            long source,
            long target
    ) {
        if (source != target) {
            throw new StorageException(
                    "Crown storage migration verification failed for "
                            + name + ": source=" + source
                            + ", target=" + target);
        }
    }
}