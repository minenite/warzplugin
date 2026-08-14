package com.local.warz.projectile;

import com.local.warz.model.GunDefinition;
import com.local.warz.runtime.MagPlatform;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Sniper zero / drop helpers. 1 game-yard ≈ 1 block for HUD + holdover.
 * Soft gravity matches {@link Bullet#applyFallBallistics()} (incl. default sniper fall).
 */
public final class SniperBallistics {
    /** Default fall per tick when YAML omits fallSpeed (straight-line snipers felt wrong). */
    public static final double DEFAULT_SNIPER_FALL = 0.014;
    public static final double HORIZ_DRAG = 0.988;
    /** Legacy coarse holdover (° per yard of zero above 100) — fallback only. */
    public static final double LEGACY_DEG_PER_YD = 0.012;

    private SniperBallistics() {
    }

    public static boolean isSniper(GunDefinition gun) {
        return gun != null && MagPlatform.forGun(gun) == MagPlatform.SNIPER;
    }

    public static double fallSpeed(GunDefinition gun) {
        if (gun == null) {
            return DEFAULT_SNIPER_FALL;
        }
        double f = gun.fallSpeed();
        if (f > 0.0) {
            return f;
        }
        return isSniper(gun) ? DEFAULT_SNIPER_FALL : 0.0;
    }

    public static double bulletSpeed(GunDefinition gun) {
        if (gun == null) {
            return 4.5;
        }
        return Math.max(1.5, gun.bulletSpeed());
    }

    /**
     * Elevation (degrees above look) so POI meets the look ray at {@code zeroYards}.
     */
    public static double holdoverDegrees(GunDefinition gun, int zeroYards) {
        int z = Math.max(50, Math.min(1000, zeroYards));
        double speed = bulletSpeed(gun);
        double fall = fallSpeed(gun);
        if (fall <= 0.0) {
            return (z - 100) * LEGACY_DEG_PER_YD;
        }
        return elevationForZero(speed, fall, z);
    }

    /**
     * Vertical offset (degrees, positive = below reticle) of impact at {@code rangeYards}
     * when zeroed at {@code zeroYards}. Used for mildot placement.
     */
    public static double holdunderDegrees(GunDefinition gun, int zeroYards, int rangeYards) {
        double speed = bulletSpeed(gun);
        double fall = fallSpeed(gun);
        double elev = holdoverDegrees(gun, zeroYards);
        return impactAngleBelowLook(speed, fall, elev, rangeYards);
    }

    /** Binary-search muzzle elevation so height≈0 at horizontal distance {@code range}. */
    public static double elevationForZero(double speed, double fall, double range) {
        if (range <= 1.0 || speed <= 0.1) {
            return 0.0;
        }
        double lo = -2.0;
        double hi = 12.0;
        for (int i = 0; i < 28; i++) {
            double mid = (lo + hi) * 0.5;
            double y = heightAtRange(speed, fall, mid, range);
            if (y > 0.0) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return (lo + hi) * 0.5;
    }

    /**
     * Angle from look ray down to impact point at range (degrees). Positive → mark below center.
     */
    public static double impactAngleBelowLook(double speed, double fall, double elevDeg, double range) {
        if (range <= 1.0) {
            return 0.0;
        }
        double y = heightAtRange(speed, fall, elevDeg, range);
        // Look ray height at range is 0 in this frame; impact at y.
        // Angle below look ≈ atan(-y / range) in degrees (y negative = dropped = below).
        return Math.toDegrees(Math.atan2(-y, range));
    }

    /** Height relative to horizontal look plane after traveling {@code range} blocks. */
    public static double heightAtRange(double speed, double fall, double elevDeg, double range) {
        double elev = Math.toRadians(elevDeg);
        double vx = speed * Math.cos(elev);
        double vy = speed * Math.sin(elev);
        double x = 0.0;
        double y = 0.0;
        int guard = 0;
        while (x < range && guard++ < 800) {
            x += vx;
            y += vy;
            vy -= fall;
            double terminal = Math.min(0.42, Math.max(0.045, fall * 8.5));
            if (vy < -terminal) {
                vy = -terminal;
            }
            vx *= HORIZ_DRAG;
            if (vx < 0.08) {
                break;
            }
        }
        return y;
    }

    /**
     * True when a solid ~1-high rest sits in front of the muzzle line
     * (block to rest on, clear above to shoot over).
     */
    public static boolean hasRifleRest(Player player, boolean prone) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        Location base = player.getLocation();
        Vector flat = base.getDirection().clone();
        flat.setY(0);
        if (flat.lengthSquared() < 1.0e-4) {
            return false;
        }
        flat.normalize();
        int feetY = base.getBlockY();
        // Standing/crouch: rest top at feet+1. Prone: rest at body height (feet / feet+1).
        int[] restYs = prone ? new int[]{feetY, feetY + 1} : new int[]{feetY + 1};
        for (double d = 0.55; d <= 1.45; d += 0.2) {
            Location probe = base.clone().add(flat.clone().multiply(d));
            int bx = probe.getBlockX();
            int bz = probe.getBlockZ();
            for (int ry : restYs) {
                Block rest = player.getWorld().getBlockAt(bx, ry, bz);
                if (!isRestSolid(rest)) {
                    continue;
                }
                Block above = rest.getRelative(BlockFace.UP);
                if (isRestSolid(above)) {
                    continue;
                }
                // Must be close — resting on it, not a wall far ahead
                Location top = rest.getLocation().add(0.5, 1.0, 0.5);
                if (base.distanceSquared(top) <= 2.6 * 2.6) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isRestSolid(Block block) {
        if (block == null || block.isEmpty() || block.isLiquid()) {
            return false;
        }
        return block.getType().isSolid();
    }

    /** Block/entity range along look for rangefinder (blocks ≈ yd). */
    public static double rangeYards(Player player, double max) {
        if (player == null) {
            return -1;
        }
        Location eye = player.getEyeLocation();
        RayTraceResult hit = player.getWorld().rayTrace(
                eye, eye.getDirection(), max, FluidCollisionMode.NEVER, true, 0.0,
                e -> e != player && e.isValid());
        if (hit == null || hit.getHitPosition() == null) {
            return -1;
        }
        return eye.toVector().distance(hit.getHitPosition());
    }
}
