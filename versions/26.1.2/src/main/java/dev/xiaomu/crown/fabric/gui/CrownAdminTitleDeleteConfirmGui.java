package dev.xiaomu.crown.fabric.gui;

import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.command.CrownTitleAdminCommands;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;

/** 管理员商品删除二次确认；只删除配置商品，不删除玩家历史仓库条目。 */
public final class CrownAdminTitleDeleteConfirmGui extends SimpleGui {
    private final CrownServerContext context;
    private final String definitionId;

    private CrownAdminTitleDeleteConfirmGui(
            CrownServerContext context, ServerPlayer player, String definitionId) {
        super(MenuType.GENERIC_9x3, player, false);
        this.context = context;
        this.definitionId = definitionId;
    }

    public static void open(CrownServerContext context, ServerPlayer player, String definitionId) {
        var gui = new CrownAdminTitleDeleteConfirmGui(context, player, definitionId);
        gui.setTitle(context.messages().renderRaw("&cConfirm Delete"));
        GuiItems items = new GuiItems(context.messages());
        gui.setSlot(11, items.build(item("minecraft:red_concrete",
                "&cConfirm Delete", java.util.List.of("&7Irreversible")),
                java.util.Map.of())
                .setCallback((slot, click, input, ignored) -> {
                    if (click.isLeft) {
                        gui.close();
                        CrownTitleAdminCommands.deleteFromGui(context, player, definitionId,
                                () -> CrownAdminShopGui.open(context, player));
                    }
                }));
        gui.setSlot(13, items.build(item("minecraft:name_tag", definitionId,
                java.util.List.of()), java.util.Map.of()));
        gui.setSlot(15, items.build(item("minecraft:lime_concrete",
                "&aKeep Title", java.util.List.of()), java.util.Map.of())
                .setCallback((slot, click, input, ignored) -> {
                    if (click.isLeft) {
                        gui.close();
                        CrownAdminShopGui.openDetail(context, player, definitionId);
                    }
                }));
        gui.open();
    }

    private static dev.xiaomu.crown.config.model.GuiItemTemplate item(
            String item, String name, java.util.List<String> lore) {
        return new dev.xiaomu.crown.config.model.GuiItemTemplate(
                item, name, lore, 1, false, false, null, "", false);
    }
}