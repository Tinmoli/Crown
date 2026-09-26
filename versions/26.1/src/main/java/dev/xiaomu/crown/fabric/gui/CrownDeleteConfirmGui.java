package dev.xiaomu.crown.fabric.gui;

import dev.xiaomu.crown.config.model.GuiButton;
import dev.xiaomu.crown.config.model.GuiLayout;
import dev.xiaomu.crown.config.model.GuiScreenType;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.display.CrownNametagDisplay;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;

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
public final class CrownDeleteConfirmGui extends CrownGui {
    private final CrownServerContext context;
    private final OwnedTitleRecord record;
    private boolean terminalAction;
    private final long refund;
    private final dev.xiaomu.crown.config.model.CoreSettings.Deletion quotedSettings;

    private CrownDeleteConfirmGui(
            CrownServerContext context,
            ServerPlayer player,
            MenuType<?> type,
            OwnedTitleRecord record,
            long refund
    ) {
        super(context, type, player);
        this.context = context;
        this.record = record;
        this.refund = refund;
        this.quotedSettings = context.core().deletion();
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
                                .map(entry -> new Preview(entry, context.runtime().storageBackend().repository()
                                        .deletionRefund(playerId, entryId, context.core().deletion(), Instant.now())))
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
                    render(context, player, record.title(), record.refund());
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
            OwnedTitleRecord record,
            long refund
    ) {
        GuiLayout layout = context.runtime().snapshot().gui()
                .require("delete-confirm");
        CrownDeleteConfirmGui gui = new CrownDeleteConfirmGui(
                context,
                player,
                menuType(layout.screenType()),
                record, refund);
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
                "entry_id", record.entryId().toString(),
                "refund", Long.toString(refund), "title_coin_unit", context.core().titleCoin().name());

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
                if (!quotedSettings.equals(context.core().deletion())) {
                    getPlayer().sendSystemMessage(context.messages().render("purchase.changed"));
                    close();
                    return;
                }
                if (!context.permissions().checkSource(
                        dev.xiaomu.crown.runtime.platform.PermissionSource.of(getPlayer().createCommandSourceStack()),
                        dev.xiaomu.crown.fabric.permission.CrownPermissions.COMMAND_OPEN, 0)) {
                    getPlayer().sendSystemMessage(context.messages().render("command.no-permission"));
                    close();
                    return;
                }
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
                context.runtime().playerOperations().submit(playerId, () ->
                        context.runtime().storageExecutor().submit(() -> {
                    var result = context.runtime().storageBackend().repository().deleteOwnedTitle(
                            playerId, entryId, "player:" + playerId, quotedSettings,
                            context.core().titleCoin().maximumBalance(), Instant.now());
                    if (result.status() == dev.xiaomu.crown.storage.model.TitleDeletionResult.Status.DELETED)
                        context.runtime().playerTitleCache().load(playerId, playerName);
                    return result;
                })),
                result -> {
                    if (!online(context, playerId)) return;
                    switch (result.status()) {
                        case DELETED -> {
                            CrownNametagDisplay.refreshPlayer(context, player);
                            player.sendSystemMessage(context.messages().render("warehouse.deleted-refund",
                                    Long.toString(result.refund()), context.core().titleCoin().name()));
                        }
                        case BALANCE_LIMIT -> player.sendSystemMessage(
                                context.messages().render("warehouse.refund-balance-limit"));
                        case NOT_OWNED -> player.sendSystemMessage(context.messages().render(
                                "shop.unavailable", context.runtime().snapshot().languages().text("gui.reason.not-owned")));
                    }
                    CrownWarehouseGui.open(context, player);
                },
                failure -> {
                    if (online(context, playerId)) player.sendSystemMessage(
                            context.messages().render("purchase.failed.storage"));
                });
    }

    private record Preview(OwnedTitleRecord title, long refund) { }
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
