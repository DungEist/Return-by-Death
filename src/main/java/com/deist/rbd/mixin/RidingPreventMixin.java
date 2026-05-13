package com.deist.rbd.mixin;

import com.deist.rbd.miasma.MiasmaManager;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents players with Miasma >= 1 from mounting any entity.
 * Animals flee or reject the miasma-tainted player.
 */
@Mixin(Entity.class)
public class RidingPreventMixin {

    @Inject(method = "startRiding(Lnet/minecraft/entity/Entity;Z)Z", at = @At("HEAD"), cancellable = true)
    private void preventMiasmaRiding(Entity vehicle, boolean force, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity)(Object) this;
        if (!(self instanceof ServerPlayerEntity player)) return;
        if (player.server == null) return;

        int level = MiasmaManager.get(player.server).getMiasmaLevel(player.getUuid());
        if (level >= 1) {
            cir.setReturnValue(false); // reject mount attempt
        }
    }
}
