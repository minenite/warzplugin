package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Loads {@code kill-feed.yml} settings + optional pool overrides.
 * Built-in defaults live in {@link KillFeedService}; YAML only replaces listed keys.
 */
public final class KillFeedCatalog {
    private final WarzPlugin plugin;
    private final Map<String, List<String>> poolOverrides = new LinkedHashMap<>();

    public long creditMs = 12_000L;
    public long bleedCreditMs = 300_000L;
    public long streakWindowMs = 10_000L;
    public long templateCooldownMs = 18_000L;
    public float trickshotFallBlocks = 3.0f;
    public boolean sounds = true;
    public boolean toasts = true;
    public double rangePointBlank = 5.0;
    public double rangeClose = 15.0;
    public double rangeLong = 45.0;
    public double rangeExtreme = 90.0;

    public KillFeedCatalog(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        poolOverrides.clear();
        File file = new File(plugin.getDataFolder(), "kill-feed.yml");
        if (!file.exists()) {
            plugin.saveResource("kill-feed.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        // Merge resource defaults under missing keys
        try (InputStream in = plugin.getResource("kill-feed.yml")) {
            if (in != null) {
                YamlConfiguration def = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                yaml.setDefaults(def);
                yaml.options().copyDefaults(true);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "kill-feed.yml defaults: " + e.getMessage());
        }

        ConfigurationSection set = yaml.getConfigurationSection("settings");
        if (set != null) {
            creditMs = Math.max(1000L, set.getLong("credit-ms", creditMs));
            bleedCreditMs = Math.max(creditMs, set.getLong("bleed-credit-ms", bleedCreditMs));
            streakWindowMs = Math.max(1000L, set.getLong("streak-window-ms", streakWindowMs));
            templateCooldownMs = Math.max(0L, set.getLong("template-cooldown-ms", templateCooldownMs));
            trickshotFallBlocks = (float) Math.max(0.5, set.getDouble("trickshot-fall-blocks", trickshotFallBlocks));
            sounds = set.getBoolean("sounds", sounds);
            toasts = set.getBoolean("toasts", toasts);
            rangePointBlank = Math.max(1.0, set.getDouble("range-point-blank", rangePointBlank));
            rangeClose = Math.max(rangePointBlank + 0.1, set.getDouble("range-close", rangeClose));
            rangeLong = Math.max(rangeClose + 0.1, set.getDouble("range-long", rangeLong));
            rangeExtreme = Math.max(rangeLong + 0.1, set.getDouble("range-extreme", rangeExtreme));
        }

        ConfigurationSection pools = yaml.getConfigurationSection("pools");
        if (pools != null) {
            for (String key : pools.getKeys(false)) {
                List<String> lines = pools.getStringList(key);
                if (lines == null || lines.isEmpty()) {
                    continue;
                }
                List<String> clean = new ArrayList<>(lines.size());
                for (String line : lines) {
                    if (line != null && !line.isBlank()) {
                        clean.add(line);
                    }
                }
                if (!clean.isEmpty()) {
                    poolOverrides.put(key.toLowerCase(Locale.ROOT), List.copyOf(clean));
                }
            }
        }
    }

    public List<String> override(String key) {
        if (key == null) {
            return List.of();
        }
        return poolOverrides.getOrDefault(key.toLowerCase(Locale.ROOT), List.of());
    }

    public String[] poolOrDefault(String key, String[] builtIn) {
        List<String> over = override(key);
        if (!over.isEmpty()) {
            return over.toArray(new String[0]);
        }
        return builtIn;
    }

    public Map<String, List<String>> overridesView() {
        return Collections.unmodifiableMap(poolOverrides);
    }
}
