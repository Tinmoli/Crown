package dev.xiaomu.crown.runtime.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** 把配置中的主单位价格转换为 Mint 最小单位 long。 */
public final class MintMoneyCodec {
    private MintMoneyCodec() {
    }

    public static long toMinorUnits(
            BigDecimal majorUnits,
            int scale,
            RoundingMode roundingMode
    ) {
        Objects.requireNonNull(majorUnits, "majorUnits");
        Objects.requireNonNull(roundingMode, "roundingMode");
        if (scale < 0 || scale > 9
                || majorUnits.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Invalid Mint price conversion input");
        }

        BigDecimal rounded = majorUnits
                .movePointRight(scale)
                .setScale(0, roundingMode);
        final long value;
        try {
            value = rounded.longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Mint price is outside the supported long range",
                    exception);
        }
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "Mint price rounds to zero minor units");
        }
        return value;
    }
}