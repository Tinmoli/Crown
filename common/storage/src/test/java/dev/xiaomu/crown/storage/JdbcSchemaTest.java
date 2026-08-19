package dev.xiaomu.crown.storage;

import dev.xiaomu.crown.storage.jdbc.JdbcDialect;
import dev.xiaomu.crown.storage.jdbc.JdbcSchema;
import dev.xiaomu.crown.storage.jdbc.SqliteConnectionFactory;
import dev.xiaomu.crown.storage.jdbc.TableNames;
import dev.xiaomu.crown.storage.repository.JdbcCrownRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcSchemaTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void createsCompleteVersionOneSchema() throws Exception {
        TableNames tables = TableNames.withPrefix("schema_");
        SqliteConnectionFactory connections = connectionFactory(
                "complete.db");

        try (JdbcCrownRepository repository =
                     new JdbcCrownRepository(
                             connections,
                             JdbcDialect.SQLITE,
                             tables)) {
            assertEquals(JdbcSchema.VERSION,
                    repository.initializeSchema());
        }

        try (Connection connection = connections.open()) {
            assertEquals(JdbcSchema.VERSION,
                    JdbcSchema.readVersion(connection, tables));
            assertTrue(tableExists(connection, tables.players()));
            assertTrue(tableExists(connection, tables.ownedTitles()));
            assertTrue(tableExists(connection,
                    tables.purchaseOrders()));
            assertTrue(tableExists(connection,
                    tables.titleCoinLedger()));
            assertTrue(tableExists(connection,
                    tables.saleCounters()));
            assertTrue(tableExists(connection, tables.cards()));
            assertTrue(tableExists(connection, tables.audit()));
        }
    }

    @Test
    void rejectsFutureSchemaBeforeCreatingBusinessTables()
            throws Exception {
        TableNames tables = TableNames.withPrefix("future_");
        SqliteConnectionFactory connections = connectionFactory(
                "future.db");

        try (Connection connection = connections.open();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE " + tables.schemaVersion()
                            + " (version INTEGER NOT NULL)");
            statement.executeUpdate(
                    "INSERT INTO " + tables.schemaVersion()
                            + "(version) VALUES (99)");
        }

        try (JdbcCrownRepository repository =
                     new JdbcCrownRepository(
                             connections,
                             JdbcDialect.SQLITE,
                             tables)) {
            assertThrows(StorageException.class,
                    repository::initializeSchema);
        }

        try (Connection connection = connections.open()) {
            assertTrue(tableExists(
                    connection, tables.schemaVersion()));
            assertFalse(tableExists(connection, tables.players()));
            assertFalse(tableExists(
                    connection, tables.purchaseOrders()));
            assertFalse(tableExists(
                    connection, tables.ownedTitles()));
        }
    }

    @Test
    void rejectsSchemaVersionTableWithMultipleRows()
            throws Exception {
        TableNames tables = TableNames.withPrefix("multiple_");
        SqliteConnectionFactory connections = connectionFactory(
                "multiple.db");

        try (Connection connection = connections.open();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE " + tables.schemaVersion()
                            + " (version INTEGER NOT NULL)");
            statement.executeUpdate(
                    "INSERT INTO " + tables.schemaVersion()
                            + "(version) VALUES (1)");
            statement.executeUpdate(
                    "INSERT INTO " + tables.schemaVersion()
                            + "(version) VALUES (1)");
        }

        try (JdbcCrownRepository repository =
                     new JdbcCrownRepository(
                             connections,
                             JdbcDialect.SQLITE,
                             tables)) {
            assertThrows(StorageException.class,
                    repository::initializeSchema);
        }
    }

    private SqliteConnectionFactory connectionFactory(String fileName) {
        return new SqliteConnectionFactory(
                temporaryDirectory.resolve(fileName),
                5_000,
                true,
                "NORMAL");
    }

    private static boolean tableExists(
            Connection connection,
            String table
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master"
                        + " WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1) == 1;
            }
        }
    }
}