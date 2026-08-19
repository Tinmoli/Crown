package dev.xiaomu.crown.runtime.purchase;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.xiaomu.crown.domain.catalog.DefinitionId;
import dev.xiaomu.crown.domain.catalog.DurationPolicy;
import dev.xiaomu.crown.domain.catalog.DurationType;
import dev.xiaomu.crown.domain.catalog.NamespacedId;
import dev.xiaomu.crown.storage.model.OwnedTitleKind;
import dev.xiaomu.crown.storage.model.ProductType;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 严格、带版本的订单称号快照 JSON 编解码器。 */
public final class TitleOrderSnapshotCodec {
    private static final int MAXIMUM_JSON_LENGTH = 1_048_576;
    private static final Set<String> ROOT_KEYS = Set.of(
            "schemaVersion",
            "entryId",
            "productType",
            "definitionId",
            "kind",
            "titleText",
            "titlePrefix",
            "titleSuffix",
            "source",
            "duration",
            "mintShopAccount");
    private static final Set<String> DURATION_KEYS =
            Set.of("type", "days");

    public String encode(TitleOrderSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        JsonObject root = new JsonObject();
        root.addProperty(
                "schemaVersion", snapshot.schemaVersion());
        root.addProperty(
                "entryId", snapshot.entryId().toString());
        root.addProperty(
                "productType", snapshot.productType().name());
        if (snapshot.definitionId() == null) {
            root.add("definitionId",
                    com.google.gson.JsonNull.INSTANCE);
        } else {
            root.addProperty(
                    "definitionId",
                    snapshot.definitionId().value());
        }
        root.addProperty("kind", snapshot.kind().name());
        root.addProperty("titleText", snapshot.titleText());
        root.addProperty("titlePrefix", snapshot.titlePrefix());
        root.addProperty("titleSuffix", snapshot.titleSuffix());
        root.addProperty("source", snapshot.source());
        if (snapshot.mintShopAccount() == null) {
            root.add("mintShopAccount",
                    com.google.gson.JsonNull.INSTANCE);
        } else {
            root.addProperty(
                    "mintShopAccount",
                    snapshot.mintShopAccount().serialized());
        }

        JsonObject duration = new JsonObject();
        duration.addProperty(
                "type", snapshot.duration().type().name());
        duration.addProperty(
                "days", snapshot.duration().days());
        root.add("duration", duration);
        return root.toString();
    }

    public TitleOrderSnapshot decode(String json) {
        Objects.requireNonNull(json, "json");
        if (json.isBlank()
                || json.length() > MAXIMUM_JSON_LENGTH) {
            throw invalid("Snapshot JSON is blank or too large", null);
        }

        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw invalid(
                        "Snapshot root must be an object", null);
            }
            JsonObject root = parsed.getAsJsonObject();
            requireExactKeys(root, ROOT_KEYS, "snapshot");

            JsonElement definitionElement =
                    root.get("definitionId");
            DefinitionId definition = definitionElement.isJsonNull()
                    ? null
                    : DefinitionId.of(requiredString(
                    root, "definitionId"));
            JsonElement shopElement =
                    root.get("mintShopAccount");
            NamespacedId mintShopAccount = shopElement.isJsonNull()
                    ? null
                    : NamespacedId.parse(requiredString(
                    root, "mintShopAccount"));

            JsonObject durationObject =
                    requiredObject(root, "duration");
            requireExactKeys(
                    durationObject,
                    DURATION_KEYS,
                    "snapshot duration");
            DurationPolicy duration = new DurationPolicy(
                    enumValue(
                            DurationType.class,
                            requiredString(
                                    durationObject, "type"),
                            "duration type"),
                    requiredInteger(durationObject, "days"));

            return new TitleOrderSnapshot(
                    requiredInteger(root, "schemaVersion"),
                    UUID.fromString(requiredString(
                            root, "entryId")),
                    enumValue(
                            ProductType.class,
                            requiredString(
                                    root, "productType"),
                            "product type"),
                    definition,
                    enumValue(
                            OwnedTitleKind.class,
                            requiredString(root, "kind"),
                            "owned title kind"),
                    requiredString(root, "titleText"),
                    requiredString(root, "titlePrefix"),
                    requiredString(root, "titleSuffix"),
                    requiredString(root, "source"),
                    duration,
                    mintShopAccount);
        } catch (SnapshotFormatException exception) {
            throw exception;
        } catch (JsonParseException
                 | IllegalArgumentException exception) {
            throw invalid(
                    "Invalid title order snapshot", exception);
        }
    }

    private static void requireExactKeys(
            JsonObject object,
            Set<String> expected,
            String name
    ) {
        if (!object.keySet().equals(expected)) {
            throw invalid(
                    name + " has missing or unknown fields", null);
        }
    }

    private static JsonObject requiredObject(
            JsonObject object,
            String key
    ) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonObject()) {
            throw invalid(key + " must be an object", null);
        }
        return value.getAsJsonObject();
    }

    private static String requiredString(
            JsonObject object,
            String key
    ) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw invalid(key + " must be a string", null);
        }
        return value.getAsString();
    }

    private static int requiredInteger(
            JsonObject object,
            String key
    ) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(key + " must be an integer", null);
        }
        try {
            return new BigDecimal(
                    value.getAsString()).intValueExact();
        } catch (NumberFormatException
                 | ArithmeticException exception) {
            throw invalid(key + " must be an integer", exception);
        }
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type,
            String value,
            String name
    ) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw invalid("Invalid " + name, exception);
        }
    }

    private static SnapshotFormatException invalid(
            String message,
            Throwable cause
    ) {
        return cause == null
                ? new SnapshotFormatException(message)
                : new SnapshotFormatException(message, cause);
    }

    /** 表示持久化订单快照损坏或使用不支持的版本。 */
    public static final class SnapshotFormatException
            extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        SnapshotFormatException(String message) {
            super(message);
        }

        SnapshotFormatException(
                String message,
                Throwable cause
        ) {
            super(message, cause);
        }
    }
}