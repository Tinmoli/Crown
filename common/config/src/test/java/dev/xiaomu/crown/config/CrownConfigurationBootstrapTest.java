package dev.xiaomu.crown.config;

import dev.xiaomu.crown.config.runtime.CrownConfigurationBootstrap;
import dev.xiaomu.crown.config.runtime.RuntimeSnapshot;
import dev.xiaomu.crown.config.runtime.RuntimeSnapshotManager;
import dev.xiaomu.crown.config.model.DisplayMode;
import dev.xiaomu.crown.domain.catalog.PaymentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CrownConfigurationBootstrapTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-26T00:00:00Z"),
            ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void deploysAndParsesAllManagedDefaults() throws Exception {
        var validatorCalls = new AtomicInteger();
        var bootstrap = new CrownConfigurationBootstrap(
                FIXED_CLOCK,
                snapshot -> validatorCalls.incrementAndGet());

        var report = bootstrap.initialize(temporaryDirectory);
        RuntimeSnapshot snapshot = report.snapshot();

        assertEquals(1, validatorCalls.get());
        assertEquals("zh_cn", snapshot.core().language());
        assertEquals(DisplayMode.PLACEHOLDER,
                snapshot.core().display().chatMode());
        assertEquals(DisplayMode.PLACEHOLDER,
                snapshot.core().display().tabMode());
        assertEquals(DisplayMode.PLACEHOLDER,
                snapshot.core().display().nametagMode());
        assertEquals("萌新",
                snapshot.core().defaultTitle().content().textSource());
        assertEquals(2, snapshot.catalog().definitions().size());
        assertEquals(PaymentType.MINT,
                snapshot.catalog().find("veteran")
                        .orElseThrow().payment().type());
        assertEquals(PaymentType.TITLE_COIN,
                snapshot.catalog().find("event_winner")
                        .orElseThrow().payment().type());
        assertEquals(8, snapshot.gui().layouts().size());
        assertEquals("购买成功：%1%",
                stripMessagePrefix(snapshot.languages()
                        .text("purchase.success")));

        assertEquals(11, report.configurations().size());
        assertEquals(2, report.languages().size());
        assertEquals(11, report.changedConfigurationCount());
        assertEquals(2, report.changedLanguageCount());

        assertTrue(Files.exists(
                temporaryDirectory.resolve("config.yml")));
        assertTrue(Files.exists(
                temporaryDirectory.resolve("titles.yml")));
        assertTrue(Files.exists(
                temporaryDirectory.resolve("storage.yml")));
        assertTrue(Files.exists(
                temporaryDirectory.resolve("gui/shop.yml")));
        assertTrue(Files.exists(
                temporaryDirectory.resolve("lang/zh_cn.json")));
        assertTrue(Files.isDirectory(
                temporaryDirectory.resolve("data")));
        assertTrue(Files.isDirectory(
                temporaryDirectory.resolve("backups")));

        String coreText = Files.readString(
                temporaryDirectory.resolve("config.yml"),
                StandardCharsets.UTF_8);
        assertTrue(coreText.startsWith("# Crown 核心配置"));
        assertTrue(coreText.contains("# 服务器默认称号"));
    }

    @Test
    void secondLoadIsStableAndDoesNotRewriteDefaults() throws Exception {
        var bootstrap = new CrownConfigurationBootstrap(
                FIXED_CLOCK, snapshot -> {
                });
        bootstrap.initialize(temporaryDirectory);

        Path config = temporaryDirectory.resolve("config.yml");
        String before = Files.readString(config, StandardCharsets.UTF_8);
        var second = bootstrap.initialize(temporaryDirectory);
        String after = Files.readString(config, StandardCharsets.UTF_8);

        assertEquals(before, after);
        assertEquals(0, second.changedConfigurationCount());
        assertEquals(0, second.changedLanguageCount());
    }

    @Test
    void reloadFailureRetainsPublishedSnapshot() throws Exception {
        var manager = new RuntimeSnapshotManager(
                temporaryDirectory,
                new CrownConfigurationBootstrap(
                        FIXED_CLOCK, snapshot -> {
                        }));
        RuntimeSnapshot original = manager.start().snapshot();

        Path config = temporaryDirectory.resolve("config.yml");
        String invalid = Files.readString(
                config, StandardCharsets.UTF_8)
                .replace("minimum-length: 1",
                        "minimum-length: 200");
        Files.writeString(
                config, invalid, StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, manager::reload);
        assertSame(original, manager.requireSnapshot());

        String repaired = invalid.replace(
                "minimum-length: 200",
                "minimum-length: 1");
        Files.writeString(
                config, repaired, StandardCharsets.UTF_8);
        RuntimeSnapshot reloaded = manager.reload().snapshot();

        assertNotSame(original, reloaded);
        assertSame(reloaded, manager.requireSnapshot());
    }

    @Test
    void platformValidationFailureDoesNotPublishCandidate()
            throws Exception {
        var calls = new AtomicInteger();
        var manager = new RuntimeSnapshotManager(
                temporaryDirectory,
                new CrownConfigurationBootstrap(
                        FIXED_CLOCK,
                        snapshot -> {
                            if (calls.incrementAndGet() > 1) {
                                throw new IllegalArgumentException(
                                        "missing registry item");
                            }
                        }));
        RuntimeSnapshot original = manager.start().snapshot();

        assertThrows(IllegalArgumentException.class, manager::reload);
        assertSame(original, manager.requireSnapshot());
    }

    @Test
    void invalidGuiSlotRejectsWholeSnapshot() throws Exception {
        var bootstrap = new CrownConfigurationBootstrap(
                FIXED_CLOCK, snapshot -> {
                });
        bootstrap.initialize(temporaryDirectory);

        Path gui = temporaryDirectory.resolve("gui/main.yml");
        String invalid = Files.readString(gui, StandardCharsets.UTF_8)
                .replace("slot: 22", "slot: 99");
        Files.writeString(gui, invalid, StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class,
                () -> bootstrap.initialize(temporaryDirectory));
    }

    @Test
    void invalidDisplayModeRejectsWholeSnapshot() throws Exception {
        var bootstrap = new CrownConfigurationBootstrap(
                FIXED_CLOCK, snapshot -> {
                });
        bootstrap.initialize(temporaryDirectory);

        Path config = temporaryDirectory.resolve("config.yml");
        String invalid = Files.readString(config, StandardCharsets.UTF_8)
                .replace("chat: \"placeholder\"", "chat: \"unknown\"");
        Files.writeString(config, invalid, StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class,
                () -> bootstrap.initialize(temporaryDirectory));
    }

    @Test
    void futureConfigurationVersionIsRejected() throws Exception {
        var bootstrap = new CrownConfigurationBootstrap(
                FIXED_CLOCK, snapshot -> {
                });
        bootstrap.initialize(temporaryDirectory);

        Path storage = temporaryDirectory.resolve("storage.yml");
        String future = Files.readString(
                storage, StandardCharsets.UTF_8)
                .replace("config-version: 1", "config-version: 99");
        Files.writeString(storage, future, StandardCharsets.UTF_8);

        IOException failure = assertThrows(
                IOException.class,
                () -> bootstrap.initialize(temporaryDirectory));
        assertTrue(failure.getMessage().contains(
                "newer config-version"));
    }

    private static String stripMessagePrefix(String value) {
        assertFalse(value.isBlank());
        int marker = value.indexOf("&a");
        return marker < 0 ? value : value.substring(marker + 2);
    }
}