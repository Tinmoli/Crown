package dev.xiaomu.crown.config.model;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** 经过校验的 storage.yml 快照。连接类设置只在重启后应用。 */
public record StorageSettings(
        Type type,
        Sqlite sqlite,
        Mysql mysql,
        Migration migration
) {
    public StorageSettings {
        type = Objects.requireNonNull(type, "type");
        sqlite = Objects.requireNonNull(sqlite, "sqlite");
        mysql = Objects.requireNonNull(mysql, "mysql");
        migration = Objects.requireNonNull(migration, "migration");
    }

    public enum Type {
        SQLITE,
        MYSQL
    }

    public enum SqliteSynchronous {
        OFF,
        NORMAL,
        FULL,
        EXTRA
    }

    public record Sqlite(
            String path,
            Duration busyTimeout,
            boolean wal,
            SqliteSynchronous synchronous,
            boolean snapshotBeforeMigration,
            int maximumSnapshots
    ) {
        public Sqlite {
            path = requireRelativePath(path);
            busyTimeout = requireDuration(
                    busyTimeout, "SQLite busy timeout",
                    Duration.ofMillis(1), Duration.ofMinutes(5));
            synchronous = Objects.requireNonNull(
                    synchronous, "synchronous");
            if (maximumSnapshots < 1 || maximumSnapshots > 1_000) {
                throw new IllegalArgumentException(
                        "SQLite maximum snapshots must be 1..1000");
            }
        }

        private static String requireRelativePath(String value) {
            Objects.requireNonNull(value, "path");
            if (value.isBlank() || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "SQLite path is blank or contains NUL");
            }
            String portable = value.replace('\\', '/');
            if (portable.startsWith("/")
                    || portable.matches("^[A-Za-z]:/.*")) {
                throw new IllegalArgumentException(
                        "SQLite path must be relative to the game directory");
            }
            for (String part : portable.split("/")) {
                if (part.equals("..")) {
                    throw new IllegalArgumentException(
                            "SQLite path cannot escape the game directory");
                }
            }
            try {
                Path.of(value);
            } catch (InvalidPathException exception) {
                throw new IllegalArgumentException(
                        "Invalid SQLite path", exception);
            }
            return portable;
        }
    }

    public record Mysql(
            String host,
            int port,
            String database,
            String username,
            String password,
            String tablePrefix,
            Map<String, String> parameters,
            Pool pool,
            boolean requireManualBackupForDestructiveMigration
    ) {
        private static final Pattern DATABASE =
                Pattern.compile("[A-Za-z0-9_$-]{1,64}");
        private static final Pattern TABLE_PREFIX =
                Pattern.compile("[A-Za-z0-9_]{1,24}");

        public Mysql {
            host = requirePlainText(host, "MySQL host", 255, false);
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException(
                        "MySQL port is out of range");
            }
            database = requirePattern(
                    database, "MySQL database", DATABASE);
            username = requirePlainText(
                    username, "MySQL username", 128, false);
            password = requirePlainText(
                    password, "MySQL password", 1024, true);
            tablePrefix = requirePattern(
                    tablePrefix, "MySQL table prefix", TABLE_PREFIX);
            Objects.requireNonNull(parameters, "parameters");
            var copy = new LinkedHashMap<String, String>();
            parameters.forEach((key, value) -> {
                if (key == null
                        || !key.matches("[A-Za-z0-9_.-]{1,128}")
                        || value == null || value.length() > 512
                        || containsControl(value)) {
                    throw new IllegalArgumentException(
                            "Invalid MySQL JDBC parameter");
                }
                copy.put(key, value);
            });
            parameters = Map.copyOf(copy);
            pool = Objects.requireNonNull(pool, "pool");
        }
    }

    public record Pool(
            int minimumIdle,
            int maximumSize,
            Duration connectionTimeout,
            Duration validationTimeout,
            Duration idleTimeout,
            Duration maximumLifetime
    ) {
        public Pool {
            if (minimumIdle < 0 || maximumSize < 1
                    || minimumIdle > maximumSize
                    || maximumSize > 100) {
                throw new IllegalArgumentException(
                        "Invalid MySQL pool size");
            }
            connectionTimeout = requireDuration(
                    connectionTimeout, "connection timeout",
                    Duration.ofMillis(250), Duration.ofMinutes(5));
            validationTimeout = requireDuration(
                    validationTimeout, "validation timeout",
                    Duration.ofMillis(250), connectionTimeout);
            idleTimeout = requireDuration(
                    idleTimeout, "idle timeout",
                    Duration.ZERO, Duration.ofHours(24));
            maximumLifetime = requireDuration(
                    maximumLifetime, "maximum lifetime",
                    Duration.ofSeconds(30), Duration.ofHours(24));
        }
    }

    public record Migration(
            boolean autoCompatibleSchema,
            boolean protectEmptyTarget,
            Verification verification
    ) {
        public Migration {
            verification = Objects.requireNonNull(
                    verification, "verification");
        }
    }

    public record Verification(
            boolean playerCount,
            boolean ownedTitleCount,
            boolean titleCoinTotal,
            boolean orderCount,
            boolean cardCount,
            boolean auditCount
    ) {
    }

    private static Duration requireDuration(
            Duration value,
            String name,
            Duration minimum,
            Duration maximum
    ) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(minimum) < 0
                || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    name + " is outside the allowed range");
        }
        return value;
    }

    private static String requirePlainText(
            String value,
            String name,
            int maximumLength,
            boolean allowEmpty
    ) {
        Objects.requireNonNull(value, name);
        if ((!allowEmpty && value.isBlank())
                || value.length() > maximumLength
                || containsControl(value)) {
            throw new IllegalArgumentException(
                    name + " is invalid");
        }
        return value;
    }

    private static String requirePattern(
            String value,
            String name,
            Pattern pattern
    ) {
        Objects.requireNonNull(value, name);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }
}