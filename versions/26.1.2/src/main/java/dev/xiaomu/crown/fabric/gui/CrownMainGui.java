package dev.xiaomu.crown.fabric.gui;

import dev.xiaomu.crown.config.model.GuiButton;
import dev.xiaomu.crown.config.model.GuiItemTemplate;
import dev.xiaomu.crown.config.model.GuiLayout;
import dev.xiaomu.crown.config.model.GuiScreenType;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.custom.PlayerCustomTitleSessions;
import dev.xiaomu.crown.fabric.text.CrownMessages;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;

import java.util.Map;
import java.util.Objects;

/**
 * Crown 主菜单 GUI（DESIGN.md §16）。
 *
 * <p>基于 SGUI 虚拟箱子，客户端无需安装 Crown。所有点击回调按配置的稳定
 * 动作名分派，禁止玩家取放物品，符合 §16.4 GUI 安全要求。</p>
 */
public final class CrownMainGui extends SimpleGui {
    private final CrownServerContext context;

    private CrownMainGui(
            CrownServerContext context,
            ServerPlayer player,
            MenuType<?> type
    ) {
        super(type, player, false);
        this.context = context;
    }

    /** 打开主菜单；必须在服务器主线程调用。 */
    public static void open(
            CrownServerContext context,
            ServerPlayer player
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(player, "player");

        GuiLayout layout = context.runtime().snapshot().gui().require("main");
        CrownMainGui gui = new CrownMainGui(
                context, player, menuType(layout.screenType()));
        gui.setLockPlayerInventory(true);
        gui.setTitle(context.messages().renderRaw(layout.title()));
        gui.render(layout);
        CrownGuiSessions.main(context, player);
        gui.open();
    }

    private void render(GuiLayout layout) {
        CrownMessages messages = context.messages();
        GuiItems items = new GuiItems(messages);
        Map<String, String> emptyVariables = Map.of();

        if (layout.fillerEnabled()) {
            GuiItemTemplate filler = layout.filler();
            GuiElementBuilder fillerElement =
                    items.build(filler, emptyVariables);
            for (int slot = 0; slot < getSize(); slot++) {
                setSlot(slot, fillerElement);
            }
        }

        for (GuiButton button : layout.buttons().values()) {
            GuiElementBuilder element = items
                    .build(button.item(), emptyVariables)
                    .setCallback((index, clickType, input, gui) ->
                            handle(button.action(), clickType));
            setSlot(button.slot(), element);
        }
    }

    private void handle(String action, ClickType clickType) {
        if (!clickType.isLeft) {
            return;
        }
        ServerPlayer player = getPlayer();
        switch (action) {
            case "close" -> {
                CrownGuiSessions.clear(getPlayer());
                close();
            }
            case "shop" -> {
                CrownGuiSessions.clear(player);
                close();
                CrownShopGui.open(context, player);
            }
            case "warehouse" -> {
                CrownGuiSessions.clear(player);
                close();
                CrownWarehouseGui.open(context, player);
            }
            case "custom" -> {
                CrownGuiSessions.clear(player);
                close();
                if (!context.core().customTitle().enabled()) {
                    player.sendSystemMessage(context.messages().render(
                            "shop.unavailable",
                            context.runtime().snapshot().languages()
                                    .text("gui.value.custom-disabled")));
                    return;
                }
                if (!PlayerCustomTitleSessions.begin(context, player)) {
                    player.sendSystemMessage(context.messages().render(
                            "purchase.processing"));
                }
            }
            default -> {
                // 未知动作忽略，避免异常传播到网络线程。
            }
        }
    }

    private static MenuType<?> menuType(GuiScreenType type) {
        return switch (type) {
            case GENERIC_9X1 -> MenuType.GENERIC_9x1;
            case GENERIC_9X2 -> MenuType.GENERIC_9x2;
            case GENERIC_9X3 -> MenuType.GENERIC_9x3;
            case GENERIC_9X4 -> MenuType.GENERIC_9x4;
            case GENERIC_9X5 -> MenuType.GENERIC_9x5;
            case GENERIC_9X6 -> MenuType.GENERIC_9x6;
        };
    }
}