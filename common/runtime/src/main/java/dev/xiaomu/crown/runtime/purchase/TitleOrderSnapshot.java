package dev.xiaomu.crown.runtime.purchase;

import dev.xiaomu.crown.domain.catalog.DefinitionId;
import dev.xiaomu.crown.domain.catalog.DurationPolicy;
import dev.xiaomu.crown.domain.catalog.TitleContent;
import dev.xiaomu.crown.domain.catalog.TitleDefinition;
import dev.xiaomu.crown.storage.model.OwnedTitleKind;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import dev.xiaomu.crown.storage.model.OwnedTitleStatus;
import dev.xiaomu.crown.storage.model.ProductType;
import dev.xiaomu.crown.storage.model.PurchaseOrderRecord;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 随订单持久化的不可变称号发放快照。配置改价或删除商品不会影响恢复。
 */
public record TitleOrderSnapshot(
        int schemaVersion,
        UUID entryId,
        ProductType productType,
        DefinitionId definitionId,
        OwnedTitleKind kind,
        String titleText,
        String titlePrefix,
        String titleSuffix,
        String source,
        DurationPolicy duration
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final int MAXIMUM_SOURCE_LENGTH = 16_384;

    public TitleOrderSnapshot {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported title order snapshot version");
        }
        entryId = Objects.requireNonNull(entryId, "entryId");
        productType = Objects.requireNonNull(
                productType, "productType");
        kind = Objects.requireNonNull(kind, "kind");
        titleText = requireSource(
                titleText, "titleText", false);
        titlePrefix = requireSource(
                titlePrefix, "titlePrefix", true);
        titleSuffix = requireSource(
                titleSuffix, "titleSuffix", true);
        source = requireSource(source, "source", false);
        duration = Objects.requireNonNull(duration, "duration");

        if (productType == ProductType.CATALOG
                && (definitionId == null
                || kind != OwnedTitleKind.CATALOG)) {
            throw new IllegalArgumentException(
                    "Catalog snapshot has inconsistent identity");
        }
        if (productType == ProductType.CUSTOM
                && (definitionId != null
                || kind != OwnedTitleKind.CUSTOM)) {
            throw new IllegalArgumentException(
                    "Custom snapshot has inconsistent identity");
        }
    }

    public static TitleOrderSnapshot catalog(
            UUID entryId,
            TitleDefinition definition
    ) {
        Objects.requireNonNull(definition, "definition");
        return fromContent(
                entryId,
                ProductType.CATALOG,
                definition.id(),
                OwnedTitleKind.CATALOG,
                "catalog:" + definition.id().value(),
                definition.content(),
                definition.duration());
    }

    public static TitleOrderSnapshot custom(
            UUID entryId,
            TitleContent content,
            DurationPolicy duration
    ) {
        return fromContent(
                entryId,
                ProductType.CUSTOM,
                null,
                OwnedTitleKind.CUSTOM,
                "custom",
                content,
                duration);
    }

    public boolean matches(PurchaseOrderRecord order) {
        Objects.requireNonNull(order, "order");
        return productType == order.productType()
                && Objects.equals(
                definitionId, order.definitionId());
    }

    public OwnedTitleRecord toOwnedTitle(
            PurchaseOrderRecord order,
            Instant acquiredAt
    ) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        if (!matches(order)) {
            throw new IllegalArgumentException(
                    "Title snapshot does not match purchase order");
        }
        return new OwnedTitleRecord(
                entryId,
                order.playerId(),
                definitionId,
                kind,
                titleText,
                titlePrefix,
                titleSuffix,
                source,
                acquiredAt,
                duration.expiresAt(acquiredAt).orElse(null),
                order.orderId(),
                OwnedTitleStatus.ACTIVE,
                null,
                null);
    }

    private static TitleOrderSnapshot fromContent(
            UUID entryId,
            ProductType productType,
            DefinitionId definitionId,
            OwnedTitleKind kind,
            String source,
            TitleContent content,
            DurationPolicy duration
    ) {
        Objects.requireNonNull(content, "content");
        return new TitleOrderSnapshot(
                CURRENT_SCHEMA_VERSION,
                entryId,
                productType,
                definitionId,
                kind,
                content.textSource(),
                content.prefixSource(),
                content.suffixSource(),
                source,
                duration);
    }

    private static String requireSource(
            String value,
            String name,
            boolean allowEmpty
    ) {
        Objects.requireNonNull(value, name);
        if ((!allowEmpty && value.isBlank())
                || value.length() > MAXIMUM_SOURCE_LENGTH
                || value.codePoints().anyMatch(
                Character::isISOControl)) {
            throw new IllegalArgumentException(
                    name + " is invalid");
        }
        return value;
    }
}
