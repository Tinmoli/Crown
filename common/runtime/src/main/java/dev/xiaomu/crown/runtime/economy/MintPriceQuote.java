package dev.xiaomu.crown.runtime.economy;

import dev.xiaomu.crown.domain.catalog.NamespacedId;

import java.math.RoundingMode;
import java.util.Objects;

/** 订单创建前解析出的稳定 Mint 最小单位报价。 */
public record MintPriceQuote(
        NamespacedId currency,
        long amountMinor,
        int scale,
        RoundingMode roundingMode
) {
    public MintPriceQuote {
        currency = Objects.requireNonNull(currency, "currency")
                .requireSimplePath();
        roundingMode = Objects.requireNonNull(
                roundingMode, "roundingMode");
        if (amountMinor <= 0 || scale < 0 || scale > 9) {
            throw new IllegalArgumentException(
                    "Invalid Mint price quote");
        }
    }
}