package com.deist.rbd.effects;

import net.minecraft.util.Identifier;

public class RbdNetworking {
    public static final Identifier ROLLBACK_START_ID    = new Identifier("rbd", "rollback_start");
    public static final Identifier ROLLBACK_COMPLETE_ID = new Identifier("rbd", "rollback_complete");
    /** Sent server→client to sync current miasmaLevel and maxMiasmaLevel for the overlay. */
    public static final Identifier MIASMA_SYNC_ID       = new Identifier("rbd", "miasma_sync");
}
