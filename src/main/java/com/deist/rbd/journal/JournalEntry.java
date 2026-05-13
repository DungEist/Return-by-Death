package com.deist.rbd.journal;

/**
 * Data class for a single Death Journal entry.
 */
public class JournalEntry {
    public final int  loopNumber;
    public final long timestamp;      // real time (unix ms)
    public final long worldTime;      // game tick of death
    public final String deathCause;  // e.g. "minecraft.death.attack.mob"
    public final double deathX;
    public final double deathY;
    public final double deathZ;
    public final String deathDim;    // e.g. "minecraft:overworld"
    public final long survivedTicks; // ticks from checkpoint to death
    public final int miasmaLevel;

    public JournalEntry(int loopNumber, long timestamp, long worldTime,
                        String deathCause, double x, double y, double z,
                        String deathDim, long survivedTicks, int miasmaLevel) {
        this.loopNumber    = loopNumber;
        this.timestamp     = timestamp;
        this.worldTime     = worldTime;
        this.deathCause    = deathCause;
        this.deathX        = x;
        this.deathY        = y;
        this.deathZ        = z;
        this.deathDim      = deathDim;
        this.survivedTicks = survivedTicks;
        this.miasmaLevel   = miasmaLevel;
    }

    /** Format survival duration as "Xm Ys" */
    public String formatSurvival() {
        long seconds = survivedTicks / 20;
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    /** Short death cause label for display */
    public String formatCause() {
        if (deathCause == null || deathCause.isEmpty()) return "Unknown";
        // Extract last segment, e.g. "minecraft.death.attack.mob" → "mob"
        String[] parts = deathCause.split("\\.");
        String raw = parts[parts.length - 1];
        // Capitalize and replace underscores
        return raw.substring(0, 1).toUpperCase()
            + raw.substring(1).replace('_', ' ');
    }
}
