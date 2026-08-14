package com.local.warz.runtime;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;

import java.util.Locale;

/** Road-flare color variants (item look + burn plume). */
public enum FlareColor {
    RED("red", "&cRed Road Flare", Material.REDSTONE_TORCH,
            Color.fromRGB(0xFF2A1A), Color.fromRGB(0xE8A020), true, false),
    GREEN("green", "&aGreen Road Flare", Material.LIME_CANDLE,
            Color.fromRGB(0x2AFF3A), Color.fromRGB(0x90C040), false, false),
    BLUE("blue", "&bBlue Road Flare", Material.SOUL_TORCH,
            Color.fromRGB(0x2A80FF), Color.fromRGB(0x70A0C8), false, true);

    private final String id;
    private final String displayName;
    private final Material material;
    private final Color tip;
    private final Color smoke;
    private final boolean useFlame;
    private final boolean useSoulFlame;

    FlareColor(String id, String displayName, Material material,
               Color tip, Color smoke, boolean useFlame, boolean useSoulFlame) {
        this.id = id;
        this.displayName = displayName;
        this.material = material;
        this.tip = tip;
        this.smoke = smoke;
        this.useFlame = useFlame;
        this.useSoulFlame = useSoulFlame;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Material material() {
        return material;
    }

    public Color tipColor() {
        return tip;
    }

    public Color smokeColor() {
        return smoke;
    }

    public boolean useFlame() {
        return useFlame;
    }

    public boolean useSoulFlame() {
        return useSoulFlame;
    }

    public Particle tipFlameParticle() {
        if (useSoulFlame) {
            return Particle.SOUL_FIRE_FLAME;
        }
        return Particle.FLAME;
    }

    /** Non-solid emitters Complementary ACT already tints (hidden on companion clients). */
    public Material colorLightBlock() {
        return switch (this) {
            case RED -> Material.REDSTONE_TORCH;
            case GREEN -> Material.LIME_CANDLE;
            case BLUE -> Material.SOUL_TORCH;
        };
    }

    public boolean colorLightIsCandle() {
        return this == GREEN;
    }

    /** Packed RGB for companion bloom glow. */
    public int bloomRgb() {
        return (tip.getRed() << 16) | (tip.getGreen() << 8) | tip.getBlue();
    }

    public byte colorId() {
        return switch (this) {
            case RED -> 0;
            case GREEN -> 1;
            case BLUE -> 2;
        };
    }

    public static FlareColor fromColorId(int id) {
        return switch (id) {
            case 1 -> GREEN;
            case 2 -> BLUE;
            default -> RED;
        };
    }

    public static FlareColor fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return RED;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (FlareColor c : values()) {
            if (c.id.equals(key) || c.name().equalsIgnoreCase(key)) {
                return c;
            }
        }
        return RED;
    }
}
