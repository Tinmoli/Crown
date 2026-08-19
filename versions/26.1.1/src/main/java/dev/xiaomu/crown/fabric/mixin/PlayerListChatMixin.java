package dev.xiaomu.crown.fabric.mixin;

import dev.xiaomu.crown.fabric.display.CrownChatDisplay;
import net.minecraft.network.chat.ChatType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Adds Crown's title to the rendered sender name without changing signed text. */
@Mixin(PlayerList.class)
abstract class PlayerListChatMixin {
    @ModifyArgs(
            method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V"))
    private void crown$decorateSignedChatName(Args arguments) {
        ServerPlayer sender = arguments.get(2);
        ChatType.Bound type = arguments.get(3);
        arguments.set(3, new ChatType.Bound(type.chatType(),
                CrownChatDisplay.decorateName(sender, type.name()),
                type.targetName()));
    }

}