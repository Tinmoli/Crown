package dev.xiaomu.crown.fabric.gui;

import dev.xiaomu.crown.config.model.GuiButton;
import dev.xiaomu.crown.config.model.GuiItemTemplate;
import dev.xiaomu.crown.config.model.GuiLayout;
import dev.xiaomu.crown.config.model.GuiScreenType;
import dev.xiaomu.crown.domain.catalog.DurationPolicy;
import dev.xiaomu.crown.domain.player.SelectionType;
import dev.xiaomu.crown.domain.player.TitleSelection;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.custom.CustomTitleInputSessions;
import dev.xiaomu.crown.fabric.display.CrownNametagDisplay;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import dev.xiaomu.crown.storage.model.OwnedTitleStatus;
import dev.xiaomu.crown.storage.model.PlayerRecord;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 管理员查看并管理指定玩家仓库的 GUI。 */
public final class CrownAdminWarehouseGui extends SimpleGui {
    private final CrownServerContext context;
    private final UUID targetId;
    private final String targetName;
    private final List<OwnedTitleRecord> titles;
    private final UUID equippedEntryId;
    private final int[] contentSlots;
    private int page;

    private CrownAdminWarehouseGui(
            CrownServerContext context,
            ServerPlayer administrator,
            MenuType<?> type,
            UUID targetId,
            String targetName,
            List<OwnedTitleRecord> titles,
            UUID equippedEntryId,
            int[] contentSlots
    ) {
        super(type, administrator, false);
        this.context = context;
        this.targetId = targetId;
        this.targetName = targetName;
        this.titles = titles;
        this.equippedEntryId = equippedEntryId;
        this.contentSlots = contentSlots;
    }

    /** 从游戏内管理员入口打开；数据读取始终在存储执行器中完成。 */
    public static void open(
            CrownServerContext context,
            ServerPlayer administrator,
            UUID targetId,
            String targetName
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(administrator, "administrator");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(targetName, "targetName");

        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() ->
                        loadState(context, targetId)),
                state -> render(context, administrator, targetId,
                        targetName, state),
                failure -> administrator.sendSystemMessage(context.messages()
                        .render("purchase.failed.storage")));
    }

    private static State loadState(
            CrownServerContext context,
            UUID targetId
    ) {
        List<OwnedTitleRecord> titles = context.runtime().storageBackend()
                .repository().listOwnedTitles(targetId, true);
        TitleSelection selection = context.runtime().storageBackend()
                .repository().findPlayer(targetId)
                .map(PlayerRecord::selection)
                .orElse(TitleSelection.none());
        UUID equipped = selection.type() == SelectionType.OWNED
                ? selection.ownedEntryId().orElse(null)
                : null;
        return new State(titles, equipped);
    }

    private static void render(
            CrownServerContext context,
            ServerPlayer administrator,
            UUID targetId,
            String targetName,
            State state
    ) {
        GuiLayout layout = context.runtime().snapshot().gui()
                .require("admin-warehouse");
        int[] slots = layout.contentSlots().stream()
                .mapToInt(Integer::intValue).toArray();
        CrownAdminWarehouseGui gui = new CrownAdminWarehouseGui(
                context, administrator, menuType(layout.screenType()),
                targetId, targetName, state.titles(), state.equippedEntryId(),
                slots);
        gui.setLockPlayerInventory(true);
        gui.setTitle(context.messages().renderRaw(
                layout.title().replace("{player}", targetName)));
        gui.draw(layout);
        CrownGuiSessions.adminWarehouse(
                context, administrator, targetId, targetName);
        gui.open();
    }

    private void draw(GuiLayout layout) {
        GuiItems items = new GuiItems(context.messages());
        if (layout.fillerEnabled()) {
            GuiElementBuilder filler = items.build(layout.filler(), Map.of());
            for (int slot = 0; slot < getSize(); slot++) {
                setSlot(slot, filler);
            }
        }

        Map<String, String> variables = Map.of(
                "player", targetName,
                "page", Integer.toString(page + 1),
                "pages", Integer.toString(pageCount()));
        for (GuiButton button : layout.buttons().values()) {
            setSlot(button.slot(), items.build(button.item(), variables)
                    .setCallback((slot, click, input, gui) ->
                            handleButton(button.action(), click)));
        }

        Instant now = Instant.now();
        for (int offset = 0; offset < contentSlots.length; offset++) {
            int index = page * contentSlots.length + offset;
            int slot = contentSlots[offset];
            if (index >= titles.size()) {
                clearSlot(slot);
                continue;
            }
            OwnedTitleRecord record = titles.get(index);
            setSlot(slot, safeTitleElement(items, layout, record, now));
        }
    }

    private GuiElementBuilder titleElement(
            GuiItems items,
            GuiLayout layout,
            OwnedTitleRecord record,
            Instant now
    ) {
        boolean deleted = record.status() == OwnedTitleStatus.DELETED;
        boolean expired = record.expiredAt(now);
        GuiItemTemplate template = layout.itemVariants().get(
                deleted ? "deleted" : "active");
        if (template == null) {
            template = layout.filler();
        }
        var languages = context.runtime().snapshot().languages();
        Map<String, String> variables = Map.of(
                "title_preview", record.titlePrefix() + record.titleText()
                        + record.titleSuffix(),
                "entry_id", record.entryId().toString(),
                "expires", GuiFormatting.time(record.expiresAt(), layout.textValues()),
                "deleted_by", record.deletedBy() == null
                        ? "None"
                        : record.deletedBy());
        GuiElementBuilder element = items.build(template, variables);
        if (!deleted) {
            element.setCallback((slot, click, input, gui) -> {
                if (click.isLeft && !expired) {
                    setOwned(record.entryId());
                } else if (click.isRight) {
                    revoke(record.entryId());
                }
            });
        }
        return element;
    }

    private GuiElementBuilder safeTitleElement(
            GuiItems items, GuiLayout layout, OwnedTitleRecord record, Instant now
    ) {
        try {
            GuiElementBuilder element = titleElement(items, layout, record, now);
            return element == null ? barrierElement() : element;
        } catch (RuntimeException exception) {
            return barrierElement();
        }
    }

    private static GuiElementBuilder barrierElement() {
        return new GuiElementBuilder(net.minecraft.world.item.Items.BARRIER);
    }

    private void handleButton(String action, ClickType click) {
        if (!click.isLeft) {
            return;
        }
        switch (action) {
            case "close" -> {
                CrownGuiSessions.clear(getPlayer());
                close();
            }
            case "previous" -> {
                if (page > 0) {
                    page--;
                    draw(context.runtime().snapshot().gui()
                            .require("admin-warehouse"));
                }
            }
            case "next" -> {
                if (page + 1 < pageCount()) {
                    page++;
                    draw(context.runtime().snapshot().gui()
                            .require("admin-warehouse"));
                }
            }
            case "set-default" -> setSelection(TitleSelection.defaultTitle());
            case "set-none" -> setSelection(TitleSelection.none());
            case "grant" -> {
                CrownGuiSessions.clear(getPlayer());
                close();
                if (!CustomTitleInputSessions.beginAdminGrant(
                        context, getPlayer(), targetId, targetName,
                        DurationPolicy.permanent())) {
                    getPlayer().sendSystemMessage(context.messages().render(
                            "admin.player.custom.busy"));
                }
            }
            default -> {
                // 配置可扩展动作；未知动作安全忽略。
            }
        }
    }

    private void setOwned(UUID entryId) {
        setSelection(TitleSelection.owned(entryId));
    }

    private void setSelection(TitleSelection selection) {
        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() -> {
                    boolean updated = context.runtime().storageBackend()
                            .repository().setSelection(targetId, selection,
                                    Instant.now());
                    if (updated) {
                        context.runtime().playerTitleCache().load(targetId,
                                targetName);
                    }
                    return updated;
                }),
                updated -> {
                    refreshTargetDisplay();
                    getPlayer().sendSystemMessage(context.messages().render(
                            updated ? "admin.player.selection"
                                    : "shop.unavailable",
                            updated ? "" : context.runtime().snapshot()
                                    .languages().text("gui.reason.not-owned")));
                    reopen();
                },
                failure -> getPlayer().sendSystemMessage(context.messages()
                        .render("purchase.failed.storage")));
    }

    private void revoke(UUID entryId) {
        String actor = "player:" + getPlayer().getUUID();
        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() -> {
                    boolean removed = context.runtime().storageBackend()
                            .repository().softDeleteOwnedTitle(targetId,
                                    entryId, actor, Instant.now());
                    if (removed) {
                        context.runtime().playerTitleCache().load(targetId,
                                targetName);
                    }
                    return removed;
                }),
                removed -> {
                    refreshTargetDisplay();
                    getPlayer().sendSystemMessage(context.messages().render(
                            removed ? "admin.player.revoked"
                                    : "shop.unavailable",
                            removed ? "" : context.runtime().snapshot()
                                    .languages().text("gui.reason.not-owned")));
                    reopen();
                },
                failure -> getPlayer().sendSystemMessage(context.messages()
                        .render("purchase.failed.storage")));
    }

    private void refreshTargetDisplay() {
        ServerPlayer target = context.server().getPlayerList()
                .getPlayer(targetId);
        if (target != null) {
            CrownNametagDisplay.refreshPlayer(context, target);
        }
    }

    private void reopen() {
        close();
        open(context, getPlayer(), targetId, targetName);
    }

    private int pageCount() {
        return titles.isEmpty() || contentSlots.length == 0
                ? 1
                : (titles.size() + contentSlots.length - 1)
                        / contentSlots.length;
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

    private record State(
            List<OwnedTitleRecord> titles,
            UUID equippedEntryId
    ) {
    }
}