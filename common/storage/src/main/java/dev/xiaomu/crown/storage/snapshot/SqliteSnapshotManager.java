package dev.xiaomu.crown.storage.snapshot;

import dev.xiaomu.crown.storage.StorageException;
import dev.xiaomu.crown.storage.jdbc.ConnectionFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 使用 SQLite 的一致性 VACUUM INTO 创建在线快照，并按数量清理旧快照。
 */
public final class SqliteSnapshotManager {
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS")
                    .withZone(ZoneOffset.UTC);

    public Path create(
            Path database,
            ConnectionFactory connections,
            Path snapshotDirectory,
            int maximumSnapshots,
            Instant now
    ) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(connections, "connections");
        Objects.requireNonNull(snapshotDirectory, "snapshotDirectory");
        Objects.requireNonNull(now, "now");
        if (maximumSnapshots < 1 || maximumSnapshots > 1_000) {
            throw new IllegalArgumentException(
                    "Maximum SQLite snapshots must be 1..1000");
        }

        Path source = database.toAbsolutePath().normalize();
        Path directory = snapshotDirectory
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new StorageException(
                    "SQLite database does not exist for snapshot");
        }

        String baseName = source.getFileName().toString();
        String stem = baseName + ".snapshot-";
        String suffix = FILE_TIME.format(now) + '-'
                + UUID.randomUUID().toString().substring(0, 8);
        Path temporary = directory.resolve(
                '.' + stem + suffix + ".tmp");
        Path snapshot = directory.resolve(
                stem + suffix + ".db");

        try {
            Files.createDirectories(directory);
            Files.deleteIfExists(temporary);
            vacuumInto(connections, temporary);
            moveAtomically(temporary, snapshot);
            prune(directory, stem, maximumSnapshots);
            return snapshot;
        } catch (IOException | SQLException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw new StorageException(
                    "Could not create SQLite snapshot", exception);
        }
    }

    private static void vacuumInto(
            ConnectionFactory connections,
            Path target
    ) throws SQLException {
        String escaped = target.toAbsolutePath().toString()
                .replace("'", "''");
        try (Connection connection = connections.open();
             Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + escaped + '\'');
        }
    }

    private static void moveAtomically(
            Path source,
            Path target
    ) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void prune(
            Path directory,
            String stem,
            int maximumSnapshots
    ) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            Path[] snapshots = files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(stem)
                                && name.endsWith(".db");
                    })
                    .sorted(Comparator.comparing(path ->
                            path.getFileName().toString()))
                    .toArray(Path[]::new);
            int remove = snapshots.length - maximumSnapshots;
            for (int index = 0; index < remove; index++) {
                Files.deleteIfExists(snapshots[index]);
            }
        }
    }
}