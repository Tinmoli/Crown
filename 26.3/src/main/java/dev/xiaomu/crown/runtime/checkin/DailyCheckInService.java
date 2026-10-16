package dev.xiaomu.crown.runtime.checkin;

import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.domain.player.TitleSelection;
import dev.xiaomu.crown.runtime.lifecycle.CrownRuntime;
import dev.xiaomu.crown.storage.model.CheckInResult;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;

/** Queues each player's claim and runs all database work off the server thread. */
public final class DailyCheckInService {
    private DailyCheckInService() {}

    public static CompletionStage<CheckInResult> claim(
            CrownRuntime runtime, UUID playerId, String playerName) {
        CoreSettings settings = runtime.snapshot().core();
        if (!settings.checkIn().enabled()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Check-in is disabled"));
        }
        return runtime.playerOperations()
                .submit(
                        playerId,
                        () ->
                                runtime.storageExecutor()
                                        .submit(
                                                () ->
                                                        persistClaim(
                                                                runtime,
                                                                settings,
                                                                playerId,
                                                                playerName)));
    }

    private static CheckInResult persistClaim(
            CrownRuntime runtime, CoreSettings settings, UUID playerId, String playerName) {
        Instant now = Instant.now();
        var repository = runtime.storageBackend().repository();
        TitleSelection initialSelection =
                settings.defaultTitle().equipForNewPlayer()
                        ? TitleSelection.defaultTitle()
                        : TitleSelection.none();
        repository.ensurePlayer(playerId, playerName, initialSelection, now);

        var checkIn = settings.checkIn();
        long reward = checkIn.drawReward(ThreadLocalRandom.current());
        return repository.claimDailyReward(
                playerId,
                now.atZone(checkIn.zoneId()).toLocalDate(),
                reward,
                settings.titleCoin().maximumBalance(),
                now);
    }
}
