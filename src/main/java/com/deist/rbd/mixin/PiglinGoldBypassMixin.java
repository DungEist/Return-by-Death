package com.deist.rbd.mixin;

import com.deist.rbd.miasma.MiasmaManager;
import net.minecraft.entity.mob.PiglinEntity;

import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bypasses Piglin gold-armor immunity when the target player has Miasma >= 2.
 * PiglinBrain.isImmuneToAnger() normally returns true if the player wears any gold armor,
 * preventing Piglins from becoming or staying angry. This Mixin overrides that.
 */
@Mixin(PiglinEntity.class)
public class PiglinGoldBypassMixin {

    @Inject(method = "isImmuneToAnger", at = @At("RETURN"), cancellable = true)
    private void bypassGoldImmunity(CallbackInfoReturnable<Boolean> cir) {
        // Only proceed if the result was "immune" (true) — nothing to do otherwise
        if (!cir.getReturnValue()) return;

        PiglinEntity self = (PiglinEntity)(Object) this;
        if (!(self.getTarget() instanceof ServerPlayerEntity)) return;
        if (!(self.getWorld() instanceof net.minecraft.server.world.ServerWorld)) return;
        ServerPlayerEntity player = (ServerPlayerEntity) self.getTarget();
        net.minecraft.server.world.ServerWorld sw = (net.minecraft.server.world.ServerWorld) self.getWorld();

        int level = MiasmaManager.get(sw.getServer()).getMiasmaLevel(player.getUuid());
        if (level >= 2) {
            cir.setReturnValue(false);
        }
    }
}
