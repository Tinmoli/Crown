package dev.xiaomu.crown.fabric.gui;

import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.config.model.GuiButton;
import dev.xiaomu.crown.config.model.GuiLayout;
import dev.xiaomu.crown.config.model.GuiScreenType;
import dev.xiaomu.crown.domain.catalog.TitleDefinition;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.display.CrownNametagDisplay;
import dev.xiaomu.crown.fabric.permission.CrownPermissions;
import dev.xiaomu.crown.fabric.text.CrownMessages;
import dev.xiaomu.crown.runtime.platform.PermissionSource;
import dev.xiaomu.crown.runtime.purchase.PurchaseIdentifiers;
import dev.xiaomu.crown.runtime.purchase.PurchaseResult;
import dev.xiaomu.crown.runtime.purchase.PurchaseStatus;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Crown 普通称号购买确认 GUI（DESIGN.md §16）。
 *
 * <p>展示价格、有效期与描述；确认后调用 {@link
 * dev.xiaomu.crown.runtime.purchase.UnifiedPurchaseService} 走完整异步
 * 购买状态机，扣款由内部称号币账本完成。点击确认后立即锁定按钮，
 * 结果切回主线程反馈并刷新称号缓存。</p>
 */
public final class CrownPurchaseConfirmGui extends SimpleGui {
    private final CrownServerContext context;
    private final TitleDefinition definition;
    private final GuiLayout layout;
    private boolean processing;

    private CrownPurchaseConfirmGui(
            CrownServerContext context,
            ServerPlayer player,
            MenuType<?> type,
            TitleDefinition definition,
            GuiLayout layout
    ) {
        super(type, player, false);
        this.context = context;
        this.definition = definition;
        this.layout = layout;
    }

    /** 打开购买确认；必须在主线程调用。 */
    public static void open(
            CrownServerContext context,
            ServerPlayer player,
            TitleDefinition definition
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(definition, "definition");

        GuiLayout layout = context.runtime().snapshot().gui()
                .require("purchase-confirm");
        CrownPurchaseConfirmGui gui = new CrownPurchaseConfirmGui(
                context, player, menuType(layout.screenType()),
                definition, layout);
        gui.setLockPlayerInventory(true);
        gui.setTitle(context.messages().renderRaw(layout.title()));
        gui.draw();
        gui.open();
    }

    private void draw() {
        CrownMessages messages = context.messages();
        GuiItems items = new GuiItems(messages);
        CoreSettings core = context.core();
        var languages = context.runtime().snapshot().languages();

        if (layout.fillerEnabled()) {
            GuiElementBuilder filler =
                    items.build(layout.filler(), Map.of());
            for (int slot = 0; slot < getSize(); slot++) {
                setSlot(slot, filler);
            }
        }

        Map<String, String> variables = Map.of(
                "title_icon", definition.icon().serialized(),
                "title_preview", GuiFormatting.previewSource(
                        definition.content()),
                "price", GuiFormatting.priceText(definition.payment(), layout.textValues()),
                "currency", GuiFormatting.currencyText(
                        definition.payment(), core),
                "duration", GuiFormatting.durationText(
                        definition.duration(), layout.textValues()),
                "title_coin_price", GuiFormatting.priceText(definition.payment(), layout.textValues()),
                "title_coin_unit", core.titleCoin().name());
        Map<String, List<String>> multiline = Map.of(
                "title_description", definition.description(),
                "payment_price_lines", List.of(layout.textValue("title-coin-price-line")));

        for (GuiButton button : layout.buttons().values()) {

            if (processing && ("confirm".equals(button.action())
                    || "pay-title-coin".equals(button.action()))) {
                continue;
            }
            if (!processing && "processing".equals(button.action())) {
                continue;
            }
            GuiElementBuilder element = items
                    .build(button.item(), variables, multiline)
                    .setCallback((index, clickType, input, gui) ->
                            handleButton(button.action(), clickType));
            setSlot(button.slot(), element);
        }
    }

    private void handleButton(String action, ClickType clickType) {
        if (!clickType.isLeft) {
            return;
        }
        switch (action) {
            case "cancel" -> close();
            case "confirm" -> confirm();
            case "pay-title-coin" -> confirm();
            default -> {
                // preview / processing / 未知动作忽略。
            }
        }
    }

    private void confirm() {
        if (processing) {
            return;
        }
        TitleDefinition current = context.runtime().snapshot().catalog()
                .find(definition.id().value()).orElse(null);
        if (!definition.equals(current)) {
            getPlayer().sendSystemMessage(context.messages().render("purchase.changed"));
            close();
            return;
        }
        if (!context.permissions().checkSource(
                PermissionSource.of(getPlayer().createCommandSourceStack()), CrownPermissions.COMMAND_BUY, 0)) {
            getPlayer().sendSystemMessage(context.messages().render("command.no-permission"));
            close();
            return;
        }
        processing = true;
        draw();

        ServerPlayer player = getPlayer();
        java.util.UUID playerId = player.getUUID();
        String name = player.getGameProfile().name();
        boolean hasPermission = definition.permission().isEmpty()
                || context.permissions().checkSource(
                PermissionSource.of(player.createCommandSourceStack()),
                definition.permission().orElseThrow(),
                0);
        CoreSettings.Purchase purchaseSettings =
                context.core().purchase();
        PurchaseIdentifiers identifiers =
                PurchaseIdentifiers.create();

        context.mainThread().whenComplete(
                context.runtime().purchaseService().purchaseCatalog(
                        playerId, definition, hasPermission,
                        purchaseSettings, identifiers),
                result -> onResult(player, playerId, name, result),
                failure -> onFailure(player));
    }

    private void onResult(
            ServerPlayer player,
            java.util.UUID playerId,
            String name,
            PurchaseResult result
    ) {
        if (result.status() == PurchaseStatus.GRANTED) {
            context.mainThread().whenComplete(
                    context.runtime().storageExecutor().submit(() -> {
                        context.runtime().playerTitleCache().load(playerId,
                                name);
                        return null;
                    }),
                    ignored -> CrownNametagDisplay.refreshPlayer(
                            context, player),
                    failure -> player.sendSystemMessage(context.messages()
                            .render("purchase.failed.storage")));
            player.sendSystemMessage(context.messages().render(
                    "purchase.success",
                    GuiFormatting.previewSource(definition.content())));
        } else {
            player.sendSystemMessage(resultMessage(result));
        }
        close();
    }

    private void onFailure(ServerPlayer player) {
        player.sendSystemMessage(context.messages()
                .render("purchase.failed.storage"));
        close();
    }

    private net.minecraft.network.chat.Component resultMessage(
            PurchaseResult result
    ) {
        CrownMessages messages = context.messages();
        var languages = context.runtime().snapshot().languages();
        return switch (result.status()) {
            case INSUFFICIENT_FUNDS ->
                    messages.render("purchase.failed.balance",
                            GuiFormatting.priceText(definition.payment(), layout.textValues()),
                            Long.toString(context.runtime().playerTitleCache().get(getPlayer().getUUID()).titleCoinBalance()));
            case PAYMENT_FAILED, PAYMENT_UNCERTAIN ->
                    messages.render("purchase.failed.provider");
            case DISABLED, HIDDEN, NOT_ON_SALE, PERMISSION_DENIED ->
                    messages.render("shop.unavailable",
                            GuiFormatting.statusText(
                                    result.status().name(), layout.textValues()));
            case OUT_OF_STOCK, PLAYER_LIMIT_REACHED ->
                    messages.render("shop.unavailable",
                            GuiFormatting.statusText(
                                    result.status().name(), layout.textValues()));
            case TOO_MANY_PENDING ->
                    messages.render("purchase.processing");
            case ORDER_CONFLICT, INVALID_STATE ->
                    messages.render("purchase.failed.storage");
            case GRANTED ->
                    messages.render("purchase.success",
                            GuiFormatting.previewSource(
                                    definition.content()));
        };
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
