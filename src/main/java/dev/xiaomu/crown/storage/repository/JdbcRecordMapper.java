package dev.xiaomu.crown.storage.repository;

import dev.xiaomu.crown.domain.catalog.DefinitionId;
import dev.xiaomu.crown.domain.catalog.DurationPolicy;
import dev.xiaomu.crown.domain.catalog.DurationType;
import dev.xiaomu.crown.domain.catalog.PaymentType;
import dev.xiaomu.crown.domain.order.PurchaseOrderState;
import dev.xiaomu.crown.domain.player.SelectionType;
import dev.xiaomu.crown.domain.player.TitleSelection;
import dev.xiaomu.crown.storage.model.AuditRecord;
import dev.xiaomu.crown.storage.model.CardRecord;
import dev.xiaomu.crown.storage.model.OwnedTitleKind;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import dev.xiaomu.crown.storage.model.OwnedTitleStatus;
import dev.xiaomu.crown.storage.model.PlayerRecord;
import dev.xiaomu.crown.storage.model.ProductType;
import dev.xiaomu.crown.storage.model.PurchaseOrderRecord;
import dev.xiaomu.crown.storage.model.SaleCounterRecord;
import dev.xiaomu.crown.storage.model.TitleCoinLedgerRecord;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

/** Maps SQL rows and statement parameters without running queries or transactions. */
final class JdbcRecordMapper {
    private JdbcRecordMapper() {}

    static PlayerRecord readPlayer(ResultSet result) throws SQLException {
        SelectionType type = SelectionType.valueOf(result.getString("selection_type"));
        String selected = result.getString("selected_entry_id");
        TitleSelection selection =
                switch (type) {
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

    static OwnedTitleRecord readOwnedTitle(ResultSet result) throws SQLException {
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

    static PurchaseOrderRecord readOrder(ResultSet result) throws SQLException {
        return new PurchaseOrderRecord(
                UUID.fromString(result.getString("order_id")),
                UUID.fromString(result.getString("player_uuid")),
                ProductType.valueOf(result.getString("product_type")),
                nullableDefinition(result.getString("definition_id")),
                PaymentType.valueOf(result.getString("payment_type")),
                result.getLong("amount_minor"),
                result.getString("title_snapshot_json"),
                PurchaseOrderState.valueOf(result.getString("state")),
                nullableUuid(result.getString("entry_id")),
                result.getString("failure_code"),
                result.getInt("inventory_reserved") != 0,
                instant(result.getLong("created_at")),
                instant(result.getLong("updated_at")));
    }

    static TitleCoinLedgerRecord readLedger(ResultSet result) throws SQLException {
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

    static SaleCounterRecord readSaleCounter(ResultSet result) throws SQLException {
        return new SaleCounterRecord(
                DefinitionId.of(result.getString("definition_id")),
                result.getLong("sold_count"),
                result.getLong("reserved_count"),
                result.getLong("revision"));
    }

    static CardRecord readCard(ResultSet result) throws SQLException {
        DurationType type = DurationType.valueOf(result.getString("duration_type"));
        DurationPolicy duration = new DurationPolicy(type, result.getInt("duration_days"));
        return new CardRecord(
                result.getString("card_token"),
                DefinitionId.of(result.getString("definition_id")),
                duration,
                result.getString("issued_by"),
                instant(result.getLong("issued_at")),
                nullableUuid(result.getString("redeemed_by")),
                nullableInstant(result, "redeemed_at"));
    }

    static AuditRecord readAudit(ResultSet result) throws SQLException {
        return new AuditRecord(
                result.getLong("audit_id"),
                result.getString("actor"),
                result.getString("action"),
                nullableUuid(result.getString("player_uuid")),
                result.getString("target_id"),
                result.getString("details_json"),
                instant(result.getLong("created_at")));
    }

    static void bindOrder(PreparedStatement statement, PurchaseOrderRecord order)
            throws SQLException {
        statement.setString(1, order.orderId().toString());
        statement.setString(2, order.playerId().toString());
        statement.setString(3, order.productType().name());
        setNullableString(statement, 4, order.definition().map(DefinitionId::value).orElse(null));
        statement.setString(5, order.paymentType().name());
        statement.setLong(6, order.amountMinor());
        statement.setString(7, order.titleSnapshotJson());
        statement.setString(8, order.state().name());
        setNullableUuid(statement, 9, order.entryId());
        setNullableString(statement, 10, order.failureCode());
        statement.setInt(11, order.inventoryReserved() ? 1 : 0);
        statement.setLong(12, epoch(order.createdAt()));
        statement.setLong(13, epoch(order.updatedAt()));
    }

    static void bindSelection(
            PreparedStatement statement, int typeIndex, int entryIndex, TitleSelection selection)
            throws SQLException {
        statement.setString(typeIndex, selection.type().name());
        setNullableUuid(statement, entryIndex, selection.entryId());
    }

    static void setNullableUuid(PreparedStatement statement, int index, UUID value)
            throws SQLException {
        setNullableString(statement, index, value == null ? null : value.toString());
    }

    static void setNullableString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    static void setNullableInstant(PreparedStatement statement, int index, Instant value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, epoch(value));
        }
    }

    static Instant nullableInstant(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : instant(value);
    }

    static UUID nullableUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    static DefinitionId nullableDefinition(String value) {
        return value == null ? null : DefinitionId.of(value);
    }

    static long epoch(Instant value) {
        return value.toEpochMilli();
    }

    static Instant instant(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis);
    }
}
