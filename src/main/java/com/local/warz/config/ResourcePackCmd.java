package com.local.warz.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Maps WarZ gun file names to CustomModelData values from the bone resource pack
 * ({@code assets/minecraft/items/bone.json} range_dispatch thresholds).
 * Shields / unused pack entries are intentionally not wired.
 */
public final class ResourcePackCmd {
    /** Fallback CMD below pack thresholds → vanilla bone look. */
    public static final int FALLBACK_CMD = 1;

    private static final Map<String, Integer> BY_GUN;
    private static final Map<Integer, String> PACK_MODEL;

    static {
        Map<String, Integer> guns = new LinkedHashMap<>();
        Map<Integer, String> models = new LinkedHashMap<>();

        // Exact / closest matches from testpack bone.json (skip shields 1055–1060).
        // Prefer baked HWG 3D where available; remaining firearms use fixed SAG bake.
        put(guns, models, "usp45", 1001, "hwg/pistol");
        put(guns, models, "m9", 1002, "hwg/luger");
        put(guns, models, "deserteagle", 1006, "hwg/golden_gun");
        put(guns, models, "mac10", 1007, "hwg/smg");
        put(guns, models, "typhoid", 1012, "hwg/tommy_gun");
        put(guns, models, "demolisher", 1013, "revolvers/needler"); // Ray-Gun (no 3D bake)
        put(guns, models, "python", 1015, "hwg/hellhorse_revolver");
        put(guns, models, "magnum", 1018, "hwg/hellhorse_revolver");
        put(guns, models, "mp7", 1020, "hwg/smg");
        put(guns, models, "ump45", 1021, "hwg/tommy_gun");
        put(guns, models, "pp90m1", 1023, "hwg/smg");
        put(guns, models, "p90", 1024, "hwg/smg");
        put(guns, models, "m4a1", 1025, "sag/assaultrifle_light");
        put(guns, models, "famas", 1026, "sag/assaultrifle_heavy");
        put(guns, models, "m16", 1027, "sag/assaultrifle_light");
        put(guns, models, "lemantation", 1028, "sag/assaultrifle_heavy");
        put(guns, models, "ak47", 1030, "hwg/ak47");
        put(guns, models, "disassembler", 1034, "sag/lmg_m60");
        put(guns, models, "minigun", 1036, "hwg/minigun");
        put(guns, models, "m1014", 1037, "hwg/shotgun");
        put(guns, models, "aa12", 1038, "hwg/shotgun");
        put(guns, models, "spas12", 1039, "hwg/shotgun");
        put(guns, models, "spas24", 1040, "hwg/shotgun");
        put(guns, models, "executioner", 1041, "shotguns/judgement");
        put(guns, models, "moddel1887", 1042, "sag/shotgun_doublebarrel");
        put(guns, models, "law", 1043, "hwg/rocketlauncher");
        put(guns, models, "javelin", 1043, "hwg/rocketlauncher");
        put(guns, models, "m79", 1044, "hwg/grenade_launcher");
        put(guns, models, "flamethrower", 1045, "hwg/flamethrower");
        put(guns, models, "l118a", 1049, "hwg/sniper_rifle");
        put(guns, models, "barret50c", 1050, "hwg/sniper_rifle");
        put(guns, models, "l120_isolator", 1051, "hwg/sniper_rifle");
        put(guns, models, "dragunov", 1052, "hwg/sniper_rifle");
        put(guns, models, "crossbow", 1053, "snipers/crossbow");
        put(guns, models, "msr", 1054, "hwg/sniper_rifle");
        put(guns, models, "warz_fiveseven", 1002, "hwg/luger");
        put(guns, models, "warz_m1911", 1001, "hwg/pistol");
        put(guns, models, "warz_deserteagle", 1006, "hwg/golden_gun");
        put(guns, models, "warz_uzi", 1007, "hwg/smg");
        put(guns, models, "warz_mp5", 1020, "hwg/smg");
        put(guns, models, "warz_ump45", 1021, "hwg/tommy_gun");
        put(guns, models, "warz_p90", 1024, "hwg/smg");
        put(guns, models, "warz_m4a1", 1025, "sag/assaultrifle_light");
        put(guns, models, "warz_famas", 1026, "sag/assaultrifle_heavy");
        put(guns, models, "warz_m16", 1027, "sag/assaultrifle_light");
        put(guns, models, "warz_ak47", 1030, "hwg/ak47");
        put(guns, models, "warz_m1014", 1037, "hwg/shotgun");
        put(guns, models, "warz_aa12", 1038, "hwg/shotgun");
        put(guns, models, "warz_spas12", 1039, "hwg/shotgun");
        put(guns, models, "warz_m590", 1042, "sag/shotgun_doublebarrel");
        put(guns, models, "warz_m40a3", 1049, "hwg/sniper_rifle");
        put(guns, models, "warz_barrett", 1050, "hwg/sniper_rifle");
        put(guns, models, "warz_dragunov", 1052, "hwg/sniper_rifle");
        put(guns, models, "warz_msr", 1054, "hwg/sniper_rifle");
        put(guns, models, "throwingknife", 1061, "melee/combatknife");
        put(guns, models, "skullcrusher", 1062, "melee/bat");
        put(guns, models, "flaregun", 1067, "hwg/flare_gun");

        // No dedicated grenade art in the pack → keep vanilla bone.
        put(guns, models, "flashbang", FALLBACK_CMD, "bone (fallback)");
        put(guns, models, "grenade", FALLBACK_CMD, "bone (fallback)");
        put(guns, models, "molotov", FALLBACK_CMD, "bone (fallback)");

        BY_GUN = Collections.unmodifiableMap(guns);
        PACK_MODEL = Collections.unmodifiableMap(models);
    }

    private ResourcePackCmd() {
    }

    private static void put(Map<String, Integer> guns, Map<Integer, String> models,
                            String gunId, int cmd, String packModel) {
        guns.put(gunId.toLowerCase(Locale.ROOT), cmd);
        models.putIfAbsent(cmd, packModel);
    }

    public static int forGun(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return FALLBACK_CMD;
        }
        return BY_GUN.getOrDefault(fileName.toLowerCase(Locale.ROOT), FALLBACK_CMD);
    }

    public static Optional<String> packModel(int cmd) {
        return Optional.ofNullable(PACK_MODEL.get(cmd));
    }

    public static Map<String, Integer> all() {
        return BY_GUN;
    }
}
