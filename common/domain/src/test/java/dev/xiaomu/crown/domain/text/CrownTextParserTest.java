package dev.xiaomu.crown.domain.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CrownTextParserTest {
    private final CrownTextParser parser =
            new CrownTextParser(TextParsePolicy.serverDefault());

    @Test
    void parsesLegacyColorsDecorationsAndReset() {
        StyledText result = parser.parse("&a绿&l粗&r普通");

        assertEquals("绿粗普通", result.plainText());
        assertEquals(3, result.segments().size());

        StyledSegment green = result.segments().get(0);
        assertEquals("#55FF55", green.style().color().toHex());
        assertTrue(green.style().decorations().isEmpty());

        StyledSegment bold = result.segments().get(1);
        assertEquals("#55FF55", bold.style().color().toHex());
        assertTrue(bold.style().decorations().contains(
                TextDecoration.BOLD));

        StyledSegment reset = result.segments().get(2);
        assertEquals(TextStyle.EMPTY, reset.style());
    }

    @Test
    void parsesBothRgbPrefixForms() {
        StyledText result = parser.parse(
                "#112233甲&#AABBCC乙");

        assertEquals("甲乙", result.plainText());
        assertEquals(List.of("#112233", "#AABBCC"),
                result.segments().stream()
                        .map(segment -> segment.style().color().toHex())
                        .toList());
    }

    @Test
    void colorTagRestoresOuterStyleAfterClosing() {
        StyledText result = parser.parse(
                "&l前<color:#123456>中</color>后");

        assertEquals("前中后", result.plainText());
        assertEquals(3, result.segments().size());
        assertTrue(result.segments().get(1).style().decorations()
                .contains(TextDecoration.BOLD));
        assertEquals("#123456",
                result.segments().get(1).style().color().toHex());
        assertEquals(null, result.segments().get(2).style().color());
        assertTrue(result.segments().get(2).style().decorations()
                .contains(TextDecoration.BOLD));
    }

    @Test
    void gradientInterpolatesByUnicodeCodePoint() {
        StyledText result = parser.parse(
                "<gradient:#FF0000:#0000FF>A😀B</gradient>");

        assertEquals("A😀B", result.plainText());
        assertEquals(3, result.visibleCodePointCount());
        assertEquals(3, result.segments().size());
        assertEquals("#FF0000",
                result.segments().get(0).style().color().toHex());
        assertEquals("#800080",
                result.segments().get(1).style().color().toHex());
        assertEquals("#0000FF",
                result.segments().get(2).style().color().toHex());
    }

    @Test
    void formattingDoesNotCountTowardsVisibleLength() {
        CrownTextParser limited = new CrownTextParser(
                new TextParsePolicy(true, true, true, 128, 2));

        StyledText accepted = limited.parse(
                "<color:#FFFFFF>甲乙</color>");
        assertEquals(2, accepted.visibleCodePointCount());

        assertThrows(TextParseException.class,
                () -> limited.parse("&a甲乙丙"));
    }

    @Test
    void supportsMiniMessageImplicitClosingAndRejectsMalformedColors() {
        assertEquals("不需要闭合", parser.parse("<color:#FFFFFF>不需要闭合").plainText());
        assertEquals(TextParseError.MALFORMED_GRADIENT_TAG,
                assertThrows(TextParseException.class,
                        () -> parser.parse(
                                "<gradient:#FF0000:#GG0000>文字"))
                        .error());
        assertEquals(TextParseError.INCOMPLETE_RGB,
                assertThrows(TextParseException.class,
                        () -> parser.parse("&#123"))
                        .error());
    }

    @Test void supportsSectionSignsAndExpandedHex() {
        for (String source : List.of("&#12abEF字", "§#12abEF字", "#12abEF字",
                "&x&1&2&a&b&E&F字", "§x§1§2§a§b§E§F字", "<#12abEF>字")) {
            var text = parser.parse(source);
            assertEquals("字", text.plainText(), source);
            assertEquals("#12ABEF", text.segments().getFirst().style().color().toHex(), source);
        }
        assertEquals(parser.parse("&a绿&l粗&r普通"), parser.parse("§a绿§l粗§r普通"));
    }

    @Test void supportsMiniMessageNamedColorsDecorationsAndReset() {
        var text = parser.parse("<red><b>A<!bold>B</!bold>C</b></red>D<reset>E");
        assertEquals("ABCDE", text.plainText());
        assertTrue(text.segments().getFirst().style().decorations().contains(TextDecoration.BOLD));
        assertFalse(text.segments().get(1).style().decorations().contains(TextDecoration.BOLD));
        assertEquals(TextStyle.EMPTY, text.segments().getLast().style());
        assertEquals("#FF5555", parser.parse("<color:red>X</color>").segments().getFirst().style().color().toHex());
        assertEquals("#123456", parser.parse("<COLOUR:'#123456'>X").segments().getFirst().style().color().toHex());
    }

    @Test void supportsMultiStopGradientRainbowAndTransition() {
        for (String source : List.of("<gradient:red:green:blue>ABC</gradient>",
                "<rainbow>ABC</rainbow>", "<transition:red:blue:0.5>ABC</transition>")) {
            assertEquals("ABC", parser.parse(source).plainText());
            assertTrue(parser.parse(source).segments().stream().allMatch(s -> s.style().color() != null));
        }
    }

    @Test void newFormatsCannotBypassFeatureFlags() {
        var plain = new CrownTextParser(new TextParsePolicy(false, false, false, 512, 64));
        for (String source : List.of("§aA", "&x&1&2&3&4&5&6A", "§x§1§2§3§4§5§6A", "<red>A", "<b>A",
                "<#123456>A", "<color:'#123456'>A", "<rainbow>A", "<gradient:red:blue>A")) {
            assertThrows(TextParseException.class, () -> plain.parse(source), source);
        }
        assertEquals("<red>A", parser.parse("\\<red>A").plainText());
        assertEquals("A<click:run_command:/op>B</click>", parser.parse("A<click:run_command:/op>B</click>").plainText());
    }

    @Test void rgbDoesNotRequireLegacyFormattingToCloseItsTags() {
        var rgb = new CrownTextParser(new TextParsePolicy(false, true, true, 512, 64));
        for (String source : List.of("<color:#123456>A</color>", "<c:'#123456'>A</c>",
                "&#123456A", "&x&1&2&3&4&5&6A")) {
            assertEquals("#123456", rgb.parse(source).segments().getFirst().style().color().toHex());
        }
    }

    @Test void legacyColorsClearDecorationsAndMiniMessageRestoresOuterStyle() {
        for (String source : List.of("&lA&aB", "§lA§x§1§2§3§4§5§6B")) {
            var text = parser.parse(source);
            assertTrue(text.segments().getFirst().style().decorations().contains(TextDecoration.BOLD));
            assertFalse(text.segments().getLast().style().decorations().contains(TextDecoration.BOLD));
        }
        var text = parser.parse("<red>A<blue>B</blue>C</red>");
        assertEquals("#FF5555", text.segments().getLast().style().color().toHex());
    }

    @Test
    void enforcesFeatureFlags() {
        CrownTextParser plainOnly = new CrownTextParser(
                new TextParsePolicy(false, false, false, 64, 64));

        assertEquals(TextParseError.LEGACY_DISABLED,
                assertThrows(TextParseException.class,
                        () -> plainOnly.parse("&a绿色"))
                        .error());
        assertEquals(TextParseError.RGB_DISABLED,
                assertThrows(TextParseException.class,
                        () -> plainOnly.parse("#112233彩色"))
                        .error());
        assertEquals(TextParseError.GRADIENT_DISABLED,
                assertThrows(TextParseException.class,
                        () -> plainOnly.parse(
                                "<gradient:#000000:#FFFFFF>渐变</gradient>"))
                        .error());

        StyledText literal = plainOnly.parse("普通文字");
        assertFalse(literal.isEmpty());
    }

    @Test
    void rejectsControlCharactersAndSourceOverflow() {
        assertEquals(TextParseError.CONTROL_CHARACTER,
                assertThrows(TextParseException.class,
                        () -> parser.parse("非法\n换行"))
                        .error());

        CrownTextParser sourceLimited = new CrownTextParser(
                new TextParsePolicy(true, true, true, 3, 64));
        assertEquals(TextParseError.SOURCE_TOO_LONG,
                assertThrows(TextParseException.class,
                        () -> sourceLimited.parse("1234"))
                        .error());
    }

    @Test
    void langKeySuffixUsesKebabCase() {
        assertEquals("source-too-long",
                assertThrows(TextParseException.class,
                        () -> new CrownTextParser(
                                new TextParsePolicy(true, true, true, 2, 64))
                                .parse("三个字"))
                        .langKeySuffix());
        assertEquals("malformed-color-tag",
                new TextParseException(TextParseError.MALFORMED_COLOR_TAG,
                        "Malformed color tag", 0)
                        .langKeySuffix());
    }
}
