package com.local.warz.model;

import com.local.warz.util.LaserBeams;
import org.bukkit.Color;
import org.bukkit.Material;

import java.util.Locale;

/** Mutable round config for the in-game round library editor. */
public final class RoundDraft {
    public String fileName = "new_round";
    public String displayName = "&eNew Round";
    public Material material = Material.FLINT;
    public int customModelData = 2000;
    public String caliber = "rifle";
    public double damageMult = 1.0;
    public int armorPenAdd = 0;
    public double accuracyMult = 1.0;
    public double speedMult = 1.0;
    public double knockbackMult = 1.0;
    public double rangeMult = 1.0;
    public boolean tracer = false;
    public String tracerColor = "#FFC850";
    public float tracerWidth = 0.035f;
    public boolean muzzleFlash = true;
    public String muzzleColor = "#FFBE5A";
    public float muzzleScale = 0.85f;
    public double explodeRadiusAdd = 0;
    public double fireRadiusAdd = 0;
    public int setFireTicks = 0;
    public boolean subsonic = false;

    public static RoundDraft from(RoundDefinition def) {
        RoundDraft d = new RoundDraft();
        d.fileName = def.fileName();
        d.displayName = def.displayName();
        d.material = def.material();
        d.customModelData = def.customModelData();
        d.caliber = def.caliber();
        d.damageMult = def.damageMult();
        d.armorPenAdd = def.armorPenAdd();
        d.accuracyMult = def.accuracyMult();
        d.speedMult = def.speedMult();
        d.knockbackMult = def.knockbackMult();
        d.rangeMult = def.rangeMult();
        d.tracer = def.tracer();
        d.tracerColor = LaserBeams.colorToConfig(def.tracerColor());
        d.tracerWidth = def.tracerWidth();
        d.muzzleFlash = def.muzzleFlash();
        d.muzzleColor = LaserBeams.colorToConfig(def.muzzleColor());
        d.muzzleScale = def.muzzleScale();
        d.explodeRadiusAdd = def.explodeRadiusAdd();
        d.fireRadiusAdd = def.fireRadiusAdd();
        d.setFireTicks = def.setFireTicks();
        d.subsonic = def.subsonic();
        return d;
    }

    public RoundDefinition toDefinition() {
        return new RoundDefinition.Builder()
                .fileName(sanitizeFileName(fileName))
                .displayName(displayName)
                .material(material)
                .customModelData(customModelData)
                .caliber(caliber)
                .damageMult(damageMult)
                .armorPenAdd(armorPenAdd)
                .accuracyMult(accuracyMult)
                .speedMult(speedMult)
                .knockbackMult(knockbackMult)
                .rangeMult(rangeMult)
                .tracer(tracer)
                .tracerColor(LaserBeams.parseColor(tracerColor, Color.fromRGB(255, 200, 80)))
                .tracerWidth(tracerWidth)
                .muzzleFlash(muzzleFlash)
                .muzzleColor(LaserBeams.parseColor(muzzleColor, Color.fromRGB(255, 190, 90)))
                .muzzleScale(muzzleScale)
                .explodeRadiusAdd(explodeRadiusAdd)
                .fireRadiusAdd(fireRadiusAdd)
                .setFireTicks(setFireTicks)
                .subsonic(subsonic)
                .build();
    }

    public void sanitizeFileName() {
        fileName = sanitizeFileName(fileName);
    }

    public static String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "round";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
    }
}
