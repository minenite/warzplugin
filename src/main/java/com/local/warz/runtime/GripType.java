package com.local.warz.runtime;

import com.local.warz.model.GunDefinition;
import org.bukkit.Material;

import java.util.Arrays;
import java.util.Locale;

/** Foregrip / bipod rail attachment. */
public enum GripType {
    NONE("none", "&7Grip: &8None", Material.GRAY_DYE, 1.0, 1.0, 1.0, 1.0),
    VERTICAL("vertical", "&7Vertical Grip", Material.STICK, 0.78, 0.92, 0.90, 1.0),
    ANGLED("angled", "&7Angled Grip", Material.TRIPWIRE_HOOK, 0.88, 0.85, 0.82, 1.0),
    BIPOD("bipod", "&7Bipod", Material.IRON_BARS, 0.90, 0.95, 0.70, 0.45),
    HANDSTOP("handstop", "&7Handstop", Material.OAK_BUTTON, 0.95, 0.82, 0.95, 1.0);

    private final String id;
    private final String displayName;
    private final Material icon;
    private final double recoilMult;
    private final double hipfireSpreadMult;
    private final double adsSwayMult;
    private final double restSwayMult;

    GripType(String id, String displayName, Material icon,
             double recoilMult, double hipfireSpreadMult, double adsSwayMult, double restSwayMult) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.recoilMult = recoilMult;
        this.hipfireSpreadMult = hipfireSpreadMult;
        this.adsSwayMult = adsSwayMult;
        this.restSwayMult = restSwayMult;
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

    /** Iron-nugget CMD for 3D part / held-gun overlay (3150–3153). NONE → -1. */
    public int customModelData() {
        if (!isInstalled()) {
            return -1;
        }
        return 3149 + ordinal(); // VERTICAL=3150 … HANDSTOP=3153
    }

    public boolean isInstalled() {
        return this != NONE;
    }

    public double recoilMult() {
        return recoilMult;
    }

    public double hipfireSpreadMult() {
        return hipfireSpreadMult;
    }

    public double adsSwayMult() {
        return adsSwayMult;
    }

    /** Multiplier on sway when rested / bipod-deployed (&lt;1 = steadier). */
    public double restSwayMult() {
        return restSwayMult;
    }

    public boolean isBipod() {
        return this == BIPOD;
    }

    public boolean fits(GunDefinition def) {
        return OpticType.acceptsRail(def);
    }

    public static GripType[] installable() {
        return Arrays.stream(values()).filter(GripType::isInstalled).toArray(GripType[]::new);
    }

    public static GripType fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (GripType g : values()) {
            if (g.id.equals(key) || g.name().equalsIgnoreCase(key)) {
                return g;
            }
        }
        return NONE;
    }
}
