package dev.xiaomu.crown.fabric.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.display.CrownNametagDisplay;
import dev.xiaomu.crown.fabric.gui.CrownMainGui;
import dev.xiaomu.crown.fabric.gui.CrownGuiSessions;
import dev.xiaomu.crown.fabric.permission.CrownPermissions;
import dev.xiaomu.crown.fabric.permission.FabricPermissionService;
import dev.xiaomu.crown.fabric.text.CrownMessages;
import dev.xiaomu.crown.runtime.platform.PermissionSource;
import dev.xiaomu.crown.runtime.wardrobe.EquipResult;
import dev.xiaomu.crown.storage.model.CoinAdjustmentResult;
import dev.xiaomu.crown.storage.model.PlayerRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Crown 的 /crown 命令树。
 *
 * <p>命令处理器只做参数解析与权限判定，随后把数据库操作提交到存储执行器，
 * 完成后经 {@link dev.xiaomu.crown.fabric.platform.ServerThreadExecutor}
 * 切回主线程发送消息，全程不阻塞服务器线程。</p>
 */
public final class CrownCommandTree {
    private CrownCommandTree() {
    }

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CrownServerContext context
    ) {
        register(dispatcher, context, false);
    }

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CrownServerContext context,
            boolean titleAliasEnabled
    ) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(context, "context");

        LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("crown")
                        .executes(ctx -> openMainGui(context, ctx))
                        .then(help(context))
                        .then(open(context))
                        .then(warehouse(context))
                        .then(shop(context))
                        .then(buy(context))
                        .then(custom(context))
                        .then(delete(context))
                        .then(card(context))
                        .then(info(context))
                        .then(reload(context))
                        .then(coin(context))
                        .then(equip(context))
                        .then(unequip(context))
                        .then(CrownTitleAdminCommands.title(context))
                        .then(CrownAdminCommands.player(context))
                        .then(CrownAdminCommands.view(context))
                        .then(CrownAdminCommands.storage(context))
                        .then(CrownAdminCommands.audit(context));

        dispatcher.register(root);
        if (titleAliasEnabled) registerAlias(dispatcher, context);
    }

    public static void registerAlias(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CrownServerContext context
    ) {
        dispatcher.register(Commands.literal("title")
                .requires(source -> can(
                        context, source,
                        CrownPermissions.COMMAND_OPEN, 0))
                .executes(ctx -> openMainGui(context, ctx))
                .redirect(dispatcher.getRoot().getChild("crown")));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> help(
            CrownServerContext context
    ) {
        return Commands.literal("help")
                .requires(source -> can(
                        context, source, CrownPermissions.COMMAND_OPEN, 0))
                .executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    sendHelp(context, source, "command.help.player");
                    if (can(context, source, CrownPermissions.ADMIN_RELOAD, 3)) {
                        sendHelp(context, source, "command.help.admin");
                    }
                    if (can(context, source, CrownPermissions.ADMIN_TITLE, 3)) {
                        sendHelp(context, source, "command.help.title-admin");
                    }
                    return 1;
                });
    }

    private static void sendHelp(
            CrownServerContext context,
            CommandSourceStack source,
            String key
    ) {
        String template = context.runtime().snapshot().languages().text(key)
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replaceAll("[;；](?=&f/crown)", "\n");
        for (String line : template.split("\\R", -1)) {
            if (line.isBlank()) {
                continue;
            }
            source.sendSuccess(() -> context.messages().renderRaw(line,
                    context.runtime().snapshot().languages().text("prefix")), false);
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> open(
            CrownServerContext context
    ) {
        return Commands.literal("open")
                .requires(source -> can(
                        context, source, CrownPermissions.COMMAND_OPEN, 0))
                .executes(ctx -> openWarehouseGui(context, ctx));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> warehouse(
            CrownServerContext context
    ) {
        return Commands.literal("warehouse")
                .requires(source -> can(
                        context, source, CrownPermissions.COMMAND_OPEN, 0))
                .executes(ctx -> openWarehouseGui(context, ctx));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> shop(
            CrownServerContext context
    ) {
        return Commands.literal("shop")
                .requires(source -> can(
                        context, source, CrownPermissions.COMMAND_SHOP, 0))
                .executes(ctx -> {
                    ServerPlayer player = requirePlayer(context, ctx);
                    if (player == null) {
                        return 0;
                    }
                    dev.xiaomu.crown.fabric.gui.CrownShopGui.open(
                            context, player);
                    return 1;
                });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buy(
            CrownServerContext context
    ) {
        return Commands.literal("buy")
                .requires(source -> can(
                        context, source, CrownPermissions.COMMAND_BUY, 0))
                .then(Commands.argument(
                                "title", StringArgumentType.word())
                        .suggests((ctx, builder) -> suggestDefinitions(
                                context, builder))
                        .executes(ctx -> {
                            ServerPlayer player =
                                    requirePlayer(context, ctx);
                            if (player == null) {
                                return 0;
                            }
                            String id = StringArgumentType.getString(
                                    ctx, "title");
                            var definition = context.runtime().snapshot()
                                    .catalog().find(id).orElse(null);
                            if (definition == null) {
                                ctx.getSource().sendFailure(
                                        context.messages().render(
                                                "shop.unavailable", id));
                                return 0;
                            }
                            dev.xiaomu.crown.fabric.gui
                                    .CrownPurchaseConfirmGui.open(
                                    context, player, definition);
                            return 1;
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> custom(
            CrownServerContext context
    ) {
        return Commands.literal("custom")
                .requires(source -> can(
                        context, source, CrownPermissions.COMMAND_CUSTOM, 0))
                .executes(ctx -> {
                    ServerPlayer player = requirePlayer(context, ctx);
                    if (player == null) return 0;
                    if (!context.core().customTitle().enabled()) {
                        ctx.getSource().sendFailure(context.messages()
                                .render("shop.unavailable", "custom"));
                        return 0;
                    }
                    boolean started = dev.xiaomu.crown.fabric.custom
                            .PlayerCustomTitleSessions.begin(context, player);
                    if (!started) {
                        ctx.getSource().sendFailure(context.messages()
                                .render("purchase.processing"));
                        return 0;
                    }
                    return 1;
                });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> delete(
            CrownServerContext context
    ) {
        return Commands.literal("delete")
                .requires(source -> can(
                        context, source, CrownPermissions.COMMAND_OPEN, 0))
                .then(Commands.argument(
                                "entry", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer player =
                                    requirePlayer(context, ctx);
                            if (player == null) {
                                return 0;
                            }
                            UUID entryId;
                            try {
                                entryId = UUID.fromString(
                                        StringArgumentType.getString(
                                                ctx, "entry"));
                            } catch (IllegalArgumentException exception) {
                                ctx.getSource().sendFailure(
                                        context.messages().render(
                                                "warehouse.expired"));
                                return 0;
                            }
                            dev.xiaomu.crown.fabric.gui
                                    .CrownDeleteConfirmGui.open(
                                            context, player, entryId);
                            return 1;
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> card(
            CrownServerContext context
    ) {
        return Commands.literal("card")
                .then(Commands.literal("redeem")
                        .requires(source -> can(
                                context, source,
                                CrownPermissions.COMMAND_CARD, 0))
                        .executes(ctx -> {
                            ServerPlayer player =
                                    requirePlayer(context, ctx);
                            if (player == null) {
                                return 0;
                            }
                            return dev.xiaomu.crown.fabric.card
                                    .CrownCardRedemption.redeemAnyHand(
                                            context, player) ? 1 : 0;
                        }))
                .then(CrownAdminCommands.cardCreate(context));
    }

    static java.util.concurrent.CompletableFuture<
            com.mojang.brigadier.suggestion.Suggestions>
    suggestDefinitions(
            CrownServerContext context,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder
    ) {
        for (var definition : context.runtime().snapshot()
                .catalog().definitions().values()) {
            if (definition.enabled() && definition.visible()) {
                builder.suggest(definition.id().value());
            }
        }
        return builder.buildFuture();
    }

    private static int openWarehouseGui(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx
    ) {
        ServerPlayer player = requirePlayer(context, ctx);
        if (player == null) {
            return 0;
        }
        dev.xiaomu.crown.fabric.gui.CrownWarehouseGui.open(context, player);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> info(
            CrownServerContext context
    ) {
        return Commands.literal("info")
                .requires(source -> can(
                        context, source, CrownPermissions.ADMIN_INFO, 2))
                .executes(ctx -> {
                    CrownMessages messages = context.messages();
                    var snapshot = context.runtime().snapshot();
                    String storage = snapshot.storage().type().name()
                            .toLowerCase(java.util.Locale.ROOT);
                    String titleCount = Integer.toString(
                            snapshot.catalog().definitions().size());
                    ctx.getSource().sendSuccess(
                            () -> messages.render(
                                    "command.info",
                                    "0.1.0", storage, titleCount),
                            false);
                    return 1;
                });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> reload(
            CrownServerContext context
    ) {
        return Commands.literal("reload")
                .requires(source -> can(
                        context, source, CrownPermissions.ADMIN_RELOAD, 3))
                .executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    CompletableFuture<?> reload = context.runtime().reloadAsync(
                            CompletableFuture.delayedExecutor(0,
                                    java.util.concurrent.TimeUnit.MILLISECONDS));
                    context.mainThread().whenComplete(reload, ignored -> {
                        CrownNametagDisplay.refresh(context);
                        CrownGuiSessions.refresh(context);
                        source.sendSuccess(
                                () -> context.messages()
                                        .render("command.reload.success"),
                                true);
                    }, exception -> {
                        source.sendFailure(context.messages().render(
                                "command.reload.failed",
                                safeMessage(exception)));
                    });
                    return 1;
                });
    }

    private static final class ReloadFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ReloadFailedException(IOException cause) {
            super(cause);
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> coin(
            CrownServerContext context
    ) {
        return Commands.literal("coin")
                .then(Commands.literal("balance")
                        .requires(source -> can(
                                context, source,
                                CrownPermissions.COMMAND_COIN, 0))
                        .executes(ctx -> coinBalance(context, ctx)))
                .then(coinAdmin(context, "give"))
                .then(coinAdmin(context, "take"))
                .then(coinAdmin(context, "set"))
                .then(Commands.literal("look")
                        .requires(source -> can(
                                context, source,
                                CrownPermissions.ADMIN_COIN, 3))
                        .then(Commands.argument(
                                        "player", GameProfileArgument.gameProfile())
                                .executes(ctx -> coinLook(context, ctx))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> coinAdmin(
            CrownServerContext context,
            String action
    ) {
        return Commands.literal(action)
                .requires(source -> can(
                        context, source, CrownPermissions.ADMIN_COIN, 3))
                .then(Commands.argument(
                                "player", GameProfileArgument.gameProfile())
                        .then(Commands.argument(
                                        "amount", LongArgumentType.longArg(0))
                                .executes(ctx -> coinAdjust(
                                        context, ctx, action))));
    }

    private static int coinBalance(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx
    ) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(context.messages()
                    .render("command.player-only"));
            return 0;
        }
        UUID id = player.getUUID();
        String name = player.getGameProfile().name();
        CoreSettings.TitleCoin coinSettings = context.core().titleCoin();
        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() ->
                        context.runtime().storageBackend().repository()
                                .ensurePlayer(id, name,
                                        defaultSelection(context),
                                        Instant.now())),
                record -> ctx.getSource().sendSuccess(
                        () -> context.messages().render(
                                "coin.balance",
                                formatCoins(coinSettings,
                                        record.titleCoinBalance())),
                        false),
                failure -> ctx.getSource().sendFailure(
                        context.messages().render(
                                "purchase.failed.storage")));
        return 1;
    }

    private static int coinLook(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx
    ) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        var profile = profiles.iterator().next();
        UUID id = profile.id();
        String name = profile.name();
        CoreSettings.TitleCoin coinSettings = context.core().titleCoin();
        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() ->
                        context.runtime().storageBackend().repository()
                                .findPlayer(id)),
                found -> {
                    long balance = found
                            .map(PlayerRecord::titleCoinBalance)
                            .orElse(0L);
                    ctx.getSource().sendSuccess(
                            () -> context.messages().render(
                                    "coin.changed", name,
                                    formatCoins(coinSettings, balance)),
                            false);
                },
                failure -> ctx.getSource().sendFailure(
                        context.messages().render(
                                "purchase.failed.storage")));
        return 1;
    }

    private static int coinAdjust(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx,
            String action
    ) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        var profile = profiles.iterator().next();
        long amount = LongArgumentType.getLong(ctx, "amount");
        UUID id = profile.id();
        String name = profile.name();
        CoreSettings.TitleCoin coinSettings = context.core().titleCoin();
        String actor = actorName(ctx.getSource());

        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() ->
                        applyCoinChange(
                                context, id, name, action,
                                amount, actor)),
                result -> ctx.getSource().sendSuccess(
                        () -> context.messages().render(
                                "coin.changed", name,
                                formatCoins(coinSettings,
                                        result.balanceAfter())),
                        true),
                failure -> ctx.getSource().sendFailure(
                        context.messages().render(
                                "purchase.failed.storage")));
        return 1;
    }

    private static CoinAdjustmentResult applyCoinChange(
            CrownServerContext context,
            UUID id,
            String name,
            String action,
            long amount,
            String actor
    ) {
        var repository = context.runtime().storageBackend().repository();
        CoreSettings.TitleCoin coinSettings = context.core().titleCoin();
        Instant now = Instant.now();
        PlayerRecord player = repository.ensurePlayer(
                id, name, defaultSelection(context), now);

        long delta = switch (action) {
            case "give" -> amount;
            case "take" -> -Math.min(amount, player.titleCoinBalance());
            case "set" -> amount - player.titleCoinBalance();
            default -> throw new IllegalArgumentException(
                    "Unknown coin action: " + action);
        };
        return repository.adjustTitleCoins(
                id, delta, coinSettings.maximumBalance(),
                actor, "admin:" + action, null, now);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> equip(
            CrownServerContext context
    ) {
        return Commands.literal("equip")
                .requires(source -> can(
                        context, source, CrownPermissions.COMMAND_OPEN, 0))
                .then(Commands.literal("default")
                        .executes(ctx -> {
                            ServerPlayer player = requirePlayer(context, ctx);
                            if (player == null) {
                                return 0;
                            }
                            runEquip(context, ctx, player,
                                    () -> context.runtime().wardrobe()
                                            .equipDefault(player.getUUID()));
                            return 1;
                        }))
                .then(Commands.argument("entry", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer player = requirePlayer(context, ctx);
                            if (player == null) {
                                return 0;
                            }
                            UUID entryId;
                            try {
                                entryId = UUID.fromString(
                                        StringArgumentType.getString(
                                                ctx, "entry"));
                            } catch (IllegalArgumentException exception) {
                                ctx.getSource().sendFailure(context.messages()
                                        .render("warehouse.expired"));
                                return 0;
                            }
                            runEquip(context, ctx, player,
                                    () -> context.runtime().wardrobe()
                                            .equip(player.getUUID(), entryId));
                            return 1;
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> unequip(
            CrownServerContext context
    ) {
        return Commands.literal("unequip")
                .requires(source -> can(
                        context, source, CrownPermissions.COMMAND_OPEN, 0))
                .executes(ctx -> {
                    ServerPlayer player = requirePlayer(context, ctx);
                    if (player == null) {
                        return 0;
                    }
                    runEquip(context, ctx, player,
                            () -> context.runtime().wardrobe()
                                    .unequip(player.getUUID()));
                    return 1;
                });
    }

    /** 在存储线程执行佩戴操作，成功后刷新缓存并切回主线程反馈。 */
    private static void runEquip(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx,
            ServerPlayer player,
            java.util.function.Supplier<EquipResult> operation
    ) {
        UUID id = player.getUUID();
        String name = player.getGameProfile().name();
        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() -> {
                    EquipResult result = operation.get();
                    if (result == EquipResult.EQUIPPED
                            || result == EquipResult.EQUIPPED_DEFAULT
                            || result == EquipResult.UNEQUIPPED) {
                        context.runtime().playerTitleCache().load(id, name);
                    }
                    return result;
                }),
                result -> {
                    if (result == EquipResult.EQUIPPED
                            || result == EquipResult.EQUIPPED_DEFAULT
                            || result == EquipResult.UNEQUIPPED) {
                        CrownNametagDisplay.refreshPlayer(context, player);
                    }
                    ctx.getSource().sendSuccess(
                            () -> equipMessage(context, result), false);
                },
                failure -> ctx.getSource().sendFailure(
                        context.messages().render(
                                "purchase.failed.storage")));
    }

    private static net.minecraft.network.chat.Component equipMessage(
            CrownServerContext context,
            EquipResult result
    ) {
        return switch (result) {
            case EQUIPPED, ALREADY_EQUIPPED ->
                    context.messages().render("warehouse.equipped", "");
            case EQUIPPED_DEFAULT ->
                    context.messages().render("warehouse.default");
            case UNEQUIPPED ->
                    context.messages().render("warehouse.none");
            case NOT_OWNED ->
                    context.messages().render(
                            "shop.unavailable", context.runtime().snapshot()
                                    .languages().text(
                                            "gui.reason.not-owned"));
            case EXPIRED ->
                    context.messages().render("warehouse.expired");
            case STORAGE_FAILED ->
                    context.messages().render("purchase.failed.storage");
        };
    }

    private static ServerPlayer requirePlayer(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx
    ) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(context.messages()
                    .render("command.player-only"));
        }
        return player;
    }

    private static int openMainGui(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx
    ) {
        if (!can(context, ctx.getSource(), CrownPermissions.COMMAND_OPEN, 0)) {
            ctx.getSource().sendFailure(context.messages()
                    .render("command.no-permission"));
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(context.messages()
                    .render("command.player-only"));
            return 0;
        }
        // 命令在主线程执行，GUI 打开也在主线程完成。
        CrownMainGui.open(context, player);
        return 1;
    }

    private static dev.xiaomu.crown.domain.player.TitleSelection
    defaultSelection(CrownServerContext context) {
        return context.core().defaultTitle().equipForNewPlayer()
                ? dev.xiaomu.crown.domain.player.TitleSelection.defaultTitle()
                : dev.xiaomu.crown.domain.player.TitleSelection.none();
    }

    private static boolean can(
            CrownServerContext context,
            CommandSourceStack source,
            String node,
            int fallbackOpLevel
    ) {
        FabricPermissionService permissions = context.permissions();
        return permissions.checkSource(
                PermissionSource.of(source), node, fallbackOpLevel);
    }

    private static String actorName(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null
                ? "player:" + player.getUUID()
                : "console";
    }

    private static String formatCoins(
            CoreSettings.TitleCoin settings,
            long amount
    ) {
        return settings.format()
                .replace("{amount}", Long.toString(amount))
                .replace("{name}", settings.name());
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        String trimmed = message.length() > 128
                ? message.substring(0, 128)
                : message;
        StringBuilder result = new StringBuilder(trimmed.length());
        trimmed.codePoints().forEach(codePoint -> {
            if (!Character.isISOControl(codePoint)) {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }
}