package dev.xiaomu.crown.fabric.gui;

import dev.xiaomu.crown.config.model.GuiButton;
import dev.xiaomu.crown.config.model.GuiLayout;
import dev.xiaomu.crown.config.model.GuiScreenType;
import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.domain.catalog.TitleContent;
import dev.xiaomu.crown.domain.catalog.PaymentPolicy;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.custom.PlayerCustomTitleSessions;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 玩家自定义称号购买确认 GUI。 */
public final class PlayerCustomConfirmGui extends SimpleGui {
    private final CrownServerContext context;
    private final UUID sessionId;
    private final TitleContent content;
    private final GuiLayout layout;
    private boolean terminalAction;
    private final CoreSettings.CustomTitle quotedSettings;

    private PlayerCustomConfirmGui(
            CrownServerContext context, ServerPlayer player, MenuType<?> type,
            UUID sessionId, TitleContent content, GuiLayout layout
    ) {
        super(type, player, false);
        this.context = context;
        this.sessionId = sessionId;
        this.content = content;
        this.layout = layout;
        this.quotedSettings = context.core().customTitle();
    }

    public static void open(
            CrownServerContext context, ServerPlayer player,
            UUID sessionId, TitleContent content
    ) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(player);
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(content);
        GuiLayout layout = context.runtime().snapshot().gui().require("custom-confirm");
        PlayerCustomConfirmGui gui = new PlayerCustomConfirmGui(
                context, player, menuType(layout.screenType()),
                sessionId, content, layout);
        gui.setLockPlayerInventory(true);
        gui.setTitle(context.messages().renderRaw(layout.title()));
        gui.draw();
        gui.open();
    }

    private void draw() {
        GuiItems items = new GuiItems(context.messages());
        if (layout.fillerEnabled()) {
            GuiElementBuilder filler = items.build(layout.filler(), Map.of());
            for (int slot = 0; slot < getSize(); slot++) setSlot(slot, filler);
        }
        var settings = context.core().customTitle();
        var languages = context.runtime().snapshot().languages();
        PaymentPolicy primary = settings.payment();
        Map<String, String> variables = Map.of(
                "title_preview", GuiFormatting.previewSource(content),
                "title_source", content.textSource(),
                "price", GuiFormatting.priceText(primary, layout.textValues()),
                "currency", GuiFormatting.currencyText(
                        primary, context.core()),
                "title_coin_price", GuiFormatting.priceText(primary, layout.textValues()),
                "title_coin_unit", context.core().titleCoin().name(),
                "duration", GuiFormatting.durationText(settings.duration(), layout.textValues()));
        Map<String, List<String>> multiline = Map.of(
                "payment_price_lines", List.of(layout.textValue("title-coin-price-line")));
        for (GuiButton button : layout.buttons().values()) {

            if ("processing".equals(button.action())) continue;
            GuiElementBuilder element = items.build(button.item(), variables, multiline);
            element.setCallback((index, clickType, input, gui) ->
                    handleButton(button.action(), clickType));
            setSlot(button.slot(), element);
        }
    }

    private void handleButton(String action, ClickType clickType) {
        if (!clickType.isLeft || terminalAction) return;
        switch (action) {
            case "confirm", "pay-title-coin" -> {
                if (!quotedSettings.equals(context.core().customTitle())) {
                    getPlayer().sendSystemMessage(context.messages().render("purchase.changed"));
                    close();
                    return;
                }
                terminalAction = true;
                close();
                PlayerCustomTitleSessions.confirm(context, getPlayer(), sessionId);
            }
            case "reenter" -> {
                terminalAction = true;
                close();
                PlayerCustomTitleSessions.reenter(context, getPlayer(), sessionId);
            }
            case "cancel" -> {
                terminalAction = true;
                close();
                PlayerCustomTitleSessions.cancel(
                        context, getPlayer(), sessionId, true);
            }
            default -> { }
        }
    }

    @Override
    public void onPlayerClose(boolean serverInitiated) {
        if (!terminalAction) {
            terminalAction = true;
            PlayerCustomTitleSessions.cancel(
                    context, getPlayer(), sessionId, false);
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
