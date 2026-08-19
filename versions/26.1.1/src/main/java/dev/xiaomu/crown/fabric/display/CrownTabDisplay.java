package dev.xiaomu.crown.fabric.display;

import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.config.model.DisplayMode;
import dev.xiaomu.crown.domain.text.CrownTextParser;
import dev.xiaomu.crown.fabric.CrownServerContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 原版 PlayerInfo displayName 的服务端侧称号装饰。 */
public final class CrownTabDisplay {
    private static volatile CrownServerContext context;
    private static final Map<UUID, String> LAST_SENT =
            new ConcurrentHashMap<>();

    private CrownTabDisplay() {
    }

    public static void install(CrownServerContext serverContext) {
        context = Objects.requireNonNull(serverContext, "serverContext");
    }

    public static void clear(CrownServerContext serverContext) {
        if (context == serverContext) {
            context = null;
            LAST_SENT.clear();
        }
    }

    /** 由版本 Mixin 装饰原版 getTabListDisplayName 的返回值。 */
    public static Component decorateName(
            ServerPlayer player,
            Component originalName
    ) {
        CrownServerContext active = context;
        if (active == null || active.core().display().tabMode()
                != DisplayMode.VANILLA) {
            return originalName;
        }
        if (originalName == null) {
            originalName = Component.literal(player.getGameProfile().name());
        }
        CoreSettings.DisplayTemplate display = active.core().display().tab();
        CrownTextParser parser = new CrownTextParser(
                active.core().safety().serverTextPolicy());
        Component title = active.textAdapter().component(active.runtime()
                .playerTitleCache().get(player.getUUID()).title().fullText());
        return CrownChatDisplay.renderTemplate(active, parser,
                display.template(), title, originalName, true);
    }

    /** 通知所有在线客户端重新读取该玩家的 TAB displayName。 */
    public static void refreshPlayer(
            CrownServerContext active,
            ServerPlayer player
    ) {
        Objects.requireNonNull(active, "active");
        Objects.requireNonNull(player, "player");
        String state = active.core().display().tabMode() == DisplayMode.VANILLA
                ? decorateName(player, Component.literal(
                        player.getGameProfile().name())).toString()
                : "";
        UUID playerId = player.getUUID();
        if (state.equals(LAST_SENT.put(playerId, state))) {
            return;
        }
        active.server().getPlayerList().broadcastAll(
                new ClientboundPlayerInfoUpdatePacket(
                        ClientboundPlayerInfoUpdatePacket.Action
                                .UPDATE_DISPLAY_NAME,
                        player));
    }

    public static void refresh(CrownServerContext active) {
        LAST_SENT.clear();
        for (ServerPlayer player : active.server().getPlayerList().getPlayers()) {
            refreshPlayer(active, player);
        }
    }

    public static void removePlayer(ServerPlayer player) {
        LAST_SENT.remove(player.getUUID());
    }
}