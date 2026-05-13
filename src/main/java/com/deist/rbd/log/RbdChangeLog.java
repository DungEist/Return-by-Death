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
