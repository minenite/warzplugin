package com.local.warz.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MQ-9 family airframes — same mesh / systems, different loadout & sensors.
 */
public enum BigDroneType {
    MQ9("mq9", "MQ-9 Reaper",
            600, 8, true, true, false, false, false, 1.0f, 1.0f, 8, 192.0,
            160, 1.0f),
    RQ4("rq4", "RQ-4 Global Hawk",
            2000, 0, true, true, false, true, false, 0.95f, 1.15f, 14, 320.0,
            180, 0.85f),
    MQ4C("mq4c", "MQ-4C Triton",
            2000, 0, true, true, true, true, false, 0.95f, 1.15f, 14, 320.0,
            180, 0.85f),
    MQ1C("mq1c", "MQ-1C Gray Eagle",
            150, 4, true, false, false, false, false, 0.9f, 0.72f, 8, 160.0,
            110, 1.45f),
    RQ170("rq170", "RQ-170 Sentinel",
            4000, 8, true, true, true, false, true, 1.05f, 1.0f, 10, 256.0,
            220, 0.55f),
    /** Dedicated Minecraft_UAV mesh (client {@code x47b_mesh.bin}); scale ~MQ-9 wingspan. */
    X47B("x47b", "X-47B",
            3000, 2, true, true, false, false, true, 1.55f, 1.0f, 10, 256.0,
            240, 0.5f),
    /** Spaceplane — unarmed, very fast, cargo bay, hydrazine leak when parked. */
    X37B("x37b", "X-37B",
            5000, 0, true, true, false, true, false, 3.5f, 1.0f, 12, 384.0,
            280, 0.45f);

    /** Gallons added by one Jet Fuel Can. */
    public static final int GAL_PER_CAN = 60;

    private final String id;
    private final String displayName;
    private final int fuelGal;
    private final int missileSlots;
    private final boolean nvg;
    private final boolean thermal;
    private final boolean waterVision;
    private final boolean wideArea;
    private final boolean stealth;
    private final float speedMult;
    private final float meshScale;
    private final int maxZoom;
    private final double farEntityRadius;
    private final int structureMax;
    /** Multiplier on small-arms / non-explosive structure damage (lower = tougher). */
    private final float bulletDamageTaken;

    BigDroneType(String id, String displayName, int fuelGal, int missileSlots,
                 boolean nvg, boolean thermal, boolean waterVision, boolean wideArea, boolean stealth,
                 float speedMult, float meshScale, int maxZoom, double farEntityRadius,
                 int structureMax, float bulletDamageTaken) {
        this.id = id;
        this.displayName = displayName;
        this.fuelGal = fuelGal;
        this.missileSlots = missileSlots;
        this.nvg = nvg;
        this.thermal = thermal;
        this.waterVision = waterVision;
        this.wideArea = wideArea;
        this.stealth = stealth;
        this.speedMult = speedMult;
        this.meshScale = meshScale;
        this.maxZoom = maxZoom;
        this.farEntityRadius = farEntityRadius;
        this.structureMax = structureMax;
        this.bulletDamageTaken = bulletDamageTaken;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public int fuelGal() {
        return fuelGal;
    }

    /** Max Jet Fuel Cans that fit the tank (ceil gallons / {@link #GAL_PER_CAN}). */
    public int maxFuelCans() {
        return Math.max(1, (fuelGal + GAL_PER_CAN - 1) / GAL_PER_CAN);
    }

    public int missileSlots() {
        return missileSlots;
    }

    public boolean hasNvg() {
        return nvg;
    }

    public boolean hasThermal() {
        return thermal;
    }

    public boolean waterVision() {
        return waterVision;
    }

    public boolean wideArea() {
        return wideArea;
    }

    /** Invisible to Javelin lock / guided seekers. */
    public boolean stealth() {
        return stealth;
    }

    public float speedMult() {
        return speedMult;
    }

    public float meshScale() {
        return meshScale;
    }

    public int maxZoom() {
        return maxZoom;
    }

    public double farEntityRadius() {
        return farEntityRadius;
    }

    public int structureMax() {
        return structureMax;
    }

    public float bulletDamageTaken() {
        return bulletDamageTaken;
    }

    /** General cargo bay (any items) instead of rocket hardpoints. */
    public boolean cargoBay() {
        return this == X37B;
    }

    public List<String> defaultRockets() {
        List<String> out = new ArrayList<>();
        if (missileSlots <= 0 || cargoBay()) {
            return out;
        }
        int n = Math.min(missileSlots, Math.min(3, missileSlots));
        if (this == MQ9) {
            n = Math.min(3, missileSlots);
        } else if (this == MQ1C) {
            n = Math.min(2, missileSlots);
        } else if (this == X47B || this == RQ170) {
            n = Math.min(missileSlots, this == X47B ? 2 : 3);
        }
        for (int i = 0; i < n; i++) {
            out.add(DronePadService.ROUND_HP);
        }
        return out;
    }

    public int defaultFuelCans() {
        return maxFuelCans();
    }

    public String loreId() {
        return switch (this) {
            case MQ9 -> "mq9reaper";
            case RQ4 -> "rq4globalhawk";
            case MQ4C -> "mq4ctriton";
            case MQ1C -> "mq1cgrayeagle";
            case RQ170 -> "rq170sentinel";
            case X47B -> "x47b";
            case X37B -> "x37b";
        };
    }

    public static BigDroneType fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return MQ9;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .replace('-', '_');
        return switch (key) {
            case "mq9", "mq9reaper", "reaper", "bigdrone" -> MQ9;
            case "rq4", "rq4globalhawk", "globalhawk", "global_hawk" -> RQ4;
            case "mq4c", "mq4ctriton", "triton" -> MQ4C;
            case "mq1c", "mq1cgrayeagle", "grayeagle", "gray_eagle" -> MQ1C;
            case "rq170", "rq170sentinel", "sentinel" -> RQ170;
            case "x47b", "x_47b", "x47" -> X47B;
            case "x37b", "x_37b", "x37" -> X37B;
            default -> MQ9;
        };
    }
}
