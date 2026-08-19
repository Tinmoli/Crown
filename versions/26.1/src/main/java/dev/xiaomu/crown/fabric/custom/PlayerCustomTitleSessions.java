package dev.xiaomu.crown.fabric.custom;

import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.domain.catalog.TitleContent;
import dev.xiaomu.crown.domain.catalog.PaymentPolicy;
import dev.xiaomu.crown.domain.text.CrownTextParser;
import dev.xiaomu.crown.domain.text.StyledText;
import dev.xiaomu.crown.domain.text.TextParsePolicy;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.display.CrownNametagDisplay;
import dev.xiaomu.crown.fabric.gui.PlayerCustomConfirmGui;
import dev.xiaomu.crown.fabric.permission.CrownPermissions;
import dev.xiaomu.crown.runtime.platform.PermissionSource;
import dev.xiaomu.crown.runtime.purchase.PurchaseIdentifiers;
import dev.xiaomu.crown.runtime.purchase.PurchaseStatus;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 玩家自定义称号购买的聊天输入、确认和支付会话。 */
public final class PlayerCustomTitleSessions {
    private static final ConcurrentHashMap<UUID, Session> SESSIONS =
            new ConcurrentHashMap<>();

    private PlayerCustomTitleSessions() {
    }

    public static boolean begin(
            CrownServerContext context,
            ServerPlayer player
    ) {
        CoreSettings.CustomTitle settings = context.core().customTitle();
        UUID playerId = player.getUUID();
        if (!settings.enabled()
                || CustomTitleInputSessions.hasSession(playerId)
                || AdminTitleDraftSessions.hasSession(playerId)
                || AdminTitleTextEditSessions.hasSession(playerId)
                || AdminTitlePaymentEditSessions.hasSession(playerId)) {
            return false;
        }
        Session created = new Session(
                UUID.randomUUID(), Phase.INPUT,
                Instant.now().plus(settings.inputTimeout()), null);
        Session existing = SESSIONS.putIfAbsent(playerId, created);
        if (existing != null) {
            if (existing.phase() == Phase.PROCESSING
                    || !existing.expiredAt(Instant.now())
                    || !SESSIONS.replace(playerId, existing, created)) {
                return false;
            }
        }
        player.sendSystemMessage(context.messages().render("custom.prompt"));
        return true;
    }

    public static boolean hasSession(UUID playerId) {
        return playerId != null && SESSIONS.containsKey(playerId);
    }

    /** @return true 表示应继续广播，false 表示 Crown 已截获。 */
    public static boolean handleChat(
            CrownServerContext context,
            ServerPlayer player,
            String message
    ) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.phase() != Phase.INPUT) {
            return true;
        }
        context.mainThread().run(() ->
                processInput(context, player, message));
        return false;
    }

    public static void expire(CrownServerContext context) {
        Instant now = Instant.now();
        for (var entry : SESSIONS.entrySet()) {
            Session session = entry.getValue();
            if (session.phase() == Phase.PROCESSING
                    || !session.expiredAt(now)
                    || !SESSIONS.remove(entry.getKey(), session)) {
                continue;
            }
            ServerPlayer player = context.server().getPlayerList()
                    .getPlayer(entry.getKey());
            if (player != null) {
                player.sendSystemMessage(context.messages()
                        .render("custom.timeout"));
            }
        }
    }

    public static void disconnect(UUID playerId) {
        if (playerId != null) {
            SESSIONS.remove(playerId);
        }
    }

    public static void cancel(
            CrownServerContext context,
            ServerPlayer player,
            UUID sessionId,
            boolean notify
    ) {
        Session session = current(player.getUUID(), sessionId);
        if (session != null && session.phase() != Phase.PROCESSING
                && SESSIONS.remove(player.getUUID(), session)
                && notify) {
            player.sendSystemMessage(context.messages()
                    .render("custom.cancelled"));
        }
    }

    public static void reenter(
            CrownServerContext context,
            ServerPlayer player,
            UUID sessionId
    ) {
        UUID playerId = player.getUUID();
        Session session = current(playerId, sessionId);
        if (session == null || session.phase() != Phase.CONFIRM) {
            player.sendSystemMessage(context.messages()
                    .render("custom.timeout"));
            return;
        }
        Session input = new Session(
                session.sessionId(), Phase.INPUT,
                Instant.now().plus(
                        context.core().customTitle().inputTimeout()), null);
        if (SESSIONS.replace(playerId, session, input)) {
            player.sendSystemMessage(context.messages()
                    .render("custom.prompt"));
        }
    }

    public static void confirm(
            CrownServerContext context,
            ServerPlayer player,
            UUID sessionId
    ) {
        confirm(context, player, sessionId,
                context.core().customTitle().payment());
    }

    public static void confirm(
            CrownServerContext context,
            ServerPlayer player,
            UUID sessionId,
            PaymentPolicy selectedPayment
    ) {
        UUID playerId = player.getUUID();
        Session session = current(playerId, sessionId);
        if (session == null || session.phase() != Phase.CONFIRM
                || session.content() == null
                || session.expiredAt(Instant.now())) {
            if (session != null) SESSIONS.remove(playerId, session);
            player.sendSystemMessage(context.messages().render("custom.timeout"));
            return;
        }
        Validation validation = validate(context, player, session.content().textSource());
        if (!validation.valid()) {
            SESSIONS.remove(playerId, session);
            player.sendSystemMessage(context.messages().render("custom.invalid", validation.error()));
            return;
        }
        Session processing = new Session(session.sessionId(), Phase.PROCESSING,
                session.deadline(), validation.content());
        if (!SESSIONS.replace(playerId, session, processing)) {
            player.sendSystemMessage(context.messages().render("purchase.processing"));
            return;
        }
        CoreSettings.CustomTitle custom = context.core().customTitle();
        PurchaseIdentifiers ids = PurchaseIdentifiers.create(selectedPayment.type());
        String playerName = player.getGameProfile().name();
        context.mainThread().whenComplete(
                context.runtime().purchaseService().purchaseCustom(
                        playerId, custom, selectedPayment, validation.content(),
                        context.core().purchase(), ids),
                result -> {
                    SESSIONS.remove(playerId, processing);
                    if (result.status() == PurchaseStatus.GRANTED) {
                        context.mainThread().whenComplete(
                                context.runtime().storageExecutor().submit(() -> {
                                    context.runtime().playerTitleCache().load(
                                            playerId, playerName);
                                    return null;
                                }),
                                ignored -> CrownNametagDisplay.refreshPlayer(
                                        context, player),
                                failure -> player.sendSystemMessage(
                                        context.messages().render(
                                                "purchase.failed.storage")));
                        player.sendSystemMessage(context.messages().render(
                                "purchase.success", validation.content().textSource()));
                    } else {
                        player.sendSystemMessage(resultMessage(context, result.status()));
                    }
                },
                failure -> {
                    SESSIONS.remove(playerId, processing);
                    player.sendSystemMessage(context.messages().render("purchase.failed.storage"));
                });
    }
    private static void processInput(
            CrownServerContext context, ServerPlayer player, String source
    ) {
        UUID playerId = player.getUUID();
        Session session = SESSIONS.get(playerId);
        if (session == null || session.phase() != Phase.INPUT) return;
        if (session.expiredAt(Instant.now())) {
            if (SESSIONS.remove(playerId, session)) {
                player.sendSystemMessage(context.messages().render("custom.timeout"));
            }
            return;
        }
        String normalized = source.strip().toLowerCase(Locale.ROOT);
        if (context.core().customTitle().cancelKeywords().contains(normalized)) {
            if (SESSIONS.remove(playerId, session)) {
                player.sendSystemMessage(context.messages().render("custom.cancelled"));
            }
            return;
        }
        Validation validation = validate(context, player, source);
        if (!validation.valid()) {
            player.sendSystemMessage(context.messages().render(
                    "custom.invalid", validation.error()));
            return;
        }
        Session confirmation = new Session(
                session.sessionId(), Phase.CONFIRM,
                Instant.now().plus(context.core().customTitle().inputTimeout()),
                validation.content());
        if (SESSIONS.replace(playerId, session, confirmation)) {
            PlayerCustomConfirmGui.open(context, player,
                    confirmation.sessionId(), confirmation.content());
        }
    }

    private static Validation validate(
            CrownServerContext context, ServerPlayer player, String source
    ) {
        CoreSettings.CustomTitle settings = context.core().customTitle();
        if (!settings.enabled()) return Validation.invalid("custom titles are disabled");
        boolean color = context.permissions().checkSource(
                PermissionSource.of(player.createCommandSourceStack()),
                CrownPermissions.CUSTOM_COLOR, 0);
        TextParsePolicy base = settings.inputPolicy(
                context.core().safety().maximumTitleSourceLength());
        TextParsePolicy policy = color ? base : new TextParsePolicy(
                base.allowLegacyFormatting(), false, false,
                base.maximumSourceLength(), base.maximumVisibleLength());
        try {
            StyledText body = new CrownTextParser(policy).parse(source);
            if (body.visibleCodePointCount() < settings.minimumLength()) {
                return Validation.invalid("visible text is too short");
            }
            String plain = body.plainText().toLowerCase(Locale.ROOT);
            for (String forbidden : settings.forbiddenWords()) {
                if (!forbidden.isEmpty() && plain.contains(forbidden)) {
                    return Validation.invalid("contains a forbidden word");
                }
            }
            return Validation.valid(new TitleContent(
                    settings.prefixSource(), source, settings.suffixSource(),
                    settings.prefix(), body, settings.suffix()));
        } catch (IllegalArgumentException exception) {
            String message = exception.getMessage();
            return Validation.invalid(message == null || message.isBlank()
                    ? "invalid text" : safeMessage(message));
        }
    }

    private static net.minecraft.network.chat.Component resultMessage(
            CrownServerContext context, PurchaseStatus status
    ) {
        return switch (status) {
            case INSUFFICIENT_FUNDS -> context.messages().render(
                    "purchase.failed.balance", "?", "?");
            case PAYMENT_FAILED, PAYMENT_UNCERTAIN ->
                    context.messages().render("purchase.failed.provider");
            case TOO_MANY_PENDING ->
                    context.messages().render("purchase.processing");
            case DISABLED, HIDDEN, NOT_ON_SALE, PERMISSION_DENIED,
                    OUT_OF_STOCK, PLAYER_LIMIT_REACHED ->
                    context.messages().render("shop.unavailable",
                            status.name().toLowerCase(Locale.ROOT));
            case ORDER_CONFLICT, INVALID_STATE ->
                    context.messages().render("purchase.failed.storage");
            case GRANTED -> context.messages().render(
                    "purchase.success", "custom");
        };
    }

    private static Session current(UUID playerId, UUID sessionId) {
        Session session = SESSIONS.get(playerId);
        return session != null && session.sessionId().equals(sessionId)
                ? session : null;
    }

    private static String safeMessage(String value) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), 160));
        value.codePoints().filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(160).forEach(result::appendCodePoint);
        return result.toString();
    }

    private enum Phase { INPUT, CONFIRM, PROCESSING }

    private record Session(
            UUID sessionId, Phase phase, Instant deadline, TitleContent content
    ) {
        private Session {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(deadline, "deadline");
        }
        boolean expiredAt(Instant now) { return !now.isBefore(deadline); }
    }

    private record Validation(TitleContent content, String error) {
        static Validation valid(TitleContent content) {
            return new Validation(Objects.requireNonNull(content), null);
        }
        static Validation invalid(String error) {
            return new Validation(null, Objects.requireNonNull(error));
        }
        boolean valid() { return content != null; }
    }
}