package dev.xiaomu.crown.config;

import dev.xiaomu.crown.config.io.SafeYaml;
import dev.xiaomu.crown.config.lang.JsonLanguageSynchronizer;
import dev.xiaomu.crown.config.lang.SafeJsonLanguage;
import dev.xiaomu.crown.config.sync.ConfigurationKind;
import dev.xiaomu.crown.config.sync.YamlConfigurationSynchronizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfigurationSynchronizationTest {
    private static final Pattern GUI_LANGUAGE_KEY =
            Pattern.compile("@([a-z0-9][a-z0-9._-]*)");
    private static final List<String> DEFAULT_GUI_FILES = List.of(
            "admin-shop.yml",
            "admin-warehouse.yml",
            "custom-confirm.yml",
            "delete-confirm.yml",
            "main.yml",
            "purchase-confirm.yml",
            "shop.yml",
            "warehouse.yml");
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-01-01T00:00:00Z"),
            ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void yamlRejectsDuplicateKeysCustomTagsAndDeepNesting() {
        SafeYaml yaml = new SafeYaml();

        assertThrows(IllegalArgumentException.class,
                () -> yaml.loadMap("a: 1\na: 2\n"));
        assertThrows(IllegalArgumentException.class,
                () -> yaml.loadMap("!!java.lang.String test"));

        String deep = "value: true";
        for (int index = 0; index < 52; index++) {
            deep = "x:\n" + deep.indent(2);
        }
        String finalDeep = deep;
        assertThrows(IllegalArgumentException.class,
                () -> yaml.loadMap(finalDeep));
    }

    @Test
    void yamlSynchronizationPreservesOpenMapAndRestoresComments()
            throws Exception {
        Path target = temporaryDirectory.resolve("storage.yml");
        Files.writeString(target, """
                config-version: 1
                type: "mysql"
                obsolete: true
                mysql:
                  host: "db.internal"
                  parameters:
                    customFlag: "kept"
                """, StandardCharsets.UTF_8);
        String template = """
                # 标准中文头部注释
                config-version: 2

                # 存储类型注释
                type: "sqlite"

                mysql:
                  # 主机注释
                  host: "127.0.0.1"
                  port: 3306
                  # 开放参数
                  parameters:
                    useSSL: false
                """;

        var result = new YamlConfigurationSynchronizer(
                new SafeYaml(), FIXED_CLOCK).synchronize(
                target,
                template,
                temporaryDirectory.resolve("backups"),
                ConfigurationKind.STORAGE);

        assertTrue(result.changed());
        assertNotNull(result.backup());
        assertTrue(Files.exists(result.backup()));
        assertEquals("mysql", result.values().get("type"));
        assertFalse(result.values().containsKey("obsolete"));

        @SuppressWarnings("unchecked")
        Map<String, Object> mysql =
                (Map<String, Object>) result.values().get("mysql");
        assertEquals("db.internal", mysql.get("host"));
        assertEquals(3306, ((Number) mysql.get("port")).intValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters =
                (Map<String, Object>) mysql.get("parameters");
        assertEquals("kept", parameters.get("customFlag"));
        assertFalse(parameters.containsKey("useSSL"));

        String rewritten = Files.readString(
                target, StandardCharsets.UTF_8);
        assertTrue(rewritten.startsWith("# 标准中文头部注释"));
        assertTrue(rewritten.contains("# 存储类型注释"));
        assertTrue(rewritten.contains("# 主机注释"));
    }

    @Test
    void coreV1DisplayFlagsMigrateToChannelModes() throws Exception {
        Path target = temporaryDirectory.resolve("config.yml");
        Files.writeString(target, """
                config-version: 1
                display:
                  placeholder-first: false
                  direct:
                    chat:
                      enabled: true
                      template: "{title} {player}: {message}"
                    tab:
                      enabled: false
                      template: "{title} {player}"
                    nametag:
                      enabled: true
                      template: "{title} {player}"
                """, StandardCharsets.UTF_8);
        String template = """
                config-version: 2
                display:
                  channels:
                    chat: "placeholder"
                    tab: "placeholder"
                    nametag: "placeholder"
                  direct:
                    chat:
                      template: "{title} {player}: {message}"
                    tab:
                      template: "{title} {player}"
                    nametag:
                      template: "{title} {player}"
                """;

        var result = new YamlConfigurationSynchronizer(
                new SafeYaml(), FIXED_CLOCK).synchronize(
                target, template, temporaryDirectory.resolve("backups"),
                ConfigurationKind.CORE);

        assertTrue(result.changed());
        assertTrue(result.backupFile().isPresent());
        Map<?, ?> display = (Map<?, ?>) result.values().get("display");
        Map<?, ?> channels = (Map<?, ?>) display.get("channels");
        assertEquals("vanilla", channels.get("chat"));
        assertEquals("disabled", channels.get("tab"));
        assertEquals("vanilla", channels.get("nametag"));
        assertFalse(display.containsKey("placeholder-first"));
        Map<?, ?> direct = (Map<?, ?>) display.get("direct");
        assertFalse(((Map<?, ?>) direct.get("chat"))
                .containsKey("enabled"));
    }

    @Test
    void coreV1DisabledDirectUsesPlaceholderWhenPreferred()
            throws Exception {
        Path target = temporaryDirectory.resolve("config.yml");
        Files.writeString(target, """
                config-version: 1
                display:
                  placeholder-first: true
                  direct:
                    chat:
                      enabled: false
                      template: "{title} {player}: {message}"
                    tab:
                      enabled: false
                      template: "{title} {player}"
                    nametag:
                      enabled: false
                      template: "{title} {player}"
                """, StandardCharsets.UTF_8);
        String template = """
                config-version: 2
                display:
                  channels:
                    chat: "placeholder"
                    tab: "placeholder"
                    nametag: "placeholder"
                  direct:
                    chat:
                      template: "{title} {player}: {message}"
                    tab:
                      template: "{title} {player}"
                    nametag:
                      template: "{title} {player}"
                """;

        var result = new YamlConfigurationSynchronizer(
                new SafeYaml(), FIXED_CLOCK).synchronize(
                target, template, temporaryDirectory.resolve("backups"),
                ConfigurationKind.CORE);
        Map<?, ?> channels = (Map<?, ?>) ((Map<?, ?>) result.values()
                .get("display")).get("channels");
        assertEquals(Set.of("placeholder"),
                new LinkedHashSet<>(channels.values()));
    }

    @Test
    void guiVersionUpgradeBacksUpAndReplacesLegacyLayout()
            throws Exception {
        Path target = temporaryDirectory.resolve("shop.yml");
        Files.writeString(target, """
                config-version: 1
                screen:
                  type: "GENERIC_9X6"
                  title: "legacy"
                content-slots:
                  - "0-44"
                filler:
                  enabled: true
                  item: "minecraft:glass_pane"
                  name: ""
                buttons: {}
                """, StandardCharsets.UTF_8);
        String template = """
                config-version: 2
                screen:
                  type: "GENERIC_9X6"
                  title: "&e商城标题"
                content-slots:
                  - "10-16"
                filler:
                  enabled: true
                  item: "minecraft:black_stained_glass_pane"
                  name: ""
                buttons: {}
                """;

        var result = new YamlConfigurationSynchronizer(
                new SafeYaml(), FIXED_CLOCK).synchronize(
                target, template, temporaryDirectory.resolve("backups"),
                ConfigurationKind.GUI);

        assertTrue(result.changed());
        assertTrue(result.backupFile().isPresent());
        assertTrue(Files.exists(result.backupFile().orElseThrow()));
        assertEquals("&e商城标题", ((Map<?, ?>) result.values()
                .get("screen")).get("title"));
        String rewritten = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("- \"10-16\""));
        assertFalse(rewritten.contains("- \"0-44\""));
    }

    @Test
    void jsonLanguageSyncKeepsTranslationAndReconcilesKeys()
            throws Exception {
        Path target = temporaryDirectory.resolve("zh_cn.json");
        Files.writeString(target, """
                {
                  "keep": "用户翻译",
                  "obsolete": "删除"
                }
                """, StandardCharsets.UTF_8);
        String template = """
                {
                  "keep": "默认翻译",
                  "added": "新增"
                }
                """;

        var report = new JsonLanguageSynchronizer(
                new SafeJsonLanguage(), FIXED_CLOCK)
                .synchronize(target, template);

        assertEquals(1, report.added());
        assertEquals(1, report.removed());
        Map<String, String> parsed = new SafeJsonLanguage().parse(
                Files.readString(target, StandardCharsets.UTF_8));
        assertEquals("用户翻译", parsed.get("keep"));
        assertEquals("新增", parsed.get("added"));
        assertFalse(parsed.containsKey("obsolete"));
    }

    @Test
    void corruptJsonIsBackedUpBesideFileAndRecovered()
            throws Exception {
        Path target = temporaryDirectory.resolve("en_us.json");
        Files.writeString(
                target, "{\"broken\":", StandardCharsets.UTF_8);

        var report = new JsonLanguageSynchronizer(
                new SafeJsonLanguage(), FIXED_CLOCK)
                .synchronize(target, "{\"message\":\"ok\"}");

        assertTrue(report.recovered());
        assertTrue(report.backupFile().isPresent());
        assertTrue(Files.exists(report.backupFile().orElseThrow()));
        assertTrue(report.backupFile().orElseThrow()
                .getFileName().toString().contains(".invalid-"));
        assertEquals("ok", new SafeJsonLanguage().parse(
                Files.readString(target)).get("message"));
    }

    @Test
    void bundledGuiLanguageReferencesExistInBothDefaultCatalogs()
            throws Exception {
        SafeJsonLanguage parser = new SafeJsonLanguage();
        Map<String, String> chinese = parser.parse(resource(
                "/crown/defaults/lang/zh_cn.json"));
        Map<String, String> english = parser.parse(resource(
                "/crown/defaults/lang/en_us.json"));
        assertEquals(chinese.keySet(), english.keySet(),
                "Bundled language catalogs must expose identical keys");

        Set<String> referenced = new LinkedHashSet<>();
        for (String file : DEFAULT_GUI_FILES) {
            Matcher matcher = GUI_LANGUAGE_KEY.matcher(resource(
                    "/crown/defaults/gui/" + file));
            while (matcher.find()) {
                referenced.add(matcher.group(1));
            }
        }

        for (String key : referenced) {
            assertTrue(chinese.containsKey(key),
                    "zh_cn is missing GUI language key: " + key);
            assertTrue(english.containsKey(key),
                    "en_us is missing GUI language key: " + key);
        }
    }

    private static String resource(String path) throws Exception {
        try (var stream = ConfigurationSynchronizationTest.class
                .getResourceAsStream(path)) {
            assertNotNull(stream, "Missing test resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}