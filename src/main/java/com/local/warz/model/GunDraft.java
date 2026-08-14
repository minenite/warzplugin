package com.local.warz.model;

import com.local.warz.util.LaserBeams;
import org.bukkit.Material;
import org.bukkit.Sound;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Mutable gun config used by the in-game editor. */
public final class GunDraft {
    public String fileName = "newgun";
    public String displayName = "&eNew Gun";
    public Material gunMaterial = Material.BONE;
    public Material ammoMaterial = Material.CLAY_BALL;
    public int ammoAmtNeeded = 1;
    public int gunDamage = 5;
    public int armorPenetration = 0;
    public int explosionDamage = -1;
    public int roundsPerBurst = 1;
    public int reloadTime = 40;
    public int maxDistance = 50;
    public int bulletsPerClick = 1;
    public int bulletDelayTime = 10;
    public int maxClipSize = 30;
    public int releaseTime = -1;
    public double bulletSpeed = 3.5;
    /** Downward accel/tick; 0 = none. */
    public double fallSpeed = 0.0;
    public double accuracy = 0.1;
    public double accuracyAimed = 0.05;
    public double accuracyCrouched = 0.08;
    public double explodeRadius = 0;
    public double fireRadius = 0;
    public double flashRadius = 0;
    public double knockback = 0;
    public double recoil = 0;
    public double recoilPitch = 0;
    public double gunVolume = 1.0;
    public boolean canHeadshot = true;
    public boolean resetHitCooldown = true;
    public boolean canAim = true;
    public boolean canClickLeft = false;
    public boolean canClickRight = true;
    public boolean hasClip = true;
    public boolean reloadGunOnDrop = true;
    public boolean localGunSound = true;
    public boolean needsPermission = false;
    public boolean throwable = false;
    public boolean consumable = false;
    public boolean canGoPastMaxDistance = false;
    public boolean destroyBulletWhenHit = true;
    public boolean explodeOnImpact = true;
    public boolean hasSmokeTrail = false;
    public String bulletType = "";
    public String reloadType = "NORMAL";
    public String outOfAmmoMessage = "&7Out of ammo!";
    public String permissionMessage = "";
    public List<String> gunSounds = new ArrayList<>(List.of("ghast fireball"));
    public Material remnantItem = Material.AIR;
    public String remnantName = "";
    public int remnantAmount = 1;
    public int remnantPickupDelay = 0;
    public int remnantLifetime = -1;
    /** Visual aim pointer only — never deals damage. */
    public boolean laserSight = false;
    public boolean laserSightAimOnly = true;
    public String laserSightColor = "#FF2020";
    public double laserSightRange = -1;
    public float laserSightSize = 0.28f;
    public double laserSightOffsetRight = 0.32;
    public double laserSightOffsetUp = -0.28;
    public double laserSightOffsetForward = 0.55;
    public double laserSightDensity = 1.0;
    public boolean laserSightGlow = false;
    public double laserSightGlowStrength = 0.35;
    public boolean laserSightIr = false;
    public String ammoCaliber = "rifle";
    public List<String> allowedRounds = new ArrayList<>();
    public boolean muzzleFlash = true;
    public String muzzleColor = "#FFBE5A";
    public float muzzleScale = 0.85f;

    public static GunDraft from(GunDefinition def, boolean throwableFolder) {
        GunDraft d = new GunDraft();
        d.fileName = def.fileName();
        d.displayName = def.displayName();
        d.gunMaterial = def.gunMaterial();
        d.ammoMaterial = def.ammoMaterial();
        d.ammoAmtNeeded = def.ammoAmtNeeded();
        d.gunDamage = def.gunDamage();
        d.armorPenetration = def.armorPenetration();
        d.explosionDamage = def.explosionDamage();
        d.roundsPerBurst = def.roundsPerBurst();
        d.reloadTime = def.reloadTime();
        d.maxDistance = def.maxDistance();
        d.bulletsPerClick = def.bulletsPerClick();
        d.bulletDelayTime = def.bulletDelayTime();
        d.maxClipSize = def.maxClipSize();
        d.releaseTime = def.releaseTime();
        d.bulletSpeed = def.bulletSpeed();
        d.fallSpeed = def.fallSpeed();
        d.accuracy = def.accuracy();
        d.accuracyAimed = def.accuracyAimed();
        d.accuracyCrouched = def.accuracyCrouched();
        d.explodeRadius = def.explodeRadius();
        d.fireRadius = def.fireRadius();
        d.flashRadius = def.flashRadius();
        d.knockback = def.knockback();
        d.recoil = def.recoil();
        d.recoilPitch = def.recoilPitch();
        d.gunVolume = def.gunVolume();
        d.canHeadshot = def.canHeadshot();
        d.resetHitCooldown = def.resetHitCooldown();
        d.canAim = def.canAimLeft();
        d.canClickLeft = def.canClickLeft();
        d.canClickRight = def.canClickRight();
        d.hasClip = def.hasClip();
        d.reloadGunOnDrop = def.reloadGunOnDrop();
        d.localGunSound = def.localGunSound();
        d.needsPermission = def.needsPermission();
        d.throwable = throwableFolder || def.throwable();
        d.consumable = def.consumable();
        d.canGoPastMaxDistance = def.canGoPastMaxDistance();
        d.destroyBulletWhenHit = def.destroyBulletWhenHit();
        d.explodeOnImpact = def.explodeOnImpact();
        d.hasSmokeTrail = def.hasSmokeTrail();
        d.bulletType = def.bulletType() == null ? "" : def.bulletType();
        d.reloadType = def.reloadType();
        d.outOfAmmoMessage = def.outOfAmmoMessage();
        d.permissionMessage = def.permissionMessage() == null ? "" : def.permissionMessage();
        d.remnantItem = def.remnantItem() == null ? Material.AIR : def.remnantItem();
        d.remnantName = def.remnantName() == null ? "" : def.remnantName();
        d.remnantAmount = def.remnantAmount();
        d.remnantPickupDelay = def.remnantPickupDelay();
        d.remnantLifetime = def.remnantLifetime();
        d.laserSight = def.laserSight();
        d.laserSightAimOnly = def.laserSightAimOnly();
        d.laserSightColor = LaserBeams.colorToConfig(def.laserSightColor());
        d.laserSightRange = def.laserSightRange();
        d.laserSightSize = def.laserSightSize();
        d.laserSightOffsetRight = def.laserSightOffsetRight();
        d.laserSightOffsetUp = def.laserSightOffsetUp();
        d.laserSightOffsetForward = def.laserSightOffsetForward();
        d.laserSightDensity = def.laserSightDensity();
        d.laserSightGlow = def.laserSightGlow();
        d.laserSightGlowStrength = def.laserSightGlowStrength();
        d.laserSightIr = def.laserSightIr();
        d.ammoCaliber = def.ammoCaliber();
        d.allowedRounds = new ArrayList<>(def.allowedRounds());
        d.muzzleFlash = def.muzzleFlash();
        d.muzzleColor = LaserBeams.colorToConfig(def.muzzleColor());
        d.muzzleScale = def.muzzleScale();
        d.gunSounds = def.gunSounds().stream()
                .map(Sound::name)
                .map(s -> s.toLowerCase(Locale.ROOT).replace('_', ' '))
                .collect(Collectors.toCollection(ArrayList::new));
        if (d.gunSounds.isEmpty()) {
            d.gunSounds.add("ghast fireball");
        }
        return d;
    }

    public void sanitizeFileName() {
        String cleaned = fileName == null ? "newgun" : fileName.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\-]", "_");
        if (cleaned.isBlank()) {
            cleaned = "newgun";
        }
        fileName = cleaned;
    }
}
