package dev.xiaomu.crown.config.sync;

import dev.xiaomu.crown.config.io.ConfigMaps;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 单个 YML 配置同步后的不可变结果。 */
public record ConfigurationSyncResult(
        Path file,
        Map<String, Object> values,
        boolean created,
        boolean recovered,
        boolean changed,
        Path backup
) {
    public ConfigurationSyncResult {
        file = Objects.requireNonNull(file, "file");
        values = ConfigMaps.immutableDeepMap(
                Objects.requireNonNull(values, "values"));
    }

    public Optional<Path> backupFile() {
        return Optional.ofNullable(backup);
    }
}