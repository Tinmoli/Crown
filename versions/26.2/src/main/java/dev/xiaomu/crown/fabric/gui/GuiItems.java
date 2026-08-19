package dev.xiaomu.crown.fabric.gui;

import dev.xiaomu.crown.config.model.GuiItemTemplate;
import dev.xiaomu.crown.fabric.text.CrownMessages;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把配置中的 {@link GuiItemTemplate} 渲染为 SGUI 元素。
 *
 * <p>物品 ID 与文本中的 {variable} 占位在此统一替换为字面量，不做二次
 * 格式解析。斜体默认关闭以贴近原版展示。</p>
 */
public final class GuiItems {
    private final CrownMessages messages;

    public GuiItems(CrownMessages messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    public GuiElementBuilder build(
            GuiItemTemplate template,
            Map<String, String> variables
    ) {
        return build(template, variables, Map.of());
    }

    /**
     * 构建 GUI 元素，并支持把某些 lore 行按“多行变量”展开。
     *
     * <p>当一整行 lore 恰好等于 {@code {key}} 且该 key 出现在
     * {@code multiline} 中时，会展开为对应的多行文本；其余行按普通
     * 单值变量替换处理。用于把称号描述等多行内容注入单个占位。</p>
     */
    public GuiElementBuilder build(
            GuiItemTemplate template,
            Map<String, String> variables,
            Map<String, List<String>> multiline
    ) {
        if (template == null) {
            return new GuiElementBuilder(Items.BARRIER);
        }
        Objects.requireNonNull(variables, "variables");
        Objects.requireNonNull(multiline, "multiline");

        Item item;
        try {
            item = resolveItem(substitute(template.item(), variables));
        } catch (RuntimeException exception) {
            item = Items.BARRIER;
        }
        GuiElementBuilder builder = new GuiElementBuilder(item)
                .setCount(template.amount());

        builder.setName(disableItalic(messages.renderRaw(
                substitute(template.name(), variables))));

        List<Component> lore = new ArrayList<>(template.lore().size());
        for (String line : template.lore()) {
            String key = multilineKey(line, multiline);
            if (key != null) {
                for (String expanded : multiline.get(key)) {
                    lore.add(disableItalic(messages.renderRaw(
                            substitute(expanded, variables))));
                }
                continue;
            }
            lore.add(disableItalic(messages.renderRaw(
                    substitute(line, variables))));
        }
        builder.setLore(lore);

        if (template.glow()) {
            builder.glow();
        }
        if (template.hideTooltip()) {
            builder.hideTooltip();
        }
        template.customModelDataValue().ifPresent(value ->
                builder.setCustomModelData(
                        List.of((float) value),
                        List.of(), List.of(), List.of()));
        return builder;
    }

    private static String multilineKey(
            String line,
            Map<String, List<String>> multiline
    ) {
        if (multiline.isEmpty()
                || line.length() < 3
                || line.charAt(0) != '{'
                || line.charAt(line.length() - 1) != '}') {
            return null;
        }
        String key = line.substring(1, line.length() - 1);
        return multiline.containsKey(key) ? key : null;
    }

    private static Component disableItalic(Component component) {
        // 原版对物品名/lore 默认套用斜体；显式关闭以保持配置样式。
        return component.copy().withStyle(style -> style.withItalic(false));
    }

    private static Item resolveItem(String id) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) {
            return Items.BARRIER;
        }
        return BuiltInRegistries.ITEM.getOptional(identifier)
                .orElse(Items.BARRIER);
    }

    private static String substitute(
            String template,
            Map<String, String> variables
    ) {
        if (template.indexOf('{') < 0) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace(
                    "{" + entry.getKey() + "}",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }
}