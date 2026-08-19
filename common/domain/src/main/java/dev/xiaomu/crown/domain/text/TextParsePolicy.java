package dev.xiaomu.crown.domain.text;

/** 文本来源的功能开关和安全长度上限。 */
public record TextParsePolicy(
        boolean allowLegacyFormatting,
        boolean allowRgb,
        boolean allowGradient,
        int maximumSourceLength,
        int maximumVisibleLength
) {
    public TextParsePolicy {
        if (maximumSourceLength < 1 || maximumVisibleLength < 1) {
            throw new IllegalArgumentException(
                    "Text length limits must be positive");
        }
    }

    public static TextParsePolicy serverDefault() {
        return new TextParsePolicy(true, true, true, 512, 64);
    }
}