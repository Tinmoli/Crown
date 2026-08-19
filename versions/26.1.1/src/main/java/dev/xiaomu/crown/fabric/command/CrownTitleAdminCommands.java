package dev.xiaomu.crown.fabric.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.xiaomu.crown.config.edit.TitleCatalogEditor;
import dev.xiaomu.crown.domain.catalog.TitleDefinition;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.gui.CrownAdminShopGui;
import dev.xiaomu.crown.fabric.permission.CrownPermissions;
import dev.xiaomu.crown.runtime.platform.PermissionSource;
import dev.xiaomu.crown.storage.model.AuditRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** DESIGN.md §17.2 管理员商品配置命令。 */
public final class CrownTitleAdminCommands {
    private static final TitleCatalogEditor EDITOR =
            new TitleCatalogEditor();

    private CrownTitleAdminCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> title(
            CrownServerContext context
    ) {
        return Commands.literal("title")
                .requires(source -> can(context, source))
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendFailure(
                                context.messages().render(
                                        "admin.title.edit.player-only"));
                        return 0;
                    }
                    CrownAdminShopGui.open(context, player);
                    return 1;
                })
                .then(create(context))
                .then(idOperation(context, "delete",
                        (editor, file, id) -> editor.delete(
                                file,
                                context.core().safety(),
                                id,
                                context.runtime()::reload)))
                .then(Commands.literal("list")
                        .executes(ctx -> list(context, ctx)))
                .then(edit(context))
                .then(fieldsOperation(
                        context,
                        "enable",
                        fields("enabled", true, "visible", true)))
                .then(simpleField(
                        context, "disable", "enabled", false))
                .then(textField(context, "text", "text"))
                .then(textField(context, "prefix", "prefix"))
                .then(textField(context, "suffix", "suffix"))
                .then(duration(context))
                .then(payment(context))
                .then(permission(context))
                .then(numberField(
                        context,
                        "stock",
                        "sale.global-stock",
                        true))
                .then(numberField(
                        context,
                        "limit",
                        "sale.per-player-limit",
                        false))
                .then(saleTime(context, "sale-start", "sale.starts-at"))
                .then(saleTime(context, "sale-end", "sale.ends-at"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> create(
            CrownServerContext context
    ) {
        return Commands.literal("create")
                .then(idArgument(context)
                        .executes(ctx -> {
                            String id = id(ctx);
                            ServerPlayer player = ctx.getSource().getPlayer();
                            return submit(
                                    context,
                                    ctx,
                                    id,
                                    "create",
                                    Map.of(),
                                    (editor, file, definitionId) ->
                                            editor.create(
                                                    file,
                                                    context.core().safety(),
                                                    definitionId,
                                                    context.runtime()
                                                            ::reload),
                                    player == null
                                            ? null
                                            : () -> CrownAdminShopGui
                                                    .openDetail(
                                                            context,
                                                            player,
                                                            id));
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> edit(
            CrownServerContext context
    ) {
        return Commands.literal("edit")
                .then(idArgument(context)
                        .executes(ctx -> {
                            String id = id(ctx);
                            if (!exists(context, id)) {
                                fail(context, ctx, id,
                                        "Unknown title: " + id);
                                return 0;
                            }
                            if (ctx.getSource().getPlayer() == null) {
                                ctx.getSource().sendFailure(
                                        context.messages().render(
                                                "admin.title.edit.player-only"));
                                return 0;
                            }
                            CrownAdminShopGui.openDetail(
                                    context,
                                    ctx.getSource().getPlayer(),
                                    id);
                            return 1;
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> idOperation(
            CrownServerContext context,
            String action,
            CatalogOperation operation
    ) {
        return Commands.literal(action)
                .then(idArgument(context)
                        .executes(ctx -> submit(
                                context,
                                ctx,
                                id(ctx),
                                action,
                                Map.of(),
                                operation)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> simpleField(
            CrownServerContext context,
            String action,
            String field,
            Object value
    ) {
        return fieldsOperation(
                context, action, fields(field, value));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> fieldsOperation(
            CrownServerContext context,
            String action,
            Map<String, Object> fields
    ) {
        return Commands.literal(action)
                .then(idArgument(context)
                        .executes(ctx -> submitSet(
                                context,
                                ctx,
                                id(ctx),
                                action,
                                fields)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> textField(
            CrownServerContext context,
            String action,
            String field
    ) {
        return Commands.literal(action)
                .then(idArgument(context)
                        .then(Commands.argument(
                                        "value",
                                        StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String value =
                                            StringArgumentType.getString(
                                                    ctx, "value");
                                    return submitSet(
                                            context,
                                            ctx,
                                            id(ctx),
                                            action,
                                            fields(field, value));
                                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> duration(
            CrownServerContext context
    ) {
        return Commands.literal("duration")
                .then(idArgument(context)
                        .then(Commands.literal("permanent")
                                .executes(ctx -> submitSet(
                                        context,
                                        ctx,
                                        id(ctx),
                                        "duration",
                                        fields(
                                                "duration.type",
                                                "PERMANENT",
                                                "duration.days",
                                                0))))
                        .then(Commands.argument(
                                        "days",
                                        IntegerArgumentType.integer(1))
                                .executes(ctx -> submitSet(
                                        context,
                                        ctx,
                                        id(ctx),
                                        "duration",
                                        fields(
                                                "duration.type",
                                                "LIMITED",
                                                "duration.days",
                                                IntegerArgumentType.getInteger(
                                                        ctx, "days"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> payment(
            CrownServerContext context
    ) {
        var product = idArgument(context);
        product.then(Commands.literal("free")
                .executes(ctx -> submitSet(
                        context, ctx, id(ctx), "payment",
                        paymentFields("free", null))));
        product.then(Commands.literal("title_coin")
                .then(Commands.argument("price", StringArgumentType.word())
                        .executes(ctx -> submitSet(
                                context, ctx, id(ctx), "payment",
                                paymentFields("title-coin", price(ctx))))));
        product.then(Commands.literal("mint")
                .then(Commands.argument("price", StringArgumentType.word())
                        .executes(ctx -> submitSet(
                                context, ctx, id(ctx), "payment",
                                paymentFields("mint", price(ctx))))));
        return Commands.literal("payment").then(product);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> permission(
            CrownServerContext context
    ) {
        return Commands.literal("permission")
                .then(idArgument(context)
                        .then(Commands.argument(
                                        "permission",
                                        StringArgumentType.word())
                                .executes(ctx -> {
                                    String permission =
                                            StringArgumentType.getString(
                                                    ctx, "permission");
                                    if ("none".equalsIgnoreCase(permission)) {
                                        permission = "";
                                    }
                                    return submitSet(
                                            context,
                                            ctx,
                                            id(ctx),
                                            "permission",
                                            fields(
                                                    "requirement.permission",
                                                    permission));
                                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> numberField(
            CrownServerContext context,
            String action,
            String field,
            boolean longNumber
    ) {
        var literal = Commands.literal(action);
        if (longNumber) {
            return literal.then(idArgument(context)
                    .then(Commands.argument(
                                    "amount",
                                    LongArgumentType.longArg(-1))
                            .executes(ctx -> submitSet(
                                    context,
                                    ctx,
                                    id(ctx),
                                    action,
                                    fields(
                                            field,
                                            LongArgumentType.getLong(
                                                    ctx, "amount"))))));
        }
        return literal.then(idArgument(context)
                .then(Commands.argument(
                                "amount",
                                IntegerArgumentType.integer(-1))
                        .executes(ctx -> submitSet(
                                context,
                                ctx,
                                id(ctx),
                                action,
                                fields(
                                        field,
                                        IntegerArgumentType.getInteger(
                                                ctx, "amount"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> saleTime(
            CrownServerContext context,
            String action,
            String field
    ) {
        return Commands.literal(action)
                .then(idArgument(context)
                        .then(Commands.argument(
                                        "time",
                                        StringArgumentType.word())
                                .executes(ctx -> {
                                    String raw = StringArgumentType.getString(
                                            ctx, "time");
                                    Object value = "none".equalsIgnoreCase(raw)
                                            ? null
                                            : Instant.parse(raw).toString();
                                    return submitSet(
                                            context,
                                            ctx,
                                            id(ctx),
                                            action,
                                            fields(field, value));
                                })));
    }

    private static com.mojang.brigadier.builder
            .RequiredArgumentBuilder<CommandSourceStack, String> idArgument(
            CrownServerContext context
    ) {
        return Commands.argument("id", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    for (var definition : context.runtime().snapshot()
                            .catalog().definitions().values()) {
                        builder.suggest(definition.id().value());
                    }
                    return builder.buildFuture();
                });
    }

    private static int submitSet(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx,
            String id,
            String action,
            Map<String, Object> fields
    ) {
        return submit(
                context,
                ctx,
                id,
                action,
                fields,
                (editor, file, definitionId) -> editor.setAll(
                        file,
                        context.core().safety(),
                        definitionId,
                        fields,
                        context.runtime()::reload));
    }

    private static int submit(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx,
            String id,
            String action,
            Map<String, Object> fields,
            CatalogOperation operation
    ) {
        return submit(context, ctx, id, action, fields, operation, null);
    }

    private static int submit(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx,
            String id,
            String action,
            Map<String, Object> fields,
            CatalogOperation operation,
            Runnable afterSuccess
    ) {
        String actor = actor(ctx.getSource());
        Path file = context.runtime().configRoot().resolve("titles.yml");
        JsonObject details = new JsonObject();
        details.addProperty("operation", action);
        details.addProperty("definitionId", id);
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                details.add(entry.getKey(), null);
            } else if (value instanceof Number number) {
                details.addProperty(entry.getKey(), number);
            } else if (value instanceof Boolean bool) {
                details.addProperty(entry.getKey(), bool);
            } else {
                details.addProperty(entry.getKey(), value.toString());
            }
        }

        AtomicBoolean configurationApplied = new AtomicBoolean();
        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() -> {
                    try {
                        operation.apply(EDITOR, file, id);
                    } catch (Exception exception) {
                        throw new CompletionException(exception);
                    }
                    configurationApplied.set(true);
                    /*
                     * titles.yml 与 JDBC 无法共享事务。配置已经通过原子替换
                     * 和内部重载提交后，再写不可变审计；审计失败会明确反馈，
                     * 不谎称整个操作失败或自动覆盖已生效配置。
                     */
                    context.runtime().storageBackend().repository()
                            .appendAudit(new AuditRecord(
                                    0,
                                    actor,
                                    "admin_title_" + action,
                                    null,
                                    id,
                                    details.toString(),
                                    Instant.now()));
                    return null;
                }),
                ignored -> {
                    ctx.getSource().sendSuccess(
                            () -> context.messages().render(
                                    "admin.title.changed", id, action),
                            true);
                    if (afterSuccess != null) {
                        afterSuccess.run();
                    }
                },
                failure -> {
                    String message = safeMessage(failure);
                    if (configurationApplied.get()
                            && isAuditFailure(failure)) {
                        ctx.getSource().sendFailure(
                                context.messages().render(
                                        "admin.title.audit-failed",
                                        message));
                    } else {
                        fail(context, ctx, id, message);
                    }
                });
        return 1;
    }

    /**
     * GUI 商品修改入口；复用与命令一致的异步原子编辑、内部重载与审计流程。
     */
    public static void updateFromGui(
            CrownServerContext context,
            ServerPlayer player,
            String id,
            String action,
            Map<String, Object> fields,
            Runnable afterSuccess
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(fields, "fields");
        if (!context.permissions().checkSource(
                PermissionSource.of(player.createCommandSourceStack()),
                CrownPermissions.ADMIN_TITLE,
                3)) {
            player.sendSystemMessage(context.messages().render(
                    "command.no-permission"));
            return;
        }

        Path file = context.runtime().configRoot().resolve("titles.yml");
        JsonObject details = auditDetails(action, id, fields);
        AtomicBoolean configurationApplied = new AtomicBoolean();
        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() -> {
                    try {
                        EDITOR.setAll(
                                file,
                                context.core().safety(),
                                id,
                                fields,
                                context.runtime()::reload);
                    } catch (Exception exception) {
                        throw new CompletionException(exception);
                    }
                    configurationApplied.set(true);
                    context.runtime().storageBackend().repository()
                            .appendAudit(new AuditRecord(
                                    0,
                                    "player:" + player.getUUID(),
                                    "admin_title_" + action,
                                    null,
                                    id,
                                    details.toString(),
                                    Instant.now()));
                    return null;
                }),
                ignored -> {
                    player.sendSystemMessage(context.messages().render(
                            "admin.title.changed", id, action));
                    if (afterSuccess != null) {
                        afterSuccess.run();
                    }
                },
                failure -> {
                    String message = safeMessage(failure);
                    String key = configurationApplied.get()
                            && isAuditFailure(failure)
                            ? "admin.title.audit-failed"
                            : "admin.title.failed";
                    if ("admin.title.audit-failed".equals(key)) {
                        player.sendSystemMessage(context.messages().render(
                                key, message));
                    } else {
                        player.sendSystemMessage(context.messages().render(
                                key, id, message));
                    }
                });
    }

    /** GUI 商品删除入口；配置删除不触碰玩家已拥有的历史条目。 */
    public static void deleteFromGui(
            CrownServerContext context,
            ServerPlayer player,
            String id,
            Runnable afterSuccess
    ) {
        if (!context.permissions().checkSource(
                PermissionSource.of(player.createCommandSourceStack()),
                CrownPermissions.ADMIN_TITLE, 3)) {
            player.sendSystemMessage(context.messages().render("command.no-permission"));
            return;
        }
        Path file = context.runtime().configRoot().resolve("titles.yml");
        AtomicBoolean configurationApplied = new AtomicBoolean();
        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() -> {
                    try {
                        EDITOR.delete(file, context.core().safety(), id,
                                context.runtime()::reload);
                    } catch (Exception exception) {
                        throw new CompletionException(exception);
                    }
                    configurationApplied.set(true);
                    context.runtime().storageBackend().repository().appendAudit(
                            new AuditRecord(0, "player:" + player.getUUID(),
                                    "admin_title_delete", null, id,
                                    "{\"definitionId\":\"" + id + "\"}", Instant.now()));
                    return null;
                }),
                ignored -> {
                    player.sendSystemMessage(context.messages().render(
                            "admin.title.deleted", id));
                    if (afterSuccess != null) afterSuccess.run();
                },
                failure -> player.sendSystemMessage(context.messages().render(
                        configurationApplied.get() && isAuditFailure(failure)
                                ? "admin.title.audit-failed" : "admin.title.failed",
                        configurationApplied.get() && isAuditFailure(failure)
                                ? safeMessage(failure) : id,
                        configurationApplied.get() && isAuditFailure(failure)
                                ? "" : safeMessage(failure))));
    }

    private static JsonObject auditDetails(
            String action,
            String id,
            Map<String, Object> fields
    ) {
        JsonObject details = new JsonObject();
        details.addProperty("operation", action);
        details.addProperty("definitionId", id);
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                details.add(entry.getKey(), null);
            } else if (value instanceof Number number) {
                details.addProperty(entry.getKey(), number);
            } else if (value instanceof Boolean bool) {
                details.addProperty(entry.getKey(), bool);
            } else {
                details.addProperty(entry.getKey(), value.toString());
            }
        }
        return details;
    }

    private static int list(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx
    ) {
        var definitions = context.runtime().snapshot()
                .catalog().definitions().values();
        ctx.getSource().sendSuccess(
                () -> context.messages().render(
                        "admin.title.list.header",
                        Integer.toString(definitions.size())),
                false);
        for (TitleDefinition definition : definitions) {
            ctx.getSource().sendSuccess(
                    () -> context.messages().render(
                            "admin.title.list.entry",
                            definition.id().value(),
                            Boolean.toString(definition.enabled()),
                            Boolean.toString(definition.visible()),
                            definition.payment().type().name()
                                    .toLowerCase(Locale.ROOT)),
                    false);
        }
        return definitions.size();
    }

    private static boolean exists(
            CrownServerContext context,
            String id
    ) {
        return context.runtime().snapshot().catalog().find(id).isPresent();
    }

    private static String id(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "id");
    }

    private static String price(
            CommandContext<CommandSourceStack> ctx
    ) {
        String raw = StringArgumentType.getString(ctx, "price");
        BigDecimal parsed = new BigDecimal(raw);
        if (parsed.signum() < 0) {
            throw new IllegalArgumentException(
                    "Price cannot be negative");
        }
        return parsed.stripTrailingZeros().toPlainString();
    }

    private static Map<String, Object> paymentFields(
            String type,
            String price
    ) {
        if ("free".equals(type)) {
            return Map.of("payment-options", Map.of("free", true));
        }
        if ("mint".equals(type)) {
            return Map.of("payment-options", Map.of(type, Map.of("price", price)));
        }
        return Map.of("payment-options", Map.of(type, Map.of("price", price)));
    }

    private static Map<String, Object> fields(
            Object... pairs
    ) {
        if (pairs.length == 0 || pairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Invalid title field pairs");
        }
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) {
            String key = Objects.requireNonNull(
                    (String) pairs[index], "field");
            result.put(key, pairs[index + 1]);
        }
        return result;
    }

    private static void fail(
            CrownServerContext context,
            CommandContext<CommandSourceStack> ctx,
            String id,
            String error
    ) {
        ctx.getSource().sendFailure(context.messages().render(
                "admin.title.failed", id, error));
    }

    private static boolean can(
            CrownServerContext context,
            CommandSourceStack source
    ) {
        return context.permissions().checkSource(
                PermissionSource.of(source),
                CrownPermissions.ADMIN_TITLE,
                3);
    }

    private static String actor(CommandSourceStack source) {
        var player = source.getPlayer();
        return player == null
                ? "console"
                : "player:" + player.getUUID();
    }

    private static boolean isAuditFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String name = current.getClass().getName();
            if (name.contains("Storage")
                    || name.contains("SQLException")
                    || name.contains("Jdbc")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null
                && current.getMessage() == null) {
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

    @FunctionalInterface
    private interface CatalogOperation {
        void apply(
                TitleCatalogEditor editor,
                Path titlesFile,
                String id
        ) throws Exception;
    }
}