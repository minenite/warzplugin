package com.local.warz.model;

import org.bukkit.Color;
import org.bukkit.Material;

import java.util.Locale;

/** One ammunition subtype (FMJ, tracer, HP, …) identified by file name. */
public final class RoundDefinition {
    private final String fileName;
    private final String displayName;
    /** Optional one-line Shift tooltip blurb; empty → generated. */
    private final String description;
    private final Material material;
    private final int customModelData;
    private final String caliber;
    private final double damageMult;
    private final int armorPenAdd;
    private final double accuracyMult;
    private final double speedMult;
    private final double knockbackMult;
    private final double rangeMult;
    private final boolean tracer;
    private final Color tracerColor;
    private final float tracerWidth;
    private final boolean muzzleFlash;
    private final Color muzzleColor;
    private final float muzzleScale;
    private final double explodeRadiusAdd;
    private final double fireRadiusAdd;
    private final int setFireTicks;
    private final boolean subsonic;

    private RoundDefinition(Builder b) {
        this.fileName = b.fileName;
        this.displayName = b.displayName == null || b.displayName.isBlank() ? b.fileName : b.displayName;
        this.description = b.description == null ? "" : b.description.trim();
        this.material = b.material == null ? Material.FLINT : b.material;
        this.customModelData = b.customModelData;
        this.caliber = b.caliber == null || b.caliber.isBlank() ? "rifle" : b.caliber.toLowerCase(Locale.ROOT);
        this.damageMult = b.damageMult <= 0 ? 1.0 : b.damageMult;
        this.armorPenAdd = b.armorPenAdd;
        this.accuracyMult = b.accuracyMult <= 0 ? 1.0 : b.accuracyMult;
        this.speedMult = b.speedMult <= 0 ? 1.0 : b.speedMult;
        this.knockbackMult = b.knockbackMult < 0 ? 1.0 : b.knockbackMult;
        this.rangeMult = b.rangeMult <= 0 ? 1.0 : b.rangeMult;
        this.tracer = b.tracer;
        this.tracerColor = b.tracerColor == null ? Color.fromRGB(255, 200, 80) : b.tracerColor;
        this.tracerWidth = b.tracerWidth <= 0 ? 0.035f : b.tracerWidth;
        this.muzzleFlash = b.muzzleFlash;
        this.muzzleColor = b.muzzleColor == null ? Color.fromRGB(255, 190, 90) : b.muzzleColor;
        this.muzzleScale = b.muzzleScale <= 0 ? 0.85f : b.muzzleScale;
        this.explodeRadiusAdd = Math.max(0, b.explodeRadiusAdd);
        this.fireRadiusAdd = Math.max(0, b.fireRadiusAdd);
        this.setFireTicks = Math.max(0, b.setFireTicks);
        this.subsonic = b.subsonic;
    }

    public String fileName() { return fileName; }
    public String displayName() { return displayName; }
    public String description() { return description; }
    public Material material() { return material; }
    public int customModelData() { return customModelData; }
    public String caliber() { return caliber; }
    public double damageMult() { return damageMult; }
    public int armorPenAdd() { return armorPenAdd; }
    public double accuracyMult() { return accuracyMult; }
    public double speedMult() { return speedMult; }
    public double knockbackMult() { return knockbackMult; }
    public double rangeMult() { return rangeMult; }
    public boolean tracer() { return tracer; }
    public Color tracerColor() { return tracerColor; }
    public float tracerWidth() { return tracerWidth; }
    public boolean muzzleFlash() { return muzzleFlash; }
    public Color muzzleColor() { return muzzleColor; }
    public float muzzleScale() { return muzzleScale; }
    public double explodeRadiusAdd() { return explodeRadiusAdd; }
    public double fireRadiusAdd() { return fireRadiusAdd; }
    public int setFireTicks() { return setFireTicks; }
    /** Below ~Mach 1 — no sonic crack; suppressor becomes a true whisper. */
    public boolean subsonic() { return subsonic; }

    public int damageFor(GunDefinition gun) {
        return Math.max(0, (int) Math.round(gun.gunDamage() * damageMult));
    }

    public double accuracyFor(GunDefinition gun, double baseAccuracy) {
        return Math.max(0.0001, baseAccuracy * accuracyMult);
    }

    public double speedFor(GunDefinition gun) {
        return Math.max(0.05, gun.bulletSpeed() * speedMult);
    }

    public double knockbackFor(GunDefinition gun) {
        return gun.knockback() * knockbackMult;
    }

    public int rangeFor(GunDefinition gun) {
        return Math.max(1, (int) Math.round(gun.maxDistance() * rangeMult));
    }

    public static final class Builder {
        private String fileName = "round";
        private String displayName = "Round";
        private String description = "";
        private Material material = Material.FLINT;
        private int customModelData = 2000;
        private String caliber = "rifle";
        private double damageMult = 1.0;
        private int armorPenAdd = 0;
        private double accuracyMult = 1.0;
        private double speedMult = 1.0;
        private double knockbackMult = 1.0;
        private double rangeMult = 1.0;
        private boolean tracer = false;
        private Color tracerColor = Color.fromRGB(255, 200, 80);
        private float tracerWidth = 0.035f;
        private boolean muzzleFlash = true;
        private Color muzzleColor = Color.fromRGB(255, 190, 90);
        private float muzzleScale = 0.85f;
        private double explodeRadiusAdd = 0;
        private double fireRadiusAdd = 0;
        private int setFireTicks = 0;
        private boolean subsonic = false;

        public Builder fileName(String fileName) { this.fileName = fileName; return this; }
        public Builder displayName(String displayName) { this.displayName = displayName; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder material(Material material) { this.material = material; return this; }
        public Builder customModelData(int customModelData) { this.customModelData = customModelData; return this; }
        public Builder caliber(String caliber) { this.caliber = caliber; return this; }
        public Builder damageMult(double damageMult) { this.damageMult = damageMult; return this; }
        public Builder armorPenAdd(int armorPenAdd) { this.armorPenAdd = armorPenAdd; return this; }
        public Builder accuracyMult(double accuracyMult) { this.accuracyMult = accuracyMult; return this; }
        public Builder speedMult(double speedMult) { this.speedMult = speedMult; return this; }
        public Builder knockbackMult(double knockbackMult) { this.knockbackMult = knockbackMult; return this; }
        public Builder rangeMult(double rangeMult) { this.rangeMult = rangeMult; return this; }
        public Builder tracer(boolean tracer) { this.tracer = tracer; return this; }
        public Builder tracerColor(Color tracerColor) { this.tracerColor = tracerColor; return this; }
        public Builder tracerWidth(float tracerWidth) { this.tracerWidth = tracerWidth; return this; }
        public Builder muzzleFlash(boolean muzzleFlash) { this.muzzleFlash = muzzleFlash; return this; }
        public Builder muzzleColor(Color muzzleColor) { this.muzzleColor = muzzleColor; return this; }
        public Builder muzzleScale(float muzzleScale) { this.muzzleScale = muzzleScale; return this; }
        public Builder explodeRadiusAdd(double explodeRadiusAdd) { this.explodeRadiusAdd = explodeRadiusAdd; return this; }
        public Builder fireRadiusAdd(double fireRadiusAdd) { this.fireRadiusAdd = fireRadiusAdd; return this; }
        public Builder setFireTicks(int setFireTicks) { this.setFireTicks = setFireTicks; return this; }
        public Builder subsonic(boolean subsonic) { this.subsonic = subsonic; return this; }

        public RoundDefinition build() {
            return new RoundDefinition(this);
        }
    }
}
