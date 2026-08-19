package dev.xiaomu.crown.domain.text;

import java.util.Objects;
import java.util.Set;

/** 单个文本段的不可变样式。 */
public record TextStyle(RgbColor color, Set<TextDecoration> decorations) {
    public static final TextStyle EMPTY = new TextStyle(null, Set.of());

    public TextStyle {
        decorations = Set.copyOf(Objects.requireNonNull(
                decorations, "decorations"));
    }

    public TextStyle withColor(RgbColor newColor) {
        return new TextStyle(Objects.requireNonNull(newColor, "newColor"),
                decorations);
    }

    /**
     * 旧式颜色码与原版语义一致：设置颜色时同时清除已有修饰。
     */
    public TextStyle withLegacyColor(RgbColor newColor) {
        return new TextStyle(Objects.requireNonNull(newColor, "newColor"),
                Set.of());
    }

    public TextStyle withDecoration(TextDecoration decoration) {
        Objects.requireNonNull(decoration, "decoration");
        if (decorations.contains(decoration)) {
            return this;
        }
        var copy = new java.util.HashSet<>(decorations);
        copy.add(decoration);
        return new TextStyle(color, copy);
    }
}