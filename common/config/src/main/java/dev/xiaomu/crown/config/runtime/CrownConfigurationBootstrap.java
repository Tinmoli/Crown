package dev.xiaomu.crown.config.runtime;

import dev.xiaomu.crown.config.io.SafeYaml;
import dev.xiaomu.crown.config.lang.JsonLanguageSynchronizer;
import dev.xiaomu.crown.config.lang.LanguageCatalog;
import dev.xiaomu.crown.config.lang.LanguageSyncReport;
import dev.xiaomu.crown.config.lang.SafeJsonLanguage;
import dev.xiaomu.crown.config.model.CatalogSettings;
import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.config.model.GuiLayout;
import dev.xiaomu.crown.config.model.GuiLayouts;
import dev.xiaomu.crown.config.model.StorageSettings;
import dev.xiaomu.crown.config.parse.CoreSettingsParser;
import dev.xiaomu.crown.config.parse.GuiLayoutParser;
import dev.xiaomu.crown.config.parse.StorageSettingsParser;
import dev.xiaomu.crown.config.parse.TitleCatalogParser;
import dev.xiaomu.crown.config.sync.ConfigurationKind;
import dev.xiaomu.crown.config.sync.ConfigurationSyncResult;
import dev.xiaomu.crown.config.sync.YamlConfigurationSynchronizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 部署、同步并完整校验 Crown 的全部配置文件。
 *
 * <p>返回前会先构建整个候选快照并执行平台验证。调用方应只在本方法成功
 * 返回后，才把 report.snapshot 原子替换为当前运行快照。</p>
 */
public final class CrownConfigurationBootstrap {
    private static final List<String> MANAGED_LANGUAGES =
            List.of("zh_cn", "en_us");

    private final Clock clock;
    private final SafeYaml yaml;
    private final SafeJsonLanguage json;
    private final PlatformConfigurationValidator platformValidator;

    public CrownConfigurationBootstrap() {
        this(Clock.systemUTC(), PlatformConfigurationValidator.NO_OP);
    }

    public CrownConfigurationBootstrap(
            Clock clock,
            PlatformConfigurationValidator platformValidator
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.platformValidator = Objects.requireNonNull(
                platformValidator, "platformValidator");
        this.yaml = new SafeYaml();
        this.json = new SafeJsonLanguage();
    }

    public ConfigurationLoadReport initialize(Path configRoot)
            throws IOException {
        Objects.requireNonNull(configRoot, "configRoot");
        Files.createDirectories(configRoot);
        Files.createDirectories(configRoot.resolve("gui"));
        Files.createDirectories(configRoot.resolve("lang"));
        Files.createDirectories(configRoot.resolve("data"));
        Path backupRoot = configRoot.resolve("backups");
        Files.createDirectories(backupRoot);

        var yamlSynchronizer =
                new YamlConfigurationSynchronizer(yaml, clock);
        var configurationReports =
                new ArrayList<ConfigurationSyncResult>();

        ConfigurationSyncResult coreResult = synchronize(
                yamlSynchronizer,
                configRoot.resolve("config.yml"),
                "config.yml",
                backupRoot,
                ConfigurationKind.CORE,
                configurationReports);
        ConfigurationSyncResult titleResult = synchronize(
                yamlSynchronizer,
                configRoot.resolve("titles.yml"),
                "titles.yml",
                backupRoot,
                ConfigurationKind.TITLES,
                configurationReports);
        ConfigurationSyncResult storageResult = synchronize(
                yamlSynchronizer,
                configRoot.resolve("storage.yml"),
                "storage.yml",
                backupRoot,
                ConfigurationKind.STORAGE,
                configurationReports);

        CoreSettings core = new CoreSettingsParser().parse(
                coreResult.values());
        CatalogSettings catalog = new TitleCatalogParser().parse(
                titleResult.values(), core.safety(), core.purchase().mintCurrency());
        StorageSettings storage = new StorageSettingsParser().parse(
                storageResult.values());

        var layouts = new LinkedHashMap<String, GuiLayout>();
        var guiParser = new GuiLayoutParser();
        for (String id : GuiLayouts.REQUIRED.stream().sorted().toList()) {
            ConfigurationSyncResult result = synchronize(
                    yamlSynchronizer,
                    configRoot.resolve("gui").resolve(id + ".yml"),
                    "gui/" + id + ".yml",
                    backupRoot,
                    ConfigurationKind.GUI,
                    configurationReports);
            layouts.put(id, guiParser.parse(
                    id, result.values(), core.safety()));
        }
        GuiLayouts gui = new GuiLayouts(layouts);

        List<LanguageSyncReport> languageReports =
                synchronizeLanguages(configRoot.resolve("lang"));
        LanguageCatalog languages = loadLanguages(
                configRoot.resolve("lang"), core.language());

        RuntimeSnapshot snapshot = new RuntimeSnapshot(
                core,
                catalog,
                storage,
                gui,
                languages,
                clock.instant());
        platformValidator.validate(snapshot);

        return new ConfigurationLoadReport(
                snapshot, configurationReports, languageReports);
    }

    private static ConfigurationSyncResult synchronize(
            YamlConfigurationSynchronizer synchronizer,
            Path target,
            String templateResource,
            Path backupRoot,
            ConfigurationKind kind,
            List<ConfigurationSyncResult> reports
    ) throws IOException {
        ConfigurationSyncResult result = synchronizer.synchronize(
                target,
                TemplateResources.read(templateResource),
                backupRoot,
                kind);
        reports.add(result);
        return result;
    }

    private List<LanguageSyncReport> synchronizeLanguages(
            Path languageRoot
    ) throws IOException {
        var synchronizer = new JsonLanguageSynchronizer(json, clock);
        var reports = new ArrayList<LanguageSyncReport>();
        for (String language : MANAGED_LANGUAGES) {
            reports.add(synchronizer.synchronize(
                    languageRoot.resolve(language + ".json"),
                    TemplateResources.read(
                            "lang/" + language + ".json")));
        }
        return List.copyOf(reports);
    }

    private LanguageCatalog loadLanguages(
            Path languageRoot,
            String selectedLanguage
    ) throws IOException {
        var languages =
                new LinkedHashMap<String, Map<String, String>>();
        try (var files = Files.list(languageRoot)) {
            List<Path> candidates = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted()
                    .toList();
            for (Path candidate : candidates) {
                String fileName = candidate.getFileName().toString();
                String id = fileName.substring(
                        0, fileName.length() - ".json".length())
                        .toLowerCase(Locale.ROOT);
                LanguageCatalog.requireLanguageId(id);
                if (Files.size(candidate)
                        > SafeJsonLanguage.MAX_FILE_BYTES) {
                    throw new IOException(
                            "Language file is too large: " + candidate);
                }
                try {
                    languages.put(id, json.parse(Files.readString(
                            candidate, StandardCharsets.UTF_8)));
                } catch (IllegalArgumentException exception) {
                    throw new IOException(
                            "Invalid language file: " + candidate,
                            exception);
                }
            }
        }
        if (!languages.containsKey("zh_cn")
                || !languages.containsKey("en_us")) {
            throw new IOException(
                    "Managed Crown languages are unavailable");
        }
        return new LanguageCatalog(selectedLanguage, languages);
    }
}