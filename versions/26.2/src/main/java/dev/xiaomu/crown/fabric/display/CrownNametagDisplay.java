package dev.xiaomu.crown.fabric.display;

import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.config.model.DisplayMode;
import dev.xiaomu.crown.domain.text.CrownTextParser;
import dev.xiaomu.crown.fabric.CrownServerContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Objects;

/**
 * 使用 Crown 自有 scoreboard team 刷新头顶名称。
 *
 * <p>绝不覆盖其他模组或插件创建的队伍：玩家已加入非 {@code crown_}
 * 队伍时跳过；关闭功能后仅移除 Crown 自己创建的队伍。</p>
 */
public final class CrownNametagDisplay {
    private static final String TEAM_PREFIX = "crown_";

    private CrownNametagDisplay() {
    }

    /** 在服务端主线程调用，按当前缓存和配置刷新所有在线玩家。 */
    public static void refresh(CrownServerContext context) {
        Objects.requireNonNull(context, "context");
        for (ServerPlayer player : context.server().getPlayerList().getPlayers()) {
            refreshPlayer(context, player);
        }
    }

    /** 刷新单个在线玩家，供登录预热和称号变更完成后立即调用。 */
    public static void refreshPlayer(
            CrownServerContext context,
            ServerPlayer player
    ) {
        // 使用服务端主 scoreboard 与 GameProfile 名称，避免各 Minecraft
        // 版本在 ServerPlayer#level/serverLevel、getScoreboardName 上的变动。
        Scoreboard scoreboard = context.server().getScoreboard();
        String playerName = player.getGameProfile().name();
        PlayerTeam current = scoreboard.getPlayersTeam(playerName);
        String ownTeamName = teamName(player);

        // TAB 是独立的原版 PlayerInfo 展示；不能因头顶名称关闭而跳过。
        CrownTabDisplay.refreshPlayer(context, player);

        if (context.core().display().nametagMode() != DisplayMode.VANILLA) {
            removeOwnTeam(scoreboard, current, playerName, ownTeamName);
            return;
        }

        // 其他系统已经占用队伍时安全跳过，绝不破坏其前后缀或成员关系。
        if (current != null && !current.getName().equals(ownTeamName)) {
            return;
        }

        PlayerTeam team = current;
        if (team == null) {
            team = scoreboard.getPlayerTeam(ownTeamName);
            if (team == null) {
                team = scoreboard.addPlayerTeam(ownTeamName);
            } else if (!team.getPlayers().isEmpty()
                    && !team.getPlayers().contains(playerName)) {
                // UUID 截断后的极低概率名称冲突不能让 Crown 接管另一个
                // 玩家正在使用的 team；跳过本次显示比破坏其名称更安全。
                return;
            }
            scoreboard.addPlayerToTeam(playerName, team);
        }

        TemplateParts parts = parts(context, player);
        team.setPlayerPrefix(parts.prefix());
        team.setPlayerSuffix(parts.suffix());
    }

    /** 玩家断开连接时立即回收其 Crown 专属 team。 */
    public static void removePlayer(
            CrownServerContext context,
            ServerPlayer player
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(player, "player");
        Scoreboard scoreboard = context.server().getScoreboard();
        String playerName = player.getGameProfile().name();
        removeOwnTeam(scoreboard, scoreboard.getPlayersTeam(playerName),
                playerName, teamName(player));
        CrownTabDisplay.removePlayer(player);
    }

    private static void removeOwnTeam(
            Scoreboard scoreboard,
            PlayerTeam current,
            String playerName,
            String ownTeamName
    ) {
        if (current == null || !current.getName().equals(ownTeamName)) {
            return;
        }
        scoreboard.removePlayerFromTeam(playerName, current);
        if (current.getPlayers().isEmpty()) {
            scoreboard.removePlayerTeam(current);
        }
    }

    private static TemplateParts parts(
            CrownServerContext context,
            ServerPlayer player
    ) {
        CoreSettings.DisplayTemplate display =
                context.core().display().nametag();
        String template = display.template();
        int nameMarker = template.indexOf("{player}");
        if (nameMarker < 0) {
            // 配置模型已验证该变量；这里仍避免热重载期间的异常破坏团队。
            return new TemplateParts(Component.empty(), Component.empty());
        }
        Component title = context.textAdapter().component(context.runtime()
                .playerTitleCache().get(player.getUUID()).title().fullText());
        String prefixSource = template.substring(0, nameMarker);
        String suffixSource = template.substring(nameMarker + "{player}".length());
        CrownTextParser parser = new CrownTextParser(
                context.core().safety().serverTextPolicy());
        return new TemplateParts(
                templatePart(context, parser, prefixSource, title),
                templatePart(context, parser, suffixSource, title));
    }

    /** 保留称号 StyledText 的原始颜色和格式，不把它降级为纯文本。 */
    private static Component templatePart(
            CrownServerContext context,
            CrownTextParser parser,
            String source,
            Component title
    ) {
        MutableComponent result = Component.empty();
        int start = 0;
        int marker;
        while ((marker = source.indexOf("{title}", start)) >= 0) {
            result.append(context.textAdapter().component(parser.parse(
                    source.substring(start, marker))));
            result.append(title.copy());
            start = marker + "{title}".length();
        }
        result.append(context.textAdapter().component(parser.parse(
                source.substring(start))));
        return result;
    }

    private static String teamName(ServerPlayer player) {
        return TEAM_PREFIX + player.getUUID().toString().replace("-", "")
                .substring(0, 10);
    }

    /**
     * 在停服时或关闭配置后清理 Crown 自己创建的无成员 team。不会触及不带
     * Crown 前缀的外部 team。
     */
    public static void cleanup(CrownServerContext context) {
        Objects.requireNonNull(context, "context");
        Scoreboard scoreboard = context.server().getScoreboard();
        for (PlayerTeam team : new ArrayList<>(scoreboard.getPlayerTeams())) {
            if (!team.getName().startsWith(TEAM_PREFIX)) {
                continue;
            }
            for (String playerName : new ArrayList<>(team.getPlayers())) {
                scoreboard.removePlayerFromTeam(playerName, team);
            }
            if (team.getPlayers().isEmpty()) {
                scoreboard.removePlayerTeam(team);
            }
        }
    }

    private record TemplateParts(Component prefix, Component suffix) {
    }
}