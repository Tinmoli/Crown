package dev.xiaomu.crown.storage.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLite 与 MySQL 的受控 SQL 方言差异。 */
public enum JdbcDialect {
    SQLITE {
        @Override
        public void configure(Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }
        }

        @Override
        String autoIncrementId() {
            return "INTEGER PRIMARY KEY AUTOINCREMENT";
        }

        @Override
        String keyType(int ignoredLength) {
            return "TEXT";
        }

        @Override
        String largeText() {
            return "TEXT";
        }
    },
    MYSQL {
        @Override
        public void configure(Connection connection) throws SQLException {
            connection.setTransactionIsolation(
                    Connection.TRANSACTION_READ_COMMITTED);
        }

        @Override
        String autoIncrementId() {
            return "BIGINT PRIMARY KEY AUTO_INCREMENT";
        }

        @Override
        String keyType(int length) {
            return "VARCHAR(" + length + ")";
        }

        @Override
        String largeText() {
            return "LONGTEXT";
        }
    };

    public abstract void configure(Connection connection)
            throws SQLException;

    abstract String autoIncrementId();

    abstract String keyType(int length);

    abstract String largeText();
}