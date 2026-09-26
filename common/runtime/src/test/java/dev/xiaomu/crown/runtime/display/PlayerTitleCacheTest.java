package dev.xiaomu.crown.runtime.display;

import dev.xiaomu.crown.config.runtime.RuntimeSnapshot;
import dev.xiaomu.crown.config.runtime.CrownConfigurationBootstrap;
import dev.xiaomu.crown.domain.player.SelectionType;
import dev.xiaomu.crown.domain.text.StyledText;
import dev.xiaomu.crown.storage.repository.CrownRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class PlayerTitleCacheTest {
    @TempDir Path root;

    private CrownRepository noDatabaseReads() {
        return (CrownRepository) Proxy.newProxyInstance(
                CrownRepository.class.getClassLoader(), new Class<?>[]{CrownRepository.class},
                (proxy, method, args) -> { throw new AssertionError("Display accessed database: " + method); });
    }

    @Test void expiredCachedTitleDisappearsWithoutDatabaseRead() {
        Instant now = Instant.parse("2026-09-26T00:00:00Z");
        var cache = new PlayerTitleCache(noDatabaseReads(), () -> null,
                Clock.fixed(now, ZoneOffset.UTC));
        UUID player = UUID.randomUUID();
        for (Instant expiry : new Instant[]{now.minusSeconds(1), now}) {
            cache.put(new PlayerTitleCache.CachedPlayer(player, "Player", 50,
                    new ResolvedTitle(SelectionType.OWNED, UUID.randomUUID(), "winner",
                            StyledText.empty(), StyledText.empty(), StyledText.empty(), expiry)));
            assertEquals(SelectionType.NONE, cache.get(player).title().state());
            assertEquals(50, cache.get(player).titleCoinBalance());
        }
    }

    @Test void defaultTitleTracksReloadAndCanBeReenabled() throws Exception {
        var bootstrap = new CrownConfigurationBootstrap();
        AtomicReference<RuntimeSnapshot> snapshot = new AtomicReference<>(bootstrap.initialize(root).snapshot());
        var cache = new PlayerTitleCache(noDatabaseReads(), snapshot::get);
        UUID player = UUID.randomUUID();
        cache.put(new PlayerTitleCache.CachedPlayer(player, "Player", 50,
                new ResolvedTitle(SelectionType.DEFAULT, null, "default",
                        StyledText.empty(), StyledText.empty(), StyledText.empty(), null)));
        assertTrue(cache.get(player).title().plainText().contains("萌新"));
        Path config = root.resolve("config.yml");
        String original = Files.readString(config);
        Files.writeString(config, original.replace("text: \"萌新\"", "text: \"新称号\""));
        snapshot.set(bootstrap.initialize(root).snapshot());
        assertTrue(cache.get(player).title().plainText().contains("新称号"));
        Files.writeString(config, original.replaceFirst("enabled: true", "enabled: false"));
        snapshot.set(bootstrap.initialize(root).snapshot());
        assertEquals(SelectionType.NONE, cache.get(player).title().state());
        Files.writeString(config, original);
        snapshot.set(bootstrap.initialize(root).snapshot());
        assertEquals(SelectionType.DEFAULT, cache.get(player).title().state());
    }
}
