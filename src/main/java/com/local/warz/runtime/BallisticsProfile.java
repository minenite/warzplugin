package com.local.warz.runtime;

import com.local.warz.config.AmmoCaliber;
import com.local.warz.model.GunDefinition;
import com.local.warz.model.RoundDefinition;
import com.local.warz.projectile.Bullet;

import java.util.Locale;

/**
 * Shared shot profile: caliber + ammo family drive glass cracks, cover pen, and ricochet.
 */
public record BallisticsProfile(
        double impact,
        double pen,
        double frag,
        double retainBonus,
        boolean thermal,
        String caliber,
        float penNorm,
        AmmoFamily family,
        float crackScale
) {
    public enum AmmoFamily {
        /** Full metal jacket — punches soft cover / glass; stops on hard. */
        FMJ,
        /** Hollow point — expands, poor penetration. */
        HP,
        /** Armor piercing — soft + some hard cover. */
        AP,
        /** Shotgun slug. */
        SLUG,
        /** Buckshot — soft only, low retain. */
        BUCK,
        /** High explosive / rocket. */
        HE,
        /** Incendiary / energy. */
        THERMAL,
        /** Match / tracer / generic. */
        STANDARD
    }

    public static BallisticsProfile of(Bullet bullet) {
        GunDefinition gun = bullet != null ? bullet.gun() : null;
        RoundDefinition round = bullet != null ? bullet.round() : null;
        return of(gun, round);
    }

    public static BallisticsProfile of(GunDefinition gun, RoundDefinition round) {
        String caliber = round != null ? round.caliber() : (gun != null ? gun.ammoCaliber() : "rifle");
        caliber = AmmoCaliber.normalize(caliber);
        double calFactor = switch (caliber) {
            case "pistol", "handgun" -> 0.95;
            case "rifle", "auto" -> 1.65;
            case "sniper", "heavy" -> 2.9;
            case "shotgun", "shot" -> 1.25;
            case "arrow", "bolt" -> 0.75;
            case "rocket", "launcher" -> 8.0;
            case "energy", "plasma", "laser" -> 1.45;
            default -> 1.4;
        };

        double dmg = gun != null ? gun.gunDamage() : 10;
        if (round != null) {
            dmg *= round.damageMult();
        }
        int armorPen = (gun != null ? gun.armorPenetration() : 0) + (round != null ? round.armorPenAdd() : 0);

        String tag = "";
        if (round != null) {
            tag = (round.fileName() + " " + round.displayName()).toLowerCase(Locale.ROOT);
        }

        AmmoFamily family = classify(tag, caliber);
        double penMult = 1.0;
        double fragMult = 1.0;
        double retainBonus = 1.0;
        boolean thermal = false;

        switch (family) {
            case AP -> {
                penMult *= 1.55;
                fragMult *= 0.7;
                retainBonus *= 1.12;
            }
            case HP -> {
                penMult *= 0.52;
                fragMult *= 1.55;
                retainBonus *= 0.72;
            }
            case SLUG -> {
                penMult *= 1.6;
                calFactor = Math.max(calFactor, 2.05);
            }
            case BUCK -> {
                penMult *= 0.5;
                fragMult *= 1.7;
                retainBonus *= 0.55;
            }
            case HE -> {
                penMult *= 2.4;
                fragMult *= 1.9;
            }
            case THERMAL -> {
                thermal = true;
                penMult *= 1.1;
            }
            default -> {
            }
        }
        if (caliber.equals("energy") || caliber.equals("plasma") || caliber.equals("laser")) {
            thermal = true;
        }
        if (tag.contains("incendiary") || tag.contains("dragon")) {
            thermal = true;
        }

        double impact = dmg * calFactor * 0.45 * fragMult;
        double pen = (dmg * calFactor * 0.55 + armorPen * 1.8) * penMult;
        float penNorm = (float) Math.min(1.0, pen / 80.0);
        float crackScale = switch (caliber) {
            case "pistol", "handgun" -> 0.75f;
            case "sniper", "heavy" -> 1.45f;
            case "shotgun", "shot" -> 1.35f;
            case "rocket", "launcher" -> 2.1f;
            case "energy", "plasma", "laser" -> 1.15f;
            case "arrow", "bolt" -> 0.55f;
            default -> 1.0f; // rifle
        };
        if (family == AmmoFamily.HP) {
            crackScale *= 1.25f; // wider surface shatter, shallow
        } else if (family == AmmoFamily.AP) {
            crackScale *= 0.85f; // tighter hole, less web
        }
        return new BallisticsProfile(impact, pen, fragMult, retainBonus, thermal, caliber, penNorm, family, crackScale);
    }

    private static AmmoFamily classify(String tag, String caliber) {
        if (tag.contains("hollow") || tag.contains("_hp") || tag.contains(" hp")
                || tag.startsWith("hp_") || tag.contains("hollowpoint")) {
            return AmmoFamily.HP;
        }
        if (tag.contains("_ap") || tag.contains(" ap") || tag.startsWith("ap_")
                || tag.contains("armor pierc") || tag.contains("armorpierc")) {
            return AmmoFamily.AP;
        }
        if (tag.contains("slug")) {
            return AmmoFamily.SLUG;
        }
        if (tag.contains("buck")) {
            return AmmoFamily.BUCK;
        }
        if (caliber.equals("rocket") || caliber.equals("launcher")
                || tag.contains("_he") || tag.contains(" he") || tag.startsWith("he_")
                || tag.contains("explosive") || tag.contains("high explosive")) {
            return AmmoFamily.HE;
        }
        if (tag.contains("incendiary") || tag.contains("dragon")
                || caliber.equals("energy") || caliber.equals("plasma") || caliber.equals("laser")) {
            return AmmoFamily.THERMAL;
        }
        if (tag.contains("fmj") || tag.contains("full metal")) {
            return AmmoFamily.FMJ;
        }
        // Default ball by caliber
        return switch (caliber) {
            case "shotgun", "shot" -> AmmoFamily.BUCK;
            case "rocket", "launcher" -> AmmoFamily.HE;
            default -> AmmoFamily.FMJ;
        };
    }

    /** Soft cover: wood, doors, leaves-adjacent solids already handled elsewhere. */
    public boolean penetratesSoft() {
        return switch (family) {
            case HP, BUCK -> penNorm >= 0.55f; // only hot loads
            case HE, AP, FMJ, SLUG, THERMAL, STANDARD -> true;
        };
    }

    /** Hard cover: stone / concrete / brick. */
    public boolean penetratesHard() {
        return switch (family) {
            case AP -> penNorm >= 0.35f;
            case HE, SLUG -> penNorm >= 0.55f;
            case FMJ -> penNorm >= 0.85f && (caliber.equals("sniper") || caliber.equals("heavy"));
            default -> false;
        };
    }

    public boolean canRicochet() {
        return switch (family) {
            case HP, BUCK, HE -> false;
            default -> true;
        };
    }

    /** Energy kept after soft pen (0–1). */
    public double softRetain() {
        double base = switch (family) {
            case AP -> 0.88;
            case FMJ, STANDARD -> 0.78;
            case SLUG -> 0.70;
            case THERMAL -> 0.65;
            case HP -> 0.40;
            case BUCK -> 0.35;
            case HE -> 0.55;
        };
        return Math.min(0.95, base * retainBonus);
    }

    public double hardRetain() {
        double base = switch (family) {
            case AP -> 0.62;
            case HE -> 0.45;
            case SLUG -> 0.40;
            case FMJ -> 0.35;
            default -> 0.25;
        };
        return Math.min(0.75, base * retainBonus);
    }

    public double ricochetRetain() {
        return switch (family) {
            case AP -> 0.55;
            case FMJ, STANDARD -> 0.72;
            case SLUG -> 0.50;
            case THERMAL -> 0.45;
            default -> 0.4;
        };
    }
}
