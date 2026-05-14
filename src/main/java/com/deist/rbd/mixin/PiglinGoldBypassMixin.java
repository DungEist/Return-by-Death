package com.deist.rbd.mixin;

import com.deist.rbd.miasma.MiasmaManager;
import net.minecraft.entity.mob.PiglinBrain;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bypasses Piglin gold-armor immunity when the target player has Miasma >= 2.
 * PiglinBrain.wearsGoldArmor() normally returns true if the player wears any gold armor,
 * preventing Piglins from becoming or staying angry. This Mixin overrides that.
 */
@Mixin(PiglinBrain.class)
public class PiglinGoldBypassMixin {

    @Inject(method = "wearsGoldArmor", at = @At("RETURN"), cancellable = true)
    private static void bypassGoldImmunity(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        // Only proceed if the result was "wearing gold" (true) — nothing to do otherwise
        if (!cir.getReturnValue()) return;

        if (!(entity instanceof ServerPlayerEntity player)) return;
        if (!(entity.getWorld() instanceof net.minecraft.server.world.ServerWorld sw)) return;

        int level = MiasmaManager.get(sw.getServer()).getMiasmaLevel(player.getUuid());
        if (level >= 2) {
            // Override: return false so Piglins treat the player as not wearing gold
            cir.setReturnValue(false);
        }
    }
}

