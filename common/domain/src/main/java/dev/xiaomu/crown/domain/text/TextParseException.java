package dev.xiaomu.crown.domain.text;

/** 称号或 GUI 文本源格式无效。 */
public final class TextParseException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final int sourceIndex;

    public TextParseException(String message, int sourceIndex) {
        super(message + " at source index " + sourceIndex);
        this.sourceIndex = sourceIndex;
    }

    public int sourceIndex() {
        return sourceIndex;
    }
}