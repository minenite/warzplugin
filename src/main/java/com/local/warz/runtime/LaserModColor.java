package com.local.warz.runtime;

import org.bukkit.Color;
import org.bukkit.Material;

import java.util.Arrays;
import java.util.Locale;

/** Per-item laser module colors for gun attachments. */
public enum LaserModColor {
    NONE("none", "&7Laser: &8Off", Material.GRAY_DYE, null, false),
    // Visible greens must stay away from IR phosphor (client used to hide pure greens as NV-only).
    RED("red", "&7Laser: &cRed", Material.RED_DYE, Color.fromRGB(255, 40, 40), false),
    GREEN("green", "&7Laser: &aGreen", Material.LIME_DYE, Color.fromRGB(130, 255, 70), false),
    BLUE("blue", "&7Laser: &bBlue", Material.BLUE_DYE, Color.fromRGB(40, 120, 255), false),
    YELLOW("yellow", "&7Laser: &eYellow", Material.YELLOW_DYE, Color.fromRGB(255, 230, 40), false),
    ORANGE("orange", "&7Laser: &6Orange", Material.ORANGE_DYE, Color.fromRGB(255, 140, 30), false),
    PURPLE("purple", "&7Laser: &dPurple", Material.PURPLE_DYE, Color.fromRGB(180, 60, 255), false),
    CYAN("cyan", "&7Laser: &3Cyan", Material.CYAN_DYE, Color.fromRGB(40, 230, 255), false),
    PINK("pink", "&7Laser: &dPink", Material.PINK_DYE, Color.fromRGB(255, 80, 180), false),
    WHITE("white", "&7Laser: &fWhite", Material.WHITE_DYE, Color.fromRGB(255, 255, 255), false),
    IR("ir", "&7Laser: &aIR &8(NVG)", Material.ENDER_EYE, NvgGear.IR_PHOSPHOR, true);

    private final String id;
    private final String loreLine;
    private final Material icon;
    private final Color color;
    private final boolean infrared;

    LaserModColor(String id, String loreLine, Material icon, Color color, boolean infrared) {
        this.id = id;
        this.loreLine = loreLine;
        this.icon = icon;
        this.color = color;
        this.infrared = infrared;
    }

    public String id() {
        return id;
    }

    public String loreLine() {
        return loreLine;
    }

    public Material icon() {
        return icon;
    }

    /** Iron-nugget CMD for 3D part / held-gun overlay (3120–3129). NONE → -1. */
    public int customModelData() {
        if (!isInstalled()) {
            return -1;
        }
        return 3119 + ordinal(); // RED=3120 … IR=3129
    }

    public Color color() {
        return color;
    }

    public boolean infrared() {
        return infrared;
    }

    public boolean isInstalled() {
        return this != NONE;
    }

    /** All craftable / giveable laser modules (excludes {@link #NONE}). */
    public static LaserModColor[] installable() {
        return Arrays.stream(values()).filter(LaserModColor::isInstalled).toArray(LaserModColor[]::new);
    }

    public static LaserModColor fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (LaserModColor c : values()) {
            if (c.id.equals(key) || c.name().equalsIgnoreCase(key)) {
                return c;
            }
        }
        return NONE;
    }
}
