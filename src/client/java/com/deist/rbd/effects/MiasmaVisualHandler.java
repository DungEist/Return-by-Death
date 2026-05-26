package com.deist.rbd.effects;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * Client-side persistent Miasma visual.
 * Shows a semi-transparent purple vignette when the player has Miasma level > 0.
 * Intensity scales with Miasma level (0 = none, maxLevel = max opacity).
 * Updated each time miasmaLevel is received from server.
 */
public class MiasmaVisualHandler {

    private static final Identifier VIGNETTE_TEXTURE = new Identifier("textures/misc/vignette.png");

    // Received from server via packet or command response
    private static int currentLevel = 0;
    private static int maxLevel     = 4;

    // Post-RbD particle effect duration (140 ticks = 7 seconds)
    private static int particleTicks = 0;

    public static void setLevel(int level, int max) {
        currentLevel = level;
        maxLevel     = Math.max(1, max);
    }

    /** Called on rollback complete — triggers the 7-second Miasma particle effect. */
    public static void onRbdComplete(int miasmaLevel) {
        currentLevel = miasmaLevel;
        if (miasmaLevel > 0) {
            particleTicks = 140; // 7 seconds
        }
    }

    public static void tick() {
        if (particleTicks > 0) {
            particleTicks--;

            // Spawn witch particles around the player
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null && mc.world != null && !mc.isPaused() && currentLevel > 0) {
                int particleCount = currentLevel + 3; // base count + extra burst
                for (int i = 0; i < particleCount; i++) {
                    double px = mc.player.getX() + (mc.world.random.nextDouble() - 0.5) * 1.5;
                    double py = mc.player.getY() + mc.world.random.nextDouble() * 2.0;
                    double pz = mc.player.getZ() + (mc.world.random.nextDouble() - 0.5) * 1.5;
                    mc.world.addParticle(
                        net.minecraft.particle.ParticleTypes.WITCH,
                        px, py, pz,
                        0.0, 0.0, 0.0
                    );
                }
            }
        }
    }

    /**
     * Overlay (vignette) has been removed as requested.
     */
    public static void renderVignette(DrawContext context) {
        // Overlay is removed after rollback
    }

    public static boolean isActive() { return currentLevel > 0; }
    public static int getCurrentLevel() { return currentLevel; }
}
