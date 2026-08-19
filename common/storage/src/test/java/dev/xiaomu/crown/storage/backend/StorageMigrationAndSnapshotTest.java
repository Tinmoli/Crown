package dev.xiaomu.crown.storage.backend;

import dev.xiaomu.crown.config.model.StorageSettings;
import dev.xiaomu.crown.domain.catalog.DefinitionId;
import dev.xiaomu.crown.domain.catalog.DurationPolicy;
import dev.xiaomu.crown.domain.catalog.NamespacedId;
import dev.xiaomu.crown.domain.catalog.PaymentType;
import dev.xiaomu.crown.domain.order.PurchaseOrderState;
import dev.xiaomu.crown.domain.player.TitleSelection;
import dev.xiaomu.crown.storage.StorageException;
import dev.xiaomu.crown.storage.jdbc.JdbcDialect;
import dev.xiaomu.crown.storage.jdbc.SqliteConnectionFactory;
import dev.xiaomu.crown.storage.jdbc.TableNames;
import dev.xiaomu.crown.storage.migration.JdbcStorageMigrator;
import dev.xiaomu.crown.storage.model.AuditRecord;
import dev.xiaomu.crown.storage.model.CardRecord;
import dev.xiaomu.crown.storage.model.OrderPreparationStatus;
import dev.xiaomu.crown.storage.model.OwnedTitleKind;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import dev.xiaomu.crown.storage.model.OwnedTitleStatus;
import dev.xiaomu.crown.storage.model.ProductType;
import dev.xiaomu.crown.storage.model.PurchaseOrderRecord;
import dev.xiaomu.crown.storage.model.StorageSummary;
import dev.xiaomu.crown.storage.repository.JdbcCrownRepository;
import dev.xiaomu.crown.storage.snapshot.SqliteSnapshotManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StorageMigrationAndSnapshotTest {
    private static final DefinitionId VIP = DefinitionId.of("vip");
    private static final TableNames TABLES =
            TableNames.withPrefix("crown_");
    private static final Instant BASE_TIME =
            Instant.parse("2026-07-26T03:00:00Z");
    private static final StorageSettings.Verification VERIFY_ALL =
            new StorageSettings.Verification(
                    true, true, true, true, true, true);

    @TempDir
    private Path temporaryDirectory;

    @Test
    void migratesAllTablesInBothDirections() {
        Path sourceFile = temporaryDirectory.resolve("source.db");
        Path middleFile = temporaryDirectory.resolve("middle.db");
        Path finalFile = temporaryDirectory.resolve("final.db");

        try (StorageBackend source = backend(
                StorageSettings.Type.SQLITE, sourceFile);
             StorageBackend middle = backend(
                     StorageSettings.Type.MYSQL, middleFile);
             StorageBackend target = backend(
                     StorageSettings.Type.SQLITE, finalFile)) {
            Seed seed = seedAllTables(source.repository());

            JdbcStorageMigrator migrator =
                    new JdbcStorageMigrator();
            var forward = migrator.migrate(
                    source,
                    middle,
                    VERIFY_ALL,
                    Instant.now());

            assertEquals(StorageSettings.Type.SQLITE,
                    forward.sourceType());
            assertEquals(StorageSettings.Type.MYSQL,
                    forward.targetType());
            assertEquals(7, forward.totalCopiedRows());
            assertEquals(
                    forward.sourceSummary(),
                    forward.targetSummary());
            assertSeedCopied(middle.repository(), seed);

            var reverse = migrator.migrate(
                    middle,
                    target,
                    VERIFY_ALL,
                    Instant.now());
            assertEquals(StorageSettings.Type.MYSQL,
                    reverse.sourceType());
            assertEquals(StorageSettings.Type.SQLITE,
                    reverse.targetType());
            assertEquals(
                    reverse.sourceSummary(),
                    reverse.targetSummary());
            assertSeedCopied(target.repository(), seed);
        }
    }

    @Test
    void rollsBackEveryCopiedTableWhenLateCopyFails()
            throws Exception {
        Path sourceFile = temporaryDirectory.resolve(
                "rollback-source.db");
        Path targetFile = temporaryDirectory.resolve(
                "rollback-target.db");

        try (StorageBackend source = backend(
                StorageSettings.Type.SQLITE, sourceFile);
             StorageBackend target = backend(
                     StorageSettings.Type.MYSQL, targetFile)) {
            seedAllTables(source.repository());
            try (Connection connection =
                         target.connections().open();
                 Statement statement =
                         connection.createStatement()) {
                statement.executeUpdate(
                        "CREATE TRIGGER reject_migrated_cards"
                                + " BEFORE INSERT ON "
                                + target.tables().cards()
                                + " BEGIN SELECT RAISE(ABORT,"
                                + " 'forced card failure'); END");
            }

            assertThrows(StorageException.class,
                    () -> new JdbcStorageMigrator().migrate(
                            source,
                            target,
                            VERIFY_ALL,
                            Instant.now()));

            StorageSummary empty =
                    target.repository().summarize();
            assertFalse(empty.hasBusinessData());
            assertEquals(0, empty.playerCount());
            assertEquals(0, empty.purchaseOrderCount());
            assertEquals(0, empty.ownedTitleCount());
            assertEquals(0, empty.titleCoinLedgerCount());
            assertEquals(0, empty.saleCounterCount());
            assertEquals(0, empty.cardCount());
            assertEquals(0, empty.auditCount());
        }
    }

    @Test
    void rejectsNonEmptyMigrationTargetWithoutChangingIt() {
        try (StorageBackend source = backend(
                StorageSettings.Type.SQLITE,
                temporaryDirectory.resolve(
                        "nonempty-source.db"));
             StorageBackend target = backend(
                     StorageSettings.Type.MYSQL,
                     temporaryDirectory.resolve(
                             "nonempty-target.db"))) {
            seedAllTables(source.repository());
            UUID targetPlayer = UUID.randomUUID();
            target.repository().ensurePlayer(
                    targetPlayer,
                    "ExistingTarget",
                    TitleSelection.none(),
                    BASE_TIME);

            StorageSummary before =
                    target.repository().summarize();
            assertThrows(StorageException.class,
                    () -> new JdbcStorageMigrator().migrate(
                            source,
                            target,
                            VERIFY_ALL,
                            Instant.now()));
            assertEquals(
                    before,
                    target.repository().summarize());
            assertTrue(target.repository()
                    .findPlayer(targetPlayer).isPresent());
        }
    }

    @Test
    void createsConsistentWalSnapshotsAndPrunesOldFiles()
            throws Exception {
        Path database = temporaryDirectory.resolve("live.db");
        Path snapshots = temporaryDirectory.resolve("snapshots");

        try (StorageBackend backend = backend(
                StorageSettings.Type.SQLITE, database)) {
            UUID playerId = UUID.randomUUID();
            backend.repository().ensurePlayer(
                    playerId,
                    "SnapshotPlayer",
                    TitleSelection.defaultTitle(),
                    BASE_TIME);
            backend.repository().adjustTitleCoins(
                    playerId,
                    42,
                    1_000,
                    "console",
                    "snapshot_seed",
                    null,
                    BASE_TIME.plusSeconds(1));

            SqliteSnapshotManager manager =
                    new SqliteSnapshotManager();
            Path first = manager.create(
                    database,
                    backend.connections(),
                    snapshots,
                    2,
                    BASE_TIME.plusSeconds(10));
            Path second = manager.create(
                    database,
                    backend.connections(),
                    snapshots,
                    2,
                    BASE_TIME.plusSeconds(11));
            Path third = manager.create(
                    database,
                    backend.connections(),
                    snapshots,
                    2,
                    BASE_TIME.plusSeconds(12));

            assertFalse(Files.exists(first));
            assertTrue(Files.isRegularFile(second));
            assertTrue(Files.isRegularFile(third));
            try (Stream<Path> files = Files.list(snapshots)) {
                assertEquals(
                        2,
                        files.filter(Files::isRegularFile)
                                .count());
            }

            try (JdbcCrownRepository snapshotRepository =
                         new JdbcCrownRepository(
                                 new SqliteConnectionFactory(
                                         third,
                                         5_000,
                                         false,
                                         "NORMAL"),
                                 JdbcDialect.SQLITE,
                                 TABLES)) {
                assertEquals(1,
                        snapshotRepository.initializeSchema());
                assertEquals(
                        42,
                        snapshotRepository.findPlayer(playerId)
                                .orElseThrow()
                                .titleCoinBalance());
                assertEquals(
                        backend.repository().summarize(),
                        snapshotRepository.summarize());
            }
        }
    }

    @Test
    void opensConfiguredSqliteBackendAndEnforcesSwitchGuard() {
        Path gameDirectory =
                temporaryDirectory.resolve("game");
        StorageSettings settings = settings(
                "config/crown/data/test.db");

        StorageBackendFactory factory =
                new StorageBackendFactory();
        Path expectedDatabase = gameDirectory.resolve(
                "config/crown/data/test.db")
                .toAbsolutePath().normalize();

        try (StorageBackend backend =
                     factory.openConfigured(
                             gameDirectory, settings)) {
            assertEquals(StorageSettings.Type.SQLITE,
                    backend.type());
            assertEquals(
                    expectedDatabase,
                    backend.sqliteDatabase().orElseThrow());
            assertTrue(Files.isRegularFile(expectedDatabase));
            assertEquals(
                    1,
                    backend.repository().summarize()
                            .schemaVersion());
        }

        StorageSummary populated = new StorageSummary(
                1, 1, 0, 0, 0, 0, 0, 0, 0);
        StorageSummary empty = new StorageSummary(
                1, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThrows(StorageException.class,
                () -> StorageSwitchGuard.requireSafeActivation(
                        populated, empty, true));
        StorageSwitchGuard.requireSafeActivation(
                populated, empty, false);
        StorageSwitchGuard.requireSafeActivation(
                empty, populated, true);
    }

    private StorageBackend backend(
            StorageSettings.Type type,
            Path database
    ) {
        var connections = new SqliteConnectionFactory(
                database,
                5_000,
                true,
                "NORMAL");
        StorageBackend backend = new StorageBackend(
                type,
                connections,
                JdbcDialect.SQLITE,
                TABLES,
                database.toAbsolutePath().normalize());
        backend.repository().initializeSchema();
        return backend;
    }

    private static Seed seedAllTables(
            JdbcCrownRepository repository
    ) {
        UUID playerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        String cardToken = "MigrationCardToken_1234567890";
        String auditTarget = orderId.toString();

        repository.ensurePlayer(
                playerId,
                "MigrationPlayer",
                TitleSelection.defaultTitle(),
                BASE_TIME);
        repository.adjustTitleCoins(
                playerId,
                100,
                10_000,
                "console",
                "migration_seed",
                null,
                BASE_TIME.plusSeconds(1));

        PurchaseOrderRecord order = new PurchaseOrderRecord(
                orderId,
                UUID.randomUUID(),
                playerId,
                ProductType.CATALOG,
                VIP,
                PaymentType.MINT,
                NamespacedId.parse("mint:coin"),
                250,
                "{\"text\":\"VIP\"}",
                PurchaseOrderState.PREPARED,
                null,
                null,
                true,
                BASE_TIME.plusSeconds(2),
                BASE_TIME.plusSeconds(2));
        assertEquals(
                OrderPreparationStatus.CREATED,
                repository.prepareOrder(order, 10, 3));
        assertTrue(repository.transitionOrder(
                orderId,
                PurchaseOrderState.PREPARED,
                PurchaseOrderState.PAYMENT_PENDING,
                null,
                BASE_TIME.plusSeconds(3)));
        assertTrue(repository.transitionOrder(
                orderId,
                PurchaseOrderState.PAYMENT_PENDING,
                PurchaseOrderState.PAYMENT_COMMITTED,
                null,
                BASE_TIME.plusSeconds(4)));

        OwnedTitleRecord title = new OwnedTitleRecord(
                entryId,
                playerId,
                VIP,
                OwnedTitleKind.CATALOG,
                "&6VIP",
                "[",
                "]",
                "migration",
                BASE_TIME.plusSeconds(5),
                null,
                orderId,
                OwnedTitleStatus.ACTIVE,
                null,
                null);
        repository.grantCommittedOrder(
                orderId, title, BASE_TIME.plusSeconds(5));

        CardRecord card = new CardRecord(
                cardToken,
                VIP,
                DurationPolicy.limited(7),
                "console",
                BASE_TIME.plusSeconds(6),
                null,
                null);
        assertTrue(repository.createCard(card));

        AuditRecord audit = repository.appendAudit(
                new AuditRecord(
                        0,
                        "console",
                        "migration_seed",
                        playerId,
                        auditTarget,
                        "{\"seed\":true}",
                        BASE_TIME.plusSeconds(7)));

        StorageSummary summary = repository.summarize();
        assertEquals(1, summary.playerCount());
        assertEquals(1, summary.purchaseOrderCount());
        assertEquals(1, summary.ownedTitleCount());
        assertEquals(1, summary.titleCoinLedgerCount());
        assertEquals(1, summary.saleCounterCount());
        assertEquals(1, summary.cardCount());
        assertEquals(1, summary.auditCount());
        return new Seed(
                playerId,
                orderId,
                entryId,
                cardToken,
                auditTarget,
                audit.auditId(),
                summary);
    }

    private static void assertSeedCopied(
            JdbcCrownRepository repository,
            Seed seed
    ) {
        assertEquals(
                seed.summary(),
                repository.summarize());
        assertEquals(
                100,
                repository.findPlayer(seed.playerId())
                        .orElseThrow().titleCoinBalance());
        assertEquals(
                PurchaseOrderState.GRANTED,
                repository.findOrder(seed.orderId())
                        .orElseThrow().state());
        assertEquals(
                seed.entryId(),
                repository.findOwnedTitle(seed.entryId())
                        .orElseThrow().entryId());
        assertEquals(
                seed.cardToken(),
                repository.findCard(seed.cardToken())
                        .orElseThrow().cardToken());
        assertEquals(
                seed.auditId(),
                repository.findAuditByTarget(
                                seed.auditTarget(), 10)
                        .getFirst().auditId());
    }

    private static StorageSettings settings(String sqlitePath) {
        var sqlite = new StorageSettings.Sqlite(
                sqlitePath,
                Duration.ofSeconds(5),
                true,
                StorageSettings.SqliteSynchronous.NORMAL,
                true,
                10);
        var pool = new StorageSettings.Pool(
                0,
                2,
                Duration.ofSeconds(5),
                Duration.ofSeconds(3),
                Duration.ofMinutes(10),
                Duration.ofMinutes(30));
        var mysql = new StorageSettings.Mysql(
                "127.0.0.1",
                3306,
                "crown",
                "crown",
                "unused",
                "crown_",
                Map.of(),
                pool,
                true);
        return new StorageSettings(
                StorageSettings.Type.SQLITE,
                sqlite,
                mysql,
                new StorageSettings.Migration(
                        true,
                        true,
                        VERIFY_ALL));
    }

    private record Seed(
            UUID playerId,
            UUID orderId,
            UUID entryId,
            String cardToken,
            String auditTarget,
            long auditId,
            StorageSummary summary
    ) {
    }
}