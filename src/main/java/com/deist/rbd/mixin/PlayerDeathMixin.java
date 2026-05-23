package com.deist.rbd.mixin;

import com.deist.rbd.checkpoint.CheckpointData;
import com.deist.rbd.checkpoint.CheckpointManager;
import com.deist.rbd.miasma.MiasmaManager;
import com.deist.rbd.rollback.RollbackExecutor;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public class PlayerDeathMixin {

    @Inject(method = "onDeath", at = @At("HEAD"), cancellable = true)
    private void onPlayerDeath(DamageSource damageSource, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity)(Object) this;
        ServerWorld world = player.getServerWorld();

        CheckpointData cp = CheckpointManager.load(world);
        if (cp == null) return; // no checkpoint → vanilla death

        // ── 1. Increment miasma ────────────────────────────────────────────
        int newMiasmaLevel = MiasmaManager.get(player.server).onDeath(player.getUuid());



        // ── 3. Trigger rollback ────────────────────────────────────────────
        RollbackExecutor.execute(player, world, newMiasmaLevel);

        // Cancel vanilla death (no item drops, no respawn screen)
        player.setHealth(player.getMaxHealth());
        ci.cancel();
    }
}
