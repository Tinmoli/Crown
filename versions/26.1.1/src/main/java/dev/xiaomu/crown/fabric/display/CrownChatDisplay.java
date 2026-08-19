package dev.xiaomu.crown.fabric.display;

import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.config.model.DisplayMode;
import dev.xiaomu.crown.domain.text.CrownTextParser;
import dev.xiaomu.crown.fabric.CrownServerContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/**
 * 为原版签名聊天提供仅修改显示名的称号装饰。
 *
 * <p>Mixin 只替换 {@code ChatType.Bound} 中的 name 参数；玩家的
 * {@code PlayerChatMessage} 从不被替换、取消签名或重新广播。因此聊天签名、
 * 举报上下文和原版过滤流程保持不变。</p>
 */
public final class CrownChatDisplay {
    private static volatile CrownServerContext context;

    private CrownChatDisplay() {
    }

    public static void install(CrownServerContext serverContext) {
        context = Objects.requireNonNull(serverContext, "serverContext");
    }

    public static void clear(CrownServerContext serverContext) {
        if (context == serverContext) {
            context = null;
        }
    }

    /**
     * 由版本 Mixin 在 {@code PlayerList.broadcastChatMessage} 入口调用。
     * 原版传入的显示名可能已包含 scoreboard team 前后缀。聊天模板中的
     * {@code {player}} 必须使用原始游戏名，否则同时开启 Crown nametag 时会把
     * 同一称号叠加两次。
     */
    public static Component decorateName(
            ServerPlayer player,
            Component originalName
    ) {
        Objects.requireNonNull(originalName, "originalName");
        if (player == null) {
            return originalName;
        }
        CrownServerContext active = context;
        if (active == null || active.core().display().chatMode()
                != DisplayMode.VANILLA) {
            return originalName;
        }

        CoreSettings.DisplayTemplate display = active.core().display().chat();
        String source = nameTemplate(display.template());
        CrownTextParser parser = new CrownTextParser(
                active.core().safety().serverTextPolicy());
        Component title = active.textAdapter().component(active.runtime()
                .playerTitleCache().get(player.getUUID()).title().fullText());
        Component playerName = Component.literal(player.getGameProfile().name());
        return renderTemplate(active, parser, source, title, playerName, false);
    }

    /*
     * v1 配置把整个聊天行写成 "{title} {player}: {message}"。原版签名
     * ChatType 已经负责冒号和消息正文，这里只保留消息变量之前的名称部分。
     */
    private static String nameTemplate(String template) {
        int message = template.indexOf("{message}");
        if (message < 0) {
            return template;
        }
        String result = template.substring(0, message);
        while (!result.isEmpty()) {
            char last = result.charAt(result.length() - 1);
            if (last == ':' || Character.isWhitespace(last)) {
                result = result.substring(0, result.length() - 1);
            } else {
                break;
            }
        }
        return result;
    }

    static Component renderTemplate(
            CrownServerContext context,
            CrownTextParser parser,
            String source,
            Component title,
            Component playerName,
            boolean stripMessage
    ) {
        if (stripMessage) {
            source = nameTemplate(source);
        }
        MutableComponent result = Component.empty();
        int offset = 0;
        while (offset < source.length()) {
            int titleMarker = source.indexOf("{title}", offset);
            int playerMarker = source.indexOf("{player}", offset);
            int marker;
            String replacement;
            Component value;
            if (titleMarker >= 0
                    && (playerMarker < 0 || titleMarker < playerMarker)) {
                marker = titleMarker;
                replacement = "{title}";
                value = title;
            } else if (playerMarker >= 0) {
                marker = playerMarker;
                replacement = "{player}";
                value = playerName;
            } else {
                result.append(context.textAdapter().component(parser.parse(
                        source.substring(offset))));
                break;
            }
            result.append(context.textAdapter().component(parser.parse(
                    source.substring(offset, marker))));
            result.append(value.copy());
            offset = marker + replacement.length();
        }
        return result;
    }
}