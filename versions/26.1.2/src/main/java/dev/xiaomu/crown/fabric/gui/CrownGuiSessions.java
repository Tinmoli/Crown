package dev.xiaomu.crown.fabric.gui;

import dev.xiaomu.crown.fabric.CrownServerContext;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录可安全重建的 Crown 列表页。
 *
 * <p>确认页和聊天输入会话不登记，配置重载不会打断付款或输入流程。
 * 该类只保存恢复页面所需的最小参数，不持有 GUI 实例。</p>
 */
public final class CrownGuiSessions {
    private static final Map<UUID, Session> SESSIONS =
            new ConcurrentHashMap<>();

    private CrownGuiSessions() {
    }

    public static void main(CrownServerContext context, ServerPlayer player) {
        register(player, new Session(context, Page.MAIN, null, null));
    }

    public static void shop(CrownServerContext context, ServerPlayer player) {
        register(player, new Session(context, Page.SHOP, null, null));
    }

    public static void warehouse(
            CrownServerContext context,
            ServerPlayer player
    ) {
        register(player, new Session(context, Page.WAREHOUSE, null, null));
    }

    public static void adminShop(
            CrownServerContext context,
            ServerPlayer player
    ) {
        register(player, new Session(context, Page.ADMIN_SHOP, null, null));
    }

    public static void adminWarehouse(
            CrownServerContext context,
            ServerPlayer administrator,
            UUID targetId,
            String targetName
    ) {
        register(administrator, new Session(
                context, Page.ADMIN_WAREHOUSE, targetId, targetName));
    }

    public static void clear(ServerPlayer player) {
        if (player != null) {
            SESSIONS.remove(player.getUUID());
        }
    }

    public static void clearAll() {
        SESSIONS.clear();
    }

    /**
     * 重载后重建当前服务器中登记的列表页。
     * 页面重新打开时会重新登记，因此先复制并清空旧记录。
     */
    public static void refresh(CrownServerContext context) {
        Map<UUID, Session> pending = new ConcurrentHashMap<>();
        SESSIONS.forEach((id, session) -> {
            if (session.context() == context) {
                pending.put(id, session);
            }
        });
        pending.forEach(SESSIONS::remove);

        for (Session session : pending.values()) {
            ServerPlayer player = context.server().getPlayerList()
                    .getPlayer(session.playerId());
            if (player == null) {
                continue;
            }
            player.closeContainer();
            switch (session.page()) {
                case MAIN -> CrownMainGui.open(context, player);
                case SHOP -> CrownShopGui.open(context, player);
                case WAREHOUSE -> CrownWarehouseGui.open(context, player);
                case ADMIN_SHOP -> CrownAdminShopGui.open(context, player);
                case ADMIN_WAREHOUSE -> CrownAdminWarehouseGui.open(
                        context, player, session.targetId(), session.targetName());
            }
        }
    }

    private static void register(ServerPlayer player, Session session) {
        SESSIONS.put(player.getUUID(), session.withPlayer(player.getUUID()));
    }

    private enum Page {
        MAIN,
        SHOP,
        WAREHOUSE,
        ADMIN_SHOP,
        ADMIN_WAREHOUSE
    }

    private record Session(
            CrownServerContext context,
            Page page,
            UUID targetId,
            String targetName,
            UUID playerId
    ) {
        private Session(
                CrownServerContext context,
                Page page,
                UUID targetId,
                String targetName
        ) {
            this(context, page, targetId, targetName, null);
        }

        private Session withPlayer(UUID id) {
            return new Session(context, page, targetId, targetName, id);
        }
    }
}