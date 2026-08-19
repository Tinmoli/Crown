package dev.xiaomu.crown.storage.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/** 为单次 Repository 操作提供已配置 JDBC 连接。 */
@FunctionalInterface
public interface ConnectionFactory extends AutoCloseable {
    Connection open() throws SQLException;

    @Override
    default void close() {
        // DriverManager SQLite 工厂不持有长期资源。
    }
}