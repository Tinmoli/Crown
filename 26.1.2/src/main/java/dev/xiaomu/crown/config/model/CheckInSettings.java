package dev.xiaomu.crown.config.model;

import java.time.ZoneId;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Daily check-in policy. A failed reward roll still consumes the day's attempt. */
public record CheckInSettings(
        boolean enabled,
        long minimumReward,
        long maximumReward,
        int rewardChancePercent,
        ZoneId zoneId) {
    public CheckInSettings {
        Objects.requireNonNull(zoneId, "zoneId");
        if (minimumReward < 0 || maximumReward < minimumReward) {
            throw new IllegalArgumentException("Check-in reward requires 0 <= minimum <= maximum");
        }
        if (rewardChancePercent < 0 || rewardChancePercent > 100) {
            throw new IllegalArgumentException("Check-in reward chance must be 0..100");
        }
    }

    public long drawReward(RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        if (random.nextInt(100) >= rewardChancePercent) {
            return 0;
        }
        if (minimumReward == maximumReward) {
            return minimumReward;
        }
        if (maximumReward == Long.MAX_VALUE) {
            // Shift the inclusive interval to avoid overflowing the upper bound.
            if (minimumReward == 0) {
                return random.nextLong() & Long.MAX_VALUE;
            }
            return random.nextLong(minimumReward - 1, maximumReward) + 1;
        }
        return random.nextLong(minimumReward, maximumReward + 1);
    }
}
