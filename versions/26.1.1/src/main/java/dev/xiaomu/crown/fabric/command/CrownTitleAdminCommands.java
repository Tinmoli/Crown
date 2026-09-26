package dev.xiaomu.crown.fabric.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.xiaomu.crown.config.edit.TitleCatalogEditor;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.gui.CrownAdminShopGui;
import dev.xiaomu.crown.fabric.permission.CrownPermissions;
import dev.xiaomu.crown.runtime.platform.PermissionSource;
import dev.xiaomu.crown.storage.model.AuditRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 管理员商品配置命令。 */
public final class CrownTitleAdminCommands {
    private static final TitleCatalogEditor EDITOR =
            new TitleCatalogEditor();
    private static final Logger LOGGER =
            LoggerFactory.getLogger(CrownTitleAdminCommands.class);

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
                });

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
                    LOGGER.warn("Failed to update title {} ({})",
                            id, action, failure);
                    if (configurationApplied.get()
                            && isAuditFailure(failure)) {
                        player.sendSystemMessage(context.messages().render(
                                "admin.title.audit-failed", id));
                    } else {
                        player.sendSystemMessage(context.messages().render(
                                "admin.title.failed", id,
                                reason(context, "error.internal")));
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
                failure -> {
                    LOGGER.warn("Failed to delete title {}", id, failure);
                    if (configurationApplied.get()
                            && isAuditFailure(failure)) {
                        player.sendSystemMessage(context.messages().render(
                                "admin.title.audit-failed", id));
                    } else {
                        player.sendSystemMessage(context.messages().render(
                                "admin.title.failed", id,
                                reason(context, "error.internal")));
                    }
                });
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

    /** 把语言键解析为当前语言文案；未知键回退为键名本身。 */
    private static String reason(
            CrownServerContext context,
            String key
    ) {
        return context.runtime().snapshot().languages().text(key);
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

}
