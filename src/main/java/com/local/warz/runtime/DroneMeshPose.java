package com.local.warz.runtime;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Per-airframe mesh tuning on top of the baked Blockbench model.
 * Angles in degrees; offsets in blocks (pre-scale, local space: +X right, +Y up, +Z forward).
 * Camera offsets nudge the operator sensor view from the default nose/belly point.
 */
public final class DroneMeshPose {
    public float yaw;
    public float pitch;
    public float roll;
    public float scaleMul = 1f;
    public float offX;
    public float offY;
    public float offZ;
    /** Operator camera: +X right, +Y up, +Z forward (blocks, after default sensor base). */
    public float camX;
    public float camY;
    public float camZ;

    public DroneMeshPose() {
    }

    public DroneMeshPose(DroneMeshPose o) {
        if (o == null) {
            return;
        }
        this.yaw = o.yaw;
        this.pitch = o.pitch;
        this.roll = o.roll;
        this.scaleMul = o.scaleMul;
        this.offX = o.offX;
        this.offY = o.offY;
        this.offZ = o.offZ;
        this.camX = o.camX;
        this.camY = o.camY;
        this.camZ = o.camZ;
    }

    public static DroneMeshPose identity() {
        return new DroneMeshPose();
    }

    public void load(ConfigurationSection sec) {
        if (sec == null) {
            return;
        }
        yaw = (float) sec.getDouble("yaw", 0);
        pitch = (float) sec.getDouble("pitch", 0);
        roll = (float) sec.getDouble("roll", 0);
        scaleMul = (float) sec.getDouble("scale", 1);
        if (!Float.isFinite(scaleMul) || scaleMul < 0.05f) {
            scaleMul = 1f;
        }
        offX = (float) sec.getDouble("off-x", 0);
        offY = (float) sec.getDouble("off-y", 0);
        offZ = (float) sec.getDouble("off-z", 0);
        camX = (float) sec.getDouble("cam-x", 0);
        camY = (float) sec.getDouble("cam-y", 0);
        camZ = (float) sec.getDouble("cam-z", 0);
    }

    public void save(ConfigurationSection sec) {
        sec.set("yaw", yaw);
        sec.set("pitch", pitch);
        sec.set("roll", roll);
        sec.set("scale", scaleMul);
        sec.set("off-x", offX);
        sec.set("off-y", offY);
        sec.set("off-z", offZ);
        sec.set("cam-x", camX);
        sec.set("cam-y", camY);
        sec.set("cam-z", camZ);
    }

    public String summary() {
        return String.format(java.util.Locale.ROOT,
                "yaw %.1f° pitch %.1f° roll %.1f° scale %.3f off(%.2f,%.2f,%.2f) cam(%.2f,%.2f,%.2f)",
                yaw, pitch, roll, scaleMul, offX, offY, offZ, camX, camY, camZ);
    }
}
