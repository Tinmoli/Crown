package dev.xiaomu.crown.domain.text;

import java.util.Locale;
import java.util.Objects;

/** 与 Minecraft API 解耦的 24 位 RGB 颜色。 */
public record RgbColor(int red, int green, int blue) {
    public RgbColor {
        requireChannel(red, "red");
        requireChannel(green, "green");
        requireChannel(blue, "blue");
    }

    public static RgbColor fromPacked(int rgb) {
        if (rgb < 0 || rgb > 0xFFFFFF) {
            throw new IllegalArgumentException("RGB value is out of range");
        }
        return new RgbColor(
                (rgb >>> 16) & 0xFF,
                (rgb >>> 8) & 0xFF,
                rgb & 0xFF
        );
    }

    public static RgbColor parse(String source) {
        Objects.requireNonNull(source, "source");
        String value = source.startsWith("#") ? source.substring(1) : source;
        if (value.length() != 6 || !isHex(value)) {
            throw new IllegalArgumentException("Expected a six-digit RGB color");
        }
        return fromPacked(Integer.parseInt(value, 16));
    }

    public int packed() {
        return (red << 16) | (green << 8) | blue;
    }

    public String toHex() {
        return String.format(Locale.ROOT, "#%06X", packed());
    }

    /**
     * 按线性 RGB 通道插值。比例 0 返回起始颜色，比例 1 返回结束颜色。
     */
    public static RgbColor interpolate(
            RgbColor start,
            RgbColor end,
            double ratio
    ) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!Double.isFinite(ratio) || ratio < 0.0 || ratio > 1.0) {
            throw new IllegalArgumentException(
                    "Interpolation ratio must be between zero and one");
        }
        return new RgbColor(
                interpolateChannel(start.red, end.red, ratio),
                interpolateChannel(start.green, end.green, ratio),
                interpolateChannel(start.blue, end.blue, ratio)
        );
    }

    static boolean isHex(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean digit = character >= '0' && character <= '9';
            boolean lower = character >= 'a' && character <= 'f';
            boolean upper = character >= 'A' && character <= 'F';
            if (!digit && !lower && !upper) {
                return false;
            }
        }
        return true;
    }

    private static int interpolateChannel(int start, int end, double ratio) {
        return (int) Math.round(start + (end - start) * ratio);
    }

    private static void requireChannel(int value, String name) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException(name + " is out of range");
        }
    }
}