package dev.xiaomu.crown.domain.catalog;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * 配置阶段的商品支付策略。
 *
 * <p>Mint 金额暂以十进制主单位保存；运行时读取当前 Provider 的货币
 * scale 和舍入规则后再转换为最小单位。称号币必须是整数。</p>
 */
public record PaymentPolicy(
        PaymentType type,
        NamespacedId mintCurrency,
        BigDecimal price
) {
    public PaymentPolicy {
        type = Objects.requireNonNull(type, "type");
        if (type == PaymentType.FREE) {
            if (mintCurrency != null || price != null) {
                throw new IllegalArgumentException(
                        "Free payment cannot have currency or price");
            }
        } else {
            price = Objects.requireNonNull(price, "price")
                    .stripTrailingZeros();
            if (price.signum() <= 0) {
                throw new IllegalArgumentException(
                        "Paid product price must be positive");
            }
        }

        if (type == PaymentType.MINT) {
            mintCurrency = Objects.requireNonNull(
                    mintCurrency, "mintCurrency").requireSimplePath();
            if (price.scale() > 9) {
                throw new IllegalArgumentException(
                        "Mint price has more than nine decimal places");
            }
        } else if (mintCurrency != null) {
            throw new IllegalArgumentException(
                    "Only Mint payment has a currency ID");
        }

        if (type == PaymentType.TITLE_COIN
                && price.scale() > 0) {
            throw new IllegalArgumentException(
                    "Title coin price must be an integer");
        }
    }

    public static PaymentPolicy free() {
        return new PaymentPolicy(PaymentType.FREE, null, null);
    }

    public static PaymentPolicy mint(
            NamespacedId currency,
            BigDecimal price
    ) {
        return new PaymentPolicy(PaymentType.MINT, currency, price);
    }

    public static PaymentPolicy titleCoin(long price) {
        return new PaymentPolicy(
                PaymentType.TITLE_COIN,
                null,
                BigDecimal.valueOf(price));
    }

    public Optional<NamespacedId> mintCurrencyId() {
        return Optional.ofNullable(mintCurrency);
    }

    public Optional<BigDecimal> configuredPrice() {
        return Optional.ofNullable(price);
    }

    public long titleCoinPrice() {
        if (type != PaymentType.TITLE_COIN) {
            throw new IllegalStateException(
                    "Payment does not use title coins");
        }
        return price.longValueExact();
    }
}