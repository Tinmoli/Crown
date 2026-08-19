package dev.xiaomu.crown.config.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** 配置备份与同目录原子替换工具。 */
public final class AtomicFiles {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                    .withZone(ZoneOffset.UTC);

    private AtomicFiles() {
    }

    public static Path backup(
            Path source,
            Path backupRoot,
            Clock clock
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(backupRoot, "backupRoot");
        Objects.requireNonNull(clock, "clock");

        Files.createDirectories(backupRoot);
        String fileName = source.getFileName()
                + "." + STAMP.format(clock.instant()) + ".bak";
        Path destination = unique(backupRoot.resolve(fileName));
        return Files.copy(source, destination,
                StandardCopyOption.COPY_ATTRIBUTES);
    }

    public static Path backupInvalidBeside(
            Path source,
            Clock clock
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(clock, "clock");

        Path parent = source.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("Source has no parent: " + source);
        }
        String fileName = source.getFileName()
                + ".invalid-" + STAMP.format(clock.instant()) + ".bak";
        Path destination = unique(parent.resolve(fileName));
        return Files.copy(source, destination,
                StandardCopyOption.COPY_ATTRIBUTES);
    }

    public static void writeUtf8Atomically(
            Path target,
            String content
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(content, "content");

        Path parent = target.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("Target has no parent: " + target);
        }
        Files.createDirectories(parent);

        Path temporary = Files.createTempFile(
                parent, "." + target.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static Path unique(Path proposed) {
        if (!Files.exists(proposed)) {
            return proposed;
        }
        for (int index = 1; ; index++) {
            Path candidate = proposed.resolveSibling(
                    proposed.getFileName() + "." + index);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }
}