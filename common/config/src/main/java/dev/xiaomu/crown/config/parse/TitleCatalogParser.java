package dev.xiaomu.crown.config.parse;

import dev.xiaomu.crown.config.io.ConfigValueException;
import dev.xiaomu.crown.config.io.YamlValues;
import dev.xiaomu.crown.config.model.CatalogSettings;
import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.domain.catalog.DefinitionId;
import dev.xiaomu.crown.domain.catalog.DurationPolicy;
import dev.xiaomu.crown.domain.catalog.NamespacedId;
import dev.xiaomu.crown.domain.catalog.PaymentPolicy;
import dev.xiaomu.crown.domain.catalog.SalePolicy;
import dev.xiaomu.crown.domain.catalog.TitleContent;
import dev.xiaomu.crown.domain.catalog.TitleDefinition;
import dev.xiaomu.crown.domain.text.CrownTextParser;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 把 titles.yml 的开放映射转换为完整商品目录。 */
public final class TitleCatalogParser {
    public CatalogSettings parse(
            Map<String, Object> root,
            CoreSettings.Safety safety
    ) {
        return parse(root, safety, NamespacedId.parse("mint:coin"));
    }

    public CatalogSettings parse(
            Map<String, Object> root,
            CoreSettings.Safety safety,
            NamespacedId mintCurrency
    ) {
        Map<String, Object> configured = YamlValues.map(root, "titles");
        CrownTextParser textParser = new CrownTextParser(
                safety.serverTextPolicy());

        var definitions =
                new LinkedHashMap<DefinitionId, TitleDefinition>();
        for (Map.Entry<String, Object> entry : configured.entrySet()) {
            String sourceId = entry.getKey();
            String path = "titles." + sourceId;
            DefinitionId id = ConfigParsing.wrap(
                    path, () -> DefinitionId.of(sourceId));
            Map<String, Object> values =
                    ConfigParsing.mapValue(entry.getValue(), path);
            TitleDefinition definition = parseDefinition(
                    id, values, path, safety, mintCurrency, textParser);
            if (definitions.put(id, definition) != null) {
                throw new ConfigValueException(
                        path, "duplicate title definition");
            }
        }
        return new CatalogSettings(definitions);
    }

    private static TitleDefinition parseDefinition(
            DefinitionId id,
            Map<String, Object> values,
            String path,
            CoreSettings.Safety safety,
            NamespacedId mintCurrency,
            CrownTextParser textParser
    ) {
        String prefix = optionalString(values, "prefix", "");
        String text = YamlValues.nonBlankString(values, "text");
        String suffix = optionalString(values, "suffix", "");
        TitleContent content = CoreSettingsParser.titleContent(
                textParser, prefix, text, suffix,
                path, safety.maximumVisibleTitleLength());

        List<String> description = optionalStringList(values, "description");
        for (int index = 0;
             index < description.size();
             index++) {
            String line = description.get(index);
            CoreSettingsParser.parseText(
                    textParser, line,
                    path + ".description[" + index + ']');
        }

        DurationPolicy duration = parseDuration(values, path);
        List<PaymentPolicy> paymentOptions = parsePaymentOptions(
                values, path, mintCurrency);
        PaymentPolicy payment = paymentOptions.getFirst();
        SalePolicy sale = parseSale(values, path + ".sale");

        return ConfigParsing.wrap(path, () -> new TitleDefinition(
                id,
                optionalBoolean(values, "enabled", true),
                optionalBoolean(values, "visible", true),
                optionalString(values, "category", "normal"),
                content,
                NamespacedId.parse(YamlValues.nonBlankString(
                        values, "icon")),
                description,
                duration,
                payment,
                paymentOptions,
                optionalString(values, "requirement.permission", ""),
                optionalBoolean(values,
                        "requirement.deny-if-missing-permission", true),
                sale));
    }

    private static List<PaymentPolicy> parsePaymentOptions(
            Map<String, Object> values,
            String path,
            NamespacedId mintCurrency
    ) {
        Object raw = YamlValues.findNullable(values, "payment-options");
        if (raw == null) {
            throw new ConfigValueException(
                    path + ".payment-options", "required value is missing");
        }
        Map<String, Object> options = ConfigParsing.mapValue(
                raw, path + ".payment-options");
        var result = new ArrayList<PaymentPolicy>();
        for (Map.Entry<String, Object> option : options.entrySet()) {
            String optionPath = path + ".payment-options." + option.getKey();
            switch (option.getKey()) {
                case "free" -> {
                    if (!(option.getValue() instanceof Boolean enabled)
                            || !enabled) {
                        throw new ConfigValueException(optionPath,
                                "free must be true when present");
                    }
                    result.add(PaymentPolicy.free());
                }
                case "mint" -> {
                    Map<String, Object> payment = ConfigParsing.mapValue(
                            option.getValue(), optionPath);
                    if (YamlValues.findNullable(payment, "currency-id") != null) {
                        throw new ConfigValueException(optionPath + ".currency-id",
                                "Mint currency is configured globally at purchase.mint-currency");
                    }
                    result.add(PaymentPolicy.mint(
                            mintCurrency,
                            ConfigParsing.decimal(
                                    YamlValues.nonBlankString(payment, "price"),
                                    optionPath + ".price")));
                }
                case "title-coin" -> {
                    Map<String, Object> payment = ConfigParsing.mapValue(
                            option.getValue(), optionPath);
                    result.add(PaymentPolicy.titleCoin(Long.parseLong(
                            YamlValues.nonBlankString(payment, "price"))));
                }
                default -> throw new ConfigValueException(
                        optionPath, "unknown payment option");
            }
        }
        if (result.isEmpty()) {
            throw new ConfigValueException(
                path + ".payment-options",
                    "at least one payment option is required");
        }
        return result;
    }

    private static DurationPolicy parseDuration(
            Map<String, Object> values,
            String path
    ) {
        Object days = YamlValues.findNullable(values, "duration.days");
        if (days == null) {
            return new DurationPolicy(dev.xiaomu.crown.domain.catalog.DurationType.PERMANENT, 0);
        }
        if (YamlValues.integer(values, "duration.days") == 0) {
            return new DurationPolicy(dev.xiaomu.crown.domain.catalog.DurationType.PERMANENT, 0);
        }
        return ConfigParsing.wrap(path + ".duration", () ->
                new DurationPolicy(dev.xiaomu.crown.domain.catalog.DurationType.LIMITED,
                        YamlValues.integer(values, "duration.days")));
    }

    private static SalePolicy parseSale(
            Map<String, Object> values,
            String path
    ) {
        Instant startsAt = ConfigParsing.nullableInstant(
                YamlValues.findNullable(values, "sale.starts-at"),
                path + ".starts-at");
        Instant endsAt = ConfigParsing.nullableInstant(
                YamlValues.findNullable(values, "sale.ends-at"),
                path + ".ends-at");
        return ConfigParsing.wrap(path, () -> new SalePolicy(
                startsAt,
                endsAt,
                optionalLong(values, "sale.global-stock", -1L),
                optionalInt(values, "sale.per-player-limit", -1)));
    }

    private static long optionalLong(Map<String, Object> values, String path, long fallback) {
        return YamlValues.findNullable(values, path) == null ? fallback : YamlValues.longValue(values, path);
    }

    private static int optionalInt(Map<String, Object> values, String path, int fallback) {
        return YamlValues.findNullable(values, path) == null ? fallback : YamlValues.integer(values, path);
    }

    private static String optionalString(
            Map<String, Object> values, String path, String fallback
    ) {
        Object value = YamlValues.findNullable(values, path);
        return value == null ? fallback : YamlValues.string(values, path);
    }

    private static boolean optionalBoolean(
            Map<String, Object> values, String path, boolean fallback
    ) {
        Object value = YamlValues.findNullable(values, path);
        return value == null ? fallback : YamlValues.bool(values, path);
    }

    private static List<String> optionalStringList(
            Map<String, Object> values, String path
    ) {
        return YamlValues.findNullable(values, path) == null
                ? List.of() : YamlValues.stringList(values, path);
    }
}