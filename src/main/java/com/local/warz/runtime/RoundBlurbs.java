package com.local.warz.runtime;

import com.local.warz.model.RoundDefinition;

import java.util.Locale;

/** One-line flavor text for Shift-expanded ammo tooltips. */
public final class RoundBlurbs {
    private RoundBlurbs() {
    }

    public static String describe(RoundDefinition def) {
        if (def == null) {
            return "Ammunition.";
        }
        String custom = def.description();
        if (custom != null && !custom.isBlank()) {
            return custom.trim();
        }
        String id = def.fileName() == null ? "" : def.fileName().toLowerCase(Locale.ROOT);
        // UAV munitions — short identity lines
        if (id.equals("rocket_he")) {
            return "Dumb HE rocket — small blast.";
        }
        if (id.equals("rocket_ap")) {
            return "Dumb AP rocket — punches armor.";
        }
        if (id.equals("rocket_hp")) {
            return "Dumb HP rocket — soft-target blast.";
        }
        if (id.equals("rocket_aa")) {
            return "Heat-seek AA — hunts other UAVs.";
        }
        if (id.equals("rocket_r9x")) {
            return "Laser Hellfire — kinetic blades, no boom.";
        }
        if (id.equals("rocket_mac")) {
            return "Laser Hellfire — big flash & pressure.";
        }
        if (id.equals("rocket_romeo")) {
            return "Laser Hellfire — classic HE.";
        }
        if (id.equals("rocket_jagm")) {
            return "Dual-mode — laser + reacquire.";
        }
        if (id.equals("gbu_viper")) {
            return "Laser glide bomb — small crater.";
        }
        if (id.equals("gbu_sgm")) {
            return "Laser glide — medium crater & dust.";
        }
        if (id.equals("gbu_sdb")) {
            return "Penetrator — buries, then deep boom.";
        }
        if (id.equals("gbu_storm")) {
            return "Multi-seek glide — medium HE.";
        }
        if (id.equals("gbu_paveway")) {
            return "Laser bomb — huge delayed boom.";
        }
        if (id.equals("gbu_sonar")) {
            return "Sonar marker — LOS glow 120m / 90s (45s CD).";
        }
        if (id.equals("aim9x")) {
            return "IR Sidewinder — air-to-air vs drones.";
        }
        if (id.contains("broadhead")) {
            return "Wide cutting tip — higher damage.";
        }
        if (id.contains("explosive") || def.explodeRadiusAdd() > 0) {
            return "Explodes on impact.";
        }
        if (id.contains("incendiary") || id.contains("dragon") || def.fireRadiusAdd() > 0 || def.setFireTicks() > 0) {
            return "Incendiary — lights targets on fire.";
        }
        if (id.contains("subsonic") || def.subsonic()) {
            return "Subsonic — no sonic crack.";
        }
        if (id.contains("tracer") || def.tracer()) {
            return "Bright trail for spotting fire.";
        }
        if (id.contains("flechette")) {
            return "Needle darts — better penetration.";
        }
        if (id.contains("beanbag")) {
            return "Less-lethal impact round.";
        }
        if (id.contains("breaching")) {
            return "Door / lock breaching load.";
        }
        if (id.contains("bird")) {
            return "Light birdshot — wide spread.";
        }
        if (id.contains("buck")) {
            return "Buckshot — close-range pellets.";
        }
        if (id.contains("slug")) {
            return "Solid slug — single heavy hit.";
        }
        if (id.contains("_ap") || id.endsWith("ap") || id.contains("green_tip") || id.contains("api")) {
            return "Armor-piercing core.";
        }
        if (id.contains("_hp") || id.contains("expansive") || id.contains("softpoint")) {
            return "Expands on impact — soft targets.";
        }
        if (id.contains("match") || id.contains("plusp")) {
            return "Match-grade — tighter accuracy.";
        }
        if (id.contains("duplex")) {
            return "Two projectiles per shot.";
        }
        if (id.contains("smoke")) {
            return "Deploys a smoke screen.";
        }
        if (id.contains("flash")) {
            return "Blinding flash on detonation.";
        }
        if (id.contains("he") || id.contains("overcharge")) {
            return "High-explosive payload.";
        }
        if (id.contains("pulse")) {
            return "Pulsed energy burst.";
        }
        if (id.contains("flare")) {
            return "Bright signal / illumination.";
        }
        if (id.contains("fmj") || id.contains("standard")
                || (id.contains("bolt") && !id.contains("explosive"))) {
            return "Standard full-metal jacket.";
        }
        if (id.contains("energy")) {
            return "Directed energy cell.";
        }
        if (id.contains("rocket") || id.contains("40mm")) {
            return "Explosive ordnance round.";
        }
        if (def.armorPenAdd() >= 3) {
            return "Hardened tip — better armor pen.";
        }
        if (def.damageMult() > 1.05) {
            return "Hot load — extra damage.";
        }
        if (def.damageMult() < 0.95) {
            return "Specialized loadout round.";
        }
        return "Standard ammunition.";
    }
}
