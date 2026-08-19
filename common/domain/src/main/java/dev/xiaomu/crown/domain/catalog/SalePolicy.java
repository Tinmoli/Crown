package dev.xiaomu.crown.domain.catalog;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** 商品销售窗口、全局库存和单玩家限购。-1 表示无限制。 */
public record SalePolicy(
        Instant startsAt,
        Instant endsAt,
        long globalStock,
        int perPlayerLimit
) {
    public SalePolicy {
        if (startsAt != null && endsAt != null
                && !startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException(
                    "Sale start must be before sale end");
        }
        if (globalStock < -1) {
            throw new IllegalArgumentException(
                    "Global stock must be -1 or non-negative");
        }
        if (perPlayerLimit == 0 || perPlayerLimit < -1) {
            throw new IllegalArgumentException(
                    "Per-player limit must be -1 or positive");
        }
    }

    public Optional<Instant> start() {
        return Optional.ofNullable(startsAt);
    }

    public Optional<Instant> end() {
        return Optional.ofNullable(endsAt);
    }

    public boolean onSaleAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return (startsAt == null || !instant.isBefore(startsAt))
                && (endsAt == null || !instant.isAfter(endsAt));
    }

    public boolean limitedStock() {
        return globalStock >= 0;
    }

    public boolean limitedPerPlayer() {
        return perPlayerLimit >= 0;
    }
}