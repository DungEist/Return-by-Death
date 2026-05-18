package com.deist.rbd.mixin;

import com.deist.rbd.checkpoint.EntitySnapshot;
import com.deist.rbd.core.RbdStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Track mọi entity spawn sau khi checkpoint được set.
 *
 * Mục đích: phân biệt entity "spawned sau checkpoint" (cần discard khi rollback)
 * vs entity "ở unloaded chunk lúc capture, chunk reload nên xuất hiện lại"
 * (đã có trong disk snapshot, không cần track).
 *
 * Không track: Player, Item, XP, Projectile (đã xử lý riêng).
 * Không track: spawn trong The End (xử lý bởi EndSnapshot).
 * Không track: spawn trong lúc rollback đang chạy.
 */
@Mixin(ServerWorld.class)
public class EntitySpawnMixin {

    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void onSpawnEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerWorld world = (ServerWorld)(Object) this;

        // Không làm gì trong lúc rollback
        if (RbdStateManager.isRollingBack()) return;

        // Skip The End — xử lý bởi EndSnapshot
        if (world.getRegistryKey() == World.END) return;

        // Skip entity loại không cần track
        if (entity instanceof PlayerEntity) return;
        if (entity instanceof ItemEntity) return;
        if (entity instanceof ExperienceOrbEntity) return;
        if (entity instanceof ProjectileEntity) return;

        // Ghi nhận UUID này là "spawned sau checkpoint"
        EntitySnapshot.recordSpawned(entity.getUuid());
    }
}
