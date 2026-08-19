package dev.xiaomu.crown.fabric.text;

import dev.xiaomu.crown.domain.text.StyledSegment;
import dev.xiaomu.crown.domain.text.StyledText;
import dev.xiaomu.crown.domain.text.TextDecoration;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.Objects;

/** 将映射中立的 Crown StyledText 转换为 26.x 原版 Component。 */
public final class FabricTextAdapter {
    public Component component(StyledText text) {
        Objects.requireNonNull(text, "text");
        MutableComponent root = Component.empty();
        for (StyledSegment segment : text.segments()) {
            root.append(Component.literal(segment.text())
                    .setStyle(style(segment)));
        }
        return root;
    }

    public Component literal(String value) {
        return Component.literal(Objects.requireNonNull(value, "value"));
    }

    private static Style style(StyledSegment segment) {
        var source = segment.style();
        Style result = Style.EMPTY;
        if (source.color() != null) {
            result = result.withColor(
                    TextColor.fromRgb(source.color().packed()));
        }
        var decorations = source.decorations();
        return result
                .withBold(decorations.contains(TextDecoration.BOLD))
                .withItalic(decorations.contains(TextDecoration.ITALIC))
                .withUnderlined(
                        decorations.contains(TextDecoration.UNDERLINED))
                .withStrikethrough(
                        decorations.contains(TextDecoration.STRIKETHROUGH))
                .withObfuscated(
                        decorations.contains(TextDecoration.OBFUSCATED));
    }
}