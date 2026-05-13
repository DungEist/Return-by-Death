package com.deist.rbd.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class RbdConfig {
    private static RbdConfig instance = new RbdConfig();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("rbd.json");

    // ── Rollback ──────────────────────────────────────────────────────────────
    public int rollbackEffectDurationTicks = 40;

    // ── Miasma — gameplay ─────────────────────────────────────────────────────
    public boolean miasmaEnabled = true;
    public int maxMiasmaLevel = 4;
    public boolean miasmaDecay = true;
    public int miasmaDecayDays = 5;       // in-game days between each −1 level

    // ── Miasma — visual ───────────────────────────────────────────────────────
    public boolean scentVisualEnabled = true;
    public int scentVisualPeakTicks  = 60;  // 3 s
    public int scentVisualTotalTicks = 160; // 8 s

    // ─────────────────────────────────────────────────────────────────────────

    public static RbdConfig get() { return instance; }

    /** Load config from disk; falls back to defaults if file is missing or corrupt. */
    public static void load() {
        File file = CONFIG_PATH.toFile();
        if (!file.exists()) {
            save(); // write defaults
            return;
        }
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            RbdConfig loaded = GSON.fromJson(reader, RbdConfig.class);
            if (loaded != null) instance = loaded;
        } catch (Exception e) {
            System.err.println("[RbD] Failed to load config, using defaults: " + e.getMessage());
        }
    }

    /** Save current config to disk. */
    public static void save() {
        try {
            File file = CONFIG_PATH.toFile();
            file.getParentFile().mkdirs();
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                GSON.toJson(instance, writer);
            }
        } catch (Exception e) {
            System.err.println("[RbD] Failed to save config: " + e.getMessage());
        }
    }
}
