package dev.xiaomu.crown.config;

import dev.xiaomu.crown.config.lang.SafeJsonLanguage;
import dev.xiaomu.crown.domain.text.TextParseError;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语言文件覆盖率检查。
 *
 * <p>所有非 GUI 文案来自配置。缺失语言键时
 * {@code LanguageCatalog.text} 会回退为键名本身，玩家将直接看到
 * {@code gui.reason.not-owned} 之类的内部标识，因此这里把键一致性固定为测试。</p>
 */
final class LanguageKeyCoverageTest {
    private static final List<String> LANGUAGES = List.of("zh_cn", "en_us");

    /** 代码直接引用的非 GUI 语言键，不含由 {@link TextParseError} 派生的部分。 */
    private static final List<String> REQUIRED_KEYS = List.of(
            "custom.invalid.too-short",
            "custom.invalid.forbidden-word",
            "custom.invalid.disabled",
            "custom.invalid.permission",
            "custom.invalid.unknown",
            "gui.reason.unavailable",
            "gui.reason.not-owned",
            "gui.value.custom-disabled",
            "shop.custom-title",
            "shop.status.disabled",
            "shop.status.hidden",
            "shop.status.not-on-sale",
            "shop.status.permission-denied",
            "shop.status.out-of-stock",
            "shop.status.player-limit-reached",
            "admin.title.unknown",
            "error.internal");

    @Test
    void chineseAndEnglishDefineTheSameKeys() throws IOException {
        assertEquals(load("zh_cn").keySet(), load("en_us").keySet(),
                "zh_cn and en_us must define identical language keys");
    }

    @Test
    void everyTextParseErrorHasALanguageKey() throws IOException {
        for (String language : LANGUAGES) {
            Map<String, String> entries = load(language);
            for (TextParseError error : TextParseError.values()) {
                String key = "custom.invalid." + error.name()
                        .toLowerCase(Locale.ROOT).replace('_', '-');
                assertTrue(entries.containsKey(key),
                        language + " is missing " + key);
            }
        }
    }

    @Test
    void requiredNonGuiKeysAreDefined() throws IOException {
        for (String language : LANGUAGES) {
            Map<String, String> entries = load(language);
            assertEquals("%0% %1%", entries.get("help.line"));
            for (String section : List.of("player-header", "admin-header", "main", "warehouse", "shop", "custom", "balance", "give", "take", "set", "look", "title", "reload")) {
                String line = entries.get("help." + section);
                assertTrue(line != null && line.contains("&"));
                assertFalse(line.contains("%0%"));
                assertFalse(line.contains("/crown coin "));
            }
            for (String key : REQUIRED_KEYS) {
                assertTrue(entries.containsKey(key),
                        language + " is missing " + key);
            }
        }
    }

    @Test
    void invalidTitleTemplatePlacesTheReasonAtArgumentOne()
            throws IOException {
        for (String language : LANGUAGES) {
            assertTrue(load(language).get("custom.invalid").contains("%1%"),
                    language + " custom.invalid must render the reason");
        }
    }

    @Test
    void templatesWithoutArgumentsDoNotKeepPlaceholders()
            throws IOException {
        for (String language : LANGUAGES) {
            Map<String, String> entries = load(language);
            for (String key : List.of("command.reload.failed")) {
                assertFalse(entries.get(key).contains("%1%"),
                        language + " " + key
                                + " must not expose a raw failure message");
            }
        }
    }

    private static Map<String, String> load(String language)
            throws IOException {
        String path = "/crown/defaults/lang/" + language + ".json";
        try (InputStream stream = LanguageKeyCoverageTest.class
                .getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing resource " + path);
            }
            return new SafeJsonLanguage().parse(
                    new String(stream.readAllBytes(),
                            StandardCharsets.UTF_8));
        }
    }
}
