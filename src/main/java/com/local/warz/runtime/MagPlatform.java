package com.local.warz.runtime;

import com.local.warz.config.AmmoCaliber;
import com.local.warz.model.GunDefinition;

import java.util.Locale;
import java.util.Set;

/**
 * Magazine / receiver family. Caliber match is required first; platform must also match
 * unless an AK↔AR adapter is fitted (rifle calibers only — never .50 / sniper).
 */
public enum MagPlatform {
    AR,
    AK,
    SMG,
    PISTOL_9,
    PISTOL_45,
    SNIPER,
    SHOTGUN,
    /** Rare / misc — only same-caliber empty fit, no cross-platform. */
    OTHER;

    private static final Set<String> AR_IDS = Set.of(
            "m4a1", "m16", "famas", "minigun", "pp90m1", "p90", "mp7", "mac10",
            "warz_m4a1", "warz_m16", "warz_famas");
    private static final Set<String> AK_IDS = Set.of(
            "ak47", "typhoid", "warz_ak47");
    private static final Set<String> SMG_IDS = Set.of(
            "warz_mp5", "warz_uzi", "warz_p90", "warz_ump45");
    private static final Set<String> SNIPER_IDS = Set.of(
            "barret50c", "l118a", "l120_isolator", "msr", "dragunov", "skullcrusher",
            "warz_barrett", "warz_dragunov", "warz_msr", "warz_m40a3");
    private static final Set<String> SHOT_IDS = Set.of(
            "aa12", "spas12", "spas24", "m1014", "moddel1887",
            "warz_aa12", "warz_spas12", "warz_m1014", "warz_m590");
    private static final Set<String> PISTOL9_IDS = Set.of(
            "m9", "usp45", "warz_fiveseven");
    private static final Set<String> PISTOL45_IDS = Set.of(
            "ump45", "deserteagle", "python", "magnum", "executioner", "lemantation",
            "warz_m1911", "warz_deserteagle");

    public static MagPlatform forMagazine(MagazineType type) {
        if (type == null) {
            return OTHER;
        }
        return switch (type) {
            case STANAG_556, PMAG_40, DRUM_50 -> AR;
            case AK_762 -> AK;
            case SMG_32 -> SMG;
            case PISTOL_15 -> PISTOL_9;
            case PISTOL_45_8 -> PISTOL_45;
            case SNIPER_5, SNIPER_10 -> SNIPER;
            case SHOT_8, SHOT_5 -> SHOTGUN;
        };
    }

    public static MagPlatform forGun(GunDefinition gun) {
        if (gun == null) {
            return OTHER;
        }
        String id = gun.fileName() == null ? "" : gun.fileName().toLowerCase(Locale.ROOT);
        if (AR_IDS.contains(id)) {
            return AR;
        }
        if (AK_IDS.contains(id)) {
            return AK;
        }
        if (SMG_IDS.contains(id)) {
            return SMG;
        }
        if (SNIPER_IDS.contains(id)) {
            return SNIPER;
        }
        if (SHOT_IDS.contains(id)) {
            return SHOTGUN;
        }
        if (PISTOL45_IDS.contains(id) || id.contains("ump")) {
            return PISTOL_45;
        }
        if (PISTOL9_IDS.contains(id) || "pistol".equals(AmmoCaliber.normalize(gun.ammoCaliber()))) {
            return PISTOL_9;
        }
        if ("sniper".equals(AmmoCaliber.normalize(gun.ammoCaliber()))
                || "heavy".equals(AmmoCaliber.normalize(gun.ammoCaliber()))) {
            return SNIPER;
        }
        if ("shotgun".equals(AmmoCaliber.normalize(gun.ammoCaliber()))
                || "shot".equals(AmmoCaliber.normalize(gun.ammoCaliber()))) {
            return SHOTGUN;
        }
        if (id.contains("ak")) {
            return AK;
        }
        if (id.contains("m4") || id.contains("m16") || id.contains("ar15")) {
            return AR;
        }
        return OTHER;
    }

    /** True if an AK↔AR adapter can bridge these platforms (rifle only). */
    public static boolean adapterBridges(MagPlatform gun, MagPlatform mag) {
        if (gun == null || mag == null) {
            return false;
        }
        if (gun == SNIPER || mag == SNIPER || gun == SHOTGUN || mag == SHOTGUN) {
            return false;
        }
        return (gun == AR && mag == AK) || (gun == AK && mag == AR);
    }
}
