package dev.xiaomu.crown.domain.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RgbColorTest {
    @Test
    void packsParsesAndFormats() {
        RgbColor color = RgbColor.parse("#12aBcF");

        assertEquals(0x12ABCF, color.packed());
        assertEquals("#12ABCF", color.toHex());
        assertEquals(color, RgbColor.fromPacked(0x12ABCF));
    }

    @Test
    void interpolationUsesRoundedLinearChannels() {
        assertEquals("#800080", RgbColor.interpolate(
                RgbColor.parse("#FF0000"),
                RgbColor.parse("#0000FF"),
                0.5).toHex());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> RgbColor.parse("#12345"));
        assertThrows(IllegalArgumentException.class,
                () -> RgbColor.fromPacked(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new RgbColor(256, 0, 0));
    }
}