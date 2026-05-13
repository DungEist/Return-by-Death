package com.deist.rbd.mixin;

import com.deist.rbd.core.RbdPendingDeletions;
import com.deist.rbd.core.RbdStateManager;
import com.deist.rbd.log.RbdEntityLog;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerWorld.class)
public class EntitySpawnMixin {

    @Inject(method = "spawnEntity", at = @At("HEAD"))
    private void onSpawnEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (RbdStateManager.isRollingBack()) return;

        if (entity instanceof PlayerEntity) return;
        if (entity instanceof ItemEntity) return;
        if (entity instanceof ExperienceOrbEntity) return;
        if (entity instanceof ProjectileEntity) return;

        // If this entity's UUID was already captured in the checkpoint snapshot,
        // it is a pre-existing entity being loaded from disk (chunk reload) — NOT a new spawn.
        // Do NOT log it or it will be incorrectly removed during rollback.
        if (com.deist.rbd.checkpoint.CheckpointManager.getSnapshotUuids().contains(entity.getUuid())) return;

        ServerWorld world = (ServerWorld) (Object) this;
        RbdEntityLog.get(world).recordSpawned(entity.getUuid(), world.getTime());
    }
}
