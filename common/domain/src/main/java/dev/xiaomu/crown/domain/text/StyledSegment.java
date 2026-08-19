package dev.xiaomu.crown.domain.text;

import java.util.Objects;

/** 一段样式完全相同的字面文本。 */
public record StyledSegment(String text, TextStyle style) {
    public StyledSegment {
        text = Objects.requireNonNull(text, "text");
        style = Objects.requireNonNull(style, "style");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Text segment is empty");
        }
    }

    public int codePointCount() {
        return text.codePointCount(0, text.length());
    }
}