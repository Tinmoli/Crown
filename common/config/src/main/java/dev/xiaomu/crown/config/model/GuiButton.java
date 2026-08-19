package dev.xiaomu.crown.config.model;

import java.util.Objects;

/** GUI 中绑定稳定动作名的固定槽位按钮。 */
public record GuiButton(
        String action,
        int slot,
        GuiItemTemplate item
) {
    public GuiButton {
        action = Objects.requireNonNull(action, "action");
        if (!action.matches("[a-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException(
                    "Invalid GUI action: " + action);
        }
        if (slot < 0) {
            throw new IllegalArgumentException(
                    "GUI button slot cannot be negative");
        }
        item = Objects.requireNonNull(item, "item");
    }
}