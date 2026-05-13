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
