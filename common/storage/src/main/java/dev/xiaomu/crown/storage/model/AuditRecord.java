package dev.xiaomu.crown.storage.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 不可变管理员和关键业务操作审计记录。 */
public record AuditRecord(
        long auditId,
        String actor,
        String action,
        UUID playerId,
        String targetId,
        String detailsJson,
        Instant createdAt
) {
    public AuditRecord {
        if (auditId < 0) {
            throw new IllegalArgumentException(
                    "Audit ID cannot be negative");
        }
        actor = requireText(actor, "actor", 192);
        action = requireText(action, "action", 128);
        if (targetId != null) {
            targetId = requireText(targetId, "targetId", 128);
        }
        detailsJson = Objects.requireNonNull(
                detailsJson, "detailsJson");
        if (detailsJson.isBlank()
                || detailsJson.length() > 1_048_576) {
            throw new IllegalArgumentException(
                    "Audit details are blank or too large");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public Optional<UUID> player() {
        return Optional.ofNullable(playerId);
    }

    public Optional<String> target() {
        return Optional.ofNullable(targetId);
    }

    public boolean persisted() {
        return auditId > 0;
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