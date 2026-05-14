package com.deist.rbd.effects;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public class RbdClientEffects {
    public static final SoundEvent HEARTBEAT_SOUND = SoundEvent.of(new Identifier("rbd", "heartbeat_fast"));
    public static final SoundEvent REVERSE_SOUND = SoundEvent.of(new Identifier("rbd", "reverse_whoosh"));
    public static final SoundEvent WITCH_SOUND = SoundEvent.of(new Identifier("rbd", "call_of_the_witch"));
    public static final SoundEvent AISHITERU_SOUND = SoundEvent.of(new Identifier("rbd", "aishiteru"));

    private static int state = 0; // 0 = none, 1 = red/black fade, 2 = white flash, 3 = fade in
    private static int ticksRemaining = 0;
    private static int maxTicks = 0;
    private static int currentMiasmaLevel = 0;

    public static boolean isRollingBack() {
        return state != 0;
    }

    public static void startRollback() {
        state = 1;
        maxTicks = 120; // 6 giây timeout, đủ cho server rollback xong
        ticksRemaining = maxTicks;
        playSound(HEARTBEAT_SOUND);
    }

    public static void onRollbackComplete(int miasmaLevel, int loopNumber) {
        state = 2;
        maxTicks = 10; // White flash
        ticksRemaining = maxTicks;
        currentMiasmaLevel = miasmaLevel;
        playSound(REVERSE_SOUND);
        // Aishiteru: guaranteed at loop 16, 2% chance for loops 17+
        // (Call of the Witch plays otherwise)
        boolean playAishiteru = loopNumber == 16
                || (loopNumber > 16 && Math.random() < 0.02);
        // Schedule after white flash fades
        final boolean fa = playAishiteru;
        new java.util.Timer().schedule(new java.util.TimerTask() {
            @Override public void run() {
                net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
                    if (fa) {
                        playSound(AISHITERU_SOUND);
                    } else {
                        playSound(WITCH_SOUND);
                    }
                });
            }
        }, 500); // 0.5s after white flash
    }

    public static void tick() {
        if (state == 0) return;

        ticksRemaining--;

        if (ticksRemaining <= 0) {
            if (state == 1) {
                // Timeout safety: nếu ROLLBACK_COMPLETE packet không đến sau 120 ticks,
                // tự chuyển sang white flash để không kẹt mãi
                state = 2;
                maxTicks = 10;
                ticksRemaining = maxTicks;
            } else if (state == 2) {
                state = 3;
                maxTicks = 40;
                ticksRemaining = maxTicks;
                // Sound is scheduled in onRollbackComplete via Timer
            } else if (state == 3) {
                state = 0;
            }
        }
    }

    public static void renderOverlay(DrawContext context, float tickDelta) {
        if (state == 0) return;

        int width = MinecraftClient.getInstance().getWindow().getScaledWidth();
        int height = MinecraftClient.getInstance().getWindow().getScaledHeight();

        float progress = (float) (maxTicks - ticksRemaining + tickDelta) / maxTicks;
        if (progress > 1.0f) progress = 1.0f;

        if (state == 1) {
            // Flash red, then fade to black
            int alpha;
            int r, g, b;
            if (progress < 0.3f) {
                // Red flash
                alpha = (int) ((progress / 0.3f) * 255);
                r = 150; g = 0; b = 0;
            } else {
                // Fade to black
                alpha = 255;
                float blackProgress = (progress - 0.3f) / 0.7f;
                r = (int) (150 * (1 - blackProgress));
                g = 0; b = 0;
            }
            int color = (alpha << 24) | (r << 16) | (g << 8) | b;
            context.fill(0, 0, width, height, color);
        } else if (state == 2) {
            // White flash
            int alpha = 255;
            int color = (alpha << 24) | 0xFFFFFF;
            context.fill(0, 0, width, height, color);
        } else if (state == 3) {
            // Fade from white/black to clear
            int alpha = (int) ((1.0f - progress) * 255);
            int color = (alpha << 24) | 0x000000;
            context.fill(0, 0, width, height, color);
        }
    }

    private static void playSound(SoundEvent sound) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.getSoundManager().play(PositionedSoundInstance.master(sound, 1.0F, 1.0F));
        }
    }
}
