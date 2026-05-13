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
