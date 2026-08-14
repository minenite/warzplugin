package com.local.warz.runtime;

import com.local.warz.config.AmmoCaliber;
import com.local.warz.model.GunDefinition;
import org.bukkit.Material;

import java.util.Locale;

/** Caliber-specific suppressors — a pistol can cannot take a Barrett can. */
public enum SuppressorType {
    PISTOL("pistol", "&7Pistol Suppressor", Material.IRON_NUGGET, "pistol",
            "&7Fits &fpistol &7/ handgun calibers"),
    RIFLE("rifle", "&7Rifle Suppressor", Material.IRON_INGOT, "rifle",
            "&7Fits &fassault / rifle &7calibers"),
    SNIPER("sniper", "&7Sniper Suppressor", Material.IRON_BLOCK, "sniper",
            "&7Fits &fsniper / heavy &7calibers (Barrett…)"),
    SHOTGUN("shotgun", "&7Shotgun Suppressor", Material.HOPPER, "shotgun",
            "&7Fits &fshotgun &7calibers");

    private final String id;
    private final String displayName;
    private final Material icon;
    private final String caliberGroup;
    private final String fitLore;

    SuppressorType(String id, String displayName, Material icon, String caliberGroup, String fitLore) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.caliberGroup = caliberGroup;
        this.fitLore = fitLore;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Material icon() {
        return icon;
    }

    /** Iron-nugget CMD for 3D part / held-gun overlay (3110–3113). */
    public int customModelData() {
        return 3110 + ordinal();
    }

    public String caliberGroup() {
        return caliberGroup;
    }

    public String fitLore() {
        return fitLore;
    }

    public boolean fits(GunDefinition def) {
        if (def == null) {
            return false;
        }
        String cal = AmmoCaliber.normalize(def.ammoCaliber());
        return switch (this) {
            case PISTOL -> cal.equals("pistol") || cal.equals("handgun");
            case RIFLE -> cal.equals("rifle") || cal.equals("auto");
            case SNIPER -> cal.equals("sniper") || cal.equals("heavy");
            case SHOTGUN -> cal.equals("shotgun") || cal.equals("shot");
        };
    }

    public static SuppressorType fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        // legacy generic "suppressor" / "1" → rifle
        if (key.equals("suppressor") || key.equals("1") || key.equals("true")) {
            return RIFLE;
        }
        for (SuppressorType t : values()) {
            if (t.id.equals(key) || t.name().equalsIgnoreCase(key)) {
                return t;
            }
        }
        return null;
    }

    /** Map a gun's caliber to the matching suppressor type, if any. */
    public static SuppressorType forGun(GunDefinition def) {
        if (def == null) {
            return null;
        }
        for (SuppressorType t : values()) {
            if (t.fits(def)) {
                return t;
            }
        }
        return null;
    }
}
