package dev.xiaomu.crown.fabric.custom;

import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.command.CrownTitleAdminCommands;
import dev.xiaomu.crown.fabric.gui.CrownAdminShopGui;
import dev.xiaomu.crown.fabric.permission.CrownPermissions;
import dev.xiaomu.crown.runtime.platform.PermissionSource;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 管理员商品正文、前后缀和购买权限的单字段聊天编辑会话。 */
public final class AdminTitleTextEditSessions {
    private static final ConcurrentHashMap<UUID, Session> SESSIONS =
            new ConcurrentHashMap<>();

    private AdminTitleTextEditSessions() {
    }

    public static boolean hasSession(UUID playerId) {
        return playerId != null && SESSIONS.containsKey(playerId);
    }

    public static boolean begin(
            CrownServerContext context,
            ServerPlayer player,
            String definitionId
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(definitionId, "definitionId");
        if (!context.permissions().checkSource(
                PermissionSource.of(player.createCommandSourceStack()),
                CrownPermissions.ADMIN_TITLE, 3)) {
            player.sendSystemMessage(context.messages().render(
                    "command.no-permission"));
            return false;
        }
        UUID playerId = player.getUUID();
        if (PlayerCustomTitleSessions.hasSession(playerId)
                || CustomTitleInputSessions.hasSession(playerId)
                || AdminTitleDraftSessions.hasSession(playerId)
                || AdminTitlePaymentEditSessions.hasSession(playerId)
                || AdminTitleSaleEditSessions.hasSession(playerId)) {
            return false;
        }
        Session session = new Session(definitionId, Instant.now().plus(
                context.core().customTitle().inputTimeout()));
        if (SESSIONS.putIfAbsent(playerId, session) != null) {
            return false;
        }
        player.sendSystemMessage(context.messages().render(
                "admin.title.edit.text.prompt", definitionId));
        return true;
    }

    /** @return true 表示消息应继续广播。 */
    public static boolean handleChat(
            CrownServerContext context,
            ServerPlayer player,
            String message
    ) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return true;
        }
        context.mainThread().run(() -> process(context, player, message, session));
        return false;
    }

    public static void expire(CrownServerContext context) {
        Instant now = Instant.now();
        for (var entry : SESSIONS.entrySet()) {
            Session session = entry.getValue();
            if (session.deadline().isAfter(now)
                    || !SESSIONS.remove(entry.getKey(), session)) {
                continue;
            }
            ServerPlayer player = context.server().getPlayerList()
                    .getPlayer(entry.getKey());
            if (player != null) {
                player.sendSystemMessage(context.messages().render(
                        "admin.title.edit.text.timeout"));
            }
        }
    }

    public static void disconnect(UUID playerId) {
        if (playerId != null) {
            SESSIONS.remove(playerId);
        }
    }

    private static void process(
            CrownServerContext context,
            ServerPlayer player,
            String message,
            Session expected
    ) {
        UUID playerId = player.getUUID();
        if (!SESSIONS.remove(playerId, expected)) {
            return;
        }
        if (!expected.deadline().isAfter(Instant.now())) {
            player.sendSystemMessage(context.messages().render(
                    "admin.title.edit.text.timeout"));
            return;
        }

        String normalized = message.strip();
        if (context.core().customTitle().cancelKeywords().contains(
                normalized.toLowerCase(Locale.ROOT))) {
            player.sendSystemMessage(context.messages().render(
                    "admin.title.edit.text.cancelled"));
            return;
        }

        int separator = normalized.indexOf('=');
        if (separator <= 0) {
            invalid(context, player);
            return;
        }
        String key = normalized.substring(0, separator).strip()
                .toLowerCase(Locale.ROOT);
        String value = normalized.substring(separator + 1).strip();
        String field = switch (key) {
            case "text" -> "content.text";
            case "prefix" -> "content.prefix";
            case "suffix" -> "content.suffix";
            case "permission" -> "requirement.permission";
            default -> null;
        };
        if (field == null || ("permission".equals(key)
                && !value.isEmpty() && !value.matches("[a-z0-9._-]{1,128}"))) {
            invalid(context, player);
            return;
        }
        if ("none".equalsIgnoreCase(value)
                && ("prefix".equals(key) || "suffix".equals(key)
                || "permission".equals(key))) {
            value = "";
        }
        CrownTitleAdminCommands.updateFromGui(
                context, player, expected.definitionId(), "edit-" + key,
                Map.of(field, value),
                () -> CrownAdminShopGui.openDetail(
                        context, player, expected.definitionId()));
    }

    private static void invalid(
            CrownServerContext context,
            ServerPlayer player
    ) {
        player.sendSystemMessage(context.messages().render(
                "admin.title.edit.text.invalid"));
    }

    private record Session(String definitionId, Instant deadline) {
    }
}