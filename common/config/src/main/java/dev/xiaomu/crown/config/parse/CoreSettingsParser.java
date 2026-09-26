package dev.xiaomu.crown.config.parse;

import dev.xiaomu.crown.config.io.ConfigValueException;
import dev.xiaomu.crown.config.io.YamlValues;
import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.config.model.DisplayMode;
import dev.xiaomu.crown.domain.catalog.DurationPolicy;
import dev.xiaomu.crown.domain.catalog.DurationType;
import dev.xiaomu.crown.domain.catalog.NamespacedId;
import dev.xiaomu.crown.domain.catalog.PaymentPolicy;
import dev.xiaomu.crown.domain.catalog.TitleContent;
import dev.xiaomu.crown.domain.text.CrownTextParser;
import dev.xiaomu.crown.domain.text.StyledText;

import java.time.Duration;
import java.util.Map;

/** 把同步后的 config.yml 映射转换为不可变核心设置。 */
public final class CoreSettingsParser {
    public CoreSettings parse(Map<String, Object> root) {
        int maximumSource = YamlValues.integer(
                root, "safety.maximum-title-source-length");
        int maximumVisible = YamlValues.integer(
                root, "safety.maximum-visible-title-length");

        CoreSettings.Safety safety = ConfigParsing.wrap(
                "safety", () -> new CoreSettings.Safety(
                        maximumSource,
                        maximumVisible,
                        YamlValues.integer(
                                root,
                                "safety.maximum-gui-open-count-per-player")));

        CrownTextParser parser = new CrownTextParser(
                safety.serverTextPolicy());

        CoreSettings.DefaultTitle defaultTitle =
                parseDefaultTitle(root, parser, maximumVisible);
        CoreSettings.TitleCoin titleCoin = ConfigParsing.wrap(
                "title-coin", () -> new CoreSettings.TitleCoin(
                        YamlValues.nonBlankString(root, "title-coin.name"),
                        YamlValues.nonBlankString(root, "title-coin.symbol"),
                        YamlValues.nonBlankString(root, "title-coin.format"),
                        YamlValues.longValue(
                                root, "title-coin.maximum-balance")));

        CoreSettings.CustomTitle custom = parseCustomTitle(
                root, parser, safety);
        CoreSettings.Display display = parseDisplay(root);
        CoreSettings.PermissionFallback permissions =
                CoreSettings.PermissionFallback.defaults();

        CoreSettings.Purchase purchase = ConfigParsing.wrap(
                "purchase", () -> new CoreSettings.Purchase(
                        YamlValues.integer(root,
                                "purchase.maximum-pending-orders-per-player"),
                        YamlValues.integer(root, "purchase.maximum-owned-titles-per-player")));

        return ConfigParsing.wrap("config.yml", () -> new CoreSettings(
                YamlValues.nonBlankString(root, "language"),
                defaultTitle,
                titleCoin,
                custom,
                display,
                permissions,
                purchase,
                ConfigParsing.wrap("deletion", () -> new CoreSettings.Deletion(
                        YamlValues.bool(root, "deletion.refund-enabled"),
                        YamlValues.integer(root, "deletion.refund-percent"),
                        YamlValues.bool(root, "deletion.refund-expired"))),
                safety));
    }

    private static CoreSettings.DefaultTitle parseDefaultTitle(
            Map<String, Object> root,
            CrownTextParser parser,
            int maximumVisible
    ) {
        String prefix = YamlValues.string(root, "default-title.prefix");
        String text = YamlValues.nonBlankString(root, "default-title.text");
        String suffix = YamlValues.string(root, "default-title.suffix");
        TitleContent content = titleContent(
                parser, prefix, text, suffix,
                "default-title", maximumVisible);

        return ConfigParsing.wrap("default-title",
                () -> new CoreSettings.DefaultTitle(
                        YamlValues.bool(root, "default-title.enabled"),
                        YamlValues.bool(
                                root,
                                "default-title.equip-for-new-player"),
                        content,
                        NamespacedId.parse(YamlValues.nonBlankString(
                                root, "default-title.icon"))));
    }

    private static CoreSettings.CustomTitle parseCustomTitle(
            Map<String, Object> root,
            CrownTextParser serverParser,
            CoreSettings.Safety safety
    ) {
        DurationPolicy duration = parseDuration(root, "custom-title.duration");
        PaymentPolicy payment = parsePrice(root, "custom-title.price");

        String prefixSource = YamlValues.string(
                root, "custom-title.prefix");
        String suffixSource = YamlValues.string(
                root, "custom-title.suffix");
        StyledText prefix = parseText(
                serverParser, prefixSource, "custom-title.prefix");
        StyledText suffix = parseText(
                serverParser, suffixSource, "custom-title.suffix");

        int minimumLength = YamlValues.integer(
                root, "custom-title.minimum-length");
        int maximumLength = YamlValues.integer(
                root, "custom-title.maximum-length");
        if (maximumLength > safety.maximumVisibleTitleLength()) {
            throw new ConfigValueException(
                    "custom-title.maximum-length",
                    "cannot exceed safety.maximum-visible-title-length");
        }

        CoreSettings.CustomTitle result = ConfigParsing.wrap(
                "custom-title", () -> new CoreSettings.CustomTitle(
                        YamlValues.bool(root, "custom-title.enabled"),
                        duration,
                        payment,
                        prefixSource,
                        suffixSource,
                        prefix,
                        suffix,
                        minimumLength,
                        maximumLength,
                        YamlValues.bool(root, "custom-title.allow-rgb"),
                        YamlValues.bool(root, "custom-title.allow-gradient"),
                        YamlValues.bool(
                                root, "custom-title.allow-formatting"),
                        Duration.ofSeconds(YamlValues.integer(
                                root,
                                "custom-title.input-timeout-seconds")),
                        YamlValues.stringList(
                                root, "custom-title.cancel-keywords"),
                        YamlValues.stringList(
                                root, "custom-title.forbidden-words")));

        int wrapperLength = prefix.visibleCodePointCount()
                + suffix.visibleCodePointCount()
                + result.maximumLength();
        if (wrapperLength > safety.maximumVisibleTitleLength()) {
            throw new ConfigValueException(
                    "custom-title",
                    "prefix, body and suffix can exceed the global "
                            + "visible title length");
        }
        return result;
    }

    private static CoreSettings.Display parseDisplay(
            Map<String, Object> root
    ) {
        return ConfigParsing.wrap("display", () -> new CoreSettings.Display(
                displayMode(root, "display.channels.chat",
                        "display.direct.chat"),
                displayMode(root, "display.channels.tab",
                        "display.direct.tab"),
                displayMode(root, "display.channels.nametag",
                        "display.direct.nametag"),
                direct(root, "display.direct.chat"),
                direct(root, "display.direct.tab"),
                direct(root, "display.direct.nametag")));
    }

    private static DisplayMode displayMode(
            Map<String, Object> root,
            String channelPath,
            String legacyDirectPath
    ) {
        Object configured = YamlValues.findNullable(root, channelPath);
        if (configured != null) {
            if (!(configured instanceof String value)) {
                throw new ConfigValueException(channelPath,
                        "must be a string");
            }
            return DisplayMode.parse(value, channelPath);
        }
        boolean direct = YamlValues.bool(root, legacyDirectPath + ".enabled");
        boolean placeholderFirst = YamlValues.bool(
                root, "display.placeholder-first");
        return direct
                ? DisplayMode.VANILLA
                : (placeholderFirst
                        ? DisplayMode.PLACEHOLDER
                        : DisplayMode.DISABLED);
    }

    private static CoreSettings.DisplayTemplate direct(
            Map<String, Object> root,
            String path
    ) {
        return new CoreSettings.DisplayTemplate(
                YamlValues.nonBlankString(root, path + ".template"));
    }

    static DurationPolicy parseDuration(
            Map<String, Object> root,
            String path
    ) {
        DurationType type = ConfigParsing.enumValue(
                DurationType.class,
                YamlValues.nonBlankString(root, path + ".type"),
                path + ".type");
        int days = YamlValues.integer(root, path + ".days");
        return ConfigParsing.wrap(path,
                () -> new DurationPolicy(type, days));
    }

    static PaymentPolicy parsePrice(Map<String, Object> values, String path) {
        return ConfigParsing.wrap(path, () -> PaymentPolicy.titleCoin(YamlValues.longValue(values, path)));
    }

    static TitleContent titleContent(
            CrownTextParser parser,
            String prefix,
            String text,
            String suffix,
            String path,
            int maximumVisible
    ) {
        StyledText parsedPrefix = parseText(
                parser, prefix, path + ".prefix");
        StyledText parsedText = parseText(
                parser, text, path + ".text");
        StyledText parsedSuffix = parseText(
                parser, suffix, path + ".suffix");
        TitleContent content = ConfigParsing.wrap(path,
                () -> new TitleContent(
                        prefix, text, suffix,
                        parsedPrefix, parsedText, parsedSuffix));
        if (content.fullText().visibleCodePointCount() > maximumVisible) {
            throw new ConfigValueException(
                    path, "full title exceeds maximum visible length");
        }
        return content;
    }

    static StyledText parseText(
            CrownTextParser parser,
            String source,
            String path
    ) {
        try {
            return parser.parse(source);
        } catch (IllegalArgumentException exception) {
            throw new ConfigValueException(
                    path, exception.getMessage(), exception);
        }
    }
}
