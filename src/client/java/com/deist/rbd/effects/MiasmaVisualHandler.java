package com.deist.rbd.effects;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Client-side persistent Miasma visual.
 * Shows a semi-transparent purple overlay when the player has Miasma level > 0.
 * Intensity scales with Miasma level (0 = none, maxLevel = max opacity).
 * Updated each time miasmaLevel is received from server.
 */
public class MiasmaVisualHandler {

    // Received from server via packet or command response
    private static int currentLevel = 0;
    private static int maxLevel     = 4;

    // Post-RbD burst: fades up briefly after respawn then settles at persistent intensity
    private static int burstTicks     = 0;
    private static final int BURST_DURATION = 60; // 3s burst after respawn

    public static void setLevel(int level, int max) {
        currentLevel = level;
        maxLevel     = Math.max(1, max);
    }

    /** Called on rollback complete — triggers the post-respawn burst. */
    public static void onRbdComplete(int miasmaLevel) {
        currentLevel = miasmaLevel;
        if (miasmaLevel > 0) burstTicks = BURST_DURATION;
    }

    public static void tick() {
        if (burstTicks > 0) burstTicks--;
    }

    /**
     * Render persistent purple overlay scaled by Miasma level.
     * Also adds a brief intensity burst right after respawn.
     */
    public static void renderVignette(DrawContext context) {
        if (currentLevel <= 0) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        int w = mc.getWindow().getScaledWidth();
        int h = mc.getWindow().getScaledHeight();

        // Base opacity: scales from 15 (Lv1) to 55 (maxLevel) out of 255
        float levelRatio = (float) currentLevel / maxLevel;
        float baseAlpha = 15 + levelRatio * 40; // 15..55

        // Burst adds up to +80 alpha for 3s after respawn
        float burstAlpha = burstTicks > 0
            ? 80f * ((float) burstTicks / BURST_DURATION)
            : 0f;

        int alpha = (int) Math.min(baseAlpha + burstAlpha, 160);
        int color = (alpha << 24) | 0x6A0DAD; // purple
        context.fill(0, 0, w, h, color);
    }

    public static boolean isActive() { return currentLevel > 0; }
    public static int getCurrentLevel() { return currentLevel; }
}
