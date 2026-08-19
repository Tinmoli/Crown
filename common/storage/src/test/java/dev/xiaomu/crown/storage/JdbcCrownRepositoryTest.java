package dev.xiaomu.crown.storage;

import dev.xiaomu.crown.domain.catalog.DefinitionId;
import dev.xiaomu.crown.domain.catalog.NamespacedId;
import dev.xiaomu.crown.domain.catalog.PaymentType;
import dev.xiaomu.crown.domain.order.PurchaseOrderState;
import dev.xiaomu.crown.domain.player.TitleSelection;
import dev.xiaomu.crown.storage.jdbc.JdbcDialect;
import dev.xiaomu.crown.storage.jdbc.SqliteConnectionFactory;
import dev.xiaomu.crown.storage.jdbc.TableNames;
import dev.xiaomu.crown.storage.model.AuditRecord;
import dev.xiaomu.crown.storage.model.CoinAdjustmentResult;
import dev.xiaomu.crown.storage.model.OwnedTitleDurationStatus;
import dev.xiaomu.crown.storage.model.OwnedTitleKind;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import dev.xiaomu.crown.storage.model.OwnedTitleStatus;
import dev.xiaomu.crown.storage.model.PlayerRecord;
import dev.xiaomu.crown.storage.model.ProductType;
import dev.xiaomu.crown.storage.model.PurchaseOrderRecord;
import dev.xiaomu.crown.storage.model.TitleCoinLedgerRecord;
import dev.xiaomu.crown.storage.repository.JdbcCrownRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcCrownRepositoryTest {
    private static final TableNames TABLES =
            TableNames.withPrefix("crown_");
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-26T01:00:00Z");

    @TempDir
    private Path temporaryDirectory;

    @Test
    void initializesSchemaIdempotentlyAndPersistsPlayerIdentity() {
        UUID defaultPlayerId = UUID.randomUUID();
        UUID nonePlayerId = UUID.randomUUID();
        Instant renamedAt = CREATED_AT.plusSeconds(60);

        try (JdbcCrownRepository repository = repository("players.db")) {
            assertEquals(1, repository.initializeSchema());
            assertEquals(1, repository.initializeSchema());

            PlayerRecord created = repository.ensurePlayer(
                    defaultPlayerId,
                    "FirstName",
                    TitleSelection.defaultTitle(),
                    CREATED_AT);
            assertEquals(TitleSelection.defaultTitle(),
                    created.selection());
            assertEquals(0, created.titleCoinBalance());
            assertEquals(CREATED_AT, created.createdAt());
            assertEquals(CREATED_AT, created.updatedAt());

            PlayerRecord renamed = repository.ensurePlayer(
                    defaultPlayerId,
                    "RenamedPlayer",
                    TitleSelection.none(),
                    renamedAt);
            assertEquals("RenamedPlayer", renamed.lastKnownName());
            assertEquals(TitleSelection.defaultTitle(),
                    renamed.selection());
            assertEquals(CREATED_AT, renamed.createdAt());
            assertEquals(renamedAt, renamed.updatedAt());

            PlayerRecord stored = repository.findPlayer(defaultPlayerId)
                    .orElseThrow();
            assertEquals(renamed, stored);

            PlayerRecord none = repository.ensurePlayer(
                    nonePlayerId,
                    "NoDefault",
                    TitleSelection.none(),
                    CREATED_AT);
            assertEquals(TitleSelection.none(), none.selection());
        }
    }

    @Test
    void selectsOwnedTitleAndSoftDeleteClearsSelectionAtomically() {
        UUID playerId = UUID.randomUUID();
        UUID otherPlayerId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        UUID expiredEntryId = UUID.randomUUID();

        try (JdbcCrownRepository repository =
                     initializedRepository("warehouse.db")) {
            repository.ensurePlayer(
                    playerId, "PlayerOne",
                    TitleSelection.defaultTitle(), CREATED_AT);
            repository.ensurePlayer(
                    otherPlayerId, "PlayerTwo",
                    TitleSelection.none(), CREATED_AT);

            OwnedTitleRecord title = title(
                    entryId, playerId, null, null);
            assertTrue(repository.insertOwnedTitle(title));
            assertFalse(repository.insertOwnedTitle(title));

            assertFalse(repository.setSelection(
                    otherPlayerId,
                    TitleSelection.owned(entryId),
                    CREATED_AT.plusSeconds(1)));
            assertTrue(repository.setSelection(
                    playerId,
                    TitleSelection.owned(entryId),
                    CREATED_AT.plusSeconds(1)));
            assertEquals(
                    TitleSelection.owned(entryId),
                    repository.findPlayer(playerId)
                            .orElseThrow().selection());

            OwnedTitleRecord expired = title(
                    expiredEntryId,
                    playerId,
                    null,
                    CREATED_AT.plusSeconds(30));
            assertTrue(repository.insertOwnedTitle(expired));
            assertFalse(repository.setSelection(
                    playerId,
                    TitleSelection.owned(expiredEntryId),
                    CREATED_AT.plusSeconds(30)));

            Instant deletedAt = CREATED_AT.plusSeconds(90);
            assertTrue(repository.softDeleteOwnedTitle(
                    playerId, entryId, "console", deletedAt));
            assertFalse(repository.softDeleteOwnedTitle(
                    playerId, entryId, "console", deletedAt));

            assertEquals(
                    TitleSelection.none(),
                    repository.findPlayer(playerId)
                            .orElseThrow().selection());
            assertTrue(repository.listOwnedTitles(
                    playerId, false).stream()
                    .noneMatch(record ->
                            record.entryId().equals(entryId)));

            OwnedTitleRecord deleted = repository.findOwnedTitle(entryId)
                    .orElseThrow();
            assertEquals(OwnedTitleStatus.DELETED, deleted.status());
            assertEquals(deletedAt, deleted.deletedAt());
            assertEquals("console", deleted.deletedBy());
            assertTrue(repository.listOwnedTitles(
                    playerId, true).stream()
                    .anyMatch(record ->
                            record.entryId().equals(entryId)));
            assertFalse(repository.setSelection(
                    playerId,
                    TitleSelection.owned(entryId),
                    deletedAt.plusSeconds(1)));
        }
    }

    @Test
    void grantsAndUpdatesOwnedTitleDurationWithAtomicAudit() {
        UUID playerId = UUID.randomUUID();
        UUID otherPlayerId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();

        try (JdbcCrownRepository repository =
                     initializedRepository("admin-owned-title.db")) {
            repository.ensurePlayer(
                    playerId, "Target",
                    TitleSelection.none(), CREATED_AT);
            repository.ensurePlayer(
                    otherPlayerId, "Other",
                    TitleSelection.none(), CREATED_AT);

            OwnedTitleRecord granted = title(
                    entryId, playerId, null,
                    CREATED_AT.plusSeconds(86_400));
            AuditRecord grantAudit = new AuditRecord(
                    0,
                    "console",
                    "admin_grant_title",
                    playerId,
                    entryId.toString(),
                    "{\"kind\":\"CATALOG\"}",
                    CREATED_AT);
            assertTrue(repository.insertOwnedTitleWithAudit(
                    granted, grantAudit));
            assertFalse(repository.insertOwnedTitleWithAudit(
                    granted, grantAudit));
            assertEquals(1, repository.findAuditByTarget(
                    entryId.toString(), 10).size());

            Instant updatedAt = CREATED_AT.plusSeconds(100);
            Instant newExpiry = CREATED_AT.plusSeconds(172_800);
            AuditRecord durationAudit = new AuditRecord(
                    0,
                    "console",
                    "admin_update_title_duration",
                    playerId,
                    entryId.toString(),
                    "{\"expiresAt\":\""
                            + newExpiry + "\"}",
                    updatedAt);
            assertEquals(
                    OwnedTitleDurationStatus.UPDATED,
                    repository.updateOwnedTitleDurationWithAudit(
                            playerId, entryId, newExpiry,
                            durationAudit, updatedAt));
            assertEquals(newExpiry, repository.findOwnedTitle(entryId)
                    .orElseThrow().expiresAt());
            assertEquals(2, repository.findAuditByTarget(
                    entryId.toString(), 10).size());

            int auditCount = repository.findAuditByTarget(
                    entryId.toString(), 10).size();
            assertEquals(
                    OwnedTitleDurationStatus.NOT_OWNED,
                    repository.updateOwnedTitleDurationWithAudit(
                            otherPlayerId, entryId, null,
                            new AuditRecord(
                                    0, "console",
                                    "admin_update_title_duration",
                                    otherPlayerId,
                                    entryId.toString(),
                                    "{}", updatedAt.plusSeconds(1)),
                            updatedAt.plusSeconds(1)));
            UUID missingEntry = UUID.randomUUID();
            assertEquals(
                    OwnedTitleDurationStatus.NOT_FOUND,
                    repository.updateOwnedTitleDurationWithAudit(
                            playerId, missingEntry, null,
                            new AuditRecord(
                                    0, "console",
                                    "admin_update_title_duration",
                                    playerId,
                                    missingEntry.toString(),
                                    "{}", updatedAt.plusSeconds(2)),
                            updatedAt.plusSeconds(2)));
            assertEquals(auditCount, repository.findAuditByTarget(
                    entryId.toString(), 10).size());

            assertTrue(repository.softDeleteOwnedTitle(
                    playerId, entryId, "console",
                    updatedAt.plusSeconds(3)));
            assertEquals(
                    OwnedTitleDurationStatus.DELETED,
                    repository.updateOwnedTitleDurationWithAudit(
                            playerId, entryId, null,
                            new AuditRecord(
                                    0, "console",
                                    "admin_update_title_duration",
                                    playerId,
                                    entryId.toString(),
                                    "{}", updatedAt.plusSeconds(4)),
                            updatedAt.plusSeconds(4)));
            assertEquals(auditCount, repository.findAuditByTarget(
                    entryId.toString(), 10).size());

            assertThrows(IllegalArgumentException.class,
                    () -> repository
                            .updateOwnedTitleDurationWithAudit(
                                    playerId, entryId, null,
                                    new AuditRecord(
                                            0, "console",
                                            "admin_update_title_duration",
                                            playerId,
                                            UUID.randomUUID().toString(),
                                            "{}", updatedAt.plusSeconds(5)),
                                    updatedAt.plusSeconds(5)));
        }
    }

    @Test
    void adjustsCoinsWithImmutableLedgerAndRollsBackFailures() {
        UUID playerId = UUID.randomUUID();

        try (JdbcCrownRepository repository =
                     initializedRepository("coins.db")) {
            repository.ensurePlayer(
                    playerId, "CoinPlayer",
                    TitleSelection.defaultTitle(), CREATED_AT);

            CoinAdjustmentResult credited =
                    repository.adjustTitleCoins(
                            playerId, 100, 1_000,
                            "console", "admin_give",
                            null, CREATED_AT.plusSeconds(1));
            assertEquals(0, credited.balanceBefore());
            assertEquals(100, credited.balanceAfter());
            assertTrue(credited.ledger().ledgerId() > 0);

            CoinAdjustmentResult debited =
                    repository.adjustTitleCoins(
                            playerId, -40, 1_000,
                            "crown:shop", "title_purchase",
                            null, CREATED_AT.plusSeconds(2));
            assertEquals(100, debited.balanceBefore());
            assertEquals(60, debited.balanceAfter());

            List<TitleCoinLedgerRecord> beforeFailures =
                    repository.titleCoinLedger(playerId, 10);
            assertEquals(2, beforeFailures.size());
            assertEquals(-40, beforeFailures.get(0).delta());
            assertEquals(100, beforeFailures.get(1).delta());

            assertThrows(StorageException.class,
                    () -> repository.adjustTitleCoins(
                            playerId, -61, 1_000,
                            "crown:shop", "insufficient",
                            null, CREATED_AT.plusSeconds(3)));
            assertThrows(StorageException.class,
                    () -> repository.adjustTitleCoins(
                            playerId, 941, 1_000,
                            "console", "over_maximum",
                            null, CREATED_AT.plusSeconds(4)));
            assertThrows(StorageException.class,
                    () -> repository.adjustTitleCoins(
                            playerId, Long.MAX_VALUE,
                            Long.MAX_VALUE,
                            "console", "overflow",
                            null, CREATED_AT.plusSeconds(5)));

            PlayerRecord unchanged =
                    repository.findPlayer(playerId).orElseThrow();
            assertEquals(60, unchanged.titleCoinBalance());
            assertEquals(beforeFailures,
                    repository.titleCoinLedger(playerId, 10));

            assertThrows(IllegalArgumentException.class,
                    () -> repository.titleCoinLedger(playerId, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> repository.adjustTitleCoins(
                            playerId, 0, 1_000,
                            "console", "zero",
                            null, CREATED_AT));
        }
    }

    @Test
    void transitionsAndGrantsMintOrderIdempotently() {
        UUID playerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();

        try (JdbcCrownRepository repository =
                     initializedRepository("orders.db")) {
            repository.ensurePlayer(
                    playerId, "Buyer",
                    TitleSelection.defaultTitle(), CREATED_AT);

            PurchaseOrderRecord prepared = mintOrder(
                    orderId, transactionId, playerId,
                    PurchaseOrderState.PREPARED, CREATED_AT);
            assertTrue(repository.createOrder(prepared));
            assertFalse(repository.createOrder(prepared));
            assertEquals(prepared,
                    repository.findOrder(orderId).orElseThrow());
            assertEquals(List.of(prepared),
                    repository.findRecoverableOrders(10));

            Instant pendingAt = CREATED_AT.plusSeconds(1);
            assertTrue(repository.transitionOrder(
                    orderId,
                    PurchaseOrderState.PREPARED,
                    PurchaseOrderState.PAYMENT_PENDING,
                    null,
                    pendingAt));
            assertFalse(repository.transitionOrder(
                    orderId,
                    PurchaseOrderState.PREPARED,
                    PurchaseOrderState.FAILED,
                    "stale_worker",
                    pendingAt.plusMillis(1)));

            Instant committedAt = CREATED_AT.plusSeconds(2);
            assertTrue(repository.transitionOrder(
                    orderId,
                    PurchaseOrderState.PAYMENT_PENDING,
                    PurchaseOrderState.PAYMENT_COMMITTED,
                    null,
                    committedAt));

            OwnedTitleRecord requestedGrant = title(
                    entryId, playerId, orderId, null);
            Instant grantedAt = CREATED_AT.plusSeconds(3);
            OwnedTitleRecord granted = repository.grantCommittedOrder(
                    orderId, requestedGrant, grantedAt);
            assertEquals(requestedGrant, granted);

            PurchaseOrderRecord finalized =
                    repository.findOrder(orderId).orElseThrow();
            assertEquals(PurchaseOrderState.GRANTED, finalized.state());
            assertEquals(entryId,
                    finalized.grantedEntryId().orElseThrow());
            assertFalse(finalized.inventoryReserved());
            assertEquals(grantedAt, finalized.updatedAt());

            OwnedTitleRecord recovered = repository.grantCommittedOrder(
                    orderId,
                    title(UUID.randomUUID(), playerId, orderId, null),
                    grantedAt.plusSeconds(10));
            assertEquals(granted, recovered);
            assertEquals(1,
                    repository.listOwnedTitles(playerId, true).size());
            assertTrue(repository.findRecoverableOrders(10).isEmpty());
        }
    }

    @Test
    void rejectsDuplicateMintTransactionAndInvalidGrantWithoutPartialWrites() {
        UUID playerId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID firstOrderId = UUID.randomUUID();
        UUID secondOrderId = UUID.randomUUID();

        try (JdbcCrownRepository repository =
                     initializedRepository("constraints.db")) {
            repository.ensurePlayer(
                    playerId, "Buyer",
                    TitleSelection.defaultTitle(), CREATED_AT);

            assertTrue(repository.createOrder(mintOrder(
                    firstOrderId, transactionId, playerId,
                    PurchaseOrderState.PREPARED, CREATED_AT)));

            assertThrows(StorageException.class,
                    () -> repository.createOrder(mintOrder(
                            secondOrderId, transactionId, playerId,
                            PurchaseOrderState.PREPARED,
                            CREATED_AT.plusSeconds(1))));
            assertTrue(repository.findOrder(secondOrderId).isEmpty());
            assertNotNull(repository.findOrder(firstOrderId)
                    .orElseThrow());

            UUID uncommittedEntryId = UUID.randomUUID();
            assertThrows(StorageException.class,
                    () -> repository.grantCommittedOrder(
                            firstOrderId,
                            title(uncommittedEntryId, playerId,
                                    firstOrderId, null),
                            CREATED_AT.plusSeconds(2)));
            assertTrue(repository.findOwnedTitle(
                    uncommittedEntryId).isEmpty());

            assertThrows(IllegalArgumentException.class,
                    () -> repository.grantCommittedOrder(
                            firstOrderId,
                            title(UUID.randomUUID(), playerId,
                                    UUID.randomUUID(), null),
                            CREATED_AT.plusSeconds(2)));
        }
    }

    private JdbcCrownRepository initializedRepository(String fileName) {
        JdbcCrownRepository repository = repository(fileName);
        repository.initializeSchema();
        return repository;
    }

    private JdbcCrownRepository repository(String fileName) {
        return new JdbcCrownRepository(
                new SqliteConnectionFactory(
                        temporaryDirectory.resolve(fileName),
                        5_000,
                        true,
                        "NORMAL"),
                JdbcDialect.SQLITE,
                TABLES);
    }

    private static OwnedTitleRecord title(
            UUID entryId,
            UUID playerId,
            UUID orderId,
            Instant expiresAt
    ) {
        return new OwnedTitleRecord(
                entryId,
                playerId,
                DefinitionId.of("vip"),
                OwnedTitleKind.CATALOG,
                "&6VIP",
                "[",
                "]",
                "test",
                CREATED_AT,
                expiresAt,
                orderId,
                OwnedTitleStatus.ACTIVE,
                null,
                null);
    }

    private static PurchaseOrderRecord mintOrder(
            UUID orderId,
            UUID transactionId,
            UUID playerId,
            PurchaseOrderState state,
            Instant now
    ) {
        return new PurchaseOrderRecord(
                orderId,
                transactionId,
                playerId,
                ProductType.CATALOG,
                DefinitionId.of("vip"),
                PaymentType.MINT,
                NamespacedId.parse("mint:coin"),
                250,
                "{\"text\":\"VIP\"}",
                state,
                null,
                null,
                false,
                now,
                now);
    }
}