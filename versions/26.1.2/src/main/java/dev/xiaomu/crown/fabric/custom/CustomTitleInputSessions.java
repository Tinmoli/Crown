package dev.xiaomu.crown.fabric.custom;

import com.google.gson.JsonObject;
import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.domain.catalog.DurationPolicy;
import dev.xiaomu.crown.domain.catalog.TitleContent;
import dev.xiaomu.crown.domain.player.TitleSelection;
import dev.xiaomu.crown.domain.text.CrownTextParser;
import dev.xiaomu.crown.domain.text.StyledText;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.display.CrownNametagDisplay;
import dev.xiaomu.crown.fabric.gui.AdminCustomConfirmGui;
import dev.xiaomu.crown.storage.model.AuditRecord;
import dev.xiaomu.crown.storage.model.OwnedTitleKind;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import dev.xiaomu.crown.storage.model.OwnedTitleStatus;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理员自定义称号聊天输入会话。
 *
 * <p>聊天回调只负责认领消息并切回主线程处理；数据库工作始终提交到
 * Crown 存储执行器。一个管理员同时只能存在一个输入、确认或发放会话。</p>
 */
public final class CustomTitleInputSessions {
    private static final ConcurrentHashMap<UUID, Session> SESSIONS =
            new ConcurrentHashMap<>();

    private CustomTitleInputSessions() {
    }

    /**
     * 启动管理员自定义称号发放流程。
     *
     * @return false 表示管理员已有未完成会话
     */
    public static boolean beginAdminGrant(
            CrownServerContext context,
            ServerPlayer administrator,
            UUID targetId,
            String targetName,
            DurationPolicy duration
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(administrator, "administrator");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(targetName, "targetName");
        Objects.requireNonNull(duration, "duration");

        UUID administratorId = administrator.getUUID();
        if (PlayerCustomTitleSessions.hasSession(administratorId)
                || AdminTitleDraftSessions.hasSession(administratorId)
                || AdminTitleTextEditSessions.hasSession(administratorId)
                || AdminTitlePaymentEditSessions.hasSession(administratorId)) {
            return false;
        }
        Instant deadline = Instant.now().plus(
                context.core().customTitle().inputTimeout());
        Session created = new Session(
                UUID.randomUUID(),
                administratorId,
                targetId,
                targetName,
                duration,
                Phase.INPUT,
                deadline,
                null,
                false);
        Session existing = SESSIONS.putIfAbsent(
                administratorId, created);
        if (existing != null) {
            if (existing.expiredAt(Instant.now())
                    && SESSIONS.remove(administratorId, existing)) {
                SESSIONS.put(administratorId, created);
            } else {
                return false;
            }
        }

        administrator.sendSystemMessage(context.messages().render(
                "admin.player.custom.prompt",
                targetName,
                durationText(context, duration)));
        return true;
    }

    /**
     * Fabric ALLOW_CHAT_MESSAGE 回调入口。
     *
     * @return true 表示不是 Crown 会话消息，应继续广播；false 表示已截获
     */
    public static boolean handleChat(
            CrownServerContext context,
            ServerPlayer administrator,
            String message
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(administrator, "administrator");
        Objects.requireNonNull(message, "message");

        UUID administratorId = administrator.getUUID();
        Session session = SESSIONS.get(administratorId);
        if (session == null || session.phase() != Phase.INPUT) {
            return true;
        }

        context.mainThread().run(() ->
                processInput(context, administrator, message));
        return false;
    }

    public static boolean hasSession(UUID administratorId) {
        return administratorId != null
                && SESSIONS.containsKey(administratorId);
    }

    /** 每个服务端 tick 清理到期的输入/确认会话。 */
    public static void expire(CrownServerContext context) {
        Instant now = Instant.now();
        for (var entry : SESSIONS.entrySet()) {
            Session session = entry.getValue();
            // 已提交的数据库发放只能由完成回调结束，避免慢查询同时产生
            // “超时”和“发放成功”两条相互矛盾的反馈。
            if (session.phase() == Phase.PROCESSING) {
                continue;
            }
            if (!session.expiredAt(now)
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

    public static void disconnect(UUID administratorId) {
        if (administratorId != null) {
            SESSIONS.remove(administratorId);
        }
    }

    public static void cancel(
            CrownServerContext context,
            ServerPlayer administrator,
            UUID sessionId,
            boolean notify
    ) {
        Session session = current(administrator.getUUID(), sessionId);
        if (session != null
                && SESSIONS.remove(administrator.getUUID(), session)
                && notify) {
            administrator.sendSystemMessage(context.messages()
                    .render("custom.cancelled"));
        }
    }

    public static void reenter(
            CrownServerContext context,
            ServerPlayer administrator,
            UUID sessionId
    ) {
        UUID administratorId = administrator.getUUID();
        Session session = current(administratorId, sessionId);
        if (session == null || session.processing()) {
            administrator.sendSystemMessage(context.messages()
                    .render("admin.player.custom.session-expired"));
            return;
        }
        Session input = session.withInput(
                Instant.now().plus(
                        context.core().customTitle().inputTimeout()));
        if (SESSIONS.replace(administratorId, session, input)) {
            administrator.sendSystemMessage(context.messages().render(
                    "admin.player.custom.prompt",
                    session.targetName(),
                    durationText(context, session.duration())));
        }
    }

    /** 确认 GUI 的幂等发放入口。 */
    public static void confirm(
            CrownServerContext context,
            ServerPlayer administrator,
            UUID sessionId
    ) {
        UUID administratorId = administrator.getUUID();
        Session session = current(administratorId, sessionId);
        if (session == null
                || session.phase() != Phase.CONFIRM
                || session.content() == null
                || session.expiredAt(Instant.now())) {
            if (session != null) {
                SESSIONS.remove(administratorId, session);
            }
            administrator.sendSystemMessage(context.messages()
                    .render("admin.player.custom.session-expired"));
            return;
        }

        /*
         * 配置可能在确认 GUI 打开后热重载，因此确认时重新解析原始正文，
         * 并使用当前配置的统一前后缀，不信任 GUI 中捕获的旧解析结果。
         */
        Validation validation = validate(
                context, session.content().textSource());
        if (!validation.valid()) {
            SESSIONS.remove(administratorId, session);
            administrator.sendSystemMessage(context.messages().render(
                    "custom.invalid", validation.error()));
            return;
        }

        Session processing = session.withContent(
                Phase.PROCESSING,
                session.deadline(),
                validation.content(),
                true);
        if (!SESSIONS.replace(
                administratorId, session, processing)) {
            administrator.sendSystemMessage(context.messages()
                    .render("admin.player.custom.processing"));
            return;
        }

        String actor = "player:" + administratorId;
        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() ->
                        grantStored(context, processing, actor)),
                record -> {
                    SESSIONS.remove(administratorId, processing);
                    administrator.sendSystemMessage(
                            context.messages().render(
                                    "admin.player.custom.granted",
                                    processing.targetName(),
                                    record.entryId().toString()));
                    ServerPlayer target = context.server().getPlayerList()
                            .getPlayer(processing.targetId());
                    if (target != null) {
                        CrownNametagDisplay.refreshPlayer(context, target);
                        target.sendSystemMessage(context.messages().render(
                                "admin.player.custom.received",
                                record.entryId().toString()));
                    }
                },
                failure -> {
                    /*
                     * 未知存储结果时不自动重试生成新的 entryId。当前 session
                     * 结束，管理员可通过 audit/list 核对后再决定是否重发。
                     */
                    SESSIONS.remove(administratorId, processing);
                    administrator.sendSystemMessage(context.messages()
                            .render("purchase.failed.storage"));
                });
    }

    private static OwnedTitleRecord grantStored(
            CrownServerContext context,
            Session session,
            String actor
    ) {
        Instant now = Instant.now();
        var repository = context.runtime()
                .storageBackend().repository();
        repository.ensurePlayer(
                session.targetId(),
                session.targetName(),
                TitleSelection.none(),
                now);

        TitleContent content = session.content();
        OwnedTitleRecord record = new OwnedTitleRecord(
                UUID.randomUUID(),
                session.targetId(),
                null,
                OwnedTitleKind.ADMIN_CUSTOM,
                content.textSource(),
                content.prefixSource(),
                content.suffixSource(),
                "admin:grant-custom",
                now,
                session.duration().expiresAt(now).orElse(null),
                null,
                OwnedTitleStatus.ACTIVE,
                null,
                null);

        JsonObject details = new JsonObject();
        details.addProperty("entryId", record.entryId().toString());
        details.addProperty("kind", record.kind().name());
        details.addProperty(
                "durationType", session.duration().type().name());
        details.addProperty(
                "durationDays", session.duration().days());
        details.addProperty(
                "expiresAt",
                record.expiresAt() == null
                        ? "permanent"
                        : record.expiresAt().toString());
        AuditRecord audit = new AuditRecord(
                0,
                actor,
                "admin_grant_custom_title",
                session.targetId(),
                record.entryId().toString(),
                details.toString(),
                now);

        if (!repository.insertOwnedTitleWithAudit(record, audit)) {
            throw new IllegalStateException(
                    "Generated admin custom title entry already exists");
        }
        context.runtime().playerTitleCache().load(
                session.targetId(), session.targetName());
        return record;
    }

    private static void processInput(
            CrownServerContext context,
            ServerPlayer administrator,
            String source
    ) {
        UUID administratorId = administrator.getUUID();
        Session session = SESSIONS.get(administratorId);
        if (session == null || session.phase() != Phase.INPUT) {
            return;
        }
        if (session.expiredAt(Instant.now())) {
            if (SESSIONS.remove(administratorId, session)) {
                administrator.sendSystemMessage(context.messages()
                        .render("custom.timeout"));
            }
            return;
        }

        String normalized = source.strip()
                .toLowerCase(Locale.ROOT);
        if (context.core().customTitle().cancelKeywords()
                .contains(normalized)) {
            if (SESSIONS.remove(administratorId, session)) {
                administrator.sendSystemMessage(context.messages()
                        .render("custom.cancelled"));
            }
            return;
        }

        Validation validation = validate(context, source);
        if (!validation.valid()) {
            administrator.sendSystemMessage(context.messages().render(
                    "custom.invalid", validation.error()));
            return;
        }

        Session confirmation = session.withContent(
                Phase.CONFIRM,
                Instant.now().plus(
                        context.core().customTitle().inputTimeout()),
                validation.content(),
                false);
        if (!SESSIONS.replace(
                administratorId, session, confirmation)) {
            return;
        }
        AdminCustomConfirmGui.open(
                context,
                administrator,
                confirmation.sessionId(),
                confirmation.targetName(),
                confirmation.duration(),
                confirmation.content());
    }

    private static Validation validate(
            CrownServerContext context,
            String source
    ) {
        CoreSettings.CustomTitle settings =
                context.core().customTitle();
        try {
            StyledText body = new CrownTextParser(
                    settings.inputPolicy(
                            context.core().safety()
                                    .maximumTitleSourceLength()))
                    .parse(source);
            int visibleLength = body.visibleCodePointCount();
            if (visibleLength < settings.minimumLength()) {
                return Validation.invalid("visible text is too short");
            }

            String plain = body.plainText()
                    .toLowerCase(Locale.ROOT);
            for (String forbidden : settings.forbiddenWords()) {
                if (!forbidden.isEmpty()
                        && plain.contains(forbidden)) {
                    return Validation.invalid(
                            "contains a forbidden word");
                }
            }

            TitleContent content = new TitleContent(
                    settings.prefixSource(),
                    source,
                    settings.suffixSource(),
                    settings.prefix(),
                    body,
                    settings.suffix());
            return Validation.valid(content);
        } catch (IllegalArgumentException exception) {
            String message = exception.getMessage();
            return Validation.invalid(
                    message == null || message.isBlank()
                            ? "invalid text"
                            : safeMessage(message));
        }
    }

    private static Session current(
            UUID administratorId,
            UUID sessionId
    ) {
        Session session = SESSIONS.get(administratorId);
        return session != null
                && session.sessionId().equals(sessionId)
                ? session
                : null;
    }

    private static String durationText(
            CrownServerContext context,
            DurationPolicy duration
    ) {
        return duration.days() == 0
                ? context.runtime().snapshot().languages()
                        .text("card.duration.permanent")
                : context.runtime().snapshot().languages()
                        .text("card.duration.days")
                        .replace("%0%",
                                Integer.toString(duration.days()));
    }

    private static String safeMessage(String value) {
        StringBuilder result = new StringBuilder(
                Math.min(value.length(), 160));
        value.codePoints()
                .filter(codePoint ->
                        !Character.isISOControl(codePoint))
                .limit(160)
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    private enum Phase {
        INPUT,
        CONFIRM,
        PROCESSING
    }

    private record Session(
            UUID sessionId,
            UUID administratorId,
            UUID targetId,
            String targetName,
            DurationPolicy duration,
            Phase phase,
            Instant deadline,
            TitleContent content,
            boolean processing
    ) {
        private Session {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(
                    administratorId, "administratorId");
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(targetName, "targetName");
            Objects.requireNonNull(duration, "duration");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(deadline, "deadline");
        }

        boolean expiredAt(Instant now) {
            return !now.isBefore(deadline);
        }

        Session withInput(Instant newDeadline) {
            return new Session(
                    sessionId, administratorId,
                    targetId, targetName, duration,
                    Phase.INPUT, newDeadline, null, false);
        }

        Session withContent(
                Phase newPhase,
                Instant newDeadline,
                TitleContent newContent,
                boolean newProcessing
        ) {
            return new Session(
                    sessionId, administratorId,
                    targetId, targetName, duration,
                    newPhase, newDeadline,
                    newContent, newProcessing);
        }
    }

    private record Validation(
            TitleContent content,
            String error
    ) {
        static Validation valid(TitleContent content) {
            return new Validation(
                    Objects.requireNonNull(content, "content"),
                    null);
        }

        static Validation invalid(String error) {
            return new Validation(
                    null,
                    Objects.requireNonNull(error, "error"));
        }

        boolean valid() {
            return content != null;
        }
    }
}