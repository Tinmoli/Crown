package dev.xiaomu.crown.fabric.gui;

import dev.xiaomu.crown.config.lang.LanguageCatalog;
import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.config.model.GuiButton;
import dev.xiaomu.crown.config.model.GuiItemTemplate;
import dev.xiaomu.crown.config.model.GuiLayout;
import dev.xiaomu.crown.config.model.GuiScreenType;
import dev.xiaomu.crown.domain.catalog.TitleDefinition;
import dev.xiaomu.crown.domain.catalog.PaymentType;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.custom.PlayerCustomTitleSessions;
import dev.xiaomu.crown.fabric.permission.CrownPermissions;
import dev.xiaomu.crown.fabric.text.CrownMessages;
import dev.xiaomu.crown.runtime.platform.PermissionSource;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import dev.xiaomu.crown.storage.model.OwnedTitleStatus;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Crown 称号商城 GUI（DESIGN.md §16）。
 *
 * <p>渲染前先在存储线程读取玩家已拥有的称号集合，再切回主线程打开界面，
 * 据此展示 available/owned/unavailable 三态。翻页仅重排内容槽，不重新查库。</p>
 */
public final class CrownShopGui extends SimpleGui {
    private final CrownServerContext context;
    private final List<TitleDefinition> catalog;
    private final Set<String> ownedDefinitionIds;
    private final int[] contentSlots;
    private int page;

    private CrownShopGui(
            CrownServerContext context,
            ServerPlayer player,
            MenuType<?> type,
            List<TitleDefinition> catalog,
            Set<String> ownedDefinitionIds,
            int[] contentSlots
    ) {
        super(type, player, false);
        this.context = context;
        this.catalog = catalog;
        this.ownedDefinitionIds = ownedDefinitionIds;
        this.contentSlots = contentSlots;
    }

    /** 打开商城；在主线程调用，内部异步读取已拥有称号后再渲染。 */
    public static void open(
            CrownServerContext context,
            ServerPlayer player
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(player, "player");
        UUID playerId = player.getUUID();

        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() ->
                        ownedDefinitionIds(context, playerId)),
                owned -> render(context, player, owned),
                failure -> player.sendSystemMessage(context.messages()
                        .render("purchase.failed.storage")));
    }

    private static Set<String> ownedDefinitionIds(
            CrownServerContext context,
            UUID playerId
    ) {
        Set<String> owned = new HashSet<>();
        Instant now = Instant.now();
        for (OwnedTitleRecord record :
                context.runtime().wardrobe().listOwned(playerId)) {
            if (record.status() == OwnedTitleStatus.ACTIVE
                    && !record.expiredAt(now)) {
                record.definition().ifPresent(id ->
                        owned.add(id.value()));
            }
        }
        return owned;
    }

    private static void render(
            CrownServerContext context,
            ServerPlayer player,
            Set<String> owned
    ) {
        GuiLayout layout = context.runtime().snapshot().gui().require("shop");
        List<TitleDefinition> catalog = new ArrayList<>();
        for (TitleDefinition definition : context.runtime().snapshot()
                .catalog().definitions().values()) {
            if (definition.enabled() && definition.visible()) {
                catalog.add(definition);
            }
        }
        int[] slots = layout.contentSlots().stream()
                .mapToInt(Integer::intValue).toArray();

        CrownShopGui gui = new CrownShopGui(
                context, player, menuType(layout.screenType()),
                catalog, owned, slots);
        gui.setLockPlayerInventory(true);
        gui.setTitle(context.messages().renderRaw(layout.title()));
        gui.draw(layout);
        CrownGuiSessions.shop(context, player);
        gui.open();
    }

    private void draw(GuiLayout layout) {
        CrownMessages messages = context.messages();
        GuiItems items = new GuiItems(messages);
        Map<String, String> emptyVariables = Map.of();

        if (layout.fillerEnabled()) {
            GuiElementBuilder filler =
                    items.build(layout.filler(), emptyVariables);
            for (int slot = 0; slot < getSize(); slot++) {
                setSlot(slot, filler);
            }
        }

        int pageCount = pageCount();
        Map<String, String> pageVariables = Map.of(
                "page", Integer.toString(page + 1),
                "pages", Integer.toString(pageCount));

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
        CoreSettings core = context.core();
        Instant now = Instant.now();
        int perPage = contentSlots.length;
        int start = page * perPage;

        for (int offset = 0; offset < perPage; offset++) {
            int slot = contentSlots[offset];
            int catalogIndex = start + offset;
            if (catalogIndex >= catalog.size()) {
                // Remove the filler so unused content slots are genuinely empty.
                clearSlot(slot);
                continue;
            }
            TitleDefinition definition = catalog.get(catalogIndex);
            setSlot(slot, safeTitleElement(
                    items, layout, definition, core, now));
        }
    }

    private GuiElementBuilder titleElement(
            GuiItems items,
            GuiLayout layout,
            TitleDefinition definition,
            CoreSettings core,
            Instant now
    ) {
        String variantKey = variantFor(definition, now);
        var languages = context.runtime().snapshot().languages();
        GuiItemTemplate template =
                layout.itemVariants().get(variantKey);
        if (template == null) {
            template = layout.filler();
        }

        Map<String, String> variables = Map.of(
                "title_icon", definition.icon().serialized(),
                "title_preview", GuiFormatting.previewSource(
                        definition.content()),
                "mint_price", optionPrice(definition, PaymentType.MINT,
                        layout.textValues()),
                "title_coin_price", optionPrice(definition,
                        PaymentType.TITLE_COIN, layout.textValues()),
                "mint_unit", core.purchase().mintCurrencyName(),
                "title_coin_unit", core.titleCoin().name(),
                "duration", GuiFormatting.durationText(
                        definition.duration(), layout.textValues()),
                "unavailable_reason", unavailableReason(definition, now, languages));
        Map<String, List<String>> multiline = Map.of(
                "title_description", definition.description());

        GuiElementBuilder element =
                items.build(template, variables, multiline);
        if ("available".equals(variantKey)) {
            element.setCallback((index, clickType, input, gui) -> {
                if (clickType.isLeft) {
                    CrownGuiSessions.clear(getPlayer());
                    CrownPurchaseConfirmGui.open(context, getPlayer(),
                            definition);
                }
            });
        }
        return element;
    }

    private static String optionPrice(TitleDefinition definition,
                                      PaymentType type,
                                      Map<String, String> textValues) {
        return definition.paymentOptions().stream()
                .filter(payment -> payment.type() == type)
                .findFirst()
                .map(payment -> GuiFormatting.priceText(payment, textValues))
                .orElse("");
    }

    private GuiElementBuilder safeTitleElement(
            GuiItems items,
            GuiLayout layout,
            TitleDefinition definition,
            CoreSettings core,
            Instant now
    ) {
        try {
            GuiElementBuilder element = titleElement(
                    items, layout, definition, core, now);
            return element == null ? barrierElement() : element;
        } catch (RuntimeException exception) {
            return barrierElement();
        }
    }

    private static GuiElementBuilder barrierElement() {
        return new GuiElementBuilder(net.minecraft.world.item.Items.BARRIER);
    }

    private String variantFor(TitleDefinition definition, Instant now) {
        if (ownedDefinitionIds.contains(definition.id().value())) {
            return "owned";
        }
        if (!definition.sale().onSaleAt(now)) {
            return "unavailable";
        }
        if (definition.permission().isPresent()
                && !hasTitlePermission(definition)) {
            return "unavailable";
        }
        return "available";
    }

    private String unavailableReason(
            TitleDefinition definition,
            Instant now,
            LanguageCatalog languages
    ) {
        if (!definition.sale().onSaleAt(now)) {
            return "Outside sale period";
        }
        if (definition.permission().isPresent()
                && !hasTitlePermission(definition)) {
            return "Missing purchase permission";
        }
        return "Currently unavailable";
    }

    private boolean hasTitlePermission(TitleDefinition definition) {
        return context.permissions().checkSource(
                PermissionSource.of(getPlayer().createCommandSourceStack()),
                CrownPermissions.title(definition.id().value()),
                0);
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
                    draw(context.runtime().snapshot().gui().require("shop"));
                }
            }
            case "next" -> {
                if (page + 1 < pageCount()) {
                    page++;
                    draw(context.runtime().snapshot().gui().require("shop"));
                }
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
                // 未知动作忽略。
            }
        }
    }

    private int pageCount() {
        if (catalog.isEmpty() || contentSlots.length == 0) {
            return 1;
        }
        return (catalog.size() + contentSlots.length - 1)
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
}