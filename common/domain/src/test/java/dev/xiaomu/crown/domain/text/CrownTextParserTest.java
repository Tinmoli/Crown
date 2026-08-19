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
    void rejectsMalformedOrMismatchedTags() {
        assertThrows(TextParseException.class,
                () -> parser.parse("<color:#FFFFFF>缺少闭合"));
        assertThrows(TextParseException.class,
                () -> parser.parse(
                        "<color:#FFFFFF>错序</gradient></color>"));
        assertThrows(TextParseException.class,
                () -> parser.parse("<gradient:#FF0000:#GG0000>文字"));
        assertThrows(TextParseException.class,
                () -> parser.parse("&#123"));
    }

    @Test
    void enforcesFeatureFlags() {
        CrownTextParser plainOnly = new CrownTextParser(
                new TextParsePolicy(false, false, false, 64, 64));

        assertThrows(TextParseException.class,
                () -> plainOnly.parse("&a绿色"));
        assertThrows(TextParseException.class,
                () -> plainOnly.parse("#112233彩色"));
        assertThrows(TextParseException.class,
                () -> plainOnly.parse(
                        "<gradient:#000000:#FFFFFF>渐变</gradient>"));

        StyledText literal = plainOnly.parse("普通文字");
        assertFalse(literal.isEmpty());
    }

    @Test
    void rejectsControlCharactersAndSourceOverflow() {
        assertThrows(TextParseException.class,
                () -> parser.parse("非法\n换行"));

        CrownTextParser sourceLimited = new CrownTextParser(
                new TextParsePolicy(true, true, true, 3, 64));
        assertThrows(TextParseException.class,
                () -> sourceLimited.parse("1234"));
    }
}