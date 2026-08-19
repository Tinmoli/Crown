package dev.xiaomu.crown.fabric.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.xiaomu.crown.config.model.StorageSettings;
import dev.xiaomu.crown.domain.catalog.DurationPolicy;
import dev.xiaomu.crown.domain.catalog.TitleContent;
import dev.xiaomu.crown.domain.catalog.TitleDefinition;
import dev.xiaomu.crown.domain.player.SelectionType;
import dev.xiaomu.crown.domain.player.TitleSelection;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.card.CrownCardItems;
import dev.xiaomu.crown.fabric.custom.CustomTitleInputSessions;
import dev.xiaomu.crown.fabric.gui.CrownAdminWarehouseGui;
import dev.xiaomu.crown.fabric.permission.CrownPermissions;
import dev.xiaomu.crown.storage.model.AuditRecord;
import dev.xiaomu.crown.storage.model.CardRecord;
import dev.xiaomu.crown.storage.model.OwnedTitleDurationStatus;
import dev.xiaomu.crown.storage.model.OwnedTitleKind;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import dev.xiaomu.crown.storage.model.OwnedTitleStatus;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.level.ServerPlayer;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Crown 管理员命令（DESIGN.md §17.3、§17.5）。
 *
 * <p>player / view / storage status / audit。所有数据库操作提交到存储执行器，
 * 反馈切回主线程。写操作附带审计并刷新目标玩家称号缓存。</p>
 */
final class CrownAdminCommands {
    private CrownAdminCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> player(
            CrownServerContext context
    ) {
        return Commands.literal("player")
                .requires(source -> can(
                        context, source, CrownPermissions.ADMIN_PLAYER, 3))
                .then(grant(context))
                .then(grantCustom(context))
                .then(revoke(context))
                .then(list(context))
                .then(duration(context))
                .then(selection(context));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> grant(
            CrownServerContext context
    ) {
        return Commands.literal("grant")
                .then(Commands.argument(
                                "player", GameProfileArgument.gameProfile())
                        .then(Commands.argument(
                                        "title", StringArgumentType.word())
                                .suggests((ctx, builder) ->
                                        CrownCommandTree.suggestDefinitions(
                                                context, builder))
                                .executes(ctx -> grant(
                                        context, ctx, null))
                                .then(Commands.literal("permanent")
                                        .executes(ctx -> grant(
                                                context, ctx, 0)))
                                .then(Commands.argument(
                                                "days",
                                                IntegerArgumentType.integer(
                                                        1, 36_500))
                                        .executes(ctx -> grant(
                                                context, ctx,
                                                IntegerArgumentType.getInteger(
                                                        ctx, "days"))))));
    }

    private static int grant(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx,
            Integer overrideDays
    ) throws CommandSyntaxException {
        var profile = GameProfileArgument.getGameProfiles(ctx, "player")
                .iterator().next();
        UUID playerId = profile.id();
        String playerName = profile.name();
        String titleId = StringArgumentType.getString(ctx, "title");

        TitleDefinition definition = context.runtime().snapshot()
                .catalog().find(titleId).orElse(null);
        if (definition == null) {
            ctx.getSource().sendFailure(context.messages()
                    .render("shop.unavailable", titleId));
            return 0;
        }

        DurationPolicy duration = overrideDays == null
                ? definition.duration()
                : (overrideDays == 0
                        ? DurationPolicy.permanent()
                        : DurationPolicy.limited(overrideDays));
        String actor = actor(ctx);

        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() -> {
                    Instant now = Instant.now();
                    context.runtime().storageBackend().repository()
                            .ensurePlayer(playerId, playerName,
                                    TitleSelection.none(), now);
                    OwnedTitleRecord record = grantRecord(
                            definition, playerId, duration, now);
                    AuditRecord audit = grantAudit(
                            actor, playerId, record, now);
                    boolean inserted = context.runtime().storageBackend()
                            .repository().insertOwnedTitleWithAudit(
                                    record, audit);
                    if (inserted) {
                        context.runtime().playerTitleCache()
                                .load(playerId, playerName);
                    }
                    return inserted;
                }),
                inserted -> ctx.getSource().sendSuccess(
                        () -> context.messages().render(
                                inserted
                                        ? "admin.player.granted"
                                        : "purchase.failed.storage",
                                playerName, titleId),
                        true),
                failure -> ctx.getSource().sendFailure(
                        context.messages().render(
                                "purchase.failed.storage")));
        return 1;
    }

    private static OwnedTitleRecord grantRecord(
            TitleDefinition definition,
            UUID playerId,
            DurationPolicy duration,
            Instant now
    ) {
        TitleContent content = definition.content();
        return new OwnedTitleRecord(
                UUID.randomUUID(),
                playerId,
                definition.id(),
                OwnedTitleKind.CATALOG,
                content.textSource(),
                content.prefixSource(),
                content.suffixSource(),
                "admin:grant",
                now,
                duration.expiresAt(now).orElse(null),
                null,
                OwnedTitleStatus.ACTIVE,
                null,
                null);
    }

    private static AuditRecord grantAudit(
            String actor,
            UUID playerId,
            OwnedTitleRecord record,
            Instant now
    ) {
        JsonObject details = new JsonObject();
        details.addProperty("entryId", record.entryId().toString());
        record.definition().ifPresent(id ->
                details.addProperty("definitionId", id.value()));
        details.addProperty("expiresAt", record.expiresAt() == null
                ? "permanent"
                : record.expiresAt().toString());
        return new AuditRecord(
                0, actor, "admin_grant_title",
                playerId, record.entryId().toString(),
                details.toString(), now);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> grantCustom(
            CrownServerContext context
    ) {
        return Commands.literal("grant-custom")
                .then(Commands.argument(
                                "player",
                                GameProfileArgument.gameProfile())
                        .then(Commands.literal("permanent")
                                .executes(ctx -> grantCustom(
                                        context, ctx, 0)))
                        .then(Commands.argument(
                                        "days",
                                        IntegerArgumentType.integer(
                                                1, 36_500))
                                .executes(ctx -> grantCustom(
                                        context,
                                        ctx,
                                        IntegerArgumentType.getInteger(
                                                ctx, "days")))));
    }

    private static int grantCustom(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx,
            int days
    ) throws CommandSyntaxException {
        ServerPlayer administrator = ctx.getSource().getPlayer();
        if (administrator == null) {
            ctx.getSource().sendFailure(context.messages()
                    .render("admin.player.custom.player-only"));
            return 0;
        }

        var profile = GameProfileArgument.getGameProfiles(
                ctx, "player").iterator().next();
        DurationPolicy duration = days == 0
                ? DurationPolicy.permanent()
                : DurationPolicy.limited(days);
        boolean started = CustomTitleInputSessions.beginAdminGrant(
                context,
                administrator,
                profile.id(),
                profile.name(),
                duration);
        if (!started) {
            ctx.getSource().sendFailure(context.messages()
                    .render("admin.player.custom.busy"));
            return 0;
        }
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> revoke(
            CrownServerContext context
    ) {
        return Commands.literal("revoke")
                .then(Commands.argument(
                                "player", GameProfileArgument.gameProfile())
                        .then(Commands.argument(
                                        "entry", StringArgumentType.word())
                                .executes(ctx -> revoke(context, ctx))));
    }

    private static int revoke(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx
    ) throws CommandSyntaxException {
        var profile = GameProfileArgument.getGameProfiles(ctx, "player")
                .iterator().next();
        UUID playerId = profile.id();
        String playerName = profile.name();
        UUID entryId;
        try {
            entryId = UUID.fromString(
                    StringArgumentType.getString(ctx, "entry"));
        } catch (IllegalArgumentException exception) {
            ctx.getSource().sendFailure(context.messages()
                    .render("warehouse.expired"));
            return 0;
        }
        String actor = actor(ctx);

        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() -> {
                    boolean removed = context.runtime().storageBackend()
                            .repository().softDeleteOwnedTitle(
                                    playerId, entryId, actor,
                                    Instant.now());
                    if (removed) {
                        context.runtime().playerTitleCache()
                                .load(playerId, playerName);
                    }
                    return removed;
                }),
                removed -> ctx.getSource().sendSuccess(
                        () -> context.messages().render(
                                removed
                                        ? "admin.player.revoked"
                                        : "shop.unavailable",
                                removed
                                        ? ""
                                        : context.runtime().snapshot()
                                                .languages().text(
                                                        "gui.reason.not-owned")),
                        true),
                failure -> ctx.getSource().sendFailure(
                        context.messages().render(
                                "purchase.failed.storage")));
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> list(
            CrownServerContext context
    ) {
        return Commands.literal("list")
                .then(Commands.argument(
                                "player", GameProfileArgument.gameProfile())
                        .executes(ctx -> list(context, ctx)));
    }

    private static int list(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx
    ) throws CommandSyntaxException {
        var profile = GameProfileArgument.getGameProfiles(ctx, "player")
                .iterator().next();
        UUID playerId = profile.id();
        String playerName = profile.name();

        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() ->
                        context.runtime().storageBackend().repository()
                                .listOwnedTitles(playerId, false)),
                titles -> {
                    ctx.getSource().sendSuccess(
                            () -> context.messages().render(
                                    "admin.player.list.header",
                                    playerName,
                                    Integer.toString(titles.size())),
                            false);
                    for (OwnedTitleRecord record : titles) {
                        ctx.getSource().sendSuccess(
                                () -> context.messages().render(
                                        "admin.player.list.entry",
                                        record.entryId().toString(),
                                        record.definition()
                                                .map(Object::toString)
                                                .orElse("custom"),
                                        record.expiresAt() == null
                                                ? "permanent"
                                                : record.expiresAt()
                                                        .toString()),
                                false);
                    }
                },
                failure -> ctx.getSource().sendFailure(
                        context.messages().render(
                                "purchase.failed.storage")));
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> duration(
            CrownServerContext context
    ) {
        return Commands.literal("duration")
                .then(Commands.argument(
                                "player", GameProfileArgument.gameProfile())
                        .then(Commands.argument(
                                        "entry", StringArgumentType.word())
                                .then(Commands.literal("permanent")
                                        .executes(ctx -> duration(
                                                context, ctx, 0)))
                                .then(Commands.argument(
                                                "days",
                                                IntegerArgumentType.integer(
                                                        1, 36_500))
                                        .executes(ctx -> duration(
                                                context,
                                                ctx,
                                                IntegerArgumentType
                                                        .getInteger(
                                                                ctx,
                                                                "days"))))));
    }

    private static int duration(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx,
            int days
    ) throws CommandSyntaxException {
        var profile = GameProfileArgument.getGameProfiles(
                ctx, "player").iterator().next();
        UUID playerId = profile.id();
        String playerName = profile.name();

        UUID entryId;
        try {
            entryId = UUID.fromString(
                    StringArgumentType.getString(ctx, "entry"));
        } catch (IllegalArgumentException exception) {
            ctx.getSource().sendFailure(context.messages()
                    .render("admin.player.duration.invalid-entry"));
            return 0;
        }

        String actor = actor(ctx);
        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() -> {
                    Instant now = Instant.now();
                    DurationPolicy policy = days == 0
                            ? DurationPolicy.permanent()
                            : DurationPolicy.limited(days);
                    Instant expiresAt =
                            policy.expiresAt(now).orElse(null);

                    JsonObject details = new JsonObject();
                    details.addProperty(
                            "durationType", policy.type().name());
                    details.addProperty("durationDays", policy.days());
                    details.addProperty(
                            "expiresAt",
                            expiresAt == null
                                    ? "permanent"
                                    : expiresAt.toString());
                    AuditRecord audit = new AuditRecord(
                            0,
                            actor,
                            "admin_update_title_duration",
                            playerId,
                            entryId.toString(),
                            details.toString(),
                            now);
                    OwnedTitleDurationStatus result =
                            context.runtime().storageBackend()
                                    .repository()
                                    .updateOwnedTitleDurationWithAudit(
                                            playerId,
                                            entryId,
                                            expiresAt,
                                            audit,
                                            now);
                    if (result
                            == OwnedTitleDurationStatus.UPDATED) {
                        context.runtime().playerTitleCache()
                                .load(playerId, playerName);
                    }
                    return result;
                }),
                result -> sendDurationResult(
                        context,
                        ctx.getSource(),
                        result,
                        playerName,
                        entryId,
                        days),
                failure -> ctx.getSource().sendFailure(
                        context.messages().render(
                                "purchase.failed.storage")));
        return 1;
    }

    private static void sendDurationResult(
            CrownServerContext context,
            CommandSourceStack source,
            OwnedTitleDurationStatus result,
            String playerName,
            UUID entryId,
            int days
    ) {
        switch (result) {
            case UPDATED -> source.sendSuccess(
                    () -> context.messages().render(
                            "admin.player.duration.updated",
                            playerName,
                            entryId.toString(),
                            durationText(context, days)),
                    true);
            case NOT_FOUND -> source.sendFailure(
                    context.messages().render(
                            "admin.player.duration.not-found",
                            entryId.toString()));
            case NOT_OWNED -> source.sendFailure(
                    context.messages().render(
                            "admin.player.duration.not-owned",
                            entryId.toString(),
                            playerName));
            case DELETED -> source.sendFailure(
                    context.messages().render(
                            "admin.player.duration.deleted",
                            entryId.toString()));
        }
    }

    private static String durationText(
            CrownServerContext context,
            int days
    ) {
        var languages = context.runtime().snapshot().languages();
        return days == 0
                ? languages.text("card.duration.permanent")
                : languages.text("card.duration.days")
                        .replace("%0%", Integer.toString(days));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> selection(
            CrownServerContext context
    ) {
        return Commands.literal("selection")
                .then(Commands.argument(
                                "player", GameProfileArgument.gameProfile())
                        .then(Commands.literal("default")
                                .executes(ctx -> selection(
                                        context, ctx,
                                        TitleSelection.defaultTitle())))
                        .then(Commands.literal("none")
                                .executes(ctx -> selection(
                                        context, ctx,
                                        TitleSelection.none())))
                        .then(Commands.argument(
                                        "entry", StringArgumentType.word())
                                .executes(ctx -> {
                                    UUID entryId;
                                    try {
                                        entryId = UUID.fromString(
                                                StringArgumentType.getString(
                                                        ctx, "entry"));
                                    } catch (IllegalArgumentException e) {
                                        ctx.getSource().sendFailure(
                                                context.messages().render(
                                                        "warehouse.expired"));
                                        return 0;
                                    }
                                    return selection(context, ctx,
                                            TitleSelection.owned(entryId));
                                })));
    }

    private static int selection(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx,
            TitleSelection selection
    ) throws CommandSyntaxException {
        var profile = GameProfileArgument.getGameProfiles(ctx, "player")
                .iterator().next();
        UUID playerId = profile.id();
        String playerName = profile.name();

        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() -> {
                    Instant now = Instant.now();
                    if (selection.type() == SelectionType.OWNED) {
                        UUID entryId = selection.ownedEntryId()
                                .orElseThrow();
                        OwnedTitleRecord record = context.runtime()
                                .storageBackend().repository()
                                .findOwnedTitle(entryId).orElse(null);
                        if (record == null
                                || !record.playerId().equals(playerId)
                                || record.status()
                                        != OwnedTitleStatus.ACTIVE) {
                            return false;
                        }
                    }
                    boolean updated = context.runtime().storageBackend()
                            .repository().setSelection(
                                    playerId, selection, now);
                    if (updated) {
                        context.runtime().playerTitleCache()
                                .load(playerId, playerName);
                    }
                    return updated;
                }),
                updated -> ctx.getSource().sendSuccess(
                        () -> context.messages().render(
                                updated
                                        ? "admin.player.selection"
                                        : "shop.unavailable",
                                updated
                                        ? ""
                                        : context.runtime().snapshot()
                                                .languages().text(
                                                        "gui.reason.not-owned")),
                        true),
                failure -> ctx.getSource().sendFailure(
                        context.messages().render(
                                "purchase.failed.storage")));
        return 1;
    }

    static LiteralArgumentBuilder<CommandSourceStack> view(
            CrownServerContext context
    ) {
        return Commands.literal("view")
                .requires(source -> can(
                        context, source, CrownPermissions.ADMIN_VIEW, 3))
                .then(Commands.argument(
                                "player", GameProfileArgument.gameProfile())
                        .executes(ctx -> view(context, ctx)));
    }

    private static int view(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx
    ) throws CommandSyntaxException {
        ServerPlayer administrator = ctx.getSource().getPlayer();
        if (administrator == null) {
            return list(context, ctx);
        }
        var profile = GameProfileArgument.getGameProfiles(ctx, "player")
                .iterator().next();
        CrownAdminWarehouseGui.open(context, administrator,
                profile.id(), profile.name());
        return 1;
    }

    static LiteralArgumentBuilder<CommandSourceStack> storage(
            CrownServerContext context
    ) {
        return Commands.literal("storage")
                .requires(source -> can(
                        context, source, CrownPermissions.ADMIN_STORAGE, 3))
                .then(Commands.literal("status")
                        .executes(ctx -> storageStatus(context, ctx)))
                .then(Commands.literal("migrate")
                        .then(Commands.literal("schema")
                                .executes(ctx -> migrateSchema(
                                        context, ctx)))
                        .then(Commands.literal("sqlite")
                                .executes(ctx -> migrateStorage(
                                        context,
                                        ctx,
                                        StorageSettings.Type.SQLITE)))
                        .then(Commands.literal("mysql")
                                .executes(ctx -> migrateStorage(
                                        context,
                                        ctx,
                                        StorageSettings.Type.MYSQL))));
    }

    private static int storageStatus(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx
    ) {
        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() ->
                        context.runtime().storageBackend().repository()
                                .summarize()),
                summary -> ctx.getSource().sendSuccess(
                        () -> context.messages().render(
                                "admin.storage.status",
                                context.runtime().snapshot().storage()
                                        .type().name().toLowerCase(
                                                java.util.Locale.ROOT),
                                Long.toString(summary.playerCount()),
                                Long.toString(summary.ownedTitleCount()),
                                Long.toString(
                                        summary.purchaseOrderCount())),
                        false),
                failure -> ctx.getSource().sendFailure(
                        context.messages().render(
                                "purchase.failed.storage")));
        return 1;
    }

    private static int migrateSchema(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx
    ) {
        if (context.runtime().storageMaintenance()) {
            ctx.getSource().sendFailure(context.messages().render(
                    "storage.maintenance"));
            return 0;
        }
        String migrationActor = actor(ctx);
        ctx.getSource().sendSuccess(
                () -> context.messages().render(
                        "admin.storage.migration.started", "schema"),
                true);
        context.mainThread().whenComplete(
                context.runtime().migrateSchema(migrationActor),
                version -> ctx.getSource().sendSuccess(
                        () -> context.messages().render(
                                "admin.storage.schema.success",
                                Integer.toString(version)),
                        true),
                failure -> ctx.getSource().sendFailure(
                        context.messages().render(
                                "admin.storage.migration.failed",
                                safeMessage(failure))));
        return 1;
    }

    private static int migrateStorage(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx,
            StorageSettings.Type target
    ) {
        if (context.runtime().storageMaintenance()) {
            ctx.getSource().sendFailure(context.messages().render(
                    "storage.maintenance"));
            return 0;
        }
        String targetName = target.name().toLowerCase(
                java.util.Locale.ROOT);
        String migrationActor = actor(ctx);
        ctx.getSource().sendSuccess(
                () -> context.messages().render(
                        "admin.storage.migration.started", targetName),
                true);
        context.mainThread().whenComplete(
                context.runtime().migrateStorage(
                        target, migrationActor),
                report -> {
                    ctx.getSource().sendSuccess(
                            () -> context.messages().render(
                                    "admin.storage.migration.success",
                                    report.sourceType().name().toLowerCase(
                                            java.util.Locale.ROOT),
                                    report.targetType().name().toLowerCase(
                                            java.util.Locale.ROOT),
                                    Long.toString(
                                            report.totalCopiedRows()),
                                    Long.toString(
                                            report.duration().toMillis())),
                            true);
                    ctx.getSource().sendSuccess(
                            () -> context.messages().render(
                                    "admin.storage.migration.switch-hint",
                                    targetName),
                            false);
                },
                failure -> ctx.getSource().sendFailure(
                        context.messages().render(
                                "admin.storage.migration.failed",
                                safeMessage(failure))));
        return 1;
    }

    static LiteralArgumentBuilder<CommandSourceStack> audit(
            CrownServerContext context
    ) {
        return Commands.literal("audit")
                .requires(source -> can(
                        context, source, CrownPermissions.ADMIN_AUDIT, 3))
                .then(Commands.argument(
                                "target", StringArgumentType.word())
                        .executes(ctx -> audit(context, ctx)));
    }

    private static int audit(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx
    ) {
        String target = StringArgumentType.getString(ctx, "target");
        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() ->
                        context.runtime().storageBackend().repository()
                                .findAuditByTarget(target, 20)),
                records -> {
                    ctx.getSource().sendSuccess(
                            () -> context.messages().render(
                                    "admin.audit.header", target,
                                    Integer.toString(records.size())),
                            false);
                    for (AuditRecord record : records) {
                        ctx.getSource().sendSuccess(
                                () -> context.messages().render(
                                        "admin.audit.entry",
                                        record.action(),
                                        record.actor(),
                                        record.createdAt().toString()),
                                false);
                    }
                },
                failure -> ctx.getSource().sendFailure(
                        context.messages().render(
                                "purchase.failed.storage")));
        return 1;
    }

    static LiteralArgumentBuilder<CommandSourceStack> cardCreate(
            CrownServerContext context
    ) {
        return Commands.literal("create")
                .requires(source -> can(
                        context, source, CrownPermissions.ADMIN_CARD, 3))
                .then(Commands.argument(
                                "title", StringArgumentType.word())
                        .suggests((ctx, builder) ->
                                CrownCommandTree.suggestDefinitions(
                                        context, builder))
                        .then(cardPermanent(context))
                        .then(cardLimited(context)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack>
    cardPermanent(CrownServerContext context) {
        return Commands.literal("permanent")
                .executes(ctx -> runCardCreate(
                        context, ctx, 0, 1, false))
                .then(Commands.argument(
                                "amount",
                                IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> runCardCreate(
                                context,
                                ctx,
                                0,
                                IntegerArgumentType.getInteger(
                                        ctx, "amount"),
                                false))
                        .then(Commands.argument(
                                        "recipient",
                                        GameProfileArgument.gameProfile())
                                .executes(ctx -> runCardCreate(
                                        context,
                                        ctx,
                                        0,
                                        IntegerArgumentType.getInteger(
                                                ctx, "amount"),
                                        true))))
                .then(Commands.argument(
                                "recipient",
                                GameProfileArgument.gameProfile())
                        .executes(ctx -> runCardCreate(
                                context, ctx, 0, 1, true)));
    }

    private static com.mojang.brigadier.builder
            .RequiredArgumentBuilder<CommandSourceStack, Integer>
    cardLimited(CrownServerContext context) {
        return Commands.argument(
                        "days",
                        IntegerArgumentType.integer(1, 36_500))
                .executes(ctx -> runCardCreate(
                        context,
                        ctx,
                        IntegerArgumentType.getInteger(ctx, "days"),
                        1,
                        false))
                .then(Commands.argument(
                                "amount",
                                IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> runCardCreate(
                                context,
                                ctx,
                                IntegerArgumentType.getInteger(
                                        ctx, "days"),
                                IntegerArgumentType.getInteger(
                                        ctx, "amount"),
                                false))
                        .then(Commands.argument(
                                        "recipient",
                                        GameProfileArgument.gameProfile())
                                .executes(ctx -> runCardCreate(
                                        context,
                                        ctx,
                                        IntegerArgumentType.getInteger(
                                                ctx, "days"),
                                        IntegerArgumentType.getInteger(
                                                ctx, "amount"),
                                        true))))
                .then(Commands.argument(
                                "recipient",
                                GameProfileArgument.gameProfile())
                        .executes(ctx -> runCardCreate(
                                context,
                                ctx,
                                IntegerArgumentType.getInteger(
                                        ctx, "days"),
                                1,
                                true)));
    }

    private static int runCardCreate(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx,
            int days,
            int amount,
            boolean explicitRecipient
    ) throws CommandSyntaxException {
        String titleId = StringArgumentType.getString(ctx, "title");
        TitleDefinition definition = context.runtime().snapshot()
                .catalog().find(titleId).orElse(null);
        if (definition == null) {
            ctx.getSource().sendFailure(context.messages()
                    .render("shop.unavailable", titleId));
            return 0;
        }

        ServerPlayer recipient = resolveCardRecipient(
                context, ctx, explicitRecipient);
        if (recipient == null) {
            return 0;
        }
        UUID recipientId = recipient.getUUID();
        String recipientName = recipient.getGameProfile().name();
        DurationPolicy duration = days == 0
                ? DurationPolicy.permanent()
                : DurationPolicy.limited(days);
        String issuer = actor(ctx);
        UUID batchId = UUID.randomUUID();

        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() -> {
                    Instant now = Instant.now();
                    List<CardRecord> cards =
                            new ArrayList<>(amount);
                    for (int i = 0; i < amount; i++) {
                        cards.add(new CardRecord(
                                generateToken(),
                                definition.id(),
                                duration,
                                issuer,
                                now,
                                null,
                                null));
                    }

                    JsonObject details = new JsonObject();
                    details.addProperty(
                            "definitionId", titleId);
                    details.addProperty(
                            "amount", amount);
                    details.addProperty(
                            "durationType",
                            duration.type().name());
                    details.addProperty(
                            "durationDays", duration.days());
                    details.addProperty(
                            "recipientId",
                            recipientId.toString());
                    details.addProperty(
                            "recipientName", recipientName);
                    AuditRecord audit = new AuditRecord(
                            0,
                            issuer,
                            "admin_create_cards",
                            recipientId,
                            batchId.toString(),
                            details.toString(),
                            now);
                    return context.runtime().storageBackend()
                            .repository().createCardsWithAudit(
                                    cards, audit);
                }),
                cards -> deliverCards(
                        context,
                        ctx,
                        recipientId,
                        recipientName,
                        titleId,
                        cards),
                failure -> ctx.getSource().sendFailure(
                        context.messages().render(
                                "purchase.failed.storage")));
        return 1;
    }

    private static ServerPlayer resolveCardRecipient(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx,
            boolean explicitRecipient
    ) throws CommandSyntaxException {
        if (!explicitRecipient) {
            ServerPlayer player = ctx.getSource().getPlayer();
            if (player == null) {
                ctx.getSource().sendFailure(context.messages()
                        .render("admin.card.recipient-required"));
            }
            return player;
        }

        var profile = GameProfileArgument.getGameProfiles(
                ctx, "recipient").iterator().next();
        ServerPlayer player = context.server().getPlayerList()
                .getPlayer(profile.id());
        if (player == null) {
            ctx.getSource().sendFailure(context.messages()
                    .render("admin.card.recipient-offline",
                            profile.name()));
        }
        return player;
    }

    private static void deliverCards(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx,
            UUID recipientId,
            String recipientName,
            String titleId,
            List<CardRecord> cards
    ) {
        ServerPlayer recipient = context.server().getPlayerList()
                .getPlayer(recipientId);
        if (recipient == null) {
            ctx.getSource().sendFailure(context.messages()
                    .render("admin.card.recipient-disconnected",
                            recipientName));
            for (CardRecord card : cards) {
                ctx.getSource().sendSuccess(
                        () -> context.messages().render(
                                "admin.card.token",
                                card.cardToken()),
                        false);
            }
            return;
        }

        for (CardRecord card : cards) {
            var item = CrownCardItems.create(context, card);
            if (!recipient.addItem(item)) {
                recipient.drop(item, false);
            }
        }
        ctx.getSource().sendSuccess(
                () -> context.messages().render(
                        "admin.card.created",
                        Integer.toString(cards.size()),
                        titleId,
                        recipientName),
                true);
        recipient.sendSystemMessage(context.messages().render(
                "card.received",
                Integer.toString(cards.size()),
                titleId));
    }

    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();

    private static String generateToken() {
        byte[] bytes = new byte[24];
        TOKEN_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(bytes);
    }

    private static String actor(CommandContext<CommandSourceStack> ctx) {
        var player = ctx.getSource().getPlayer();
        return player != null
                ? "player:" + player.getUUID()
                : "console";
    }

    private static String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        StringBuilder safe = new StringBuilder(
                Math.min(message.length(), 160));
        message.codePoints()
                .filter(codePoint ->
                        !Character.isISOControl(codePoint))
                .limit(160)
                .forEach(safe::appendCodePoint);
        return safe.toString();
    }

    private static boolean can(
            CrownServerContext context,
            CommandSourceStack source,
            String node,
            int fallbackOpLevel
    ) {
        return context.permissions().checkSource(
                dev.xiaomu.crown.runtime.platform.PermissionSource.of(
                        source),
                node, fallbackOpLevel);
    }
}