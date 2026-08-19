package dev.xiaomu.crown.config.lang;

import dev.xiaomu.crown.config.io.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 以内置 JSON 为权威键集合同步 zh_cn 和 en_us。
 * 已有字符串翻译内容始终保留。
 */
public final class JsonLanguageSynchronizer {
    private final SafeJsonLanguage json;
    private final Clock clock;

    public JsonLanguageSynchronizer(
            SafeJsonLanguage json,
            Clock clock
    ) {
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LanguageSyncReport synchronize(
            Path target,
            String templateText
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(templateText, "templateText");

        Map<String, String> template = json.parse(templateText);
        if (!Files.exists(target)) {
            AtomicFiles.writeUtf8Atomically(target, templateText);
            return new LanguageSyncReport(
                    target, template.size(), 0,
                    true, false, null,
                    template.keySet().stream()
                            .map(key -> "+" + key)
                            .toList());
        }

        Map<String, String> user;
        try {
            if (Files.size(target) > SafeJsonLanguage.MAX_FILE_BYTES) {
                throw new IllegalArgumentException(
                        "Language JSON file is too large");
            }
            user = json.parse(Files.readString(
                    target, StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            Path backup = AtomicFiles.backupInvalidBeside(
                    target, clock);
            AtomicFiles.writeUtf8Atomically(target, templateText);
            return new LanguageSyncReport(
                    target, template.size(), 0,
                    false, true, backup,
                    java.util.List.of("<invalid-json>"));
        }

        int added = 0;
        boolean repaired = false;
        var keys = new ArrayList<String>();
        var normalized = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : template.entrySet()) {
            String key = entry.getKey();
            if (user.containsKey(key)) {
                String userValue = user.get(key);
                normalized.put(key, userValue);
            } else {
                normalized.put(key, entry.getValue());
                added++;
                keys.add("+" + key);
            }
        }

        int removed = 0;
        for (String key : user.keySet()) {
            if (!template.containsKey(key)) {
                removed++;
                keys.add("-" + key);
            }
        }

        if (added > 0 || removed > 0 || repaired) {
            AtomicFiles.writeUtf8Atomically(
                    target, json.write(normalized));
        }
        return new LanguageSyncReport(
                target, added, removed,
                false, false, null, keys);
    }

}