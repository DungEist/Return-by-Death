package com.deist.rbd.core;

import com.deist.rbd.log.ChangeType;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class RbdStateManager {
    private static final ThreadLocal<Boolean> rollingBack = ThreadLocal.withInitial(() -> false);
    private static final Set<UUID> waitingForRollback = Collections.synchronizedSet(new HashSet<>());
    private static final ThreadLocal<ChangeType> currentCause = ThreadLocal.withInitial(() -> ChangeType.UNKNOWN);

    public static boolean isRollingBack() {
        return rollingBack.get();
    }

    public static void setRollingBack(boolean value) {
        rollingBack.set(value);
    }

    public static boolean isWaitingForRollback(UUID uuid) {
        return waitingForRollback.contains(uuid);
    }

    public static void setWaitingForRollback(UUID uuid, boolean value) {
        if (value) waitingForRollback.add(uuid);
        else waitingForRollback.remove(uuid);
    }

    public static ChangeType getCurrentCause() {
        return currentCause.get();
    }

    public static void setCurrentCause(ChangeType cause) {
        currentCause.set(cause);
    }
}
