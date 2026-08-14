package com.local.warz.runtime;

import org.bukkit.Material;

/**
 * Tactical smoke grenade variants. Optical fields drive companion NVG / thermal response.
 */
public enum SmokeType {
    WHITE(0, "White Smoke", "&fWhite Smoke", Material.SNOWBALL, 0xD0D4D8,
            1.0f, 0.85f, 0.22f, 18.0f, 20 * 75, 0.45f, 1.2f, 0.55f, 3.0f, false,
            "&7Standard concealment cloud"),
    BLACK(1, "Black Smoke", "&8Black Smoke", Material.INK_SAC, 0x1A1A1E,
            1.0f, 0.95f, 0.35f, 20.0f, 20 * 80, 0.4f, 1.25f, 0.5f, 3.4f, false,
            "&7Dense marker smoke — wrecks / LZs"),
    RED(2, "Red Smoke", "&cRed Smoke", Material.RED_DYE, 0xC62828,
            0.95f, 0.45f, 0.14f, 14.0f, 20 * 90, 0.45f, 0.9f, 0.5f, 6.0f, false,
            "&7Target / location marker"),
    GREEN(3, "Green Smoke", "&aGreen Smoke", Material.LIME_DYE, 0x43A047,
            0.95f, 0.45f, 0.14f, 14.0f, 20 * 90, 0.45f, 0.9f, 0.5f, 6.0f, false,
            "&7Friendly / LZ marker"),
    YELLOW(4, "Yellow Smoke", "&eYellow Smoke", Material.YELLOW_DYE, 0xF9A825,
            0.95f, 0.45f, 0.14f, 14.0f, 20 * 90, 0.45f, 0.9f, 0.5f, 6.0f, false,
            "&7Warning / objective marker"),
    ORANGE(5, "Orange Smoke", "&6Orange Smoke", Material.ORANGE_DYE, 0xEF6C00,
            0.95f, 0.45f, 0.14f, 14.0f, 20 * 90, 0.45f, 0.9f, 0.5f, 6.0f, false,
            "&7Extraction / resupply marker"),
    PURPLE(6, "Purple Smoke", "&dPurple Smoke", Material.PURPLE_DYE, 0x8E24AA,
            0.95f, 0.45f, 0.14f, 14.0f, 20 * 90, 0.45f, 0.9f, 0.5f, 6.0f, false,
            "&7Special-team / event marker"),
    BLUE(7, "Blue Smoke", "&9Blue Smoke", Material.BLUE_DYE, 0x1E88E5,
            0.95f, 0.45f, 0.14f, 14.0f, 20 * 90, 0.45f, 0.9f, 0.5f, 6.0f, false,
            "&7Friendly-position marker"),
    IR(8, "IR Smoke", "&bIR Smoke", Material.ENDER_EYE, 0x88AACC,
            0.3f, 1.0f, 0.1f, 16.0f, 20 * 70, 0.35f, 1.0f, 0.55f, 3.2f, true,
            "&7Mostly invisible to eye — blooms under NVG/IR"),
    THERMAL(9, "Thermal-Obscuring Smoke", "&6Thermal Smoke", Material.BLAZE_POWDER, 0xB0A090,
            0.9f, 0.4f, 1.0f, 17.0f, 20 * 70, 0.3f, 1.1f, 0.55f, 3.2f, false,
            "&7Heavy FLIR / thermal camera degrade"),
    MULTISPECTRAL(10, "Multispectral Smoke", "&5Multispectral Smoke", Material.NETHER_STAR, 0x9E9E9E,
            1.0f, 1.0f, 1.0f, 22.0f, 20 * 85, 0.35f, 1.0f, 0.5f, 3.6f, false,
            "&7Blocks eye, NVG, and thermal together"),
    SIGNAL(11, "Signal Smoke", "&fSignal Smoke", Material.FIREWORK_ROCKET, 0xECEFF1,
            0.6f, 0.35f, 0.12f, 12.0f, 20 * 120, 0.5f, 0.55f, 0.35f, 12.0f, false,
            "&7Tall long-duration marking plume"),
    QUICK(12, "Quick Smoke", "&7Quick Smoke", Material.SUGAR, 0xCFD8DC,
            1.0f, 0.9f, 0.28f, 12.0f, 20 * 28, 0.25f, 2.4f, 1.4f, 2.4f, false,
            "&7Fast dense burst — dissipates quickly"),
    PERSISTENT(13, "Persistent Smoke", "&7Persistent Smoke", Material.SLIME_BALL, 0xB0BEC5,
            1.0f, 0.85f, 0.3f, 28.0f, 20 * 150, 0.45f, 0.35f, 0.22f, 4.0f, false,
            "&7Slow build, large cloud, long hang time"),
    WIND(14, "Wind-Driven Smoke", "&3Wind-Driven Smoke", Material.FEATHER, 0x90A4AE,
            0.98f, 0.8f, 0.24f, 18.0f, 20 * 85, 1.5f, 1.0f, 0.6f, 3.2f, false,
            "&7Drifts hard with simulated wind");

    private final byte id;
    private final String plainName;
    private final String displayName;
    private final Material icon;
    private final int rgb;
    private final float densityMax;
    private final float nvgWash;
    private final float thermalBlock;
    private final float maxRadius;
    private final int lifeTicks;
    private final float windFactor;
    private final float buildRate;
    private final float dissipateRate;
    private final float riseHeight;
    private final boolean irPrimary;
    private final String blurb;

    SmokeType(int id, String plainName, String displayName, Material icon, int rgb,
              float densityMax, float nvgWash, float thermalBlock, float maxRadius, int lifeTicks,
              float windFactor, float buildRate, float dissipateRate, float riseHeight,
              boolean irPrimary, String blurb) {
        this.id = (byte) id;
        this.plainName = plainName;
        this.displayName = displayName;
        this.icon = icon;
        this.rgb = rgb & 0xFFFFFF;
        this.densityMax = densityMax;
        this.nvgWash = nvgWash;
        this.thermalBlock = thermalBlock;
        this.maxRadius = maxRadius;
        this.lifeTicks = lifeTicks;
        this.windFactor = windFactor;
        this.buildRate = buildRate;
        this.dissipateRate = dissipateRate;
        this.riseHeight = riseHeight;
        this.irPrimary = irPrimary;
        this.blurb = blurb;
    }

    public byte id() {
        return id;
    }

    public String plainName() {
        return plainName;
    }

    public String displayName() {
        return displayName;
    }

    public Material icon() {
        return icon;
    }

    public int rgb() {
        return rgb;
    }

    public float densityMax() {
        return densityMax;
    }

    public float nvgWash() {
        return nvgWash;
    }

    public float thermalBlock() {
        return thermalBlock;
    }

    public float maxRadius() {
        return maxRadius;
    }

    public int lifeTicks() {
        return lifeTicks;
    }

    public float windFactor() {
        return windFactor;
    }

    public float buildRate() {
        return buildRate;
    }

    public float dissipateRate() {
        return dissipateRate;
    }

    public float riseHeight() {
        return riseHeight;
    }

    public boolean irPrimary() {
        return irPrimary;
    }

    public String blurb() {
        return blurb;
    }

    public String fileKey() {
        return name().toLowerCase();
    }

    public static SmokeType byId(int id) {
        for (SmokeType t : values()) {
            if (t.id == id) {
                return t;
            }
        }
        return WHITE;
    }

    public static SmokeType byKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String k = key.trim().toLowerCase().replace('-', '_').replace(' ', '_');
        for (SmokeType t : values()) {
            if (t.fileKey().equals(k) || t.plainName.toLowerCase().replace(' ', '_').equals(k)) {
                return t;
            }
        }
        return null;
    }
}
