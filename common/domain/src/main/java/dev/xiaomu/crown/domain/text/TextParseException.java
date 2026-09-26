package dev.xiaomu.crown.domain.text;

/** 称号或 GUI 文本源格式无效。 */
public final class TextParseException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final TextParseError error;
    private final int sourceIndex;

    public TextParseException(
            TextParseError error,
            String message,
            int sourceIndex
    ) {
        super(message + " at source index " + sourceIndex);
        this.error = java.util.Objects.requireNonNull(error, "error");
        this.sourceIndex = sourceIndex;
    }

    /** 面向语言键映射的稳定错误码。 */
    public TextParseError error() {
        return error;
    }

    public int sourceIndex() {
        return sourceIndex;
    }

    /** 错误码对应的语言键后缀：小写并把下划线转连字符。 */
    public String langKeySuffix() {
        return error.name().toLowerCase(java.util.Locale.ROOT)
                .replace('_', '-');
    }
}
