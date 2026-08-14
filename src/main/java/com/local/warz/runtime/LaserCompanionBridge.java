package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.util.LaserOptics;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Encodes and sends laser / FX packets to Fabric companion clients. */
public final class LaserCompanionBridge {
    /** Ground / infantry companion laser view distance. */
    private static final double VIEW_RANGE_SQ = 96.0 * 96.0;
    /** BigDrone sits ~100m up — need a much longer envelope to see ground lasers. */
    private static final double DRONE_VIEW_RANGE_SQ = 320.0 * 320.0;
    /** Thermal blast cues for MQ-9 white/black hot — matches long drone LAW slant range. */
    private static final double THERMAL_BLAST_VIEW_RANGE_SQ = 768.0 * 768.0;
    private static final double THERMAL_BLAST_GROUND_RANGE_SQ = 160.0 * 160.0;
    /** FX type 3 — companion TemperatureField.explode (see LaserPackets.FX_THERMAL_BLAST). */
    public static final byte FX_THERMAL_BLAST = 3;
    /** FX type 4 — companion TemperatureField.smokePlume (drone WH/BH crater columns). */
    public static final byte FX_THERMAL_SMOKE = 4;
    private static final double THERMAL_SMOKE_VIEW_RANGE_SQ = 820.0 * 820.0;
    private static final int MAX_SEGMENTS = 64;
    private static final long FLASH_MIN_INTERVAL_MS = 100L;
    /** Half-width across both eyes (player head ~0.6 wide). */
    private static final double EYE_LATERAL = 0.26;
    private static final double EYE_VERTICAL = 0.14;
    /** How far in front of / behind the eye plane still counts as a face hit. */
    private static final double EYE_FORWARD_MIN = -0.04;
    private static final double EYE_FORWARD_MAX = 0.16;
    /** Laser must travel into the face (against look dir). ~75° cone from dead-on. */
    private static final double FACE_APPROACH_DOT = -0.35;
    /** tipFlags bit0 = tip underwater (existing). */
    public static final int TIP_UNDERWATER = 1;
    /** tipFlags bit1 = BigDrone designator — visible on NV + thermal + pilot EO. */
    public static final int TIP_DRONE_IR = 2;
    /** tipFlags bit2 = gun / infantry IR laser (NV only; not color-heuristic). */
    public static final int TIP_GUN_IR = 4;
    /** tipFlags bit3 = suppressor — cut IR/NV bloom giveaway. */
    public static final int TIP_SUPPRESSED = 8;
    /** Muzzle FX trailing flag: suppressor. */
    public static final int FX_FLAG_SUPPRESSED = 1;
    /** Long envelope for drone IR designators (ground NV / thermal observers). */
    private static final double DRONE_IR_VIEW_RANGE_SQ = 384.0 * 384.0;

    private final WarzPlugin plugin;
    private final Map<UUID, Long> lastFlashMs = new ConcurrentHashMap<>();
    private volatile int lastSegmentCount;
    private volatile int lastViewerCount;

    public LaserCompanionBridge(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public int lastSegmentCount() {
        return lastSegmentCount;
    }

    public int lastViewerCount() {
        return lastViewerCount;
    }

    public void broadcastBeam(Player shooter, LaserOptics.BeamPath path, Color color, float baseWidth) {
        broadcastBeam(shooter, path, color, baseWidth, false);
    }

    public void broadcastBeam(Player shooter, LaserOptics.BeamPath path, Color color, float baseWidth, boolean infrared) {
        broadcastBeam(shooter, path, color, baseWidth, infrared, false);
    }

    /**
     * @param droneIr BigDrone designator — NV + thermal + the pilot (any optic) can see it;
     *                uses an extended view envelope so ground NODs pick it up from altitude.
     */
    public void broadcastBeam(Player shooter, LaserOptics.BeamPath path, Color color, float baseWidth,
                              boolean infrared, boolean droneIr) {
        broadcastBeam(shooter, path, color, baseWidth, infrared, droneIr, false);
    }

    public void broadcastBeam(Player shooter, LaserOptics.BeamPath path, Color color, float baseWidth,
                              boolean infrared, boolean droneIr, boolean suppressed) {
        if (shooter == null || path == null || path.tip() == null || path.tip().getWorld() == null) {
            return;
        }
        CompanionClients companions = plugin.companions();
        if (companions == null) {
            return;
        }
        float width = baseWidth;
        if (suppressed) {
            // Thin IR/visible pointer — much less NOD wash / AGC bloom
            width = Math.max(0.04f, baseWidth * (infrared ? 0.28f : 0.45f));
        }
        Location tip = path.tip();
        Location muzzleApprox;
        if (path.segments().isEmpty()) {
            muzzleApprox = shooter.getEyeLocation();
        } else {
            LaserOptics.Segment first = path.segments().get(0);
            muzzleApprox = new Location(tip.getWorld(), first.x0(), first.y0(), first.z0());
        }
        List<Player> targets = new ArrayList<>();
        for (Player viewer : tip.getWorld().getPlayers()) {
            if (droneIr) {
                if (!canSeeDroneInfrared(viewer, shooter)) {
                    continue;
                }
            } else if (infrared && !canSeeInfrared(viewer)) {
                continue;
            }
            double rangeSq = droneIr ? DRONE_IR_VIEW_RANGE_SQ : viewRangeSq(viewer);
            if (!viewerCanSeeBeam(viewer, shooter.getLocation(), muzzleApprox, tip, rangeSq)) {
                continue;
            }
            targets.add(viewer);
        }
        if (targets.isEmpty()) {
            lastViewerCount = 0;
            lastSegmentCount = path.segments().size();
            return;
        }
        lastViewerCount = targets.size();
        lastSegmentCount = path.segments().size();
        int tipFlags = path.tipUnderwater() ? TIP_UNDERWATER : 0;
        if (droneIr) {
            tipFlags |= TIP_DRONE_IR;
        } else if (infrared) {
            tipFlags |= TIP_GUN_IR;
        }
        if (suppressed) {
            tipFlags |= TIP_SUPPRESSED;
        }
        byte[] payload = encodeLaser(shooter.getUniqueId(), path, color, width, tipFlags);
        if (payload == null) {
            return;
        }
        for (Player viewer : targets) {
            viewer.sendPluginMessage(plugin, CompanionClients.CHANNEL_LASER, payload);
        }
    }

    /**
     * Visual-only: dazzle only when the laser is aimed into the front of the face (either eye).
     * Hits on the back of the head do not count.
     */
    public void applyEyeFlashes(Player shooter, Location muzzle, LaserOptics.BeamPath path, Color color) {
        applyEyeFlashes(shooter, muzzle, path, color, false);
    }

    public void applyEyeFlashes(Player shooter, Location muzzle, LaserOptics.BeamPath path, Color color, boolean infrared) {
        if (shooter == null || muzzle == null || path == null || path.tip() == null || color == null) {
            return;
        }
        CompanionClients companions = plugin.companions();
        if (companions == null) {
            return;
        }
        Location tip = path.tip();
        Vector origin = muzzle.toVector();
        Vector tipVec = tip.toVector();
        Vector ray = tipVec.clone().subtract(origin);
        double len = ray.length();
        if (len < 0.15) {
            return;
        }
        Vector dir = ray.clone().multiply(1.0 / len);

        for (Player victim : muzzle.getWorld().getPlayers()) {
            if (victim.equals(shooter) || !victim.isValid() || victim.isDead()) {
                continue;
            }
            // IR dazzle only registers through night vision tubes (incl. drone NVG optic).
            if (infrared && !canSeeInfrared(victim)) {
                continue;
            }
            Location eye = victim.getEyeLocation();
            Vector look = eye.getDirection().clone();
            if (look.lengthSquared() < 1.0E-6) {
                continue;
            }
            look.normalize();

            // Must be shining into their face, not the back of the head.
            if (dir.dot(look) > FACE_APPROACH_DOT) {
                continue;
            }

            Vector eyeVec = eye.toVector();
            Vector toEye = eyeVec.clone().subtract(origin);
            double t = toEye.dot(dir);
            // Tip often stops on the body before the eyes — allow a little past.
            if (t < 0.25 || t > len + 0.45) {
                continue;
            }
            double tClosest = Math.max(0, Math.min(t, len + 0.35));
            Vector closest = origin.clone().add(dir.clone().multiply(tClosest));
            Vector local = closest.clone().subtract(eyeVec);

            Vector right = look.clone().crossProduct(new Vector(0, 1, 0));
            if (right.lengthSquared() < 1.0E-6) {
                right = new Vector(1, 0, 0);
            } else {
                right.normalize();
            }
            Vector up = right.clone().crossProduct(look).normalize();

            double forward = local.dot(look);   // + = in front of eyes, - = into skull / behind
            double lateral = local.dot(right);  // spans both eyes
            double vertical = local.dot(up);

            if (forward < EYE_FORWARD_MIN || forward > EYE_FORWARD_MAX) {
                continue;
            }
            if (Math.abs(lateral) > EYE_LATERAL || Math.abs(vertical) > EYE_VERTICAL) {
                continue;
            }

            // Stronger when dead-center between the eyes / closer to the eye plane.
            double faceDist = Math.sqrt(
                    (lateral / EYE_LATERAL) * (lateral / EYE_LATERAL)
                            + (vertical / EYE_VERTICAL) * (vertical / EYE_VERTICAL)
                            + (forward / 0.16) * (forward / 0.16) * 0.35
            );
            float intensity = (float) Math.max(0.45, Math.min(1.0, 1.15 - faceDist * 0.55));
            long now = System.currentTimeMillis();
            Long last = lastFlashMs.get(victim.getUniqueId());
            if (last != null && now - last < FLASH_MIN_INTERVAL_MS) {
                continue;
            }
            lastFlashMs.put(victim.getUniqueId(), now);
            // Callers that pass a suppressed laser should already have cut width; also soften dazzle.
            byte[] flash = encodeFlash(color, intensity);
            if (flash != null) {
                victim.sendPluginMessage(plugin, CompanionClients.CHANNEL_FLASH, flash);
            }
        }
    }

    public void broadcastMuzzleFlash(Player shooter, Location at, Vector direction, Color color, float scale) {
        broadcastMuzzleFlash(shooter, at, direction, color, scale, false);
    }

    public void broadcastMuzzleFlash(Player shooter, Location at, Vector direction, Color color, float scale,
                                     boolean suppressed) {
        if (shooter == null || at == null || at.getWorld() == null) {
            return;
        }
        CompanionClients companions = plugin.companions();
        if (companions == null) {
            return;
        }
        Vector dir = direction == null || direction.lengthSquared() == 0
                ? at.getDirection()
                : direction.clone().normalize();
        float useScale = suppressed ? Math.max(0.08f, scale * 0.25f) : scale;
        byte[] payload = encodeMuzzle(shooter.getUniqueId(), at, dir, color, useScale, suppressed);
        if (payload == null) {
            return;
        }
        for (Player viewer : nearbyViewers(at)) {
            viewer.sendPluginMessage(plugin, CompanionClients.CHANNEL_FX, payload);
        }
    }

    public void broadcastTracer(Player shooter, Location from, Location to, Color color, float width) {
        if (shooter == null || from == null || to == null || from.getWorld() == null) {
            return;
        }
        if (from.distanceSquared(to) < 1.0E-4) {
            return;
        }
        CompanionClients companions = plugin.companions();
        if (companions == null) {
            return;
        }
        byte[] payload = encodeTracer(shooter.getUniqueId(), from, to, color, width);
        if (payload == null) {
            return;
        }
        for (Player viewer : nearbyViewers(to)) {
            viewer.sendPluginMessage(plugin, CompanionClients.CHANNEL_FX, payload);
        }
    }

    /**
     * FLIR / drone white-hot & black-hot heat bloom. Vanilla explode packets stop at ~view distance,
     * so MQ-9 pilots never saw ground LAW heat until this cue.
     */
    public void broadcastThermalBlast(Location at, float radius) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        CompanionClients companions = plugin.companions();
        if (companions == null) {
            return;
        }
        float r = Math.max(0.5f, Math.min(24f, radius));
        byte[] payload = encodeThermalBlast(at, r);
        if (payload == null) {
            return;
        }
        for (Player viewer : at.getWorld().getPlayers()) {
            double rangeSq = (plugin.bigDrone() != null && plugin.bigDrone().isPiloting(viewer))
                    ? THERMAL_BLAST_VIEW_RANGE_SQ
                    : THERMAL_BLAST_GROUND_RANGE_SQ;
            if (viewer.getLocation().distanceSquared(at) > rangeSq) {
                continue;
            }
            viewer.sendPluginMessage(plugin, CompanionClients.CHANNEL_FX, payload);
        }
    }

    /**
     * Rising crater smoke heat for MQ-9 white-hot / black-hot. Client ignores unless piloting thermal mono.
     */
    public void broadcastThermalSmoke(Location at, float height) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        CompanionClients companions = plugin.companions();
        if (companions == null || plugin.bigDrone() == null) {
            return;
        }
        float h = Math.max(4f, Math.min(56f, height));
        byte[] payload = encodeThermalSmoke(at, h);
        if (payload == null) {
            return;
        }
        for (Player viewer : at.getWorld().getPlayers()) {
            if (plugin.bigDrone() == null || !plugin.bigDrone().isPiloting(viewer)) {
                continue;
            }
            if (viewer.getLocation().distanceSquared(at) > THERMAL_SMOKE_VIEW_RANGE_SQ) {
                continue;
            }
            viewer.sendPluginMessage(plugin, CompanionClients.CHANNEL_FX, payload);
        }
    }

    private List<Player> nearbyViewers(Location origin) {
        List<Player> out = new ArrayList<>();
        for (Player viewer : origin.getWorld().getPlayers()) {
            double rangeSq = viewRangeSq(viewer);
            if (viewer.getLocation().distanceSquared(origin) > rangeSq) {
                continue;
            }
            out.add(viewer);
        }
        return out;
    }

    /** NVG helmet / drone NVG optic — gun IR pointers stay NOD-only. */
    private boolean canSeeInfrared(Player viewer) {
        return NvgGear.isWearingNvg(plugin, viewer);
    }

    /** Drone designator: pilot (any optic), NVG wearers, or FLIR wearers. */
    private boolean canSeeDroneInfrared(Player viewer, Player shooter) {
        if (viewer.equals(shooter)) {
            return true;
        }
        if (plugin.bigDrone() != null && plugin.bigDrone().isPiloting(viewer)) {
            return true;
        }
        return NvgGear.isWearingNvg(plugin, viewer) || ThermalGear.isWearingThermal(plugin, viewer);
    }

    private double viewRangeSq(Player viewer) {
        if (plugin.bigDrone() != null && plugin.bigDrone().isPiloting(viewer)) {
            return DRONE_VIEW_RANGE_SQ;
        }
        return VIEW_RANGE_SQ;
    }

    /**
     * Viewer receives the beam if within range of the tip, shooter, muzzle, or the
     * closest point along the muzzle→tip segment (so altitude drones still see ground lasers).
     */
    private boolean viewerCanSeeBeam(Player viewer, Location shooterLoc, Location muzzle, Location tip) {
        return viewerCanSeeBeam(viewer, shooterLoc, muzzle, tip, viewRangeSq(viewer));
    }

    private boolean viewerCanSeeBeam(Player viewer, Location shooterLoc, Location muzzle, Location tip, double rangeSq) {
        Location eye = viewer.getEyeLocation();
        if (eye.distanceSquared(tip) <= rangeSq
                || eye.distanceSquared(shooterLoc) <= rangeSq
                || eye.distanceSquared(muzzle) <= rangeSq) {
            return true;
        }
        return distSqPointToSegment(eye.toVector(), muzzle.toVector(), tip.toVector()) <= rangeSq;
    }

    private static double distSqPointToSegment(Vector p, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double ab2 = ab.lengthSquared();
        if (ab2 < 1.0e-8) {
            return p.distanceSquared(a);
        }
        double t = p.clone().subtract(a).dot(ab) / ab2;
        t = Math.max(0.0, Math.min(1.0, t));
        Vector closest = a.clone().add(ab.clone().multiply(t));
        return p.distanceSquared(closest);
    }

    public void clearBeam(Player shooter) {
        if (shooter == null) {
            return;
        }
        CompanionClients companions = plugin.companions();
        if (companions == null) {
            return;
        }
        byte[] payload = encodeClear(shooter.getUniqueId());
        for (Player viewer : shooter.getWorld().getPlayers()) {
            viewer.sendPluginMessage(plugin, CompanionClients.CHANNEL_CLEAR, payload);
        }
    }

    public List<Player> vanillaViewersNear(Location origin) {
        List<Player> out = new ArrayList<>();
        if (origin == null || origin.getWorld() == null) {
            return out;
        }
        CompanionClients companions = plugin.companions();
        for (Player viewer : origin.getWorld().getPlayers()) {
            if (viewer.getLocation().distanceSquared(origin) > VIEW_RANGE_SQ) {
                continue;
            }
            if (companions != null && companions.hasCompanion(viewer)) {
                continue;
            }
            out.add(viewer);
        }
        return out;
    }

    private static byte[] encodeLaser(UUID shooter, LaserOptics.BeamPath path, Color color, float baseWidth) {
        return encodeLaser(shooter, path, color, baseWidth, path.tipUnderwater() ? TIP_UNDERWATER : 0);
    }

    private static byte[] encodeLaser(UUID shooter, LaserOptics.BeamPath path, Color color, float baseWidth, int tipFlags) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 + path.segments().size() * 36);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(CompanionClients.PROTOCOL);
            out.writeLong(shooter.getMostSignificantBits());
            out.writeLong(shooter.getLeastSignificantBits());
            int rgb = ((color.getRed() & 0xFF) << 16) | ((color.getGreen() & 0xFF) << 8) | (color.getBlue() & 0xFF);
            out.writeInt(rgb);
            out.writeFloat(baseWidth);
            Location tip = path.tip();
            out.writeFloat((float) tip.getX());
            out.writeFloat((float) tip.getY());
            out.writeFloat((float) tip.getZ());
            out.writeByte(tipFlags & 0xFF);
            List<LaserOptics.Segment> segs = path.segments();
            int total = segs.size();
            int stride = total <= MAX_SEGMENTS ? 1 : (int) Math.ceil(total / (double) MAX_SEGMENTS);
            int count = 0;
            for (int i = 0; i < total; i += stride) {
                count++;
            }
            out.writeShort(count);
            for (int i = 0; i < total; i += stride) {
                LaserOptics.Segment seg = segs.get(i);
                out.writeFloat((float) seg.x0());
                out.writeFloat((float) seg.y0());
                out.writeFloat((float) seg.z0());
                out.writeFloat((float) seg.x1());
                out.writeFloat((float) seg.y1());
                out.writeFloat((float) seg.z1());
                out.writeFloat(seg.intensity());
                out.writeFloat(seg.widthScale());
                out.writeByte(seg.flags());
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] encodeMuzzle(UUID shooter, Location at, Vector dir, Color color, float scale,
                                       boolean suppressed) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(42);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(CompanionClients.PROTOCOL);
            out.writeByte(1); // muzzle
            out.writeLong(shooter.getMostSignificantBits());
            out.writeLong(shooter.getLeastSignificantBits());
            int rgb = ((color.getRed() & 0xFF) << 16) | ((color.getGreen() & 0xFF) << 8) | (color.getBlue() & 0xFF);
            out.writeInt(rgb);
            out.writeFloat((float) at.getX());
            out.writeFloat((float) at.getY());
            out.writeFloat((float) at.getZ());
            out.writeFloat((float) dir.getX());
            out.writeFloat((float) dir.getY());
            out.writeFloat((float) dir.getZ());
            out.writeFloat(Math.max(0.08f, scale));
            out.writeByte(suppressed ? FX_FLAG_SUPPRESSED : 0);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] encodeTracer(UUID shooter, Location from, Location to, Color color, float width) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(48);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(CompanionClients.PROTOCOL);
            out.writeByte(2); // tracer
            out.writeLong(shooter.getMostSignificantBits());
            out.writeLong(shooter.getLeastSignificantBits());
            int rgb = ((color.getRed() & 0xFF) << 16) | ((color.getGreen() & 0xFF) << 8) | (color.getBlue() & 0xFF);
            out.writeInt(rgb);
            out.writeFloat((float) from.getX());
            out.writeFloat((float) from.getY());
            out.writeFloat((float) from.getZ());
            out.writeFloat((float) to.getX());
            out.writeFloat((float) to.getY());
            out.writeFloat((float) to.getZ());
            out.writeFloat(Math.max(0.01f, width));
            out.writeByte(4); // client TTL ticks
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] encodeThermalBlast(Location at, float radius) {
        return encodeThermalFx(FX_THERMAL_BLAST, at, radius, 8);
    }

    private static byte[] encodeThermalSmoke(Location at, float height) {
        return encodeThermalFx(FX_THERMAL_SMOKE, at, height, 12);
    }

    private static byte[] encodeThermalFx(byte fxType, Location at, float scale, int ttl) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(48);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(CompanionClients.PROTOCOL);
            out.writeByte(fxType);
            out.writeLong(0L);
            out.writeLong(0L);
            out.writeInt(0);
            out.writeFloat((float) at.getX());
            out.writeFloat((float) at.getY());
            out.writeFloat((float) at.getZ());
            out.writeFloat(0f);
            out.writeFloat(0f);
            out.writeFloat(0f);
            out.writeFloat(scale);
            out.writeByte(Math.max(1, Math.min(255, ttl)));
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] encodeClear(UUID shooter) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(17);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(CompanionClients.PROTOCOL);
            out.writeLong(shooter.getMostSignificantBits());
            out.writeLong(shooter.getLeastSignificantBits());
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /**
     * Multi-channel blast shock for companion clients.
     * Format byte 2: pressure, flash, tinnitus, muffle, knockback, dirX, dirZ, 4× durations.
     */
    public void sendBlast(Player victim, float pressure, float flash, float tinnitus, float muffle,
                          float knockback, float dirX, float dirZ,
                          int shakeTicks, int flashTicks, int tinnitusTicks, int muffleTicks) {
        if (victim == null || !victim.isOnline()) {
            return;
        }
        byte[] payload = encodeBlast(pressure, flash, tinnitus, muffle, knockback, dirX, dirZ,
                shakeTicks, flashTicks, tinnitusTicks, muffleTicks);
        if (payload == null) {
            return;
        }
        victim.sendPluginMessage(plugin, CompanionClients.CHANNEL_BLAST, payload);
    }

    private static final byte BLAST_FMT = 2;

    private static byte[] encodeBlast(float pressure, float flash, float tinnitus, float muffle,
                                      float knockback, float dirX, float dirZ,
                                      int shakeTicks, int flashTicks, int tinnitusTicks, int muffleTicks) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(40);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(CompanionClients.PROTOCOL);
            out.writeByte(BLAST_FMT);
            out.writeFloat(clamp01(pressure));
            out.writeFloat(clamp01(flash));
            out.writeFloat(clamp01(tinnitus));
            out.writeFloat(clamp01(muffle));
            out.writeFloat(clamp01(knockback));
            out.writeFloat(dirX);
            out.writeFloat(dirZ);
            out.writeShort(Math.max(0, Math.min(32767, shakeTicks)));
            out.writeShort(Math.max(0, Math.min(32767, flashTicks)));
            out.writeShort(Math.max(0, Math.min(32767, tinnitusTicks)));
            out.writeShort(Math.max(0, Math.min(32767, muffleTicks)));
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /**
     * Blood-loss HUD for companion clients: vignette strength + optional heartbeat pulse.
     * {@code severity} 0 clears the overlay.
     */
    public void sendBloodFx(Player victim, float severity, boolean pulse) {
        if (victim == null || !victim.isOnline()) {
            return;
        }
        byte[] payload = encodeBlood(severity, pulse);
        if (payload == null) {
            return;
        }
        victim.sendPluginMessage(plugin, CompanionClients.CHANNEL_BLOOD, payload);
    }

    private static byte[] encodeBlood(float severity, boolean pulse) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(10);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(CompanionClients.PROTOCOL);
            out.writeFloat(Math.max(0f, Math.min(1f, severity)));
            out.writeByte(pulse ? 1 : 0);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Fullscreen whiteout (flashbang) for companion clients — pulses so the client overlay holds.
     */
    public void sendWhiteout(Player victim, int ticks) {
        if (victim == null || !victim.isOnline() || ticks <= 0) {
            return;
        }
        byte[] flash = encodeFlash(Color.WHITE, 1.0f);
        if (flash == null) {
            return;
        }
        final int hold = ticks;
        final int[] elapsed = {0};
        org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!victim.isOnline() || elapsed[0] >= hold) {
                task.cancel();
                return;
            }
            victim.sendPluginMessage(plugin, CompanionClients.CHANNEL_FLASH, flash);
            elapsed[0]++;
        }, 0L, 1L);
    }

    private static byte[] encodeFlash(Color color, float intensity) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(12);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(CompanionClients.PROTOCOL);
            int rgb = ((color.getRed() & 0xFF) << 16) | ((color.getGreen() & 0xFF) << 8) | (color.getBlue() & 0xFF);
            out.writeInt(rgb);
            out.writeFloat(Math.max(0f, Math.min(1f, intensity)));
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
