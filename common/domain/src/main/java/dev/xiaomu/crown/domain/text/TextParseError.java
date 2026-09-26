package dev.xiaomu.crown.domain.text;

/**
 * 文本解析失败的稳定错误码。
 *
 * <p>domain 层不接触语言文件；版本适配层按
 * {@code custom.invalid.} + 常量名（小写、下划线转连字符）查找语言键，
 * 英文消息仅保留给日志和测试。</p>
 */
public enum TextParseError {
    SOURCE_TOO_LONG,
    VISIBLE_TOO_LONG,
    CONTROL_CHARACTER,
    MALFORMED_COLOR_TAG,
    MALFORMED_GRADIENT_TAG,
    MISSING_CLOSE_TAG,
    MISMATCHED_CLOSE_TAG,
    INCOMPLETE_RGB,
    MALFORMED_RGB,
    LEGACY_DISABLED,
    RGB_DISABLED,
    GRADIENT_DISABLED
}
