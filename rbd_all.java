=== src/main/java/com/deist/rbd/core/RbdConfig.java ===
package com.deist.rbd.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class RbdConfig {
    private static RbdConfig instance = new RbdConfig();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("rbd.json");

    // ── Rollback ──────────────────────────────────────────────────────────────
    public int rollbackEffectDurationTicks = 40;

    // ── Miasma — gameplay ─────────────────────────────────────────────────────
    public boolean miasmaEnabled = true;
    public int maxMiasmaLevel = 4;
    public boolean miasmaDecay = true;
    public int miasmaDecayDays = 5;       // in-game days between each −1 level

    // ── Miasma — visual ───────────────────────────────────────────────────────
    public boolean scentVisualEnabled = true;
    public int scentVisualPeakTicks  = 60;  // 3 s
    public int scentVisualTotalTicks = 160; // 8 s

    // ─────────────────────────────────────────────────────────────────────────

    public static RbdConfig get() { return instance; }

    /** Load config from disk; falls back to defaults if file is missing or corrupt. */
    public static void load() {
        File file = CONFIG_PATH.toFile();
        if (!file.exists()) {
            save(); // write defaults
            return;
        }
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            RbdConfig loaded = GSON.fromJson(reader, RbdConfig.class);
            if (loaded != null) instance = loaded;
        } catch (Exception e) {
            System.err.println("[RbD] Failed to load config, using defaults: " + e.getMessage());
        }
    }

    /** Save current config to disk. */
    public static void save() {
        try {
            File file = CONFIG_PATH.toFile();
            file.getParentFile().mkdirs();
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                GSON.toJson(instance, writer);
            }
        } catch (Exception e) {
            System.err.println("[RbD] Failed to save config: " + e.getMessage());
        }
    }
}
=== src/main/java/com/deist/rbd/core/RbdStateManager.java ===
package com.deist.rbd.core;

import com.deist.rbd.log.ChangeType;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class RbdStateManager {
    private static final ThreadLocal<Boolean> rollingBack = ThreadLocal.withInitial(() -> false);
    private static final Set<UUID> waitingForRollback = Collections.synchronizedSet(new HashSet<>());
    private static final ThreadLocal<ChangeType> currentCause = ThreadLocal.withInitial(() -> ChangeType.UNKNOWN);

    public static boolean isRollingBack() {
        return rollingBack.get();
    }

    public static void setRollingBack(boolean value) {
        rollingBack.set(value);
    }

    public static boolean isWaitingForRollback(UUID uuid) {
        return waitingForRollback.contains(uuid);
    }

    public static void setWaitingForRollback(UUID uuid, boolean value) {
        if (value) waitingForRollback.add(uuid);
        else waitingForRollback.remove(uuid);
    }

    public static ChangeType getCurrentCause() {
        return currentCause.get();
    }

    public static void setCurrentCause(ChangeType cause) {
        currentCause.set(cause);
    }
}
=== src/main/java/com/deist/rbd/core/RbdMod.java ===
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
=== src/main/java/com/deist/rbd/core/RbdPendingDeletions.java ===
package com.deist.rbd.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Stores UUIDs of entities that were spawned AFTER the last checkpoint.
 * When a chunk loads, any entity whose UUID is in this set must be discarded.
 * This handles the case where spawned entities are in unloaded chunks at rollback time.
 */
public class RbdPendingDeletions {
    private static final Set<UUID> pendingDelete = Collections.synchronizedSet(new HashSet<>());

    public static void add(UUID uuid) {
        pendingDelete.add(uuid);
    }

    public static void addAll(Set<UUID> uuids) {
        pendingDelete.addAll(uuids);
    }

    public static boolean contains(UUID uuid) {
        return pendingDelete.contains(uuid);
    }

    public static void remove(UUID uuid) {
        pendingDelete.remove(uuid);
    }

    public static void clear() {
        pendingDelete.clear();
    }

    public static Set<UUID> getAll() {
        return Collections.unmodifiableSet(new HashSet<>(pendingDelete));
    }
}
=== src/main/java/com/deist/rbd/checkpoint/CheckpointData.java ===
package com.deist.rbd.checkpoint;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class CheckpointData {
    public final BlockPos pos;
    public final float yaw;
    public final float pitch;
    public final float health;
    public final int hunger;
    public final float saturation;
    public final int experienceLevel;
    public final float experienceProgress;
    public final int sculkWarningLevel;
    public int loopNumber;
    public final long worldTime;
    public final String dimension;
    public final long timestamp;
    public final DefaultedList<ItemStack> inventory;
    public final List<NbtCompound> statusEffects;
    public final boolean raining;
    public final boolean thundering;
    public final int rainTime;
    public final int thunderTime;
    public final int clearWeatherTime;
    public final List<NbtCompound> itemEntitySnapshots;
    public final List<NbtCompound> entitySnapshots;      // ALL living entities at checkpoint
    public final List<NbtCompound> blockEntitySnapshots;  // Container block entities at checkpoint
    public final NbtCompound enderChestInventory;         // Per-player ender chest snapshot

    public CheckpointData(BlockPos pos, float yaw, float pitch, float health, int hunger, float saturation,
                          int experienceLevel, float experienceProgress, int sculkWarningLevel,
                          int loopNumber, long worldTime, String dimension, long timestamp,
                          DefaultedList<ItemStack> inventory, Collection<StatusEffectInstance> effects,
                          boolean raining, boolean thundering, int rainTime, int thunderTime, int clearWeatherTime,
                          List<NbtCompound> itemEntitySnapshots,
                          List<NbtCompound> entitySnapshots,
                          List<NbtCompound> blockEntitySnapshots,
                          NbtCompound enderChestInventory) {
        this.pos = pos; this.yaw = yaw; this.pitch = pitch;
        this.health = health; this.hunger = hunger; this.saturation = saturation;
        this.experienceLevel = experienceLevel; this.experienceProgress = experienceProgress;
        this.sculkWarningLevel = sculkWarningLevel;
        this.loopNumber = loopNumber; this.worldTime = worldTime;
        this.dimension = dimension; this.timestamp = timestamp;
        this.inventory = inventory;
        this.statusEffects = new ArrayList<>();
        for (StatusEffectInstance effect : effects) {
            NbtCompound nbt = new NbtCompound();
            effect.writeNbt(nbt);
            this.statusEffects.add(nbt);
        }
        this.raining = raining; this.thundering = thundering;
        this.rainTime = rainTime; this.thunderTime = thunderTime;
        this.clearWeatherTime = clearWeatherTime;
        this.itemEntitySnapshots = itemEntitySnapshots != null ? itemEntitySnapshots : new ArrayList<>();
        this.entitySnapshots = entitySnapshots != null ? entitySnapshots : new ArrayList<>();
        this.blockEntitySnapshots = blockEntitySnapshots != null ? blockEntitySnapshots : new ArrayList<>();
        this.enderChestInventory = enderChestInventory != null ? enderChestInventory : new NbtCompound();
    }

    private CheckpointData(BlockPos pos, float yaw, float pitch, float health, int hunger, float saturation,
                          int experienceLevel, float experienceProgress, int sculkWarningLevel,
                          int loopNumber, long worldTime, String dimension, long timestamp,
                          DefaultedList<ItemStack> inventory, List<NbtCompound> statusEffects,
                          boolean raining, boolean thundering, int rainTime, int thunderTime, int clearWeatherTime,
                          List<NbtCompound> itemEntitySnapshots,
                          List<NbtCompound> entitySnapshots,
                          List<NbtCompound> blockEntitySnapshots,
                          NbtCompound enderChestInventory) {
        this.pos = pos; this.yaw = yaw; this.pitch = pitch;
        this.health = health; this.hunger = hunger; this.saturation = saturation;
        this.experienceLevel = experienceLevel; this.experienceProgress = experienceProgress;
        this.sculkWarningLevel = sculkWarningLevel;
        this.loopNumber = loopNumber; this.worldTime = worldTime;
        this.dimension = dimension; this.timestamp = timestamp;
        this.inventory = inventory; this.statusEffects = statusEffects;
        this.raining = raining; this.thundering = thundering;
        this.rainTime = rainTime; this.thunderTime = thunderTime;
        this.clearWeatherTime = clearWeatherTime;
        this.itemEntitySnapshots = itemEntitySnapshots != null ? itemEntitySnapshots : new ArrayList<>();
        this.entitySnapshots = entitySnapshots != null ? entitySnapshots : new ArrayList<>();
        this.blockEntitySnapshots = blockEntitySnapshots != null ? blockEntitySnapshots : new ArrayList<>();
        this.enderChestInventory = enderChestInventory != null ? enderChestInventory : new NbtCompound();
    }

    public void writeNbt(NbtCompound nbt) {
        nbt.putInt("PosX", pos.getX()); nbt.putInt("PosY", pos.getY()); nbt.putInt("PosZ", pos.getZ());
        nbt.putFloat("Yaw", yaw); nbt.putFloat("Pitch", pitch);
        nbt.putFloat("Health", health); nbt.putInt("Hunger", hunger); nbt.putFloat("Saturation", saturation);
        nbt.putInt("ExperienceLevel", experienceLevel); nbt.putFloat("ExperienceProgress", experienceProgress);
        nbt.putInt("SculkWarningLevel", sculkWarningLevel);
        nbt.putInt("LoopNumber", loopNumber); nbt.putLong("WorldTime", worldTime);
        nbt.putString("Dimension", dimension); nbt.putLong("Timestamp", timestamp);

        NbtList invList = new NbtList();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.get(i);
            if (!stack.isEmpty()) {
                NbtCompound t = new NbtCompound(); t.putByte("Slot", (byte) i);
                stack.writeNbt(t); invList.add(t);
            }
        }
        nbt.put("Inventory", invList);

        nbt.put("StatusEffects", copyList(statusEffects));
        nbt.putBoolean("Raining", raining); nbt.putBoolean("Thundering", thundering);
        nbt.putInt("RainTime", rainTime); nbt.putInt("ThunderTime", thunderTime);
        nbt.putInt("ClearWeatherTime", clearWeatherTime);
        nbt.put("ItemEntities", copyList(itemEntitySnapshots));
        nbt.put("EntitySnapshots", copyList(entitySnapshots));
        nbt.put("BlockEntitySnapshots", copyList(blockEntitySnapshots));
        if (!enderChestInventory.isEmpty()) nbt.put("EnderChestInventory", enderChestInventory.copy());
    }

    private NbtList copyList(List<NbtCompound> list) {
        NbtList l = new NbtList();
        for (NbtCompound c : list) l.add(c.copy());
        return l;
    }

    private static List<NbtCompound> readList(NbtCompound nbt, String key) {
        List<NbtCompound> list = new ArrayList<>();
        NbtList l = nbt.getList(key, NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < l.size(); i++) list.add(l.getCompound(i).copy());
        return list;
    }

    public static CheckpointData readNbt(NbtCompound nbt) {
        BlockPos pos = new BlockPos(nbt.getInt("PosX"), nbt.getInt("PosY"), nbt.getInt("PosZ"));
        DefaultedList<ItemStack> inv = DefaultedList.ofSize(41, ItemStack.EMPTY);
        NbtList invList = nbt.getList("Inventory", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < invList.size(); i++) {
            NbtCompound t = invList.getCompound(i);
            int slot = t.getByte("Slot") & 255;
            if (slot >= 0 && slot < inv.size()) inv.set(slot, ItemStack.fromNbt(t));
        }
        String dim = nbt.getString("Dimension");
        if (dim.isEmpty()) dim = "minecraft:overworld";

        return new CheckpointData(pos, nbt.getFloat("Yaw"), nbt.getFloat("Pitch"),
                nbt.getFloat("Health"), nbt.getInt("Hunger"), nbt.getFloat("Saturation"),
                nbt.getInt("ExperienceLevel"), nbt.getFloat("ExperienceProgress"),
                nbt.getInt("SculkWarningLevel"),
                nbt.getInt("LoopNumber"), nbt.getLong("WorldTime"), dim, nbt.getLong("Timestamp"),
                inv, readList(nbt, "StatusEffects"),
                nbt.getBoolean("Raining"), nbt.getBoolean("Thundering"),
                nbt.getInt("RainTime"), nbt.getInt("ThunderTime"), nbt.getInt("ClearWeatherTime"),
                readList(nbt, "ItemEntities"), readList(nbt, "EntitySnapshots"),
                readList(nbt, "BlockEntitySnapshots"),
                nbt.contains("EnderChestInventory") ? nbt.getCompound("EnderChestInventory") : new NbtCompound());
    }
}
=== src/main/java/com/deist/rbd/checkpoint/CheckpointManager.java ===
package com.deist.rbd.checkpoint;

import com.deist.rbd.log.RbdChangeLog;
import com.deist.rbd.log.RbdEntityLog;
import com.deist.rbd.mixin.ChunkStorageAccessor;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.level.ServerWorldProperties;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CheckpointManager {
    private static CheckpointData currentCheckpoint = null;
    // UUIDs of all entities that existed AT checkpoint time (across all dimensions).
    // Used by EntitySpawnMixin to avoid logging chunk-reload spawns as "new spawns".
    private static final java.util.Set<java.util.UUID> snapshotUuids = new java.util.HashSet<>();

    /** Returns the UUID set of all entities captured at the last checkpoint. */
    public static java.util.Set<java.util.UUID> getSnapshotUuids() {
        return snapshotUuids;
    }

    public static void save(ServerPlayerEntity player, ServerWorld world) {
        int loopNumber = currentCheckpoint != null ? currentCheckpoint.loopNumber : 0;

        // Inventory
        DefaultedList<ItemStack> inventoryCopy = DefaultedList.ofSize(player.getInventory().size(), ItemStack.EMPTY);
        for (int i = 0; i < player.getInventory().size(); i++) {
            inventoryCopy.set(i, player.getInventory().getStack(i).copy());
        }

        // Ender chest (per-player) — uses NbtList internally
        NbtCompound enderChestNbt = new NbtCompound();
        enderChestNbt.put("Items", player.getEnderChestInventory().toNbtList());

        // Weather
        ServerWorld overworld = world.getServer().getOverworld();
        ServerWorldProperties wProps = (ServerWorldProperties) overworld.getLevelProperties();

        // Item entity snapshots
        List<NbtCompound> itemSnapshots = new ArrayList<>();
        world.getServer().getWorlds().forEach(sw -> {
            sw.getEntitiesByType(EntityType.ITEM, e -> true).forEach(item -> {
                NbtCompound nbt = new NbtCompound();
                item.writeNbt(nbt);
                nbt.putString("id", "minecraft:item");
                nbt.putString("RbdDim", sw.getRegistryKey().getValue().toString());
                itemSnapshots.add(nbt);
            });
        });

        // Entity snapshots - ALL non-player, non-projectile, non-item entities
        List<NbtCompound> entitySnapshots = new ArrayList<>();
        world.getServer().getWorlds().forEach(sw -> {
            sw.iterateEntities().forEach(entity -> {
                if (entity instanceof PlayerEntity) return;
                if (entity instanceof ProjectileEntity) return;
                if (entity instanceof net.minecraft.entity.ItemEntity) return;
                if (entity instanceof net.minecraft.entity.ExperienceOrbEntity) return;
                NbtCompound nbt = new NbtCompound();
                entity.writeNbt(nbt);
                nbt.putString("id", EntityType.getId(entity.getType()).toString());
                nbt.putString("RbdDim", sw.getRegistryKey().getValue().toString());
                entitySnapshots.add(nbt);
            });
        });

        // Block entity snapshots (containers) via chunk accessor
        List<NbtCompound> beSnapshots = new ArrayList<>();
        world.getServer().getWorlds().forEach(sw -> {
            try {
                ChunkStorageAccessor accessor = (ChunkStorageAccessor) sw.getChunkManager().threadedAnvilChunkStorage;
                for (ChunkHolder holder : accessor.invokeEntryIterator()) {
                    WorldChunk chunk = holder.getWorldChunk();
                    if (chunk != null) {
                        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                            BlockEntity be = entry.getValue();
                            if (be instanceof Inventory) {
                                NbtCompound nbt = be.createNbt();
                                nbt.putInt("RbdBeX", entry.getKey().getX());
                                nbt.putInt("RbdBeY", entry.getKey().getY());
                                nbt.putInt("RbdBeZ", entry.getKey().getZ());
                                nbt.putString("RbdBeDim", sw.getRegistryKey().getValue().toString());
                                beSnapshots.add(nbt);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        int sculkLevel = player.getSculkShriekerWarningManager().isPresent() ? 
                         player.getSculkShriekerWarningManager().get().getWarningLevel() : 0;

        currentCheckpoint = new CheckpointData(
            player.getBlockPos(), player.getYaw(), player.getPitch(),
            player.getHealth(), player.getHungerManager().getFoodLevel(),
            player.getHungerManager().getSaturationLevel(),
            player.experienceLevel, player.experienceProgress, sculkLevel,
            loopNumber,
            world.getTimeOfDay(), world.getRegistryKey().getValue().toString(),
            System.currentTimeMillis(), inventoryCopy, player.getStatusEffects(),
            wProps.isRaining(), wProps.isThundering(),
            wProps.getRainTime(), wProps.getThunderTime(), wProps.getClearWeatherTime(),
            itemSnapshots, entitySnapshots, beSnapshots, enderChestNbt
        );

        // Build the UUID whitelist of pre-checkpoint entities
        snapshotUuids.clear();
        for (NbtCompound snap : entitySnapshots) {
            if (snap.containsUuid("UUID")) snapshotUuids.add(snap.getUuid("UUID"));
        }
        for (NbtCompound snap : itemSnapshots) {
            if (snap.containsUuid("UUID")) snapshotUuids.add(snap.getUuid("UUID"));
        }

        // Clear ALL dimension logs
        world.getServer().getWorlds().forEach(sw -> {
            RbdChangeLog.get(sw).clear();
            RbdEntityLog.get(sw).clear();
        });

        File dir = getSaveDirectory(world);
        if (!dir.exists()) dir.mkdirs();
        NbtCompound nbt = new NbtCompound();
        currentCheckpoint.writeNbt(nbt);
        try { NbtIo.writeCompressed(nbt, new File(dir, "checkpoint.nbt").toPath()); }
        catch (IOException e) { e.printStackTrace(); }
    }

    public static CheckpointData load(ServerWorld world) {
        if (currentCheckpoint != null) return currentCheckpoint;
        File file = new File(getSaveDirectory(world), "checkpoint.nbt");
        if (!file.exists()) return null;
        try {
            currentCheckpoint = CheckpointData.readNbt(NbtIo.readCompressed(file.toPath(), NbtSizeTracker.ofUnlimitedBytes()));
            return currentCheckpoint;
        } catch (IOException e) { e.printStackTrace(); return null; }
    }

    public static void clear(ServerWorld world) {
        currentCheckpoint = null;
        File f = new File(getSaveDirectory(world), "checkpoint.nbt");
        if (f.exists()) f.delete();
        world.getServer().getWorlds().forEach(sw -> {
            RbdChangeLog.get(sw).clear();
            RbdEntityLog.get(sw).clear();
        });
    }

    public static void clearInMemory() {
        currentCheckpoint = null;
        snapshotUuids.clear();
    }

    private static File getSaveDirectory(ServerWorld world) {
        return new File(world.getServer().getSavePath(WorldSavePath.ROOT).toFile(), "rbd");
    }
}
=== src/main/java/com/deist/rbd/log/ChangeType.java ===
package com.deist.rbd.log;

public enum ChangeType {
    PLAYER_BREAK,
    PLAYER_PLACE,
    EXPLOSION,
    ENTITY_CHANGE,
    FLUID_FLOW,
    FIRE_SPREAD,
    PISTON,
    UNKNOWN,
    WORLD_GEN
}
=== src/main/java/com/deist/rbd/log/BlockChangeEntry.java ===
package com.deist.rbd.log;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

public class BlockChangeEntry {
    public final BlockPos pos;
    public final BlockState oldState;
    public final BlockState newState;
    public final NbtCompound blockEntityNbt;
    public final ChangeType cause;
    public final long tick;

    public BlockChangeEntry(BlockPos pos, BlockState oldState, BlockState newState, NbtCompound blockEntityNbt, ChangeType cause, long tick) {
        this.pos = pos;
        this.oldState = oldState;
        this.newState = newState;
        this.blockEntityNbt = blockEntityNbt;
        this.cause = cause;
        this.tick = tick;
    }
}
=== src/main/java/com/deist/rbd/log/RbdChangeLog.java ===
package com.deist.rbd.log;

import com.deist.rbd.core.RbdConfig;
import com.deist.rbd.core.RbdStateManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

public class RbdChangeLog {
    private static final Map<RegistryKey<World>, RbdChangeLog> instances = new HashMap<>();

    public static Set<RegistryKey<World>> getAllTrackedDimensions() {
        return instances.keySet();
    }

    private final ArrayList<BlockChangeEntry> entries = new ArrayList<>();
    private final int maxEntries;

    private RbdChangeLog(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public static RbdChangeLog get(ServerWorld world) {
        return instances.computeIfAbsent(world.getRegistryKey(),
            k -> new RbdChangeLog(50_000));
    }

    public void record(BlockPos pos, BlockState oldState, BlockState newState,
                       NbtCompound beNbt, ChangeType cause, long time) {
        if (entries.size() >= maxEntries) truncate();
        entries.add(new BlockChangeEntry(pos.toImmutable(), oldState, newState, beNbt,
                                         cause, time));
    }

    public void rollback(ServerWorld world) {
        RbdStateManager.setRollingBack(true);
        try {
            ListIterator<BlockChangeEntry> it = entries.listIterator(entries.size());
            while (it.hasPrevious()) {
                BlockChangeEntry e = it.previous();
                world.setBlockState(e.pos, e.oldState, Block.NOTIFY_ALL);
                if (e.blockEntityNbt != null) {
                    BlockEntity be = world.getBlockEntity(e.pos);
                    if (be != null) {
                        be.readNbt(e.blockEntityNbt);
                    }
                }
            }
        } finally {
            RbdStateManager.setRollingBack(false);
            entries.clear();
        }
    }

    public void clear() {
        entries.clear();
    }

    private void truncate() {
        int removeCount = (int)(maxEntries * 0.2);
        entries.subList(0, removeCount).clear();
    }
}
=== src/main/java/com/deist/rbd/log/EntityEntryType.java ===
package com.deist.rbd.log;

public enum EntityEntryType {
    KILLED,
    SPAWNED,
    MUTATED
}
=== src/main/java/com/deist/rbd/log/EntityChangeEntry.java ===
package com.deist.rbd.log;

import net.minecraft.nbt.NbtCompound;

import java.util.UUID;

public class EntityChangeEntry {
    public final UUID uuid;
    public final EntityEntryType type;
    public final NbtCompound fullNbt;
    public NbtCompound oldNbt;
    public final long tick;

    public EntityChangeEntry(UUID uuid, EntityEntryType type, NbtCompound fullNbt, NbtCompound oldNbt, long tick) {
        this.uuid = uuid;
        this.type = type;
        this.fullNbt = fullNbt;
        this.oldNbt = oldNbt;
        this.tick = tick;
    }
}
=== src/main/java/com/deist/rbd/log/RbdEntityLog.java ===
package com.deist.rbd.log;

import com.deist.rbd.core.RbdStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.util.*;

public class RbdEntityLog {
    private static final Map<RegistryKey<World>, RbdEntityLog> instances = new HashMap<>();

    private final List<EntityChangeEntry> entries = new ArrayList<>();
    private final Map<UUID, NbtCompound> bossSnapshots = new HashMap<>();
    private final int maxEntries = 5000;

    public static RbdEntityLog get(ServerWorld world) {
        return instances.computeIfAbsent(world.getRegistryKey(), k -> new RbdEntityLog());
    }

    public static Set<RegistryKey<World>> getAllTrackedDimensions() {
        return instances.keySet();
    }

    /** Restore entities that were KILLED after checkpoint but were NOT in the checkpoint snapshot.
     *  (These are entities that existed but weren't snapshotted — e.g. in unloaded chunks; the
     *  snapshot already handles the ones that WERE captured.) */
    public void restoreKilled(ServerWorld world, Set<UUID> checkpointUuids) {
        for (EntityChangeEntry e : entries) {
            if (e.type != EntityEntryType.KILLED) continue;
            if (checkpointUuids.contains(e.uuid)) continue; // already restored via snapshot
            if (e.fullNbt == null) continue;
            Entity existing = getByUuid(world, e.uuid);
            if (existing != null) existing.discard();
            EntityType.loadEntityWithPassengers(e.fullNbt, world, entity -> {
                world.spawnEntity(entity);
                return entity;
            });
        }
    }

    public void recordKilled(UUID uuid, NbtCompound nbt, long tick) {
        if (entries.size() >= maxEntries) truncate();
        entries.add(new EntityChangeEntry(uuid, EntityEntryType.KILLED, nbt.copy(), null, tick));
    }

    public void recordSpawned(UUID uuid, long tick) {
        if (entries.size() >= maxEntries) truncate();
        entries.add(new EntityChangeEntry(uuid, EntityEntryType.SPAWNED, null, null, tick));
    }

    public Set<UUID> getSpawnedUuids() {
        Set<UUID> uuids = new HashSet<>();
        for (EntityChangeEntry e : entries) {
            if (e.type == EntityEntryType.SPAWNED) {
                uuids.add(e.uuid);
            }
        }
        return uuids;
    }

    public void updateBossSnapshot(UUID uuid, NbtCompound snap, long tick) {
        if (!bossSnapshots.containsKey(uuid)) {
            bossSnapshots.put(uuid, snap.copy());
        }
    }

    public void rollback(ServerWorld world) {
        RbdStateManager.setRollingBack(true);
        try {
            // FIRST: Build a set of UUIDs that were SPAWNED after checkpoint.
            // These entities should NOT exist after rollback, period.
            Set<UUID> spawnedAfterCheckpoint = new HashSet<>();
            for (EntityChangeEntry e : entries) {
                if (e.type == EntityEntryType.SPAWNED) {
                    spawnedAfterCheckpoint.add(e.uuid);
                }
            }

            Set<UUID> processedUuids = new HashSet<>();

            ListIterator<EntityChangeEntry> it = entries.listIterator(entries.size());
            while (it.hasPrevious()) {
                EntityChangeEntry e = it.previous();

                if (processedUuids.contains(e.uuid)) continue;
                processedUuids.add(e.uuid);

                switch (e.type) {
                    case KILLED -> {
                        // If this entity was ALSO spawned after checkpoint,
                        // it should NOT exist after rollback. Just remove it.
                        if (spawnedAfterCheckpoint.contains(e.uuid)) {
                            Entity existing = getByUuid(world, e.uuid);
                            if (existing != null) existing.discard();
                        } else {
                            // Entity existed before checkpoint and was killed → respawn it
                            Entity existing = getByUuid(world, e.uuid);
                            if (existing != null) existing.discard();
                            EntityType.loadEntityWithPassengers(e.fullNbt, world, entity -> {
                                world.spawnEntity(entity);
                                return entity;
                            });
                        }
                    }
                    case SPAWNED -> {
                        // Entity spawned after checkpoint → remove it
                        Entity toRemove = getByUuid(world, e.uuid);
                        if (toRemove != null) toRemove.discard();
                    }
                    case MUTATED -> {
                        // Handled below via bossSnapshots
                    }
                }
            }

            // Restore bosses from their first snapshots
            for (Map.Entry<UUID, NbtCompound> entry : bossSnapshots.entrySet()) {
                UUID uuid = entry.getKey();
                NbtCompound snapshot = entry.getValue();

                if (processedUuids.contains(uuid)) continue;

                Entity boss = getByUuid(world, uuid);
                if (boss != null) {
                    boss.readNbt(snapshot);
                } else {
                    EntityType.loadEntityWithPassengers(snapshot, world, entity -> {
                        world.spawnEntity(entity);
                        return entity;
                    });
                }
            }
        } finally {
            RbdStateManager.setRollingBack(false);
            entries.clear();
            bossSnapshots.clear();
        }
    }

    private Entity getByUuid(ServerWorld world, UUID uuid) {
        return world.getEntity(uuid);
    }

    public void clear() {
        entries.clear();
        bossSnapshots.clear();
    }

    private void truncate() {
        int removeCount = (int) (maxEntries * 0.2);
        if (removeCount > 0) {
            entries.subList(0, removeCount).clear();
        }
    }
}
=== src/main/java/com/deist/rbd/rollback/RollbackExecutor.java ===
package com.deist.rbd.rollback;

import com.deist.rbd.checkpoint.CheckpointData;
import com.deist.rbd.checkpoint.CheckpointManager;
import com.deist.rbd.core.RbdMod;
import com.deist.rbd.core.RbdPendingDeletions;
import com.deist.rbd.core.RbdStateManager;
import com.deist.rbd.log.RbdChangeLog;
import com.deist.rbd.log.RbdEntityLog;
import com.deist.rbd.mixin.RaidManagerAccessor;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EyeOfEnderEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.raid.Raid;
import net.minecraft.village.raid.RaidManager;
import net.minecraft.world.World;
import net.minecraft.world.level.ServerWorldProperties;

import java.util.*;

public class RollbackExecutor {
    public static void execute(ServerPlayerEntity player, ServerWorld world, int miasmaLevel) {
        CheckpointData cp = CheckpointManager.load(world);
        if (cp == null) return;

        player.setNoGravity(true);
        player.getAbilities().invulnerable = true;
        player.sendAbilitiesUpdate();
        RbdStateManager.setWaitingForRollback(player.getUuid(), true);

        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                com.deist.rbd.effects.RbdNetworking.ROLLBACK_START_ID,
                net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create());

        RbdMod.scheduleTask(40, () -> {
            world.getServer().execute(() -> {
                ServerWorld targetWorld = world.getServer().getWorld(
                    RegistryKey.of(RegistryKeys.WORLD, new Identifier(cp.dimension))
                );
                if (targetWorld == null) targetWorld = world.getServer().getOverworld();

                // === 1. ROLLBACK BLOCKS in ALL tracked dimensions ===
                for (RegistryKey<World> dimKey : new HashSet<>(RbdChangeLog.getAllTrackedDimensions())) {
                    ServerWorld dw = world.getServer().getWorld(dimKey);
                    if (dw != null) RbdChangeLog.get(dw).rollback(dw);
                }

                // === 2. ENTITY ROLLBACK ===
                // Build UUID sets from checkpoint snapshot
                Set<UUID> checkpointUuids = new HashSet<>();
                for (NbtCompound snap : cp.entitySnapshots) {
                    if (snap.containsUuid("UUID")) checkpointUuids.add(snap.getUuid("UUID"));
                }

                RbdStateManager.setRollingBack(true);
                try {
                    world.getServer().getWorlds().forEach(sw -> {
                        RbdEntityLog log = RbdEntityLog.get(sw);

                        // Step A: Remove entities SPAWNED after checkpoint
                        Set<UUID> spawnedUuids = log.getSpawnedUuids();
                        RbdPendingDeletions.addAll(spawnedUuids);
                        List<Entity> toRemove = new ArrayList<>();
                        sw.iterateEntities().forEach(entity -> {
                            if (entity instanceof PlayerEntity) return;
                            if (spawnedUuids.contains(entity.getUuid())) toRemove.add(entity);
                            // Also remove projectiles unconditionally
                            if (entity instanceof ProjectileEntity || entity instanceof EyeOfEnderEntity)
                                toRemove.add(entity);
                        });
                        toRemove.forEach(e -> {
                            e.discard();
                            RbdPendingDeletions.remove(e.getUuid());
                        });

                        // Step B: Re-apply checkpoint NBT for entities that still exist
                        // (handles health/position drift for mobs that were alive at checkpoint
                        //  and are still alive now but may have moved or been damaged)
                        Map<UUID, NbtCompound> snapshotByUuid = new HashMap<>();
                        for (NbtCompound snap : cp.entitySnapshots) {
                            if (snap.containsUuid("UUID")) {
                                String snapDim = snap.getString("RbdDim");
                                if (new Identifier(snapDim).equals(sw.getRegistryKey().getValue())) {
                                    snapshotByUuid.put(snap.getUuid("UUID"), snap);
                                }
                            }
                        }
                        sw.iterateEntities().forEach(entity -> {
                            if (entity instanceof PlayerEntity) return;
                            NbtCompound snap = snapshotByUuid.get(entity.getUuid());
                            if (snap != null) {
                                NbtCompound clean = snap.copy();
                                clean.remove("RbdDim");
                                entity.readNbt(clean);
                            }
                        });

                        // Step C: Restore entities KILLED after checkpoint (from delta log)
                        log.restoreKilled(sw, checkpointUuids);
                    });
                } finally {
                    RbdStateManager.setRollingBack(false);
                }

                // === 3. ITEM ENTITIES ===
                world.getServer().getWorlds().forEach(sw -> {
                    sw.getEntitiesByType(EntityType.ITEM, e -> true).forEach(Entity::discard);
                    sw.getEntitiesByType(EntityType.EXPERIENCE_ORB, e -> true).forEach(Entity::discard);
                });
                RbdStateManager.setRollingBack(true);
                try {
                    for (NbtCompound itemNbt : cp.itemEntitySnapshots) {
                        String dimId = itemNbt.getString("RbdDim");
                        ServerWorld sw = world.getServer().getWorld(
                            RegistryKey.of(RegistryKeys.WORLD, new Identifier(dimId))
                        );
                        if (sw == null) sw = targetWorld;
                        NbtCompound clean = itemNbt.copy();
                        clean.remove("RbdDim");
                        final ServerWorld fsw = sw;
                        EntityType.loadEntityWithPassengers(clean, fsw, entity -> {
                            fsw.spawnEntity(entity);
                            return entity;
                        });
                    }
                } finally {
                    RbdStateManager.setRollingBack(false);
                }

                // === 4. RESTORE CONTAINER BLOCK ENTITIES ===
                for (NbtCompound beNbt : cp.blockEntitySnapshots) {
                    String dimId = beNbt.getString("RbdBeDim");
                    ServerWorld sw = world.getServer().getWorld(
                        RegistryKey.of(RegistryKeys.WORLD, new Identifier(dimId))
                    );
                    if (sw == null) continue;
                    BlockPos bePos = new BlockPos(
                        beNbt.getInt("RbdBeX"), beNbt.getInt("RbdBeY"), beNbt.getInt("RbdBeZ")
                    );
                    BlockEntity be = sw.getBlockEntity(bePos);
                    if (be != null) {
                        be.readNbt(beNbt);
                        be.markDirty();
                    }
                }

                // === 5. RESTORE ENDER CHEST ===
                if (cp.enderChestInventory.contains("Items")) {
                    player.getEnderChestInventory().readNbtList(cp.enderChestInventory.getList("Items", net.minecraft.nbt.NbtElement.COMPOUND_TYPE));
                }

                // === 6. WEATHER ===
                ServerWorldProperties wp = (ServerWorldProperties) world.getServer().getOverworld().getLevelProperties();
                wp.setClearWeatherTime(cp.clearWeatherTime);
                wp.setRainTime(cp.rainTime);
                wp.setThunderTime(cp.thunderTime);
                wp.setRaining(cp.raining);
                wp.setThundering(cp.thundering);

                // === 7. WORLD TIME ===
                targetWorld.setTimeOfDay(cp.worldTime);

                // === 8. CANCEL ALL RAIDS ===
                // === 8. CANCEL ALL RAIDS ===
                // Invalidate each raid — do NOT call raids.clear() directly,
                // that corrupts RaidManager state causing random mob vanishing.
                world.getServer().getWorlds().forEach(sw -> {
                    RaidManager rm = sw.getRaidManager();
                    if (rm != null) {
                        try {
                            RaidManagerAccessor accessor = (RaidManagerAccessor) rm;
                            List<Raid> raidList = new ArrayList<>(accessor.getRaids().values());
                            raidList.forEach(Raid::invalidate);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
                player.removeStatusEffect(net.minecraft.entity.effect.StatusEffects.BAD_OMEN);
                player.removeStatusEffect(net.minecraft.entity.effect.StatusEffects.HERO_OF_THE_VILLAGE);

                // === 9. RESET MOB ANGER ===
                // (already handled by entity respawn from clean snapshots,
                //  but also reset any leftover entities that weren't in snapshot)
                world.getServer().getWorlds().forEach(sw -> {
                    sw.iterateEntities().forEach(entity -> {
                        if (entity instanceof Angerable angerable) {
                            angerable.stopAnger();
                            if (entity instanceof MobEntity mob) mob.setTarget(null);
                        }
                    });
                });

                // === 10. PLAYER RESTORE ===
                player.teleport(targetWorld, cp.pos.getX() + 0.5, cp.pos.getY(), cp.pos.getZ() + 0.5, cp.yaw, cp.pitch);
                player.setHealth(cp.health);
                player.getHungerManager().setFoodLevel(cp.hunger);
                player.getHungerManager().setSaturationLevel(cp.saturation);
                player.experienceLevel = cp.experienceLevel;
                player.experienceProgress = cp.experienceProgress;
                player.getSculkShriekerWarningManager().ifPresent(manager -> manager.setWarningLevel(cp.sculkWarningLevel));

                player.getInventory().clear();
                for (int i = 0; i < cp.inventory.size(); i++) {
                    player.getInventory().setStack(i, cp.inventory.get(i).copy());
                }

                player.clearStatusEffects();
                for (NbtCompound effectNbt : cp.statusEffects) {
                    StatusEffectInstance effect = StatusEffectInstance.fromNbt(effectNbt);
                    if (effect != null) player.addStatusEffect(effect);
                }

                // === 11. FINALIZE ===
                cp.loopNumber++;
                final ServerWorld ft = targetWorld;

                CheckpointManager.save(player, ft);

                player.setNoGravity(false);
                player.getAbilities().invulnerable = false;
                player.sendAbilitiesUpdate();
                RbdStateManager.setWaitingForRollback(player.getUuid(), false);
                player.setHealth(cp.health);

                net.minecraft.network.PacketByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
                buf.writeInt(miasmaLevel);
                buf.writeInt(cp.loopNumber); // needed for Aishiteru trigger on client
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                        com.deist.rbd.effects.RbdNetworking.ROLLBACK_COMPLETE_ID, buf);

                // Restore journal if it was lost from inventory
                com.deist.rbd.journal.DeathJournalItem.ensureJournal(player);
            });
        });
    }
}
=== src/main/java/com/deist/rbd/mixin/PlayerDeathMixin.java ===
package com.deist.rbd.mixin;

import com.deist.rbd.checkpoint.CheckpointData;
import com.deist.rbd.checkpoint.CheckpointManager;
import com.deist.rbd.journal.DeathJournalItem;
import com.deist.rbd.journal.JournalEntry;
import com.deist.rbd.journal.JournalManager;
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

        // ── 2. Record journal entry ────────────────────────────────────────
        String cause = "";
        try { cause = damageSource.getDeathMessage(player).getString(); } catch (Exception ignored) {}
        long survived = world.getTime() - cp.timestamp;
        JournalEntry entry = new JournalEntry(
            cp.loopNumber,
            System.currentTimeMillis(),
            world.getTime(),
            cause,
            player.getX(), player.getY(), player.getZ(),
            world.getRegistryKey().getValue().toString(),
            survived,
            newMiasmaLevel
        );
        JournalManager.addEntry(player, entry);

        // ── 3. Trigger rollback ────────────────────────────────────────────
        RollbackExecutor.execute(player, world, newMiasmaLevel);

        // Cancel vanilla death (no item drops, no respawn screen)
        player.setHealth(player.getMaxHealth());
        ci.cancel();
    }
}
=== src/main/java/com/deist/rbd/mixin/WorldMixin.java ===
package com.deist.rbd.mixin;

import com.deist.rbd.core.RbdStateManager;
import com.deist.rbd.log.ChangeType;
import com.deist.rbd.log.RbdChangeLog;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public class WorldMixin {

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z", at = @At("HEAD"))
    private void onSetBlock(BlockPos pos, BlockState newState, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
        if (RbdStateManager.isRollingBack()) return;

        World world = (World) (Object) this;
        if (world.isClient()) return; // Chỉ log trên server

        ServerWorld serverWorld = (ServerWorld) world;
        BlockState oldState = world.getBlockState(pos);

        if (oldState.equals(newState)) return;

        NbtCompound beNbt = null;
        BlockEntity be = world.getBlockEntity(pos);
        if (be != null) {
            beNbt = be.createNbt();
        }

        ChangeType cause = RbdStateManager.getCurrentCause();
        if (cause == ChangeType.UNKNOWN) {
            cause = ChangeType.WORLD_GEN;
        }

        RbdChangeLog.get(serverWorld).record(pos, oldState, newState, beNbt, cause, serverWorld.getTime());
    }
}
=== src/main/java/com/deist/rbd/mixin/SpawnPointMixin.java ===
package com.deist.rbd.mixin;

import com.deist.rbd.checkpoint.CheckpointManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public class SpawnPointMixin {

    @Inject(method = "setSpawnPoint", at = @At("RETURN"))
    private void onSetSpawnPoint(RegistryKey<World> dimension, BlockPos pos, float angle, boolean forced, boolean sendMessage, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        // setSpawnPoint is called when interacting with beds/anchors or commands.
        // We trigger a checkpoint save here.
        if (pos != null) {
            CheckpointManager.save(player, player.getServerWorld());
        }
    }
}
=== src/main/java/com/deist/rbd/mixin/PlayerInteractionMixin.java ===
package com.deist.rbd.mixin;

import com.deist.rbd.core.RbdStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerEntity.class)
public class PlayerInteractionMixin {

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void onAttack(Entity target, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        if (RbdStateManager.isWaitingForRollback(player.getUuid())) {
            ci.cancel();
        }
    }

    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void onDropItem(ItemStack stack, boolean throwRandomly, boolean retainOwnership, CallbackInfoReturnable<net.minecraft.entity.ItemEntity> cir) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        if (RbdStateManager.isWaitingForRollback(player.getUuid())) {
            cir.setReturnValue(null);
        }
    }

    // Block interaction with blocks and entities
    // We can also target the ServerPlayNetworkHandler but Mixin on Player is easier.
}
=== src/main/java/com/deist/rbd/mixin/LivingEntityDeathMixin.java ===
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
=== src/main/java/com/deist/rbd/mixin/EntitySpawnMixin.java ===
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
=== src/main/java/com/deist/rbd/mixin/ChunkStorageAccessor.java ===
package com.deist.rbd.mixin;

import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ThreadedAnvilChunkStorage.class)
public interface ChunkStorageAccessor {
    @Invoker("entryIterator")
    Iterable<ChunkHolder> invokeEntryIterator();
}
=== src/main/java/com/deist/rbd/mixin/RaidManagerAccessor.java ===
package com.deist.rbd.mixin;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.raid.Raid;
import net.minecraft.village.raid.RaidManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(RaidManager.class)
public interface RaidManagerAccessor {
    @Accessor("raids")
    Map<Integer, Raid> getRaids();
}
=== src/main/java/com/deist/rbd/mixin/ChunkEntityLoadMixin.java ===
package com.deist.rbd.mixin;

// This file intentionally left as placeholder.
// Pending deletion cleanup is handled via RbdMod server tick sweep.
// See RbdPendingDeletions and RbdMod.onInitialize() for the implementation.
=== src/main/java/com/deist/rbd/mixin/EntityMixin.java ===
package com.deist.rbd.mixin;

import com.deist.rbd.core.RbdStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.event.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "emitGameEvent(Lnet/minecraft/world/event/GameEvent;Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void onEmitGameEvent(GameEvent event, Entity entity, CallbackInfo ci) {
        if ((Object) this instanceof PlayerEntity player) {
            if (RbdStateManager.isWaitingForRollback(player.getUuid())) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "emitGameEvent(Lnet/minecraft/world/event/GameEvent;)V", at = @At("HEAD"), cancellable = true)
    private void onEmitGameEventNoEntity(GameEvent event, CallbackInfo ci) {
        if ((Object) this instanceof PlayerEntity player) {
            if (RbdStateManager.isWaitingForRollback(player.getUuid())) {
                ci.cancel();
            }
        }
    }
}
=== src/main/java/com/deist/rbd/mixin/EntityRemoveMixin.java ===
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
=== src/main/java/com/deist/rbd/mixin/PiglinGoldBypassMixin.java ===
package com.deist.rbd.mixin;

import com.deist.rbd.miasma.MiasmaManager;
import net.minecraft.entity.mob.PiglinBrain;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bypasses Piglin gold-armor immunity when the target player has Miasma >= 2.
 * PiglinBrain.wearsGoldArmor() normally returns true if the player wears any gold armor,
 * preventing Piglins from becoming or staying angry. This Mixin overrides that.
 */
@Mixin(PiglinBrain.class)
public class PiglinGoldBypassMixin {

    @Inject(method = "wearsGoldArmor", at = @At("RETURN"), cancellable = true)
    private static void bypassGoldImmunity(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        // Only proceed if the result was "wearing gold" (true) — nothing to do otherwise
        if (!cir.getReturnValue()) return;

        if (!(entity instanceof ServerPlayerEntity player)) return;
        if (!(entity.getWorld() instanceof net.minecraft.server.world.ServerWorld sw)) return;

        int level = MiasmaManager.get(sw.getServer()).getMiasmaLevel(player.getUuid());
        if (level >= 2) {
            // Override: return false so Piglins treat the player as not wearing gold
            cir.setReturnValue(false);
        }
    }
}

=== src/main/java/com/deist/rbd/mixin/RidingPreventMixin.java ===
package com.deist.rbd.mixin;

import com.deist.rbd.miasma.MiasmaManager;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents players with Miasma >= 1 from mounting any entity.
 * Animals flee or reject the miasma-tainted player.
 */
@Mixin(Entity.class)
public class RidingPreventMixin {

    @Inject(method = "startRiding(Lnet/minecraft/entity/Entity;Z)Z", at = @At("HEAD"), cancellable = true)
    private void preventMiasmaRiding(Entity vehicle, boolean force, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity)(Object) this;
        if (!(self instanceof ServerPlayerEntity player)) return;
        if (player.server == null) return;

        int level = MiasmaManager.get(player.server).getMiasmaLevel(player.getUuid());
        if (level >= 1) {
            cir.setReturnValue(false); // reject mount attempt
        }
    }
}
=== src/main/java/com/deist/rbd/effects/RbdNetworking.java ===
package com.deist.rbd.effects;

import net.minecraft.util.Identifier;

public class RbdNetworking {
    public static final Identifier ROLLBACK_START_ID    = new Identifier("rbd", "rollback_start");
    public static final Identifier ROLLBACK_COMPLETE_ID = new Identifier("rbd", "rollback_complete");
    /** Sent server→client to sync current miasmaLevel and maxMiasmaLevel for the overlay. */
    public static final Identifier MIASMA_SYNC_ID       = new Identifier("rbd", "miasma_sync");
}
=== src/main/java/com/deist/rbd/miasma/MiasmaManager.java ===
package com.deist.rbd.miasma;

import com.deist.rbd.core.RbdConfig;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the Witch's Miasma per-player data.
 * Stored via PersistentState → survives world reload, independent of player NBT.
 *
 * Data per player:
 *   - miasmaLevel     : 0..MAX, derived from effectiveDeaths, caps at maxMiasmaLevel
 *   - effectiveDeaths : hidden score — increases on death, ALSO decays every N days
 *                       so that one more death after decay doesn't jump back to MAX instantly
 *   - totalDeaths     : read-only lifetime counter, used for journal display only
 *   - lastGameDay     : last in-game day when decay was checked
 */
public class MiasmaManager extends PersistentState {

    private static final String DATA_KEY = "rbd_miasma";

    private final Map<UUID, Integer> miasmaLevels     = new HashMap<>();
    private final Map<UUID, Integer> effectiveDeaths  = new HashMap<>(); // hidden score, decays
    private final Map<UUID, Integer> totalDeaths      = new HashMap<>(); // journal only, never decays
    private final Map<UUID, Long>    lastGameDay      = new HashMap<>();

    // ── Level table based on effectiveDeaths ───────────────────────────────
    public static int computeLevel(int eff) {
        if (eff == 0)       return 0;
        if (eff <= 3)       return 1;
        if (eff <= 8)       return 2;
        if (eff <= 15)      return 3;
        return Math.min(eff / 4, RbdConfig.get().maxMiasmaLevel);
    }

    // ── Accessors ──────────────────────────────────────────────────────────
    public int getMiasmaLevel(UUID uuid) {
        return miasmaLevels.getOrDefault(uuid, 0);
    }

    public int getEffectiveDeaths(UUID uuid) {
        return effectiveDeaths.getOrDefault(uuid, 0);
    }

    public int getTotalDeaths(UUID uuid) {
        return totalDeaths.getOrDefault(uuid, 0);
    }

    /** Called each time the player dies. Returns new miasma level. */
    public int onDeath(UUID uuid) {
        // Increment both counters
        int eff    = effectiveDeaths.merge(uuid, 1, Integer::sum);
        totalDeaths.merge(uuid, 1, Integer::sum);
        int level  = Math.min(computeLevel(eff), RbdConfig.get().maxMiasmaLevel);
        miasmaLevels.put(uuid, level);
        markDirty();
        return level;
    }

    /**
     * Tick decay: call once per second.
     * Reduces effectiveDeaths by 1 every N in-game days, then recomputes level.
     * Because effectiveDeaths decays, one more death won't jump straight back to MAX.
     */
    public boolean tickDecay(ServerPlayerEntity player, long currentWorldDay) {
        if (!RbdConfig.get().miasmaDecay) return false;
        UUID uuid = player.getUuid();
        int eff = effectiveDeaths.getOrDefault(uuid, 0);
        if (eff <= 0) return false;

        long last = lastGameDay.getOrDefault(uuid, currentWorldDay);
        long daysPassed = currentWorldDay - last;

        if (daysPassed >= RbdConfig.get().miasmaDecayDays) {
            int steps  = (int)(daysPassed / RbdConfig.get().miasmaDecayDays);
            int newEff = Math.max(0, eff - steps);
            effectiveDeaths.put(uuid, newEff);
            int newLevel = Math.min(computeLevel(newEff), RbdConfig.get().maxMiasmaLevel);
            int oldLevel = miasmaLevels.getOrDefault(uuid, 0);
            miasmaLevels.put(uuid, newLevel);
            lastGameDay.put(uuid, currentWorldDay - (daysPassed % RbdConfig.get().miasmaDecayDays));
            markDirty();
            return newLevel != oldLevel; // return true only if level actually changed
        } else {
            if (!lastGameDay.containsKey(uuid)) {
                lastGameDay.put(uuid, currentWorldDay);
                markDirty();
            }
        }
        return false;
    }

    /** Detection bonus in blocks added to mob follow range. */
    public static int getDetectionBonus(int level) {
        return switch (level) {
            case 1  -> 9;
            case 2  -> 18;
            case 3  -> 36;
            default -> level >= 4 ? 72 : 0;
        };
    }

    // ── PersistentState NBT ───────────────────────────────────────────────
    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtCompound levels  = new NbtCompound();
        NbtCompound effMap  = new NbtCompound();
        NbtCompound totMap  = new NbtCompound();
        NbtCompound daysMap = new NbtCompound();
        miasmaLevels.forEach((uuid, v)    -> levels.putInt(uuid.toString(), v));
        effectiveDeaths.forEach((uuid, v) -> effMap.putInt(uuid.toString(), v));
        totalDeaths.forEach((uuid, v)     -> totMap.putInt(uuid.toString(), v));
        lastGameDay.forEach((uuid, v)     -> daysMap.putLong(uuid.toString(), v));
        nbt.put("MiasmaLevels",    levels);
        nbt.put("EffectiveDeaths", effMap);
        nbt.put("TotalDeaths",     totMap);
        nbt.put("LastGameDay",     daysMap);
        return nbt;
    }

    public static MiasmaManager fromNbt(NbtCompound nbt) {
        MiasmaManager m = new MiasmaManager();
        NbtCompound levels  = nbt.getCompound("MiasmaLevels");
        NbtCompound effMap  = nbt.getCompound("EffectiveDeaths");
        NbtCompound totMap  = nbt.getCompound("TotalDeaths");
        NbtCompound daysMap = nbt.getCompound("LastGameDay");
        for (String k : levels.getKeys())  m.miasmaLevels.put(UUID.fromString(k), levels.getInt(k));
        for (String k : effMap.getKeys())  m.effectiveDeaths.put(UUID.fromString(k), effMap.getInt(k));
        for (String k : totMap.getKeys())  m.totalDeaths.put(UUID.fromString(k), totMap.getInt(k));
        for (String k : daysMap.getKeys()) m.lastGameDay.put(UUID.fromString(k), daysMap.getLong(k));
        // Back-compat: if old save has TotalDeaths but no EffectiveDeaths, migrate
        if (effMap.isEmpty() && !totMap.isEmpty()) {
            for (String k : totMap.getKeys()) m.effectiveDeaths.put(UUID.fromString(k), totMap.getInt(k));
        }
        return m;
    }

    // ── Singleton accessor ─────────────────────────────────────────────────
    public static MiasmaManager get(MinecraftServer server) {
        PersistentStateManager psm = server.getOverworld().getPersistentStateManager();
        return psm.getOrCreate(
            new PersistentState.Type<>(MiasmaManager::new, MiasmaManager::fromNbt, null),
            DATA_KEY
        );
    }
}
=== src/main/java/com/deist/rbd/miasma/MiasmaAggroGoal.java ===
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
=== src/main/java/com/deist/rbd/miasma/ScentBehaviorRegistry.java ===
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
=== src/main/java/com/deist/rbd/journal/JournalEntry.java ===
package com.deist.rbd.journal;

/**
 * Data class for a single Death Journal entry.
 */
public class JournalEntry {
    public final int  loopNumber;
    public final long timestamp;      // real time (unix ms)
    public final long worldTime;      // game tick of death
    public final String deathCause;  // e.g. "minecraft.death.attack.mob"
    public final double deathX;
    public final double deathY;
    public final double deathZ;
    public final String deathDim;    // e.g. "minecraft:overworld"
    public final long survivedTicks; // ticks from checkpoint to death
    public final int miasmaLevel;

    public JournalEntry(int loopNumber, long timestamp, long worldTime,
                        String deathCause, double x, double y, double z,
                        String deathDim, long survivedTicks, int miasmaLevel) {
        this.loopNumber    = loopNumber;
        this.timestamp     = timestamp;
        this.worldTime     = worldTime;
        this.deathCause    = deathCause;
        this.deathX        = x;
        this.deathY        = y;
        this.deathZ        = z;
        this.deathDim      = deathDim;
        this.survivedTicks = survivedTicks;
        this.miasmaLevel   = miasmaLevel;
    }

    /** Format survival duration as "Xm Ys" */
    public String formatSurvival() {
        long seconds = survivedTicks / 20;
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    /** Short death cause label for display */
    public String formatCause() {
        if (deathCause == null || deathCause.isEmpty()) return "Unknown";
        // Extract last segment, e.g. "minecraft.death.attack.mob" → "mob"
        String[] parts = deathCause.split("\\.");
        String raw = parts[parts.length - 1];
        // Capitalize and replace underscores
        return raw.substring(0, 1).toUpperCase()
            + raw.substring(1).replace('_', ' ');
    }
}
=== src/main/java/com/deist/rbd/journal/JournalManager.java ===
package com.deist.rbd.journal;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.level.storage.LevelStorage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manages saving and loading Death Journal entries.
 * Backs up per-player to: <world>/rbd/journal_<uuid>.nbt
 */
public class JournalManager {

    private static File getBackupFile(MinecraftServer server, UUID playerUuid) {
        File rbdDir = new File(server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).toFile(), "rbd");
        if (!rbdDir.exists()) rbdDir.mkdirs();
        return new File(rbdDir, "journal_" + playerUuid + ".nbt");
    }

    /** Add a new death entry and persist to disk. */
    public static void addEntry(ServerPlayerEntity player, JournalEntry entry) {
        List<JournalEntry> entries = loadEntries(player.server, player.getUuid());
        entries.add(entry);
        save(player.server, player.getUuid(), entries);
    }

    /** Load all entries for a player from disk backup. */
    public static List<JournalEntry> loadEntries(MinecraftServer server, UUID playerUuid) {
        File file = getBackupFile(server, playerUuid);
        List<JournalEntry> entries = new ArrayList<>();
        if (!file.exists()) return entries;
        try {
            NbtCompound root = NbtIo.readCompressed(file.toPath(), NbtSizeTracker.ofUnlimitedBytes());
            NbtList list = root.getList("Entries", net.minecraft.nbt.NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                NbtCompound e = list.getCompound(i);
                entries.add(new JournalEntry(
                    e.getInt("LoopNumber"),
                    e.getLong("Timestamp"),
                    e.getLong("WorldTime"),
                    e.getString("DeathCause"),
                    e.getDouble("DeathX"), e.getDouble("DeathY"), e.getDouble("DeathZ"),
                    e.getString("DeathDim"),
                    e.getLong("SurvivedTicks"),
                    e.getInt("MiasmaLevel")
                ));
            }
        } catch (IOException e) { e.printStackTrace(); }
        return entries;
    }

    /** Save all entries for a player to disk backup. */
    public static void save(MinecraftServer server, UUID playerUuid, List<JournalEntry> entries) {
        NbtList list = new NbtList();
        for (JournalEntry e : entries) {
            NbtCompound c = new NbtCompound();
            c.putInt("LoopNumber", e.loopNumber);
            c.putLong("Timestamp", e.timestamp);
            c.putLong("WorldTime", e.worldTime);
            c.putString("DeathCause", e.deathCause != null ? e.deathCause : "");
            c.putDouble("DeathX", e.deathX);
            c.putDouble("DeathY", e.deathY);
            c.putDouble("DeathZ", e.deathZ);
            c.putString("DeathDim", e.deathDim != null ? e.deathDim : "minecraft:overworld");
            c.putLong("SurvivedTicks", e.survivedTicks);
            c.putInt("MiasmaLevel", e.miasmaLevel);
            list.add(c);
        }
        NbtCompound root = new NbtCompound();
        root.put("Entries", list);
        try { NbtIo.writeCompressed(root, getBackupFile(server, playerUuid).toPath()); }
        catch (IOException e) { e.printStackTrace(); }
    }
}
=== src/main/java/com/deist/rbd/journal/DeathJournalItem.java ===
package com.deist.rbd.journal;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/**
 * Death Journal — custom item that opens as a Written Book.
 * Data is stored in item NBT and backed up to disk by JournalManager.
 * Auto-restored to inventory after respawn if missing.
 */
public class DeathJournalItem extends Item {

    public static DeathJournalItem INSTANCE;

    /** Wrap a legacy-formatted string into the minimal JSON that WrittenBookItem expects. */
    private static String textToJson(String legacyText) {
        // Escape the string for JSON and wrap in a literal text node
        String escaped = legacyText
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n");
        return "{\"text\":\"" + escaped + "\"}";
    }

    public DeathJournalItem(Settings settings) {
        super(settings);
    }

    @Override
    public Text getName(ItemStack stack) {
        return Text.literal("§5Death Journal");
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return true;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity player) {
            // Build a proper WrittenBook stack from journal data
            ItemStack book = buildBook(player);
            // Place book in hand FIRST, then force-sync the inventory slot to the client,
            // then send OpenWrittenBookS2CPacket — client reads the slot and sees the WrittenBook.
            player.setStackInHand(hand, book);
            player.playerScreenHandler.sendContentUpdates(); // ← critical: syncs slot before open
            player.networkHandler.sendPacket(
                new net.minecraft.network.packet.s2c.play.OpenWrittenBookS2CPacket(hand)
            );
            // Restore the Death Journal item next tick
            com.deist.rbd.core.RbdMod.scheduleTask(1, () -> {
                player.setStackInHand(hand, stack);
                player.playerScreenHandler.sendContentUpdates();
            });
        }
        return TypedActionResult.success(stack);
    }

    /** Build a Written Book ItemStack from the journal entries of the player. */
    public static ItemStack buildBook(ServerPlayerEntity player) {
        List<JournalEntry> entries = JournalManager.loadEntries(player.server, player.getUuid());

        NbtList pages = new NbtList();

        // Cover page
        pages.add(NbtString.of(textToJson(
            "§5§lReturn by Death\n\n" +
            "§7Total loops: §f" + entries.size() + "\n\n" +
            "§8\"Only I remember...\""
        )));

        // One page per entry (newest first)
        for (int i = entries.size() - 1; i >= 0; i--) {
            JournalEntry e = entries.get(i);
            String dim = e.deathDim.replace("minecraft:", "");
            String page = String.format(
                "§5Loop #%d\n§7━━━━━━━━━━\n" +
                "§fTime: §7%s\n" +
                "§fCause: §7%s\n" +
                "§fAt: §7%.0f, %.0f, %.0f\n" +
                "§fDim: §7%s\n" +
                "§fMiasma: §7Lv.%d",
                e.loopNumber,
                e.formatSurvival(),
                e.formatCause(),
                e.deathX, e.deathY, e.deathZ,
                dim,
                e.miasmaLevel
            );
            pages.add(NbtString.of(textToJson(page)));
        }

        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        NbtCompound nbt = book.getOrCreateNbt();
        nbt.putString("title", "Death Journal");
        nbt.putString("author", player.getName().getString());
        nbt.putBoolean("resolved", true);
        nbt.putInt("generation", 0);
        nbt.put("pages", pages);
        return book;
    }

    /** Check if a player has a Death Journal in their inventory. */
    public static boolean hasJournal(PlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() == INSTANCE) return true;
        }
        return false;
    }

    /** Give a fresh Death Journal to the player if they don't have one. */
    public static void ensureJournal(ServerPlayerEntity player) {
        if (!hasJournal(player)) {
            ItemStack journal = new ItemStack(INSTANCE);
            journal.setCustomName(Text.literal("§5Death Journal"));
            player.getInventory().insertStack(journal);
        }
    }
}
=== src/client/java/com/deist/rbd/effects/RbdClientEffects.java ===
package com.deist.rbd.effects;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public class RbdClientEffects {
    public static final SoundEvent HEARTBEAT_SOUND = SoundEvent.of(new Identifier("rbd", "heartbeat_fast"));
    public static final SoundEvent REVERSE_SOUND = SoundEvent.of(new Identifier("rbd", "reverse_whoosh"));
    public static final SoundEvent WITCH_SOUND = SoundEvent.of(new Identifier("rbd", "call_of_the_witch"));
    public static final SoundEvent AISHITERU_SOUND = SoundEvent.of(new Identifier("rbd", "aishiteru"));

    private static int state = 0; // 0 = none, 1 = red/black fade, 2 = white flash, 3 = fade in
    private static int ticksRemaining = 0;
    private static int maxTicks = 0;
    private static int currentMiasmaLevel = 0;

    public static boolean isRollingBack() {
        return state != 0;
    }

    public static void startRollback() {
        state = 1;
        maxTicks = 40; // 2 seconds
        ticksRemaining = maxTicks;
        playSound(HEARTBEAT_SOUND);
    }

    public static void onRollbackComplete(int miasmaLevel, int loopNumber) {
        state = 2;
        maxTicks = 10; // White flash
        ticksRemaining = maxTicks;
        currentMiasmaLevel = miasmaLevel;
        playSound(REVERSE_SOUND);
        // Aishiteru: guaranteed at loop 16, 2% chance for loops 17+
        // (Call of the Witch plays otherwise)
        boolean playAishiteru = loopNumber == 16
                || (loopNumber > 16 && Math.random() < 0.02);
        // Schedule after white flash fades
        final boolean fa = playAishiteru;
        new java.util.Timer().schedule(new java.util.TimerTask() {
            @Override public void run() {
                net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
                    if (fa) {
                        playSound(AISHITERU_SOUND);
                    } else {
                        playSound(WITCH_SOUND);
                    }
                });
            }
        }, 500); // 0.5s after white flash
    }

    public static void tick() {
        if (state == 0) return;

        ticksRemaining--;

        if (ticksRemaining <= 0) {
            if (state == 1) {
                // Should wait for complete packet
            } else if (state == 2) {
                state = 3;
                maxTicks = 40;
                ticksRemaining = maxTicks;
                // Sound is scheduled in onRollbackComplete via Timer
            } else if (state == 3) {
                state = 0;
            }
        }
    }

    public static void renderOverlay(DrawContext context, float tickDelta) {
        if (state == 0) return;

        int width = MinecraftClient.getInstance().getWindow().getScaledWidth();
        int height = MinecraftClient.getInstance().getWindow().getScaledHeight();

        float progress = (float) (maxTicks - ticksRemaining + tickDelta) / maxTicks;
        if (progress > 1.0f) progress = 1.0f;

        if (state == 1) {
            // Flash red, then fade to black
            int alpha;
            int r, g, b;
            if (progress < 0.3f) {
                // Red flash
                alpha = (int) ((progress / 0.3f) * 255);
                r = 150; g = 0; b = 0;
            } else {
                // Fade to black
                alpha = 255;
                float blackProgress = (progress - 0.3f) / 0.7f;
                r = (int) (150 * (1 - blackProgress));
                g = 0; b = 0;
            }
            int color = (alpha << 24) | (r << 16) | (g << 8) | b;
            context.fill(0, 0, width, height, color);
        } else if (state == 2) {
            // White flash
            int alpha = 255;
            int color = (alpha << 24) | 0xFFFFFF;
            context.fill(0, 0, width, height, color);
        } else if (state == 3) {
            // Fade from white/black to clear
            int alpha = (int) ((1.0f - progress) * 255);
            int color = (alpha << 24) | 0x000000;
            context.fill(0, 0, width, height, color);
        }
    }

    private static void playSound(SoundEvent sound) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.getSoundManager().play(PositionedSoundInstance.master(sound, 1.0F, 1.0F));
        }
    }
}
=== src/client/java/com/deist/rbd/effects/MiasmaVisualHandler.java ===
package com.deist.rbd.effects;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Client-side persistent Miasma visual.
 * Shows a semi-transparent purple overlay when the player has Miasma level > 0.
 * Intensity scales with Miasma level (0 = none, maxLevel = max opacity).
 * Updated each time miasmaLevel is received from server.
 */
public class MiasmaVisualHandler {

    // Received from server via packet or command response
    private static int currentLevel = 0;
    private static int maxLevel     = 4;

    // Post-RbD burst: fades up briefly after respawn then settles at persistent intensity
    private static int burstTicks     = 0;
    private static final int BURST_DURATION = 60; // 3s burst after respawn

    public static void setLevel(int level, int max) {
        currentLevel = level;
        maxLevel     = Math.max(1, max);
    }

    /** Called on rollback complete — triggers the post-respawn burst. */
    public static void onRbdComplete(int miasmaLevel) {
        currentLevel = miasmaLevel;
        if (miasmaLevel > 0) burstTicks = BURST_DURATION;
    }

    public static void tick() {
        if (burstTicks > 0) burstTicks--;
    }

    /**
     * Render persistent purple overlay scaled by Miasma level.
     * Also adds a brief intensity burst right after respawn.
     */
    public static void renderVignette(DrawContext context) {
        if (currentLevel <= 0) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        int w = mc.getWindow().getScaledWidth();
        int h = mc.getWindow().getScaledHeight();

        // Base opacity: scales from 15 (Lv1) to 55 (maxLevel) out of 255
        float levelRatio = (float) currentLevel / maxLevel;
        float baseAlpha = 15 + levelRatio * 40; // 15..55

        // Burst adds up to +80 alpha for 3s after respawn
        float burstAlpha = burstTicks > 0
            ? 80f * ((float) burstTicks / BURST_DURATION)
            : 0f;

        int alpha = (int) Math.min(baseAlpha + burstAlpha, 160);
        int color = (alpha << 24) | 0x6A0DAD; // purple
        context.fill(0, 0, w, h, color);
    }

    public static boolean isActive() { return currentLevel > 0; }
    public static int getCurrentLevel() { return currentLevel; }
}
=== src/client/java/com/deist/rbd/core/RbdClientEntrypoint.java ===
package com.deist.rbd.core;

import com.deist.rbd.effects.MiasmaVisualHandler;
import com.deist.rbd.effects.RbdClientEffects;
import com.deist.rbd.effects.RbdNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

public class RbdClientEntrypoint implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(RbdNetworking.ROLLBACK_START_ID, (client, handler, buf, responseSender) -> {
            client.execute(RbdClientEffects::startRollback);
        });

        ClientPlayNetworking.registerGlobalReceiver(RbdNetworking.ROLLBACK_COMPLETE_ID, (client, handler, buf, responseSender) -> {
            int miasmaLevel = buf.readInt();
            int loopNumber  = buf.readInt();
            client.execute(() -> {
                RbdClientEffects.onRollbackComplete(miasmaLevel, loopNumber);
                MiasmaVisualHandler.onRbdComplete(miasmaLevel);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(RbdNetworking.MIASMA_SYNC_ID, (client, handler, buf, responseSender) -> {
            int level    = buf.readInt();
            int maxLevel = buf.readInt();
            client.execute(() -> MiasmaVisualHandler.setLevel(level, maxLevel));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            RbdClientEffects.tick();
            MiasmaVisualHandler.tick();
        });

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            RbdClientEffects.renderOverlay(drawContext, tickDelta);
            MiasmaVisualHandler.renderVignette(drawContext);
        });
    }
}
=== src/client/java/com/deist/rbd/mixin/client/ChatHudMixin.java ===
package com.deist.rbd.mixin.client;

import com.deist.rbd.effects.RbdClientEffects;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatHud.class)
public class ChatHudMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void onRender(DrawContext context, int currentTick, int mouseX, int mouseY, CallbackInfo ci) {
        if (RbdClientEffects.isRollingBack()) {
            ci.cancel();
        }
    }
}
=== src/client/java/com/deist/rbd/client/RbdConfigScreen.java ===
package com.deist.rbd.client;

import com.deist.rbd.core.RbdConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Cloth Config screen builder for Return by Death settings.
 * Accessed via Mod Menu.
 */
public class RbdConfigScreen {

    public static Screen build(Screen parent) {
        RbdConfig cfg = RbdConfig.get();

        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Text.literal("Return by Death — Settings"));

        ConfigEntryBuilder entry = builder.entryBuilder();

        // ── Miasma Gameplay ─────────────────────────────────────────────────
        ConfigCategory miasma = builder.getOrCreateCategory(Text.literal("Witch's Miasma"));

        miasma.addEntry(entry.startBooleanToggle(Text.literal("Enable Miasma System"), cfg.miasmaEnabled)
            .setDefaultValue(true)
            .setTooltip(Text.literal("When disabled, mobs are not affected by your death count."))
            .setSaveConsumer(v -> cfg.miasmaEnabled = v)
            .build());

        miasma.addEntry(entry.startIntSlider(Text.literal("Max Miasma Level"), cfg.maxMiasmaLevel, 1, 16)
            .setDefaultValue(4)
            .setTooltip(Text.literal("Maximum Miasma level the hidden score can reach."))
            .setSaveConsumer(v -> cfg.maxMiasmaLevel = v)
            .build());

        miasma.addEntry(entry.startBooleanToggle(Text.literal("Enable Miasma Decay"), cfg.miasmaDecay)
            .setDefaultValue(true)
            .setTooltip(Text.literal("If enabled, Miasma decays over time while the player stays alive."))
            .setSaveConsumer(v -> cfg.miasmaDecay = v)
            .build());

        miasma.addEntry(entry.startIntSlider(Text.literal("Decay Rate (in-game days)"), cfg.miasmaDecayDays, 1, 30)
            .setDefaultValue(5)
            .setTooltip(Text.literal("Number of in-game days per 1 level of Miasma decay."))
            .setSaveConsumer(v -> cfg.miasmaDecayDays = v)
            .build());

        // ── Miasma Visuals ───────────────────────────────────────────────────
        ConfigCategory visuals = builder.getOrCreateCategory(Text.literal("Visual Effects"));

        visuals.addEntry(entry.startBooleanToggle(Text.literal("Enable Scent Visual"), cfg.scentVisualEnabled)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Show witch particles and purple vignette after death at high Miasma."))
            .setSaveConsumer(v -> cfg.scentVisualEnabled = v)
            .build());

        // ── Rollback ─────────────────────────────────────────────────────────
        ConfigCategory rollback = builder.getOrCreateCategory(Text.literal("Rollback"));

        rollback.addEntry(entry.startIntSlider(
                Text.literal("Rollback Flash Duration (ticks)"), cfg.rollbackEffectDurationTicks, 5, 100)
            .setDefaultValue(40)
            .setTooltip(Text.literal("Duration of the white flash during rollback (20 ticks = 1 second)."))
            .setSaveConsumer(v -> cfg.rollbackEffectDurationTicks = v)
            .build());

        builder.setSavingRunnable(RbdConfig::save);

        return builder.build();
    }
}
=== src/client/java/com/deist/rbd/client/ModMenuIntegration.java ===
package com.deist.rbd.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu integration — shows the RbD config screen when pressing Configure.
 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return RbdConfigScreen::build;
    }
}
