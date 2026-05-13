package com.deist.rbd.mixin;

import com.deist.rbd.core.RbdStateManager;
import com.deist.rbd.log.RbdEntityLog;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts Entity.remove() to record non-living entity removals (End Crystals, Armor Stands, etc.)
 * LivingEntity deaths are handled by LivingEntityDeathMixin.
 */
@Mixin(Entity.class)
public class EntityRemoveMixin {

    @Inject(method = "remove", at = @At("HEAD"))
    private void onRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        if (RbdStateManager.isRollingBack()) return;
        // Only care about entities actually destroyed (not teleported, changed dimensions, etc.)
        if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) return;

        Entity self = (Entity)(Object) this;
        if (self instanceof LivingEntity) return;   // handled by LivingEntityDeathMixin
        if (self instanceof PlayerEntity) return;
        if (self instanceof ProjectileEntity) return;
        if (self instanceof net.minecraft.entity.ItemEntity) return;
        if (self instanceof net.minecraft.entity.ExperienceOrbEntity) return;
        if (!(self.getWorld() instanceof ServerWorld)) return;
        ServerWorld world = (ServerWorld) self.getWorld();

        NbtCompound nbt = new NbtCompound();
        self.writeNbt(nbt);
        nbt.putString("id", EntityType.getId(self.getType()).toString());
        RbdEntityLog.get(world).recordKilled(self.getUuid(), nbt, world.getTime());
    }
}
