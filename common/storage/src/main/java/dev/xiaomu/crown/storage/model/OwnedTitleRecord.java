package dev.xiaomu.crown.storage.model;

import dev.xiaomu.crown.domain.catalog.DefinitionId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 购买或发放时保存完整内容快照的仓库条目。 */
public record OwnedTitleRecord(
        UUID entryId,
        UUID playerId,
        DefinitionId definitionId,
        OwnedTitleKind kind,
        String titleText,
        String titlePrefix,
        String titleSuffix,
        String source,
        Instant acquiredAt,
        Instant expiresAt,
        UUID purchaseOrderId,
        OwnedTitleStatus status,
        Instant deletedAt,
        String deletedBy
) {
    public OwnedTitleRecord {
        entryId = Objects.requireNonNull(entryId, "entryId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        kind = Objects.requireNonNull(kind, "kind");
        titleText = requireSource(titleText, "titleText", false);
        titlePrefix = requireSource(titlePrefix, "titlePrefix", true);
        titleSuffix = requireSource(titleSuffix, "titleSuffix", true);
        source = requireSource(source, "source", false);
        acquiredAt = Objects.requireNonNull(acquiredAt, "acquiredAt");
        status = Objects.requireNonNull(status, "status");
        if (expiresAt != null && !expiresAt.isAfter(acquiredAt)) {
            throw new IllegalArgumentException(
                    "Title expiry must follow acquisition");
        }
        if (status == OwnedTitleStatus.ACTIVE
                && (deletedAt != null || deletedBy != null)) {
            throw new IllegalArgumentException(
                    "Active title cannot have deletion metadata");
        }
        if (status == OwnedTitleStatus.DELETED
                && (deletedAt == null || deletedBy == null)) {
            throw new IllegalArgumentException(
                    "Deleted title requires deletion metadata");
        }
        if (deletedBy != null) {
            deletedBy = requireSource(deletedBy, "deletedBy", false);
        }
    }

    public Optional<DefinitionId> definition() {
        return Optional.ofNullable(definitionId);
    }

    public Optional<Instant> expiry() {
        return Optional.ofNullable(expiresAt);
    }

    public Optional<UUID> orderId() {
        return Optional.ofNullable(purchaseOrderId);
    }

    public boolean expiredAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return expiresAt != null && !instant.isBefore(expiresAt);
    }

    private static String requireSource(
            String value,
            String name,
            boolean allowEmpty
    ) {
        Objects.requireNonNull(value, name);
        if ((!allowEmpty && value.isBlank())
                || value.length() > 16_384
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}