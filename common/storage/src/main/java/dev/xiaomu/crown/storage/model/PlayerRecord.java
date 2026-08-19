package dev.xiaomu.crown.storage.model;

import dev.xiaomu.crown.domain.player.TitleSelection;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 数据库中的玩家主记录。 */
public record PlayerRecord(
        UUID playerId,
        String lastKnownName,
        TitleSelection selection,
        long titleCoinBalance,
        Instant createdAt,
        Instant updatedAt
) {
    public PlayerRecord {
        playerId = Objects.requireNonNull(playerId, "playerId");
        lastKnownName = Objects.requireNonNull(
                lastKnownName, "lastKnownName");
        if (lastKnownName.isBlank() || lastKnownName.length() > 64) {
            throw new IllegalArgumentException(
                    "Player name is blank or too long");
        }
        selection = Objects.requireNonNull(selection, "selection");
        if (titleCoinBalance < 0) {
            throw new IllegalArgumentException(
                    "Title coin balance cannot be negative");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "Player updated time precedes creation");
        }
    }
}