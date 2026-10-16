package dev.xiaomu.crown.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.xiaomu.crown.domain.player.TitleSelection;
import dev.xiaomu.crown.storage.jdbc.JdbcDialect;
import dev.xiaomu.crown.storage.jdbc.SqliteConnectionFactory;
import dev.xiaomu.crown.storage.jdbc.TableNames;
import dev.xiaomu.crown.storage.model.CheckInResult.Status;
import dev.xiaomu.crown.storage.repository.JdbcCrownRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

final class DailyCheckInTest {
    @TempDir Path directory;

    private JdbcCrownRepository open() {
        return new JdbcCrownRepository(
                new SqliteConnectionFactory(directory.resolve("check-in.db"), 5000, true, "NORMAL"),
                JdbcDialect.SQLITE,
                TableNames.withPrefix("crown_"));
    }

    @Test
    void persistsClaimAcrossRestartAndAllowsTheNextDay() {
        UUID playerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-28T01:00:00Z");
        LocalDate date = LocalDate.of(2026, 9, 28);
        try (var repository = open()) {
            repository.initializeSchema();
            repository.ensurePlayer(playerId, "Player", TitleSelection.none(), now);
            assertEquals(
                    Status.CLAIMED,
                    repository.claimDailyReward(playerId, date, 10, 100, now).status());
        }
        try (var repository = open()) {
            repository.initializeSchema();
            assertEquals(
                    Status.ALREADY_CLAIMED,
                    repository.claimDailyReward(playerId, date, 50, 100, now).status());
            assertEquals(
                    20,
                    repository
                            .claimDailyReward(
                                    playerId, date.plusDays(1), 10, 100, now.plusSeconds(86400))
                            .balance());
            assertEquals(2, repository.titleCoinLedger(playerId, 10).size());
        }
    }

    @Test
    void losingRollConsumesAttemptAndBalanceLimitDoesNot() {
        UUID playerId = UUID.randomUUID();
        Instant now = Instant.now();
        LocalDate date = LocalDate.of(2026, 9, 28);
        try (var repository = open()) {
            repository.initializeSchema();
            repository.ensurePlayer(playerId, "Player", TitleSelection.none(), now);
            assertEquals(
                    Status.CLAIMED,
                    repository.claimDailyReward(playerId, date, 0, 100, now).status());
            assertEquals(
                    Status.ALREADY_CLAIMED,
                    repository.claimDailyReward(playerId, date, 10, 100, now).status());
            assertEquals(
                    Status.BALANCE_LIMIT,
                    repository
                            .claimDailyReward(playerId, date.plusDays(1), 101, 100, now)
                            .status());
            assertEquals(
                    10,
                    repository
                            .claimDailyReward(playerId, date.plusDays(1), 10, 100, now)
                            .balance());
            assertEquals(1, repository.titleCoinLedger(playerId, 10).size());
        }
    }
}
