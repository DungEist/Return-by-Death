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
import java.io.File;
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
 * Simplifies rollback to: discard all → spawn from snapshot.
 */
public class EntitySnapshot {

    // dim registry key value string → list of entity NBTs
    private final Map<String, List<NbtCompound>> byDimension = new HashMap<>();

    // ── Capture ───────────────────────────────────────────────────────────────

    /**
     * Build a full snapshot. Call this when the player sets a checkpoint.
     * Captures loaded entities from memory, then fills unloaded chunks from disk.
     */
    public static EntitySnapshot capture(MinecraftServer server) {
        // Force-flush tất cả loaded chunks xuống disk trước khi đọc region files.
        // Đảm bảo entity state trên disk sync với memory (health, position, v.v.)
        // save(false) = async flush, không block server thread.
        for (ServerWorld world : server.getWorlds()) {
            world.getChunkManager().save(false);
        }

        EntitySnapshot snap = new EntitySnapshot();

        for (ServerWorld world : server.getWorlds()) {
            String dimId = world.getRegistryKey().getValue().toString();
            List<NbtCompound> list = new ArrayList<>();

            // ── 1. Loaded entities (in memory) ────────────────────────────────
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
            List<NbtCompound> diskEntities = readFromDisk(server, world);
            for (NbtCompound nbt : diskEntities) {
                if (!nbt.containsUuid("UUID")) continue;
                UUID uuid = nbt.getUuid("UUID");
                if (loadedUuids.contains(uuid)) continue; // already captured from memory
                list.add(nbt);
            }

            snap.byDimension.put(dimId, list);
        }

        return snap;
    }

    // ── Rollback ──────────────────────────────────────────────────────────────

    /**
     * Restore all entities to their checkpoint state.
     * Steps:
     *   1. Discard every non-player entity currently in world (all dims)
     *   2. Discard all item entities and XP orbs
     *   3. Spawn entities from snapshot
     */
    public void restore(MinecraftServer server) {
        // Step 1 & 2: clear all non-player entities
        for (ServerWorld world : server.getWorlds()) {
            List<Entity> toDiscard = new ArrayList<>();
            world.iterateEntities().forEach(entity -> {
                if (entity instanceof PlayerEntity) return;
                toDiscard.add(entity);
            });
            toDiscard.forEach(Entity::discard);
        }

        // Step 3: spawn from snapshot
        for (ServerWorld world : server.getWorlds()) {
            String dimId = world.getRegistryKey().getValue().toString();
            List<NbtCompound> list = byDimension.getOrDefault(dimId, Collections.emptyList());

            for (NbtCompound nbt : list) {
                NbtCompound clean = nbt.copy();
                clean.remove("RbdDim"); // strip our metadata tag
                EntityType.loadEntityWithPassengers(clean, world, entity -> {
                    world.spawnEntity(entity);
                    return entity;
                });
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

        // entities/ folder lives inside the dimension's save folder
        Path entityDir = getEntityDir(server, world);
        if (entityDir == null || !Files.isDirectory(entityDir)) return result;

        // iterate all .mca region files
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
            // RegionFile covers a 32x32 chunk grid
            for (int cx = 0; cx < 32; cx++) {
                for (int cz = 0; cz < 32; cz++) {
                    net.minecraft.util.math.ChunkPos chunkPos =
                        new net.minecraft.util.math.ChunkPos(cx, cz);
                    if (!region.isChunkValid(chunkPos)) continue;

                    try (DataInputStream stream = region.getChunkInputStream(chunkPos)) {
                        if (stream == null) continue;
                        NbtCompound chunkNbt = NbtIo.readCompound(stream, NbtSizeTracker.ofUnlimitedBytes());
                        // Entity region chunk format: { "Entities": [list of entity NBTs] }
                        if (!chunkNbt.contains("Entities")) continue;
                        NbtList entities = chunkNbt.getList("Entities", NbtCompound.COMPOUND_TYPE);
                        for (int i = 0; i < entities.size(); i++) {
                            NbtCompound entityNbt = entities.getCompound(i).copy();
                            if (shouldSkipNbt(entityNbt)) continue;
                            out.add(entityNbt);
                        }
                    } catch (IOException e) {
                        // Skip corrupt chunk, don't crash
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
            // Custom dimensions: dimensions/<namespace>/<path>/entities/
            String namespace = key.getValue().getNamespace();
            String path      = key.getValue().getPath();
            return saveRoot.resolve("dimensions").resolve(namespace).resolve(path).resolve("entities");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean shouldSkip(Entity entity) {
        if (entity instanceof PlayerEntity)      return true;
        if (entity instanceof ItemEntity)        return true;
        if (entity instanceof ExperienceOrbEntity) return true;
        if (entity instanceof ProjectileEntity)  return true;
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
