package dev.xiaomu.crown.fabric.custom;

import com.google.gson.JsonObject;
import dev.xiaomu.crown.config.edit.TitleCatalogEditor;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.gui.CrownAdminShopGui;
import dev.xiaomu.crown.fabric.permission.CrownPermissions;
import dev.xiaomu.crown.runtime.platform.PermissionSource;
import dev.xiaomu.crown.storage.model.AuditRecord;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/** 管理员商品草稿 ID 的聊天输入会话。 */
public final class AdminTitleDraftSessions {
    private static final TitleCatalogEditor EDITOR = new TitleCatalogEditor();
    private static final ConcurrentHashMap<UUID, Instant> SESSIONS =
            new ConcurrentHashMap<>();

    private AdminTitleDraftSessions() {
    }

    /** 返回该玩家是否正在输入管理员商品草稿 ID。 */
    public static boolean hasSession(UUID playerId) {
        return playerId != null && SESSIONS.containsKey(playerId);
    }

    public static boolean begin(
            CrownServerContext context,
            ServerPlayer player
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(player, "player");
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
                || AdminTitleTextEditSessions.hasSession(playerId)
                || AdminTitlePaymentEditSessions.hasSession(playerId)
                || AdminTitleSaleEditSessions.hasSession(playerId)) {
            return false;
        }
        Instant deadline = Instant.now().plus(
                context.core().customTitle().inputTimeout());
        Instant existing = SESSIONS.putIfAbsent(playerId, deadline);
        if (existing != null) {
            if (!existing.isAfter(Instant.now())
                    && SESSIONS.replace(playerId, existing, deadline)) {
                player.sendSystemMessage(context.messages().render(
                        "admin.title.create.prompt"));
                return true;
            }
            return false;
        }
        player.sendSystemMessage(context.messages().render(
                "admin.title.create.prompt"));
        return true;
    }

    /** @return true 表示消息应继续广播。 */
    public static boolean handleChat(
            CrownServerContext context,
            ServerPlayer player,
            String message
    ) {
        Instant deadline = SESSIONS.get(player.getUUID());
        if (deadline == null) {
            return true;
        }
        context.mainThread().run(() -> process(context, player, message,
                deadline));
        return false;
    }

    public static void expire(CrownServerContext context) {
        Instant now = Instant.now();
        for (var entry : SESSIONS.entrySet()) {
            if (entry.getValue().isAfter(now)
                    || !SESSIONS.remove(entry.getKey(), entry.getValue())) {
                continue;
            }
            ServerPlayer player = context.server().getPlayerList()
                    .getPlayer(entry.getKey());
            if (player != null) {
                player.sendSystemMessage(context.messages().render(
                        "admin.title.create.timeout"));
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
            Instant deadline
    ) {
        UUID playerId = player.getUUID();
        if (!SESSIONS.remove(playerId, deadline)) {
            return;
        }
        String id = message.strip().toLowerCase(Locale.ROOT);
        if (context.core().customTitle().cancelKeywords().contains(id)) {
            player.sendSystemMessage(context.messages().render(
                    "admin.title.create.cancelled"));
            return;
        }
        if (!id.matches("[a-z0-9_-]{1,64}")) {
            player.sendSystemMessage(context.messages().render(
                    "admin.title.create.invalid"));
            return;
        }

        Path file = context.runtime().configRoot().resolve("titles.yml");
        JsonObject details = new JsonObject();
        details.addProperty("operation", "create");
        details.addProperty("definitionId", id);
        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() -> {
                    try {
                        EDITOR.create(file, context.core().safety(), id,
                                context.runtime()::reload);
                    } catch (Exception exception) {
                        throw new CompletionException(exception);
                    }
                    context.runtime().storageBackend().repository()
                            .appendAudit(new AuditRecord(
                                    0, "player:" + playerId,
                                    "admin_title_create", null, id,
                                    details.toString(), Instant.now()));
                    return null;
                }),
                ignored -> {
                    player.sendSystemMessage(context.messages().render(
                            "admin.title.changed", id, "create"));
                    CrownAdminShopGui.openDetail(context, player, id);
                },
                failure -> player.sendSystemMessage(context.messages().render(
                        "admin.title.failed", id, safeMessage(failure))));
    }

    private static String safeMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        StringBuilder result = new StringBuilder(
                Math.min(message.length(), 160));
        message.codePoints().filter(codePoint ->
                !Character.isISOControl(codePoint)).limit(160)
                .forEach(result::appendCodePoint);
        return result.toString();
    }
}