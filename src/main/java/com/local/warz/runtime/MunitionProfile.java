package com.local.warz.runtime;

import java.util.Locale;
import java.util.Optional;

/**
 * UAV munition identity — guidance + warhead keyed by round filename.
 */
public enum MunitionProfile {
    ROCKET_HE("rocket_he", Guidance.DUMB, Warhead.BLAST, HudMode.NONE, 0, 0, 7.5, 1.0, false),
    ROCKET_AP("rocket_ap", Guidance.DUMB, Warhead.BLAST, HudMode.NONE, 0, 0, 6.5, 1.0, false),
    ROCKET_HP("rocket_hp", Guidance.DUMB, Warhead.BLAST, HudMode.NONE, 0, 0, 8.0, 1.0, false),
    ROCKET_AA("rocket_aa", Guidance.IR_AA, Warhead.AIRBURST, HudMode.AA_LOCK, 1.35, 180, 5.5, 1.2, true),
    ROCKET_R9X("rocket_r9x", Guidance.LASER, Warhead.KINETIC, HudMode.LASER, 1.45, 420, 2.75, 0, false),
    ROCKET_MAC("rocket_mac", Guidance.LASER, Warhead.CONCUSSION, HudMode.LASER, 1.4, 400, 14.0, 1.55, false),
    ROCKET_ROMEO("rocket_romeo", Guidance.LASER, Warhead.BLAST, HudMode.LASER, 1.5, 400, 6.5, 1.15, false),
    ROCKET_JAGM("rocket_jagm", Guidance.DUAL, Warhead.BLAST, HudMode.JAGM, 1.45, 420, 7.0, 1.2, false),
    GBU_VIPER("gbu_viper", Guidance.GLIDE, Warhead.BLAST, HudMode.LASER, 0.55, 380, 4.5, 0.85, false),
    GBU_SGM("gbu_sgm", Guidance.GLIDE, Warhead.BLAST, HudMode.LASER, 0.62, 420, 8.5, 1.25, false),
    GBU_SDB("gbu_sdb", Guidance.GLIDE_PEN, Warhead.PENETRATOR, HudMode.LASER, 0.7, 450, 10.0, 1.45, false),
    GBU_STORM("gbu_storm", Guidance.MULTI, Warhead.BLAST, HudMode.STORM, 0.68, 400, 7.5, 1.2, false),
    GBU_PAVEWAY("gbu_paveway", Guidance.LASER_BOMB, Warhead.HEAVY, HudMode.LASER, 0.85, 480, 16.0, 1.85, false),
    GBU_SONAR("gbu_sonar", Guidance.GLIDE, Warhead.SONAR, HudMode.LASER, 0.6, 400, 120.0, 0, false),
    AIM9X("aim9x", Guidance.IR_AA, Warhead.AIRBURST, HudMode.AA_LOCK, 1.55, 220, 4.0, 0, true);

    public enum Guidance {
        DUMB, LASER, IR_AA, DUAL, GLIDE, GLIDE_PEN, MULTI, LASER_BOMB
    }

    public enum Warhead {
        BLAST, KINETIC, CONCUSSION, AIRBURST, PENETRATOR, HEAVY, SONAR
    }

    public enum HudMode {
        NONE, LASER, AA_LOCK, JAGM, STORM
    }

    private final String id;
    private final Guidance guidance;
    private final Warhead warhead;
    private final HudMode hudMode;
    private final double speed;
    private final double range;
    private final double effectRadius;
    private final double shockStrength;
    private final boolean flareDecoy;

    MunitionProfile(String id, Guidance guidance, Warhead warhead, HudMode hudMode,
                    double speed, double range, double effectRadius, double shockStrength,
                    boolean flareDecoy) {
        this.id = id;
        this.guidance = guidance;
        this.warhead = warhead;
        this.hudMode = hudMode;
        this.speed = speed;
        this.range = range;
        this.effectRadius = effectRadius;
        this.shockStrength = shockStrength;
        this.flareDecoy = flareDecoy;
    }

    public String id() {
        return id;
    }

    public Guidance guidance() {
        return guidance;
    }

    public Warhead warhead() {
        return warhead;
    }

    public HudMode hudMode() {
        return hudMode;
    }

    public double speed() {
        return speed;
    }

    public double range() {
        return range;
    }

    public double effectRadius() {
        return effectRadius;
    }

    public double shockStrength() {
        return shockStrength;
    }

    public boolean flareDecoy() {
        return flareDecoy;
    }

    public boolean guided() {
        return guidance != Guidance.DUMB;
    }

    public boolean glide() {
        return guidance == Guidance.GLIDE || guidance == Guidance.GLIDE_PEN
                || guidance == Guidance.LASER_BOMB;
    }

    public static Optional<MunitionProfile> ofRound(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (MunitionProfile p : values()) {
            if (p.id.equals(key)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    public static boolean isBayRocket(String raw) {
        return ofRound(raw).isPresent();
    }
}
