package dev.xiaomu.crown.fabric.custom;

import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.command.CrownTitleAdminCommands;
import dev.xiaomu.crown.fabric.gui.CrownAdminShopGui;
import dev.xiaomu.crown.fabric.permission.CrownPermissions;
import dev.xiaomu.crown.runtime.platform.PermissionSource;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 管理员商品支付方式和价格的受限聊天编辑会话。 */
public final class AdminTitlePaymentEditSessions {
    private static final ConcurrentHashMap<UUID, Session> SESSIONS =
            new ConcurrentHashMap<>();

    private AdminTitlePaymentEditSessions() {
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
                || AdminTitleTextEditSessions.hasSession(playerId)
                || AdminTitleSaleEditSessions.hasSession(playerId)) {
            return false;
        }
        Session session = new Session(definitionId, Instant.now().plus(
                context.core().customTitle().inputTimeout()));
        if (SESSIONS.putIfAbsent(playerId, session) != null) {
            return false;
        }
        player.sendSystemMessage(context.messages().render(
                "admin.title.edit.payment.prompt", definitionId));
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
                        "admin.title.edit.payment.timeout"));
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
        if (!SESSIONS.remove(player.getUUID(), expected)) {
            return;
        }
        if (!expected.deadline().isAfter(Instant.now())) {
            player.sendSystemMessage(context.messages().render(
                    "admin.title.edit.payment.timeout"));
            return;
        }
        String input = message.strip();
        if (context.core().customTitle().cancelKeywords().contains(
                input.toLowerCase(Locale.ROOT))) {
            player.sendSystemMessage(context.messages().render(
                    "admin.title.edit.payment.cancelled"));
            return;
        }
        Map<String, Object> fields = parse(input);
        if (fields == null) {
            player.sendSystemMessage(context.messages().render(
                    "admin.title.edit.payment.invalid"));
            return;
        }
        CrownTitleAdminCommands.updateFromGui(
                context, player, expected.definitionId(), "edit-payment", fields,
                () -> CrownAdminShopGui.openDetail(
                        context, player, expected.definitionId()));
    }

    private static Map<String, Object> parse(String input) {
        if ("free".equalsIgnoreCase(input)) {
            return Map.of("payment-options", Map.of("free", true));
        }
        if (input.regionMatches(true, 0, "title_coin=", 0, 11)) {
            String amount = input.substring(11).strip();
            try {
                long value = Long.parseLong(amount);
                if (value <= 0) return null;
                return Map.of("payment-options",
                        Map.of("title-coin", Map.of("price", Long.toString(value))));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (!input.regionMatches(true, 0, "mint=", 0, 5)) {
            return null;
        }
        String amount = input.substring(5).strip();
        if (amount.isBlank()) {
            return null;
        }
        try {
            BigDecimal price = new BigDecimal(amount);
            if (price.signum() <= 0) return null;
            return Map.of("payment-options", Map.of("mint", Map.of(
                    "price", price.stripTrailingZeros().toPlainString())));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record Session(String definitionId, Instant deadline) {
    }
}