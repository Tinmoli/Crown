package dev.xiaomu.crown.domain.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TitleTextOutputTest {
    private final CrownTextParser parser =
            new CrownTextParser(TextParsePolicy.serverDefault());

    @Test void messageKeepsTheCompleteStyledTitle() {
        StyledText title = parser.parse(
                "&7[<gradient:#FFD700:#FF8C00>活动冠军</gradient>&7]");
        StyledText message = TitleTextOutput.message(
                "%0%&a购买成功：%1%", "&8[&#FFD700Crown&8] ", title);

        assertEquals("[Crown] 购买成功：[活动冠军]", message.plainText());
        assertEquals(title.segments(), message.segments().subList(
                message.segments().size() - title.segments().size(),
                message.segments().size()));
    }

    @Test void compatibilityOutputsKeepBracketsColorsAndDecorations() {
        StyledText title = parser.parse("&7[&#12ABEF&l彩色&r&7]");
        String legacy = TitleTextOutput.legacy(title);
        String mini = TitleTextOutput.miniMessage(title);

        assertTrue(legacy.contains("["));
        assertTrue(legacy.contains("&#12ABEF&l彩色"));
        assertTrue(legacy.contains("]"));
        assertTrue(mini.toLowerCase(java.util.Locale.ROOT).contains("#12abef"));
        assertTrue(mini.contains("彩色"));
    }
}
