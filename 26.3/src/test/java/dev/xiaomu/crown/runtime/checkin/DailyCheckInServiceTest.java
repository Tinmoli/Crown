package dev.xiaomu.crown.runtime.checkin;

import static org.junit.jupiter.api.Assertions.*;

import dev.xiaomu.crown.runtime.lifecycle.CrownRuntime;
import dev.xiaomu.crown.storage.model.CheckInResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class DailyCheckInServiceTest {
    @TempDir Path root;

    @Test
    void concurrentSelfAndProxyClaimsShareOneDailyAttempt() throws Exception {
        try (var runtime = new CrownRuntime(root.resolve("config/crown"), root)) {
            runtime.start();
            Path config = root.resolve("config/crown/config.yml");
            Files.writeString(
                    config,
                    Files.readString(config)
                            .replace("reward-minimum: 0", "reward-minimum: 10")
                            .replace("reward-maximum: 50", "reward-maximum: 10"));
            runtime.reload();

            UUID playerId = UUID.randomUUID();
            var claims = new ArrayList<CompletableFuture<CheckInResult>>();
            for (int attempt = 0; attempt < 20; attempt++) {
                claims.add(
                        DailyCheckInService.claim(runtime, playerId, "Player")
                                .toCompletableFuture());
            }
            CompletableFuture.allOf(claims.toArray(CompletableFuture[]::new))
                    .get(10, TimeUnit.SECONDS);
            assertEquals(
                    1,
                    claims.stream()
                            .map(CompletableFuture::join)
                            .filter(result -> result.status() == CheckInResult.Status.CLAIMED)
                            .count());
            assertEquals(
                    10,
                    runtime.storageBackend()
                            .repository()
                            .findPlayer(playerId)
                            .orElseThrow()
                            .titleCoinBalance());
            assertEquals(
                    10,
                    DailyCheckInService.claim(runtime, UUID.randomUUID(), "AnotherPlayer")
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
                            .reward());

            Files.writeString(
                    config,
                    Files.readString(config)
                            .replace("\r\n", "\n")
                            .replace("check-in:\n  enabled: true", "check-in:\n  enabled: false"));
            runtime.reload();
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () ->
                            DailyCheckInService.claim(runtime, UUID.randomUUID(), "DisabledPlayer")
                                    .toCompletableFuture()
                                    .get(10, TimeUnit.SECONDS));
        }
    }
}
