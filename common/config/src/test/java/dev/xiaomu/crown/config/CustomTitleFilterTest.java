package dev.xiaomu.crown.config;

import dev.xiaomu.crown.config.runtime.CrownConfigurationBootstrap;
import dev.xiaomu.crown.domain.text.CrownTextParser;
import dev.xiaomu.crown.domain.text.TextParsePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CustomTitleFilterTest {
    @TempDir Path root;

    @Test void filtersVisibleTextAcrossColorFormatsAndReloads() throws Exception {
        var bootstrap = new CrownConfigurationBootstrap();
        bootstrap.initialize(root);
        Path config = root.resolve("config.yml");
        String original = Files.readString(config);
        Files.writeString(config, original.replaceFirst("(?m)^  forbidden-words:.*$",
                "  forbidden-words: [\"禁词\", \"Admin\"]"));
        var rules = bootstrap.initialize(root).snapshot().core().customTitle();
        var parser = new CrownTextParser(TextParsePolicy.serverDefault());
        for (String source : List.of("禁词", "禁&a词", "禁§c词", "禁&#123456词",
                "禁&x&1&2&3&4&5&6词", "<red>禁</red><blue>词</blue>",
                "<gradient:red:blue>禁词</gradient>", "<rainbow>禁词</rainbow>",
                "禁\u200b词", "ADMIN", "Ａｄｍｉｎ", "xadminx")) {
            assertTrue(rules.rejectsTitle(parser.parse(source), false), source);
            assertFalse(rules.rejectsTitle(parser.parse(source), true), "OP: " + source);
        }
        assertFalse(rules.rejectsTitle(parser.parse("<red>冒险家</red>"), false));
        Files.writeString(config, original.replaceFirst("(?m)^  forbidden-words:.*$", "  forbidden-words: []"));
        var cleared = bootstrap.initialize(root).snapshot().core().customTitle();
        assertFalse(cleared.rejectsTitle(parser.parse("禁词"), false));
    }
}
