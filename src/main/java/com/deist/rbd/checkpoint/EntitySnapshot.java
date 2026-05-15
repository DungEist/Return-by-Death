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
 * Full entity snapshot across ALL dimensions at checkpoint time.
 *
 * Strategy:
 *   - Loaded chunks  → iterateEntities() (fast, already in memory)
 *   - Unloaded chunks → read entity region files from disk (.mca in entities/ folder)
 *
 * Replaces: RbdEntityLog, bossSnapshots, RbdPendingDeletions
 *
 * Restore strategy (3 steps per dimension):
 *   1. For entities currently alive in world:
 *      - In snapshot  → readNbt() trực tiếp để restore state (tránh UUID conflict)
 *      - Not in snapshot → spawned after checkpoint → discard
 *   2. Item / XP / projectile → discard luôn (không restore)
 *   3. Entities có trong snapshot nhưng không còn trong world
 *      (bị kill sau checkpoint, hoặc ở unloaded chunk) → spawn mới từ NBT
 *
 * Không dùng "discard all rồi spawn lại" vì chunk loading tự reload entity
 * từ disk ngay sau discard → UUID conflict → Minecraft reject spawn mới.
 * Ngoài ra discard() trong tick loop gây NPE (crash Bee entity trong log).
 */
public class EntitySnapshot {

    // dim registry key value string → list of entity NBTs
    private final Map<String, List<NbtCompound>> byDimension = new HashMap<>();

    // ── Capture ───────────────────────────────────────────────────────────────

    /**
     * Build a full snapshot. Call this when the player sets a checkpoint.
     * Captures loaded entities from memory, then fills unloaded chunks from disk.
     *
     * save(true) blocks until all dirty chunks are flushed to disk, ensuring
     * entity state on disk is current before readFromDisk() runs.
     */
    public static EntitySnapshot capture(MinecraftServer server) {
        // Blocking flush: đảm bảo disk state sync với memory trước khi đọc.
        // Cần blocking (true) vì save(false) là async — readFromDisk() có thể
        // chạy trước khi flush hoàn thành, dẫn đến đọc state cũ từ disk.
        for (ServerWorld world : server.getWorlds()) {
            world.getChunkManager().save(true);
        }

        EntitySnapshot snap = new EntitySnapshot();

        for (ServerWorld world : server.getWorlds()) {
            String dimId = world.getRegistryKey().getValue().toString();
            List<NbtCompound> list = new ArrayList<>();

            // ── 1. Loaded entities (in memory) — source of truth ──────────────
            Set<UUID> loadedUuids = new HashSet<>();
            world.iterateEntities().forEach(entity -> {
                if (shouldSkip(entity)) return;
                NbtCompound nbt = writeEntity(entity);
                if (nbt != null) {
                    list.add(nbt);
                    loadedUuids.add(entity.getUuid());
                }
            });

            // ── 2. Unloaded entities (from disk) ──────────────────────────────
            // Chỉ lấy entity không có trong loaded set để tránh duplicate.
            List<NbtCompound> diskEntities = readFromDisk(server, world);
            for (NbtCompound nbt : diskEntities) {
                if (!nbt.containsUuid("UUID")) continue;
                UUID uuid = nbt.getUuid("UUID");
                if (loadedUuids.contains(uuid)) continue;
                list.add(nbt);
            }

            snap.byDimension.put(dimId, list);
        }

        return snap;
    }

    // ── Rollback ──────────────────────────────────────────────────────────────

    /**
     * Restore all entities to their checkpoint state across all dimensions.
     *
     * Không dùng "discard all → spawn lại" vì:
     *   - Entity::discard() trong khi server đang tick có thể gây NPE (crash Bee/mob)
     *     khi entity bị removed giữa chừng nhưng vẫn còn trong tick queue.
     *   - Chunk loading tự reload entity từ disk ngay sau discard,
     *     dẫn đến UUID conflict khi cố spawn entity từ snapshot.
     *
     * Thay vào đó: update NBT trực tiếp cho entity đang sống, chỉ spawn mới
     * khi entity không còn tồn tại trong world.
     */
    public void restore(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            String dimId = world.getRegistryKey().getValue().toString();
            List<NbtCompound> snapshots = byDimension.getOrDefault(dimId, Collections.emptyList());

            // Build UUID → NBT lookup từ snapshot
            Map<UUID, NbtCompound> snapshotByUuid = new HashMap<>();
            for (NbtCompound nbt : snapshots) {
                if (!nbt.containsUuid("UUID")) continue;
                NbtCompound clean = nbt.copy();
                clean.remove("RbdDim");
                snapshotByUuid.put(nbt.getUuid("UUID"), clean);
            }

            // ── Step 1 & 2: xử lý entities hiện đang có trong world ───────────
            List<Entity> toDiscard = new ArrayList<>();
            world.iterateEntities().forEach(entity -> {
                if (entity instanceof PlayerEntity) return;

                // Item / XP / projectile: xóa hết, không restore
                if (entity instanceof ItemEntity
                        || entity instanceof ExperienceOrbEntity
                        || entity instanceof ProjectileEntity) {
                    toDiscard.add(entity);
                    return;
                }

                UUID uuid = entity.getUuid();
                NbtCompound snap = snapshotByUuid.get(uuid);

                if (snap != null) {
                    // Entity có trong snapshot: restore state trực tiếp qua readNbt.
                    // Không discard + respawn để tránh UUID conflict và tick-queue NPE.
                    try {
                        entity.readNbt(snap);
                    } catch (Exception e) {
                        System.err.println("[RbD] Failed to readNbt for entity "
                                + uuid + " (" + entity.getType().toString() + "): " + e.getMessage());
                    }
                    // Đánh dấu đã xử lý — không cần spawn lại ở step 3
                    snapshotByUuid.remove(uuid);
                } else {
                    // Entity không có trong snapshot: spawned sau checkpoint → xóa
                    toDiscard.add(entity);
                }
            });

            // Discard entities cần xóa.
            // Dùng setRemoved thay vì discard() để tránh trigger side effects
            // (drop item, death event, v.v.) trong khi server đang tick.
            for (Entity entity : toDiscard) {
                entity.setRemoved(Entity.RemovalReason.DISCARDED);
            }

            // ── Step 3: spawn entities còn lại trong snapshot ─────────────────
            // Đây là entity có trong checkpoint nhưng không còn trong world:
            //   - Bị kill sau checkpoint
            //   - Ở unloaded chunk (không có trong iterateEntities())
            for (Map.Entry<UUID, NbtCompound> entry : snapshotByUuid.entrySet()) {
                NbtCompound nbt = entry.getValue();
                try {
                    EntityType.loadEntityWithPassengers(nbt, world, entity -> {
                        world.spawnEntity(entity);
                        return entity;
                    });
                } catch (Exception e) {
                    System.err.println("[RbD] Failed to spawn entity from snapshot "
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

    /**
     * Read entity NBT from the entities/ region files for this world dimension.
     * Minecraft 1.17+ stores entity data separately from chunk data.
     * Format: entities/r.X.Z.mca, each chunk section contains a list of entity NBTs.
     */
    private static List<NbtCompound> readFromDisk(MinecraftServer server, ServerWorld world) {
        List<NbtCompound> result = new ArrayList<>();

        Path entityDir = getEntityDir(server, world);
        if (entityDir == null || !Files.isDirectory(entityDir)) return result;

        try (Stream<Path> files = Files.list(entityDir)) {
            files.filter(p -> p.toString().endsWith(".mca")).forEach(regionPath -> {
                readRegionFile(regionPath, result);
            });
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
                    net.minecraft.util.math.ChunkPos chunkPos =
                            new net.minecraft.util.math.ChunkPos(cx, cz);
                    if (!region.isChunkValid(chunkPos)) continue;

                    try (DataInputStream stream = region.getChunkInputStream(chunkPos)) {
                        if (stream == null) continue;
                        NbtCompound chunkNbt = NbtIo.readCompound(stream, NbtSizeTracker.ofUnlimitedBytes());
                        if (!chunkNbt.contains("Entities")) continue;
                        NbtList entities = chunkNbt.getList("Entities", NbtCompound.COMPOUND_TYPE);
                        for (int i = 0; i < entities.size(); i++) {
                            NbtCompound entityNbt = entities.getCompound(i).copy();
                            if (shouldSkipNbt(entityNbt)) continue;
                            out.add(entityNbt);
                        }
                    } catch (IOException e) {
                        System.err.println("[RbD] Skipping corrupt entity chunk at "
                                + chunkPos + " in " + regionPath.getFileName() + ": " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[RbD] Failed to read entity region file "
                    + regionPath.getFileName() + ": " + e.getMessage());
        }
    }

    /**
     * Resolve the entities/ directory for a given world dimension.
     * Overworld:  <save>/entities/
     * Nether:     <save>/DIM-1/entities/
     * The End:    <save>/DIM1/entities/
     * Custom dim: <save>/dimensions/<namespace>/<path>/entities/
     */
    private static Path getEntityDir(MinecraftServer server, ServerWorld world) {
        Path saveRoot = server.getSavePath(WorldSavePath.ROOT);
        RegistryKey<World> key = world.getRegistryKey();

        if (key == World.OVERWORLD) {
            return saveRoot.resolve("entities");
        } else if (key == World.NETHER) {
            return saveRoot.resolve("DIM-1").resolve("entities");
        } else if (key == World.END) {
            return saveRoot.resolve("DIM1").resolve("entities");
        } else {
            String namespace = key.getValue().getNamespace();
            String path      = key.getValue().getPath();
            return saveRoot.resolve("dimensions").resolve(namespace).resolve(path).resolve("entities");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean shouldSkip(Entity entity) {
        if (entity instanceof PlayerEntity)        return true;
        if (entity instanceof ItemEntity)          return true;
        if (entity instanceof ExperienceOrbEntity) return true;
        if (entity instanceof ProjectileEntity)    return true;
        return false;
    }

    /** Same filter but for raw NBT (used when reading from disk). */
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
            System.err.println("[RbD] Failed to serialize entity "
                    + entity.getUuid() + ": " + e.getMessage());
            return null;
        }
    }
}
