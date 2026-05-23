package com.deist.rbd.core;

import com.deist.rbd.checkpoint.CheckpointManager;
import com.deist.rbd.log.RbdChangeLog;
import com.deist.rbd.miasma.MiasmaManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static net.minecraft.server.command.CommandManager.literal;

public class RbdMod implements ModInitializer {

    public static final String MOD_ID = "rbd";

    @Override
    public void onInitialize() {
        System.out.println("Initializing Return by Death Mod (Phase 3)");
        RbdConfig.load();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            if (CheckpointManager.load(player.getServerWorld()) == null) {
                scheduleTask(20, () -> {
                    if (CheckpointManager.load(player.getServerWorld()) == null) {
                        CheckpointManager.save(player, player.getServerWorld());
                        System.out.println("[RbD] Set default world spawn checkpoint.");
                    }
                });
            }
            scheduleTask(20, () -> {
                sendMiasmaSync(player, server);
            });
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("rbd")
                .then(literal("setcheckpoint").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player != null) {
                        CheckpointManager.save(player, player.getServerWorld());
                        player.sendMessage(Text.literal("Checkpoint saved!"), false);
                    }
                    return 1;
                }))
                .then(literal("clearcheckpoint").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player != null) {
                        CheckpointManager.clear(player.getServerWorld());
                        player.sendMessage(Text.literal("Checkpoint cleared!"), false);
                    }
                    return 1;
                }))
                .then(literal("miasma").then(literal("level").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player != null) {
                        int level = MiasmaManager.get(player.server).getMiasmaLevel(player.getUuid());
                        int deaths = MiasmaManager.get(player.server).getTotalDeaths(player.getUuid());
                        player.sendMessage(
                            Text.literal("Miasma Lv." + level + " | Total deaths: " + deaths), false);
                    }
                    return 1;
                })))
            );
        });

        // ENTITY_LOAD: apply Miasma follow-range modifier + special scent behaviors
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!(entity instanceof net.minecraft.entity.mob.MobEntity)) return;
            if (!(world instanceof ServerWorld)) return;
            net.minecraft.entity.mob.MobEntity mob = (net.minecraft.entity.mob.MobEntity) entity;
            ServerWorld sw = (ServerWorld) world;

            sw.getPlayers().stream()
                .filter(p -> p instanceof ServerPlayerEntity)
                .map(p -> (ServerPlayerEntity) p)
                .filter(p -> p.squaredDistanceTo(mob) <= 128 * 128)
                .max(java.util.Comparator.comparingInt(
                    p -> MiasmaManager.get(sw.getServer()).getMiasmaLevel(p.getUuid())))
                .ifPresent(player -> {
                    int level = MiasmaManager.get(sw.getServer()).getMiasmaLevel(player.getUuid());
                    int bonus = MiasmaManager.getDetectionBonus(level);
                    if (bonus > 0) {
                        var followRange = mob.getAttributeInstance(
                            net.minecraft.entity.attribute.EntityAttributes.GENERIC_FOLLOW_RANGE);
                        if (followRange != null) {
                            java.util.UUID modId = java.util.UUID.fromString(
                                "a3d4e5f6-1234-5678-9abc-def012345678");
                            followRange.removeModifier(modId);
                            followRange.addTemporaryModifier(
                                new net.minecraft.entity.attribute.EntityAttributeModifier(
                                    modId, "Miasma Detection Bonus", bonus,
                                    net.minecraft.entity.attribute.EntityAttributeModifier.Operation.ADDITION));
                        }
                    }
                    if (level >= 2 && com.deist.rbd.miasma.ScentBehaviorRegistry.isRegistered(mob.getType())) {
                        com.deist.rbd.miasma.ScentBehaviorRegistry.applyIfRegistered(mob, player);
                    }
                });
        });

        // ENTITY_LOAD: dynamic capture of loaded pre-existing entities (to fix Bug 3)
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!(world instanceof ServerWorld)) return;
            if (world.getRegistryKey() == net.minecraft.world.World.END) return; // Skip The End (handled by EndSnapshot)

            // Skip players, items, XP, projectiles (similar to EntitySnapshot.shouldSkip)
            if (entity instanceof net.minecraft.entity.player.PlayerEntity) return;
            if (entity instanceof net.minecraft.entity.ItemEntity
                    || entity instanceof net.minecraft.entity.ExperienceOrbEntity
                    || entity instanceof net.minecraft.entity.projectile.ProjectileEntity) return;

            if (com.deist.rbd.core.RbdStateManager.isRollingBack()) return;

            com.deist.rbd.checkpoint.EntitySnapshot snap = CheckpointManager.getCurrentEntitySnapshot();
            if (snap != null && !snap.containsUuid(entity.getUuid())
                    && !com.deist.rbd.checkpoint.EntitySnapshot.wasSpawnedAfterCheckpoint(entity.getUuid())) {
                snap.addEntity(entity);
                System.out.println("[RbD] Dynamically captured loaded pre-existing entity: " + entity.getType() + " (" + entity.getUuid() + ")");
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            CheckpointManager.clearInMemory();
            synchronized (pendingTasks) { pendingTasks.clear(); }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Scheduled tasks
            synchronized (pendingTasks) {
                Iterator<ScheduledTask> it = pendingTasks.iterator();
                while (it.hasNext()) {
                    ScheduledTask task = it.next();
                    task.ticks--;
                    if (task.ticks <= 0) {
                        task.runnable.run();
                        it.remove();
                    }
                }
            }

            // Miasma decay (once per second) + sync
            if (server.getTicks() % 20 == 0) {
                server.getPlayerManager().getPlayerList().forEach(player -> {
                    long absoluteDay = player.getServerWorld().getTime() / 24000L;
                    boolean changed = MiasmaManager.get(server).tickDecay(player, absoluteDay);
                    if (changed) sendMiasmaSync(player, server);
                });
            }

            // Periodic miasma overlay sync (every 5 seconds)
            if (server.getTicks() % 100 == 0) {
                server.getPlayerManager().getPlayerList().forEach(p -> sendMiasmaSync(p, server));
            }

            // Per-tick miasma behaviors (every 40 ticks)
            if (server.getTicks() % 40 == 0) {
                server.getPlayerManager().getPlayerList().forEach(player -> {
                    int level = MiasmaManager.get(server).getMiasmaLevel(player.getUuid());
                    if (level < 1) return;

                    if (player.hasVehicle() && player.getVehicle() != null) {
                        player.getVehicle().removeAllPassengers();
                    }
                    if (level < 2) return;

                    ServerWorld sw = player.getServerWorld();
                    int bonus = MiasmaManager.getDetectionBonus(level);
                    java.util.UUID modId = java.util.UUID.fromString("a3d4e5f6-1234-5678-9abc-def012345678");
                    sw.getEntitiesByClass(net.minecraft.entity.mob.MobEntity.class,
                        new net.minecraft.util.math.Box(
                            player.getX() - 64, player.getY() - 20, player.getZ() - 64,
                            player.getX() + 64, player.getY() + 20, player.getZ() + 64),
                        mob -> true
                    ).forEach(mob -> {
                        if (bonus > 0) {
                            var fr = mob.getAttributeInstance(
                                net.minecraft.entity.attribute.EntityAttributes.GENERIC_FOLLOW_RANGE);
                            if (fr != null) {
                                fr.removeModifier(modId);
                                fr.addTemporaryModifier(
                                    new net.minecraft.entity.attribute.EntityAttributeModifier(
                                        modId, "Miasma Detection Bonus", bonus,
                                        net.minecraft.entity.attribute.EntityAttributeModifier.Operation.ADDITION));
                            }
                        }
                        if (com.deist.rbd.miasma.ScentBehaviorRegistry.isRegistered(mob.getType())) {
                            com.deist.rbd.miasma.ScentBehaviorRegistry.applyIfRegistered(mob, player);
                        } else if (mob.getTarget() == null) {
                            mob.setTarget(player);
                        }
                        if (mob instanceof net.minecraft.entity.mob.Angerable angerable) {
                            if (angerable.getAngryAt() == null) {
                                angerable.setAngerTime(400);
                                angerable.setAngryAt(player.getUuid());
                                mob.setTarget(player);
                            }
                        }
                    });
                });
            }
        });
    }

    private static final List<ScheduledTask> pendingTasks = new ArrayList<>();

    public static void scheduleTask(int ticks, Runnable runnable) {
        synchronized (pendingTasks) {
            pendingTasks.add(new ScheduledTask(ticks, runnable));
        }
    }

    public static void sendMiasmaSync(ServerPlayerEntity player, net.minecraft.server.MinecraftServer server) {
        int level    = MiasmaManager.get(server).getMiasmaLevel(player.getUuid());
        int maxLevel = RbdConfig.get().maxMiasmaLevel;
        net.minecraft.network.PacketByteBuf buf =
            net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        buf.writeInt(level);
        buf.writeInt(maxLevel);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
            com.deist.rbd.effects.RbdNetworking.MIASMA_SYNC_ID, buf);
    }

    private static class ScheduledTask {
        int ticks;
        final Runnable runnable;
        ScheduledTask(int ticks, Runnable runnable) {
            this.ticks = ticks;
            this.runnable = runnable;
        }
    }
}
