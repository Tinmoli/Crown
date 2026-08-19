package dev.xiaomu.crown.fabric.placeholder;

import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.text.FabricTextAdapter;
import dev.xiaomu.crown.runtime.display.ResolvedTitle;
import eu.pb4.placeholders.api.PlaceholderResult;
import eu.pb4.placeholders.api.Placeholders;
import eu.pb4.placeholders.api.ServerPlaceholderContext;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 向 Text Placeholder API 注册 Crown 称号变量（DESIGN.md §19）。
 *
 * <p>使用 {@code registerServer} + {@link ServerPlaceholderContext}，在
 * 3.0.0+ 与 3.1.0+ 上签名一致。只读内存缓存，绝不等待数据库；未安装 API
 * 时由调用方跳过注册。</p>
 */
public final class CrownPlaceholders {
    private CrownPlaceholders() {
    }

    /** 注册全部变量；仅在 placeholder-api 已加载时调用。 */
    public static void register(CrownServerContext context) {
        Objects.requireNonNull(context, "context");
        FabricTextAdapter adapter = context.textAdapter();

        registerComponent(context, "title", (player, title) ->
                adapter.component(title.fullText()));
        registerComponent(context, "title_text", (player, title) ->
                adapter.component(title.text()));
        registerComponent(context, "title_prefix", (player, title) ->
                adapter.component(title.prefix()));
        registerComponent(context, "title_suffix", (player, title) ->
                adapter.component(title.suffix()));

        registerString(context, "title_plain",
                (player, title) -> title.plainText());
        registerString(context, "title_id", (player, title) ->
                switch (title.state()) {
                    case DEFAULT -> "default";
                    case OWNED -> title.entryId() == null
                            ? "" : title.entryId().toString();
                    case NONE -> "";
                });
        registerString(context, "title_definition",
                (player, title) -> title.definitionId());
        registerString(context, "title_state", (player, title) ->
                title.state().name().toLowerCase(java.util.Locale.ROOT));
        registerString(context, "title_expires", (player, title) ->
                expiresText(context, title));

        registerString(context, "title_coin", (player, title) -> {
            var coin = context.core().titleCoin();
            long balance = context.runtime().playerTitleCache()
                    .get(player.getUUID()).titleCoinBalance();
            return coin.format()
                    .replace("{amount}", Long.toString(balance))
                    .replace("{name}", coin.name());
        });
        registerString(context, "title_coin_raw", (player, title) ->
                Long.toString(context.runtime().playerTitleCache()
                        .get(player.getUUID()).titleCoinBalance()));
    }

    private static void registerComponent(
            CrownServerContext context,
            String name,
            ComponentResolver resolver
    ) {
        Placeholders.registerServer(
                Identifier.fromNamespaceAndPath("crown", name),
                (ctx, argument) -> {
                    ServerPlayer player = playerOf(ctx);
                    if (player == null) {
                        return PlaceholderResult.invalid("no player");
                    }
                    ResolvedTitle title = lookup(context, player.getUUID());
                    return PlaceholderResult.value(
                            resolver.resolve(player, title));
                });
    }

    private static void registerString(
            CrownServerContext context,
            String name,
            StringResolver resolver
    ) {
        Placeholders.registerServer(
                Identifier.fromNamespaceAndPath("crown", name),
                (ctx, argument) -> {
                    ServerPlayer player = playerOf(ctx);
                    if (player == null) {
                        return PlaceholderResult.invalid("no player");
                    }
                    ResolvedTitle title = lookup(context, player.getUUID());
                    return PlaceholderResult.value(
                            Component.literal(
                                    resolver.resolve(player, title)));
                });
    }

    private static ServerPlayer playerOf(ServerPlaceholderContext ctx) {
        return ctx.hasPlayer() ? ctx.serverPlayer() : null;
    }

    private static ResolvedTitle lookup(
            CrownServerContext context,
            UUID playerId
    ) {
        return context.runtime().playerTitleCache()
                .get(playerId).title();
    }

    private static String expiresText(
            CrownServerContext context,
            ResolvedTitle title
    ) {
        if (title.expiresAt() == null) {
            return title.state() == dev.xiaomu.crown.domain.player.SelectionType.NONE
                    ? ""
                    : context.runtime().snapshot().languages()
                            .text("placeholder.expires.permanent");
        }
        Duration remaining = Duration.between(Instant.now(),
                title.expiresAt());
        if (remaining.isNegative() || remaining.isZero()) {
            return "";
        }
        long days = remaining.toDays();
        if (days > 0) {
            return context.runtime().snapshot().languages()
                    .text("placeholder.expires.days")
                    .replace("%0%", Long.toString(days));
        }
        long hours = remaining.toHours();
        if (hours > 0) {
            return context.runtime().snapshot().languages()
                    .text("placeholder.expires.hours")
                    .replace("%0%", Long.toString(hours));
        }
        long minutes = Math.max(1, remaining.toMinutes());
        return context.runtime().snapshot().languages()
                .text("placeholder.expires.minutes")
                .replace("%0%", Long.toString(minutes));
    }

    @FunctionalInterface
    private interface ComponentResolver {
        Component resolve(ServerPlayer player, ResolvedTitle title);
    }

    @FunctionalInterface
    private interface StringResolver {
        String resolve(ServerPlayer player, ResolvedTitle title);
    }
}