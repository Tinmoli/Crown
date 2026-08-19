package dev.xiaomu.crown.storage.jdbc;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 带 WAL、busy timeout 和同步级别设置的 SQLite 连接工厂。 */
public final class SqliteConnectionFactory implements ConnectionFactory {
    private static final Set<String> SYNCHRONOUS =
            Set.of("OFF", "NORMAL", "FULL", "EXTRA");

    private final String jdbcUrl;
    private final int busyTimeoutMillis;
    private final boolean wal;
    private final String synchronous;

    public SqliteConnectionFactory(
            Path database,
            int busyTimeoutMillis,
            boolean wal,
            String synchronous
    ) {
        Objects.requireNonNull(database, "database");
        String normalized = Objects.requireNonNull(
                synchronous, "synchronous").toUpperCase(Locale.ROOT);
        if (busyTimeoutMillis < 0
                || !SYNCHRONOUS.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Invalid SQLite configuration");
        }
        jdbcUrl = "jdbc:sqlite:" + database.toAbsolutePath();
        this.busyTimeoutMillis = busyTimeoutMillis;
        this.wal = wal;
        this.synchronous = normalized;
    }

    @Override
    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        boolean configured = false;
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "PRAGMA busy_timeout = " + busyTimeoutMillis);
            statement.execute("PRAGMA synchronous = " + synchronous);
            statement.execute("PRAGMA foreign_keys = ON");
            if (wal) {
                statement.execute("PRAGMA journal_mode = WAL");
            }
            configured = true;
            return connection;
        } finally {
            if (!configured) {
                connection.close();
            }
        }
    }
}