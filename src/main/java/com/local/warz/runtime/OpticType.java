package com.local.warz.runtime;

import com.local.warz.model.GunDefinition;
import org.bukkit.Material;

import java.util.Arrays;
import java.util.Locale;

/**
 * Rail optic attachment. Bare gun (no PDC) resolves to {@link #IRONS}.
 * Javelin / LAW never use these — keep those code paths separate.
 */
public enum OpticType {
    IRONS("irons", "&7Ironsights", Material.IRON_NUGGET, HudKind.IRONS,
            false, 0.92f, 0.92f, 0.92f, 1.0, 1.0),
    RDS("rds", "&cRed Dot", Material.REDSTONE, HudKind.RDS,
            false, 0.85f, 0.85f, 0.85f, 0.95, 0.9),
    EOTECH("eotech", "&6EOTech Holo", Material.GLOWSTONE_DUST, HudKind.EOTECH,
            false, 0.82f, 0.82f, 0.82f, 0.94, 0.88),
    HOLO_CIRCLE("holo_circle", "&eCircle Holo", Material.GOLD_NUGGET, HudKind.HOLO_CIRCLE,
            false, 0.82f, 0.82f, 0.82f, 0.94, 0.88),
    ACOG("acog", "&aACOG 4x", Material.EMERALD, HudKind.ACOG,
            false, 0.55f, 0.50f, 0.48f, 0.88, 0.75),
    SCOPE_6X("scope_6x", "&b6x Scope", Material.SPYGLASS, HudKind.SCOPE,
            true, 0.42f, 0.30f, 0.22f, 0.85, 0.65),
    SCOPE_8X("scope_8x", "&b8x Scope", Material.ENDER_EYE, HudKind.SCOPE,
            true, 0.42f, 0.28f, 0.18f, 0.82, 0.55),
    SCOPE_BARRETT("scope_barrett", "&eM82 Optic", Material.NETHER_STAR, HudKind.SCOPE,
            true, 0.42f, 0.28f, 0.18f, 0.80, 0.52);

    public enum HudKind {
        IRONS((byte) 0),
        RDS((byte) 1),
        EOTECH((byte) 2),
        HOLO_CIRCLE((byte) 3),
        ACOG((byte) 4),
        SCOPE((byte) 5);

        private final byte wire;

        HudKind(byte wire) {
            this.wire = wire;
        }

        public byte wire() {
            return wire;
        }

        public static HudKind fromWire(int b) {
            for (HudKind h : values()) {
                if (h.wire == (byte) b) {
                    return h;
                }
            }
            return IRONS;
        }
    }

    private final String id;
    private final String displayName;
    private final Material icon;
    private final HudKind hudKind;
    private final boolean allowsZeroing;
    private final float fov100;
    private final float fov200;
    private final float fov300;
    private final double adsSpreadMult;
    private final double swayMult;

    OpticType(String id, String displayName, Material icon, HudKind hudKind,
              boolean allowsZeroing, float fov100, float fov200, float fov300,
              double adsSpreadMult, double swayMult) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.hudKind = hudKind;
        this.allowsZeroing = allowsZeroing;
        this.fov100 = fov100;
        this.fov200 = fov200;
        this.fov300 = fov300;
        this.adsSpreadMult = adsSpreadMult;
        this.swayMult = swayMult;
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

    /**
     * CustomModelData for optic part items / held-gun overlays (iron_nugget CMD range).
     * 3100–3107 reserved for rail optics.
     */
    public int customModelData() {
        return 3100 + ordinal();
    }

    public HudKind hudKind() {
        return hudKind;
    }

    public boolean allowsZeroing() {
        return allowsZeroing;
    }

    public boolean laserTintsReticle() {
        return this == EOTECH || this == HOLO_CIRCLE;
    }

    public boolean magnifying() {
        return hudKind == HudKind.ACOG || hudKind == HudKind.SCOPE;
    }

    public boolean usesBreathHud() {
        return hudKind == HudKind.SCOPE || hudKind == HudKind.ACOG
                || hudKind == HudKind.EOTECH || hudKind == HudKind.HOLO_CIRCLE;
    }

    public float fovForZero(int zeroYards) {
        if (!allowsZeroing) {
            return fov100;
        }
        if (zeroYards >= 250) {
            return fov300;
        }
        if (zeroYards >= 150) {
            return fov200;
        }
        return fov100;
    }

    public double adsSpreadMult() {
        return adsSpreadMult;
    }

    public double swayMult() {
        return swayMult;
    }

    public boolean fits(GunDefinition def) {
        return acceptsRail(def);
    }

    /** Tube scopes (ACOG / magnifiers) — housing overlay while ADS. */
    public boolean isScopeTube() {
        return this == ACOG || magnifying();
    }

    /** Reflex / holo sights — reticle HUD without heavy housing. */
    public boolean isSight() {
        return this == RDS || this == EOTECH || this == HOLO_CIRCLE;
    }

    /** Combat firearms that take optic/grip rails. */
    public static boolean acceptsRail(GunDefinition def) {
        if (def == null) {
            return false;
        }
        String id = def.fileName() == null ? "" : def.fileName().toLowerCase(Locale.ROOT);
        if (id.contains("javelin") || id.equals("law") || id.equals("law_drone")
                || id.contains("throw") || id.contains("knife")
                || id.contains("flare") || id.contains("flame")
                || id.equals("m79") || id.equals("crossbow")
                || id.startsWith("example")) {
            return false;
        }
        MagPlatform p = MagPlatform.forGun(def);
        return p == MagPlatform.AR || p == MagPlatform.AK || p == MagPlatform.SNIPER
                || p == MagPlatform.SHOTGUN || p == MagPlatform.PISTOL_9
                || p == MagPlatform.PISTOL_45 || p == MagPlatform.SMG;
    }

    /** Installable parts (includes explicit irons). */
    public static OpticType[] installable() {
        return values();
    }

    public static OpticType fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return IRONS;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (OpticType o : values()) {
            if (o.id.equals(key) || o.name().equalsIgnoreCase(key)) {
                return o;
            }
        }
        return IRONS;
    }
}
