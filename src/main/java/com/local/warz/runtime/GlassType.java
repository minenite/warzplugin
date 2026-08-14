package com.local.warz.runtime;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Tactical / architectural glass variants with distinct ballistic behavior.
 * Each type has a full block and a pane form (vanilla materials used as skins).
 */
public enum GlassType {
    // penRetain = fraction of speed kept after a through-shot (brittle glass keeps most of it)
    STANDARD("standard", "Standard Window Glass",
            Material.GLASS, Material.GLASS_PANE,
            8, 0.12, 0.95, 0.90, Shatter.INSTANT, Visual.SPIDERWEB_COLLAPSE),
    TEMPERED("tempered", "Tempered Glass",
            Material.WHITE_STAINED_GLASS, Material.WHITE_STAINED_GLASS_PANE,
            22, 0.28, 0.55, 0.84, Shatter.DICE, Visual.CRAZE_COLLAPSE),
    LAMINATED("laminated", "Laminated Glass",
            Material.LIGHT_BLUE_STAINED_GLASS, Material.LIGHT_BLUE_STAINED_GLASS_PANE,
            40, 0.55, 0.18, 0.55, Shatter.HOLD, Visual.DENSE_WEB),
    AUTO_WINDSHIELD("auto_windshield", "Automotive Windshield",
            Material.LIGHT_GRAY_STAINED_GLASS, Material.LIGHT_GRAY_STAINED_GLASS_PANE,
            48, 0.50, 0.20, 0.62, Shatter.PUNCH_HOLE, Visual.OPAQUE_HOLE),
    AUTO_SIDE("auto_side", "Automotive Side Glass",
            Material.GRAY_STAINED_GLASS, Material.GRAY_STAINED_GLASS_PANE,
            20, 0.28, 0.60, 0.86, Shatter.DICE, Visual.CRAZE_COLLAPSE),
    WIRED("wired", "Wired Glass",
            Material.BROWN_STAINED_GLASS, Material.BROWN_STAINED_GLASS_PANE,
            36, 0.48, 0.22, 0.48, Shatter.HOLD, Visual.MESH),
    POLYCARBONATE("polycarbonate", "Polycarbonate",
            Material.CYAN_STAINED_GLASS, Material.CYAN_STAINED_GLASS_PANE,
            90, 0.72, 0.05, 0.40, Shatter.FLEX, Visual.DENT),
    ACRYLIC("acrylic", "Acrylic / Plexiglas",
            Material.BLUE_STAINED_GLASS, Material.BLUE_STAINED_GLASS_PANE,
            34, 0.42, 0.25, 0.58, Shatter.HOLD, Visual.RADIAL),
    BR_LAMINATED("br_laminated", "Bullet-Resistant Laminated",
            Material.GREEN_STAINED_GLASS, Material.GREEN_STAINED_GLASS_PANE,
            120, 0.82, 0.08, 0.42, Shatter.HOLD, Visual.WHITE_ZONE),
    BALLISTIC_THICK("ballistic_thick", "Thick Ballistic Glass",
            Material.LIME_STAINED_GLASS, Material.LIME_STAINED_GLASS_PANE,
            180, 0.92, 0.04, 0.35, Shatter.CRATER, Visual.CRATER_CLOUDY),
    ONE_WAY("one_way", "One-Way / Mirrored Glass",
            Material.TINTED_GLASS, Material.BLACK_STAINED_GLASS_PANE,
            28, 0.32, 0.50, 0.88, Shatter.INSTANT, Visual.MIRROR_FLAKE),
    BOROSILICATE("borosilicate", "Heat-Resistant Borosilicate",
            Material.ORANGE_STAINED_GLASS, Material.ORANGE_STAINED_GLASS_PANE,
            26, 0.28, 0.85, 0.82, Shatter.INSTANT, Visual.SHARP_RADIAL),
    FUSED_QUARTZ("fused_quartz", "Fused Quartz",
            Material.PINK_STAINED_GLASS, Material.PINK_STAINED_GLASS_PANE,
            32, 0.36, 0.80, 0.78, Shatter.INSTANT, Visual.CONCHOIDAL),
    GLASS_CERAMIC("glass_ceramic", "Glass-Ceramic",
            Material.PURPLE_STAINED_GLASS, Material.PURPLE_STAINED_GLASS_PANE,
            44, 0.45, 0.45, 0.52, Shatter.HOLD, Visual.DENSE_LOCAL);

    public enum Shatter {
        /** Shatters and clears the block quickly; high chance of pass-through. */
        INSTANT,
        /** Whole pane dices into cubes then collapses. */
        DICE,
        /** Cracks heavily but stays until integrity is gone. */
        HOLD,
        /** Punches a hole / can pen while glass stays framed longer. */
        PUNCH_HOLE,
        /** Flexes; rarely fully removes; mostly dents. */
        FLEX,
        /** Progressive crater / cloudy zone until collapse. */
        CRATER
    }

    public enum Visual {
        SPIDERWEB_COLLAPSE,
        CRAZE_COLLAPSE,
        DENSE_WEB,
        OPAQUE_HOLE,
        MESH,
        DENT,
        RADIAL,
        WHITE_ZONE,
        CRATER_CLOUDY,
        MIRROR_FLAKE,
        SHARP_RADIAL,
        CONCHOIDAL,
        DENSE_LOCAL
    }

    private final String id;
    private final String displayName;
    private final Material blockMaterial;
    private final Material paneMaterial;
    /** Hit points for a full block (panes use ~55%). */
    private final double integrity;
    /** 0–1 resistance to punch-through. */
    private final double penResist;
    /** 0–1 fragmentation intensity when broken. */
    private final double fragmentation;
    /** Velocity multiplier retained after a penetrating hit (lower = more energy loss). */
    private final double penRetain;
    private final Shatter shatter;
    private final Visual visual;

    GlassType(String id, String displayName, Material blockMaterial, Material paneMaterial,
              double integrity, double penResist, double fragmentation, double penRetain,
              Shatter shatter, Visual visual) {
        this.id = id;
        this.displayName = displayName;
        this.blockMaterial = blockMaterial;
        this.paneMaterial = paneMaterial;
        this.integrity = integrity;
        this.penResist = penResist;
        this.fragmentation = fragmentation;
        this.penRetain = penRetain;
        this.shatter = shatter;
        this.visual = visual;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Material blockMaterial() {
        return blockMaterial;
    }

    public Material paneMaterial() {
        return paneMaterial;
    }

    public double integrity() {
        return integrity;
    }

    public double penResist() {
        return penResist;
    }

    public double fragmentation() {
        return fragmentation;
    }

    public double penRetain() {
        return penRetain;
    }

    public Shatter shatter() {
        return shatter;
    }

    public Visual visual() {
        return visual;
    }

    public double integrityFor(boolean pane) {
        return pane ? integrity * 0.55 : integrity;
    }

    public static GlassType fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (GlassType t : values()) {
            if (t.id.equals(key) || t.name().equalsIgnoreCase(key)) {
                return t;
            }
        }
        return null;
    }
}
