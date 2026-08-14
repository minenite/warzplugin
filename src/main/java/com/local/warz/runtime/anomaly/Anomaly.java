package com.local.warz.runtime.anomaly;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server-side paranormal contact (not a Bukkit entity). */
public final class Anomaly {
    public final UUID id = UUID.randomUUID();
    public AnomalyType type;
    public World world;
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    public float scaleY = 1f;
    public Vector vel = new Vector();
    public int age;
    public int lifeTicks = 20 * 90;
    /** Sync: 0–1 interference contribution. */
    public float interference;
    /** Sync: Burn-In look progress 0–1. */
    public float lookProgress;
    public boolean visible = true;
    public boolean bright;
    public boolean noBody;
    public boolean voidMask;
    public boolean distort;

    public UUID skinUuid;
    public UUID targetPlayer;
    /** Echo: delay ticks before replay starts. */
    public int echoDelayTicks;
    public int echoCursor;
    public final List<PoseSample> echoClip = new ArrayList<>();
    /** Residual path. */
    public final List<Location> path = new ArrayList<>();
    public int pathIndex;
    /** Glitch limb offsets (world-relative). */
    public final float[] partOx = new float[4];
    public final float[] partOy = new float[4];
    public final float[] partOz = new float[4];
    public int partCount;
    public int coolDown;
    public int stuckTicks;
    public double lastApproachDist = Double.MAX_VALUE;
    /** Fixed world anchor for Mimic/Imposter when solo (no second player to copy). */
    public double homeX;
    public double homeY;
    public double homeZ;
    public boolean hasHome;

    public Anomaly(AnomalyType type, World world, double x, double y, double z, float yaw) {
        this.type = type;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.homeX = x;
        this.homeY = y;
        this.homeZ = z;
        this.hasHome = true;
    }

    public Location location() {
        return new Location(world, x, y, z, yaw, pitch);
    }

    public void setPos(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        world = loc.getWorld();
        x = loc.getX();
        y = loc.getY();
        z = loc.getZ();
        yaw = loc.getYaw();
        pitch = loc.getPitch();
    }

    public record PoseSample(double x, double y, double z, float yaw, float pitch) {
    }
}
