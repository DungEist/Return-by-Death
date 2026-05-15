package com.deist.rbd.mixin;

import com.deist.rbd.core.RbdStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * With the new EntitySnapshot system we no longer need to track SPAWNED/KILLED
 * deltas for entities. This mixin only needs to suppress spawns during rollback
 * (when EntitySnapshot.restore() is discarding and re-spawning entities).
 *
 * Removed: snapshotUuids whitelist, recordSpawned() calls, boss special-casing.
 */
@Mixin(ServerWorld.class)
public class EntitySpawnMixin {

    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void onSpawnEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        // During rollback, EntitySnapshot.restore() manages spawning directly.
        // Block any other spawn triggers that might fire concurrently
        // (e.g. block update side effects, mob AI reactions).
        if (RbdStateManager.isRollingBack()) {
            // Only allow spawns that EntitySnapshot.restore() itself initiates.
            // Those calls are already inside setRollingBack(true) guard in RollbackExecutor,
            // but restore() sets rollingBack=true before spawning, then false after.
            // So we should NOT cancel here — restore() needs to spawn freely.
            // We only want to block external triggers. Since restore() calls
            // world.spawnEntity() inside the setRollingBack(true) block and then
            // immediately sets it back to false in the finally block after all spawns,
            // any spawn that reaches here with isRollingBack()==true is an unwanted
            // side-effect spawn (e.g. mob spawner ticking, block updates) → cancel it.
            if (!(entity instanceof PlayerEntity)) {
                cir.cancel();
            }
        }
    }
}
