package dev.xiaomu.crown.fabric.gui;

import dev.xiaomu.crown.config.model.GuiButton;
import dev.xiaomu.crown.config.model.GuiItemTemplate;
import dev.xiaomu.crown.config.model.GuiLayout;
import dev.xiaomu.crown.config.model.GuiScreenType;
import dev.xiaomu.crown.domain.catalog.TitleDefinition;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.command.CrownTitleAdminCommands;
import dev.xiaomu.crown.fabric.custom.AdminTitleDraftSessions;
import dev.xiaomu.crown.fabric.custom.AdminTitlePaymentEditSessions;
import dev.xiaomu.crown.fabric.custom.AdminTitleSaleEditSessions;
import dev.xiaomu.crown.fabric.custom.AdminTitleTextEditSessions;
import dev.xiaomu.crown.fabric.display.CrownNametagDisplay;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 管理员商品列表与详情 GUI。
 *
 * <p>列表布局来自 admin-shop.yml；详情页保持服务端虚拟箱子形式，不展示
 * Crown 不具备的 BUFF、粒子或属性槽。修改操作仍统一经过
 * TitleCatalogEditor 的完整校验、原子替换、内部重载和数据库审计。</p>
 */
public final class CrownAdminShopGui extends SimpleGui {
    private final CrownServerContext context;
    private final List<TitleDefinition> catalog;
    private final int[] contentSlots;
    private int page;

    private CrownAdminShopGui(
            CrownServerContext context,
            ServerPlayer player,
            MenuType<?> type,
            List<TitleDefinition> catalog,
            int[] contentSlots
    ) {
        super(type, player, false);
        this.context = context;
        this.catalog = catalog;
        this.contentSlots = contentSlots;
    }

    public static void open(
            CrownServerContext context,
            ServerPlayer player
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(player, "player");
        GuiLayout layout = context.runtime().snapshot()
                .gui().require("admin-shop");
        List<TitleDefinition> definitions = new ArrayList<>(
                context.runtime().snapshot().catalog()
                        .definitions().values());
        int[] slots = layout.contentSlots().stream()
                .mapToInt(Integer::intValue).toArray();
        CrownAdminShopGui gui = new CrownAdminShopGui(
                context, player, menuType(layout.screenType()),
                definitions, slots);
        gui.setLockPlayerInventory(true);
        gui.setTitle(context.messages().renderRaw(layout.title()));
        gui.draw(layout);
        CrownGuiSessions.adminShop(context, player);
        gui.open();
    }

    public static void openDetail(
            CrownServerContext context,
            ServerPlayer player,
            String id
    ) {
        CrownGuiSessions.clear(player);
        TitleDefinition definition = context.runtime().snapshot()
                .catalog().find(id).orElse(null);
        if (definition == null) {
            player.sendSystemMessage(context.messages().render(
                    "admin.title.failed", id, context.runtime().snapshot()
                            .languages().text("gui.reason.unavailable")));
            return;
        }
        GuiLayout layout = context.runtime().snapshot().gui()
                .require("admin-shop");
        DetailGui gui = new DetailGui(context, player, definition, layout);
        gui.setLockPlayerInventory(true);
        gui.setTitle(context.messages().renderRaw(
                "&c商品管理：&f" + id));
        gui.draw();
        gui.open();
    }

    private void draw(GuiLayout layout) {
        GuiItems items = new GuiItems(context.messages());
        if (layout.fillerEnabled()) {
            GuiElementBuilder filler = items.build(
                    layout.filler(), Map.of());
            for (int slot = 0; slot < getSize(); slot++) {
                setSlot(slot, filler);
            }
        }

        Map<String, String> pages = Map.of(
                "page", Integer.toString(page + 1),
                "pages", Integer.toString(pageCount()));
        for (GuiButton button : layout.buttons().values()) {
            setSlot(button.slot(), items.build(button.item(), pages)
                    .setCallback((index, click, input, gui) ->
                            button(button.action(), click)));
        }

        int start = page * contentSlots.length;
        for (int offset = 0; offset < contentSlots.length; offset++) {
            int index = start + offset;
            if (index >= catalog.size()) {
                clearSlot(contentSlots[offset]);
                continue;
            }
            TitleDefinition definition = catalog.get(index);
            GuiItemTemplate template = layout.itemVariants().get(
                    definition.enabled() ? "enabled" : "disabled");
            if (template == null) {
                clearSlot(contentSlots[offset]);
                continue;
            }
            Map<String, String> variables = Map.of(
                    "title_icon", definition.icon().serialized(),
                    "title_preview", GuiFormatting.previewSource(
                            definition.content()),
                    "definition_id", definition.id().value());
            setSlot(contentSlots[offset], items.build(template, variables)
                    .setCallback((slot, click, input, gui) -> {
                        if (click.isLeft) {
                            close();
                            openDetail(context, getPlayer(),
                                    definition.id().value());
                        }
                    }));
        }
    }

    private void button(String action, ClickType click) {
        if (!click.isLeft) return;
        switch (action) {
            case "close" -> {
                CrownGuiSessions.clear(getPlayer());
                close();
            }
            case "previous" -> {
                if (page > 0) {
                    page--;
                    draw(context.runtime().snapshot()
                            .gui().require("admin-shop"));
                }
            }
            case "next" -> {
                if (page + 1 < pageCount()) {
                    page++;
                    draw(context.runtime().snapshot()
                            .gui().require("admin-shop"));
                }
            }
            case "reload" -> {
                CompletableFuture<?> reload = context.runtime().reloadAsync(
                        CompletableFuture.delayedExecutor(0,
                                java.util.concurrent.TimeUnit.MILLISECONDS));
                context.mainThread().whenComplete(reload, ignored -> {
                    CrownNametagDisplay.refresh(context);
                    CrownGuiSessions.refresh(context);
                    getPlayer().sendSystemMessage(context.messages().render(
                            "command.reload.success"));
                }, exception -> {
                    getPlayer().sendSystemMessage(context.messages().render(
                            "command.reload.failed",
                            safeMessage(exception)));
                });
            }
            case "create" -> {
                CrownGuiSessions.clear(getPlayer());
                close();
                if (!AdminTitleDraftSessions.begin(context, getPlayer())) {
                    getPlayer().sendSystemMessage(context.messages().render(
                            "admin.title.create.busy"));
                }
            }
            default -> {
            }
        }
    }

    private int pageCount() {
        return catalog.isEmpty() || contentSlots.length == 0
                ? 1
                : (catalog.size() + contentSlots.length - 1)
                / contentSlots.length;
    }

    private static final class ReloadFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ReloadFailedException(java.io.IOException cause) {
            super(cause);
        }
    }

    private static final class DetailGui extends SimpleGui {
        private final CrownServerContext context;
        private final TitleDefinition definition;
        private final GuiLayout layout;
        private final GuiItems items;

        private DetailGui(
                CrownServerContext context,
                ServerPlayer player,
                TitleDefinition definition,
                GuiLayout layout
        ) {
            super(MenuType.GENERIC_9x6, player, false);
            this.context = context;
            this.definition = definition;
            this.layout = layout;
            this.items = new GuiItems(context.messages());
        }

        private void draw() {
            GuiItemTemplate filler = item(
                    "minecraft:black_stained_glass_pane", "", List.of());
            GuiElementBuilder fillerElement =
                    items.build(filler, Map.of());
            for (int slot = 0; slot < getSize(); slot++) {
                setSlot(slot, fillerElement);
            }

            Map<String, String> variables = variables();
            setSlot(13, items.build(item(
                    definition.icon().serialized(),
                    "{title_preview}",
                    List.of(
                            layout.textValue("detail-id"),
                            layout.textValue("detail-category"),
                            layout.textValue("detail-price"),
                            layout.textValue("detail-duration"))),
                    variables));

            String toggleName = layout.textValue(definition.enabled()
                    ? "enabled" : "disabled");
            setSlot(20, items.build(item(
                    definition.enabled()
                            ? "minecraft:lime_dye"
                            : "minecraft:gray_dye",
                    toggleName,
                    List.of(layout.textValue("toggle-hint"),
                            layout.textValue("toggle-visible"))),
                    variables).setCallback((slot, click, input, gui) -> {
                if (!click.isLeft) return;
                boolean enable = !definition.enabled();
                CrownTitleAdminCommands.updateFromGui(
                        context, getPlayer(), definition.id().value(),
                        enable ? "enable" : "disable",
                        enable
                                ? Map.of("enabled", true, "visible", true)
                                : Map.of("enabled", false),
                        () -> reopen());
            }));

            setSlot(22, items.build(item(
                    "minecraft:item_frame",
                    layout.textValue("change-icon"),
                    List.of(layout.textValue("change-icon-hint"),
                            layout.textValue("current-icon")
                                    .replace("{icon}",
                                            definition.icon().serialized()))),
                    variables).setCallback((slot, click, input, gui) -> {
                if (!click.isLeft) return;
                var stack = getPlayer().getMainHandItem();
                if (stack.isEmpty() || stack.is(Items.AIR)) {
                    getPlayer().sendSystemMessage(context.messages().render(
                            "admin.title.icon.empty-hand"));
                    return;
                }
                String icon = BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString();
                CrownTitleAdminCommands.updateFromGui(
                        context, getPlayer(), definition.id().value(),
                        "icon", Map.of("icon", icon), this::reopen);
            }));

            setSlot(24, items.build(item(
                    "minecraft:clock",
                    layout.textValue("sale-settings"),
                    List.of(layout.textValue("sale-start")
                                    .replace("{value}", definition.sale()
                                            .start().map(Object::toString)
                                            .orElse(layout.textValue("unlimited"))),
                            layout.textValue("sale-end")
                                    .replace("{value}", definition.sale()
                                            .end().map(Object::toString)
                                            .orElse(layout.textValue("unlimited"))),
                            layout.textValue("stock")
                                    .replace("{value}", GuiFormatting.limitText(
                                            definition.sale().globalStock(),
                                            layout.textValues())),
                            layout.textValue("player-limit")
                                    .replace("{value}", GuiFormatting.limitText(
                                            definition.sale().perPlayerLimit(),
                                            layout.textValues())),
                            "",
                            layout.textValue("sale-hint"))),
                    variables).setCallback((slot, click, input, gui) -> {
                if (!click.isLeft) return;
                CrownGuiSessions.clear(getPlayer());
                close();
                if (!AdminTitleSaleEditSessions.begin(
                        context, getPlayer(), definition.id().value())) {
                    getPlayer().sendSystemMessage(context.messages().render(
                            "admin.title.edit.sale.busy"));
                }
            }));

            setSlot(29, items.build(item(
                    "minecraft:gold_ingot",
                    layout.textValue("payment"),
                    List.of(layout.textValue("payment-type")
                                    .replace("{type}", GuiFormatting
                                            .paymentTypeText(
                                                    definition.payment().type(),
                                                    layout.textValues())),
                            layout.textValue("payment-price"),
                            "",
                            layout.textValue("payment-hint"))),
                    variables).setCallback((slot, click, input, gui) -> {
                if (!click.isLeft) return;
                CrownGuiSessions.clear(getPlayer());
                close();
                if (!AdminTitlePaymentEditSessions.begin(
                        context, getPlayer(), definition.id().value())) {
                    getPlayer().sendSystemMessage(context.messages().render(
                            "admin.title.edit.payment.busy"));
                }
            }));
            setSlot(31, items.build(item(
                    "minecraft:writable_book",
                    layout.textValue("duration"),
                    List.of(layout.textValue("detail-duration"),
                            "", layout.textValue("duration-hint"))),
                    variables).setCallback((slot, click, input, gui) -> {
                if (!click.isLeft) return;
                CrownGuiSessions.clear(getPlayer());
                close();
                if (!AdminTitleSaleEditSessions.begin(
                        context, getPlayer(), definition.id().value())) {
                    getPlayer().sendSystemMessage(context.messages().render(
                            "admin.title.edit.sale.busy"));
                }
            }));
            setSlot(40, items.build(item(
                    "minecraft:tnt", layout.textValue("delete"),
                    List.of(layout.textValue("delete-no-refund"),
                            layout.textValue("delete-irreversible"))),
                    variables).setCallback((slot, click, input, gui) -> {
                if (!click.isLeft) return;
                CrownGuiSessions.clear(getPlayer());
                close();
                CrownAdminTitleDeleteConfirmGui.open(
                        context, getPlayer(), definition.id().value());
            }));
            setSlot(33, items.build(item(
                    "minecraft:name_tag",
                    layout.textValue("text-permission"),
                    List.of(layout.textValue("permission")
                                    .replace("{permission}", definition
                                            .permission().orElse(layout
                                                    .textValue("none"))),
                            "",
                            layout.textValue("text-hint"))),
                    variables).setCallback((slot, click, input, gui) -> {
                if (!click.isLeft) return;
                CrownGuiSessions.clear(getPlayer());
                close();
                if (!AdminTitleTextEditSessions.begin(
                        context, getPlayer(), definition.id().value())) {
                    getPlayer().sendSystemMessage(context.messages().render(
                            "admin.title.edit.text.busy"));
                }
            }));

            setSlot(45, items.build(item(
                    "minecraft:arrow", layout.textValue("back"), List.of()),
                    variables).setCallback((slot, click, input, gui) -> {
                if (click.isLeft) {
                    CrownGuiSessions.clear(getPlayer());
                    close();
                    CrownAdminShopGui.open(context, getPlayer());
                }
            }));
            setSlot(49, items.build(item(
                    "minecraft:repeater", layout.textValue("refresh"), List.of()),
                    variables).setCallback((slot, click, input, gui) -> {
                if (click.isLeft) reopen();
            }));
            setSlot(53, items.build(item(
                    "minecraft:barrier", layout.textValue("close"), List.of()),
                    variables).setCallback((slot, click, input, gui) -> {
                if (click.isLeft) close();
            }));
        }

        private Map<String, String> variables() {
            var languages = context.runtime().snapshot().languages();
            return Map.of(
                    "title_preview", GuiFormatting.previewSource(
                            definition.content()),
                    "definition_id", definition.id().value(),
                    "category", definition.category(),
                    "price", GuiFormatting.priceText(definition.payment(),
                            layout.textValues()),
                    "mint_unit", context.core().purchase().mintCurrencyName(),
                    "title_coin_unit", context.core().titleCoin().name(),
                    "price_unit", GuiFormatting.currencyText(
                            definition.payment(), context.core()),
                    "duration", GuiFormatting.durationText(
                            definition.duration(), layout.textValues()));
        }

        private void reopen() {
            close();
            openDetail(context, getPlayer(), definition.id().value());
        }
    }

    private static GuiItemTemplate item(
            String id,
            String name,
            List<String> lore
    ) {
        return new GuiItemTemplate(
                id, name, lore, 1, false,
                false, null, "", false);
    }

    private static String safeMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message.substring(0, Math.min(message.length(), 160));
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