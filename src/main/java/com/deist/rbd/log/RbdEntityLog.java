package com.deist.rbd.log;

import com.deist.rbd.core.RbdStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.util.*;

public class RbdEntityLog {
    private static final Map<RegistryKey<World>, RbdEntityLog> instances = new HashMap<>();

    private final List<EntityChangeEntry> entries = new ArrayList<>();
    private final Map<UUID, NbtCompound> bossSnapshots = new HashMap<>();
    private final int maxEntries = 5000;

    public static RbdEntityLog get(ServerWorld world) {
        return instances.computeIfAbsent(world.getRegistryKey(), k -> new RbdEntityLog());
    }

    public static Set<RegistryKey<World>> getAllTrackedDimensions() {
        return instances.keySet();
    }

    /** Restore entities that were KILLED after checkpoint but were NOT in the checkpoint snapshot.
     *  (These are entities that existed but weren't snapshotted — e.g. in unloaded chunks; the
     *  snapshot already handles the ones that WERE captured.) */
    public void restoreKilled(ServerWorld world, Set<UUID> checkpointUuids) {
        for (EntityChangeEntry e : entries) {
            if (e.type != EntityEntryType.KILLED) continue;
            if (checkpointUuids.contains(e.uuid)) continue; // already restored via snapshot
            if (e.fullNbt == null) continue;
            Entity existing = getByUuid(world, e.uuid);
            if (existing != null) existing.discard();
            EntityType.loadEntityWithPassengers(e.fullNbt, world, entity -> {
                world.spawnEntity(entity);
                return entity;
            });
        }
    }

    public void recordKilled(UUID uuid, NbtCompound nbt, long tick) {
        if (entries.size() >= maxEntries) truncate();
        entries.add(new EntityChangeEntry(uuid, EntityEntryType.KILLED, nbt.copy(), null, tick));
    }

    public void recordSpawned(UUID uuid, long tick) {
        if (entries.size() >= maxEntries) truncate();
        entries.add(new EntityChangeEntry(uuid, EntityEntryType.SPAWNED, null, null, tick));
    }

    public Set<UUID> getSpawnedUuids() {
        Set<UUID> uuids = new HashSet<>();
        for (EntityChangeEntry e : entries) {
            if (e.type == EntityEntryType.SPAWNED) {
                uuids.add(e.uuid);
            }
        }
        return uuids;
    }

    public void updateBossSnapshot(UUID uuid, NbtCompound snap, long tick) {
        if (!bossSnapshots.containsKey(uuid)) {
            bossSnapshots.put(uuid, snap.copy());
        }
    }

    public void rollback(ServerWorld world) {
        RbdStateManager.setRollingBack(true);
        try {
            // FIRST: Build a set of UUIDs that were SPAWNED after checkpoint.
            // These entities should NOT exist after rollback, period.
            Set<UUID> spawnedAfterCheckpoint = new HashSet<>();
            for (EntityChangeEntry e : entries) {
                if (e.type == EntityEntryType.SPAWNED) {
                    spawnedAfterCheckpoint.add(e.uuid);
                }
            }

            Set<UUID> processedUuids = new HashSet<>();

            ListIterator<EntityChangeEntry> it = entries.listIterator(entries.size());
            while (it.hasPrevious()) {
                EntityChangeEntry e = it.previous();

                if (processedUuids.contains(e.uuid)) continue;
                processedUuids.add(e.uuid);

                switch (e.type) {
                    case KILLED -> {
                        // If this entity was ALSO spawned after checkpoint,
                        // it should NOT exist after rollback. Just remove it.
                        if (spawnedAfterCheckpoint.contains(e.uuid)) {
                            Entity existing = getByUuid(world, e.uuid);
                            if (existing != null) existing.discard();
                        } else {
                            // Entity existed before checkpoint and was killed → respawn it
                            Entity existing = getByUuid(world, e.uuid);
                            if (existing != null) existing.discard();
                            EntityType.loadEntityWithPassengers(e.fullNbt, world, entity -> {
                                world.spawnEntity(entity);
                                return entity;
                            });
                        }
                    }
                    case SPAWNED -> {
                        // Entity spawned after checkpoint → remove it
                        Entity toRemove = getByUuid(world, e.uuid);
                        if (toRemove != null) toRemove.discard();
                    }
                    case MUTATED -> {
                        // Handled below via bossSnapshots
                    }
                }
            }

            // Restore bosses from their first snapshots
            for (Map.Entry<UUID, NbtCompound> entry : bossSnapshots.entrySet()) {
                UUID uuid = entry.getKey();
                NbtCompound snapshot = entry.getValue();

                if (processedUuids.contains(uuid)) continue;

                Entity boss = getByUuid(world, uuid);
                if (boss != null) {
                    boss.readNbt(snapshot);
                } else {
                    EntityType.loadEntityWithPassengers(snapshot, world, entity -> {
                        world.spawnEntity(entity);
                        return entity;
                    });
                }
            }
        } finally {
            RbdStateManager.setRollingBack(false);
            entries.clear();
            bossSnapshots.clear();
        }
    }

    private Entity getByUuid(ServerWorld world, UUID uuid) {
        return world.getEntity(uuid);
    }

    public void clear() {
        entries.clear();
        bossSnapshots.clear();
    }

    private void truncate() {
        int removeCount = (int) (maxEntries * 0.2);
        if (removeCount > 0) {
            entries.subList(0, removeCount).clear();
        }
    }
}
