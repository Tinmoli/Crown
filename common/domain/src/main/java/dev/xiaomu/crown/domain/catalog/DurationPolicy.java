package dev.xiaomu.crown.domain.catalog;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** 商品发放时用于计算仓库条目到期时间的策略。 */
public record DurationPolicy(DurationType type, int days) {
    private static final int MAXIMUM_DAYS = 36_500;

    public DurationPolicy {
        type = Objects.requireNonNull(type, "type");
        if (type == DurationType.PERMANENT && days != 0) {
            throw new IllegalArgumentException(
                    "Permanent duration must have zero days");
        }
        if (type == DurationType.LIMITED
                && (days < 1 || days > MAXIMUM_DAYS)) {
            throw new IllegalArgumentException(
                    "Limited duration days must be between 1 and "
                            + MAXIMUM_DAYS);
        }
    }

    public static DurationPolicy permanent() {
        return new DurationPolicy(DurationType.PERMANENT, 0);
    }

    public static DurationPolicy limited(int days) {
        return new DurationPolicy(DurationType.LIMITED, days);
    }

    public Optional<Instant> expiresAt(Instant grantedAt) {
        Objects.requireNonNull(grantedAt, "grantedAt");
        if (type == DurationType.PERMANENT) {
            return Optional.empty();
        }
        return Optional.of(grantedAt.plus(Duration.ofDays(days)));
    }
}