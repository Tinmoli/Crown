package dev.xiaomu.crown.fabric.gui;

import dev.xiaomu.crown.config.model.GuiButton;
import dev.xiaomu.crown.config.model.GuiLayout;
import dev.xiaomu.crown.config.model.GuiScreenType;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.display.CrownNametagDisplay;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 玩家仓库称号删除确认 GUI。
 *
 * <p>打开与确认均不信任客户端或旧 GUI 快照。打开前异步读取条目并核验
 * 所有者；确认时由仓储层按玩家 UUID 和条目 UUID 再次原子核验后软删除。</p>
 */
public final class CrownDeleteConfirmGui extends SimpleGui {
    private final CrownServerContext context;
    private final OwnedTitleRecord record;
    private boolean terminalAction;

    private CrownDeleteConfirmGui(
            CrownServerContext context,
            ServerPlayer player,
            MenuType<?> type,
            OwnedTitleRecord record
    ) {
        super(type, player, false);
        this.context = context;
        this.record = record;
    }

    /**
     * 异步查找玩家拥有的有效条目，然后打开确认 GUI。
     */
    public static void open(
            CrownServerContext context,
            ServerPlayer player,
            UUID entryId
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(entryId, "entryId");

        UUID playerId = player.getUUID();
        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() ->
                        context.runtime().storageBackend().repository()
                                .findOwnedTitle(entryId)
                                .filter(entry ->
                                        entry.playerId().equals(playerId))
                                .filter(entry -> entry.deletedAt() == null)
                                .orElse(null)),
                record -> {
                    if (!online(context, playerId)) {
                        return;
                    }
                    if (record == null) {
                        player.sendSystemMessage(context.messages().render(
                                "shop.unavailable",
                                context.runtime().snapshot().languages()
                                        .text("gui.reason.not-owned")));
                        return;
                    }
                    render(context, player, record);
                },
                failure -> {
                    if (online(context, playerId)) {
                        player.sendSystemMessage(context.messages().render(
                                "purchase.failed.storage"));
                    }
                });
    }

    private static void render(
            CrownServerContext context,
            ServerPlayer player,
            OwnedTitleRecord record
    ) {
        GuiLayout layout = context.runtime().snapshot().gui()
                .require("delete-confirm");
        CrownDeleteConfirmGui gui = new CrownDeleteConfirmGui(
                context,
                player,
                menuType(layout.screenType()),
                record);
        gui.setLockPlayerInventory(true);
        gui.setTitle(context.messages().renderRaw(layout.title()));
        gui.draw(layout);
        gui.open();
    }

    private void draw(GuiLayout layout) {
        GuiItems items = new GuiItems(context.messages());
        if (layout.fillerEnabled()) {
            GuiElementBuilder filler =
                    items.build(layout.filler(), Map.of());
            for (int slot = 0; slot < getSize(); slot++) {
                setSlot(slot, filler);
            }
        }

        Map<String, String> variables = Map.of(
                "title_preview", previewSource(record),
                "entry_id", record.entryId().toString());

        for (GuiButton button : layout.buttons().values()) {
            GuiElementBuilder element = items.build(
                    button.item(), variables);
            element.setCallback((index, clickType, input, gui) ->
                    handleButton(button.action(), clickType));
            setSlot(button.slot(), element);
        }
    }

    private void handleButton(String action, ClickType clickType) {
        if (!clickType.isLeft || terminalAction) {
            return;
        }
        switch (action) {
            case "confirm" -> {
                terminalAction = true;
                close();
                delete();
            }
            case "cancel" -> {
                terminalAction = true;
                close();
                CrownWarehouseGui.open(context, getPlayer());
            }
            case "preview" -> {
                // 预览物品没有动作。
            }
            default -> {
                // 未知动作忽略。
            }
        }
    }

    private void delete() {
        ServerPlayer player = getPlayer();
        UUID playerId = player.getUUID();
        String playerName = player.getGameProfile().name();
        UUID entryId = record.entryId();

        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() -> {
                    boolean removed = context.runtime().storageBackend()
                            .repository().softDeleteOwnedTitle(
                                    playerId,
                                    entryId,
                                    "player:" + playerId,
                                    Instant.now());
                    if (removed) {
                        context.runtime().playerTitleCache()
                                .load(playerId, playerName);
                    }
                    return removed;
                }),
                removed -> {
                    if (!online(context, playerId)) {
                        return;
                    }
                    if (removed) {
                        CrownNametagDisplay.refreshPlayer(context, player);
                    }
                    player.sendSystemMessage(context.messages().render(
                            removed
                                    ? "warehouse.deleted"
                                    : "shop.unavailable",
                            removed
                                    ? ""
                                    : context.runtime().snapshot().languages()
                                            .text("gui.reason.not-owned")));
                    CrownWarehouseGui.open(context, player);
                },
                failure -> {
                    if (online(context, playerId)) {
                        player.sendSystemMessage(context.messages().render(
                                "purchase.failed.storage"));
                    }
                });
    }

    private static boolean online(
            CrownServerContext context,
            UUID playerId
    ) {
        return context.server().getPlayerList().getPlayer(playerId) != null;
    }

    private static String previewSource(OwnedTitleRecord record) {
        return record.titlePrefix()
                + record.titleText()
                + record.titleSuffix();
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