package com.deist.rbd.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Stores UUIDs of entities that were spawned AFTER the last checkpoint.
 * When a chunk loads, any entity whose UUID is in this set must be discarded.
 * This handles the case where spawned entities are in unloaded chunks at rollback time.
 */
public class RbdPendingDeletions {
    private static final Set<UUID> pendingDelete = Collections.synchronizedSet(new HashSet<>());

    public static void add(UUID uuid) {
        pendingDelete.add(uuid);
    }

    public static void addAll(Set<UUID> uuids) {
        pendingDelete.addAll(uuids);
    }

    public static boolean contains(UUID uuid) {
        return pendingDelete.contains(uuid);
    }

    public static void remove(UUID uuid) {
        pendingDelete.remove(uuid);
    }

    public static void clear() {
        pendingDelete.clear();
    }

    public static Set<UUID> getAll() {
        return Collections.unmodifiableSet(new HashSet<>(pendingDelete));
    }
}
