package dev.xiaomu.crown.config.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 单个 gui/*.yml 的不可变、与 Minecraft 类型解耦的布局。 */
public record GuiLayout(
        String id,
        GuiScreenType screenType,
        String title,
        List<Integer> contentSlots,
        boolean fillerEnabled,
        GuiItemTemplate filler,
        Map<String, GuiButton> buttons,
        Map<String, GuiItemTemplate> itemVariants,
        Map<String, String> textValues
) {
    public GuiLayout {
        if (id == null || !id.matches("[a-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Invalid GUI layout ID");
        }
        screenType = Objects.requireNonNull(screenType, "screenType");
        title = Objects.requireNonNull(title, "title");
        if (title.isBlank() || title.length() > 2_048) {
            throw new IllegalArgumentException(
                    "GUI title is blank or too long");
        }

        contentSlots = List.copyOf(Objects.requireNonNull(
                contentSlots, "contentSlots"));
        if (Set.copyOf(contentSlots).size() != contentSlots.size()) {
            throw new IllegalArgumentException(
                    "GUI content slots contain duplicates");
        }
        for (int slot : contentSlots) {
            requireSlot(slot, screenType.slotCount(), "content");
        }

        filler = Objects.requireNonNull(filler, "filler");
        buttons = immutableButtons(buttons);
        itemVariants = immutableItems(itemVariants);
        textValues = immutableTextValues(textValues);

        var occupied = new LinkedHashMap<Integer, String>();
        for (GuiButton button : buttons.values()) {
            requireSlot(button.slot(), screenType.slotCount(), "button");
            if (contentSlots.contains(button.slot())) {
                throw new IllegalArgumentException(
                        "GUI button " + button.action()
                                + " overlaps content slot "
                                + button.slot());
            }
            String previous = occupied.putIfAbsent(
                    button.slot(), button.action());
            if (previous != null
                    && !allowedOverlay(previous, button.action())) {
                throw new IllegalArgumentException(
                        "GUI buttons " + previous + " and "
                                + button.action() + " share slot "
                                + button.slot());
            }
        }
    }

    private static Map<String, GuiButton> immutableButtons(
            Map<String, GuiButton> source
    ) {
        Objects.requireNonNull(source, "buttons");
        var copy = new LinkedHashMap<String, GuiButton>();
        source.forEach((key, button) -> {
            Objects.requireNonNull(key, "button key");
            Objects.requireNonNull(button, "button");
            if (!key.equals(button.action())) {
                throw new IllegalArgumentException(
                        "GUI button key and action differ");
            }
            if (copy.put(key, button) != null) {
                throw new IllegalArgumentException(
                        "Duplicate GUI button: " + key);
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, GuiItemTemplate> immutableItems(
            Map<String, GuiItemTemplate> source
    ) {
        Objects.requireNonNull(source, "itemVariants");
        var copy = new LinkedHashMap<String, GuiItemTemplate>();
        source.forEach((key, item) -> {
            if (key == null || !key.matches("[a-z0-9_-]{1,64}")) {
                throw new IllegalArgumentException(
                        "Invalid GUI item variant: " + key);
            }
            copy.put(key, Objects.requireNonNull(item, "item"));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, String> immutableTextValues(
            Map<String, String> source
    ) {
        Objects.requireNonNull(source, "textValues");
        var copy = new LinkedHashMap<String, String>();
        source.forEach((key, value) -> {
            if (key == null || !key.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("Invalid GUI text value: " + key);
            }
            copy.put(key, Objects.requireNonNull(value, "text value"));
        });
        return Collections.unmodifiableMap(copy);
    }

    public String textValue(String key) {
        Objects.requireNonNull(key, "key");
        return textValues.getOrDefault(key, key);
    }

    private static void requireSlot(
            int slot,
            int slotCount,
            String type
    ) {
        if (slot < 0 || slot >= slotCount) {
            throw new IllegalArgumentException(
                    "GUI " + type + " slot is out of range: " + slot);
        }
    }

    private static boolean allowedOverlay(String left, String right) {
        Set<String> pair = Set.of(left, right);
        return pair.equals(Set.of("confirm", "processing"))
                || pair.equals(Set.of("pay-mint", "processing"))
                || pair.equals(Set.of("pay-title-coin", "processing"));
    }
}