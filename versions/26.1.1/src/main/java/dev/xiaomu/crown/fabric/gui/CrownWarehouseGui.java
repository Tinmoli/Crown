package dev.xiaomu.crown.fabric.gui;

import dev.xiaomu.crown.config.model.GuiButton;
import dev.xiaomu.crown.config.model.GuiItemTemplate;
import dev.xiaomu.crown.config.model.GuiLayout;
import dev.xiaomu.crown.config.model.GuiScreenType;
import dev.xiaomu.crown.domain.player.SelectionType;
import dev.xiaomu.crown.domain.player.TitleSelection;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.display.CrownNametagDisplay;
import dev.xiaomu.crown.fabric.text.CrownMessages;
import dev.xiaomu.crown.runtime.wardrobe.EquipResult;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
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
import java.util.function.Supplier;

/**
 * Crown 玩家称号仓库 GUI（DESIGN.md §11、§16）。
 *
 * <p>打开前在存储线程读取仓库条目与当前佩戴选择，再切回主线程渲染。
 * 左键佩戴、右键删除；过期条目仅提示不可佩戴。所有写操作都回存储线程
 * 执行并刷新称号缓存后重新打开界面。</p>
 */
public final class CrownWarehouseGui extends SimpleGui {
    private final CrownServerContext context;
    private final List<OwnedTitleRecord> owned;
    private final UUID equippedEntryId;
    private final int[] contentSlots;
    private int page;

    private CrownWarehouseGui(
            CrownServerContext context,
            ServerPlayer player,
            MenuType<?> type,
            List<OwnedTitleRecord> owned,
            UUID equippedEntryId,
            int[] contentSlots
    ) {
        super(type, player, false);
        this.context = context;
        this.owned = owned;
        this.equippedEntryId = equippedEntryId;
        this.contentSlots = contentSlots;
    }

    /** 打开仓库；在主线程调用，内部异步读取仓库数据后再渲染。 */
    public static void open(
            CrownServerContext context,
            ServerPlayer player
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(player, "player");
        UUID playerId = player.getUUID();

        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() ->
                        loadState(context, playerId)),
                state -> render(context, player, state),
                failure -> player.sendSystemMessage(context.messages()
                        .render("purchase.failed.storage")));
    }

    private static WarehouseState loadState(
            CrownServerContext context,
            UUID playerId
    ) {
        List<OwnedTitleRecord> titles =
                context.runtime().wardrobe().listOwned(playerId);
        TitleSelection selection = context.runtime().storageBackend()
                .repository().findPlayer(playerId)
                .map(PlayerRecord::selection)
                .orElse(TitleSelection.none());
        UUID equipped = selection.type() == SelectionType.OWNED
                ? selection.ownedEntryId().orElse(null)
                : null;
        return new WarehouseState(titles, equipped);
    }

    private static void render(
            CrownServerContext context,
            ServerPlayer player,
            WarehouseState state
    ) {
        GuiLayout layout = context.runtime().snapshot().gui()
                .require("warehouse");
        int[] slots = layout.contentSlots().stream()
                .mapToInt(Integer::intValue).toArray();
        CrownWarehouseGui gui = new CrownWarehouseGui(
                context, player, menuType(layout.screenType()),
                state.titles(), state.equippedEntryId(), slots);
        gui.setLockPlayerInventory(true);
        gui.setTitle(context.messages().renderRaw(layout.title()));
        gui.draw(layout);
        CrownGuiSessions.warehouse(context, player);
        gui.open();
    }

    private void draw(GuiLayout layout) {
        CrownMessages messages = context.messages();
        GuiItems items = new GuiItems(messages);

        if (layout.fillerEnabled()) {
            GuiElementBuilder filler =
                    items.build(layout.filler(), Map.of());
            for (int slot = 0; slot < getSize(); slot++) {
                setSlot(slot, filler);
            }
        }

        Map<String, String> pageVariables = Map.of(
                "page", Integer.toString(page + 1),
                "pages", Integer.toString(pageCount()),
                "default_title_preview",
                GuiFormatting.previewSource(
                        context.core().defaultTitle().content()));

        for (GuiButton button : layout.buttons().values()) {
            GuiElementBuilder element = items
                    .build(button.item(), pageVariables)
                    .setCallback((index, clickType, input, gui) ->
                            handleButton(button.action(), clickType));
            setSlot(button.slot(), element);
        }

        drawContent(items, layout);
    }

    private void drawContent(GuiItems items, GuiLayout layout) {
        Instant now = Instant.now();
        int perPage = contentSlots.length;
        int start = page * perPage;

        for (int offset = 0; offset < perPage; offset++) {
            int slot = contentSlots[offset];
            int index = start + offset;
            if (index >= owned.size()) {
                clearSlot(slot);
                continue;
            }
            OwnedTitleRecord record = owned.get(index);
            setSlot(slot, safeTitleElement(items, layout, record, now));
        }
    }

    private GuiElementBuilder titleElement(
            GuiItems items,
            GuiLayout layout,
            OwnedTitleRecord record,
            Instant now
    ) {
        boolean expired = record.expiredAt(now);
        boolean equipped = record.entryId().equals(equippedEntryId);
        var languages = context.runtime().snapshot().languages();
        String variantKey = expired
                ? "expired"
                : (equipped ? "equipped" : "active");
        GuiItemTemplate template = layout.itemVariants().get(variantKey);
        if (template == null) {
            template = layout.filler();
        }

        Map<String, String> variables = Map.of(
                "title_preview", previewSource(record),
                "entry_id", record.entryId().toString(),
                "source", GuiFormatting.sourceText(record.source(), layout.textValues()),
                "acquired_at", GuiFormatting.time(record.acquiredAt(), layout.textValues()),
                "expires", GuiFormatting.time(record.expiresAt(), layout.textValues()));

        GuiElementBuilder element = items.build(template, variables);
        element.setCallback((index, clickType, input, gui) ->
                handleTitleClick(record, expired, clickType));
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

    private void handleTitleClick(
            OwnedTitleRecord record,
            boolean expired,
            ClickType clickType
    ) {
        ServerPlayer player = getPlayer();
        UUID entryId = record.entryId();
        if (clickType.isRight) {
            CrownGuiSessions.clear(player);
            close();
            CrownDeleteConfirmGui.open(context, player, entryId);
            return;
        }
        if (!clickType.isLeft) {
            return;
        }
        if (expired) {
            player.sendSystemMessage(context.messages()
                    .render("warehouse.expired"));
            return;
        }
        runOperation(player, () -> context.runtime().wardrobe()
                .equip(player.getUUID(), entryId), null);
    }

    private void handleButton(String action, ClickType clickType) {
        if (!clickType.isLeft) {
            return;
        }
        ServerPlayer player = getPlayer();
        switch (action) {
            case "close" -> {
                CrownGuiSessions.clear(getPlayer());
                close();
            }
            case "previous" -> {
                if (page > 0) {
                    page--;
                    draw(context.runtime().snapshot().gui()
                            .require("warehouse"));
                }
            }
            case "next" -> {
                if (page + 1 < pageCount()) {
                    page++;
                    draw(context.runtime().snapshot().gui()
                            .require("warehouse"));
                }
            }
            case "default" -> runOperation(player,
                    () -> context.runtime().wardrobe()
                            .equipDefault(player.getUUID()), null);
            case "none" -> runOperation(player,
                    () -> context.runtime().wardrobe()
                            .unequip(player.getUUID()), null);
            case "shop" -> {
                CrownGuiSessions.clear(player);
                close();
                CrownShopGui.open(context, player);
            }
            default -> {
                // 未知动作忽略。
            }
        }
    }

    /**
     * 在存储线程执行仓库写操作，成功后刷新缓存并在主线程重开界面。
     * {@code overrideMessage} 非空时用它作为成功反馈键，否则按 equip 结果。
     */
    private void runOperation(
            ServerPlayer player,
            Supplier<EquipResult> operation,
            String overrideMessage
    ) {
        UUID id = player.getUUID();
        String name = player.getGameProfile().name();
        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() -> {
                    EquipResult result = operation.get();
                    if (result != EquipResult.STORAGE_FAILED
                            && result != EquipResult.NOT_OWNED
                            && result != EquipResult.EXPIRED) {
                        context.runtime().playerTitleCache().load(id, name);
                    }
                    return result;
                }),
                result -> {
                    CrownNametagDisplay.refreshPlayer(context, player);
                    player.sendSystemMessage(overrideMessage != null
                            && result != EquipResult.STORAGE_FAILED
                            ? context.messages().render(overrideMessage)
                            : equipMessage(result));
                    open(context, player);
                },
                failure -> player.sendSystemMessage(context.messages()
                        .render("purchase.failed.storage")));
    }

    private net.minecraft.network.chat.Component equipMessage(
            EquipResult result
    ) {
        CrownMessages messages = context.messages();
        return switch (result) {
            case EQUIPPED, ALREADY_EQUIPPED ->
                    messages.render("warehouse.equipped", "");
            case EQUIPPED_DEFAULT ->
                    messages.render("warehouse.default");
            case UNEQUIPPED -> messages.render("warehouse.none");
            case NOT_OWNED ->
                    messages.render("shop.unavailable",
                            context.runtime().snapshot().languages()
                                    .text("gui.reason.not-owned"));
            case EXPIRED -> messages.render("warehouse.expired");
            case STORAGE_FAILED ->
                    messages.render("purchase.failed.storage");
        };
    }

    private String previewSource(OwnedTitleRecord record) {
        return record.titlePrefix() + record.titleText()
                + record.titleSuffix();
    }

    private int pageCount() {
        if (owned.isEmpty() || contentSlots.length == 0) {
            return 1;
        }
        return (owned.size() + contentSlots.length - 1)
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

    private record WarehouseState(
            List<OwnedTitleRecord> titles,
            UUID equippedEntryId
    ) {
    }
}