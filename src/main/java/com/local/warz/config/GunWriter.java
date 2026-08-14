package com.local.warz.config;

import com.local.warz.model.GunDraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class GunWriter {
    private GunWriter() {
    }

    public static void write(Path file, GunDraft draft) throws IOException {
        draft.sanitizeFileName();
        List<String> lines = new ArrayList<>();
        lines.add("gunName=" + nullToEmpty(draft.displayName));
        lines.add("-------");
        lines.add("gunType=" + draft.gunMaterial.name());
        if (draft.consumable) {
            lines.add("ammoType=SELF");
            lines.add("consumable=true");
        } else {
            lines.add("ammoType=" + draft.ammoMaterial.name());
        }
        lines.add("ammoAmtNeeded=" + draft.ammoAmtNeeded);
        lines.add("ammoCaliber=" + (draft.ammoCaliber == null || draft.ammoCaliber.isBlank()
                ? AmmoCaliber.fromMaterial(draft.ammoMaterial) : AmmoCaliber.normalize(draft.ammoCaliber)));
        if (draft.allowedRounds != null && !draft.allowedRounds.isEmpty()) {
            lines.add("allowedRounds=" + draft.allowedRounds.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> s.trim().toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(",")));
        }
        lines.add("muzzleFlash=" + draft.muzzleFlash);
        lines.add("muzzleColor=" + (draft.muzzleColor == null || draft.muzzleColor.isBlank() ? "#FFBE5A" : draft.muzzleColor.trim()));
        lines.add("muzzleScale=" + trimDouble(draft.muzzleScale));
        lines.add("bulletDelayTime=" + draft.bulletDelayTime);
        if (draft.bulletType != null && !draft.bulletType.isBlank()) {
            lines.add("bulletType=" + draft.bulletType.trim());
        }
        lines.add("-------");
        lines.add("roundsPerBurst=" + draft.roundsPerBurst);
        lines.add("bulletsPerClick=" + draft.bulletsPerClick);
        lines.add("gunDamage=" + draft.gunDamage);
        lines.add("armorPenetration=" + draft.armorPenetration);
        lines.add("maxDistance=" + draft.maxDistance);
        lines.add("canGoPastMaxDistance=" + draft.canGoPastMaxDistance);
        lines.add("bulletSpeed=" + trimDouble(draft.bulletSpeed));
        if (draft.fallSpeed > 0) {
            lines.add("fallSpeed=" + trimDouble(draft.fallSpeed));
        }
        lines.add("accuracy=" + trimDouble(draft.accuracy));
        lines.add("accuracy_aimed=" + trimDouble(draft.accuracyAimed));
        lines.add("accuracy_crouched=" + trimDouble(draft.accuracyCrouched));
        lines.add("recoil=" + trimDouble(draft.recoil));
        lines.add("recoilpitch=" + trimDouble(draft.recoilPitch));
        lines.add("knockback=" + trimDouble(draft.knockback));
        lines.add("-------");
        lines.add("canAim=" + draft.canAim);
        lines.add("canClickLeft=" + draft.canClickLeft);
        lines.add("canClickRight=" + draft.canClickRight);
        lines.add("canHeadshot=" + draft.canHeadshot);
        lines.add("resetHitCooldown=" + draft.resetHitCooldown);
        lines.add("explodeRadius=" + trimDouble(draft.explodeRadius));
        lines.add("explosionDamage=" + draft.explosionDamage);
        lines.add("fireRadius=" + trimDouble(draft.fireRadius));
        lines.add("flashRadius=" + trimDouble(draft.flashRadius));
        lines.add("destroyBulletWhenHit=" + draft.destroyBulletWhenHit);
        lines.add("explodeOnImpact=" + draft.explodeOnImpact);
        lines.add("hasSmokeTrail=" + draft.hasSmokeTrail);
        lines.add("timeUntilRelease=" + draft.releaseTime);
        if (draft.remnantItem != null && !draft.remnantItem.isAir() && draft.remnantItem.isItem()) {
            lines.add("remnantItem=" + draft.remnantItem.name());
            if (draft.remnantName != null && !draft.remnantName.isBlank()) {
                lines.add("remnantName=" + draft.remnantName);
            }
            lines.add("remnantAmount=" + Math.max(1, draft.remnantAmount));
            lines.add("remnantPickupDelay=" + Math.max(0, draft.remnantPickupDelay));
            lines.add("remnantLifetime=" + draft.remnantLifetime);
        } else {
            lines.add("remnantItem=NONE");
        }
        lines.add("laserSight=" + draft.laserSight);
        lines.add("laserSightAimOnly=" + draft.laserSightAimOnly);
        lines.add("laserSightColor=" + (draft.laserSightColor == null || draft.laserSightColor.isBlank()
                ? "#FF2020" : draft.laserSightColor.trim()));
        lines.add("laserSightRange=" + trimDouble(draft.laserSightRange));
        lines.add("laserSightSize=" + trimDouble(draft.laserSightSize));
        lines.add("laserSightOffsetRight=" + trimDouble(draft.laserSightOffsetRight));
        lines.add("laserSightOffsetUp=" + trimDouble(draft.laserSightOffsetUp));
        lines.add("laserSightOffsetForward=" + trimDouble(draft.laserSightOffsetForward));
        lines.add("laserSightDensity=" + trimDouble(draft.laserSightDensity));
        lines.add("laserSightGlow=" + draft.laserSightGlow);
        lines.add("laserSightGlowStrength=" + trimDouble(draft.laserSightGlowStrength));
        lines.add("laserSightIr=" + draft.laserSightIr);
        lines.add("-------");
        lines.add("outOfAmmoMessage=" + nullToEmpty(draft.outOfAmmoMessage));
        lines.add("needsPermission=" + draft.needsPermission);
        if (draft.permissionMessage != null && !draft.permissionMessage.isBlank()) {
            lines.add("permissionMessage=" + draft.permissionMessage);
        }
        lines.add("-------");
        lines.add("hasClip=" + draft.hasClip);
        lines.add("maxClipSize=" + draft.maxClipSize);
        lines.add("reloadGunOnDrop=" + draft.reloadGunOnDrop);
        lines.add("reloadTime=" + draft.reloadTime);
        lines.add("reloadType=" + draft.reloadType.toUpperCase(Locale.ROOT));
        lines.add("-------");
        lines.add("localGunSound=" + draft.localGunSound);
        lines.add("gunVolume=" + trimDouble(draft.gunVolume));
        String sounds = draft.gunSounds == null ? "" : draft.gunSounds.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(","));
        if (!sounds.isBlank()) {
            lines.add("gunSound=" + sounds);
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String trimDouble(double value) {
        if (Math.rint(value) == value) {
            return Integer.toString((int) value);
        }
        return Double.toString(value);
    }
}
