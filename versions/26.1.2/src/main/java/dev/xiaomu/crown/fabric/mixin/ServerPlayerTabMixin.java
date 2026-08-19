package dev.xiaomu.crown.fabric.mixin;

import dev.xiaomu.crown.fabric.display.CrownTabDisplay;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerTabMixin {
    @Inject(method = "getTabListDisplayName()Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"), cancellable = true)
    private void crown$decorateTabName(
            CallbackInfoReturnable<Component> callback
    ) {
        callback.setReturnValue(CrownTabDisplay.decorateName(
                (ServerPlayer) (Object) this, callback.getReturnValue()));
    }
}