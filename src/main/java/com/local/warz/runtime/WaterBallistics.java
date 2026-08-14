package com.local.warz.runtime;

import com.local.warz.config.AmmoCaliber;
import com.local.warz.model.GunDefinition;
import com.local.warz.model.RoundDefinition;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Underwater / through-water projectile drag. Guns fire underwater; performance depends on
 * ammo, whether the muzzle is submerged, and muzzle devices.
 */
public final class WaterBallistics {
    private WaterBallistics() {
    }

    public static boolean isWater(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        Block b = loc.getBlock();
        Material t = b.getType();
        return t == Material.WATER || t == Material.BUBBLE_COLUMN
                || t.name().contains("WATER");
    }

    /**
     * Apply per-tick water drag to {@code velocity}. Returns false if the round should die
     * (energy spent / unsuitable ammo).
     */
    public static boolean applyTick(Vector velocity, Location at, Location muzzle,
                                    GunDefinition gun, RoundDefinition round,
                                    ItemStack gunItem, ItemFactory items,
                                    boolean shooterUnderwater, double pathWaterBlocks) {
        if (velocity == null || at == null || !isWater(at)) {
            return true;
        }
        double drag = baseDrag(round);
        // Muzzle submerged: worse than shooting into water from air
        if (shooterUnderwater || (muzzle != null && isWater(muzzle))) {
            drag *= 1.35;
        }
        // Suppressor traps gas — slightly worse underwater spit
        if (items != null && gunItem != null && items.hasSuppressor(gunItem)) {
            drag *= 1.12;
        }
        // Subsonic already slow — dies faster in water
        if (round != null && round.subsonic()) {
            drag *= 1.25;
        }
        // AP / green tip / match penetrate better
        if (round != null) {
            String id = round.fileName() == null ? "" : round.fileName().toLowerCase();
            if (id.contains("_ap") || id.contains("green_tip") || id.contains("api") || id.contains("match")) {
                drag *= 0.72;
            } else if (id.contains("_hp") || id.contains("softpoint") || id.contains("expansive")
                    || id.contains("buck") || id.contains("bird") || id.contains("flechette")) {
                drag *= 1.45;
            } else if (id.contains("slug") || id.contains("broadhead")) {
                drag *= 0.85;
            } else if (id.contains("explosive") || round.explodeRadiusAdd() > 0) {
                drag *= 1.2; // fuses / soft tips
            }
        }
        String cal = AmmoCaliber.normalize(BulletAudio.caliberOf(gun, round));
        if ("shotgun".equals(cal) || "shot".equals(cal)) {
            drag *= 1.55;
        } else if ("pistol".equals(cal)) {
            drag *= 1.2;
        } else if ("sniper".equals(cal) || "heavy".equals(cal)) {
            drag *= 0.65;
        } else if ("arrow".equals(cal) || "bolt".equals(cal)) {
            drag *= 0.55; // bolts push water better
        } else if ("energy".equals(cal)) {
            drag *= 0.4;
        } else if ("rocket".equals(cal)) {
            drag *= 1.8; // rockets hate water
        }

        velocity.multiply(Math.max(0.15, 1.0 - drag));
        // Hard range underwater — most rounds dead after ~8–20 blocks of water path
        double maxWater = maxWaterPath(round, cal);
        if (pathWaterBlocks > maxWater || velocity.lengthSquared() < 0.04) {
            return false;
        }
        return true;
    }

    private static double baseDrag(RoundDefinition round) {
        return 0.18;
    }

    private static double maxWaterPath(RoundDefinition round, String cal) {
        double base = switch (cal) {
            case "sniper", "heavy" -> 22.0;
            case "arrow", "bolt" -> 18.0;
            case "energy" -> 28.0;
            case "rifle", "auto" -> 12.0;
            case "pistol", "handgun" -> 7.0;
            case "shotgun", "shot" -> 4.0;
            case "rocket", "launcher" -> 2.5;
            default -> 10.0;
        };
        if (round != null) {
            String id = round.fileName() == null ? "" : round.fileName().toLowerCase();
            if (id.contains("_ap") || id.contains("green_tip") || id.contains("match")) {
                base *= 1.35;
            } else if (id.contains("_hp") || id.contains("buck") || id.contains("bird")) {
                base *= 0.55;
            }
            if (round.subsonic()) {
                base *= 0.7;
            }
        }
        return base;
    }

    public static boolean shooterMuzzleWet(Player shooter) {
        if (shooter == null) {
            return false;
        }
        return shooter.isUnderWater() || isWater(shooter.getEyeLocation());
    }
}
