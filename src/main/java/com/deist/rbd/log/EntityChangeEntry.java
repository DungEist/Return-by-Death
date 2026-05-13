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
