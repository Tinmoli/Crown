package dev.xiaomu.crown.storage.repository;

import dev.xiaomu.crown.domain.catalog.DefinitionId;
import dev.xiaomu.crown.domain.catalog.DurationPolicy;
import dev.xiaomu.crown.domain.catalog.DurationType;
import dev.xiaomu.crown.domain.catalog.NamespacedId;
import dev.xiaomu.crown.domain.catalog.PaymentType;
import dev.xiaomu.crown.domain.order.PurchaseOrderState;
import dev.xiaomu.crown.domain.player.SelectionType;
import dev.xiaomu.crown.domain.player.TitleSelection;
import dev.xiaomu.crown.storage.StorageException;
import dev.xiaomu.crown.storage.jdbc.ConnectionFactory;
import dev.xiaomu.crown.storage.jdbc.JdbcDialect;
import dev.xiaomu.crown.storage.jdbc.JdbcSchema;
import dev.xiaomu.crown.storage.jdbc.TableNames;
import dev.xiaomu.crown.storage.model.AuditRecord;
import dev.xiaomu.crown.storage.model.CardRecord;
import dev.xiaomu.crown.storage.model.CardRedemptionResult;
import dev.xiaomu.crown.storage.model.CoinAdjustmentResult;
import dev.xiaomu.crown.storage.model.InternalPaymentResult;
import dev.xiaomu.crown.storage.model.InternalPaymentStatus;
import dev.xiaomu.crown.storage.model.OrderPreparationStatus;
import dev.xiaomu.crown.storage.model.OwnedTitleDurationStatus;
import dev.xiaomu.crown.storage.model.OwnedTitleKind;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import dev.xiaomu.crown.storage.model.OwnedTitleStatus;
import dev.xiaomu.crown.storage.model.PlayerRecord;
import dev.xiaomu.crown.storage.model.ProductType;
import dev.xiaomu.crown.storage.model.PurchaseOrderRecord;
import dev.xiaomu.crown.storage.model.SaleCounterRecord;
import dev.xiaomu.crown.storage.model.StorageSummary;
import dev.xiaomu.crown.storage.model.TitleCoinLedgerRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 使用参数化 SQL 实现 SQLite/MySQL 共用 Repository。 */
public final class JdbcCrownRepository implements CrownRepository {
    private static final int MAX_QUERY_LIMIT = 10_000;

    private final ConnectionFactory connections;
    private final JdbcDialect dialect;
    private final TableNames tables;
    private final boolean closeConnections;

    public JdbcCrownRepository(
            ConnectionFactory connections,
            JdbcDialect dialect,
            TableNames tables
    ) {
        this(connections, dialect, tables, true);
    }

    /**
     * @param closeConnections close 时是否关闭底层工厂；共享后端资源时传
     *                         false，由后端句柄统一管理生命周期
     */
    public JdbcCrownRepository(
            ConnectionFactory connections,
            JdbcDialect dialect,
            TableNames tables,
            boolean closeConnections
    ) {
        this.connections = Objects.requireNonNull(
                connections, "connections");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.closeConnections = closeConnections;
    }

    @Override
    public int initializeSchema() {
        try (Connection connection = open()) {
            return JdbcSchema.initialize(connection, dialect, tables);
        } catch (SQLException exception) {
            throw failure("Could not initialize Crown schema", exception);
        }
    }

    @Override
    public PlayerRecord ensurePlayer(
            UUID playerId,
            String currentName,
            TitleSelection initialSelection,
            Instant now
    ) {
        Objects.requireNonNull(playerId, "playerId");
        requireName(currentName);
        Objects.requireNonNull(initialSelection, "initialSelection");
        Objects.requireNonNull(now, "now");

        return transaction(connection -> {
            Optional<PlayerRecord> existing =
                    findPlayer(connection, playerId);
            if (existing.isPresent()) {
                PlayerRecord player = existing.orElseThrow();
                if (!player.lastKnownName().equals(currentName)) {
                    try (PreparedStatement update =
                                 connection.prepareStatement(
                                         "UPDATE " + tables.players()
                                                 + " SET last_known_name = ?,"
                                                 + " updated_at = ?"
                                                 + " WHERE player_uuid = ?")) {
                        update.setString(1, currentName);
                        update.setLong(2, epoch(now));
                        update.setString(3, playerId.toString());
                        update.executeUpdate();
                    }
                    return new PlayerRecord(
                            playerId,
                            currentName,
                            player.selection(),
                            player.titleCoinBalance(),
                            player.createdAt(),
                            now);
                }
                return player;
            }

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + tables.players() + "("
                            + "player_uuid, last_known_name,"
                            + " selection_type, selected_entry_id,"
                            + " title_coin_balance, created_at, updated_at"
                            + ") VALUES (?, ?, ?, ?, 0, ?, ?)")) {
                insert.setString(1, playerId.toString());
                insert.setString(2, currentName);
                bindSelection(insert, 3, 4, initialSelection);
                insert.setLong(5, epoch(now));
                insert.setLong(6, epoch(now));
                insert.executeUpdate();
            }
            return new PlayerRecord(
                    playerId, currentName, initialSelection,
                    0, now, now);
        });
    }

    @Override
    public Optional<PlayerRecord> findPlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return query(connection -> findPlayer(connection, playerId));
    }

    @Override
    public boolean setSelection(
            UUID playerId,
            TitleSelection selection,
            Instant now
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(now, "now");

        return transaction(connection -> {
            if (selection.type() == SelectionType.OWNED) {
                UUID entryId = selection.ownedEntryId().orElseThrow();
                Optional<OwnedTitleRecord> owned =
                        findOwnedTitle(connection, entryId);
                if (owned.isEmpty()
                        || !owned.orElseThrow().playerId().equals(playerId)
                        || owned.orElseThrow().status()
                        != OwnedTitleStatus.ACTIVE
                        || owned.orElseThrow().expiredAt(now)) {
                    return false;
                }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + tables.players()
                            + " SET selection_type = ?,"
                            + " selected_entry_id = ?, updated_at = ?"
                            + " WHERE player_uuid = ?")) {
                bindSelection(update, 1, 2, selection);
                update.setLong(3, epoch(now));
                update.setString(4, playerId.toString());
                return update.executeUpdate() == 1;
            }
        });
    }

    @Override
    public List<OwnedTitleRecord> listOwnedTitles(
            UUID playerId,
            boolean includeDeleted
    ) {
        Objects.requireNonNull(playerId, "playerId");
        return query(connection -> {
            String sql = "SELECT * FROM " + tables.ownedTitles()
                    + " WHERE player_uuid = ?"
                    + (includeDeleted ? "" : " AND status = 'ACTIVE'")
                    + " ORDER BY acquired_at DESC, entry_id ASC";
            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    var records = new ArrayList<OwnedTitleRecord>();
                    while (result.next()) {
                        records.add(readOwnedTitle(result));
                    }
                    return List.copyOf(records);
                }
            }
        });
    }

    @Override
    public Optional<OwnedTitleRecord> findOwnedTitle(UUID entryId) {
        Objects.requireNonNull(entryId, "entryId");
        return query(connection -> findOwnedTitle(connection, entryId));
    }

    @Override
    public boolean insertOwnedTitle(OwnedTitleRecord title) {
        Objects.requireNonNull(title, "title");
        return transaction(connection -> {
            if (findOwnedTitle(connection, title.entryId()).isPresent()) {
                return false;
            }
            insertOwnedTitle(connection, title);
            return true;
        });
    }

    @Override
    public boolean insertOwnedTitleWithAudit(
            OwnedTitleRecord title,
            AuditRecord audit
    ) {
        Objects.requireNonNull(title, "title");
        requireGrantAudit(title, audit);
        return transaction(connection -> {
            if (findOwnedTitle(connection, title.entryId(), true)
                    .isPresent()) {
                return false;
            }
            insertOwnedTitle(connection, title);
            insertAudit(connection, audit);
            return true;
        });
    }

    @Override
    public OwnedTitleDurationStatus updateOwnedTitleDurationWithAudit(
            UUID playerId,
            UUID entryId,
            Instant expiresAt,
            AuditRecord audit,
            Instant now
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(now, "now");
        requireDurationAudit(playerId, entryId, audit, now);

        return transaction(connection -> {
            OwnedTitleRecord record =
                    findOwnedTitle(connection, entryId, true)
                            .orElse(null);
            if (record == null) {
                return OwnedTitleDurationStatus.NOT_FOUND;
            }
            if (!record.playerId().equals(playerId)) {
                return OwnedTitleDurationStatus.NOT_OWNED;
            }
            if (record.status() != OwnedTitleStatus.ACTIVE) {
                return OwnedTitleDurationStatus.DELETED;
            }
            if (expiresAt != null
                    && !expiresAt.isAfter(record.acquiredAt())) {
                throw new IllegalArgumentException(
                        "Title expiry must follow acquisition");
            }

            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + tables.ownedTitles()
                            + " SET expires_at = ?"
                            + " WHERE entry_id = ? AND player_uuid = ?"
                            + " AND status = 'ACTIVE'")) {
                if (expiresAt == null) {
                    update.setNull(1, Types.BIGINT);
                } else {
                    update.setLong(1, epoch(expiresAt));
                }
                update.setString(2, entryId.toString());
                update.setString(3, playerId.toString());
                if (update.executeUpdate() != 1) {
                    throw new StorageException(
                            "Owned title changed during duration update");
                }
            }
            insertAudit(connection, audit);
            return OwnedTitleDurationStatus.UPDATED;
        });
    }

    @Override
    public boolean softDeleteOwnedTitle(
            UUID playerId,
            UUID entryId,
            String actor,
            Instant now
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(entryId, "entryId");
        requireText(actor, "actor", 192);
        Objects.requireNonNull(now, "now");

        return transaction(connection -> {
            int changed;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + tables.ownedTitles()
                            + " SET status = 'DELETED', deleted_at = ?,"
                            + " deleted_by = ?"
                            + " WHERE entry_id = ? AND player_uuid = ?"
                            + " AND status = 'ACTIVE'")) {
                update.setLong(1, epoch(now));
                update.setString(2, actor);
                update.setString(3, entryId.toString());
                update.setString(4, playerId.toString());
                changed = update.executeUpdate();
            }
            if (changed == 0) {
                return false;
            }
            try (PreparedStatement clear = connection.prepareStatement(
                    "UPDATE " + tables.players()
                            + " SET selection_type = 'NONE',"
                            + " selected_entry_id = NULL, updated_at = ?"
                            + " WHERE player_uuid = ?"
                            + " AND selection_type = 'OWNED'"
                            + " AND selected_entry_id = ?")) {
                clear.setLong(1, epoch(now));
                clear.setString(2, playerId.toString());
                clear.setString(3, entryId.toString());
                clear.executeUpdate();
            }
            return true;
        });
    }

    @Override
    public CoinAdjustmentResult adjustTitleCoins(
            UUID playerId,
            long delta,
            long maximumBalance,
            String actor,
            String reason,
            UUID orderId,
            Instant now
    ) {
        Objects.requireNonNull(playerId, "playerId");
        if (delta == 0 || maximumBalance < 1) {
            throw new IllegalArgumentException(
                    "Invalid title coin adjustment");
        }
        requireText(actor, "actor", 192);
        requireText(reason, "reason", 128);
        Objects.requireNonNull(now, "now");

        return transaction(connection -> {
            long before = lockBalance(connection, playerId);
            long after;
            try {
                after = Math.addExact(before, delta);
            } catch (ArithmeticException exception) {
                throw new StorageException(
                        "Title coin balance overflow", exception);
            }
            if (after < 0 || after > maximumBalance) {
                throw new StorageException(
                        "Title coin balance would be outside limits");
            }

            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + tables.players()
                            + " SET title_coin_balance = ?, updated_at = ?"
                            + " WHERE player_uuid = ?"
                            + " AND title_coin_balance = ?")) {
                update.setLong(1, after);
                update.setLong(2, epoch(now));
                update.setString(3, playerId.toString());
                update.setLong(4, before);
                if (update.executeUpdate() != 1) {
                    throw new StorageException(
                            "Concurrent title coin balance update");
                }
            }

            long ledgerId;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + tables.titleCoinLedger() + "("
                            + "player_uuid, delta, balance_before,"
                            + " balance_after, actor, reason, order_id,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, playerId.toString());
                insert.setLong(2, delta);
                insert.setLong(3, before);
                insert.setLong(4, after);
                insert.setString(5, actor);
                insert.setString(6, reason);
                setNullableUuid(insert, 7, orderId);
                insert.setLong(8, epoch(now));
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException(
                                "No generated title coin ledger ID");
                    }
                    ledgerId = keys.getLong(1);
                }
            }

            TitleCoinLedgerRecord ledger = new TitleCoinLedgerRecord(
                    ledgerId, playerId, delta, before, after,
                    actor, reason, orderId, now);
            return new CoinAdjustmentResult(before, after, ledger);
        });
    }

    @Override
    public List<TitleCoinLedgerRecord> titleCoinLedger(
            UUID playerId,
            int limit
    ) {
        Objects.requireNonNull(playerId, "playerId");
        requireLimit(limit);
        return query(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement(
                                 "SELECT * FROM "
                                         + tables.titleCoinLedger()
                                         + " WHERE player_uuid = ?"
                                         + " ORDER BY ledger_id DESC"
                                         + " LIMIT ?")) {
                statement.setString(1, playerId.toString());
                statement.setInt(2, limit);
                try (ResultSet result = statement.executeQuery()) {
                    var records =
                            new ArrayList<TitleCoinLedgerRecord>();
                    while (result.next()) {
                        records.add(readLedger(result));
                    }
                    return List.copyOf(records);
                }
            }
        });
    }

    @Override
    public OrderPreparationStatus prepareOrder(
            PurchaseOrderRecord order,
            long globalStock,
            int perPlayerLimit
    ) {
        Objects.requireNonNull(order, "order");
        requireSaleLimits(globalStock, perPlayerLimit);
        if (order.state() != PurchaseOrderState.PREPARED
                || order.entryId() != null
                || order.failureCode() != null) {
            throw new IllegalArgumentException(
                    "Only a clean PREPARED order can be prepared");
        }

        boolean limitedStock = globalStock >= 0;
        if (order.inventoryReserved() != limitedStock) {
            throw new IllegalArgumentException(
                    "Order inventory flag differs from sale policy");
        }
        DefinitionId definition = order.definition().orElse(null);
        if ((limitedStock || perPlayerLimit >= 0)
                && definition == null) {
            throw new IllegalArgumentException(
                    "Stock and per-player limits require a catalog order");
        }

        return transaction(connection -> {
            if (findOrder(connection, order.orderId()).isPresent()) {
                return OrderPreparationStatus.ORDER_ALREADY_EXISTS;
            }
            lockPlayerForPurchase(connection, order.playerId());

            if (perPlayerLimit >= 0
                    && countPlayerPurchases(
                    connection, order.playerId(), definition)
                    >= perPlayerLimit) {
                return OrderPreparationStatus.PLAYER_LIMIT_REACHED;
            }

            if (limitedStock) {
                SaleCounterRecord counter =
                        ensureAndLockSaleCounter(
                                connection, definition);
                if (counter.occupiedStock() >= globalStock) {
                    return OrderPreparationStatus.OUT_OF_STOCK;
                }
                try (PreparedStatement reserve =
                             connection.prepareStatement(
                                     "UPDATE " + tables.saleCounters()
                                             + " SET reserved_count"
                                             + " = reserved_count + 1,"
                                             + " revision = revision + 1"
                                             + " WHERE definition_id = ?"
                                             + " AND revision = ?"
                                             + " AND sold_count"
                                             + " + reserved_count < ?")) {
                    reserve.setString(1, definition.value());
                    reserve.setLong(2, counter.revision());
                    reserve.setLong(3, globalStock);
                    if (reserve.executeUpdate() != 1) {
                        return OrderPreparationStatus.OUT_OF_STOCK;
                    }
                }
            }

            insertOrder(connection, order);
            return OrderPreparationStatus.CREATED;
        });
    }

    @Override
    public boolean createOrder(PurchaseOrderRecord order) {
        Objects.requireNonNull(order, "order");
        if (order.inventoryReserved()) {
            throw new IllegalArgumentException(
                    "Reserved orders must use prepareOrder");
        }
        return transaction(connection -> {
            if (findOrder(connection, order.orderId()).isPresent()) {
                return false;
            }
            insertOrder(connection, order);
            return true;
        });
    }

    @Override
    public Optional<PurchaseOrderRecord> findOrder(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return query(connection -> findOrder(connection, orderId));
    }

    @Override
    public InternalPaymentResult commitInternalPayment(
            UUID orderId,
            String actor,
            String reason,
            Instant now
    ) {
        Objects.requireNonNull(orderId, "orderId");
        requireText(actor, "actor", 192);
        requireText(reason, "reason", 128);
        Objects.requireNonNull(now, "now");

        return transaction(connection -> {
            PurchaseOrderRecord order =
                    findOrder(connection, orderId, true)
                            .orElse(null);
            if (order == null) {
                return InternalPaymentResult.of(
                        InternalPaymentStatus.INVALID_STATE);
            }
            if (order.state()
                    == PurchaseOrderState.PAYMENT_COMMITTED
                    || order.state()
                    == PurchaseOrderState.GRANTED) {
                return InternalPaymentResult.of(
                        InternalPaymentStatus.ALREADY_COMMITTED);
            }
            if (order.state()
                    != PurchaseOrderState.PAYMENT_PENDING) {
                return InternalPaymentResult.of(
                        InternalPaymentStatus.INVALID_STATE);
            }

            return switch (order.paymentType()) {
                case FREE -> commitFreePayment(
                        connection, order, now);
                case TITLE_COIN -> commitTitleCoinPayment(
                        connection, order, actor, reason, now);
                case MINT -> throw new IllegalArgumentException(
                        "Mint orders cannot use internal payment");
            };
        });
    }

    @Override
    public List<PurchaseOrderRecord> findRecoverableOrders(int limit) {
        requireLimit(limit);
        return query(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement(
                                 "SELECT * FROM "
                                         + tables.purchaseOrders()
                                         + " WHERE state IN ("
                                         + "'PREPARED', 'PAYMENT_PENDING',"
                                         + " 'PAYMENT_COMMITTED')"
                                         + " ORDER BY created_at ASC"
                                         + " LIMIT ?")) {
                statement.setInt(1, limit);
                try (ResultSet result = statement.executeQuery()) {
                    var records =
                            new ArrayList<PurchaseOrderRecord>();
                    while (result.next()) {
                        records.add(readOrder(result));
                    }
                    return List.copyOf(records);
                }
            }
        });
    }

    @Override
    public long countPendingOrders(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return query(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement(
                                 "SELECT COUNT(*) FROM "
                                         + tables.purchaseOrders()
                                         + " WHERE player_uuid = ?"
                                         + " AND state IN ('PREPARED',"
                                         + " 'PAYMENT_PENDING',"
                                         + " 'PAYMENT_COMMITTED')")) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException(
                                "No pending order count result");
                    }
                    return result.getLong(1);
                }
            }
        });
    }

    @Override
    public long countPlayerPurchases(
            UUID playerId,
            DefinitionId definitionId
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(definitionId, "definitionId");
        return query(connection -> countPlayerPurchases(
                connection, playerId, definitionId));
    }

    @Override
    public Optional<SaleCounterRecord> findSaleCounter(
            DefinitionId definitionId
    ) {
        Objects.requireNonNull(definitionId, "definitionId");
        return query(connection -> findSaleCounter(
                connection, definitionId, false));
    }

    @Override
    public boolean transitionOrder(
            UUID orderId,
            PurchaseOrderState expected,
            PurchaseOrderState target,
            String failureCode,
            Instant now
    ) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(expected, "expected")
                .requireTransitionTo(target);
        Objects.requireNonNull(now, "now");
        if (failureCode != null) {
            requireText(failureCode, "failureCode", 128);
        }
        if (target != PurchaseOrderState.FAILED
                && failureCode != null) {
            throw new IllegalArgumentException(
                    "Only FAILED transition has a failure code");
        }

        return transaction(connection -> {
            PurchaseOrderRecord order =
                    findOrder(connection, orderId, true)
                            .orElse(null);
            if (order == null || order.state() != expected) {
                return false;
            }

            boolean releaseReservation =
                    order.inventoryReserved()
                            && (target == PurchaseOrderState.FAILED
                            || target == PurchaseOrderState.CANCELLED);
            String reservationUpdate = releaseReservation
                    ? ", inventory_reserved = 0" : "";
            int changed;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + tables.purchaseOrders()
                            + " SET state = ?, failure_code = ?,"
                            + " updated_at = ?" + reservationUpdate
                            + " WHERE order_id = ? AND state = ?")) {
                update.setString(1, target.name());
                setNullableString(update, 2, failureCode);
                update.setLong(3, epoch(now));
                update.setString(4, orderId.toString());
                update.setString(5, expected.name());
                changed = update.executeUpdate();
            }
            if (changed == 0) {
                return false;
            }
            if (releaseReservation) {
                releaseReservation(connection, order);
            }
            return true;
        });
    }

    @Override
    public OwnedTitleRecord grantCommittedOrder(
            UUID orderId,
            OwnedTitleRecord title,
            Instant now
    ) {
        return grantCommittedOrder(
                orderId, title, null, now);
    }

    @Override
    public OwnedTitleRecord grantCommittedOrderWithAudit(
            UUID orderId,
            OwnedTitleRecord title,
            AuditRecord audit,
            Instant now
    ) {
        Objects.requireNonNull(audit, "audit");
        if (audit.persisted()
                || !Objects.equals(
                audit.playerId(), title.playerId())
                || !Objects.equals(
                audit.targetId(), orderId.toString())
                || !audit.createdAt().equals(now)) {
            throw new IllegalArgumentException(
                    "Purchase audit does not match the grant");
        }
        return grantCommittedOrder(
                orderId, title, audit, now);
    }

    private OwnedTitleRecord grantCommittedOrder(
            UUID orderId,
            OwnedTitleRecord title,
            AuditRecord audit,
            Instant now
    ) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(now, "now");
        if (!orderId.equals(title.purchaseOrderId())) {
            throw new IllegalArgumentException(
                    "Owned title references a different order");
        }

        return transaction(connection -> {
            PurchaseOrderRecord order =
                    findOrder(connection, orderId, true)
                            .orElseThrow(() -> new StorageException(
                                    "Purchase order does not exist"));
            if (order.state() == PurchaseOrderState.GRANTED) {
                UUID existingEntry = order.grantedEntryId().orElseThrow();
                return findOwnedTitle(connection, existingEntry)
                        .orElseThrow(() -> new StorageException(
                                "Granted order has no owned title"));
            }
            if (order.state()
                    != PurchaseOrderState.PAYMENT_COMMITTED) {
                throw new StorageException(
                        "Order payment is not committed");
            }
            if (!order.playerId().equals(title.playerId())) {
                throw new StorageException(
                        "Order and title players differ");
            }
            if (!Objects.equals(
                    order.definitionId(), title.definitionId())) {
                throw new StorageException(
                        "Order and title definitions differ");
            }

            Optional<OwnedTitleRecord> existing =
                    findOwnedByOrder(connection, orderId);
            OwnedTitleRecord granted;
            if (existing.isPresent()) {
                granted = existing.orElseThrow();
            } else {
                insertOwnedTitle(connection, title);
                granted = title;
            }

            recordCompletedSale(connection, order);

            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + tables.purchaseOrders()
                            + " SET state = 'GRANTED', entry_id = ?,"
                            + " inventory_reserved = 0, updated_at = ?"
                            + " WHERE order_id = ?"
                            + " AND state = 'PAYMENT_COMMITTED'")) {
                update.setString(1, granted.entryId().toString());
                update.setLong(2, epoch(now));
                update.setString(3, orderId.toString());
                if (update.executeUpdate() != 1) {
                    throw new StorageException(
                            "Could not finalize committed order");
                }
            }
            if (audit != null) {
                insertAudit(connection, audit);
            }
            return granted;
        });
    }

    @Override
    public boolean createCard(CardRecord card) {
        Objects.requireNonNull(card, "card");
        requireNewCard(card);
        return transaction(connection -> {
            if (findCard(connection, card.cardToken(), false)
                    .isPresent()) {
                return false;
            }
            insertCard(connection, card);
            return true;
        });
    }

    @Override
    public List<CardRecord> createCardsWithAudit(
            List<CardRecord> cards,
            AuditRecord audit
    ) {
        List<CardRecord> batch = List.copyOf(
                Objects.requireNonNull(cards, "cards"));
        Objects.requireNonNull(audit, "audit");
        if (batch.isEmpty() || batch.size() > 64) {
            throw new IllegalArgumentException(
                    "Card batch size must be between 1 and 64");
        }
        if (audit.persisted()) {
            throw new IllegalArgumentException(
                    "A new audit record cannot already have an ID");
        }

        java.util.HashSet<String> tokens = new java.util.HashSet<>();
        for (CardRecord card : batch) {
            requireNewCard(card);
            if (!tokens.add(card.cardToken())) {
                throw new IllegalArgumentException(
                        "Card batch contains duplicate tokens");
            }
        }

        return transaction(connection -> {
            for (CardRecord card : batch) {
                if (findCard(connection, card.cardToken(), false)
                        .isPresent()) {
                    throw new StorageException(
                            "Crown card token collision");
                }
                insertCard(connection, card);
            }
            insertAudit(connection, audit);
            return batch;
        });
    }

    @Override
    public Optional<CardRecord> findCard(String cardToken) {
        CardRecord.requireToken(cardToken);
        return query(connection ->
                findCard(connection, cardToken, false));
    }

    @Override
    public CardRedemptionResult redeemCard(
            String cardToken,
            UUID playerId,
            OwnedTitleRecord title,
            Instant now
    ) {
        return redeemCard(
                cardToken, playerId, title, null, now);
    }

    @Override
    public CardRedemptionResult redeemCardWithAudit(
            String cardToken,
            UUID playerId,
            OwnedTitleRecord title,
            AuditRecord audit,
            Instant now
    ) {
        Objects.requireNonNull(audit, "audit");
        if (audit.persisted()
                || !Objects.equals(audit.playerId(), playerId)
                || !Objects.equals(
                audit.targetId(), title.entryId().toString())
                || !audit.createdAt().equals(now)) {
            throw new IllegalArgumentException(
                    "Card redemption audit does not match the grant");
        }
        return redeemCard(
                cardToken, playerId, title, audit, now);
    }

    private CardRedemptionResult redeemCard(
            String cardToken,
            UUID playerId,
            OwnedTitleRecord title,
            AuditRecord audit,
            Instant now
    ) {
        CardRecord.requireToken(cardToken);
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(now, "now");

        return transaction(connection -> {
            Optional<CardRecord> found =
                    findCard(connection, cardToken, true);
            if (found.isEmpty()) {
                return CardRedemptionResult.notFound();
            }
            CardRecord card = found.orElseThrow();
            if (card.redeemed()) {
                return CardRedemptionResult.alreadyRedeemed(card);
            }
            validateCardGrant(card, playerId, title, now);

            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + tables.cards()
                            + " SET redeemed_by = ?, redeemed_at = ?"
                            + " WHERE card_token = ?"
                            + " AND redeemed_by IS NULL"
                            + " AND redeemed_at IS NULL")) {
                update.setString(1, playerId.toString());
                update.setLong(2, epoch(now));
                update.setString(3, cardToken);
                if (update.executeUpdate() != 1) {
                    CardRecord current =
                            findCard(connection, cardToken, true)
                                    .orElseThrow(() ->
                                            new StorageException(
                                                    "Card disappeared"
                                                            + " during"
                                                            + " redemption"));
                    return CardRedemptionResult.alreadyRedeemed(
                            current);
                }
            }

            insertOwnedTitle(connection, title);
            if (audit != null) {
                insertAudit(connection, audit);
            }
            CardRecord redeemed = card.redeemedBy(playerId, now);
            return CardRedemptionResult.redeemed(redeemed, title);
        });
    }

    @Override
    public AuditRecord appendAudit(AuditRecord audit) {
        Objects.requireNonNull(audit, "audit");
        if (audit.persisted()) {
            throw new IllegalArgumentException(
                    "A new audit record cannot already have an ID");
        }
        return transaction(connection ->
                insertAudit(connection, audit));
    }

    @Override
    public List<AuditRecord> findAuditByTarget(
            String targetId,
            int limit
    ) {
        requireText(targetId, "targetId", 128);
        requireLimit(limit);
        return query(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement(
                                 "SELECT * FROM " + tables.audit()
                                         + " WHERE target_id = ?"
                                         + " ORDER BY audit_id DESC"
                                         + " LIMIT ?")) {
                statement.setString(1, targetId);
                statement.setInt(2, limit);
                try (ResultSet result = statement.executeQuery()) {
                    var records = new ArrayList<AuditRecord>();
                    while (result.next()) {
                        records.add(readAudit(result));
                    }
                    return List.copyOf(records);
                }
            }
        });
    }

    @Override
    public StorageSummary summarize() {
        return query(connection -> new StorageSummary(
                JdbcSchema.readVersion(connection, tables),
                countRows(connection, tables.players()),
                countRows(connection, tables.ownedTitles()),
                countRows(connection, tables.purchaseOrders()),
                countRows(connection, tables.titleCoinLedger()),
                sumColumn(connection, tables.players(),
                        "title_coin_balance"),
                countRows(connection, tables.saleCounters()),
                countRows(connection, tables.cards()),
                countRows(connection, tables.audit())));
    }

    @Override
    public void close() {
        if (closeConnections) {
            connections.close();
        }
    }

    private Connection open() throws SQLException {
        Connection connection = connections.open();
        boolean configured = false;
        try {
            dialect.configure(connection);
            configured = true;
            return connection;
        } finally {
            if (!configured) {
                connection.close();
            }
        }
    }

    private <T> T query(SqlOperation<T> operation) {
        try (Connection connection = open()) {
            return operation.execute(connection);
        } catch (SQLException exception) {
            throw failure("Crown database query failed", exception);
        }
    }

    private <T> T transaction(SqlOperation<T> operation) {
        try (Connection connection = open()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = operation.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                if (exception instanceof StorageException storage) {
                    throw storage;
                }
                if (exception instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw failure(
                        "Crown database transaction failed",
                        (SQLException) exception);
            } finally {
                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (SQLException ignored) {
                    // 连接即将关闭，原始异常优先。
                }
            }
        } catch (SQLException exception) {
            throw failure("Could not open Crown transaction", exception);
        }
    }

    private Optional<PlayerRecord> findPlayer(
            Connection connection,
            UUID playerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM " + tables.players()
                        + " WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(readPlayer(result))
                        : Optional.empty();
            }
        }
    }

    private Optional<OwnedTitleRecord> findOwnedTitle(
            Connection connection,
            UUID entryId
    ) throws SQLException {
        return findOwnedTitle(connection, entryId, false);
    }

    private Optional<OwnedTitleRecord> findOwnedTitle(
            Connection connection,
            UUID entryId,
            boolean lock
    ) throws SQLException {
        String suffix = lock && dialect == JdbcDialect.MYSQL
                ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM " + tables.ownedTitles()
                        + " WHERE entry_id = ?" + suffix)) {
            statement.setString(1, entryId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(readOwnedTitle(result))
                        : Optional.empty();
            }
        }
    }

    private Optional<OwnedTitleRecord> findOwnedByOrder(
            Connection connection,
            UUID orderId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM " + tables.ownedTitles()
                        + " WHERE purchase_order_id = ?")) {
            statement.setString(1, orderId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(readOwnedTitle(result))
                        : Optional.empty();
            }
        }
    }

    private Optional<PurchaseOrderRecord> findOrder(
            Connection connection,
            UUID orderId
    ) throws SQLException {
        return findOrder(connection, orderId, false);
    }

    private Optional<PurchaseOrderRecord> findOrder(
            Connection connection,
            UUID orderId,
            boolean lock
    ) throws SQLException {
        String suffix = lock && dialect == JdbcDialect.MYSQL
                ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM " + tables.purchaseOrders()
                        + " WHERE order_id = ?" + suffix)) {
            statement.setString(1, orderId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(readOrder(result))
                        : Optional.empty();
            }
        }
    }

    private void insertOrder(
            Connection connection,
            PurchaseOrderRecord order
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + tables.purchaseOrders() + "("
                        + "order_id, mint_transaction_id, player_uuid,"
                        + " product_type, definition_id, payment_type,"
                        + " currency_id, amount_minor,"
                        + " title_snapshot_json, state, entry_id,"
                        + " failure_code, inventory_reserved,"
                        + " created_at, updated_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            bindOrder(insert, order);
            insert.executeUpdate();
        }
    }

    private void lockPlayerForPurchase(
            Connection connection,
            UUID playerId
    ) throws SQLException {
        String suffix = dialect == JdbcDialect.MYSQL
                ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid FROM " + tables.players()
                        + " WHERE player_uuid = ?" + suffix)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new StorageException(
                            "Purchase player does not exist");
                }
            }
        }
    }

    private long countPlayerPurchases(
            Connection connection,
            UUID playerId,
            DefinitionId definitionId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + tables.purchaseOrders()
                        + " WHERE player_uuid = ?"
                        + " AND definition_id = ?"
                        + " AND state IN ('PREPARED',"
                        + " 'PAYMENT_PENDING',"
                        + " 'PAYMENT_COMMITTED', 'GRANTED')")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, definitionId.value());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException(
                            "No purchase count result");
                }
                return result.getLong(1);
            }
        }
    }

    private SaleCounterRecord ensureAndLockSaleCounter(
            Connection connection,
            DefinitionId definitionId
    ) throws SQLException {
        String sql = dialect == JdbcDialect.SQLITE
                ? "INSERT OR IGNORE INTO " + tables.saleCounters()
                + "(definition_id, sold_count, reserved_count,"
                + " revision) VALUES (?, 0, 0, 0)"
                : "INSERT INTO " + tables.saleCounters()
                + "(definition_id, sold_count, reserved_count,"
                + " revision) VALUES (?, 0, 0, 0)"
                + " ON DUPLICATE KEY UPDATE revision = revision";
        try (PreparedStatement insert =
                     connection.prepareStatement(sql)) {
            insert.setString(1, definitionId.value());
            insert.executeUpdate();
        }
        return findSaleCounter(connection, definitionId, true)
                .orElseThrow(() -> new StorageException(
                        "Could not initialize sale counter"));
    }

    private Optional<SaleCounterRecord> findSaleCounter(
            Connection connection,
            DefinitionId definitionId,
            boolean lock
    ) throws SQLException {
        String suffix = lock && dialect == JdbcDialect.MYSQL
                ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM " + tables.saleCounters()
                        + " WHERE definition_id = ?" + suffix)) {
            statement.setString(1, definitionId.value());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(readSaleCounter(result))
                        : Optional.empty();
            }
        }
    }

    private void releaseReservation(
            Connection connection,
            PurchaseOrderRecord order
    ) throws SQLException {
        DefinitionId definition = order.definition()
                .orElseThrow(() -> new StorageException(
                        "Reserved order has no definition"));
        try (PreparedStatement release = connection.prepareStatement(
                "UPDATE " + tables.saleCounters()
                        + " SET reserved_count = reserved_count - 1,"
                        + " revision = revision + 1"
                        + " WHERE definition_id = ?"
                        + " AND reserved_count > 0")) {
            release.setString(1, definition.value());
            if (release.executeUpdate() != 1) {
                throw new StorageException(
                        "Could not release sale reservation");
            }
        }
    }

    private void recordCompletedSale(
            Connection connection,
            PurchaseOrderRecord order
    ) throws SQLException {
        DefinitionId definition = order.definition().orElse(null);
        if (definition == null) {
            if (order.inventoryReserved()) {
                throw new StorageException(
                        "Reserved order has no definition");
            }
            return;
        }

        if (order.inventoryReserved()) {
            try (PreparedStatement finalize =
                         connection.prepareStatement(
                                 "UPDATE " + tables.saleCounters()
                                         + " SET sold_count"
                                         + " = sold_count + 1,"
                                         + " reserved_count"
                                         + " = reserved_count - 1,"
                                         + " revision = revision + 1"
                                         + " WHERE definition_id = ?"
                                         + " AND reserved_count > 0"
                                         + " AND sold_count < ?")) {
                finalize.setString(1, definition.value());
                finalize.setLong(2, Long.MAX_VALUE);
                if (finalize.executeUpdate() != 1) {
                    throw new StorageException(
                            "Could not finalize sale reservation");
                }
            }
            return;
        }

        ensureAndLockSaleCounter(connection, definition);
        try (PreparedStatement sold = connection.prepareStatement(
                "UPDATE " + tables.saleCounters()
                        + " SET sold_count = sold_count + 1,"
                        + " revision = revision + 1"
                        + " WHERE definition_id = ?"
                        + " AND sold_count < ?")) {
            sold.setString(1, definition.value());
            sold.setLong(2, Long.MAX_VALUE);
            if (sold.executeUpdate() != 1) {
                throw new StorageException(
                        "Could not record completed sale");
            }
        }
    }

    private Optional<CardRecord> findCard(
            Connection connection,
            String cardToken,
            boolean lock
    ) throws SQLException {
        String suffix = lock && dialect == JdbcDialect.MYSQL
                ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM " + tables.cards()
                        + " WHERE card_token = ?" + suffix)) {
            statement.setString(1, cardToken);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(readCard(result))
                        : Optional.empty();
            }
        }
    }

    private void insertCard(
            Connection connection,
            CardRecord card
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + tables.cards() + "("
                        + "card_token, definition_id, duration_type,"
                        + " duration_days, issued_by, issued_at,"
                        + " redeemed_by, redeemed_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)")) {
            insert.setString(1, card.cardToken());
            insert.setString(2, card.definitionId().value());
            insert.setString(3, card.duration().type().name());
            insert.setInt(4, card.duration().days());
            insert.setString(5, card.issuedBy());
            insert.setLong(6, epoch(card.issuedAt()));
            insert.executeUpdate();
        }
    }

    private void insertOwnedTitle(
            Connection connection,
            OwnedTitleRecord title
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + tables.ownedTitles() + "("
                        + "entry_id, player_uuid, definition_id, kind,"
                        + " title_text, title_prefix, title_suffix, source,"
                        + " acquired_at, expires_at, purchase_order_id,"
                        + " status, deleted_at, deleted_by"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, title.entryId().toString());
            insert.setString(2, title.playerId().toString());
            setNullableString(insert, 3,
                    title.definition().map(DefinitionId::value)
                            .orElse(null));
            insert.setString(4, title.kind().name());
            insert.setString(5, title.titleText());
            insert.setString(6, title.titlePrefix());
            insert.setString(7, title.titleSuffix());
            insert.setString(8, title.source());
            insert.setLong(9, epoch(title.acquiredAt()));
            setNullableInstant(insert, 10, title.expiresAt());
            setNullableUuid(insert, 11, title.purchaseOrderId());
            insert.setString(12, title.status().name());
            setNullableInstant(insert, 13, title.deletedAt());
            setNullableString(insert, 14, title.deletedBy());
            insert.executeUpdate();
        }
    }

    private InternalPaymentResult commitFreePayment(
            Connection connection,
            PurchaseOrderRecord order,
            Instant now
    ) throws SQLException {
        if (order.amountMinor() != 0) {
            throw new StorageException(
                    "Free order has a non-zero amount");
        }
        if (!markPaymentCommitted(
                connection, order.orderId(), now)) {
            return InternalPaymentResult.of(
                    InternalPaymentStatus.INVALID_STATE);
        }
        return InternalPaymentResult.committedFree();
    }

    private InternalPaymentResult commitTitleCoinPayment(
            Connection connection,
            PurchaseOrderRecord order,
            String actor,
            String reason,
            Instant now
    ) throws SQLException {
        long amount = order.amountMinor();
        if (amount <= 0) {
            throw new StorageException(
                    "Title coin order has an invalid amount");
        }

        long before = lockBalance(
                connection, order.playerId());
        if (before < amount) {
            failInternalPayment(
                    connection,
                    order,
                    "insufficient_title_coins",
                    now);
            return InternalPaymentResult.of(
                    InternalPaymentStatus.INSUFFICIENT_FUNDS);
        }
        long after = before - amount;

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + tables.players()
                        + " SET title_coin_balance = ?, updated_at = ?"
                        + " WHERE player_uuid = ?"
                        + " AND title_coin_balance = ?")) {
            update.setLong(1, after);
            update.setLong(2, epoch(now));
            update.setString(3, order.playerId().toString());
            update.setLong(4, before);
            if (update.executeUpdate() != 1) {
                throw new StorageException(
                        "Concurrent title coin purchase update");
            }
        }

        TitleCoinLedgerRecord ledger = insertLedger(
                connection,
                order.playerId(),
                -amount,
                before,
                after,
                actor,
                reason,
                order.orderId(),
                now);
        if (!markPaymentCommitted(
                connection, order.orderId(), now)) {
            throw new StorageException(
                    "Could not commit title coin order");
        }
        return InternalPaymentResult.committed(ledger);
    }

    private void failInternalPayment(
            Connection connection,
            PurchaseOrderRecord order,
            String failureCode,
            Instant now
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + tables.purchaseOrders()
                        + " SET state = 'FAILED', failure_code = ?,"
                        + " inventory_reserved = 0, updated_at = ?"
                        + " WHERE order_id = ?"
                        + " AND state = 'PAYMENT_PENDING'")) {
            update.setString(1, failureCode);
            update.setLong(2, epoch(now));
            update.setString(3, order.orderId().toString());
            if (update.executeUpdate() != 1) {
                throw new StorageException(
                        "Could not fail internal payment order");
            }
        }
        if (order.inventoryReserved()) {
            releaseReservation(connection, order);
        }
    }

    private boolean markPaymentCommitted(
            Connection connection,
            UUID orderId,
            Instant now
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + tables.purchaseOrders()
                        + " SET state = 'PAYMENT_COMMITTED',"
                        + " failure_code = NULL, updated_at = ?"
                        + " WHERE order_id = ?"
                        + " AND state = 'PAYMENT_PENDING'")) {
            update.setLong(1, epoch(now));
            update.setString(2, orderId.toString());
            return update.executeUpdate() == 1;
        }
    }

    private AuditRecord insertAudit(
            Connection connection,
            AuditRecord audit
    ) throws SQLException {
        long auditId;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + tables.audit() + "("
                        + "actor, action, player_uuid, target_id,"
                        + " details_json, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, audit.actor());
            insert.setString(2, audit.action());
            setNullableUuid(insert, 3, audit.playerId());
            setNullableString(insert, 4, audit.targetId());
            insert.setString(5, audit.detailsJson());
            insert.setLong(6, epoch(audit.createdAt()));
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException(
                            "No generated Crown audit ID");
                }
                auditId = keys.getLong(1);
            }
        }
        return new AuditRecord(
                auditId,
                audit.actor(),
                audit.action(),
                audit.playerId(),
                audit.targetId(),
                audit.detailsJson(),
                audit.createdAt());
    }

    private TitleCoinLedgerRecord insertLedger(
            Connection connection,
            UUID playerId,
            long delta,
            long before,
            long after,
            String actor,
            String reason,
            UUID orderId,
            Instant now
    ) throws SQLException {
        long ledgerId;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + tables.titleCoinLedger() + "("
                        + "player_uuid, delta, balance_before,"
                        + " balance_after, actor, reason, order_id,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, playerId.toString());
            insert.setLong(2, delta);
            insert.setLong(3, before);
            insert.setLong(4, after);
            insert.setString(5, actor);
            insert.setString(6, reason);
            setNullableUuid(insert, 7, orderId);
            insert.setLong(8, epoch(now));
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException(
                            "No generated title coin ledger ID");
                }
                ledgerId = keys.getLong(1);
            }
        }
        return new TitleCoinLedgerRecord(
                ledgerId,
                playerId,
                delta,
                before,
                after,
                actor,
                reason,
                orderId,
                now);
    }

    private long lockBalance(
            Connection connection,
            UUID playerId
    ) throws SQLException {
        String suffix = dialect == JdbcDialect.MYSQL
                ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT title_coin_balance FROM " + tables.players()
                        + " WHERE player_uuid = ?" + suffix)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new StorageException(
                            "Player does not exist");
                }
                return result.getLong(1);
            }
        }
    }

    private static PlayerRecord readPlayer(ResultSet result)
            throws SQLException {
        SelectionType type = SelectionType.valueOf(
                result.getString("selection_type"));
        String selected = result.getString("selected_entry_id");
        TitleSelection selection = switch (type) {
            case DEFAULT -> TitleSelection.defaultTitle();
            case NONE -> TitleSelection.none();
            case OWNED -> TitleSelection.owned(UUID.fromString(selected));
        };
        return new PlayerRecord(
                UUID.fromString(result.getString("player_uuid")),
                result.getString("last_known_name"),
                selection,
                result.getLong("title_coin_balance"),
                instant(result.getLong("created_at")),
                instant(result.getLong("updated_at")));
    }

    private static OwnedTitleRecord readOwnedTitle(ResultSet result)
            throws SQLException {
        return new OwnedTitleRecord(
                UUID.fromString(result.getString("entry_id")),
                UUID.fromString(result.getString("player_uuid")),
                nullableDefinition(result.getString("definition_id")),
                OwnedTitleKind.valueOf(result.getString("kind")),
                result.getString("title_text"),
                result.getString("title_prefix"),
                result.getString("title_suffix"),
                result.getString("source"),
                instant(result.getLong("acquired_at")),
                nullableInstant(result, "expires_at"),
                nullableUuid(result.getString("purchase_order_id")),
                OwnedTitleStatus.valueOf(result.getString("status")),
                nullableInstant(result, "deleted_at"),
                result.getString("deleted_by"));
    }

    private static PurchaseOrderRecord readOrder(ResultSet result)
            throws SQLException {
        return new PurchaseOrderRecord(
                UUID.fromString(result.getString("order_id")),
                nullableUuid(result.getString("mint_transaction_id")),
                UUID.fromString(result.getString("player_uuid")),
                ProductType.valueOf(result.getString("product_type")),
                nullableDefinition(result.getString("definition_id")),
                PaymentType.valueOf(result.getString("payment_type")),
                nullableNamespacedId(result.getString("currency_id")),
                result.getLong("amount_minor"),
                result.getString("title_snapshot_json"),
                PurchaseOrderState.valueOf(result.getString("state")),
                nullableUuid(result.getString("entry_id")),
                result.getString("failure_code"),
                result.getInt("inventory_reserved") != 0,
                instant(result.getLong("created_at")),
                instant(result.getLong("updated_at")));
    }

    private static TitleCoinLedgerRecord readLedger(ResultSet result)
            throws SQLException {
        return new TitleCoinLedgerRecord(
                result.getLong("ledger_id"),
                UUID.fromString(result.getString("player_uuid")),
                result.getLong("delta"),
                result.getLong("balance_before"),
                result.getLong("balance_after"),
                result.getString("actor"),
                result.getString("reason"),
                nullableUuid(result.getString("order_id")),
                instant(result.getLong("created_at")));
    }

    private static SaleCounterRecord readSaleCounter(
            ResultSet result
    ) throws SQLException {
        return new SaleCounterRecord(
                DefinitionId.of(result.getString("definition_id")),
                result.getLong("sold_count"),
                result.getLong("reserved_count"),
                result.getLong("revision"));
    }

    private static CardRecord readCard(ResultSet result)
            throws SQLException {
        DurationType type = DurationType.valueOf(
                result.getString("duration_type"));
        DurationPolicy duration = new DurationPolicy(
                type, result.getInt("duration_days"));
        return new CardRecord(
                result.getString("card_token"),
                DefinitionId.of(result.getString("definition_id")),
                duration,
                result.getString("issued_by"),
                instant(result.getLong("issued_at")),
                nullableUuid(result.getString("redeemed_by")),
                nullableInstant(result, "redeemed_at"));
    }

    private static AuditRecord readAudit(ResultSet result)
            throws SQLException {
        return new AuditRecord(
                result.getLong("audit_id"),
                result.getString("actor"),
                result.getString("action"),
                nullableUuid(result.getString("player_uuid")),
                result.getString("target_id"),
                result.getString("details_json"),
                instant(result.getLong("created_at")));
    }

    private static void bindOrder(
            PreparedStatement statement,
            PurchaseOrderRecord order
    ) throws SQLException {
        statement.setString(1, order.orderId().toString());
        setNullableUuid(statement, 2, order.mintTransactionId());
        statement.setString(3, order.playerId().toString());
        statement.setString(4, order.productType().name());
        setNullableString(statement, 5,
                order.definition().map(DefinitionId::value).orElse(null));
        statement.setString(6, order.paymentType().name());
        setNullableString(statement, 7,
                order.currency().map(NamespacedId::serialized)
                        .orElse(null));
        statement.setLong(8, order.amountMinor());
        statement.setString(9, order.titleSnapshotJson());
        statement.setString(10, order.state().name());
        setNullableUuid(statement, 11, order.entryId());
        setNullableString(statement, 12, order.failureCode());
        statement.setInt(13, order.inventoryReserved() ? 1 : 0);
        statement.setLong(14, epoch(order.createdAt()));
        statement.setLong(15, epoch(order.updatedAt()));
    }

    private static void bindSelection(
            PreparedStatement statement,
            int typeIndex,
            int entryIndex,
            TitleSelection selection
    ) throws SQLException {
        statement.setString(typeIndex, selection.type().name());
        setNullableUuid(statement, entryIndex, selection.entryId());
    }

    private static void setNullableUuid(
            PreparedStatement statement,
            int index,
            UUID value
    ) throws SQLException {
        setNullableString(statement, index,
                value == null ? null : value.toString());
    }

    private static void setNullableString(
            PreparedStatement statement,
            int index,
            String value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableInstant(
            PreparedStatement statement,
            int index,
            Instant value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, epoch(value));
        }
    }

    private static Instant nullableInstant(
            ResultSet result,
            String column
    ) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : instant(value);
    }

    private static UUID nullableUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static DefinitionId nullableDefinition(String value) {
        return value == null ? null : DefinitionId.of(value);
    }

    private static NamespacedId nullableNamespacedId(String value) {
        return value == null ? null : NamespacedId.parse(value);
    }

    private static long epoch(Instant value) {
        return value.toEpochMilli();
    }

    private static Instant instant(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis);
    }

    private static void validateCardGrant(
            CardRecord card,
            UUID playerId,
            OwnedTitleRecord title,
            Instant now
    ) {
        if (!title.playerId().equals(playerId)
                || title.kind() != OwnedTitleKind.CARD
                || !Objects.equals(
                title.definitionId(), card.definitionId())
                || title.purchaseOrderId() != null
                || title.status() != OwnedTitleStatus.ACTIVE
                || !title.acquiredAt().equals(now)
                || !Objects.equals(
                title.expiresAt(),
                card.duration().expiresAt(now).orElse(null))) {
            throw new IllegalArgumentException(
                    "Owned title does not match Crown card redemption");
        }
    }

    private static long countRows(
            Connection connection,
            String table
    ) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM " + table)) {
            if (!result.next()) {
                throw new SQLException("No table count result");
            }
            return result.getLong(1);
        }
    }

    private static long sumColumn(
            Connection connection,
            String table,
            String column
    ) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COALESCE(SUM(" + column + "), 0)"
                             + " FROM " + table)) {
            if (!result.next()) {
                throw new SQLException("No column sum result");
            }
            long value = result.getLong(1);
            if (result.wasNull()) {
                return 0;
            }
            return value;
        }
    }

    private static void requireGrantAudit(
            OwnedTitleRecord title,
            AuditRecord audit
    ) {
        Objects.requireNonNull(audit, "audit");
        if (audit.persisted()
                || !Objects.equals(
                audit.playerId(), title.playerId())
                || !Objects.equals(
                audit.targetId(), title.entryId().toString())
                || !audit.createdAt().equals(title.acquiredAt())) {
            throw new IllegalArgumentException(
                    "Owned title audit does not match the grant");
        }
    }

    private static void requireDurationAudit(
            UUID playerId,
            UUID entryId,
            AuditRecord audit,
            Instant now
    ) {
        Objects.requireNonNull(audit, "audit");
        if (audit.persisted()
                || !Objects.equals(audit.playerId(), playerId)
                || !Objects.equals(
                audit.targetId(), entryId.toString())
                || !audit.createdAt().equals(now)) {
            throw new IllegalArgumentException(
                    "Duration audit does not match the owned title");
        }
    }

    private static void requireNewCard(CardRecord card) {
        Objects.requireNonNull(card, "card");
        if (card.redeemed()) {
            throw new IllegalArgumentException(
                    "A new Crown card cannot already be redeemed");
        }
    }

    private static void requireSaleLimits(
            long globalStock,
            int perPlayerLimit
    ) {
        if (globalStock < -1
                || perPlayerLimit == 0
                || perPlayerLimit < -1) {
            throw new IllegalArgumentException(
                    "Invalid sale stock or per-player limit");
        }
    }

    private static void requireName(String value) {
        requireText(value, "currentName", 64);
    }

    private static void requireText(
            String value,
            String name,
            int maximumLength
    ) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_QUERY_LIMIT) {
            throw new IllegalArgumentException(
                    "Query limit must be between 1 and "
                            + MAX_QUERY_LIMIT);
        }
    }

    private static StorageException failure(
            String message,
            SQLException exception
    ) {
        return new StorageException(message, exception);
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T execute(Connection connection) throws SQLException;
    }
}