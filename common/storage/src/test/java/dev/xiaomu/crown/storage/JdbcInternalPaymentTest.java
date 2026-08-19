package dev.xiaomu.crown.storage;

import dev.xiaomu.crown.domain.catalog.DefinitionId;
import dev.xiaomu.crown.domain.catalog.NamespacedId;
import dev.xiaomu.crown.domain.catalog.PaymentType;
import dev.xiaomu.crown.domain.order.PurchaseOrderState;
import dev.xiaomu.crown.domain.player.TitleSelection;
import dev.xiaomu.crown.storage.jdbc.JdbcDialect;
import dev.xiaomu.crown.storage.jdbc.SqliteConnectionFactory;
import dev.xiaomu.crown.storage.jdbc.TableNames;
import dev.xiaomu.crown.storage.model.InternalPaymentStatus;
import dev.xiaomu.crown.storage.model.OrderPreparationStatus;
import dev.xiaomu.crown.storage.model.ProductType;
import dev.xiaomu.crown.storage.model.PurchaseOrderRecord;
import dev.xiaomu.crown.storage.model.SaleCounterRecord;
import dev.xiaomu.crown.storage.model.TitleCoinLedgerRecord;
import dev.xiaomu.crown.storage.repository.JdbcCrownRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcInternalPaymentTest {
    private static final DefinitionId VIP = DefinitionId.of("vip");
    private static final Instant BASE_TIME =
            Instant.parse("2026-07-26T03:00:00Z");
    private static final TableNames TABLES =
            TableNames.withPrefix("internal_");

    @TempDir
    private Path temporaryDirectory;

    @Test
    void commitsTitleCoinPaymentAtomicallyAndIdempotently() {
        UUID playerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        try (JdbcCrownRepository repository =
                     repository("title-coin.db")) {
            ensurePlayer(repository, playerId, "CoinBuyer");
            repository.adjustTitleCoins(
                    playerId,
                    100,
                    1_000,
                    "console",
                    "admin_give",
                    null,
                    BASE_TIME.plusSeconds(1));

            PurchaseOrderRecord order = order(
                    orderId,
                    playerId,
                    PaymentType.TITLE_COIN,
                    40,
                    false,
                    BASE_TIME.plusSeconds(2));
            assertEquals(
                    OrderPreparationStatus.CREATED,
                    repository.prepareOrder(order, -1, -1));
            assertTrue(repository.transitionOrder(
                    orderId,
                    PurchaseOrderState.PREPARED,
                    PurchaseOrderState.PAYMENT_PENDING,
                    null,
                    BASE_TIME.plusSeconds(3)));
            assertEquals(1,
                    repository.countPendingOrders(playerId));

            var committed = repository.commitInternalPayment(
                    orderId,
                    "player:" + playerId,
                    "title_purchase",
                    BASE_TIME.plusSeconds(4));
            assertEquals(
                    InternalPaymentStatus.COMMITTED,
                    committed.status());

            TitleCoinLedgerRecord purchaseLedger =
                    committed.ledger().orElseThrow();
            assertEquals(playerId, purchaseLedger.playerId());
            assertEquals(-40, purchaseLedger.delta());
            assertEquals(100, purchaseLedger.balanceBefore());
            assertEquals(60, purchaseLedger.balanceAfter());
            assertEquals(orderId,
                    purchaseLedger.orderId());
            assertEquals("title_purchase",
                    purchaseLedger.reason());

            assertEquals(
                    60,
                    repository.findPlayer(playerId)
                            .orElseThrow()
                            .titleCoinBalance());
            assertEquals(
                    PurchaseOrderState.PAYMENT_COMMITTED,
                    repository.findOrder(orderId)
                            .orElseThrow()
                            .state());
            assertEquals(1,
                    repository.countPendingOrders(playerId));

            List<TitleCoinLedgerRecord> ledgerBeforeRetry =
                    repository.titleCoinLedger(playerId, 10);
            assertEquals(2, ledgerBeforeRetry.size());

            var repeated = repository.commitInternalPayment(
                    orderId,
                    "player:" + playerId,
                    "title_purchase",
                    BASE_TIME.plusSeconds(5));
            assertEquals(
                    InternalPaymentStatus.ALREADY_COMMITTED,
                    repeated.status());
            assertTrue(repeated.ledger().isEmpty());
            assertEquals(
                    60,
                    repository.findPlayer(playerId)
                            .orElseThrow()
                            .titleCoinBalance());
            assertEquals(
                    ledgerBeforeRetry,
                    repository.titleCoinLedger(playerId, 10));
        }
    }

    @Test
    void insufficientFundsFailOrderReleaseReservationAndWriteNoLedger() {
        UUID playerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        try (JdbcCrownRepository repository =
                     repository("insufficient.db")) {
            ensurePlayer(repository, playerId, "PoorBuyer");
            repository.adjustTitleCoins(
                    playerId,
                    25,
                    1_000,
                    "console",
                    "admin_give",
                    null,
                    BASE_TIME.plusSeconds(1));

            PurchaseOrderRecord order = order(
                    orderId,
                    playerId,
                    PaymentType.TITLE_COIN,
                    40,
                    true,
                    BASE_TIME.plusSeconds(2));
            assertEquals(
                    OrderPreparationStatus.CREATED,
                    repository.prepareOrder(order, 1, -1));
            assertEquals(
                    new SaleCounterRecord(VIP, 0, 1, 1),
                    repository.findSaleCounter(VIP).orElseThrow());
            assertTrue(repository.transitionOrder(
                    orderId,
                    PurchaseOrderState.PREPARED,
                    PurchaseOrderState.PAYMENT_PENDING,
                    null,
                    BASE_TIME.plusSeconds(3)));

            var failed = repository.commitInternalPayment(
                    orderId,
                    "player:" + playerId,
                    "title_purchase",
                    BASE_TIME.plusSeconds(4));
            assertEquals(
                    InternalPaymentStatus.INSUFFICIENT_FUNDS,
                    failed.status());
            assertTrue(failed.ledger().isEmpty());

            var persistedOrder =
                    repository.findOrder(orderId).orElseThrow();
            assertEquals(
                    PurchaseOrderState.FAILED,
                    persistedOrder.state());
            assertEquals(
                    "insufficient_title_coins",
                    persistedOrder.failure().orElseThrow());
            assertFalse(persistedOrder.inventoryReserved());
            assertEquals(
                    new SaleCounterRecord(VIP, 0, 0, 2),
                    repository.findSaleCounter(VIP).orElseThrow());
            assertEquals(
                    25,
                    repository.findPlayer(playerId)
                            .orElseThrow()
                            .titleCoinBalance());
            assertEquals(
                    1,
                    repository.titleCoinLedger(playerId, 10).size());
            assertEquals(0,
                    repository.countPendingOrders(playerId));

            assertEquals(
                    InternalPaymentStatus.INVALID_STATE,
                    repository.commitInternalPayment(
                            orderId,
                            "player:" + playerId,
                            "title_purchase",
                            BASE_TIME.plusSeconds(5)).status());
        }
    }

    @Test
    void commitsFreeOrderAndRejectsInvalidOrMintStates() {
        UUID playerId = UUID.randomUUID();
        UUID freeOrderId = UUID.randomUUID();
        UUID mintOrderId = UUID.randomUUID();

        try (JdbcCrownRepository repository =
                     repository("payment-types.db")) {
            ensurePlayer(repository, playerId, "MixedBuyer");

            PurchaseOrderRecord freeOrder = order(
                    freeOrderId,
                    playerId,
                    PaymentType.FREE,
                    0,
                    false,
                    BASE_TIME.plusSeconds(1));
            assertEquals(
                    OrderPreparationStatus.CREATED,
                    repository.prepareOrder(freeOrder, -1, -1));

            assertEquals(
                    InternalPaymentStatus.INVALID_STATE,
                    repository.commitInternalPayment(
                            freeOrderId,
                            "player:" + playerId,
                            "title_purchase",
                            BASE_TIME.plusSeconds(2)).status());
            assertEquals(
                    InternalPaymentStatus.INVALID_STATE,
                    repository.commitInternalPayment(
                            UUID.randomUUID(),
                            "player:" + playerId,
                            "title_purchase",
                            BASE_TIME.plusSeconds(2)).status());

            assertTrue(repository.transitionOrder(
                    freeOrderId,
                    PurchaseOrderState.PREPARED,
                    PurchaseOrderState.PAYMENT_PENDING,
                    null,
                    BASE_TIME.plusSeconds(3)));
            var freeResult = repository.commitInternalPayment(
                    freeOrderId,
                    "player:" + playerId,
                    "title_purchase",
                    BASE_TIME.plusSeconds(4));
            assertEquals(
                    InternalPaymentStatus.COMMITTED,
                    freeResult.status());
            assertTrue(freeResult.ledger().isEmpty());
            assertEquals(
                    PurchaseOrderState.PAYMENT_COMMITTED,
                    repository.findOrder(freeOrderId)
                            .orElseThrow()
                            .state());
            assertTrue(repository.titleCoinLedger(
                    playerId, 10).isEmpty());

            PurchaseOrderRecord mintOrder = mintOrder(
                    mintOrderId,
                    playerId,
                    BASE_TIME.plusSeconds(5));
            assertEquals(
                    OrderPreparationStatus.CREATED,
                    repository.prepareOrder(mintOrder, -1, -1));
            assertTrue(repository.transitionOrder(
                    mintOrderId,
                    PurchaseOrderState.PREPARED,
                    PurchaseOrderState.PAYMENT_PENDING,
                    null,
                    BASE_TIME.plusSeconds(6)));

            assertThrows(IllegalArgumentException.class,
                    () -> repository.commitInternalPayment(
                            mintOrderId,
                            "player:" + playerId,
                            "title_purchase",
                            BASE_TIME.plusSeconds(7)));
            assertEquals(
                    PurchaseOrderState.PAYMENT_PENDING,
                    repository.findOrder(mintOrderId)
                            .orElseThrow()
                            .state());
        }
    }

    @Test
    void rollsBackBalanceLedgerAndOrderWhenLateSqlStepFails()
            throws SQLException {
        UUID playerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Path database = temporaryDirectory.resolve("rollback.db");

        try (JdbcCrownRepository repository = repository(database)) {
            ensurePlayer(repository, playerId, "RollbackBuyer");
            repository.adjustTitleCoins(
                    playerId,
                    100,
                    1_000,
                    "console",
                    "admin_give",
                    null,
                    BASE_TIME.plusSeconds(1));

            PurchaseOrderRecord order = order(
                    orderId,
                    playerId,
                    PaymentType.TITLE_COIN,
                    40,
                    false,
                    BASE_TIME.plusSeconds(2));
            assertEquals(
                    OrderPreparationStatus.CREATED,
                    repository.prepareOrder(order, -1, -1));
            assertTrue(repository.transitionOrder(
                    orderId,
                    PurchaseOrderState.PREPARED,
                    PurchaseOrderState.PAYMENT_PENDING,
                    null,
                    BASE_TIME.plusSeconds(3)));

            installCommitFailureTrigger(database, orderId);

            assertThrows(StorageException.class,
                    () -> repository.commitInternalPayment(
                            orderId,
                            "player:" + playerId,
                            "title_purchase",
                            BASE_TIME.plusSeconds(4)));

            assertEquals(
                    100,
                    repository.findPlayer(playerId)
                            .orElseThrow()
                            .titleCoinBalance());
            assertEquals(
                    1,
                    repository.titleCoinLedger(playerId, 10).size());
            assertEquals(
                    PurchaseOrderState.PAYMENT_PENDING,
                    repository.findOrder(orderId)
                            .orElseThrow()
                            .state());
            assertTrue(repository.findOrder(orderId)
                    .orElseThrow()
                    .failure().isEmpty());
        }
    }

    private JdbcCrownRepository repository(String fileName) {
        return repository(temporaryDirectory.resolve(fileName));
    }

    private static JdbcCrownRepository repository(Path database) {
        JdbcCrownRepository repository = new JdbcCrownRepository(
                new SqliteConnectionFactory(
                        database,
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

    private static PurchaseOrderRecord order(
            UUID orderId,
            UUID playerId,
            PaymentType paymentType,
            long amountMinor,
            boolean inventoryReserved,
            Instant now
    ) {
        return new PurchaseOrderRecord(
                orderId,
                null,
                playerId,
                ProductType.CATALOG,
                VIP,
                paymentType,
                null,
                amountMinor,
                "{\"text\":\"VIP\"}",
                PurchaseOrderState.PREPARED,
                null,
                null,
                inventoryReserved,
                now,
                now);
    }

    private static PurchaseOrderRecord mintOrder(
            UUID orderId,
            UUID playerId,
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
                false,
                now,
                now);
    }

    private static void installCommitFailureTrigger(
            Path database,
            UUID orderId
    ) throws SQLException {
        String sql = "CREATE TRIGGER internal_fail_payment_commit "
                + "BEFORE UPDATE OF state ON internal_purchase_orders "
                + "WHEN OLD.order_id = '" + orderId + "' "
                + "AND NEW.state = 'PAYMENT_COMMITTED' "
                + "BEGIN SELECT RAISE(ABORT, "
                + "'forced late payment failure'); END";
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}