package dev.xiaomu.crown.fabric.gui;

import dev.xiaomu.crown.config.model.GuiButton;
import dev.xiaomu.crown.config.model.GuiLayout;
import dev.xiaomu.crown.config.model.GuiScreenType;
import dev.xiaomu.crown.domain.catalog.DurationPolicy;
import dev.xiaomu.crown.domain.catalog.TitleContent;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.custom.CustomTitleInputSessions;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 管理员自定义称号免费发放确认页。
 *
 * <p>复用 custom-confirm.yml 的布局和预览样式，但确认按钮明确标注为
 * “免费发放”，不会显示玩家购买流程中的付款语义。</p>
 */
public final class AdminCustomConfirmGui extends SimpleGui {
    private final CrownServerContext context;
    private final UUID sessionId;
    private final String targetName;
    private final DurationPolicy duration;
    private final TitleContent content;
    private final GuiLayout layout;
    private boolean terminalAction;

    private AdminCustomConfirmGui(
            CrownServerContext context,
            ServerPlayer administrator,
            MenuType<?> type,
            UUID sessionId,
            String targetName,
            DurationPolicy duration,
            TitleContent content,
            GuiLayout layout
    ) {
        super(type, administrator, false);
        this.context = context;
        this.sessionId = sessionId;
        this.targetName = targetName;
        this.duration = duration;
        this.content = content;
        this.layout = layout;
    }

    public static void open(
            CrownServerContext context,
            ServerPlayer administrator,
            UUID sessionId,
            String targetName,
            DurationPolicy duration,
            TitleContent content
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(administrator, "administrator");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(targetName, "targetName");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(content, "content");

        GuiLayout layout = context.runtime().snapshot().gui()
                .require("custom-confirm");
        AdminCustomConfirmGui gui = new AdminCustomConfirmGui(
                context,
                administrator,
                menuType(layout.screenType()),
                sessionId,
                targetName,
                duration,
                content,
                layout);
        gui.setLockPlayerInventory(true);
        gui.setTitle(context.messages().renderRaw(layout.title()));
        gui.draw();
        gui.open();
    }

    private void draw() {
        GuiItems items = new GuiItems(context.messages());
        if (layout.fillerEnabled()) {
            GuiElementBuilder filler =
                    items.build(layout.filler(), Map.of());
            for (int slot = 0; slot < getSize(); slot++) {
                setSlot(slot, filler);
            }
        }

        Map<String, String> variables = Map.of(
                "title_preview", GuiFormatting.previewSource(content),
                "title_source", content.textSource(),
                "price", layout.textValue("admin-free"),
                "currency", "",
                "duration", GuiFormatting.durationText(duration, layout.textValues()),
                "target_player", targetName);

        for (GuiButton button : layout.buttons().values()) {
            if ("processing".equals(button.action())) {
                continue;
            }
            GuiElementBuilder element = items.build(button.item(), variables);
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
                CustomTitleInputSessions.confirm(
                        context, getPlayer(), sessionId);
            }
            case "reenter" -> {
                terminalAction = true;
                close();
                CustomTitleInputSessions.reenter(
                        context, getPlayer(), sessionId);
            }
            case "cancel" -> {
                terminalAction = true;
                close();
                CustomTitleInputSessions.cancel(
                        context, getPlayer(), sessionId, true);
            }
            default -> {
                // preview 和未知动作不处理。
            }
        }
    }

    @Override
    public void onPlayerClose(boolean serverInitiated) {
        if (!terminalAction) {
            terminalAction = true;
            CustomTitleInputSessions.cancel(
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