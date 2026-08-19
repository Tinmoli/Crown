package dev.xiaomu.crown.config.parse;

import dev.xiaomu.crown.config.io.ConfigValueException;
import dev.xiaomu.crown.config.io.YamlValues;
import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.config.model.GuiButton;
import dev.xiaomu.crown.config.model.GuiItemTemplate;
import dev.xiaomu.crown.config.model.GuiLayout;
import dev.xiaomu.crown.config.model.GuiScreenType;
import dev.xiaomu.crown.domain.text.CrownTextParser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析并验证单个 gui/*.yml。 */
public final class GuiLayoutParser {
    private static final Pattern SLOT =
            Pattern.compile("(\\d+)(?:-(\\d+))?");

    public GuiLayout parse(
            String id,
            Map<String, Object> root,
            CoreSettings.Safety safety
    ) {
        CrownTextParser textParser = new CrownTextParser(
                safety.serverTextPolicy());
        GuiScreenType screenType = ConfigParsing.enumValue(
                GuiScreenType.class,
                YamlValues.nonBlankString(root, "screen.type"),
                id + ".screen.type");
        String title = YamlValues.nonBlankString(root, "screen.title");
        validateText(textParser, title, id + ".screen.title");

        List<Integer> contentSlots = parseContentSlots(
                root, id + ".content-slots", screenType.slotCount());

        Map<String, Object> fillerValues =
                YamlValues.map(root, "filler");
        boolean fillerEnabled = YamlValues.bool(
                fillerValues, "enabled");
        GuiItemTemplate filler = parseItem(
                fillerValues, id + ".filler", textParser);

        var buttons = new LinkedHashMap<String, GuiButton>();
        Map<String, Object> rawButtons =
                YamlValues.map(root, "buttons");
        rawButtons.forEach((action, value) -> {
            String path = id + ".buttons." + action;
            Map<String, Object> item =
                    ConfigParsing.mapValue(value, path);
            int slot = YamlValues.integer(item, "slot");
            GuiItemTemplate template = parseItem(
                    item, path, textParser);
            buttons.put(action, ConfigParsing.wrap(
                    path, () -> new GuiButton(
                            action, slot, template)));
        });

        var variants = new LinkedHashMap<String, GuiItemTemplate>();
        Object rawVariants = YamlValues.findNullable(root, "title-items");
        if (rawVariants != null) {
            Map<String, Object> mapped = ConfigParsing.mapValue(
                    rawVariants, id + ".title-items");
            mapped.forEach((variant, value) -> {
                String path = id + ".title-items." + variant;
                variants.put(variant, parseItem(
                        ConfigParsing.mapValue(value, path),
                        path,
                        textParser));
            });
        }

        var textValues = new LinkedHashMap<String, String>();
        Object rawTextValues = YamlValues.findNullable(root, "text-values");
        if (rawTextValues != null) {
            Map<String, Object> mapped = ConfigParsing.mapValue(
                    rawTextValues, id + ".text-values");
            mapped.forEach((key, value) -> textValues.put(key,
                    ConfigParsing.wrap(id + ".text-values." + key, () -> {
                        String text = ConfigParsing.stringValue(value,
                                id + ".text-values." + key);
                        validateText(textParser, text,
                                id + ".text-values." + key);
                        return text;
                    })));
        }

        return ConfigParsing.wrap(id, () -> new GuiLayout(
                id,
                screenType,
                title,
                contentSlots,
                fillerEnabled,
                filler,
                buttons,
                variants,
                textValues));
    }

    private static GuiItemTemplate parseItem(
            Map<String, Object> values,
            String path,
            CrownTextParser parser
    ) {
        String item = YamlValues.nonBlankString(values, "item");
        String name = YamlValues.string(values, "name");
        List<String> lore = optionalStringList(values, "lore");
        validateText(parser, name, path + ".name");
        for (int index = 0; index < lore.size(); index++) {
            validateText(
                    parser,
                    lore.get(index),
                    path + ".lore[" + index + ']');
        }

        int amount = optionalInteger(values, "amount", 1);
        boolean glow = optionalBoolean(values, "glow", false);
        boolean hideTooltip = optionalBoolean(
                values, "hide-tooltip", false);
        Integer customModelData = optionalNullableInteger(
                values, "custom-model-data");
        String sound = optionalString(values, "sound", "");
        boolean closeAfterAction = optionalBoolean(
                values, "close-after-action", false);

        return ConfigParsing.wrap(path, () -> new GuiItemTemplate(
                item,
                name,
                lore,
                amount,
                glow,
                hideTooltip,
                customModelData,
                sound,
                closeAfterAction));
    }

    private static List<Integer> parseContentSlots(
            Map<String, Object> root,
            String path,
            int slotCount
    ) {
        Object configured = YamlValues.findNullable(
                root, "content-slots");
        if (configured == null) {
            return List.of();
        }
        if (!(configured instanceof List<?> list)) {
            throw new ConfigValueException(path, "expected list");
        }

        var slots = new LinkedHashSet<Integer>();
        for (int index = 0; index < list.size(); index++) {
            Object value = list.get(index);
            if (!(value instanceof String expression)) {
                throw new ConfigValueException(
                        path + '[' + index + ']',
                        "expected slot or range string");
            }
            Matcher matcher = SLOT.matcher(expression);
            if (!matcher.matches()) {
                throw new ConfigValueException(
                        path + '[' + index + ']',
                        "invalid slot range");
            }
            int start = parseSlot(
                    matcher.group(1), path + '[' + index + ']');
            int end = matcher.group(2) == null
                    ? start
                    : parseSlot(
                            matcher.group(2),
                            path + '[' + index + ']');
            if (start > end || end >= slotCount) {
                throw new ConfigValueException(
                        path + '[' + index + ']',
                        "slot range is outside the screen");
            }
            for (int slot = start; slot <= end; slot++) {
                if (!slots.add(slot)) {
                    throw new ConfigValueException(
                            path, "content slot is repeated: " + slot);
                }
            }
        }
        return List.copyOf(slots);
    }

    private static int parseSlot(String value, String path) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new ConfigValueException(
                    path, "slot is out of range", exception);
        }
    }

    private static List<String> optionalStringList(
            Map<String, Object> values,
            String key
    ) {
        if (!values.containsKey(key)) {
            return List.of();
        }
        return YamlValues.stringList(values, key);
    }

    private static String optionalString(
            Map<String, Object> values,
            String key,
            String fallback
    ) {
        if (!values.containsKey(key)) {
            return fallback;
        }
        return YamlValues.string(values, key);
    }

    private static boolean optionalBoolean(
            Map<String, Object> values,
            String key,
            boolean fallback
    ) {
        if (!values.containsKey(key)) {
            return fallback;
        }
        return YamlValues.bool(values, key);
    }

    private static int optionalInteger(
            Map<String, Object> values,
            String key,
            int fallback
    ) {
        if (!values.containsKey(key)) {
            return fallback;
        }
        return YamlValues.integer(values, key);
    }

    private static Integer optionalNullableInteger(
            Map<String, Object> values,
            String key
    ) {
        if (!values.containsKey(key) || values.get(key) == null) {
            return null;
        }
        Object value = values.get(key);
        if (!(value instanceof Number number)
                || value instanceof Float
                || value instanceof Double) {
            throw new ConfigValueException(key, "expected integer");
        }
        try {
            return new BigDecimal(number.toString())
                    .toBigIntegerExact()
                    .intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ConfigValueException(
                    key, "integer is out of range", exception);
        }
    }

    private static void validateText(
            CrownTextParser parser,
            String source,
            String path
    ) {
        CoreSettingsParser.parseText(parser, source, path);
    }
}