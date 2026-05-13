package com.deist.rbd.client;

import com.deist.rbd.core.RbdConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Cloth Config screen builder for Return by Death settings.
 * Accessed via Mod Menu.
 */
public class RbdConfigScreen {

    public static Screen build(Screen parent) {
        RbdConfig cfg = RbdConfig.get();

        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Text.literal("Return by Death — Settings"));

        ConfigEntryBuilder entry = builder.entryBuilder();

        // ── Miasma Gameplay ─────────────────────────────────────────────────
        ConfigCategory miasma = builder.getOrCreateCategory(Text.literal("Witch's Miasma"));

        miasma.addEntry(entry.startBooleanToggle(Text.literal("Enable Miasma System"), cfg.miasmaEnabled)
            .setDefaultValue(true)
            .setTooltip(Text.literal("When disabled, mobs are not affected by your death count."))
            .setSaveConsumer(v -> cfg.miasmaEnabled = v)
            .build());

        miasma.addEntry(entry.startIntSlider(Text.literal("Max Miasma Level"), cfg.maxMiasmaLevel, 1, 16)
            .setDefaultValue(4)
            .setTooltip(Text.literal("Maximum Miasma level the hidden score can reach."))
            .setSaveConsumer(v -> cfg.maxMiasmaLevel = v)
            .build());

        miasma.addEntry(entry.startBooleanToggle(Text.literal("Enable Miasma Decay"), cfg.miasmaDecay)
            .setDefaultValue(true)
            .setTooltip(Text.literal("If enabled, Miasma decays over time while the player stays alive."))
            .setSaveConsumer(v -> cfg.miasmaDecay = v)
            .build());

        miasma.addEntry(entry.startIntSlider(Text.literal("Decay Rate (in-game days)"), cfg.miasmaDecayDays, 1, 30)
            .setDefaultValue(5)
            .setTooltip(Text.literal("Number of in-game days per 1 level of Miasma decay."))
            .setSaveConsumer(v -> cfg.miasmaDecayDays = v)
            .build());

        // ── Miasma Visuals ───────────────────────────────────────────────────
        ConfigCategory visuals = builder.getOrCreateCategory(Text.literal("Visual Effects"));

        visuals.addEntry(entry.startBooleanToggle(Text.literal("Enable Scent Visual"), cfg.scentVisualEnabled)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Show witch particles and purple vignette after death at high Miasma."))
            .setSaveConsumer(v -> cfg.scentVisualEnabled = v)
            .build());

        // ── Rollback ─────────────────────────────────────────────────────────
        ConfigCategory rollback = builder.getOrCreateCategory(Text.literal("Rollback"));

        rollback.addEntry(entry.startIntSlider(
                Text.literal("Rollback Flash Duration (ticks)"), cfg.rollbackEffectDurationTicks, 5, 100)
            .setDefaultValue(40)
            .setTooltip(Text.literal("Duration of the white flash during rollback (20 ticks = 1 second)."))
            .setSaveConsumer(v -> cfg.rollbackEffectDurationTicks = v)
            .build());

        builder.setSavingRunnable(RbdConfig::save);

        return builder.build();
    }
}
