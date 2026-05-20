package com.deist.rbd.checkpoint;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;
import net.minecraft.world.storage.RegionFile;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Entity snapshot cho Overworld và Nether (KHÔNG bao gồm The End).
 * The End được xử lý riêng bởi EndSnapshot.
 *
 * Restore strategy:
 *   - Entity có trong snapshot + còn sống trong world → readNbt() trực tiếp
 *   - Entity có trong snapshot + KHÔNG còn trong world → spawn mới (bị kill sau CP)
 *   - Entity KHÔNG có trong snapshot + đang trong world → spawned sau CP → discard
 *   - Item / XP / projectile → discard luôn
 *
 * Tracking spawned-after-checkpoint UUIDs:
 *   Khi entity mới spawn sau checkpoint (qua EntitySpawnMixin), UUID được thêm
 *   vào spawnedAfterCheckpoint. Dùng để phân biệt entity "mới spawn" vs entity
 *   "ở unloaded chunk khi CP, nay chunk load lại" — cả hai đều không có trong
 *   iterateEntities() lúc capture nhưng behavior khác nhau khi restore.
 */
public class EntitySnapshot {

    // dim registry key value string → list of entity NBTs
    // Chỉ chứa Overworld và Nether, KHÔNG chứa The End
    private final Map<String, List<NbtCompound>> byDimension = new HashMap<>();

    // UUID của entity spawned SAU KHI checkpoint được set.
    // Static vì cần accessible từ EntitySpawnMixin.
    // Reset mỗi lần CheckpointManager.save() được gọi.
    private static final Set<UUID> spawnedAfterCheckpoint = new HashSet<>();

    // ── Spawned tracking (gọi từ EntitySpawnMixin) ────────────────────────────

    public static void recordSpawned(UUID uuid) {
        spawnedAfterCheckpoint.add(uuid);
    }

    public static void clearSpawnedTracking() {
        spawnedAfterCheckpoint.clear();
    }

    public static boolean wasSpawnedAfterCheckpoint(UUID uuid) {
        return spawnedAfterCheckpoint.contains(uuid);
    }

    public boolean containsUuid(UUID uuid) {
        for (List<NbtCompound> list : byDimension.values()) {
            for (NbtCompound nbt : list) {
                if (nbt.containsUuid("UUID") && nbt.getUuid("UUID").equals(uuid)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void addEntity(Entity entity) {
        String dimId = entity.getWorld().getRegistryKey().getValue().toString();
        List<NbtCompound> list = byDimension.computeIfAbsent(dimId, k -> new ArrayList<>());
        NbtCompound nbt = writeEntity(entity);
        if (nbt != null) {
            list.add(nbt);
        }
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    /**
     * Capture Overworld + Nether entities.
     * The End bị skip — xử lý bởi EndSnapshot.
     */
    public static EntitySnapshot capture(MinecraftServer server) {
        // Blocking flush để đảm bảo disk state sync với memory
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey() == World.END) continue; // skip The End
            world.getChunkManager().save(true);
        }

        EntitySnapshot snap = new EntitySnapshot();

        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey() == World.END) continue; // The End → EndSnapshot

            String dimId = world.getRegistryKey().getValue().toString();
            List<NbtCompound> list = new ArrayList<>();

            // 1. Loaded entities (in memory)
            Set<UUID> loadedUuids = new HashSet<>();
            world.iterateEntities().forEach(entity -> {
                if (shouldSkip(entity)) return;
                NbtCompound nbt = writeEntity(entity);
                if (nbt != null) {
                    list.add(nbt);
                    loadedUuids.add(entity.getUuid());
                }
            });

            // 2. Unloaded entities (from disk) — chỉ lấy UUID chưa có trong loaded
            List<NbtCompound> diskEntities = readFromDisk(server, world);
            for (NbtCompound nbt : diskEntities) {
                if (!nbt.containsUuid("UUID")) continue;
                UUID uuid = nbt.getUuid("UUID");
                if (loadedUuids.contains(uuid)) continue;
                list.add(nbt);
            }

            snap.byDimension.put(dimId, list);
        }

        // Reset spawn tracking sau khi capture
        clearSpawnedTracking();

        return snap;
    }

    // ── Restore ───────────────────────────────────────────────────────────────

    /**
     * Restore Overworld + Nether entities về checkpoint state.
     * The End KHÔNG được xử lý ở đây.
     */
    public void restore(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey() == World.END) continue; // The End → EndSnapshot

            String dimId = world.getRegistryKey().getValue().toString();
            List<NbtCompound> snapshots = byDimension.getOrDefault(dimId, Collections.emptyList());

            // Build UUID → NBT lookup
            Map<UUID, NbtCompound> snapshotByUuid = new HashMap<>();
            for (NbtCompound nbt : snapshots) {
                if (!nbt.containsUuid("UUID")) continue;
                NbtCompound clean = nbt.copy();
                snapshotByUuid.put(nbt.getUuid("UUID"), clean);
            }

            // Step 1 & 2: xử lý entities đang có trong world
            List<Entity> toDiscard = new ArrayList<>();
            world.iterateEntities().forEach(entity -> {
                if (entity instanceof PlayerEntity) return;

                if (entity instanceof ItemEntity
                        || entity instanceof ExperienceOrbEntity
                        || entity instanceof ProjectileEntity) {
                    toDiscard.add(entity);
                    return;
                }

                UUID uuid = entity.getUuid();
                NbtCompound snap = snapshotByUuid.get(uuid);

                if (snap != null) {
                    // Có trong snapshot → restore state trực tiếp
                    try {
                        entity.readNbt(snap);
                    } catch (Exception e) {
                        System.err.println("[RbD] readNbt failed for " + uuid
                                + " (" + entity.getType() + "): " + e.getMessage());
                    }
                    snapshotByUuid.remove(uuid);
                } else {
                    // Không có trong snapshot → spawned sau checkpoint → discard
                    // Nhưng cần phân biệt: entity từ unloaded chunk (valid) vs spawned sau CP (invalid)
                    // spawnedAfterCheckpoint tracking giải quyết điều này:
                    // Entity từ unloaded chunk sẽ có UUID trong disk snapshot,
                    // nên đã được xử lý ở capture() và có trong snapshotByUuid.
                    // Entity thực sự spawned sau CP → không có trong snapshot → discard.
                    toDiscard.add(entity);
                }
            });

            for (Entity entity : toDiscard) {
                entity.setRemoved(Entity.RemovalReason.DISCARDED);
            }

            // Step 3: spawn entities có trong snapshot nhưng không còn trong world
            // (bị kill sau checkpoint, hoặc ở unloaded chunk lúc restore)
            for (Map.Entry<UUID, NbtCompound> entry : snapshotByUuid.entrySet()) {
                try {
                    EntityType.loadEntityWithPassengers(entry.getValue(), world, entity -> {
                        world.spawnEntity(entity);
                        return entity;
                    });
                } catch (Exception e) {
                    System.err.println("[RbD] spawn from snapshot failed for "
                            + entry.getKey() + ": " + e.getMessage());
                }
            }
        }
    }

    // ── NBT serialization ─────────────────────────────────────────────────────

    public void writeNbt(NbtCompound out) {
        NbtCompound dimsTag = new NbtCompound();
        for (Map.Entry<String, List<NbtCompound>> entry : byDimension.entrySet()) {
            NbtList list = new NbtList();
            for (NbtCompound nbt : entry.getValue()) list.add(nbt.copy());
            dimsTag.put(entry.getKey(), list);
        }
        out.put("EntitySnapshot", dimsTag);
    }

    public static EntitySnapshot readNbt(NbtCompound in) {
        EntitySnapshot snap = new EntitySnapshot();
        if (!in.contains("EntitySnapshot")) return snap;
        NbtCompound dimsTag = in.getCompound("EntitySnapshot");
        for (String dimId : dimsTag.getKeys()) {
            NbtList list = dimsTag.getList(dimId, NbtCompound.COMPOUND_TYPE);
            List<NbtCompound> entities = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) entities.add(list.getCompound(i).copy());
            snap.byDimension.put(dimId, entities);
        }
        return snap;
    }

    // ── Disk reader ───────────────────────────────────────────────────────────

    private static List<NbtCompound> readFromDisk(MinecraftServer server, ServerWorld world) {
        List<NbtCompound> result = new ArrayList<>();
        Path entityDir = getEntityDir(server, world);
        if (entityDir == null || !Files.isDirectory(entityDir)) return result;

        try (Stream<Path> files = Files.list(entityDir)) {
            files.filter(p -> p.toString().endsWith(".mca"))
                 .forEach(p -> readRegionFile(p, result));
        } catch (IOException e) {
            System.err.println("[RbD] Failed to list entity region files for "
                    + world.getRegistryKey().getValue() + ": " + e.getMessage());
        }
        return result;
    }

    private static void readRegionFile(Path regionPath, List<NbtCompound> out) {
        try (RegionFile region = new RegionFile(regionPath, regionPath.getParent(), true)) {
            for (int cx = 0; cx < 32; cx++) {
                for (int cz = 0; cz < 32; cz++) {
                    net.minecraft.util.math.ChunkPos pos =
                            new net.minecraft.util.math.ChunkPos(cx, cz);
                    if (!region.isChunkValid(pos)) continue;
                    try (DataInputStream stream = region.getChunkInputStream(pos)) {
                        if (stream == null) continue;
                        NbtCompound chunk = NbtIo.readCompound(stream, NbtSizeTracker.ofUnlimitedBytes());
                        if (!chunk.contains("Entities")) continue;
                        NbtList entities = chunk.getList("Entities", NbtCompound.COMPOUND_TYPE);
                        for (int i = 0; i < entities.size(); i++) {
                            NbtCompound nbt = entities.getCompound(i).copy();
                            if (shouldSkipNbt(nbt)) continue;
                            out.add(nbt);
                        }
                    } catch (IOException e) {
                        System.err.println("[RbD] Skipping corrupt chunk at " + pos
                                + " in " + regionPath.getFileName());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[RbD] Failed to read region file " + regionPath.getFileName());
        }
    }

    private static Path getEntityDir(MinecraftServer server, ServerWorld world) {
        Path saveRoot = server.getSavePath(WorldSavePath.ROOT);
        RegistryKey<World> key = world.getRegistryKey();
        if (key == World.OVERWORLD) return saveRoot.resolve("entities");
        if (key == World.NETHER)    return saveRoot.resolve("DIM-1").resolve("entities");
        if (key == World.END)       return saveRoot.resolve("DIM1").resolve("entities");
        String ns   = key.getValue().getNamespace();
        String path = key.getValue().getPath();
        return saveRoot.resolve("dimensions").resolve(ns).resolve(path).resolve("entities");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean shouldSkip(Entity entity) {
        return entity instanceof PlayerEntity
            || entity instanceof ItemEntity
            || entity instanceof ExperienceOrbEntity
            || entity instanceof ProjectileEntity;
    }

    private static boolean shouldSkipNbt(NbtCompound nbt) {
        String id = nbt.getString("id");
        return id.equals("minecraft:item")
            || id.equals("minecraft:experience_orb")
            || id.equals("minecraft:arrow")
            || id.equals("minecraft:spectral_arrow")
            || id.equals("minecraft:trident")
            || id.equals("minecraft:fireball")
            || id.equals("minecraft:small_fireball")
            || id.equals("minecraft:snowball")
            || id.equals("minecraft:egg")
            || id.equals("minecraft:ender_pearl")
            || id.equals("minecraft:eye_of_ender")
            || id.equals("minecraft:fishing_bobber")
            || id.equals("minecraft:llama_spit")
            || id.equals("minecraft:wither_skull");
    }

    private static NbtCompound writeEntity(Entity entity) {
        try {
            NbtCompound nbt = new NbtCompound();
            entity.writeNbt(nbt);
            nbt.putString("id", EntityType.getId(entity.getType()).toString());
            return nbt;
        } catch (Exception e) {
            System.err.println("[RbD] Failed to serialize entity " + entity.getUuid());
            return null;
        }
    }
}
