package dev.xiaomu.crown.domain.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Crown 文本源解析器。
 *
 * <p>支持旧式颜色/修饰码、三种十六进制写法、颜色标签和渐变标签。
 * 解析结果不引用任何 Minecraft 类型，可供配置校验、数据库业务和各版本
 * Fabric 文本适配层共同使用。</p>
 */
public final class CrownTextParser {
    private static final Pattern COLOR_OPEN = Pattern.compile(
            "<color:#([0-9A-Fa-f]{6})>");
    private static final Pattern GRADIENT_OPEN = Pattern.compile(
            "<gradient:#([0-9A-Fa-f]{6}):#([0-9A-Fa-f]{6})>");

    private final TextParsePolicy policy;

    public CrownTextParser(TextParsePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public StyledText parse(String source) {
        Objects.requireNonNull(source, "source");
        if (source.length() > policy.maximumSourceLength()) {
            throw new TextParseException(
                    "Text source is too long",
                    policy.maximumSourceLength());
        }
        rejectControlCharacters(source);

        var state = new ParserState(source);
        StyledText parsed = state.parseSection(null, TextStyle.EMPTY);
        if (parsed.visibleCodePointCount() > policy.maximumVisibleLength()) {
            throw new TextParseException(
                    "Visible text is too long",
                    source.length());
        }
        return parsed;
    }

    private static void rejectControlCharacters(String source) {
        for (int offset = 0; offset < source.length();) {
            int codePoint = source.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new TextParseException(
                        "Control characters are not allowed", offset);
            }
            offset += Character.charCount(codePoint);
        }
    }

    private final class ParserState {
        private final String source;
        private int index;

        private ParserState(String source) {
            this.source = source;
        }

        private StyledText parseSection(
                String expectedClosing,
                TextStyle inheritedStyle
        ) {
            var output = new SegmentBuilder();
            TextStyle currentStyle = inheritedStyle;

            while (index < source.length()) {
                if (expectedClosing != null
                        && source.startsWith(expectedClosing, index)) {
                    index += expectedClosing.length();
                    return output.build();
                }
                rejectUnexpectedClosing();

                var colorMatcher = COLOR_OPEN.matcher(source);
                colorMatcher.region(index, source.length());
                if (colorMatcher.lookingAt()) {
                    requireRgb(index);
                    RgbColor color = RgbColor.parse(colorMatcher.group(1));
                    index = colorMatcher.end();
                    output.append(parseSection(
                            "</color>",
                            currentStyle.withColor(color)));
                    continue;
                }
                if (source.startsWith("<color:", index)) {
                    throw error("Malformed color tag");
                }

                var gradientMatcher = GRADIENT_OPEN.matcher(source);
                gradientMatcher.region(index, source.length());
                if (gradientMatcher.lookingAt()) {
                    requireGradient(index);
                    RgbColor start = RgbColor.parse(
                            gradientMatcher.group(1));
                    RgbColor end = RgbColor.parse(
                            gradientMatcher.group(2));
                    index = gradientMatcher.end();
                    StyledText body = parseSection(
                            "</gradient>", currentStyle);
                    output.append(applyGradient(body, start, end));
                    continue;
                }
                if (source.startsWith("<gradient:", index)) {
                    throw error("Malformed gradient tag");
                }

                StyleToken token = readStyleToken(currentStyle);
                if (token != null) {
                    currentStyle = token.style();
                    index = token.nextIndex();
                    continue;
                }

                int codePoint = source.codePointAt(index);
                output.appendCodePoint(codePoint, currentStyle);
                index += Character.charCount(codePoint);
            }

            if (expectedClosing != null) {
                throw new TextParseException(
                        "Missing closing tag " + expectedClosing, index);
            }
            return output.build();
        }

        private void rejectUnexpectedClosing() {
            if (source.startsWith("</color", index)
                    || source.startsWith("</gradient", index)) {
                throw error("Unexpected or mismatched closing tag");
            }
        }

        private StyleToken readStyleToken(TextStyle currentStyle) {
            if (source.charAt(index) == '&'
                    && index + 1 < source.length()) {
                if (source.charAt(index + 1) == '#') {
                    return readAmpersandRgb(currentStyle);
                }

                char code = Character.toLowerCase(
                        source.charAt(index + 1));
                RgbColor legacyColor = legacyColor(code);
                if (legacyColor != null) {
                    requireLegacy(index);
                    return new StyleToken(
                            currentStyle.withLegacyColor(legacyColor),
                            index + 2);
                }

                TextDecoration decoration = legacyDecoration(code);
                if (decoration != null) {
                    requireLegacy(index);
                    return new StyleToken(
                            currentStyle.withDecoration(decoration),
                            index + 2);
                }

                if (code == 'r') {
                    requireLegacy(index);
                    return new StyleToken(TextStyle.EMPTY, index + 2);
                }
            }

            if (source.charAt(index) == '#'
                    && index + 7 <= source.length()) {
                String digits = source.substring(index + 1, index + 7);
                if (RgbColor.isHex(digits)) {
                    requireRgb(index);
                    return new StyleToken(
                            currentStyle.withColor(RgbColor.parse(digits)),
                            index + 7);
                }
            }
            return null;
        }

        private StyleToken readAmpersandRgb(TextStyle currentStyle) {
            if (index + 8 > source.length()) {
                throw error("Incomplete ampersand RGB color");
            }
            String digits = source.substring(index + 2, index + 8);
            if (!RgbColor.isHex(digits)) {
                throw error("Malformed ampersand RGB color");
            }
            requireRgb(index);
            return new StyleToken(
                    currentStyle.withColor(RgbColor.parse(digits)),
                    index + 8);
        }

        private TextParseException error(String message) {
            return new TextParseException(message, index);
        }
    }

    private void requireLegacy(int index) {
        if (!policy.allowLegacyFormatting()) {
            throw new TextParseException(
                    "Legacy formatting is disabled", index);
        }
    }

    private void requireRgb(int index) {
        if (!policy.allowRgb()) {
            throw new TextParseException("RGB colors are disabled", index);
        }
    }

    private void requireGradient(int index) {
        if (!policy.allowGradient()) {
            throw new TextParseException("Gradients are disabled", index);
        }
        requireRgb(index);
    }

    private static StyledText applyGradient(
            StyledText source,
            RgbColor start,
            RgbColor end
    ) {
        int count = source.visibleCodePointCount();
        if (count == 0) {
            return StyledText.empty();
        }

        var result = new SegmentBuilder();
        int position = 0;
        for (StyledSegment segment : source.segments()) {
            String text = segment.text();
            for (int offset = 0; offset < text.length();) {
                int codePoint = text.codePointAt(offset);
                double ratio = count == 1
                        ? 0.0
                        : (double) position / (double) (count - 1);
                RgbColor color = RgbColor.interpolate(start, end, ratio);
                result.appendCodePoint(
                        codePoint,
                        segment.style().withColor(color));
                position++;
                offset += Character.charCount(codePoint);
            }
        }
        return result.build();
    }

    private static RgbColor legacyColor(char code) {
        return switch (code) {
            case '0' -> RgbColor.fromPacked(0x000000);
            case '1' -> RgbColor.fromPacked(0x0000AA);
            case '2' -> RgbColor.fromPacked(0x00AA00);
            case '3' -> RgbColor.fromPacked(0x00AAAA);
            case '4' -> RgbColor.fromPacked(0xAA0000);
            case '5' -> RgbColor.fromPacked(0xAA00AA);
            case '6' -> RgbColor.fromPacked(0xFFAA00);
            case '7' -> RgbColor.fromPacked(0xAAAAAA);
            case '8' -> RgbColor.fromPacked(0x555555);
            case '9' -> RgbColor.fromPacked(0x5555FF);
            case 'a' -> RgbColor.fromPacked(0x55FF55);
            case 'b' -> RgbColor.fromPacked(0x55FFFF);
            case 'c' -> RgbColor.fromPacked(0xFF5555);
            case 'd' -> RgbColor.fromPacked(0xFF55FF);
            case 'e' -> RgbColor.fromPacked(0xFFFF55);
            case 'f' -> RgbColor.fromPacked(0xFFFFFF);
            default -> null;
        };
    }

    private static TextDecoration legacyDecoration(char code) {
        return switch (code) {
            case 'k' -> TextDecoration.OBFUSCATED;
            case 'l' -> TextDecoration.BOLD;
            case 'm' -> TextDecoration.STRIKETHROUGH;
            case 'n' -> TextDecoration.UNDERLINED;
            case 'o' -> TextDecoration.ITALIC;
            default -> null;
        };
    }

    private record StyleToken(TextStyle style, int nextIndex) {
    }

    /** 合并相邻同样式段，减少后续 Minecraft Component 节点数量。 */
    private static final class SegmentBuilder {
        private final List<StyledSegment> segments = new ArrayList<>();
        private StringBuilder pendingText;
        private TextStyle pendingStyle;

        private void appendCodePoint(int codePoint, TextStyle style) {
            Objects.requireNonNull(style, "style");
            if (pendingText == null || !style.equals(pendingStyle)) {
                flush();
                pendingText = new StringBuilder();
                pendingStyle = style;
            }
            pendingText.appendCodePoint(codePoint);
        }

        private void append(StyledText text) {
            for (StyledSegment segment : text.segments()) {
                String value = segment.text();
                for (int offset = 0; offset < value.length();) {
                    int codePoint = value.codePointAt(offset);
                    appendCodePoint(codePoint, segment.style());
                    offset += Character.charCount(codePoint);
                }
            }
        }

        private StyledText build() {
            flush();
            return segments.isEmpty()
                    ? StyledText.empty()
                    : new StyledText(segments);
        }

        private void flush() {
            if (pendingText == null || pendingText.isEmpty()) {
                return;
            }
            segments.add(new StyledSegment(
                    pendingText.toString(), pendingStyle));
            pendingText = null;
            pendingStyle = null;
        }
    }
}