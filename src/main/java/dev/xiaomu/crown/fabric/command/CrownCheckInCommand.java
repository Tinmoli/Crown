package dev.xiaomu.crown.fabric.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.runtime.checkin.DailyCheckInService;
import dev.xiaomu.crown.runtime.platform.PermissionSource;
import dev.xiaomu.crown.storage.model.CheckInResult;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;

import java.util.UUID;

/** Command permissions and localized feedback for daily check-in. */
public final class CrownCheckInCommand {
    public static final String PERMISSION = "crown.command.checkin";
    public static final String ADMIN_PERMISSION = "crown.admin.checkin";

    private CrownCheckInCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> create(CrownServerContext context) {
        return Commands.literal("checkin")
                .requires(
                        source ->
                                canCheckIn(context, source) || canCheckInForOthers(context, source))
                .executes(
                        command -> {
                            var source = command.getSource();
                            if (!canCheckIn(context, source)) {
                                source.sendFailure(
                                        context.messages().render("command.no-permission"));
                                return 0;
                            }
                            var player = source.getPlayer();
                            if (player == null) {
                                source.sendFailure(
                                        context.messages().render("command.player-only"));
                                return 0;
                            }
                            return execute(
                                    context,
                                    source,
                                    player.getUUID(),
                                    player.getGameProfile().name());
                        })
                .then(
                        Commands.argument("player", GameProfileArgument.gameProfile())
                                .requires(source -> canCheckInForOthers(context, source))
                                .executes(
                                        command -> {
                                            var profiles =
                                                    GameProfileArgument.getGameProfiles(
                                                            command, "player");
                                            if (profiles.size() != 1) {
                                                command.getSource()
                                                        .sendFailure(
                                                                context.messages()
                                                                        .render(
                                                                                "check-in.single-player"));
                                                return 0;
                                            }
                                            var profile = profiles.iterator().next();
                                            return execute(
                                                    context,
                                                    command.getSource(),
                                                    profile.id(),
                                                    profile.name());
                                        }));
    }

    private static boolean canCheckIn(CrownServerContext context, CommandSourceStack source) {
        return context.permissions().checkSource(PermissionSource.of(source), PERMISSION, 0);
    }

    private static boolean canCheckInForOthers(
            CrownServerContext context, CommandSourceStack source) {
        return context.permissions().checkSource(PermissionSource.of(source), ADMIN_PERMISSION, 3);
    }

    private static int execute(
            CrownServerContext context,
            CommandSourceStack source,
            UUID playerId,
            String playerName) {
        if (!context.core().checkIn().enabled()) {
            source.sendFailure(context.messages().render("check-in.disabled"));
            return 0;
        }

        var attempt = DailyCheckInService.claim(context.runtime(), playerId, playerName);
        context.mainThread()
                .whenComplete(
                        attempt,
                        result -> sendResult(context, source, playerId, playerName, result),
                        failure ->
                                source.sendFailure(
                                        context.messages().render("purchase.failed.storage")));
        return 1;
    }

    private static void sendResult(
            CrownServerContext context,
            CommandSourceStack source,
            UUID playerId,
            String playerName,
            CheckInResult result) {
        String messageKey =
                switch (result.status()) {
                    case ALREADY_CLAIMED -> "check-in.already-claimed";
                    case BALANCE_LIMIT -> "check-in.balance-limit";
                    case CLAIMED ->
                            result.reward() == 0 ? "check-in.no-reward" : "check-in.claimed";
                };
        context.runtime().playerTitleCache().load(playerId, playerName);
        source.sendSuccess(
                () ->
                        context.messages()
                                .render(messageKey, Long.toString(result.reward()), playerName),
                false);
    }
}
