package com.local.warz.config;

import org.bukkit.Material;

import java.util.Locale;

/** Caliber groups that bind guns to round catalogs and base materials. */
public final class AmmoCaliber {
    private AmmoCaliber() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "rifle";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Canonical ammo family for fit checks ({@code heavy}→{@code sniper}, {@code shot}→{@code shotgun}, …).
     */
    public static String family(String raw) {
        return switch (normalize(raw)) {
            case "heavy" -> "sniper";
            case "shot" -> "shotgun";
            case "handgun" -> "pistol";
            case "auto" -> "rifle";
            case "bolt" -> "arrow";
            case "launcher" -> "rocket";
            case "plasma", "laser" -> "energy";
            case "flares" -> "flare";
            default -> normalize(raw);
        };
    }

    public static boolean sameFamily(String a, String b) {
        return family(a).equals(family(b));
    }

    public static Material defaultMaterial(String caliber) {
        return switch (normalize(caliber)) {
            case "sniper", "heavy" -> Material.CLAY_BALL;
            case "shotgun", "shot" -> Material.WHEAT_SEEDS;
            case "pistol", "handgun" -> Material.ENDER_PEARL;
            case "arrow", "bolt" -> Material.ARROW;
            case "rocket", "launcher" -> Material.COAL;
            case "energy", "plasma", "laser" -> Material.GLOWSTONE_DUST;
            case "flare", "flares" -> Material.FIREWORK_STAR;
            case "melee" -> Material.FLINT;
            default -> Material.FLINT; // rifle / auto
        };
    }

    public static String fromMaterial(Material material) {
        if (material == null) {
            return "rifle";
        }
        return switch (material) {
            case CLAY_BALL -> "sniper";
            case WHEAT_SEEDS, WHEAT -> "shotgun";
            case ENDER_PEARL -> "pistol";
            case ARROW, SPECTRAL_ARROW, TIPPED_ARROW -> "arrow";
            case COAL, CHARCOAL -> "rocket";
            case GLOWSTONE_DUST, REDSTONE, BLAZE_POWDER -> "energy";
            case FIREWORK_STAR, FIREWORK_ROCKET -> "flare";
            default -> "rifle";
        };
    }

    public static String[] cycleOrder() {
        return new String[]{"rifle", "sniper", "shotgun", "pistol", "arrow", "rocket", "energy", "flare"};
    }

    public static String next(String current) {
        String[] order = cycleOrder();
        String cur = normalize(current);
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(cur)) {
                return order[(i + 1) % order.length];
            }
        }
        return order[0];
    }

    /** Default allowed rounds when migrating a legacy gun. */
    public static String[] defaultAllowed(String caliber) {
        return switch (normalize(caliber)) {
            case "sniper" -> new String[]{
                    "sniper_fmj", "sniper_tracer", "sniper_ap", "sniper_match",
                    "sniper_api", "sniper_subsonic", "sniper_he"
            };
            case "shotgun" -> new String[]{
                    "shot_buck", "shot_slug", "shot_tracer", "shot_dragon",
                    "shot_bird", "shot_flechette", "shot_breaching", "shot_beanbag"
            };
            case "pistol" -> new String[]{
                    "pistol_fmj", "pistol_tracer", "pistol_hp", "pistol_ap",
                    "pistol_subsonic", "pistol_match", "pistol_plusp", "pistol_expansive"
            };
            case "arrow" -> new String[]{
                    "arrow_standard", "arrow_tracer", "arrow_broadhead", "arrow_explosive"
            };
            case "rocket" -> new String[]{
                    "40mm_he", "40mm_ap", "40mm_smoke", "40mm_flash",
                    "rocket_he", "rocket_ap", "rocket_hp", "rocket_aa", "rocket_r9x"
            };
            case "energy" -> new String[]{"energy_bolt", "energy_overcharge", "energy_pulse"};
            case "flare" -> new String[]{"flare_cartridge"};
            default -> new String[]{
                    "rifle_fmj", "rifle_tracer", "rifle_hp", "rifle_ap", "rifle_incendiary",
                    "rifle_subsonic", "rifle_match", "rifle_softpoint", "rifle_green_tip", "rifle_duplex"
            };
        };
    }

    public static String primaryRound(String caliber) {
        String[] allowed = defaultAllowed(caliber);
        return allowed.length > 0 ? allowed[0] : "rifle_fmj";
    }

    /** Human-readable ammo family for UI / kill-feed hover. */
    public static String displayLabel(String caliber) {
        return switch (normalize(caliber)) {
            case "sniper", "heavy" -> ".50 Caliber";
            case "rifle", "auto" -> "5.56 Rifle";
            case "pistol", "handgun" -> "9mm";
            case "shotgun", "shot" -> "12 Gauge";
            case "rocket", "launcher" -> "40mm / Rocket";
            case "energy", "plasma", "laser" -> "Energy Cell";
            case "arrow", "bolt" -> "Arrow / Bolt";
            case "flare", "flares" -> "Flare Cartridge";
            case "melee" -> "Melee";
            default -> {
                String n = normalize(caliber);
                if (n.isEmpty()) {
                    yield "Unknown";
                }
                yield Character.toUpperCase(n.charAt(0)) + n.substring(1);
            }
        };
    }
}
