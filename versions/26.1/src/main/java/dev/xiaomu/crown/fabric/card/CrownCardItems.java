package dev.xiaomu.crown.fabric.card;

import dev.xiaomu.crown.storage.model.CardRecord;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

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

}
