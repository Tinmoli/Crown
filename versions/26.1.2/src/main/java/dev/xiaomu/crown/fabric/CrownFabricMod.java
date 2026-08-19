package dev.xiaomu.crown.fabric;

import dev.xiaomu.crown.config.runtime.ConfigurationLoadReport;
import dev.xiaomu.crown.config.model.DisplayMode;
import dev.xiaomu.crown.fabric.card.CrownCardRedemption;
import dev.xiaomu.crown.fabric.command.CrownCommandTree;
import dev.xiaomu.crown.fabric.custom.AdminTitleDraftSessions;
import dev.xiaomu.crown.fabric.custom.AdminTitlePaymentEditSessions;
import dev.xiaomu.crown.fabric.custom.AdminTitleSaleEditSessions;
import dev.xiaomu.crown.fabric.custom.AdminTitleTextEditSessions;
import dev.xiaomu.crown.fabric.custom.CustomTitleInputSessions;
import dev.xiaomu.crown.fabric.custom.PlayerCustomTitleSessions;
import dev.xiaomu.crown.fabric.display.CrownChatDisplay;
import dev.xiaomu.crown.fabric.display.CrownNametagDisplay;
import dev.xiaomu.crown.fabric.display.CrownTabDisplay;
import dev.xiaomu.crown.fabric.gui.CrownGuiSessions;
import dev.xiaomu.crown.fabric.placeholder.CrownPlaceholders;
import dev.xiaomu.crown.runtime.economy.DirectMintPaymentGateway;
import dev.xiaomu.crown.runtime.lifecycle.CrownRuntime;
import dev.xiaomu.mint.api.Mint;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Crown 的 Fabric 服务端入口。
 *
 * <p>实际运行时由公共 runtime 模块提供；版本目录只负责 Minecraft/Fabric
 * API 适配。Mint 同时由模组元数据声明为强制依赖，此处额外校验其 API
 * 主版本，避免以不兼容接口继续启动。</p>
 *
 * <p>生命周期严格绑定到服务端事件：SERVER_STARTING 装配 {@link CrownRuntime}，
 * SERVER_STOPPED 优雅关闭。装配失败直接抛出，阻止服务端在半初始化状态运行。</p>
 */
public final class CrownFabricMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "crown";
    private static final int REQUIRED_MINT_API_MAJOR = 1;
    private static final Logger LOGGER =
            LoggerFactory.getLogger(CrownFabricMod.class);

    private volatile CrownRuntime runtime;
    private volatile CrownServerContext context;
    private CrownServerContext commandContext;
    private int displayRefreshTicks;

    @Override
    public void onInitializeServer() {
        if (Mint.API_MAJOR != REQUIRED_MINT_API_MAJOR) {
            throw new IllegalStateException(
                    "Crown requires Mint API major "
                            + REQUIRED_MINT_API_MAJOR
                            + ", but found " + Mint.API_MAJOR);
        }

        commandContext = CrownServerContext.deferred(() -> context);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess,
                                                     environment) ->
                CrownCommandTree.register(dispatcher, commandContext, false));
        ServerLifecycleEvents.SERVER_STARTING.register(this::startRuntime);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::stopRuntime);
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> {
                    preloadPlayer(handler.getPlayer());
                    // Commands are registered during SERVER_STARTING. Sync the
                    // completed dispatcher when a player actually joins.
                    server.getCommands().sendCommands(handler.getPlayer());
                });
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> {
                    CustomTitleInputSessions.disconnect(
                            handler.getPlayer().getUUID());
                    AdminTitleDraftSessions.disconnect(
                            handler.getPlayer().getUUID());
                    AdminTitleTextEditSessions.disconnect(
                            handler.getPlayer().getUUID());
                    AdminTitlePaymentEditSessions.disconnect(
                            handler.getPlayer().getUUID());
                    AdminTitleSaleEditSessions.disconnect(
                            handler.getPlayer().getUUID());
                    PlayerCustomTitleSessions.disconnect(
                            handler.getPlayer().getUUID());
                    CrownGuiSessions.clear(handler.getPlayer());
                    CrownServerContext current = context;
                    if (current != null) {
                        CrownNametagDisplay.removePlayer(
                                current, handler.getPlayer());
                    }
                    invalidatePlayer(handler.getPlayer());
                });
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(
                (message, player, parameters) -> {
                    CrownServerContext current = context;
                    if (current == null) return true;
                    String content = message.signedContent();
                    return CustomTitleInputSessions.handleChat(
                            current, player, content)
                            && AdminTitleDraftSessions.handleChat(
                                    current, player, content)
                            && AdminTitleTextEditSessions.handleChat(
                                    current, player, content)
                            && AdminTitlePaymentEditSessions.handleChat(
                                    current, player, content)
                            && AdminTitleSaleEditSessions.handleChat(
                                    current, player, content)
                            && PlayerCustomTitleSessions.handleChat(
                                    current, player, content);
                });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            CrownServerContext current = context;
            if (current != null) {
                CustomTitleInputSessions.expire(current);
                AdminTitleDraftSessions.expire(current);
                AdminTitleTextEditSessions.expire(current);
                AdminTitlePaymentEditSessions.expire(current);
                AdminTitleSaleEditSessions.expire(current);
                PlayerCustomTitleSessions.expire(current);
                if (++displayRefreshTicks >= 20) {
                    displayRefreshTicks = 0;
                    CrownNametagDisplay.refresh(current);
                }
            }
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            CrownServerContext current = context;
            if (current == null
                    || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            return CrownCardRedemption.redeemHeld(
                    current, serverPlayer, hand)
                    ? InteractionResult.SUCCESS_SERVER
                    : InteractionResult.PASS;
        });

        LOGGER.info("Crown Fabric adapter initialized; Mint API major={}",
                Mint.API_MAJOR);
    }

    private void startRuntime(MinecraftServer server) {
        Path gameDirectory =
                FabricLoader.getInstance().getGameDir();
        Path configRoot = FabricLoader.getInstance()
                .getConfigDir().resolve(MOD_ID);
        CrownRuntime started = new CrownRuntime(
                configRoot,
                gameDirectory,
                new DirectMintPaymentGateway());
        try {
            ConfigurationLoadReport report = started.start();
            runtime = started;
            context = new CrownServerContext(server, started);
            CrownChatDisplay.install(context);
            CrownTabDisplay.install(context);
            displayRefreshTicks = 0;
            if (context.core().display().chatMode() == DisplayMode.VANILLA) {
                LOGGER.info("Crown signed chat name decoration is enabled");
            }
            if (context.core().display().tabMode() == DisplayMode.VANILLA) {
                LOGGER.info("Crown TAB display-name decoration is enabled");
            }
            if (context.core().display().nametagMode()
                    == DisplayMode.VANILLA) {
                LOGGER.warn("Crown nametag display is enabled. Crown will "
                        + "skip players assigned to non-Crown scoreboard "
                        + "teams to avoid conflicts with other mods.");
            }
            var crownCommand = server.getCommands().getDispatcher()
                    .getRoot().getChild("crown");
            String[] requiredCommands = {
                    "reload", "title", "view", "storage", "audit"
            };
            String missing = crownCommand == null
                    ? "crown"
                    : java.util.Arrays.stream(requiredCommands)
                            .filter(name -> crownCommand.getChild(name) == null)
                            .findFirst().orElse(null);
            if (missing != null) {
                throw new IllegalStateException(
                        "Crown command registration missing /crown " + missing);
            }
            if (context.core().commands().titleAliasEnabled()
                    && server.getCommands().getDispatcher().getRoot()
                    .getChild("title") == null) {
                CrownCommandTree.registerAlias(
                        server.getCommands().getDispatcher(), context);
            }
            for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                server.getCommands().sendCommands(online);
            }
            LOGGER.info("Crown commands registered: /crown reload and admin branches available");
            if (FabricLoader.getInstance().isModLoaded("placeholder-api")) {
                CrownPlaceholders.register(context);
                LOGGER.info("Crown placeholder variables registered");
            }
            LOGGER.info(
                    "Crown runtime started; storage={}, titles synchronized",
                    report.snapshot().storage().type());
        } catch (IOException | RuntimeException exception) {
            started.close();
            throw new IllegalStateException(
                    "Crown failed to start", exception);
        }
    }

    private void preloadPlayer(ServerPlayer player) {
        CrownServerContext current = context;
        if (current == null || player == null) {
            return;
        }
        java.util.UUID id = player.getUUID();
        String name = player.getGameProfile().name();
        // 登录时在存储线程预热称号与称号币缓存，供 Placeholder 只读使用。
        current.mainThread().whenComplete(
                current.runtime().storageExecutor().submit(() ->
                        current.runtime().playerTitleCache().load(id, name)),
                ignored -> CrownNametagDisplay.refreshPlayer(current, player),
                failure -> LOGGER.warn("Failed to preload Crown cache for {}",
                        name, failure));
    }

    private void invalidatePlayer(ServerPlayer player) {
        CrownServerContext current = context;
        if (current == null || player == null) {
            return;
        }
        current.runtime().playerTitleCache().invalidate(player.getUUID());
    }

    private void stopRuntime(MinecraftServer server) {
        CrownServerContext active = context;
        if (active != null) {
            CrownGuiSessions.clearAll();
            CrownChatDisplay.clear(active);
            CrownTabDisplay.clear(active);
            CrownNametagDisplay.cleanup(active);
        }
        context = null;
        displayRefreshTicks = 0;
        CrownRuntime current = runtime;
        runtime = null;
        if (current != null) {
            current.close();
            LOGGER.info("Crown runtime stopped");
        }
    }

    public CrownRuntime runtime() {
        CrownRuntime current = runtime;
        if (current == null) {
            throw new IllegalStateException("Crown runtime is not started");
        }
        return current;
    }
}
