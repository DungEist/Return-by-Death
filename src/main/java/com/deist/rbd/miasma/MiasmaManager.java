package com.deist.rbd.miasma;

import com.deist.rbd.core.RbdConfig;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the Witch's Miasma per-player data.
 * Stored via PersistentState → survives world reload, independent of player NBT.
 *
 * Data per player:
 *   - miasmaLevel     : 0..MAX, derived from effectiveDeaths, caps at maxMiasmaLevel
 *   - effectiveDeaths : hidden score — increases on death, ALSO decays every N days
 *                       so that one more death after decay doesn't jump back to MAX instantly
 *   - totalDeaths     : read-only lifetime counter, used for journal display only
 *   - lastGameDay     : last in-game day when decay was checked
 */
public class MiasmaManager extends PersistentState {

    private static final String DATA_KEY = "rbd_miasma";

    private final Map<UUID, Integer> miasmaLevels     = new HashMap<>();
    private final Map<UUID, Integer> effectiveDeaths  = new HashMap<>(); // hidden score, decays
    private final Map<UUID, Integer> totalDeaths      = new HashMap<>(); // journal only, never decays
    private final Map<UUID, Long>    lastGameDay      = new HashMap<>();

    // ── Level table based on effectiveDeaths ───────────────────────────────
    public static int computeLevel(int eff) {
        if (eff == 0)       return 0;
        if (eff <= 3)       return 1;
        if (eff <= 8)       return 2;
        if (eff <= 15)      return 3;
        return Math.min(eff / 4, RbdConfig.get().maxMiasmaLevel);
    }

    // ── Accessors ──────────────────────────────────────────────────────────
    public int getMiasmaLevel(UUID uuid) {
        return miasmaLevels.getOrDefault(uuid, 0);
    }

    public int getEffectiveDeaths(UUID uuid) {
        return effectiveDeaths.getOrDefault(uuid, 0);
    }

    public int getTotalDeaths(UUID uuid) {
        return totalDeaths.getOrDefault(uuid, 0);
    }

    /** Called each time the player dies. Returns new miasma level. */
    public int onDeath(UUID uuid) {
        // Increment both counters
        int eff    = effectiveDeaths.merge(uuid, 1, Integer::sum);
        totalDeaths.merge(uuid, 1, Integer::sum);
        int level  = Math.min(computeLevel(eff), RbdConfig.get().maxMiasmaLevel);
        miasmaLevels.put(uuid, level);
        markDirty();
        return level;
    }

    /**
     * Tick decay: call once per second.
     * Reduces effectiveDeaths by 1 every N in-game days, then recomputes level.
     * Because effectiveDeaths decays, one more death won't jump straight back to MAX.
     */
    public boolean tickDecay(ServerPlayerEntity player, long currentWorldDay) {
        if (!RbdConfig.get().miasmaDecay) return false;
        UUID uuid = player.getUuid();
        int eff = effectiveDeaths.getOrDefault(uuid, 0);
        if (eff <= 0) return false;

        long last = lastGameDay.getOrDefault(uuid, currentWorldDay);
        long daysPassed = currentWorldDay - last;

        if (daysPassed >= RbdConfig.get().miasmaDecayDays) {
            int steps  = (int)(daysPassed / RbdConfig.get().miasmaDecayDays);
            int newEff = Math.max(0, eff - steps);
            effectiveDeaths.put(uuid, newEff);
            int newLevel = Math.min(computeLevel(newEff), RbdConfig.get().maxMiasmaLevel);
            int oldLevel = miasmaLevels.getOrDefault(uuid, 0);
            miasmaLevels.put(uuid, newLevel);
            lastGameDay.put(uuid, currentWorldDay - (daysPassed % RbdConfig.get().miasmaDecayDays));
            markDirty();
            return newLevel != oldLevel; // return true only if level actually changed
        } else {
            if (!lastGameDay.containsKey(uuid)) {
                lastGameDay.put(uuid, currentWorldDay);
                markDirty();
            }
        }
        return false;
    }

    /** Detection bonus in blocks added to mob follow range. */
    public static int getDetectionBonus(int level) {
        return switch (level) {
            case 1  -> 9;
            case 2  -> 18;
            case 3  -> 36;
            default -> level >= 4 ? 72 : 0;
        };
    }

    // ── PersistentState NBT ───────────────────────────────────────────────
    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtCompound levels  = new NbtCompound();
        NbtCompound effMap  = new NbtCompound();
        NbtCompound totMap  = new NbtCompound();
        NbtCompound daysMap = new NbtCompound();
        miasmaLevels.forEach((uuid, v)    -> levels.putInt(uuid.toString(), v));
        effectiveDeaths.forEach((uuid, v) -> effMap.putInt(uuid.toString(), v));
        totalDeaths.forEach((uuid, v)     -> totMap.putInt(uuid.toString(), v));
        lastGameDay.forEach((uuid, v)     -> daysMap.putLong(uuid.toString(), v));
        nbt.put("MiasmaLevels",    levels);
        nbt.put("EffectiveDeaths", effMap);
        nbt.put("TotalDeaths",     totMap);
        nbt.put("LastGameDay",     daysMap);
        return nbt;
    }

    public static MiasmaManager fromNbt(NbtCompound nbt) {
        MiasmaManager m = new MiasmaManager();
        NbtCompound levels  = nbt.getCompound("MiasmaLevels");
        NbtCompound effMap  = nbt.getCompound("EffectiveDeaths");
        NbtCompound totMap  = nbt.getCompound("TotalDeaths");
        NbtCompound daysMap = nbt.getCompound("LastGameDay");
        for (String k : levels.getKeys())  m.miasmaLevels.put(UUID.fromString(k), levels.getInt(k));
        for (String k : effMap.getKeys())  m.effectiveDeaths.put(UUID.fromString(k), effMap.getInt(k));
        for (String k : totMap.getKeys())  m.totalDeaths.put(UUID.fromString(k), totMap.getInt(k));
        for (String k : daysMap.getKeys()) m.lastGameDay.put(UUID.fromString(k), daysMap.getLong(k));
        // Back-compat: if old save has TotalDeaths but no EffectiveDeaths, migrate
        if (effMap.isEmpty() && !totMap.isEmpty()) {
            for (String k : totMap.getKeys()) m.effectiveDeaths.put(UUID.fromString(k), totMap.getInt(k));
        }
        return m;
    }

    // ── Singleton accessor ─────────────────────────────────────────────────
    public static MiasmaManager get(MinecraftServer server) {
        PersistentStateManager psm = server.getOverworld().getPersistentStateManager();
        return psm.getOrCreate(
            new PersistentState.Type<>(MiasmaManager::new, MiasmaManager::fromNbt, null),
            DATA_KEY
        );
    }
}
