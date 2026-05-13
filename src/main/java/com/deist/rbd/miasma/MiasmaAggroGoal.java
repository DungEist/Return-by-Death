package com.deist.rbd.miasma;

import com.deist.rbd.core.RbdConfig;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * AI goal injected into neutral mobs when miasma level >= 2.
 * Makes them aggro on the nearest player with enough miasma.
 */
public class MiasmaAggroGoal extends ActiveTargetGoal<ServerPlayerEntity> {

    public MiasmaAggroGoal(MobEntity mob) {
        super(mob, ServerPlayerEntity.class, true);
    }

    @Override
    public boolean canStart() {
        if (!RbdConfig.get().miasmaEnabled) return false;
        if (!(this.mob.getWorld() instanceof ServerWorld)) return false;
        ServerWorld sw = (ServerWorld) this.mob.getWorld();
        return sw.getPlayers().stream()
            .filter(p -> p instanceof ServerPlayerEntity)
            .map(p -> (ServerPlayerEntity) p)
            .anyMatch(p -> MiasmaManager.get(sw.getServer()).getMiasmaLevel(p.getUuid()) >= 2);
    }

    public boolean shouldContinue() {
        if (!(this.mob.getWorld() instanceof ServerWorld)) return false;
        ServerWorld sw = (ServerWorld) this.mob.getWorld();
        LivingEntity target = this.mob.getTarget();
        if (!(target instanceof ServerPlayerEntity player)) return false;
        return MiasmaManager.get(sw.getServer()).getMiasmaLevel(player.getUuid()) >= 2;
    }
}
