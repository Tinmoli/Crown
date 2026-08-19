package dev.xiaomu.crown.fabric.custom;

import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.command.CrownTitleAdminCommands;
import dev.xiaomu.crown.fabric.gui.CrownAdminShopGui;
import dev.xiaomu.crown.fabric.permission.CrownPermissions;
import dev.xiaomu.crown.runtime.platform.PermissionSource;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 管理员商品期限、销售窗口、库存和限购的受限聊天编辑会话。 */
public final class AdminTitleSaleEditSessions {
    private static final ConcurrentHashMap<UUID, Session> SESSIONS =
            new ConcurrentHashMap<>();

    private AdminTitleSaleEditSessions() {
    }

    public static boolean hasSession(UUID playerId) {
        return playerId != null && SESSIONS.containsKey(playerId);
    }

    public static boolean begin(
            CrownServerContext context, ServerPlayer player, String definitionId) {
        if (!context.permissions().checkSource(
                PermissionSource.of(player.createCommandSourceStack()),
                CrownPermissions.ADMIN_TITLE, 3)) {
            player.sendSystemMessage(context.messages().render("command.no-permission"));
            return false;
        }
        UUID playerId = player.getUUID();
        if (AdminTitleDraftSessions.hasSession(playerId)
                || AdminTitleTextEditSessions.hasSession(playerId)
                || AdminTitlePaymentEditSessions.hasSession(playerId)
                || SESSIONS.containsKey(playerId)) {
            return false;
        }
        SESSIONS.put(playerId, new Session(definitionId, Instant.now().plus(
                context.core().customTitle().inputTimeout())));
        player.sendSystemMessage(context.messages().render(
                "admin.title.edit.sale.prompt", definitionId));
        return true;
    }

    public static boolean handleChat(
            CrownServerContext context, ServerPlayer player, String message) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) return true;
        context.mainThread().run(() -> process(context, player, message, session));
        return false;
    }

    public static void expire(CrownServerContext context) {
        Instant now = Instant.now();
        for (var entry : SESSIONS.entrySet()) {
            if (entry.getValue().deadline().isAfter(now)
                    || !SESSIONS.remove(entry.getKey(), entry.getValue())) continue;
            ServerPlayer player = context.server().getPlayerList().getPlayer(entry.getKey());
            if (player != null) player.sendSystemMessage(context.messages().render(
                    "admin.title.edit.sale.timeout"));
        }
    }

    public static void disconnect(UUID playerId) {
        if (playerId != null) SESSIONS.remove(playerId);
    }

    private static void process(
            CrownServerContext context, ServerPlayer player, String message, Session expected) {
        if (!SESSIONS.remove(player.getUUID(), expected)) return;
        if (!expected.deadline().isAfter(Instant.now())) {
            player.sendSystemMessage(context.messages().render("admin.title.edit.sale.timeout"));
            return;
        }
        String input = message.strip();
        if (context.core().customTitle().cancelKeywords().contains(input.toLowerCase(Locale.ROOT))) {
            player.sendSystemMessage(context.messages().render("admin.title.edit.sale.cancelled"));
            return;
        }
        Map<String, Object> fields = parse(input);
        if (fields == null) {
            player.sendSystemMessage(context.messages().render("admin.title.edit.sale.invalid"));
            return;
        }
        CrownTitleAdminCommands.updateFromGui(context, player, expected.definitionId(),
                "edit-sale", fields, () -> CrownAdminShopGui.openDetail(
                        context, player, expected.definitionId()));
    }

    private static Map<String, Object> parse(String input) {
        int separator = input.indexOf('=');
        if (separator <= 0) return null;
        String key = input.substring(0, separator).strip().toLowerCase(Locale.ROOT);
        String value = input.substring(separator + 1).strip();
        var fields = new LinkedHashMap<String, Object>();
        try {
            switch (key) {
                case "duration" -> {
                    if (value.equalsIgnoreCase("permanent")) {
                        fields.put("duration.type", "PERMANENT");
                        fields.put("duration.days", 0);
                    } else if (value.regionMatches(true, 0, "limited:", 0, 8)) {
                        int days = Integer.parseInt(value.substring(8));
                        if (days < 1 || days > 36500) return null;
                        fields.put("duration.type", "LIMITED");
                        fields.put("duration.days", days);
                    } else return null;
                }
                case "sale-start", "sale-end" -> {
                    Object instant = value.equalsIgnoreCase("none")
                            ? null : Instant.parse(value).toString();
                    fields.put(key.equals("sale-start") ? "sale.starts-at" : "sale.ends-at", instant);
                }
                case "stock", "limit" -> {
                    long number = value.equalsIgnoreCase("unlimited")
                            ? -1 : Long.parseLong(value);
                    if (number < -1 || (key.equals("limit") && number == 0)
                            || (key.equals("limit") && number > Integer.MAX_VALUE)) return null;
                    fields.put(key.equals("stock") ? "sale.global-stock" : "sale.per-player-limit",
                            key.equals("limit") ? (int) number : number);
                }
                default -> { return null; }
            }
            return fields;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record Session(String definitionId, Instant deadline) {
    }
}