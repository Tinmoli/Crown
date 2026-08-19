package dev.xiaomu.crown.config.runtime;

import dev.xiaomu.crown.config.lang.LanguageSyncReport;
import dev.xiaomu.crown.config.sync.ConfigurationSyncResult;

import java.util.List;
import java.util.Objects;

/** 启动或重载时所有文件同步结果及候选快照。 */
public record ConfigurationLoadReport(
        RuntimeSnapshot snapshot,
        List<ConfigurationSyncResult> configurations,
        List<LanguageSyncReport> languages
) {
    public ConfigurationLoadReport {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        configurations = List.copyOf(Objects.requireNonNull(
                configurations, "configurations"));
        languages = List.copyOf(Objects.requireNonNull(
                languages, "languages"));
    }

    public long changedConfigurationCount() {
        return configurations.stream()
                .filter(ConfigurationSyncResult::changed)
                .count();
    }

    public long changedLanguageCount() {
        return languages.stream()
                .filter(LanguageSyncReport::changed)
                .count();
    }
}