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

        // Spawn witch particles around the player if they have Miasma
        if (currentLevel > 0) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null && mc.world != null && !mc.isPaused()) {
                int particleCount = currentLevel;
                if (burstTicks > 0) {
                    particleCount += 3;
                }
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
     * Render persistent purple vignette scaled by Miasma level.
     * Also adds a brief intensity burst right after respawn.
     */
    public static void renderVignette(DrawContext context) {
        if (currentLevel <= 0) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        int w = mc.getWindow().getScaledWidth();
        int h = mc.getWindow().getScaledHeight();

        float levelRatio = (float) currentLevel / maxLevel;
        float baseIntensity = 0.1f + levelRatio * 0.4f; // 0.1 .. 0.5

        float burstIntensity = burstTicks > 0
            ? 0.4f * ((float) burstTicks / BURST_DURATION)
            : 0f;

        float intensity = Math.min(baseIntensity + burstIntensity, 0.9f);

        // Inverse color tinting to render a purple vignette under multiply blend
        float r = 1.0f - (0.8f * intensity);
        float g = 1.0f - (0.1f * intensity);
        float b = 1.0f - (0.8f * intensity);

        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
        com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.blendFuncSeparate(
            com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ZERO,
            com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE_MINUS_SRC_COLOR,
            com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ONE,
            com.mojang.blaze3d.platform.GlStateManager.DstFactor.ZERO
        );
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(r, g, b, 1.0f);
        
        context.drawTexture(VIGNETTE_TEXTURE, 0, 0, 0, 0, w, h, w, h);
        
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
    }

    public static boolean isActive() { return currentLevel > 0; }
    public static int getCurrentLevel() { return currentLevel; }
}
