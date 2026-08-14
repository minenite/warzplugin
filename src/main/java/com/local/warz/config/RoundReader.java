package com.local.warz.config;

import com.local.warz.model.RoundDefinition;
import com.local.warz.util.LaserBeams;
import org.bukkit.Color;
import org.bukkit.Material;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class RoundReader {
    private RoundReader() {
    }

    public static RoundDefinition read(Path file) throws IOException {
        String fileName = file.getFileName().toString();
        RoundDefinition.Builder builder = new RoundDefinition.Builder()
                .fileName(fileName)
                .displayName("&e" + fileName);
        // Filename hint — can be overridden by subsonic= in the file.
        if (fileName.toLowerCase(Locale.ROOT).contains("subsonic")) {
            builder.subsonic(true);
        }

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                apply(builder, line);
            }
        }
        builder.material(Material.STICK);
        return builder.build();
    }

    private static void apply(RoundDefinition.Builder builder, String rawLine) {
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#") || !line.contains("=") || line.startsWith("-")) {
            return;
        }
        int eq = line.indexOf('=');
        String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
        String value = line.substring(eq + 1).trim();
        switch (key) {
            case "displayname", "name", "roundname" -> builder.displayName(value);
            case "description", "desc", "blurb", "tooltip" -> builder.description(value);
            case "material", "item", "ammotype" -> {
                Material mat = Material.matchMaterial(value);
                if (mat == null) {
                    mat = LegacyMaterialMap.fromLegacy(value);
                }
                if (mat != null) {
                    builder.material(mat);
                }
            }
            case "custommodeldata", "cmd", "modeldata" -> builder.customModelData(parseInt(value, 2000));
            case "caliber", "ammocaliber" -> builder.caliber(value);
            case "damagemult", "damage", "dmgmult" -> builder.damageMult(parseDouble(value, 1.0));
            case "armorpenadd", "armorpen", "armorpenetrationadd" -> builder.armorPenAdd(parseInt(value, 0));
            case "accuracymult", "accuracy" -> builder.accuracyMult(parseDouble(value, 1.0));
            case "speedmult", "speed", "bulletspeedmult" -> builder.speedMult(parseDouble(value, 1.0));
            case "knockbackmult", "knockback" -> builder.knockbackMult(parseDouble(value, 1.0));
            case "rangemult", "range", "maxdistancemult" -> builder.rangeMult(parseDouble(value, 1.0));
            case "tracer", "hastracer" -> builder.tracer(parseBool(value, false));
            case "tracercolor" -> builder.tracerColor(LaserBeams.parseColor(value, Color.fromRGB(255, 200, 80)));
            case "tracerwidth" -> builder.tracerWidth((float) parseDouble(value, 0.035));
            case "muzzleflash", "muzzle" -> builder.muzzleFlash(parseBool(value, true));
            case "muzzlecolor" -> builder.muzzleColor(LaserBeams.parseColor(value, Color.fromRGB(255, 190, 90)));
            case "muzzlescale" -> builder.muzzleScale((float) parseDouble(value, 0.85));
            case "exploderadiusadd" -> builder.explodeRadiusAdd(parseDouble(value, 0));
            case "fireradiusadd" -> builder.fireRadiusAdd(parseDouble(value, 0));
            case "setfireticks", "fireticks" -> builder.setFireTicks(parseInt(value, 0));
            case "subsonic", "issubsonic", "sub_sonic" -> builder.subsonic(parseBool(value, false));
            default -> {
            }
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean parseBool(String value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1", "on" -> true;
            case "false", "no", "0", "off" -> false;
            default -> fallback;
        };
    }
}
