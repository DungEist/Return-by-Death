package com.deist.rbd.mixin;

import com.deist.rbd.core.RbdStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.event.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "emitGameEvent(Lnet/minecraft/world/event/GameEvent;Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void onEmitGameEvent(GameEvent event, Entity entity, CallbackInfo ci) {
        if ((Object) this instanceof PlayerEntity player) {
            if (RbdStateManager.isWaitingForRollback(player.getUuid())) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "emitGameEvent(Lnet/minecraft/world/event/GameEvent;)V", at = @At("HEAD"), cancellable = true)
    private void onEmitGameEventNoEntity(GameEvent event, CallbackInfo ci) {
        if ((Object) this instanceof PlayerEntity player) {
            if (RbdStateManager.isWaitingForRollback(player.getUuid())) {
                ci.cancel();
            }
        }
    }
}
