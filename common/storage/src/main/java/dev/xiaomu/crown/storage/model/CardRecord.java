package dev.xiaomu.crown.storage.model;

import dev.xiaomu.crown.domain.catalog.DefinitionId;
import dev.xiaomu.crown.domain.catalog.DurationPolicy;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** 可单次兑换的称号卡持久化记录。 */
public record CardRecord(
        String cardToken,
        DefinitionId definitionId,
        DurationPolicy duration,
        String issuedBy,
        Instant issuedAt,
        UUID redeemedBy,
        Instant redeemedAt
) {
    private static final Pattern TOKEN =
            Pattern.compile("[A-Za-z0-9_-]{24,128}");

    public CardRecord {
        cardToken = requireToken(cardToken);
        definitionId = Objects.requireNonNull(
                definitionId, "definitionId");
        duration = Objects.requireNonNull(duration, "duration");
        issuedBy = requireText(issuedBy, "issuedBy", 192);
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        if ((redeemedBy == null) != (redeemedAt == null)) {
            throw new IllegalArgumentException(
                    "Card redemption metadata must be complete");
        }
        if (redeemedAt != null && redeemedAt.isBefore(issuedAt)) {
            throw new IllegalArgumentException(
                    "Card redemption precedes issuance");
        }
    }

    public static String requireToken(String value) {
        Objects.requireNonNull(value, "cardToken");
        if (!TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid Crown card token");
        }
        return value;
    }

    public boolean redeemed() {
        return redeemedBy != null;
    }

    public Optional<UUID> redeemer() {
        return Optional.ofNullable(redeemedBy);
    }

    public Optional<Instant> redemptionTime() {
        return Optional.ofNullable(redeemedAt);
    }

    public CardRecord redeemedBy(UUID playerId, Instant instant) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(instant, "instant");
        if (redeemed()) {
            throw new IllegalStateException(
                    "Crown card has already been redeemed");
        }
        return new CardRecord(
                cardToken,
                definitionId,
                duration,
                issuedBy,
                issuedAt,
                playerId,
                instant);
    }

    private static String requireText(
            String value,
            String name,
            int maximumLength
    ) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}