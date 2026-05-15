package com.deist.rbd.checkpoint;

import com.deist.rbd.log.RbdChangeLog;
import com.deist.rbd.mixin.ChunkStorageAccessor;
import net.minecraft.block.entity.BlockEntity;
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
import net.minecraft.world.level.ServerWorldProperties;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CheckpointManager {
    private static CheckpointData currentCheckpoint = null;

    public static void save(ServerPlayerEntity player, ServerWorld world) {
        int loopNumber = currentCheckpoint != null ? currentCheckpoint.loopNumber : 0;

        // ── Player inventory ───────────────────────────────────────────────
        DefaultedList<ItemStack> inventoryCopy = DefaultedList.ofSize(player.getInventory().size(), ItemStack.EMPTY);
        for (int i = 0; i < player.getInventory().size(); i++) {
            inventoryCopy.set(i, player.getInventory().getStack(i).copy());
        }

        // ── Ender chest ────────────────────────────────────────────────────
        NbtCompound enderChestNbt = new NbtCompound();
        enderChestNbt.put("Items", player.getEnderChestInventory().toNbtList());

        // ── Weather ────────────────────────────────────────────────────────
        ServerWorld overworld = world.getServer().getOverworld();
        ServerWorldProperties wProps = (ServerWorldProperties) overworld.getLevelProperties();

        // ── Entity snapshot (loaded + unloaded chunks via disk) ───────────
        // This replaces the old iterateEntities() + itemSnapshots approach.
        // EntitySnapshot.capture() handles both loaded (memory) and unloaded (disk) chunks.
        EntitySnapshot entitySnapshot = EntitySnapshot.capture(world.getServer());

        // ── Block entity snapshots (containers) ───────────────────────────
        List<NbtCompound> beSnapshots = new ArrayList<>();
        world.getServer().getWorlds().forEach(sw -> {
            try {
                ChunkStorageAccessor accessor = (ChunkStorageAccessor) sw.getChunkManager().threadedAnvilChunkStorage;
                for (ChunkHolder holder : accessor.invokeEntryIterator()) {
                    net.minecraft.world.chunk.WorldChunk chunk = holder.getWorldChunk();
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

        // ── Sculk ──────────────────────────────────────────────────────────
        int sculkLevel = player.getSculkShriekerWarningManager().isPresent()
            ? player.getSculkShriekerWarningManager().get().getWarningLevel() : 0;

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
            entitySnapshot, beSnapshots, enderChestNbt
        );

        // ── Clear ALL dimension block logs ─────────────────────────────────
        world.getServer().getWorlds().forEach(sw -> RbdChangeLog.get(sw).clear());

        // ── Persist to disk ────────────────────────────────────────────────
        File dir = getSaveDirectory(world);
        if (!dir.exists()) dir.mkdirs();
        NbtCompound nbt = new NbtCompound();
        currentCheckpoint.writeNbt(nbt);
        try {
            NbtIo.writeCompressed(nbt, new File(dir, "checkpoint.nbt").toPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static CheckpointData load(ServerWorld world) {
        if (currentCheckpoint != null) return currentCheckpoint;
        File file = new File(getSaveDirectory(world), "checkpoint.nbt");
        if (!file.exists()) return null;
        try {
            currentCheckpoint = CheckpointData.readNbt(
                NbtIo.readCompressed(file.toPath(), NbtSizeTracker.ofUnlimitedBytes()));
            return currentCheckpoint;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void clear(ServerWorld world) {
        currentCheckpoint = null;
        File f = new File(getSaveDirectory(world), "checkpoint.nbt");
        if (f.exists()) f.delete();
        world.getServer().getWorlds().forEach(sw -> RbdChangeLog.get(sw).clear());
    }

    public static void clearInMemory() {
        currentCheckpoint = null;
    }

    private static File getSaveDirectory(ServerWorld world) {
        return new File(world.getServer().getSavePath(WorldSavePath.ROOT).toFile(), "rbd");
    }
}
