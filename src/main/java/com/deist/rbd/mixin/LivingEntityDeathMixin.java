package com.deist.rbd.mixin;

import com.deist.rbd.core.RbdStateManager;
import com.deist.rbd.log.RbdEntityLog;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class LivingEntityDeathMixin {

    @Inject(method = "onDeath", at = @At("HEAD"))
    private void onDeath(DamageSource source, CallbackInfo ci) {
        if (RbdStateManager.isRollingBack()) return;

        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof PlayerEntity) return;
        if (!(self.getWorld() instanceof ServerWorld)) return;
        ServerWorld world = (ServerWorld) self.getWorld();

        NbtCompound nbt = new NbtCompound();
        self.writeNbt(nbt);
        nbt.putString("id", EntityType.getId(self.getType()).toString());
        // CRITICAL: Entity health is 0 at death time. If we save that,
        // the respawned entity will immediately die again.
        // Override with max health so it spawns alive.
        nbt.putFloat("Health", self.getMaxHealth());
        RbdEntityLog.get(world).recordKilled(self.getUuid(), nbt, world.getTime());
    }
}
