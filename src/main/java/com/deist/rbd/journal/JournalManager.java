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
