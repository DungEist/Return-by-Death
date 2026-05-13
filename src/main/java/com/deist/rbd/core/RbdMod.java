package com.deist.rbd.core;

import com.deist.rbd.checkpoint.CheckpointManager;
 import com.deist.rbd.journal.DeathJournalItem;
 import com.deist.rbd.journal.JournalManager;
 import com.deist.rbd.log.RbdChangeLog;
 import com.deist.rbd.miasma.MiasmaManager;
 import net.fabricmc.api.ModInitializer;
 import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
 import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
 import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
 import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
 import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
 import net.minecraft.entity.EntityType;
 import net.minecraft.item.Item;
 import net.minecraft.nbt.NbtCompound;
 import net.minecraft.registry.Registries;
 import net.minecraft.registry.Registry;
 import net.minecraft.server.network.ServerPlayerEntity;
 import net.minecraft.server.world.ServerWorld;
 import net.minecraft.text.Text;
 import net.minecraft.util.Identifier;

 import java.util.ArrayList;
 import java.util.Iterator;
 import java.util.List;
 import java.util.UUID;

import static net.minecraft.server.command.CommandManager.literal;

public class RbdMod implements ModInitializer {

    public static final String MOD_ID = "rbd";

    @Override
    public void onInitialize() {
        System.out.println("Initializing Return by Death Mod (Phase 3)");
        RbdConfig.load(); // Load saved config from disk (or write defaults)

        // Register Death Journal item
        DeathJournalItem.INSTANCE = Registry.register(
            Registries.ITEM,
            new Identifier(MOD_ID, "death_journal"),
            new DeathJournalItem(new Item.Settings().maxCount(1))
        );

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            // Set default checkpoint if none exists (e.g. newly created world)
            if (CheckpointManager.load(player.getServerWorld()) == null) {
                scheduleTask(20, () -> {
                    if (CheckpointManager.load(player.getServerWorld()) == null) {
                        CheckpointManager.save(player, player.getServerWorld());
                        System.out.println("Set default world spawn checkpoint for Return by Death.");
                    }
                });
            }
            // Ensure player has their Death Journal + sync miasma overlay
            scheduleTask(20, () -> {
                DeathJournalItem.ensureJournal(player);
                sendMiasmaSync(player, server);
            });
        });

        // Register custom commands
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
                .then(literal("journal").then(literal("restore").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player != null) DeathJournalItem.ensureJournal(player);
                    return 1;
                })))
                .then(literal("miasma").then(literal("level").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player != null) {
                        int level = MiasmaManager.get(player.server).getMiasmaLevel(player.getUuid());
                        int deaths = MiasmaManager.get(player.server).getTotalDeaths(player.getUuid());
                        player.sendMessage(Text.literal("Miasma Lv." + level + " | Total deaths: " + deaths), false);
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

            // Find nearest player with highest miasma (limit to 128 block radius)
            sw.getPlayers().stream()
                .filter(p -> p instanceof ServerPlayerEntity)
                .map(p -> (ServerPlayerEntity) p)
                .filter(p -> p.squaredDistanceTo(mob) <= 128 * 128)
                .max(java.util.Comparator.comparingInt(
                    p -> com.deist.rbd.miasma.MiasmaManager.get(sw.getServer()).getMiasmaLevel(p.getUuid())))
                .ifPresent(player -> {
                    int level = com.deist.rbd.miasma.MiasmaManager.get(sw.getServer()).getMiasmaLevel(player.getUuid());

                    // Apply GENERIC_FOLLOW_RANGE bonus
                    int bonus = com.deist.rbd.miasma.MiasmaManager.getDetectionBonus(level);
                    if (bonus > 0) {
                        var followRange = mob.getAttributeInstance(
                            net.minecraft.entity.attribute.EntityAttributes.GENERIC_FOLLOW_RANGE);
                        if (followRange != null) {
                            java.util.UUID modId = java.util.UUID.fromString("a3d4e5f6-1234-5678-9abc-def012345678");
                            followRange.removeModifier(modId);
                            followRange.addTemporaryModifier(new net.minecraft.entity.attribute.EntityAttributeModifier(
                                modId, "Miasma Detection Bonus", bonus,
                                net.minecraft.entity.attribute.EntityAttributeModifier.Operation.ADDITION
                            ));
                        }
                    }

                    // Apply special scent behaviors (Lv2+)
                    if (level >= 2 && com.deist.rbd.miasma.ScentBehaviorRegistry.isRegistered(mob.getType())) {
                        com.deist.rbd.miasma.ScentBehaviorRegistry.applyIfRegistered(mob, player);
                    }
                });
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            CheckpointManager.clearInMemory();
            RbdPendingDeletions.clear();
            synchronized (pendingTasks) {
                pendingTasks.clear();
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
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

            // Boss Snapshot (every 20 ticks)
            if (server.getTicks() % 20 == 0) {
                server.getWorlds().forEach(world -> {
                    // Wither
                    world.getEntitiesByType(EntityType.WITHER, e -> true).forEach(wither -> {
                        NbtCompound snap = new NbtCompound();
                        wither.writeNbt(snap);
                        snap.putString("id", "minecraft:wither");
                        com.deist.rbd.log.RbdEntityLog.get(world).updateBossSnapshot(wither.getUuid(), snap, world.getTime());
                    });
                    // Ender Dragon
                    world.getEntitiesByType(EntityType.ENDER_DRAGON, e -> true).forEach(dragon -> {
                        NbtCompound snap = new NbtCompound();
                        dragon.writeNbt(snap);
                        snap.putString("id", "minecraft:ender_dragon");
                        com.deist.rbd.log.RbdEntityLog.get(world).updateBossSnapshot(dragon.getUuid(), snap, world.getTime());
                    });
                });
            }

            // Miasma decay tick (once per second) + ejection tick
            if (server.getTicks() % 20 == 0) {
                server.getPlayerManager().getPlayerList().forEach(player -> {
                    // Use absolute world time in ticks → convert to days
                    long absoluteDay = player.getServerWorld().getTime() / 24000L;
                    boolean changed = MiasmaManager.get(server).tickDecay(player, absoluteDay);
                    if (changed) sendMiasmaSync(player, server);
                });
            }

            // Periodic miasma overlay sync (every 5 seconds) to keep clients in sync
            if (server.getTicks() % 100 == 0) {
                server.getPlayerManager().getPlayerList().forEach(p -> sendMiasmaSync(p, server));
            }

            // Per-tick miasma behaviors: eject riders from animals, refresh mob aggro (every 40 ticks)
            if (server.getTicks() % 40 == 0) {
                server.getPlayerManager().getPlayerList().forEach(player -> {
                    int level = MiasmaManager.get(server).getMiasmaLevel(player.getUuid());
                    if (level < 1) return;

                    // Eject player from any rideable entity
                    if (player.hasVehicle() && player.getVehicle() != null) {
                        player.getVehicle().removeAllPassengers();
                    }

                    if (level < 2) return;

                    // Refresh GENERIC_FOLLOW_RANGE modifier + re-aggro for nearby mobs
                    ServerWorld sw = player.getServerWorld();
                    int bonus = MiasmaManager.getDetectionBonus(level);
                    java.util.UUID modId = java.util.UUID.fromString("a3d4e5f6-1234-5678-9abc-def012345678");
                    sw.getEntitiesByClass(net.minecraft.entity.mob.MobEntity.class,
                        new net.minecraft.util.math.Box(
                            player.getX() - 64, player.getY() - 20, player.getZ() - 64,
                            player.getX() + 64, player.getY() + 20, player.getZ() + 64),
                        mob -> true
                    ).forEach(mob -> {
                        // Range modifier
                        if (bonus > 0) {
                            var fr = mob.getAttributeInstance(
                                net.minecraft.entity.attribute.EntityAttributes.GENERIC_FOLLOW_RANGE);
                            if (fr != null) {
                                fr.removeModifier(modId);
                                fr.addTemporaryModifier(new net.minecraft.entity.attribute.EntityAttributeModifier(
                                    modId, "Miasma Detection Bonus", bonus,
                                    net.minecraft.entity.attribute.EntityAttributeModifier.Operation.ADDITION));
                            }
                        }
                        // Re-aggro via scent registry (neutral mobs + special behaviors)
                        if (com.deist.rbd.miasma.ScentBehaviorRegistry.isRegistered(mob.getType())) {
                            com.deist.rbd.miasma.ScentBehaviorRegistry.applyIfRegistered(mob, player);
                        } else if (mob.getTarget() == null) {
                            // Hostile mobs: if they have no target, actively set player as target
                            // This makes them hunt from the extended range even without scent registry
                            mob.setTarget(player);
                        }
                        // Force Angerable mobs to remain angry (forgive-death fix)
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

            // Pending deletion sweep (every 5 ticks)
            if (!RbdPendingDeletions.getAll().isEmpty() && server.getTicks() % 5 == 0) {
                for (UUID uuid : RbdPendingDeletions.getAll()) {
                    server.getWorlds().forEach(world -> {
                        net.minecraft.entity.Entity found = world.getEntity(uuid);
                        if (found != null) {
                            found.discard();
                            RbdPendingDeletions.remove(uuid);
                        }
                    });
                }
            }
        });
    }

    private static final List<ScheduledTask> pendingTasks = new ArrayList<>();

    public static void scheduleTask(int ticks, Runnable runnable) {
        synchronized (pendingTasks) {
            pendingTasks.add(new ScheduledTask(ticks, runnable));
        }
    }

    /** Send miasma level to client so the overlay HUD can display correctly. */
    public static void sendMiasmaSync(ServerPlayerEntity player, net.minecraft.server.MinecraftServer server) {
        int level   = MiasmaManager.get(server).getMiasmaLevel(player.getUuid());
        int maxLevel = RbdConfig.get().maxMiasmaLevel;
        net.minecraft.network.PacketByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
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
