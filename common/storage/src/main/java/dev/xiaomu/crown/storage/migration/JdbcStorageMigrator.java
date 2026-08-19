package dev.xiaomu.crown.storage.migration;

import dev.xiaomu.crown.config.model.StorageSettings;
import dev.xiaomu.crown.storage.StorageException;
import dev.xiaomu.crown.storage.backend.StorageBackend;
import dev.xiaomu.crown.storage.jdbc.JdbcDialect;
import dev.xiaomu.crown.storage.jdbc.JdbcSchema;
import dev.xiaomu.crown.storage.jdbc.TableNames;
import dev.xiaomu.crown.storage.model.StorageSummary;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 把一个 Crown JDBC 后端完整复制到空目标。目标复制和校验位于同一事务，
 * 任意失败均回滚；调用方必须先进入维护模式并阻止新的业务写入。
 */
public final class JdbcStorageMigrator {
    private static final int BATCH_SIZE = 500;

    public StorageMigrationReport migrate(
            StorageBackend source,
            StorageBackend target,
            StorageSettings.Verification verification,
            Instant startedAt
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(verification, "verification");
        Objects.requireNonNull(startedAt, "startedAt");
        if (source == target) {
            throw new IllegalArgumentException(
                    "Source and target storage backends are identical");
        }
        if (source.sqliteDatabase().isPresent()
                && target.sqliteDatabase().isPresent()
                && source.sqliteDatabase().orElseThrow().equals(
                target.sqliteDatabase().orElseThrow())) {
            throw new IllegalArgumentException(
                    "Source and target SQLite databases are identical");
        }

        try (Connection sourceConnection =
                     source.connections().open();
             Connection targetConnection =
                     target.connections().open()) {
            configureTransaction(
                    sourceConnection, source.dialect(), true);
            configureTransaction(
                    targetConnection, target.dialect(), false);

            boolean sourceFinished = false;
            boolean targetFinished = false;
            try {
                StorageSummary sourceSummary = summarize(
                        sourceConnection, source.tables());
                StorageSummary initialTarget = summarize(
                        targetConnection, target.tables());
                if (initialTarget.hasBusinessData()) {
                    throw new StorageException(
                            "Crown migration target is not empty");
                }

                Map<String, Long> copied = copyAll(
                        sourceConnection,
                        source.tables(),
                        targetConnection,
                        target.tables());

                StorageSummary targetSummary = summarize(
                        targetConnection, target.tables());
                verifyCopiedRowCounts(sourceSummary, copied);
                StorageSummaryVerifier.verify(
                        sourceSummary,
                        targetSummary,
                        verification);

                sourceConnection.rollback();
                sourceFinished = true;
                targetConnection.commit();
                targetFinished = true;

                Duration duration = Duration.between(
                        startedAt, Instant.now());
                if (duration.isNegative()) {
                    duration = Duration.ZERO;
                }
                return new StorageMigrationReport(
                        source.type(),
                        target.type(),
                        copied,
                        sourceSummary,
                        targetSummary,
                        duration);
            } catch (SQLException | RuntimeException exception) {
                if (!targetFinished) {
                    rollback(targetConnection, exception);
                }
                if (!sourceFinished) {
                    rollback(sourceConnection, exception);
                }
                if (exception instanceof StorageException storage) {
                    throw storage;
                }
                if (exception instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new StorageException(
                        "Crown storage migration failed",
                        exception);
            } finally {
                restoreAutoCommit(sourceConnection);
                restoreAutoCommit(targetConnection);
            }
        } catch (SQLException exception) {
            throw new StorageException(
                    "Could not open Crown migration connections",
                    exception);
        }
    }

    private static void configureTransaction(
            Connection connection,
            JdbcDialect dialect,
            boolean source
    ) throws SQLException {
        dialect.configure(connection);
        if (dialect == JdbcDialect.MYSQL) {
            connection.setTransactionIsolation(
                    source
                            ? Connection.TRANSACTION_REPEATABLE_READ
                            : Connection.TRANSACTION_READ_COMMITTED);
        }
        connection.setAutoCommit(false);
    }

    private static Map<String, Long> copyAll(
            Connection source,
            TableNames sourceTables,
            Connection target,
            TableNames targetTables
    ) throws SQLException {
        var copied = new LinkedHashMap<String, Long>();
        copied.put("players", copyTable(
                source, sourceTables.players(),
                target, targetTables.players(),
                "player_uuid",
                "last_known_name",
                "selection_type",
                "selected_entry_id",
                "title_coin_balance",
                "created_at",
                "updated_at"));
        copied.put("purchase_orders", copyTable(
                source, sourceTables.purchaseOrders(),
                target, targetTables.purchaseOrders(),
                "order_id",
                "mint_transaction_id",
                "player_uuid",
                "product_type",
                "definition_id",
                "payment_type",
                "currency_id",
                "amount_minor",
                "title_snapshot_json",
                "state",
                "entry_id",
                "failure_code",
                "inventory_reserved",
                "created_at",
                "updated_at"));
        copied.put("owned_titles", copyTable(
                source, sourceTables.ownedTitles(),
                target, targetTables.ownedTitles(),
                "entry_id",
                "player_uuid",
                "definition_id",
                "kind",
                "title_text",
                "title_prefix",
                "title_suffix",
                "source",
                "acquired_at",
                "expires_at",
                "purchase_order_id",
                "status",
                "deleted_at",
                "deleted_by"));
        copied.put("title_coin_ledger", copyTable(
                source, sourceTables.titleCoinLedger(),
                target, targetTables.titleCoinLedger(),
                "ledger_id",
                "player_uuid",
                "delta",
                "balance_before",
                "balance_after",
                "actor",
                "reason",
                "order_id",
                "created_at"));
        copied.put("sale_counters", copyTable(
                source, sourceTables.saleCounters(),
                target, targetTables.saleCounters(),
                "definition_id",
                "sold_count",
                "reserved_count",
                "revision"));
        copied.put("cards", copyTable(
                source, sourceTables.cards(),
                target, targetTables.cards(),
                "card_token",
                "definition_id",
                "duration_type",
                "duration_days",
                "issued_by",
                "issued_at",
                "redeemed_by",
                "redeemed_at"));
        copied.put("audit", copyTable(
                source, sourceTables.audit(),
                target, targetTables.audit(),
                "audit_id",
                "actor",
                "action",
                "player_uuid",
                "target_id",
                "details_json",
                "created_at"));
        return Map.copyOf(copied);
    }

    private static long copyTable(
            Connection source,
            String sourceTable,
            Connection target,
            String targetTable,
            String... columns
    ) throws SQLException {
        String columnList = String.join(", ", columns);
        String placeholders = String.join(
                ", ", java.util.Collections.nCopies(
                        columns.length, "?"));
        String selectSql = "SELECT " + columnList
                + " FROM " + sourceTable;
        String insertSql = "INSERT INTO " + targetTable
                + "(" + columnList + ") VALUES ("
                + placeholders + ')';

        long rows = 0;
        int pending = 0;
        try (PreparedStatement select =
                     source.prepareStatement(selectSql);
             PreparedStatement insert =
                     target.prepareStatement(insertSql)) {
            select.setFetchSize(BATCH_SIZE);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    for (int index = 0;
                         index < columns.length;
                         index++) {
                        insert.setObject(
                                index + 1,
                                result.getObject(index + 1));
                    }
                    insert.addBatch();
                    rows++;
                    pending++;
                    if (pending == BATCH_SIZE) {
                        insert.executeBatch();
                        pending = 0;
                    }
                }
                if (pending != 0) {
                    insert.executeBatch();
                }
            }
        }
        return rows;
    }

    private static StorageSummary summarize(
            Connection connection,
            TableNames tables
    ) throws SQLException {
        return new StorageSummary(
                JdbcSchema.readVersion(connection, tables),
                count(connection, tables.players()),
                count(connection, tables.ownedTitles()),
                count(connection, tables.purchaseOrders()),
                count(connection, tables.titleCoinLedger()),
                sum(connection, tables.players(),
                        "title_coin_balance"),
                count(connection, tables.saleCounters()),
                count(connection, tables.cards()),
                count(connection, tables.audit()));
    }

    private static long count(
            Connection connection,
            String table
    ) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM " + table)) {
            if (!result.next()) {
                throw new SQLException("No migration count result");
            }
            return result.getLong(1);
        }
    }

    private static long sum(
            Connection connection,
            String table,
            String column
    ) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COALESCE(SUM(" + column + "), 0)"
                             + " FROM " + table)) {
            if (!result.next()) {
                throw new SQLException("No migration sum result");
            }
            return result.getLong(1);
        }
    }

    private static void verifyCopiedRowCounts(
            StorageSummary source,
            Map<String, Long> copied
    ) {
        requireCopied(
                copied, "players", source.playerCount());
        requireCopied(
                copied, "owned_titles",
                source.ownedTitleCount());
        requireCopied(
                copied, "purchase_orders",
                source.purchaseOrderCount());
        requireCopied(
                copied, "title_coin_ledger",
                source.titleCoinLedgerCount());
        requireCopied(
                copied, "sale_counters",
                source.saleCounterCount());
        requireCopied(
                copied, "cards", source.cardCount());
        requireCopied(
                copied, "audit", source.auditCount());
    }

    private static void requireCopied(
            Map<String, Long> copied,
            String table,
            long expected
    ) {
        long actual = copied.getOrDefault(table, -1L);
        if (actual != expected) {
            throw new StorageException(
                    "Crown migration copied row count differs for "
                            + table + ": expected=" + expected
                            + ", actual=" + actual);
        }
    }

    private static void rollback(
            Connection connection,
            Exception original
    ) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void restoreAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 连接随即关闭，原始迁移结果优先。
        }
    }
}