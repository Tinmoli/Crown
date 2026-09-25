package dev.xiaomu.crown.domain.catalog;

import java.math.BigDecimal;
import java.util.Optional;

/** 称号币价格；0 表示免费领取，不接入外部经济。 */
public record PaymentPolicy(long titleCoinPrice) {
    public PaymentPolicy {
        if (titleCoinPrice < 0) {
            throw new IllegalArgumentException("Title coin price cannot be negative");
        }
    }

    public static PaymentPolicy titleCoin(long price) { return new PaymentPolicy(price); }
    public static PaymentPolicy free() { return new PaymentPolicy(0); }
    public PaymentType type() { return titleCoinPrice == 0 ? PaymentType.FREE : PaymentType.TITLE_COIN; }
    public Optional<BigDecimal> configuredPrice() { return Optional.of(BigDecimal.valueOf(titleCoinPrice)); }
}
