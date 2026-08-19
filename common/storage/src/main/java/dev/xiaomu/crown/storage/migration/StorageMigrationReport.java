package dev.xiaomu.crown.storage.migration;

import dev.xiaomu.crown.config.model.StorageSettings;
import dev.xiaomu.crown.storage.model.StorageSummary;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 一次成功显式迁移的后端、逐表行数和校验摘要。 */
public record StorageMigrationReport(
        StorageSettings.Type sourceType,
        StorageSettings.Type targetType,
        Map<String, Long> copiedRows,
        StorageSummary sourceSummary,
        StorageSummary targetSummary,
        Duration duration
) {
    public StorageMigrationReport {
        sourceType = Objects.requireNonNull(
                sourceType, "sourceType");
        targetType = Objects.requireNonNull(
                targetType, "targetType");
        Objects.requireNonNull(copiedRows, "copiedRows");
        var copy = new LinkedHashMap<String, Long>();
        copiedRows.forEach((table, rows) -> {
            if (table == null || table.isBlank()
                    || rows == null || rows < 0) {
                throw new IllegalArgumentException(
                        "Invalid migration table count");
            }
            copy.put(table, rows);
        });
        copiedRows = Map.copyOf(copy);
        sourceSummary = Objects.requireNonNull(
                sourceSummary, "sourceSummary");
        targetSummary = Objects.requireNonNull(
                targetSummary, "targetSummary");
        duration = Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException(
                    "Migration duration cannot be negative");
        }
    }

    public long totalCopiedRows() {
        return copiedRows.values().stream()
                .mapToLong(Long::longValue)
                .sum();
    }
}