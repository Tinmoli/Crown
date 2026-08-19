package dev.xiaomu.crown.fabric.card;

import dev.xiaomu.crown.domain.catalog.DurationType;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.storage.model.CardRecord;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Crown 称号卡的原版物品编码。
 *
 * <p>卡片使用 PAPER，token 只存入 CUSTOM_DATA，不显示在名称或 lore 中。
 * MAX_STACK_SIZE 固定为 1，避免不同 token 的卡片合并。数据库仍是唯一可信
 * 来源；即使物品被复制，同一 token 也只能成功兑换一次。</p>
 */
public final class CrownCardItems {
    private static final String MARKER_KEY = "crown_title_card";
    private static final String TOKEN_KEY = "crown_card_token";

    private CrownCardItems() {
    }

    public static ItemStack create(
            CrownServerContext context,
            CardRecord card
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(card, "card");

        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        stack.set(DataComponents.CUSTOM_NAME, plainStyle(
                renderRaw(context, "card.item.name")));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                plainStyle(renderRaw(
                        context,
                        "card.item.title",
                        card.definitionId().value())),
                plainStyle(renderRaw(
                        context,
                        "card.item.duration",
                        durationText(context, card))),
                plainStyle(renderRaw(
                        context,
                        "card.item.use")))
        ));

        CompoundTag tag = new CompoundTag();
        tag.putBoolean(MARKER_KEY, true);
        tag.putString(TOKEN_KEY, card.cardToken());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /**
     * 读取合法 Crown 卡片 token。普通纸、缺少标记或 token 格式非法均拒绝。
     */
    public static Optional<String> token(ItemStack stack) {
        if (stack == null || stack.isEmpty()
                || stack.getItem() != Items.PAPER) {
            return Optional.empty();
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return Optional.empty();
        }
        CompoundTag tag = data.copyTag();
        if (!tag.getBooleanOr(MARKER_KEY, false)) {
            return Optional.empty();
        }
        String token = tag.getStringOr(TOKEN_KEY, "");
        try {
            return Optional.of(CardRecord.requireToken(token));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static boolean matches(ItemStack stack, String token) {
        return token(stack).filter(token::equals).isPresent();
    }

    private static String durationText(
            CrownServerContext context,
            CardRecord card
    ) {
        if (card.duration().type() == DurationType.PERMANENT) {
            return context.runtime().snapshot().languages()
                    .text("card.duration.permanent");
        }
        return context.runtime().snapshot().languages()
                .text("card.duration.days")
                .replace("%0%", Integer.toString(card.duration().days()));
    }

    private static Component renderRaw(
            CrownServerContext context,
            String key,
            String... args
    ) {
        return context.messages().renderRaw(
                context.runtime().snapshot().languages().text(key),
                args);
    }

    private static Component plainStyle(Component component) {
        return component.copy().withStyle(
                style -> style.withItalic(false));
    }
}