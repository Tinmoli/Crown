package dev.xiaomu.crown.config.sync;

import dev.xiaomu.crown.config.io.AtomicFiles;
import dev.xiaomu.crown.config.io.ConfigMaps;
import dev.xiaomu.crown.config.io.SafeYaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 以内置模板为权威 Schema，同步可编辑 Crown YML 配置。
 */
public final class YamlConfigurationSynchronizer {
    private final SafeYaml yaml;
    private final Clock clock;

    public YamlConfigurationSynchronizer(SafeYaml yaml, Clock clock) {
        this.yaml = Objects.requireNonNull(yaml, "yaml");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ConfigurationSyncResult synchronize(
            Path target,
            String templateText,
            Path backupRoot,
            ConfigurationKind kind
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(templateText, "templateText");
        Objects.requireNonNull(backupRoot, "backupRoot");
        Objects.requireNonNull(kind, "kind");

        Map<String, Object> template = yaml.loadMap(templateText);
        int templateVersion = requireVersion(
                template, target + " template");

        if (!Files.exists(target) || Files.size(target) == 0) {
            if (Files.exists(target)) {
                Path backup = AtomicFiles.backup(
                        target, backupRoot, clock);
                AtomicFiles.writeUtf8Atomically(target, templateText);
                return new ConfigurationSyncResult(
                        target, template, false, true, true, backup);
            }
            AtomicFiles.writeUtf8Atomically(target, templateText);
            return new ConfigurationSyncResult(
                    target, template, true, false, true, null);
        }

        Map<String, Object> user;
        try {
            if (Files.size(target) > SafeYaml.MAX_FILE_BYTES) {
                throw new IllegalArgumentException(
                        "Configuration file is too large");
            }
            user = yaml.loadMap(Files.readString(
                    target, StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            Path backup = AtomicFiles.backup(
                    target, backupRoot, clock);
            AtomicFiles.writeUtf8Atomically(target, templateText);
            return new ConfigurationSyncResult(
                    target, template, false, true, true, backup);
        }

        int userVersion = optionalVersion(user);
        if (userVersion > templateVersion) {
            throw new IOException("Configuration " + target
                    + " uses newer config-version " + userVersion
                    + "; this Crown build supports " + templateVersion);
        }

        // GUI v2 改变了内容区与导航槽位；逐叶合并会保留旧布局，导致升级
        // 后仍使用过期界面。仅在跨 GUI 配置版本时完整替换并留下备份；
        // 已处于当前版本的服主自定义仍按下方常规同步逻辑保留。
        if (kind == ConfigurationKind.GUI && userVersion < templateVersion) {
            Path backup = AtomicFiles.backup(target, backupRoot, clock);
            AtomicFiles.writeUtf8Atomically(target, templateText);
            return new ConfigurationSyncResult(
                    target, template, false, false, true, backup);
        }

        // CORE v2 用每渠道三态模式替代 placeholder-first/direct.enabled。
        // 必须先按旧开关推导模式，再交给普通同步；否则模板新增的默认
        // placeholder 会把旧服 enabled=true 的直接显示静默关闭。
        if (kind == ConfigurationKind.CORE && userVersion < 2
                && templateVersion >= 2) {
            user = migrateCoreDisplayV1(user);
        }

        MutableState state = new MutableState();
        Map<String, Object> normalized = synchronizeMap(
                template, user, "", kind, state);
        normalized.put("config-version", templateVersion);
        if (!Objects.equals(
                user.get("config-version"), templateVersion)) {
            state.changed = true;
        }

        Path backup = null;
        if (state.changed) {
            backup = AtomicFiles.backup(target, backupRoot, clock);
            String rendered = TemplateComments.apply(
                    templateText, yaml.dump(normalized));
            AtomicFiles.writeUtf8Atomically(target, rendered);
        }

        return new ConfigurationSyncResult(
                target, normalized, false, false, state.changed, backup);
    }

    private static Map<String, Object> migrateCoreDisplayV1(
            Map<String, Object> source
    ) {
        Map<String, Object> result = ConfigMaps.deepCopyMap(source);
        Map<String, Object> display = mutableMap(result.get("display"));
        boolean placeholderFirst = booleanValue(
                display.get("placeholder-first"), true);
        Map<String, Object> direct = mutableMap(display.get("direct"));
        Map<String, Object> channels = new LinkedHashMap<>();
        for (String name : List.of("chat", "tab", "nametag")) {
            Map<String, Object> channel = mutableMap(direct.get(name));
            boolean enabled = booleanValue(channel.get("enabled"), false);
            channels.put(name, enabled
                    ? "vanilla"
                    : (placeholderFirst ? "placeholder" : "disabled"));
        }
        display.put("channels", channels);
        result.put("display", display);
        return result;
    }

    private static Map<String, Object> mutableMap(Object value) {
        return value instanceof Map<?, ?> map
                ? ConfigMaps.deepCopyMap(ConfigMaps.castMap(map))
                : new LinkedHashMap<>();
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean flag ? flag : fallback;
    }

    private static Map<String, Object> synchronizeMap(
            Map<String, Object> template,
            Map<String, Object> user,
            String path,
            ConfigurationKind kind,
            MutableState state
    ) {
        if (kind == ConfigurationKind.STORAGE
                && "mysql.parameters".equals(path)) {
            return ConfigMaps.deepCopyMap(user);
        }
        if (kind == ConfigurationKind.TITLES
                && "titles".equals(path)) {
            return synchronizeTitles(template, user, state);
        }

        var result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : template.entrySet()) {
            String key = entry.getKey();
            String childPath = path.isEmpty() ? key : path + '.' + key;
            Object expected = entry.getValue();

            if ("config-version".equals(childPath)) {
                result.put(key, ConfigMaps.deepCopy(expected));
                continue;
            }
            if (!user.containsKey(key)) {
                result.put(key, ConfigMaps.deepCopy(expected));
                state.changed = true;
                continue;
            }

            Object actual = user.get(key);
            if (expected instanceof Map<?, ?> expectedMap) {
                if (actual instanceof Map<?, ?> actualMap) {
                    result.put(key, synchronizeMap(
                            ConfigMaps.castMap(expectedMap),
                            ConfigMaps.castMap(actualMap),
                            childPath, kind, state));
                } else {
                    result.put(key, ConfigMaps.deepCopy(expected));
                    state.changed = true;
                }
            } else if (compatibleLeaf(expected, actual)) {
                result.put(key, ConfigMaps.deepCopy(actual));
            } else {
                result.put(key, ConfigMaps.deepCopy(expected));
                state.changed = true;
            }
        }

        for (String key : user.keySet()) {
            if (!template.containsKey(key)) {
                if (kind == ConfigurationKind.GUI
                        && isSupportedGuiOptionalField(key)) {
                    result.put(key, ConfigMaps.deepCopy(user.get(key)));
                } else {
                    state.changed = true;
                }
            }
        }
        return result;
    }

    private static Map<String, Object> synchronizeTitles(
            Map<String, Object> template,
            Map<String, Object> user,
            MutableState state
    ) {
        Map<String, Object> exemplar = template.values().stream()
                .filter(Map.class::isInstance)
                .map(value -> ConfigMaps.castMap((Map<?, ?>) value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "titles template has no exemplar"));

        var result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : user.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> titleMap)) {
                state.changed = true;
                continue;
            }
            Map<String, Object> exact =
                    template.get(entry.getKey()) instanceof Map<?, ?> map
                            ? ConfigMaps.castMap(map)
                            : exemplar;
            result.put(entry.getKey(), synchronizeMap(
                    exact,
                    ConfigMaps.castMap(titleMap),
                    "titles.*",
                    ConfigurationKind.TITLES,
                    state));
        }
        return result;
    }

    private static boolean isSupportedGuiOptionalField(String key) {
        return switch (key) {
            case "amount", "glow", "hide-tooltip",
                    "custom-model-data", "sound",
                    "close-after-action" -> true;
            default -> false;
        };
    }

    private static boolean compatibleLeaf(
            Object expected,
            Object actual
    ) {
        if (expected == null) {
            return actual == null || actual instanceof String;
        }
        if (actual == null) {
            return false;
        }
        if (expected instanceof List<?>) {
            return actual instanceof List<?>;
        }
        if (expected instanceof Number) {
            return actual instanceof Number
                    && !(actual instanceof Float)
                    && !(actual instanceof Double);
        }
        return expected.getClass().equals(actual.getClass());
    }

    private static int requireVersion(
            Map<String, Object> values,
            String source
    ) {
        Object value = values.get("config-version");
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    source + " must contain numeric config-version");
        }
        long raw = number.longValue();
        if (raw < 1 || raw > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    source + " config-version is invalid");
        }
        return (int) raw;
    }

    private static int optionalVersion(Map<String, Object> values) {
        Object value = values.get("config-version");
        if (!(value instanceof Number number)) {
            return 0;
        }
        long raw = number.longValue();
        return raw < 0 || raw > Integer.MAX_VALUE ? 0 : (int) raw;
    }

    private static final class MutableState {
        private boolean changed;
    }
}