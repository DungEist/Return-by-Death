package com.deist.rbd.miasma;

import com.deist.rbd.core.RbdConfig;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PiglinBruteEntity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.entity.mob.VindicatorEntity;
import net.minecraft.entity.passive.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Registry for special mob behaviours triggered by Miasma Lv 2+.
 * Called once per mob entity on ENTITY_LOAD event.
 */
public class ScentBehaviorRegistry {

    @FunctionalInterface
    public interface ScentBehavior {
        void apply(MobEntity mob, ServerPlayerEntity player);
    }

    private static final Map<EntityType<?>, ScentBehavior> BEHAVIORS = new HashMap<>();

    static {
        // Wolf: tamed → ignore, wild → attack
        BEHAVIORS.put(EntityType.WOLF, (mob, player) -> {
            WolfEntity wolf = (WolfEntity) mob;
            if (wolf.isTamed() && wolf.getOwnerUuid() != null
                    && wolf.getOwnerUuid().equals(player.getUuid())) {
                wolf.setTarget(null);
                wolf.setAttacking(false);
            } else {
                wolf.setTarget(player);
            }
        });

        // Bee: aggro the whole local swarm
        BEHAVIORS.put(EntityType.BEE, (mob, player) -> {
            if (!(mob.getWorld() instanceof ServerWorld)) return;
            ServerWorld sw = (ServerWorld) mob.getWorld();
            sw.getEntitiesByType(EntityType.BEE,
                Box.of(mob.getPos(), 40, 10, 40),
                bee -> !bee.hasAngerTime()
            ).forEach(bee -> bee.setTarget(player));
        });

        // Enderman: aggro without eye contact requirement
        BEHAVIORS.put(EntityType.ENDERMAN, (mob, player) -> mob.setTarget(player));

        // Piglin: aggro even when player wears gold (use Angerable anger to override immunity)
        BEHAVIORS.put(EntityType.PIGLIN, (mob, player) -> {
            if (mob instanceof net.minecraft.entity.mob.Angerable a) {
                a.setAngerTime(400);
                a.setAngryAt(player.getUuid());
            }
            mob.setTarget(player);
        });
        BEHAVIORS.put(EntityType.PIGLIN_BRUTE, (mob, player) -> {
            if (mob instanceof net.minecraft.entity.mob.Angerable a) {
                a.setAngerTime(400);
                a.setAngryAt(player.getUuid());
            }
            mob.setTarget(player);
        });

        // Horse / Donkey / Mule: eject player (they'll reject riding via per-tick eject in RbdMod)
        BEHAVIORS.put(EntityType.HORSE, (mob, player) -> mob.removeAllPassengers());
        BEHAVIORS.put(EntityType.DONKEY, (mob, player) -> mob.removeAllPassengers());
        BEHAVIORS.put(EntityType.MULE, (mob, player) -> mob.removeAllPassengers());
        BEHAVIORS.put(EntityType.PIG, (mob, player) -> mob.removeAllPassengers());

        // Zombified Piglin: trigger whole nearby group
        BEHAVIORS.put(EntityType.ZOMBIFIED_PIGLIN, (mob, player) -> {
            if (!(mob.getWorld() instanceof ServerWorld)) return;
            ServerWorld sw = (ServerWorld) mob.getWorld();
            sw.getEntitiesByType(EntityType.ZOMBIFIED_PIGLIN,
                Box.of(mob.getPos(), 64, 20, 64),
                z -> z.getTarget() == null
            ).forEach(z -> z.setTarget(player));
        });

        // Polar Bear: aggro (normally passive unless cub nearby)
        BEHAVIORS.put(EntityType.POLAR_BEAR, (mob, player) -> mob.setTarget(player));

        // Iron Golem: treat player as a threat
        BEHAVIORS.put(EntityType.IRON_GOLEM, (mob, player) -> mob.setTarget(player));

        // Llama + Trader Llama: spit
        BEHAVIORS.put(EntityType.LLAMA, (mob, player) -> mob.setTarget(player));
        BEHAVIORS.put(EntityType.TRADER_LLAMA, (mob, player) -> mob.setTarget(player));

        // Dolphin: flee
        BEHAVIORS.put(EntityType.DOLPHIN, (mob, player) -> {
            mob.setTarget(null);
            mob.setVelocity(mob.getPos().subtract(player.getPos()).normalize().multiply(0.4, 0.15, 0.4));
        });

        // Camel: throw player off (if ridden) and flee
        BEHAVIORS.put(EntityType.CAMEL, (mob, player) -> {
            if (mob.hasPassengers()) mob.removeAllPassengers();
            // Push away from player
            mob.setVelocity(mob.getPos().subtract(player.getPos()).normalize().multiply(0.5, 0.3, 0.5));
        });
    }

    /**
     * Apply special miasma behavior for the given mob if it has one registered.
     * Should only be called when miasma level >= 2.
     */
    public static void applyIfRegistered(MobEntity mob, ServerPlayerEntity player) {
        if (!RbdConfig.get().miasmaEnabled) return;
        ScentBehavior behavior = BEHAVIORS.get(mob.getType());
        if (behavior != null) behavior.apply(mob, player);
    }

    public static boolean isRegistered(EntityType<?> type) {
        return BEHAVIORS.containsKey(type);
    }
}
