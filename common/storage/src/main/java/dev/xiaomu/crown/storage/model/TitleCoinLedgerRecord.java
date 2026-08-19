package dev.xiaomu.crown.storage.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 不可变称号币流水。 */
public record TitleCoinLedgerRecord(
        long ledgerId,
        UUID playerId,
        long delta,
        long balanceBefore,
        long balanceAfter,
        String actor,
        String reason,
        UUID orderId,
        Instant createdAt
) {
    public TitleCoinLedgerRecord {
        if (ledgerId < 1) {
            throw new IllegalArgumentException(
                    "Ledger ID must be positive");
        }
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (delta == 0 || balanceBefore < 0 || balanceAfter < 0
                || Math.addExact(balanceBefore, delta) != balanceAfter) {
            throw new IllegalArgumentException(
                    "Invalid title coin ledger amounts");
        }
        actor = requireText(actor, "actor", 192);
        reason = requireText(reason, "reason", 128);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public Optional<UUID> purchaseOrderId() {
        return Optional.ofNullable(orderId);
    }

    private static String requireText(
            String value,
            String name,
            int maximumLength
    ) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}