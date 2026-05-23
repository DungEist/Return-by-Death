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
    public final double x, y, z;
    public final BlockPos pos;
    public final float yaw, pitch;
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
    public final boolean raining, thundering;
    public final int rainTime, thunderTime, clearWeatherTime;
    public final NbtCompound enderChestInventory;

    // Entity snapshot (Overworld + Nether)
    public final EntitySnapshot entitySnapshot;
    // Block entity snapshots (containers)
    public final List<NbtCompound> blockEntitySnapshots;
    // The End snapshot (entry/exit logic)
    public final EndSnapshot endSnapshot;

    public CheckpointData(
            double x, double y, double z, float yaw, float pitch,
            float health, int hunger, float saturation,
            int experienceLevel, float experienceProgress, int sculkWarningLevel,
            int loopNumber, long worldTime, String dimension, long timestamp,
            DefaultedList<ItemStack> inventory,
            Collection<StatusEffectInstance> effects,
            boolean raining, boolean thundering,
            int rainTime, int thunderTime, int clearWeatherTime,
            EntitySnapshot entitySnapshot,
            List<NbtCompound> blockEntitySnapshots,
            NbtCompound enderChestInventory,
            EndSnapshot endSnapshot) {

        this.x = x; this.y = y; this.z = z;
        this.pos = new BlockPos((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z));
        this.yaw = yaw; this.pitch = pitch;
        this.health = health; this.hunger = hunger; this.saturation = saturation;
        this.experienceLevel = experienceLevel;
        this.experienceProgress = experienceProgress;
        this.sculkWarningLevel = sculkWarningLevel;
        this.loopNumber = loopNumber; this.worldTime = worldTime;
        this.dimension = dimension; this.timestamp = timestamp;
        this.inventory = inventory;
        this.statusEffects = new ArrayList<>();
        for (StatusEffectInstance e : effects) {
            NbtCompound n = new NbtCompound(); e.writeNbt(n);
            this.statusEffects.add(n);
        }
        this.raining = raining; this.thundering = thundering;
        this.rainTime = rainTime; this.thunderTime = thunderTime;
        this.clearWeatherTime = clearWeatherTime;
        this.entitySnapshot   = entitySnapshot   != null ? entitySnapshot   : new EntitySnapshot();
        this.blockEntitySnapshots = blockEntitySnapshots != null ? blockEntitySnapshots : new ArrayList<>();
        this.enderChestInventory  = enderChestInventory  != null ? enderChestInventory  : new NbtCompound();
        this.endSnapshot      = endSnapshot      != null ? endSnapshot      : new EndSnapshot();
    }

    // Private constructor for readNbt
    private CheckpointData(
            double x, double y, double z, BlockPos pos, float yaw, float pitch,
            float health, int hunger, float saturation,
            int experienceLevel, float experienceProgress, int sculkWarningLevel,
            int loopNumber, long worldTime, String dimension, long timestamp,
            DefaultedList<ItemStack> inventory, List<NbtCompound> statusEffects,
            boolean raining, boolean thundering,
            int rainTime, int thunderTime, int clearWeatherTime,
            EntitySnapshot entitySnapshot,
            List<NbtCompound> blockEntitySnapshots,
            NbtCompound enderChestInventory,
            EndSnapshot endSnapshot) {

        this.x = x; this.y = y; this.z = z;
        this.pos = pos; this.yaw = yaw; this.pitch = pitch;
        this.health = health; this.hunger = hunger; this.saturation = saturation;
        this.experienceLevel = experienceLevel;
        this.experienceProgress = experienceProgress;
        this.sculkWarningLevel = sculkWarningLevel;
        this.loopNumber = loopNumber; this.worldTime = worldTime;
        this.dimension = dimension; this.timestamp = timestamp;
        this.inventory = inventory; this.statusEffects = statusEffects;
        this.raining = raining; this.thundering = thundering;
        this.rainTime = rainTime; this.thunderTime = thunderTime;
        this.clearWeatherTime = clearWeatherTime;
        this.entitySnapshot   = entitySnapshot   != null ? entitySnapshot   : new EntitySnapshot();
        this.blockEntitySnapshots = blockEntitySnapshots != null ? blockEntitySnapshots : new ArrayList<>();
        this.enderChestInventory  = enderChestInventory  != null ? enderChestInventory  : new NbtCompound();
        this.endSnapshot      = endSnapshot      != null ? endSnapshot      : new EndSnapshot();
    }

    public void writeNbt(NbtCompound nbt) {
        nbt.putDouble("PosXDouble", x);
        nbt.putDouble("PosYDouble", y);
        nbt.putDouble("PosZDouble", z);
        nbt.putInt("PosX", pos.getX());
        nbt.putInt("PosY", pos.getY());
        nbt.putInt("PosZ", pos.getZ());
        nbt.putFloat("Yaw", yaw); nbt.putFloat("Pitch", pitch);
        nbt.putFloat("Health", health);
        nbt.putInt("Hunger", hunger);
        nbt.putFloat("Saturation", saturation);
        nbt.putInt("ExperienceLevel", experienceLevel);
        nbt.putFloat("ExperienceProgress", experienceProgress);
        nbt.putInt("SculkWarningLevel", sculkWarningLevel);
        nbt.putInt("LoopNumber", loopNumber);
        nbt.putLong("WorldTime", worldTime);
        nbt.putString("Dimension", dimension);
        nbt.putLong("Timestamp", timestamp);

        NbtList invList = new NbtList();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.get(i);
            if (!stack.isEmpty()) {
                NbtCompound t = new NbtCompound();
                t.putByte("Slot", (byte) i);
                stack.writeNbt(t);
                invList.add(t);
            }
        }
        nbt.put("Inventory", invList);
        nbt.put("StatusEffects", copyList(statusEffects));
        nbt.putBoolean("Raining", raining);
        nbt.putBoolean("Thundering", thundering);
        nbt.putInt("RainTime", rainTime);
        nbt.putInt("ThunderTime", thunderTime);
        nbt.putInt("ClearWeatherTime", clearWeatherTime);
        nbt.put("BlockEntitySnapshots", copyList(blockEntitySnapshots));
        if (!enderChestInventory.isEmpty())
            nbt.put("EnderChestInventory", enderChestInventory.copy());

        entitySnapshot.writeNbt(nbt);
        endSnapshot.writeNbt(nbt);
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
        double x = nbt.contains("PosXDouble") ? nbt.getDouble("PosXDouble") : nbt.getInt("PosX") + 0.5;
        double y = nbt.contains("PosYDouble") ? nbt.getDouble("PosYDouble") : nbt.getInt("PosY");
        double z = nbt.contains("PosZDouble") ? nbt.getDouble("PosZDouble") : nbt.getInt("PosZ") + 0.5;
        BlockPos pos = new BlockPos(
                nbt.getInt("PosX"), nbt.getInt("PosY"), nbt.getInt("PosZ"));
        DefaultedList<ItemStack> inv = DefaultedList.ofSize(41, ItemStack.EMPTY);
        NbtList invList = nbt.getList("Inventory", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < invList.size(); i++) {
            NbtCompound t = invList.getCompound(i);
            int slot = t.getByte("Slot") & 255;
            if (slot >= 0 && slot < inv.size())
                inv.set(slot, ItemStack.fromNbt(t));
        }
        String dim = nbt.getString("Dimension");
        if (dim.isEmpty()) dim = "minecraft:overworld";

        return new CheckpointData(
                x, y, z, pos, nbt.getFloat("Yaw"), nbt.getFloat("Pitch"),
                nbt.getFloat("Health"), nbt.getInt("Hunger"),
                nbt.getFloat("Saturation"),
                nbt.getInt("ExperienceLevel"), nbt.getFloat("ExperienceProgress"),
                nbt.getInt("SculkWarningLevel"),
                nbt.getInt("LoopNumber"), nbt.getLong("WorldTime"),
                dim, nbt.getLong("Timestamp"),
                inv, readList(nbt, "StatusEffects"),
                nbt.getBoolean("Raining"), nbt.getBoolean("Thundering"),
                nbt.getInt("RainTime"), nbt.getInt("ThunderTime"),
                nbt.getInt("ClearWeatherTime"),
                EntitySnapshot.readNbt(nbt),
                readList(nbt, "BlockEntitySnapshots"),
                nbt.contains("EnderChestInventory")
                        ? nbt.getCompound("EnderChestInventory") : new NbtCompound(),
                EndSnapshot.readNbt(nbt)
        );
    }
}
