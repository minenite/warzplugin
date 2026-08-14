package com.local.warz.config;

import com.local.warz.model.GunDefinition;
import org.bukkit.Sound;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class WeaponReader {
    private WeaponReader() {
    }

    public static GunDefinition read(Path file, boolean throwable, int customModelData) throws IOException {
        String fileName = file.getFileName().toString();
        GunDefinition.Builder builder = new GunDefinition.Builder()
                .fileName(fileName)
                .displayName(fileName)
                .throwable(throwable)
                // Projectile-folder nades cook on the fuse, not on first bounce / maxDistance=0.
                .explodeOnImpact(!throwable)
                .customModelData(customModelData);

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                apply(builder, line);
            }
        }
        builder.gunMaterial(org.bukkit.Material.STICK);
        return builder.build();
    }

    private static void apply(GunDefinition.Builder builder, String rawLine) {
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#") || !line.contains("=") || line.startsWith("-")) {
            return;
        }
        int eq = line.indexOf('=');
        String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
        String value = line.substring(eq + 1).trim();
        switch (key) {
            case "gunname" -> builder.displayName(value);
            case "guntype" -> builder.gunMaterial(LegacyMaterialMap.fromLegacy(value));
            case "ammotype" -> {
                String ammo = value.trim().toLowerCase(Locale.ROOT);
                if (ammo.equals("self") || ammo.equals("this") || ammo.equals("gun")
                        || ammo.equals("consumable") || ammo.equals("item")) {
                    builder.consumable(true);
                } else {
                    builder.ammoMaterial(LegacyMaterialMap.fromLegacy(value));
                }
            }
            case "ammoamtneeded" -> builder.ammoAmtNeeded(parseInt(value, 1));
            case "consumable", "selfammo", "ammousesself", "useself" ->
                    builder.consumable(parseBool(value, false));
            case "gundamage" -> builder.gunDamage(parseInt(value, 1));
            case "armorpenetration" -> builder.armorPenetration(parseInt(value, 0));
            case "explosiondamage" -> builder.explosionDamage(parseInt(value, -1));
            case "reloadtime" -> builder.reloadTime(parseInt(value, 20));
            case "reloadtype" -> builder.reloadType(value);
            case "maxclipsize" -> builder.maxClipSize(parseInt(value, 30));
            case "hasclip" -> builder.hasClip(parseBool(value, true));
            case "reloadgunondrop" -> builder.reloadGunOnDrop(parseBool(value, true));
            case "roundsperburst" -> builder.roundsPerBurst(parseInt(value, 1));
            case "bulletsperclick" -> builder.bulletsPerClick(parseInt(value, 1));
            case "bulletspeed" -> builder.bulletSpeed(parseDouble(value, 3.5));
            case "fallspeed", "gravity", "bulletfall", "bulletfallspeed" ->
                    builder.fallSpeed(parseDouble(value, 0.0));
            case "bulletdelaytime" -> builder.bulletDelayTime(parseInt(value, 10));
            case "maxdistance" -> builder.maxDistance(parseInt(value, 50));
            case "cangopastmaxdistance" -> builder.canGoPastMaxDistance(parseBool(value, false));
            case "accuracy" -> builder.accuracy(parseDouble(value, 0.1));
            case "accuracy_aimed" -> builder.accuracyAimed(parseDouble(value, -1));
            case "accuracy_crouched" -> builder.accuracyCrouched(parseDouble(value, -1));
            case "recoil" -> builder.recoil(parseDouble(value, 0));
            case "recoilpitch", "recoil_pitch" -> builder.recoilPitch(parseDouble(value, 0));
            case "knockback" -> builder.knockback(parseDouble(value, 0));
            case "exploderadius" -> builder.explodeRadius(parseDouble(value, 0));
            case "blastshockradius", "shockradius" -> builder.blastShockRadius(parseDouble(value, -1));
            case "blastshockstrength", "shockstrength" -> builder.blastShockStrength(parseDouble(value, -1));
            case "fireradius" -> builder.fireRadius(parseDouble(value, 0));
            case "flashradius" -> builder.flashRadius(parseDouble(value, 0));
            case "canheadshot" -> builder.canHeadshot(parseBool(value, true));
            case "resethitcooldown", "reset_hit_cooldown", "ignorehitcooldown", "ignore_hit_cooldown" ->
                    builder.resetHitCooldown(parseBool(value, true));
            case "canshootleft", "canclickleft" -> builder.canClickLeft(parseBool(value, false));
            case "canshootright", "canclickright" -> builder.canClickRight(parseBool(value, true));
            case "canaim" -> builder.setCanAim(parseBool(value, true));
            case "canaimleft" -> builder.canAimLeft(parseBool(value, true));
            case "canaimright" -> builder.canAimRight(parseBool(value, false));
            case "bullettype" -> builder.bulletType(value);
            case "gunvolume" -> builder.gunVolume(parseDouble(value, 1.0));
            case "localgunsound" -> builder.localGunSound(parseBool(value, true));
            case "hassmoketrail" -> builder.hasSmokeTrail(parseBool(value, false));
            case "needspermission" -> builder.needsPermission(parseBool(value, false));
            case "permissionmessage" -> builder.permissionMessage(value);
            case "outofammomessage" -> builder.outOfAmmoMessage(value);
            case "destroybulletwhenhit" -> builder.destroyBulletWhenHit(parseBool(value, true));
            case "explodeonimpact", "detonateonimpact", "impactfuse" ->
                    builder.explodeOnImpact(parseBool(value, true));
            case "timeuntilrelease" -> builder.releaseTime(parseInt(value, -1));
            case "remnantitem", "leftoveritem", "dropafteritem", "remnanttype" -> {
                String v = value.trim().toLowerCase(Locale.ROOT);
                if (v.isEmpty() || v.equals("none") || v.equals("air") || v.equals("false") || v.equals("0") || v.equals("off")) {
                    builder.remnantItem(org.bukkit.Material.AIR);
                } else {
                    builder.remnantItem(LegacyMaterialMap.fromLegacy(value));
                }
            }
            case "remnantname", "leftovername", "dropaftername" -> builder.remnantName(value);
            case "remnantamount", "leftoveramount" -> builder.remnantAmount(parseInt(value, 1));
            case "remnantpickupdelay", "leftoverpickupdelay" -> builder.remnantPickupDelay(parseInt(value, 0));
            case "remnantlifetime", "leftoverlifetime", "remnantdespawn" -> builder.remnantLifetime(parseInt(value, -1));
            case "lasersight", "haslasersight", "laserpointer", "haslaser" ->
                    builder.laserSight(parseBool(value, false));
            case "lasersightaimonly", "laseraimonly", "lasersightonlyaim", "laseronlywhenaiming" ->
                    builder.laserSightAimOnly(parseBool(value, true));
            case "lasersightcolor", "lasercolor", "laserpointercolor" ->
                    builder.laserSightColor(com.local.warz.util.LaserBeams.parseColor(value, org.bukkit.Color.RED));
            case "lasersightrange", "laserrange", "laserpointerrange" ->
                    builder.laserSightRange(parseDouble(value, -1));
            case "lasersightsize", "lasersize", "laserpointersize" ->
                    builder.laserSightSize((float) parseDouble(value, 0.28));
            case "lasersightoffsetright", "laseroffsetright", "laseroffsetx" ->
                    builder.laserSightOffsetRight(parseDouble(value, 0.32));
            case "lasersightoffsetup", "laseroffsetup", "laseroffsety" ->
                    builder.laserSightOffsetUp(parseDouble(value, -0.28));
            case "lasersightoffsetforward", "laseroffsetforward", "laseroffsetz" ->
                    builder.laserSightOffsetForward(parseDouble(value, 0.55));
            case "lasersightdensity", "laserdensity", "laserparticledensity" ->
                    builder.laserSightDensity(parseDouble(value, 1.0));
            case "lasersightglow", "laserglow", "laserpointerglow" ->
                    builder.laserSightGlow(parseBool(value, false));
            case "lasersightglowstrength", "laserglowstrength" ->
                    builder.laserSightGlowStrength(parseDouble(value, 0.35));
            case "lasersightir", "laserir", "infraredlaser", "irlaser", "laserinfrared" ->
                    builder.laserSightIr(parseBool(value, false));
            case "custommodeldata", "cmd", "modeldata", "custom_model_data" ->
                    builder.customModelData(parseInt(value, 1));
            case "ammocaliber", "caliber" -> builder.ammoCaliber(value);
            case "allowedrounds", "ammoRounds", "ammorounds", "rounds" -> {
                builder.clearAllowedRounds();
                for (String part : value.split("[,;|]")) {
                    builder.addAllowedRound(part.trim());
                }
            }
            case "muzzleflash", "hasmuzzleflash" -> builder.muzzleFlash(parseBool(value, true));
            case "muzzlecolor" -> builder.muzzleColor(com.local.warz.util.LaserBeams.parseColor(value, org.bukkit.Color.fromRGB(255, 190, 90)));
            case "muzzlescale" -> builder.muzzleScale((float) parseDouble(value, 0.85));
            case "gunsound" -> {
                for (String part : value.split(",")) {
                    Sound sound = LegacySoundMap.resolve(part);
                    builder.addGunSound(sound);
                }
            }
            default -> {
                // ignore unknown keys / separators
            }
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean parseBool(String value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1", "on" -> true;
            case "false", "no", "0", "off" -> false;
            default -> fallback;
        };
    }
}
