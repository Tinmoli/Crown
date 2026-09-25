package dev.xiaomu.crown.runtime.purchase;

import dev.xiaomu.crown.domain.catalog.TitleDefinition;
import dev.xiaomu.crown.domain.player.TitleSelection;
import dev.xiaomu.crown.domain.order.PurchaseOrderState;
import dev.xiaomu.crown.runtime.lifecycle.CrownRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the same purchase service used by both confirmation GUIs against SQLite. */
final class TitleCoinPurchaseTest {
    @TempDir Path root;

    private CrownRuntime start() throws Exception {
        CrownRuntime runtime = new CrownRuntime(root.resolve("config/crown"), root);
        runtime.start();
        return runtime;
    }

    private UUID player(CrownRuntime runtime, long coins) throws Exception {
        UUID id = UUID.randomUUID();
        await(runtime.storageExecutor().submit(() -> {
            var repository = runtime.storageBackend().repository();
            repository.ensurePlayer(id, "Buyer", TitleSelection.defaultTitle(), Instant.now());
            repository.adjustTitleCoins(id, coins, Long.MAX_VALUE, "console", "admin:give", null, Instant.now());
            return null;
        }));
        return id;
    }

    private CompletionStage<PurchaseResult> buy(CrownRuntime runtime, UUID player, String product,
                                               boolean permitted, PurchaseIdentifiers ids) {
        TitleDefinition title = runtime.snapshot().catalog().find(product).orElseThrow();
        return runtime.purchaseService().purchaseCatalog(player, title, permitted,
                runtime.snapshot().core().purchase(), ids);
    }

    private long balance(CrownRuntime runtime, UUID player) throws Exception {
        return await(runtime.storageExecutor().submit(() -> runtime.storageBackend().repository()
                .findPlayer(player).orElseThrow().titleCoinBalance()));
    }

    @Test void administratorCoinsBuyExactlyOnceEvenWithConcurrentConfirmations() throws Exception {
        try (var runtime = start()) {
            UUID player = player(runtime, 100);
            PurchaseIdentifiers ids = PurchaseIdentifiers.create();
            var first = buy(runtime, player, "veteran", true, ids);
            var repeated = buy(runtime, player, "veteran", true, ids);
            assertEquals(PurchaseStatus.GRANTED, await(first).status());
            assertEquals(await(first).entryId(), await(repeated).entryId());
            assertEquals(50, balance(runtime, player));
            assertEquals(1, await(runtime.storageExecutor().submit(() -> runtime.storageBackend().repository()
                    .listOwnedTitles(player, false).size())));
            assertEquals(2, await(runtime.storageExecutor().submit(() -> runtime.storageBackend().repository()
                    .titleCoinLedger(player, 10).size()))); // administrator credit plus one purchase
        }
    }

    @Test void insufficientCoinsDoNotGrantAndDoNotReserveStock() throws Exception {
        try (var runtime = start()) {
            UUID player = player(runtime, 10);
            var result = await(buy(runtime, player, "event_winner", true, PurchaseIdentifiers.create()));
            assertEquals(PurchaseStatus.INSUFFICIENT_FUNDS, result.status());
            assertEquals(10, balance(runtime, player));
            assertTrue(await(runtime.storageExecutor().submit(() -> runtime.storageBackend().repository()
                    .listOwnedTitles(player, false).isEmpty())));
            assertEquals(0, await(runtime.storageExecutor().submit(() -> runtime.storageBackend().repository()
                    .findSaleCounter(runtime.snapshot().catalog().find("event_winner").orElseThrow().id())
                    .orElseThrow().reservedCount())));
        }
    }

    @Test void permissionDenialDoesNotCharge() throws Exception {
        try (var runtime = start()) {
            UUID player = player(runtime, 100);
            assertEquals(PurchaseStatus.PERMISSION_DENIED,
                    await(buy(runtime, player, "event_winner", false, PurchaseIdentifiers.create())).status());
            assertEquals(100, balance(runtime, player));
        }
    }

    @Test void customTitleUsesTheSameCoinBalance() throws Exception {
        try (var runtime = start()) {
            UUID player = player(runtime, 100);
            var core = runtime.snapshot().core();
            var result = await(runtime.purchaseService().purchaseCustom(player, core.customTitle(),
                    core.defaultTitle().content(), core.purchase(), PurchaseIdentifiers.create()));
            assertEquals(PurchaseStatus.GRANTED, result.status());
            assertEquals(50, balance(runtime, player));
            assertEquals("萌新", await(runtime.storageExecutor().submit(() -> runtime.storageBackend().repository()
                    .findOwnedTitle(result.entryId()).orElseThrow().titleText())));
        }
    }

    @Test void failedGrantIsRecoveredAfterRestartWithoutChargingAgain() throws Exception {
        UUID player;
        try (var runtime = start()) {
            player = player(runtime, 100);
            try (var connection = runtime.storageBackend().connections().open(); var sql = connection.createStatement()) {
                sql.execute("CREATE TRIGGER fail_grant BEFORE INSERT ON " + runtime.storageBackend().tables().ownedTitles()
                        + " BEGIN SELECT RAISE(ABORT, 'test grant failure'); END");
            }
            PurchaseIdentifiers ids = PurchaseIdentifiers.create();
            assertThrows(Exception.class, () -> await(buy(runtime, player, "veteran", true, ids)));
            assertEquals(50, balance(runtime, player));
            assertEquals(PurchaseOrderState.PAYMENT_COMMITTED,
                    await(runtime.storageExecutor().submit(() -> runtime.storageBackend().repository()
                            .findOrder(ids.orderId()).orElseThrow().state())));
            try (var connection = runtime.storageBackend().connections().open(); var sql = connection.createStatement()) {
                sql.execute("DROP TRIGGER fail_grant");
            }
        }
        try (var runtime = start()) {
            assertEquals(1, await(runtime.purchaseService().recover(100)).granted());
            assertEquals(50, balance(runtime, player));
            assertEquals(0, await(runtime.purchaseService().recover(100)).attempted());
        }
    }

    @Test void storageChangeRejectsReloadWithoutReplacingTheActiveSnapshot() throws Exception {
        try (var runtime = start()) {
            var original = runtime.snapshot();
            Path storage = runtime.configRoot().resolve("storage.yml");
            String contents = Files.readString(storage);
            assertTrue(contents.contains("crown.db"));
            Files.writeString(storage, contents.replace("crown.db", "different.db"));
            assertThrows(IllegalArgumentException.class, runtime::reload);
            assertSame(original, runtime.snapshot());
        }
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
