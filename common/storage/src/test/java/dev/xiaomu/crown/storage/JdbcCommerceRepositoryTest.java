package dev.xiaomu.crown.storage;

import dev.xiaomu.crown.domain.catalog.DefinitionId;
import dev.xiaomu.crown.domain.catalog.DurationPolicy;
import dev.xiaomu.crown.domain.catalog.NamespacedId;
import dev.xiaomu.crown.domain.catalog.PaymentType;
import dev.xiaomu.crown.domain.order.PurchaseOrderState;
import dev.xiaomu.crown.domain.player.TitleSelection;
import dev.xiaomu.crown.storage.jdbc.JdbcDialect;
import dev.xiaomu.crown.storage.jdbc.SqliteConnectionFactory;
import dev.xiaomu.crown.storage.jdbc.TableNames;
import dev.xiaomu.crown.storage.model.AuditRecord;
import dev.xiaomu.crown.storage.model.CardRecord;
import dev.xiaomu.crown.storage.model.CardRedemptionStatus;
import dev.xiaomu.crown.storage.model.OrderPreparationStatus;
import dev.xiaomu.crown.storage.model.OwnedTitleKind;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import dev.xiaomu.crown.storage.model.OwnedTitleStatus;
import dev.xiaomu.crown.storage.model.ProductType;
import dev.xiaomu.crown.storage.model.PurchaseOrderRecord;
import dev.xiaomu.crown.storage.model.SaleCounterRecord;
import dev.xiaomu.crown.storage.model.StorageSummary;
import dev.xiaomu.crown.storage.repository.JdbcCrownRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcCommerceRepositoryTest {
    private static final DefinitionId VIP = DefinitionId.of("vip");
    private static final Instant BASE_TIME =
            Instant.parse("2026-07-26T02:00:00Z");
    private static final TableNames TABLES =
            TableNames.withPrefix("commerce_");

    @TempDir
    private Path temporaryDirectory;

    @Test
    void reservesReleasesAndFinalizesFiniteStock() {
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        UUID firstOrderId = UUID.randomUUID();
        UUID secondOrderId = UUID.randomUUID();

        try (JdbcCrownRepository repository = repository("stock.db")) {
            ensurePlayer(repository, firstPlayer, "FirstBuyer");
            ensurePlayer(repository, secondPlayer, "SecondBuyer");

            PurchaseOrderRecord first = mintOrder(
                    firstOrderId, firstPlayer, true, BASE_TIME);
            assertEquals(
                    OrderPreparationStatus.CREATED,
                    repository.prepareOrder(first, 1, -1));
            assertEquals(
                    OrderPreparationStatus.ORDER_ALREADY_EXISTS,
                    repository.prepareOrder(first, 1, -1));
            assertEquals(
                    new SaleCounterRecord(VIP, 0, 1, 1),
                    repository.findSaleCounter(VIP).orElseThrow());

            PurchaseOrderRecord second = mintOrder(
                    secondOrderId,
                    secondPlayer,
                    true,
                    BASE_TIME.plusSeconds(1));
            assertEquals(
                    OrderPreparationStatus.OUT_OF_STOCK,
                    repository.prepareOrder(second, 1, -1));
            assertTrue(repository.findOrder(secondOrderId).isEmpty());

            assertTrue(repository.transitionOrder(
                    firstOrderId,
                    PurchaseOrderState.PREPARED,
                    PurchaseOrderState.CANCELLED,
                    null,
                    BASE_TIME.plusSeconds(2)));
            assertFalse(repository.transitionOrder(
                    firstOrderId,
                    PurchaseOrderState.PREPARED,
                    PurchaseOrderState.CANCELLED,
                    null,
                    BASE_TIME.plusSeconds(3)));
            assertEquals(
                    new SaleCounterRecord(VIP, 0, 0, 2),
                    repository.findSaleCounter(VIP).orElseThrow());
            assertFalse(repository.findOrder(firstOrderId)
                    .orElseThrow().inventoryReserved());

            assertEquals(
                    OrderPreparationStatus.CREATED,
                    repository.prepareOrder(second, 1, -1));
            transitionToCommitted(
                    repository, secondOrderId,
                    BASE_TIME.plusSeconds(3));

            OwnedTitleRecord title = catalogTitle(
                    UUID.randomUUID(),
                    secondPlayer,
                    secondOrderId,
                    BASE_TIME.plusSeconds(5));
            Instant grantedAt = BASE_TIME.plusSeconds(5);
            AuditRecord purchaseAudit = new AuditRecord(
                    0,
                    "player:" + secondPlayer,
                    "purchase_granted",
                    secondPlayer,
                    secondOrderId.toString(),
                    "{\"paymentType\":\"MINT\"}",
                    grantedAt);
            assertEquals(
                    title,
                    repository.grantCommittedOrderWithAudit(
                            secondOrderId,
                            title,
                            purchaseAudit,
                            grantedAt));
            assertEquals(
                    1,
                    repository.findAuditByTarget(
                            secondOrderId.toString(), 10).size());
            assertEquals(
                    new SaleCounterRecord(VIP, 1, 0, 4),
                    repository.findSaleCounter(VIP).orElseThrow());
            assertEquals(1,
                    repository.countPlayerPurchases(
                            secondPlayer, VIP));

            Instant retriedAt = BASE_TIME.plusSeconds(10);
            assertEquals(
                    title,
                    repository.grantCommittedOrderWithAudit(
                            secondOrderId,
                            catalogTitle(
                                    UUID.randomUUID(),
                                    secondPlayer,
                                    secondOrderId,
                                    retriedAt),
                            new AuditRecord(
                                    0,
                                    "system:recovery",
                                    "purchase_granted",
                                    secondPlayer,
                                    secondOrderId.toString(),
                                    "{\"recovered\":true}",
                                    retriedAt),
                            retriedAt));
            assertEquals(
                    1,
                    repository.findAuditByTarget(
                            secondOrderId.toString(), 10).size());
            assertEquals(
                    new SaleCounterRecord(VIP, 1, 0, 4),
                    repository.findSaleCounter(VIP).orElseThrow());

            PurchaseOrderRecord exhausted = mintOrder(
                    UUID.randomUUID(),
                    firstPlayer,
                    true,
                    BASE_TIME.plusSeconds(11));
            assertEquals(
                    OrderPreparationStatus.OUT_OF_STOCK,
                    repository.prepareOrder(exhausted, 1, -1));
        }
    }

    @Test
    void enforcesPerPlayerLimitAndRollsBackReservationOnConflict() {
        UUID playerId = UUID.randomUUID();

        try (JdbcCrownRepository repository =
                     repository("limits.db")) {
            ensurePlayer(repository, playerId, "LimitedBuyer");

            PurchaseOrderRecord first = mintOrder(
                    UUID.randomUUID(), playerId, false, BASE_TIME);
            PurchaseOrderRecord second = mintOrder(
                    UUID.randomUUID(),
                    playerId,
                    false,
                    BASE_TIME.plusSeconds(1));

            assertEquals(
                    OrderPreparationStatus.CREATED,
                    repository.prepareOrder(first, -1, 1));
            assertEquals(
                    OrderPreparationStatus.PLAYER_LIMIT_REACHED,
                    repository.prepareOrder(second, -1, 1));
            assertTrue(repository.findOrder(
                    second.orderId()).isEmpty());

            assertTrue(repository.transitionOrder(
                    first.orderId(),
                    PurchaseOrderState.PREPARED,
                    PurchaseOrderState.FAILED,
                    "payment_rejected",
                    BASE_TIME.plusSeconds(2)));
            assertEquals(
                    OrderPreparationStatus.CREATED,
                    repository.prepareOrder(second, -1, 1));
            assertEquals(1,
                    repository.countPlayerPurchases(playerId, VIP));

            UUID duplicateTransaction =
                    second.mintTransactionId();
            PurchaseOrderRecord conflicting = new PurchaseOrderRecord(
                    UUID.randomUUID(),
                    duplicateTransaction,
                    playerId,
                    ProductType.CATALOG,
                    VIP,
                    PaymentType.MINT,
                    NamespacedId.parse("mint:coin"),
                    100,
                    "{\"text\":\"VIP\"}",
                    PurchaseOrderState.PREPARED,
                    null,
                    null,
                    true,
                    BASE_TIME.plusSeconds(3),
                    BASE_TIME.plusSeconds(3));

            assertThrows(StorageException.class,
                    () -> repository.prepareOrder(
                            conflicting, 10, -1));
            assertTrue(repository.findOrder(
                    conflicting.orderId()).isEmpty());

            assertTrue(repository.findSaleCounter(VIP).isEmpty());
        }
    }

    @Test
    void redeemsCardExactlyOnceAndRollsBackFailedGrant() {
        UUID playerId = UUID.randomUUID();
        String token = "CrownCardToken_1234567890ABCD";
        String rollbackToken = "CrownCardToken_rollback_123456";
        Instant redeemedAt = BASE_TIME.plusSeconds(30);
        DurationPolicy limited = DurationPolicy.limited(2);

        try (JdbcCrownRepository repository =
                     repository("cards.db")) {
            ensurePlayer(repository, playerId, "CardPlayer");

            CardRecord card = new CardRecord(
                    token,
                    VIP,
                    limited,
                    "console",
                    BASE_TIME,
                    null,
                    null);
            assertTrue(repository.createCard(card));
            assertFalse(repository.createCard(card));
            assertEquals(card,
                    repository.findCard(token).orElseThrow());

            OwnedTitleRecord grant = cardTitle(
                    UUID.randomUUID(),
                    playerId,
                    redeemedAt,
                    redeemedAt.plus(Duration.ofDays(2)));
            var redeemed = repository.redeemCard(
                    token, playerId, grant, redeemedAt);
            assertEquals(CardRedemptionStatus.REDEEMED,
                    redeemed.status());
            assertEquals(grant, redeemed.title().orElseThrow());
            assertEquals(playerId,
                    redeemed.card().orElseThrow()
                            .redeemer().orElseThrow());

            var repeated = repository.redeemCard(
                    token,
                    playerId,
                    cardTitle(
                            UUID.randomUUID(),
                            playerId,
                            redeemedAt.plusSeconds(1),
                            redeemedAt.plusSeconds(1)
                                    .plus(Duration.ofDays(2))),
                    redeemedAt.plusSeconds(1));
            assertEquals(CardRedemptionStatus.ALREADY_REDEEMED,
                    repeated.status());
            assertTrue(repeated.title().isEmpty());
            assertEquals(1,
                    repository.listOwnedTitles(
                            playerId, true).size());

            CardRecord rollbackCard = new CardRecord(
                    rollbackToken,
                    VIP,
                    DurationPolicy.permanent(),
                    "console",
                    BASE_TIME,
                    null,
                    null);
            assertTrue(repository.createCard(rollbackCard));

            UUID collidingEntry = UUID.randomUUID();
            OwnedTitleRecord existing = cardTitle(
                    collidingEntry,
                    playerId,
                    redeemedAt,
                    null);
            assertTrue(repository.insertOwnedTitle(existing));

            assertThrows(StorageException.class,
                    () -> repository.redeemCard(
                            rollbackToken,
                            playerId,
                            cardTitle(
                                    collidingEntry,
                                    playerId,
                                    redeemedAt.plusSeconds(2),
                                    null),
                            redeemedAt.plusSeconds(2)));
            assertFalse(repository.findCard(rollbackToken)
                    .orElseThrow().redeemed());

            assertEquals(
                    CardRedemptionStatus.NOT_FOUND,
                    repository.redeemCard(
                            "MissingCardToken_1234567890AB",
                            playerId,
                            cardTitle(
                                    UUID.randomUUID(),
                                    playerId,
                                    redeemedAt,
                                    null),
                            redeemedAt).status());
        }
    }

    @Test
    void appendsAuditAndReportsStorageSummary() {
        UUID playerId = UUID.randomUUID();
        String target = UUID.randomUUID().toString();

        try (JdbcCrownRepository repository =
                     repository("summary.db")) {
            ensurePlayer(repository, playerId, "SummaryPlayer");
            repository.adjustTitleCoins(
                    playerId,
                    25,
                    1_000,
                    "console",
                    "admin_give",
                    null,
                    BASE_TIME.plusSeconds(1));

            AuditRecord first = repository.appendAudit(
                    new AuditRecord(
                            0,
                            "console",
                            "coin_give",
                            playerId,
                            target,
                            "{\"amount\":25}",
                            BASE_TIME.plusSeconds(2)));
            AuditRecord second = repository.appendAudit(
                    new AuditRecord(
                            0,
                            "player:" + playerId,
                            "selection_change",
                            playerId,
                            target,
                            "{\"selection\":\"NONE\"}",
                            BASE_TIME.plusSeconds(3)));

            assertTrue(first.persisted());
            assertTrue(second.auditId() > first.auditId());
            assertEquals(
                    List.of(second, first),
                    repository.findAuditByTarget(target, 10));

            StorageSummary summary = repository.summarize();
            assertEquals(1, summary.schemaVersion());
            assertEquals(1, summary.playerCount());
            assertEquals(0, summary.ownedTitleCount());
            assertEquals(0, summary.purchaseOrderCount());
            assertEquals(1, summary.titleCoinLedgerCount());
            assertEquals(25, summary.titleCoinTotal());
            assertEquals(0, summary.saleCounterCount());
            assertEquals(0, summary.cardCount());
            assertEquals(2, summary.auditCount());
            assertTrue(summary.hasBusinessData());
        }
    }

    private JdbcCrownRepository repository(String fileName) {
        JdbcCrownRepository repository = new JdbcCrownRepository(
                new SqliteConnectionFactory(
                        temporaryDirectory.resolve(fileName),
                        5_000,
                        true,
                        "NORMAL"),
                JdbcDialect.SQLITE,
                TABLES);
        repository.initializeSchema();
        return repository;
    }

    private static void ensurePlayer(
            JdbcCrownRepository repository,
            UUID playerId,
            String name
    ) {
        repository.ensurePlayer(
                playerId,
                name,
                TitleSelection.defaultTitle(),
                BASE_TIME);
    }

    private static void transitionToCommitted(
            JdbcCrownRepository repository,
            UUID orderId,
            Instant start
    ) {
        assertTrue(repository.transitionOrder(
                orderId,
                PurchaseOrderState.PREPARED,
                PurchaseOrderState.PAYMENT_PENDING,
                null,
                start));
        assertTrue(repository.transitionOrder(
                orderId,
                PurchaseOrderState.PAYMENT_PENDING,
                PurchaseOrderState.PAYMENT_COMMITTED,
                null,
                start.plusSeconds(1)));
    }

    private static PurchaseOrderRecord mintOrder(
            UUID orderId,
            UUID playerId,
            boolean inventoryReserved,
            Instant now
    ) {
        return new PurchaseOrderRecord(
                orderId,
                UUID.randomUUID(),
                playerId,
                ProductType.CATALOG,
                VIP,
                PaymentType.MINT,
                NamespacedId.parse("mint:coin"),
                100,
                "{\"text\":\"VIP\"}",
                PurchaseOrderState.PREPARED,
                null,
                null,
                inventoryReserved,
                now,
                now);
    }

    private static OwnedTitleRecord catalogTitle(
            UUID entryId,
            UUID playerId,
            UUID orderId,
            Instant acquiredAt
    ) {
        return new OwnedTitleRecord(
                entryId,
                playerId,
                VIP,
                OwnedTitleKind.CATALOG,
                "&6VIP",
                "[",
                "]",
                "catalog",
                acquiredAt,
                null,
                orderId,
                OwnedTitleStatus.ACTIVE,
                null,
                null);
    }

    private static OwnedTitleRecord cardTitle(
            UUID entryId,
            UUID playerId,
            Instant acquiredAt,
            Instant expiresAt
    ) {
        return new OwnedTitleRecord(
                entryId,
                playerId,
                VIP,
                OwnedTitleKind.CARD,
                "&6VIP",
                "[",
                "]",
                "card",
                acquiredAt,
                expiresAt,
                null,
                OwnedTitleStatus.ACTIVE,
                null,
                null);
    }
}