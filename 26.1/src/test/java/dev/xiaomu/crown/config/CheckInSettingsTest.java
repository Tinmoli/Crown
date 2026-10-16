package dev.xiaomu.crown.config;

import static org.junit.jupiter.api.Assertions.*;

import dev.xiaomu.crown.config.model.CheckInSettings;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Random;

final class CheckInSettingsTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void validatesRangeAndChance() {
        assertThrows(
                IllegalArgumentException.class, () -> new CheckInSettings(true, -1, 50, 100, ZONE));
        assertThrows(
                IllegalArgumentException.class, () -> new CheckInSettings(true, 51, 50, 100, ZONE));
        assertThrows(
                IllegalArgumentException.class, () -> new CheckInSettings(true, 0, 50, 101, ZONE));
        assertThrows(
                IllegalArgumentException.class, () -> new CheckInSettings(true, 0, 50, -1, ZONE));
    }

    @Test
    void randomRewardsIncludeBothEndpoints() {
        var settings = new CheckInSettings(true, 0, 50, 100, ZONE);
        var random = new Random(42);
        var observed = new HashSet<Long>();
        for (int attempt = 0; attempt < 10000; attempt++) {
            long reward = settings.drawReward(random);
            assertTrue(reward >= 0 && reward <= 50);
            observed.add(reward);
        }
        assertEquals(51, observed.size());
    }

    @Test
    void supportsFixedZeroAndMaximumLongRewards() {
        var random = new Random(42);
        assertEquals(0, new CheckInSettings(true, 10, 50, 0, ZONE).drawReward(random));
        assertEquals(10, new CheckInSettings(true, 10, 10, 100, ZONE).drawReward(random));
        assertEquals(
                Long.MAX_VALUE,
                new CheckInSettings(true, Long.MAX_VALUE, Long.MAX_VALUE, 100, ZONE)
                        .drawReward(random));
        for (int attempt = 0; attempt < 100; attempt++) {
            long reward =
                    new CheckInSettings(true, Long.MAX_VALUE - 1, Long.MAX_VALUE, 100, ZONE)
                            .drawReward(random);
            assertTrue(reward >= Long.MAX_VALUE - 1);
            assertTrue(
                    new CheckInSettings(true, 0, Long.MAX_VALUE, 100, ZONE).drawReward(random)
                            >= 0);
        }
    }

    @Test
    void resetsAtMidnightInConfiguredZone() {
        var before = Instant.parse("2026-09-28T15:59:59Z").atZone(ZONE).toLocalDate();
        var after = Instant.parse("2026-09-28T16:00:00Z").atZone(ZONE).toLocalDate();
        assertEquals(before.plusDays(1), after);
    }
}
