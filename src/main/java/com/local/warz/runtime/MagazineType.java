package com.local.warz.runtime;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Detachable magazines / clips. Caliber must match {@link com.local.warz.config.AmmoCaliber}.
 */
public enum MagazineType {
    STANAG_556("stanag_556", "&eSTANAG Mag", "rifle", 30, Material.IRON_NUGGET, 3001,
            "&7Fits &fAR-platform &7rifles (AK needs adapter)"),
    AK_762("ak_762", "&6AK Mag", "rifle", 30, Material.IRON_NUGGET, 3002,
            "&7Fits &fAK-platform &7rifles (AR needs adapter)"),
    PMAG_40("pmag_40", "&aPMAG 40", "rifle", 40, Material.IRON_NUGGET, 3003,
            "&7Extended &fAR &7magazine"),
    SMG_32("smg_32", "&eSMG Mag", "rifle", 32, Material.IRON_NUGGET, 3004,
            "&7Fits &fSMG / PDW &7+ AR-platform rifles"),
    PISTOL_15("pistol_15", "&e9mm Mag", "pistol", 15, Material.GOLD_NUGGET, 3010,
            "&7Fits &f9mm pistol &7weapons"),
    PISTOL_45_8("pistol_45_8", "&6.45 Mag", "pistol", 8, Material.GOLD_NUGGET, 3011,
            "&7Fits &f.45 ACP &7pistols / UMP"),
    DRUM_50("drum_50", "&c50rd Drum Mag", "rifle", 50, Material.IRON_NUGGET, 3005,
            "&7High-cap &fAR &7drum (50)"),
    SNIPER_5("sniper_5", "&b.50 Mag", "sniper", 5, Material.CLAY_BALL, 3020,
            "&7Fits &f.50 sniper &7only — no AR/AK adapter"),
    SNIPER_10("sniper_10", "&b.50 Extended", "sniper", 10, Material.CLAY_BALL, 3021,
            "&7Extended &f.50 &7only — no AR/AK adapter"),
    SHOT_8("shot_8", "&eShotgun Clip", "shotgun", 8, Material.WHEAT_SEEDS, 3030,
            "&7Box mag for &fshotguns &7only"),
    SHOT_5("shot_5", "&eShell Clip", "shotgun", 5, Material.WHEAT_SEEDS, 3031,
            "&7Short &fshotgun &7clip only");

    private final String id;
    private final String displayName;
    private final String caliber;
    private final int capacity;
    private final Material material;
    private final int customModelData;
    private final String fitLore;

    MagazineType(String id, String displayName, String caliber, int capacity,
                 Material material, int customModelData, String fitLore) {
        this.id = id;
        this.displayName = displayName;
        this.caliber = caliber;
        this.capacity = capacity;
        this.material = material;
        this.customModelData = customModelData;
        this.fitLore = fitLore;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String caliber() {
        return caliber;
    }

    public int capacity() {
        return capacity;
    }

    public Material material() {
        return material;
    }

    /** Empty magazine / clip model CMD. */
    public int customModelData() {
        return customModelData;
    }

    /**
     * Loaded magazine / clip model CMD (visible rounds).
     * Empty CMD + 500 → 3501–3531 range in the companion pack.
     */
    public int loadedCustomModelData() {
        return customModelData + 500;
    }

    public int customModelData(boolean loaded) {
        return loaded ? loadedCustomModelData() : customModelData();
    }

    public String fitLore() {
        return fitLore;
    }

    public MagPlatform platform() {
        return MagPlatform.forMagazine(this);
    }

    public static MagazineType fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (MagazineType t : values()) {
            if (t.id.equals(key) || t.name().equalsIgnoreCase(key)) {
                return t;
            }
        }
        return null;
    }

    public static MagazineType[] forCaliber(String caliber) {
        String c = caliber == null ? "" : caliber.trim().toLowerCase(Locale.ROOT);
        return java.util.Arrays.stream(values())
                .filter(t -> t.caliber.equals(c))
                .toArray(MagazineType[]::new);
    }
}
