package dev.xiaomu.crown.fabric.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.display.CrownNametagDisplay;
import dev.xiaomu.crown.fabric.gui.CrownMainGui;
import dev.xiaomu.crown.fabric.gui.CrownGuiSessions;
import dev.xiaomu.crown.fabric.permission.CrownPermissions;
import dev.xiaomu.crown.fabric.permission.FabricPermissionService;
import dev.xiaomu.crown.runtime.platform.PermissionSource;

import dev.xiaomu.crown.storage.model.PlayerRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER =
            LoggerFactory.getLogger(CrownCommandTree.class);

    private CrownCommandTree() {
    }

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CrownServerContext context
    ) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(context, "context");

        LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("crown")
                        .executes(ctx -> openMainGui(context, ctx))
                        .then(help(context))
                        .then(warehouse(context))
                        .then(shop(context))
                        .then(custom(context))
                        .then(reload(context))
                        .then(balance(context))
                        .then(coinAdmin(context, "give"))
                        .then(coinAdmin(context, "take"))
                        .then(coinAdmin(context, "set"))
                        .then(look(context))
                        .then(CrownTitleAdminCommands.title(context))
                        ;

        dispatcher.register(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> help(
            CrownServerContext context
    ) {
        return Commands.literal("help")
                .requires(source -> can(
                        context, source, CrownPermissions.COMMAND_OPEN, 0))
                .executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    sendHelp(context, source, "help.player-header");
                    sendHelp(context, source, "help.main");
                    sendHelp(context, source, "help.warehouse");
                    if (can(context, source, CrownPermissions.COMMAND_SHOP, 0)) {
                        sendHelp(context, source, "help.shop");
                    }
                    if (can(context, source, CrownPermissions.COMMAND_CUSTOM, 0)) {
                        sendHelp(context, source, "help.custom");
                    }
                    if (can(context, source, CrownPermissions.COMMAND_COIN, 0)) {
                        sendHelp(context, source, "help.balance");
                    }
                    boolean coins = can(context, source, CrownPermissions.ADMIN_COIN, 3);
                    boolean titles = can(context, source, CrownPermissions.ADMIN_TITLE, 3);
                    boolean reload = can(context, source, CrownPermissions.ADMIN_RELOAD, 3);
                    if (coins || titles || reload) {
                        sendHelp(context, source, "help.admin-header");
                        if (coins) {
                            for (String action : java.util.List.of("give", "take", "set", "look")) {
                                sendHelp(context, source, "help." + action);
                            }
                        }
                        if (titles) sendHelp(context, source, "help.title");
                        if (reload) sendHelp(context, source, "help.reload");
                    }
                    return 1;
                });
    }

    private static void sendHelp(
            CrownServerContext context,
            CommandSourceStack source,
            String key
    ) {
        String template = context.runtime().snapshot().languages().text(key);
        for (String line : template.split("\\R", -1)) {
            if (line.isBlank()) {
                continue;
            }
            source.sendSuccess(() -> context.messages().render("help.line", line), false);
        }
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
                                .render("shop.unavailable",
                                        context.runtime().snapshot()
                                                .languages().text(
                                                        "shop.custom-title")));
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
                                false);
                        if (source.getPlayer() != null) {
                            LOGGER.info("{}", context.messages().render("command.reload.success").getString());
                        }
                    }, exception -> {
                        LOGGER.warn("Crown reload failed", exception);
                        source.sendFailure(context.messages().render(
                                "command.reload.failed"));
                    });
                    return 1;
                });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> balance(
            CrownServerContext context
    ) {
        return Commands.literal("balance")
                        .requires(source -> can(
                                context, source,
                                CrownPermissions.COMMAND_COIN, 0))
                        .executes(ctx -> coinBalance(context, ctx));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> look(CrownServerContext context) {
        return Commands.literal("look")
                        .requires(source -> can(
                                context, source,
                                CrownPermissions.ADMIN_COIN, 3))
                        .then(Commands.argument(
                                        "player", GameProfileArgument.gameProfile())
                                .executes(ctx -> coinLook(context, ctx)));
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
                                    "coin.other-balance", name,
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
                context.runtime().playerOperations().submit(id, () ->
                        context.runtime().storageExecutor().submit(() -> applyCoinChange(
                                context, id, name, action,
                                amount, actor))),
                result -> ctx.getSource().sendSuccess(
                        () -> context.messages().render(
                                "coin.changed", name,
                                formatCoins(coinSettings,
                                        result)),
                        false),
                failure -> ctx.getSource().sendFailure(
                        context.messages().render(
                                "purchase.failed.storage")));
        return 1;
    }

    private static long applyCoinChange(
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
        long balance = player.titleCoinBalance();
        if (delta != 0) {
            balance = repository.adjustTitleCoins(
                id, delta, coinSettings.maximumBalance(),
                actor, "admin:" + action, null, now).balanceAfter();
        }
        context.runtime().playerTitleCache().load(id, name);
        return balance;
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
                .replace("{name}", settings.name())
                .replace("{symbol}", settings.symbol());
    }
}
