package dev.xiaomu.crown.storage.backend;

import dev.xiaomu.crown.config.model.StorageSettings;
import dev.xiaomu.crown.storage.StorageException;
import dev.xiaomu.crown.storage.jdbc.ConnectionFactory;
import dev.xiaomu.crown.storage.jdbc.JdbcDialect;
import dev.xiaomu.crown.storage.jdbc.MySqlConnectionFactory;
import dev.xiaomu.crown.storage.jdbc.SqliteConnectionFactory;
import dev.xiaomu.crown.storage.jdbc.TableNames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** 从已验证的 storage.yml 快照创建 SQLite 或 MySQL 后端。 */
public final class StorageBackendFactory {
    private static final String SQLITE_TABLE_PREFIX = "crown_";

    public StorageBackend openConfigured(
            Path gameDirectory,
            StorageSettings settings
    ) {
        Objects.requireNonNull(settings, "settings");
        return open(gameDirectory, settings, settings.type());
    }

    public StorageBackend open(
            Path gameDirectory,
            StorageSettings settings,
            StorageSettings.Type type
    ) {
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(type, "type");

        ConnectionFactory connections;
        JdbcDialect dialect;
        TableNames tables;
        Path sqliteDatabase = null;

        if (type == StorageSettings.Type.SQLITE) {
            StorageSettings.Sqlite sqlite = settings.sqlite();
            sqliteDatabase = resolveInsideGameDirectory(
                    gameDirectory, sqlite.path());
            createParentDirectories(sqliteDatabase);
            connections = new SqliteConnectionFactory(
                    sqliteDatabase,
                    Math.toIntExact(sqlite.busyTimeout().toMillis()),
                    sqlite.wal(),
                    sqlite.synchronous().name());
            dialect = JdbcDialect.SQLITE;
            tables = TableNames.withPrefix(SQLITE_TABLE_PREFIX);
        } else {
            StorageSettings.Mysql mysql = settings.mysql();
            StorageSettings.Pool pool = mysql.pool();
            connections = new MySqlConnectionFactory(
                    mysql.host(),
                    mysql.port(),
                    mysql.database(),
                    mysql.username(),
                    mysql.password(),
                    mysql.parameters(),
                    pool.minimumIdle(),
                    pool.maximumSize(),
                    pool.connectionTimeout().toMillis(),
                    pool.validationTimeout().toMillis(),
                    pool.idleTimeout().toMillis(),
                    pool.maximumLifetime().toMillis());
            dialect = JdbcDialect.MYSQL;
            tables = TableNames.withPrefix(mysql.tablePrefix());
        }

        StorageBackend backend = new StorageBackend(
                type, connections, dialect, tables, sqliteDatabase);
        boolean initialized = false;
        try {
            backend.repository().initializeSchema();
            initialized = true;
            return backend;
        } finally {
            if (!initialized) {
                backend.close();
            }
        }
    }

    static Path resolveInsideGameDirectory(
            Path gameDirectory,
            String relative
    ) {
        Path root = gameDirectory.toAbsolutePath().normalize();
        Path resolved = root.resolve(relative)
                .toAbsolutePath().normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException(
                    "SQLite database must stay inside game directory");
        }
        return resolved;
    }

    private static void createParentDirectories(Path database) {
        Path parent = database.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "SQLite database has no parent directory");
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException exception) {
            throw new StorageException(
                    "Could not create SQLite database directory",
                    exception);
        }
    }
}