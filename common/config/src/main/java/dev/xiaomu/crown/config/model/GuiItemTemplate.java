package dev.xiaomu.crown.config.model;

import dev.xiaomu.crown.domain.catalog.NamespacedId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** GUI 物品的已验证样式模板。 */
public record GuiItemTemplate(
        String item,
        String name,
        List<String> lore,
        int amount,
        boolean glow,
        boolean hideTooltip,
        Integer customModelData,
        String sound,
        boolean closeAfterAction
) {
    private static final Pattern ITEM_VARIABLE =
            Pattern.compile("\\{[a-z0-9_]{1,64}}");

    public GuiItemTemplate {
        item = requireItem(item);
        name = requireText(name, "item name", 2_048);
        lore = List.copyOf(Objects.requireNonNull(lore, "lore"));
        if (lore.size() > 128
                || lore.stream().anyMatch(line ->
                line == null || line.length() > 4_096)) {
            throw new IllegalArgumentException("Invalid GUI item lore");
        }
        if (amount < 1 || amount > 99) {
            throw new IllegalArgumentException(
                    "GUI item amount must be between 1 and 99");
        }
        if (customModelData != null && customModelData < 0) {
            throw new IllegalArgumentException(
                    "Custom model data cannot be negative");
        }
        sound = Objects.requireNonNull(sound, "sound");
        if (!sound.isEmpty()) {
            NamespacedId.parse(sound);
        }
    }

    public boolean dynamicItem() {
        return ITEM_VARIABLE.matcher(item).matches();
    }

    public Optional<Integer> customModelDataValue() {
        return Optional.ofNullable(customModelData);
    }

    public Optional<String> soundId() {
        return sound.isEmpty() ? Optional.empty() : Optional.of(sound);
    }

    private static String requireItem(String value) {
        Objects.requireNonNull(value, "item");
        if (!ITEM_VARIABLE.matcher(value).matches()) {
            NamespacedId.parse(value);
        }
        return value;
    }

    private static String requireText(
            String value,
            String name,
            int maximumLength
    ) {
        Objects.requireNonNull(value, name);
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return value;
    }
}