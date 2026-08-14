package com.local.warz.runtime;

import com.local.warz.config.AmmoCaliber;
import com.local.warz.model.GunDefinition;
import com.local.warz.model.RoundDefinition;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Caliber-aware gunshot crack + flyby pass-by audio.
 * Subsonic rounds never produce a sonic crack; suppressor + subsonic is a true whisper.
 * Custom {@code pvpgunminus:*} events are preferred; vanilla sounds back them up.
 */
public final class BulletAudio {
    private BulletAudio() {
    }

    public record Profile(
            String flybyEvent,
            String crackEvent,
            Sound flybyFallback,
            Sound crackFallback,
            float flybyVolume,
            float flybyPitchMin,
            float flybyPitchMax,
            float crackVolume,
            float crackPitch,
            double hearDistance,
            double passRadius,
            boolean hasCrack
    ) {
    }

    public static Profile profileFor(String caliber) {
        return switch (AmmoCaliber.normalize(caliber)) {
            case "pistol", "handgun" -> new Profile(
                    "pvpgunminus:bullet.flyby.pistol", null,
                    Sound.ENTITY_ARROW_SHOOT, null,
                    0.55f, 1.55f, 1.85f,
                    0f, 1f, 28.0, 3.2, false);
            case "sniper", "heavy" -> new Profile(
                    "pvpgunminus:bullet.flyby.sniper", "pvpgunminus:bullet.crack.sniper",
                    Sound.ENTITY_FIREWORK_ROCKET_BLAST, Sound.ENTITY_LIGHTNING_BOLT_THUNDER,
                    1.0f, 1.15f, 1.45f,
                    0.55f, 1.75f, 64.0, 5.0, true);
            case "shotgun", "shot" -> new Profile(
                    "pvpgunminus:bullet.flyby.shotgun", null,
                    Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, null,
                    0.7f, 0.7f, 0.95f,
                    0f, 1f, 26.0, 3.8, false);
            case "arrow", "bolt" -> new Profile(
                    "pvpgunminus:bullet.flyby.pistol", null,
                    Sound.ENTITY_ARROW_SHOOT, null,
                    0.4f, 0.9f, 1.2f,
                    0f, 1f, 22.0, 2.8, false);
            case "rocket", "launcher" -> new Profile(
                    "pvpgunminus:bullet.flyby.shotgun", null,
                    Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, null,
                    0.85f, 0.55f, 0.75f,
                    0f, 1f, 40.0, 4.5, false);
            case "energy", "plasma", "laser" -> new Profile(
                    "pvpgunminus:bullet.flyby.rifle", null,
                    Sound.BLOCK_BEACON_AMBIENT, null,
                    0.45f, 1.6f, 1.9f,
                    0f, 1f, 32.0, 3.0, false);
            default -> new Profile(
                    "pvpgunminus:bullet.flyby.rifle", "pvpgunminus:bullet.crack.rifle",
                    Sound.ENTITY_FIREWORK_ROCKET_BLAST, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST,
                    0.8f, 1.25f, 1.55f,
                    0.4f, 1.65f, 48.0, 4.0, true);
        };
    }

    public static String caliberOf(GunDefinition gun, RoundDefinition round) {
        if (round != null && round.caliber() != null && !round.caliber().isBlank()) {
            return round.caliber();
        }
        return gun != null ? gun.ammoCaliber() : "rifle";
    }

    public static boolean isSubsonic(RoundDefinition round) {
        return round != null && round.subsonic();
    }

    /**
     * Sonic boom at the muzzle for rounds that go supersonic.
     * Subsonic never cracks. Suppressed + supersonic still cracks (quieter) — the can
     * only kills muzzle blast, not the Mach cone.
     */
    public static void playMuzzleCrack(Location at, GunDefinition gun, RoundDefinition round, boolean suppressed) {
        if (at == null || at.getWorld() == null || gun == null) {
            return;
        }
        if (gun.throwable() || gun.consumable() || gun.isLaser()) {
            return;
        }
        if (isSubsonic(round)) {
            return;
        }
        Profile p = profileFor(caliberOf(gun, round));
        if (!p.hasCrack()) {
            return;
        }
        float volMul = suppressed ? 0.5f : 0.85f;
        double hear = p.hearDistance() * (suppressed ? 0.72 : 1.0);
        play(at, p.crackEvent(), p.crackFallback(), p.crackVolume() * volMul, p.crackPitch(), hear);
    }

    /**
     * If the segment from {@code from} → {@code to} passes near a listener, play a flyby once.
     * Subsonic = soft whoosh (no crack whip). Suppressed + subsonic ≈ near-silent.
     */
    public static void tickFlyby(Location from, Location to, UUID shooterId,
                                 GunDefinition gun, RoundDefinition round,
                                 boolean suppressed, Set<UUID> heard) {
        if (from == null || to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return;
        }
        if (gun != null && (gun.throwable() || gun.consumable() || gun.isLaser())) {
            return;
        }
        boolean sub = isSubsonic(round);
        Profile p = profileFor(caliberOf(gun, round));
        double hear = p.hearDistance() * (sub ? 0.45 : 1.0) * (suppressed && sub ? 0.55 : 1.0);
        double passR = p.passRadius() * (sub ? 0.75 : 1.0);
        double passR2 = passR * passR;
        float vol = p.flybyVolume();
        if (sub && suppressed) {
            vol *= 0.08f; // whisper pass — almost nothing
        } else if (sub) {
            vol *= 0.28f; // soft air whoosh, not a crack
        } else if (suppressed) {
            vol *= 0.55f; // can + still-supersonic bullet
        }
        if (vol < 0.04f) {
            return;
        }
        float pitchMin = sub ? Math.max(0.55f, p.flybyPitchMin() - 0.45f) : p.flybyPitchMin();
        float pitchMax = sub ? Math.max(pitchMin + 0.05f, p.flybyPitchMax() - 0.4f) : p.flybyPitchMax();
        float pitch = lerp(pitchMin, pitchMax, ThreadLocalRandom.current().nextFloat());
        String event = sub ? "pvpgunminus:bullet.flyby.subsonic" : p.flybyEvent();
        Sound fallback = sub ? Sound.ENTITY_BAT_TAKEOFF : p.flybyFallback();
        World world = from.getWorld();
        Location mid = from.clone().add(to).multiply(0.5);
        for (Player listener : world.getPlayers()) {
            if (listener.getUniqueId().equals(shooterId)) {
                continue;
            }
            if (heard != null && heard.contains(listener.getUniqueId())) {
                continue;
            }
            Location ear = listener.getEyeLocation();
            if (ear.distanceSquared(mid) > hear * hear) {
                continue;
            }
            double dist2 = distPointToSegmentSq(ear.toVector(), from.toVector(), to.toVector());
            if (dist2 > passR2) {
                continue;
            }
            float near = (float) (1.0 - Math.sqrt(dist2) / passR);
            float localVol = vol * (0.45f + 0.55f * near);
            Location playAt = closestPoint(ear.toVector(), from.toVector(), to.toVector())
                    .toLocation(world);
            play(playAt, event, fallback, localVol, pitch, hear);
            if (heard != null) {
                heard.add(listener.getUniqueId());
            }
        }
    }

    /** Suppressed muzzle report. Subsonic + can = Hollywood whisper. */
    public static void playSuppressedLayer(Location at, float gunVolume, boolean subsonic) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        if (subsonic) {
            float vol = Math.max(0.18f, Math.min(0.55f, gunVolume * 0.28f));
            play(at, "pvpgunminus:gun.suppressed_subsonic", Sound.BLOCK_FIRE_EXTINGUISH, vol, 0.85f, 12.0);
            play(at, null, Sound.BLOCK_NOTE_BLOCK_HAT, vol * 0.55f, 0.7f, 11.0);
            play(at, null, Sound.ENTITY_ARROW_SHOOT, vol * 0.35f, 0.55f, 10.0);
            play(at, null, Sound.BLOCK_SAND_HIT, vol * 0.4f, 1.4f, 10.0);
            return;
        }
        // Supersonic through a can — still louder; crack is layered separately
        float vol = Math.max(0.35f, Math.min(1.1f, gunVolume * 0.55f));
        play(at, "pvpgunminus:gun.suppressed", Sound.BLOCK_FIRE_EXTINGUISH, vol, 1.55f, 22.0);
        play(at, null, Sound.BLOCK_NOTE_BLOCK_HAT, vol * 0.85f, 1.85f, 20.0);
        play(at, null, Sound.ENTITY_ARROW_SHOOT, vol * 0.4f, 1.7f, 18.0);
    }

    /**
     * Unsuppressed subsonic: dull mechanical thump, no sonic crack, shorter carry.
     * Callers should also play the gun's own sounds quieter / lower-pitched.
     */
    public static void playSubsonicUnsuppressed(Location at, float gunVolume) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        float vol = Math.max(0.4f, Math.min(1.0f, gunVolume * 0.62f));
        play(at, "pvpgunminus:gun.subsonic", Sound.ENTITY_FIREWORK_ROCKET_BLAST, vol * 0.45f, 0.65f, 28.0);
        play(at, null, Sound.ENTITY_IRON_GOLEM_ATTACK, vol * 0.55f, 0.75f, 26.0);
        play(at, null, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, vol * 0.7f, 0.55f, 24.0);
        play(at, null, Sound.ENTITY_ARROW_SHOOT, vol * 0.35f, 0.8f, 22.0);
    }

    /** Muzzle report when the barrel is submerged — muffled, short carry, watery. */
    public static void playUnderwaterMuzzle(Location at, float gunVolume, boolean suppressed, boolean subsonic) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        float vol = Math.max(0.2f, Math.min(0.85f, gunVolume * (suppressed ? 0.22f : 0.4f)));
        if (subsonic) {
            vol *= 0.7f;
        }
        play(at, "pvpgunminus:gun.underwater", Sound.ENTITY_GENERIC_SPLASH, vol, 0.55f, 14.0);
        play(at, null, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, vol * 0.9f, 0.8f, 12.0);
        play(at, null, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, vol * 0.45f, 1.4f, 11.0);
        if (!suppressed) {
            play(at, null, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, vol * 0.55f, 0.45f, 13.0);
        }
    }

    private static void play(Location at, String event, Sound fallback,
                             float volume, float pitch, double categoryDistance) {
        World world = at.getWorld();
        if (world == null || volume <= 0.01f) {
            return;
        }
        // Listener underwater hears less crack / more thud
        float vol = volume;
        float pit = pitch;
        if (WaterBallistics.isWater(at)) {
            vol *= 0.55f;
            pit = Math.max(0.5f, pit * 0.72f);
            categoryDistance *= 0.55;
        }
        if (event != null && !event.isBlank()) {
            try {
                world.playSound(at, event, SoundCategory.PLAYERS, vol, pit);
            } catch (Throwable ignored) {
                // custom event may be absent
            }
        }
        if (fallback != null) {
            world.playSound(at, fallback, SoundCategory.PLAYERS, vol * 0.85f, pit);
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static double distPointToSegmentSq(Vector p, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double ab2 = ab.lengthSquared();
        if (ab2 < 1.0E-8) {
            return p.distanceSquared(a);
        }
        double t = p.clone().subtract(a).dot(ab) / ab2;
        t = Math.max(0.0, Math.min(1.0, t));
        Vector closest = a.clone().add(ab.clone().multiply(t));
        return p.distanceSquared(closest);
    }

    private static Vector closestPoint(Vector p, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double ab2 = ab.lengthSquared();
        if (ab2 < 1.0E-8) {
            return a.clone();
        }
        double t = p.clone().subtract(a).dot(ab) / ab2;
        t = Math.max(0.0, Math.min(1.0, t));
        return a.clone().add(ab.clone().multiply(t));
    }

    public static Set<UUID> newHeardSet() {
        return new HashSet<>();
    }
}
