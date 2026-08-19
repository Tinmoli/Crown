package dev.xiaomu.crown.config.lang;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 单个内置语言文件的同步报告。 */
public record LanguageSyncReport(
        Path file,
        int added,
        int removed,
        boolean created,
        boolean recovered,
        Path backup,
        List<String> changedKeys
) {
    public LanguageSyncReport {
        file = Objects.requireNonNull(file, "file");
        if (added < 0 || removed < 0) {
            throw new IllegalArgumentException(
                    "Language change counts cannot be negative");
        }
        changedKeys = List.copyOf(Objects.requireNonNull(
                changedKeys, "changedKeys"));
    }

    public Optional<Path> backupFile() {
        return Optional.ofNullable(backup);
    }

    public boolean changed() {
        return created || recovered || added > 0 || removed > 0;
    }
}