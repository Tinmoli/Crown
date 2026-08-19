package dev.xiaomu.crown.storage.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;

/** Crown 逻辑 Schema v1 的创建、版本门禁和索引初始化。 */
public final class JdbcSchema {
    public static final int VERSION = 1;

    private JdbcSchema() {
    }

    public static int initialize(
            Connection connection,
            JdbcDialect dialect,
            TableNames tables
    ) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(tables, "tables");
        dialect.configure(connection);

        if (tableExists(connection, tables.schemaVersion())) {
            int existing = readVersion(connection, tables);
            if (existing != VERSION) {
                throw new SQLException(
                        "Unsupported Crown schema version: " + existing);
            }
        }

        String key36 = dialect.keyType(36);
        String key64 = dialect.keyType(64);
        String key128 = dialect.keyType(128);
        String key192 = dialect.keyType(192);
        String largeText = dialect.largeText();

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        version INTEGER NOT NULL
                    )
                    """.formatted(tables.schemaVersion()));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        player_uuid %s PRIMARY KEY,
                        last_known_name %s NOT NULL,
                        selection_type %s NOT NULL,
                        selected_entry_id %s NULL,
                        title_coin_balance BIGINT NOT NULL DEFAULT 0,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """.formatted(
                    tables.players(), key36, key64, key64, key36));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        order_id %s PRIMARY KEY,
                        mint_transaction_id %s NULL UNIQUE,
                        player_uuid %s NOT NULL,
                        product_type %s NOT NULL,
                        definition_id %s NULL,
                        payment_type %s NOT NULL,
                        currency_id %s NULL,
                        amount_minor BIGINT NOT NULL,
                        title_snapshot_json %s NOT NULL,
                        state %s NOT NULL,
                        entry_id %s NULL UNIQUE,
                        failure_code %s NULL,
                        inventory_reserved INTEGER NOT NULL DEFAULT 0,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        FOREIGN KEY (player_uuid)
                            REFERENCES %s(player_uuid)
                    )
                    """.formatted(
                    tables.purchaseOrders(), key36, key36, key36,
                    key64, key64, key64, key128, largeText,
                    key64, key36, key128, tables.players()));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        entry_id %s PRIMARY KEY,
                        player_uuid %s NOT NULL,
                        definition_id %s NULL,
                        kind %s NOT NULL,
                        title_text %s NOT NULL,
                        title_prefix %s NOT NULL,
                        title_suffix %s NOT NULL,
                        source %s NOT NULL,
                        acquired_at BIGINT NOT NULL,
                        expires_at BIGINT NULL,
                        purchase_order_id %s NULL UNIQUE,
                        status %s NOT NULL,
                        deleted_at BIGINT NULL,
                        deleted_by %s NULL,
                        FOREIGN KEY (player_uuid)
                            REFERENCES %s(player_uuid),
                        FOREIGN KEY (purchase_order_id)
                            REFERENCES %s(order_id)
                    )
                    """.formatted(
                    tables.ownedTitles(), key36, key36, key64,
                    key64, largeText, largeText, largeText, key192,
                    key36, key64, key192, tables.players(),
                    tables.purchaseOrders()));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        ledger_id %s,
                        player_uuid %s NOT NULL,
                        delta BIGINT NOT NULL,
                        balance_before BIGINT NOT NULL,
                        balance_after BIGINT NOT NULL,
                        actor %s NOT NULL,
                        reason %s NOT NULL,
                        order_id %s NULL,
                        created_at BIGINT NOT NULL,
                        FOREIGN KEY (player_uuid)
                            REFERENCES %s(player_uuid),
                        FOREIGN KEY (order_id)
                            REFERENCES %s(order_id)
                    )
                    """.formatted(
                    tables.titleCoinLedger(),
                    dialect.autoIncrementId(),
                    key36, key192, key128, key36,
                    tables.players(), tables.purchaseOrders()));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        definition_id %s PRIMARY KEY,
                        sold_count BIGINT NOT NULL,
                        reserved_count BIGINT NOT NULL DEFAULT 0,
                        revision BIGINT NOT NULL
                    )
                    """.formatted(tables.saleCounters(), key64));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        card_token %s PRIMARY KEY,
                        definition_id %s NOT NULL,
                        duration_type %s NOT NULL,
                        duration_days INTEGER NOT NULL,
                        issued_by %s NOT NULL,
                        issued_at BIGINT NOT NULL,
                        redeemed_by %s NULL,
                        redeemed_at BIGINT NULL
                    )
                    """.formatted(
                    tables.cards(), key128, key64, key64,
                    key192, key36));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        audit_id %s,
                        actor %s NOT NULL,
                        action %s NOT NULL,
                        player_uuid %s NULL,
                        target_id %s NULL,
                        details_json %s NOT NULL,
                        created_at BIGINT NOT NULL
                    )
                    """.formatted(
                    tables.audit(), dialect.autoIncrementId(),
                    key192, key128, key36, key128, largeText));
        }

        createIndex(connection, dialect,
                tables.ownedTitles() + "_player_idx",
                "CREATE INDEX " + tables.ownedTitles() + "_player_idx"
                        + " ON " + tables.ownedTitles()
                        + "(player_uuid, status, acquired_at)");
        createIndex(connection, dialect,
                tables.purchaseOrders() + "_player_idx",
                "CREATE INDEX " + tables.purchaseOrders() + "_player_idx"
                        + " ON " + tables.purchaseOrders()
                        + "(player_uuid, state, created_at)");
        createIndex(connection, dialect,
                tables.purchaseOrders() + "_state_idx",
                "CREATE INDEX " + tables.purchaseOrders() + "_state_idx"
                        + " ON " + tables.purchaseOrders()
                        + "(state, updated_at)");
        createIndex(connection, dialect,
                tables.titleCoinLedger() + "_player_idx",
                "CREATE INDEX " + tables.titleCoinLedger() + "_player_idx"
                        + " ON " + tables.titleCoinLedger()
                        + "(player_uuid, created_at)");
        createIndex(connection, dialect,
                tables.audit() + "_target_idx",
                "CREATE INDEX " + tables.audit() + "_target_idx"
                        + " ON " + tables.audit()
                        + "(target_id, created_at)");

        int existing = readVersion(connection, tables);
        if (existing == 0) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + tables.schemaVersion()
                            + "(version) VALUES (?)")) {
                insert.setInt(1, VERSION);
                insert.executeUpdate();
            }
        } else if (existing != VERSION) {
            throw new SQLException(
                    "Unsupported Crown schema version: " + existing);
        }
        return VERSION;
    }

    public static int readVersion(
            Connection connection,
            TableNames tables
    ) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT version FROM " + tables.schemaVersion())) {
            if (!result.next()) {
                return 0;
            }
            int version = result.getInt(1);
            if (result.next()) {
                throw new SQLException(
                        "Crown schema version table has multiple rows");
            }
            return version;
        }
    }

    private static boolean tableExists(
            Connection connection,
            String table
    ) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 1 FROM " + table + " WHERE 1 = 0")) {
            result.getMetaData();
            return true;
        } catch (SQLException exception) {
            String message = exception.getMessage() == null
                    ? ""
                    : exception.getMessage().toLowerCase(Locale.ROOT);
            boolean missing = exception.getErrorCode() == 1146
                    || "42S02".equals(exception.getSQLState())
                    || message.contains("no such table");
            if (missing) {
                return false;
            }
            throw exception;
        }
    }

    private static void createIndex(
            Connection connection,
            JdbcDialect dialect,
            String name,
            String sql
    ) throws SQLException {
        String command = dialect == JdbcDialect.SQLITE
                ? sql.replace(
                "CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ")
                : sql;
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(command);
        } catch (SQLException exception) {
            boolean duplicateMySqlIndex =
                    dialect == JdbcDialect.MYSQL
                            && (exception.getErrorCode() == 1061
                            || "42000".equals(exception.getSQLState()));
            if (!duplicateMySqlIndex) {
                throw new SQLException(
                        "Could not create Crown index " + name,
                        exception);
            }
        }
    }
}