package com.local.warz.model;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Sound;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class GunDefinition {
    private final String fileName;
    private final String displayName;
    private final Material gunMaterial;
    private final Material ammoMaterial;
    private final int ammoAmtNeeded;
    private final int gunDamage;
    private final int armorPenetration;
    private final int explosionDamage;
    private final int roundsPerBurst;
    private final int reloadTime;
    private final int maxDistance;
    private final int bulletsPerClick;
    private final int bulletDelayTime;
    private final int maxClipSize;
    private final int customModelData;
    private final int releaseTime;
    private final double bulletSpeed;
    /**
     * Downward acceleration per tick while the projectile is flying.
     * {@code 0} = no gravity (normal bullets). Small values (e.g. {@code 0.01}) = slow parachute fall.
     */
    private final double fallSpeed;
    private final double accuracy;
    private final double accuracyAimed;
    private final double accuracyCrouched;
    private final double explodeRadius;
    /** Outer acoustic / shock envelope (blocks). {@code <= 0} = default 20. */
    private final double blastShockRadius;
    /** Overall shock multiplier vs a frag. {@code <= 0} = 1.0. */
    private final double blastShockStrength;
    private final double fireRadius;
    private final double flashRadius;
    private final double knockback;
    private final double recoil;
    private final double recoilPitch;
    private final double gunVolume;
    private final boolean canHeadshot;
    private final boolean resetHitCooldown;
    private final boolean canAimLeft;
    private final boolean canAimRight;
    private final boolean canClickLeft;
    private final boolean canClickRight;
    private final boolean hasClip;
    private final boolean reloadGunOnDrop;
    private final boolean localGunSound;
    private final boolean needsPermission;
    private final boolean throwable;
    private final boolean consumable;
    private final boolean canGoPastMaxDistance;
    private final boolean destroyBulletWhenHit;
    /** When false, splash waits for the fuse and the round can bounce/roll on contact. */
    private final boolean explodeOnImpact;
    private final boolean hasSmokeTrail;
    private final String bulletType;
    private final String reloadType;
    private final String outOfAmmoMessage;
    private final String permissionMessage;
    private final String permissionNode;
    private final List<Sound> gunSounds;
    /** Item left on the ground after a throwable detonates; AIR = nothing. */
    private final Material remnantItem;
    private final String remnantName;
    private final int remnantAmount;
    private final int remnantPickupDelay;
    /** Ticks until remnant despawns; -1 = vanilla item lifetime. */
    private final int remnantLifetime;
    private final boolean laserSight;
    private final boolean laserSightAimOnly;
    private final Color laserSightColor;
    private final double laserSightRange;
    private final float laserSightSize;
    private final double laserSightOffsetRight;
    private final double laserSightOffsetUp;
    private final double laserSightOffsetForward;
    private final double laserSightDensity;
    private final boolean laserSightGlow;
    private final double laserSightGlowStrength;
    /** When true, beam is infrared — only players wearing NVG can see it. */
    private final boolean laserSightIr;
    private final String ammoCaliber;
    private final List<String> allowedRounds;
    private final boolean muzzleFlash;
    private final Color muzzleColor;
    private final float muzzleScale;

    private GunDefinition(Builder builder) {
        this.fileName = builder.fileName;
        this.displayName = builder.displayName;
        this.gunMaterial = builder.gunMaterial;
        Material resolvedAmmo = builder.ammoMaterial == null ? Material.FLINT : builder.ammoMaterial;
        String caliber = builder.ammoCaliber;
        if (caliber == null || caliber.isBlank()) {
            if ("laser".equalsIgnoreCase(builder.bulletType)) {
                caliber = "energy";
                resolvedAmmo = com.local.warz.config.AmmoCaliber.defaultMaterial("energy");
            } else {
                caliber = com.local.warz.config.AmmoCaliber.fromMaterial(resolvedAmmo);
            }
        }
        this.ammoMaterial = resolvedAmmo;
        this.ammoAmtNeeded = builder.ammoAmtNeeded;
        this.gunDamage = builder.gunDamage;
        this.armorPenetration = builder.armorPenetration;
        this.explosionDamage = builder.explosionDamage;
        this.roundsPerBurst = builder.roundsPerBurst;
        this.reloadTime = builder.reloadTime;
        this.maxDistance = builder.maxDistance;
        this.bulletsPerClick = builder.bulletsPerClick;
        this.bulletDelayTime = builder.bulletDelayTime;
        this.maxClipSize = builder.maxClipSize;
        this.customModelData = builder.customModelData;
        this.releaseTime = builder.releaseTime;
        this.bulletSpeed = builder.bulletSpeed;
        this.fallSpeed = Math.max(0.0, builder.fallSpeed);
        this.accuracy = builder.accuracy;
        this.accuracyAimed = builder.accuracyAimed;
        this.accuracyCrouched = builder.accuracyCrouched;
        this.explodeRadius = builder.explodeRadius;
        this.blastShockRadius = builder.blastShockRadius;
        this.blastShockStrength = builder.blastShockStrength;
        this.fireRadius = builder.fireRadius;
        this.flashRadius = builder.flashRadius;
        this.knockback = builder.knockback;
        this.recoil = builder.recoil;
        this.recoilPitch = builder.recoilPitch;
        this.gunVolume = builder.gunVolume;
        this.canHeadshot = builder.canHeadshot;
        this.resetHitCooldown = builder.resetHitCooldown;
        this.canAimLeft = builder.canAimLeft;
        this.canAimRight = builder.canAimRight;
        this.canClickLeft = builder.canClickLeft;
        this.canClickRight = builder.canClickRight;
        this.hasClip = builder.hasClip;
        this.reloadGunOnDrop = builder.reloadGunOnDrop;
        this.localGunSound = builder.localGunSound;
        this.needsPermission = builder.needsPermission;
        this.throwable = builder.throwable;
        this.consumable = builder.consumable;
        this.canGoPastMaxDistance = builder.canGoPastMaxDistance;
        this.destroyBulletWhenHit = builder.destroyBulletWhenHit;
        this.explodeOnImpact = builder.explodeOnImpact;
        this.hasSmokeTrail = builder.hasSmokeTrail;
        this.bulletType = builder.bulletType;
        this.reloadType = builder.reloadType;
        this.outOfAmmoMessage = builder.outOfAmmoMessage;
        this.permissionMessage = builder.permissionMessage;
        this.permissionNode = builder.permissionNode;
        this.gunSounds = Collections.unmodifiableList(new ArrayList<>(builder.gunSounds));
        this.remnantItem = builder.remnantItem == null ? Material.AIR : builder.remnantItem;
        this.remnantName = builder.remnantName == null ? "" : builder.remnantName;
        this.remnantAmount = Math.max(1, builder.remnantAmount);
        this.remnantPickupDelay = Math.max(0, builder.remnantPickupDelay);
        this.remnantLifetime = builder.remnantLifetime;
        this.laserSight = builder.laserSight;
        this.laserSightAimOnly = builder.laserSightAimOnly;
        this.laserSightColor = builder.laserSightColor == null ? Color.RED : builder.laserSightColor;
        this.laserSightRange = builder.laserSightRange;
        this.laserSightSize = builder.laserSightSize <= 0 ? 0.28f : builder.laserSightSize;
        this.laserSightOffsetRight = builder.laserSightOffsetRight;
        this.laserSightOffsetUp = builder.laserSightOffsetUp;
        this.laserSightOffsetForward = builder.laserSightOffsetForward;
        this.laserSightDensity = builder.laserSightDensity <= 0 ? 1.0 : builder.laserSightDensity;
        this.laserSightGlow = builder.laserSightGlow;
        this.laserSightGlowStrength = builder.laserSightGlowStrength < 0 ? 0.35 : builder.laserSightGlowStrength;
        this.laserSightIr = builder.laserSightIr;
        this.ammoCaliber = com.local.warz.config.AmmoCaliber.normalize(caliber);
        List<String> rounds = new ArrayList<>();
        for (String id : builder.allowedRounds) {
            if (id != null && !id.isBlank()) {
                rounds.add(id.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (rounds.isEmpty() && !builder.consumable && !builder.throwable) {
            for (String id : com.local.warz.config.AmmoCaliber.defaultAllowed(this.ammoCaliber)) {
                rounds.add(id);
            }
        }
        this.allowedRounds = Collections.unmodifiableList(rounds);
        this.muzzleFlash = builder.muzzleFlash;
        this.muzzleColor = builder.muzzleColor == null ? Color.fromRGB(255, 190, 90) : builder.muzzleColor;
        this.muzzleScale = builder.muzzleScale <= 0 ? 0.85f : builder.muzzleScale;
    }

    public String fileName() {
        return fileName;
    }

    public String displayName() {
        return displayName;
    }

    public Material gunMaterial() {
        return gunMaterial;
    }

    public Material ammoMaterial() {
        return ammoMaterial;
    }

    public int ammoAmtNeeded() {
        return ammoAmtNeeded;
    }

    public int gunDamage() {
        return gunDamage;
    }

    public int armorPenetration() {
        return armorPenetration;
    }

    public int explosionDamage() {
        return explosionDamage;
    }

    public int roundsPerBurst() {
        return roundsPerBurst;
    }

    public int reloadTime() {
        return reloadTime;
    }

    public int maxDistance() {
        return maxDistance;
    }

    public int bulletsPerClick() {
        return bulletsPerClick;
    }

    public int bulletDelayTime() {
        return bulletDelayTime;
    }

    public int maxClipSize() {
        return maxClipSize;
    }

    public int customModelData() {
        return customModelData;
    }

    public int releaseTime() {
        return releaseTime;
    }

    public double bulletSpeed() {
        return bulletSpeed;
    }

    /** Gravity pull per tick; 0 disables fall (default). */
    public double fallSpeed() {
        return fallSpeed;
    }

    public double accuracy() {
        return accuracy;
    }

    public double accuracyAimed() {
        return accuracyAimed;
    }

    public double accuracyCrouched() {
        return accuracyCrouched;
    }

    public double explodeRadius() {
        return explodeRadius;
    }

    /** Player shock / tinnitus envelope; independent of crater {@link #explodeRadius()}. */
    public double blastShockRadius() {
        return blastShockRadius;
    }

    public double blastShockStrength() {
        return blastShockStrength;
    }

    public double fireRadius() {
        return fireRadius;
    }

    public double flashRadius() {
        return flashRadius;
    }

    public double knockback() {
        return knockback;
    }

    public double recoil() {
        return recoil;
    }

    /** Degrees the camera kicks upward per shot (0 = no camera recoil). */
    public double recoilPitch() {
        return recoilPitch;
    }

    public double gunVolume() {
        return gunVolume;
    }

    public boolean canHeadshot() {
        return canHeadshot;
    }

    public boolean resetHitCooldown() {
        return resetHitCooldown;
    }

    public boolean canAimLeft() {
        return canAimLeft;
    }

    public boolean canAimRight() {
        return canAimRight;
    }

    public boolean canClickLeft() {
        return canClickLeft;
    }

    public boolean canClickRight() {
        return canClickRight;
    }

    public boolean hasClip() {
        return hasClip;
    }

    public boolean reloadGunOnDrop() {
        return reloadGunOnDrop;
    }

    public boolean localGunSound() {
        return localGunSound;
    }

    public boolean needsPermission() {
        return needsPermission;
    }

    public boolean throwable() {
        return throwable;
    }

    /** When true, firing consumes this gun item itself instead of a separate ammo material. */
    public boolean consumable() {
        return consumable;
    }

    public boolean canGoPastMaxDistance() {
        return canGoPastMaxDistance;
    }

    public boolean destroyBulletWhenHit() {
        return destroyBulletWhenHit;
    }

    public boolean explodeOnImpact() {
        return explodeOnImpact;
    }

    public boolean hasSmokeTrail() {
        return hasSmokeTrail;
    }

    public String bulletType() {
        return bulletType;
    }

    public String reloadType() {
        return reloadType;
    }

    public String outOfAmmoMessage() {
        return outOfAmmoMessage;
    }

    public String permissionMessage() {
        return permissionMessage;
    }

    public String permissionNode() {
        return permissionNode;
    }

    public List<Sound> gunSounds() {
        return gunSounds;
    }

    public Material remnantItem() {
        return remnantItem;
    }

    public String remnantName() {
        return remnantName;
    }

    public int remnantAmount() {
        return remnantAmount;
    }

    public int remnantPickupDelay() {
        return remnantPickupDelay;
    }

    public int remnantLifetime() {
        return remnantLifetime;
    }

    public boolean hasRemnant() {
        return remnantItem != null && !remnantItem.isAir() && remnantItem.isItem();
    }

    public boolean laserSight() {
        return laserSight;
    }

    public boolean laserSightAimOnly() {
        return laserSightAimOnly;
    }

    public Color laserSightColor() {
        return laserSightColor;
    }

    public double laserSightRange() {
        return laserSightRange;
    }

    public float laserSightSize() {
        return laserSightSize;
    }

    public double laserSightOffsetRight() {
        return laserSightOffsetRight;
    }

    public double laserSightOffsetUp() {
        return laserSightOffsetUp;
    }

    public double laserSightOffsetForward() {
        return laserSightOffsetForward;
    }

    public double laserSightDensity() {
        return laserSightDensity;
    }

    public boolean laserSightGlow() {
        return laserSightGlow;
    }

    public double laserSightGlowStrength() {
        return laserSightGlowStrength;
    }

    public boolean laserSightIr() {
        return laserSightIr;
    }

    public String ammoCaliber() {
        return ammoCaliber;
    }

    public List<String> allowedRounds() {
        return allowedRounds;
    }

    public boolean allowsRound(String roundId) {
        if (consumable || throwable) {
            return false;
        }
        if (roundId == null || roundId.isBlank()) {
            return false;
        }
        String id = roundId.toLowerCase(Locale.ROOT);
        if (allowedRounds.isEmpty()) {
            return true;
        }
        return allowedRounds.contains(id);
    }

    public boolean muzzleFlash() {
        return muzzleFlash;
    }

    public Color muzzleColor() {
        return muzzleColor;
    }

    public float muzzleScale() {
        return muzzleScale;
    }

    public boolean isLaser() {
        return "laser".equalsIgnoreCase(bulletType);
    }

    public boolean isCrossbow() {
        return "crossbow".equalsIgnoreCase(bulletType);
    }

    public static final class Builder {
        private String fileName = "unknown";
        private String displayName = "Gun";
        private Material gunMaterial = Material.STICK;
        private Material ammoMaterial = Material.CLAY_BALL;
        private int ammoAmtNeeded = 1;
        private int gunDamage = 1;
        private int armorPenetration = 0;
        private int explosionDamage = -1;
        private int roundsPerBurst = 1;
        private int reloadTime = 20;
        private int maxDistance = 50;
        private int bulletsPerClick = 1;
        private int bulletDelayTime = 10;
        private int maxClipSize = 30;
        private int customModelData = 1;
        private int releaseTime = -1;
        private double bulletSpeed = 3.5;
        private double fallSpeed = 0.0;
        private double accuracy = 0.1;
        private double accuracyAimed = -1;
        private double accuracyCrouched = -1;
        private double explodeRadius = 0;
        private double blastShockRadius = -1;
        private double blastShockStrength = -1;
        private double fireRadius = 0;
        private double flashRadius = 0;
        private double knockback = 0;
        private double recoil = 0;
        private double recoilPitch = 0;
        private double gunVolume = 1.0;
        private boolean canHeadshot = true;
        private boolean resetHitCooldown = true;
        private boolean canAimLeft = true;
        private boolean canAimRight = false;
        private boolean canClickLeft = false;
        private boolean canClickRight = true;
        private boolean hasClip = true;
        private boolean reloadGunOnDrop = true;
        private boolean localGunSound = true;
        private boolean needsPermission = false;
        private boolean throwable = false;
        private boolean consumable = false;
        private boolean canGoPastMaxDistance = false;
        private boolean destroyBulletWhenHit = true;
        private boolean explodeOnImpact = true;
        private boolean hasSmokeTrail = false;
        private String bulletType = "";
        private String reloadType = "NORMAL";
        private String outOfAmmoMessage = "&7Out of ammo!";
        private String permissionMessage = "";
        private String permissionNode = "pvpgunplus.unknown";
        private final List<Sound> gunSounds = new ArrayList<>();
        private Material remnantItem = Material.AIR;
        private String remnantName = "";
        private int remnantAmount = 1;
        private int remnantPickupDelay = 0;
        private int remnantLifetime = -1;
        private boolean laserSight = false;
        private boolean laserSightAimOnly = true;
        private Color laserSightColor = Color.RED;
        private double laserSightRange = -1;
        private float laserSightSize = 0.28f;
        private double laserSightOffsetRight = 0.32;
        private double laserSightOffsetUp = -0.28;
        private double laserSightOffsetForward = 0.55;
        private double laserSightDensity = 1.0;
        private boolean laserSightGlow = false;
        private double laserSightGlowStrength = 0.35;
        private boolean laserSightIr = false;
        private String ammoCaliber = "";
        private final List<String> allowedRounds = new ArrayList<>();
        private boolean muzzleFlash = true;
        private Color muzzleColor = Color.fromRGB(255, 190, 90);
        private float muzzleScale = 0.85f;
        public Builder fileName(String fileName) {
            this.fileName = fileName;
            this.permissionNode = "pvpgunplus." + fileName.toLowerCase(Locale.ROOT);
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder gunMaterial(Material gunMaterial) {
            this.gunMaterial = gunMaterial;
            return this;
        }

        public Builder ammoMaterial(Material ammoMaterial) {
            this.ammoMaterial = ammoMaterial;
            return this;
        }

        public Builder ammoAmtNeeded(int ammoAmtNeeded) {
            this.ammoAmtNeeded = ammoAmtNeeded;
            return this;
        }

        public Builder gunDamage(int gunDamage) {
            this.gunDamage = gunDamage;
            return this;
        }

        public Builder armorPenetration(int armorPenetration) {
            this.armorPenetration = armorPenetration;
            return this;
        }

        public Builder explosionDamage(int explosionDamage) {
            this.explosionDamage = explosionDamage;
            return this;
        }

        public Builder roundsPerBurst(int roundsPerBurst) {
            this.roundsPerBurst = roundsPerBurst;
            return this;
        }

        public Builder reloadTime(int reloadTime) {
            this.reloadTime = reloadTime;
            return this;
        }

        public Builder maxDistance(int maxDistance) {
            this.maxDistance = maxDistance;
            return this;
        }

        public Builder bulletsPerClick(int bulletsPerClick) {
            this.bulletsPerClick = bulletsPerClick;
            return this;
        }

        public Builder bulletDelayTime(int bulletDelayTime) {
            this.bulletDelayTime = bulletDelayTime;
            return this;
        }

        public Builder maxClipSize(int maxClipSize) {
            this.maxClipSize = maxClipSize;
            return this;
        }

        public Builder customModelData(int customModelData) {
            this.customModelData = customModelData;
            return this;
        }

        public Builder releaseTime(int releaseTime) {
            this.releaseTime = releaseTime;
            return this;
        }

        public Builder bulletSpeed(double bulletSpeed) {
            this.bulletSpeed = bulletSpeed;
            return this;
        }

        public Builder fallSpeed(double fallSpeed) {
            this.fallSpeed = fallSpeed;
            return this;
        }

        public Builder accuracy(double accuracy) {
            this.accuracy = accuracy;
            return this;
        }

        public Builder accuracyAimed(double accuracyAimed) {
            this.accuracyAimed = accuracyAimed;
            return this;
        }

        public Builder accuracyCrouched(double accuracyCrouched) {
            this.accuracyCrouched = accuracyCrouched;
            return this;
        }

        public Builder explodeRadius(double explodeRadius) {
            this.explodeRadius = explodeRadius;
            return this;
        }

        public Builder blastShockRadius(double blastShockRadius) {
            this.blastShockRadius = blastShockRadius;
            return this;
        }

        public Builder blastShockStrength(double blastShockStrength) {
            this.blastShockStrength = blastShockStrength;
            return this;
        }

        public Builder fireRadius(double fireRadius) {
            this.fireRadius = fireRadius;
            return this;
        }

        public Builder flashRadius(double flashRadius) {
            this.flashRadius = flashRadius;
            return this;
        }

        public Builder knockback(double knockback) {
            this.knockback = knockback;
            return this;
        }

        public Builder recoil(double recoil) {
            this.recoil = recoil;
            return this;
        }

        public Builder recoilPitch(double recoilPitch) {
            this.recoilPitch = recoilPitch;
            return this;
        }

        public Builder gunVolume(double gunVolume) {
            this.gunVolume = gunVolume;
            return this;
        }

        public Builder canHeadshot(boolean canHeadshot) {
            this.canHeadshot = canHeadshot;
            return this;
        }

        public Builder resetHitCooldown(boolean resetHitCooldown) {
            this.resetHitCooldown = resetHitCooldown;
            return this;
        }

        public Builder canAimLeft(boolean canAimLeft) {
            this.canAimLeft = canAimLeft;
            return this;
        }

        public Builder canAimRight(boolean canAimRight) {
            this.canAimRight = canAimRight;
            return this;
        }

        public Builder canClickLeft(boolean canClickLeft) {
            this.canClickLeft = canClickLeft;
            return this;
        }

        public Builder canClickRight(boolean canClickRight) {
            this.canClickRight = canClickRight;
            return this;
        }

        public Builder hasClip(boolean hasClip) {
            this.hasClip = hasClip;
            return this;
        }

        public Builder reloadGunOnDrop(boolean reloadGunOnDrop) {
            this.reloadGunOnDrop = reloadGunOnDrop;
            return this;
        }

        public Builder localGunSound(boolean localGunSound) {
            this.localGunSound = localGunSound;
            return this;
        }

        public Builder needsPermission(boolean needsPermission) {
            this.needsPermission = needsPermission;
            return this;
        }

        public Builder throwable(boolean throwable) {
            this.throwable = throwable;
            return this;
        }

        public Builder consumable(boolean consumable) {
            this.consumable = consumable;
            return this;
        }

        public Builder canGoPastMaxDistance(boolean canGoPastMaxDistance) {
            this.canGoPastMaxDistance = canGoPastMaxDistance;
            return this;
        }

        public Builder destroyBulletWhenHit(boolean destroyBulletWhenHit) {
            this.destroyBulletWhenHit = destroyBulletWhenHit;
            return this;
        }

        public Builder explodeOnImpact(boolean explodeOnImpact) {
            this.explodeOnImpact = explodeOnImpact;
            return this;
        }

        public Builder hasSmokeTrail(boolean hasSmokeTrail) {
            this.hasSmokeTrail = hasSmokeTrail;
            return this;
        }

        public Builder bulletType(String bulletType) {
            this.bulletType = bulletType == null ? "" : bulletType.trim();
            return this;
        }

        public Builder reloadType(String reloadType) {
            this.reloadType = reloadType == null ? "NORMAL" : reloadType.trim().toUpperCase(Locale.ROOT);
            return this;
        }

        public Builder outOfAmmoMessage(String outOfAmmoMessage) {
            this.outOfAmmoMessage = outOfAmmoMessage;
            return this;
        }

        public Builder permissionMessage(String permissionMessage) {
            this.permissionMessage = permissionMessage;
            return this;
        }

        public Builder remnantItem(Material remnantItem) {
            this.remnantItem = remnantItem == null ? Material.AIR : remnantItem;
            return this;
        }

        public Builder remnantName(String remnantName) {
            this.remnantName = remnantName == null ? "" : remnantName;
            return this;
        }

        public Builder remnantAmount(int remnantAmount) {
            this.remnantAmount = remnantAmount;
            return this;
        }

        public Builder remnantPickupDelay(int remnantPickupDelay) {
            this.remnantPickupDelay = remnantPickupDelay;
            return this;
        }

        public Builder remnantLifetime(int remnantLifetime) {
            this.remnantLifetime = remnantLifetime;
            return this;
        }

        public Builder laserSight(boolean laserSight) {
            this.laserSight = laserSight;
            return this;
        }

        public Builder laserSightAimOnly(boolean laserSightAimOnly) {
            this.laserSightAimOnly = laserSightAimOnly;
            return this;
        }

        public Builder laserSightColor(Color laserSightColor) {
            this.laserSightColor = laserSightColor;
            return this;
        }

        public Builder laserSightRange(double laserSightRange) {
            this.laserSightRange = laserSightRange;
            return this;
        }

        public Builder laserSightSize(float laserSightSize) {
            this.laserSightSize = laserSightSize;
            return this;
        }

        public Builder laserSightOffsetRight(double laserSightOffsetRight) {
            this.laserSightOffsetRight = laserSightOffsetRight;
            return this;
        }

        public Builder laserSightOffsetUp(double laserSightOffsetUp) {
            this.laserSightOffsetUp = laserSightOffsetUp;
            return this;
        }

        public Builder laserSightOffsetForward(double laserSightOffsetForward) {
            this.laserSightOffsetForward = laserSightOffsetForward;
            return this;
        }

        public Builder laserSightDensity(double laserSightDensity) {
            this.laserSightDensity = laserSightDensity;
            return this;
        }

        public Builder laserSightGlow(boolean laserSightGlow) {
            this.laserSightGlow = laserSightGlow;
            return this;
        }

        public Builder laserSightGlowStrength(double laserSightGlowStrength) {
            this.laserSightGlowStrength = laserSightGlowStrength;
            return this;
        }

        public Builder laserSightIr(boolean laserSightIr) {
            this.laserSightIr = laserSightIr;
            return this;
        }

        public Builder ammoCaliber(String ammoCaliber) {
            this.ammoCaliber = ammoCaliber;
            return this;
        }

        public Builder clearAllowedRounds() {
            this.allowedRounds.clear();
            return this;
        }

        public Builder addAllowedRound(String roundId) {
            if (roundId != null && !roundId.isBlank()) {
                this.allowedRounds.add(roundId.trim().toLowerCase(Locale.ROOT));
            }
            return this;
        }

        public Builder allowedRounds(List<String> roundIds) {
            this.allowedRounds.clear();
            if (roundIds != null) {
                for (String id : roundIds) {
                    addAllowedRound(id);
                }
            }
            return this;
        }

        public Builder muzzleFlash(boolean muzzleFlash) {
            this.muzzleFlash = muzzleFlash;
            return this;
        }

        public Builder muzzleColor(Color muzzleColor) {
            this.muzzleColor = muzzleColor;
            return this;
        }

        public Builder muzzleScale(float muzzleScale) {
            this.muzzleScale = muzzleScale;
            return this;
        }

        public Builder addGunSound(Sound sound) {
            if (sound != null) {
                this.gunSounds.add(sound);
            }
            return this;
        }

        public Builder setCanAim(boolean canAim) {
            this.canAimLeft = canAim;
            this.canAimRight = false;
            this.canClickLeft = !canAim;
            this.canClickRight = true;
            return this;
        }

        public GunDefinition build() {
            if (accuracyAimed < 0) {
                accuracyAimed = accuracy;
            }
            if (accuracyCrouched < 0) {
                accuracyCrouched = accuracy;
            }
            if (roundsPerBurst < 1) {
                roundsPerBurst = 1;
            }
            if (bulletsPerClick < 1) {
                bulletsPerClick = 1;
            }
            return new GunDefinition(this);
        }
    }
}
