package com.local.warz.util;

import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Optical laser path builder + optional particle fallback for clients without the Fabric companion.
 * Supports a few bounces off reflective surfaces (metal / tinted glass / etc.).
 */
public final class LaserOptics {
    public static final byte FLAG_UNDERWATER = 1;
    public static final byte FLAG_REFLECTION = 2;
    public static final byte FLAG_TIP = 4;

    /** Keep bounce count low — stops water-edge flicker and packet spam. */
    private static final int MAX_BOUNCES = 3;
    private static final double MIN_BOUNCE_INTENSITY = 0.12;

    private LaserOptics() {
    }

    public record Segment(
            double x0, double y0, double z0,
            double x1, double y1, double z1,
            float intensity,
            float widthScale,
            byte flags
    ) {
        public boolean underwater() {
            return (flags & FLAG_UNDERWATER) != 0;
        }

        public boolean reflection() {
            return (flags & FLAG_REFLECTION) != 0;
        }
    }

    public record BeamPath(List<Segment> segments, Location tip, boolean tipUnderwater) {
        public BeamPath {
            segments = List.copyOf(segments == null ? List.of() : segments);
        }

        public static BeamPath empty(Location tip) {
            return new BeamPath(List.of(), tip, false);
        }
    }

    public static BeamPath traceFromTo(Location start, Location end, float size, double density) {
        return traceFromTo(start, end, size, density, false);
    }

    public static BeamPath traceFromTo(Location start, Location end, float size, double density,
                                       boolean infrared) {
        if (start == null || end == null || start.getWorld() == null) {
            return BeamPath.empty(start);
        }
        Vector delta = end.toVector().subtract(start.toVector());
        double range = Math.max(0.5, delta.length() + 0.5);
        if (delta.lengthSquared() < 1.0E-6) {
            return BeamPath.empty(start);
        }
        return traceOptical(start, delta.normalize(), range, size, density, infrared);
    }

    /**
     * Straight ray with underwater tagging + absorption, plus limited specular bounces
     * off reflective solids (iron/gold/tinted glass/etc.). Clear glass still transmits.
     * Plants/leaves: IR only.
     */
    public static BeamPath traceOptical(Location start, Vector initialDir, double maxRange,
                                        float size, double density) {
        return traceOptical(start, initialDir, maxRange, size, density, false);
    }

    public static BeamPath traceOptical(Location start, Vector initialDir, double maxRange,
                                        float size, double density, boolean infrared) {
        List<Segment> out = new ArrayList<>();
        if (start == null || start.getWorld() == null || initialDir == null || maxRange <= 0) {
            return BeamPath.empty(start);
        }
        Vector dir = initialDir.clone();
        if (dir.lengthSquared() == 0) {
            return BeamPath.empty(start);
        }
        dir.normalize();

        Location pos = start.clone();
        boolean inWater = LaserBeams.isUnderwater(pos.getBlock());
        int mediumVotes = 0;
        final int mediumFlipThreshold = 2;
        double travelled = 0;
        double intensity = 1.0;
        float densityClamped = (float) Math.max(0.1, density);
        double step = Math.max(0.08, Math.min(0.2, (Math.max(0.08f, size) * 0.55) / densityClamped));
        int guard = 0;
        int bounces = 0;
        Location tip = start.clone();
        Location segStart = start.clone();
        float segIntensity = 1f;
        byte segFlags = inWater ? FLAG_UNDERWATER : 0;

        while (travelled < maxRange && intensity > 0.04 && guard++ < 2500) {
            Location next = pos.clone().add(dir.clone().multiply(step));
            Block hitBlock = next.getBlock();
            if (!LaserBeams.isLaserPassable(hitBlock, infrared)) {
                tip = pos.clone();
                flushSegment(out, segStart, tip, segIntensity, 1f, segFlags);

                if (bounces < MAX_BOUNCES && intensity > MIN_BOUNCE_INTENSITY
                        && isReflective(hitBlock.getType())) {
                    BlockFace face = hitFace(pos, dir, step + 0.35);
                    Vector normal = face != null ? face.getDirection() : guessNormal(dir);
                    if (normal.lengthSquared() > 1.0E-6) {
                        Vector reflected = reflect(dir, normal);
                        // Nudge out of the surface so the next step isn't immediately solid
                        Location bounceOrigin = tip.clone().add(normal.clone().multiply(0.05));
                        if (!LaserBeams.isLaserPassable(bounceOrigin.getBlock(), infrared)) {
                            bounceOrigin = tip.clone().subtract(dir.clone().multiply(0.08));
                        }
                        intensity *= bounceLoss(hitBlock.getType());
                        bounces++;
                        dir = reflected;
                        pos = bounceOrigin;
                        tip = bounceOrigin;
                        segStart = bounceOrigin.clone();
                        segIntensity = (float) intensity;
                        segFlags = (byte) ((inWater ? FLAG_UNDERWATER : 0) | FLAG_REFLECTION);
                        continue;
                    }
                }
                break;
            }

            boolean nextWater = LaserBeams.isUnderwater(next.getBlock());
            if (nextWater == inWater) {
                mediumVotes = 0;
            } else {
                mediumVotes++;
                if (mediumVotes >= mediumFlipThreshold) {
                    flushSegment(out, segStart, pos, (float) intensity, 1f, segFlags);
                    inWater = nextWater;
                    mediumVotes = 0;
                    intensity *= 0.9;
                    segStart = pos.clone();
                    segIntensity = (float) intensity;
                    segFlags = (byte) ((inWater ? FLAG_UNDERWATER : 0)
                            | (bounces > 0 ? FLAG_REFLECTION : 0));
                }
            }

            if (inWater) {
                intensity *= Math.pow(0.97, step * 4.0);
            }

            double flushAt = inWater ? 2.25 : 16.0;
            if (segStart.distanceSquared(next) > flushAt) {
                flushSegment(out, segStart, next, segIntensity, 1f, segFlags);
                segStart = next.clone();
                segIntensity = (float) intensity;
                segFlags = (byte) ((inWater ? FLAG_UNDERWATER : 0)
                        | (bounces > 0 ? FLAG_REFLECTION : 0));
            }

            pos = next;
            tip = next;
            travelled += step;
        }

        flushSegment(out, segStart, tip, segIntensity, 1f, segFlags);
        if (!out.isEmpty()) {
            Segment last = out.get(out.size() - 1);
            out.set(out.size() - 1, new Segment(
                    last.x0(), last.y0(), last.z0(),
                    last.x1(), last.y1(), last.z1(),
                    last.intensity(), last.widthScale(),
                    (byte) (last.flags() | FLAG_TIP)
            ));
        }
        return new BeamPath(out, tip, LaserBeams.isUnderwater(tip.getBlock()));
    }

    /** Materials that throw a specular bounce for laser sights. */
    public static boolean isReflective(Material type) {
        if (type == null) {
            return false;
        }
        String name = type.name();
        if (type == Material.IRON_BLOCK || type == Material.GOLD_BLOCK || type == Material.COPPER_BLOCK
                || type == Material.EXPOSED_COPPER || type == Material.WEATHERED_COPPER
                || type == Material.OXIDIZED_COPPER || type == Material.WAXED_COPPER_BLOCK
                || type == Material.NETHERITE_BLOCK || type == Material.AMETHYST_BLOCK
                || type == Material.TINTED_GLASS || type == Material.LIGHT_WEIGHTED_PRESSURE_PLATE
                || type == Material.HEAVY_WEIGHTED_PRESSURE_PLATE || type == Material.IRON_TRAPDOOR
                || type == Material.IRON_DOOR
                || type == Material.RAW_IRON_BLOCK || type == Material.RAW_GOLD_BLOCK
                || type == Material.RAW_COPPER_BLOCK) {
            return true;
        }
        if (name.contains("COPPER") || name.contains("IRON") || name.contains("GOLD")
                || name.contains("NETHERITE") || name.startsWith("WAXED_")) {
            return true;
        }
        // Polished / glazed surfaces catch a dim bounce
        return name.startsWith("POLISHED_") || name.endsWith("_GLAZED_TERRACOTTA")
                || name.contains("QUARTZ") || name.equals("OBSIDIAN") || name.equals("CRYING_OBSIDIAN");
    }

    private static double bounceLoss(Material type) {
        if (type == Material.TINTED_GLASS || type == Material.GOLD_BLOCK
                || type == Material.IRON_BLOCK || type == Material.NETHERITE_BLOCK) {
            return 0.72;
        }
        if (type.name().contains("COPPER") || type.name().contains("AMETHYST")) {
            return 0.55;
        }
        return 0.4;
    }

    private static BlockFace hitFace(Location from, Vector dir, double distance) {
        if (from.getWorld() == null) {
            return null;
        }
        RayTraceResult hit = from.getWorld().rayTraceBlocks(
                from, dir, distance, FluidCollisionMode.NEVER, true);
        return hit != null ? hit.getHitBlockFace() : null;
    }

    private static Vector guessNormal(Vector dir) {
        // Prefer upward-ish bounce if we somehow miss a face
        Vector n = new Vector(0, 1, 0);
        if (Math.abs(dir.getY()) > 0.85) {
            n = new Vector(-Math.signum(dir.getX()), 0, -Math.signum(dir.getZ()));
            if (n.lengthSquared() < 1.0E-6) {
                n = new Vector(1, 0, 0);
            }
        }
        return n.normalize();
    }

    /** Reflect incident direction off a surface normal (for lasers and bullet ricochets). */
    public static Vector reflectDirection(Vector incident, Vector normal) {
        Vector i = incident.clone().normalize();
        Vector n = normal.clone().normalize();
        // Ensure normal faces the incoming ray
        if (i.dot(n) > 0) {
            n.multiply(-1);
        }
        return i.subtract(n.multiply(2.0 * i.dot(n))).normalize();
    }

    private static Vector reflect(Vector incident, Vector normal) {
        return reflectDirection(incident, normal);
    }

    public static void spawnParticles(BeamPath path, Color color, float size, double density,
                                      Iterable<Player> viewers) {
        if (path == null || path.segments().isEmpty() || color == null) {
            return;
        }
        List<Player> list = new ArrayList<>();
        for (Player p : viewers) {
            if (p != null && p.isOnline()) {
                list.add(p);
            }
        }
        if (list.isEmpty()) {
            return;
        }
        float particleSize = Math.max(0.08f, size);
        double dens = Math.max(0.1, density);
        double step = Math.max(0.04, Math.min(0.14, (particleSize * 0.4) / dens));

        for (Segment seg : path.segments()) {
            Vector a = new Vector(seg.x0(), seg.y0(), seg.z0());
            Vector b = new Vector(seg.x1(), seg.y1(), seg.z1());
            Vector delta = b.clone().subtract(a);
            double len = delta.length();
            if (len < 1.0E-4) {
                continue;
            }
            Vector dir = delta.normalize();
            boolean water = seg.underwater();
            float intensity = Math.max(0.05f, seg.intensity());
            float width = particleSize * Math.max(0.3f, seg.widthScale());
            // Reflections: slightly denser so bounced segments stay readable
            double useStep = seg.reflection() ? step * 0.75 : step;
            for (double t = 0; t <= len; t += useStep) {
                Vector p = a.clone().add(dir.clone().multiply(t));
                Location loc = new Location(path.tip().getWorld(), p.getX(), p.getY(), p.getZ());
                spawnDust(list, loc, color, width, dens, intensity, water);
            }
        }
        Location tip = path.tip();
        if (tip != null) {
            spawnTip(list, tip, color, particleSize, 1f, path.tipUnderwater());
        }
    }

    /**
     * World tip spark so Iris/Complementary SSR can catch the burn mark even when
     * the companion draws the beam with a post-pass pipeline.
     */
    public static void spawnWorldTipSpark(Location tip, Color color, float size) {
        if (tip == null || tip.getWorld() == null || color == null) {
            return;
        }
        float s = Math.max(0.2f, Math.min(0.9f, size * 1.8f));
        tip.getWorld().spawnParticle(Particle.DUST, tip, 3, 0.02, 0.02, 0.02, 0.0,
                new Particle.DustOptions(color, s), true);
        tip.getWorld().spawnParticle(Particle.DUST, tip, 2, 0.04, 0.04, 0.04, 0.0,
                new Particle.DustOptions(scaleColor(color, 0.55), s * 1.35f), true);
    }

    private static void spawnDust(List<Player> viewers, Location point, Color color, float size,
                                  double dens, double intensity, boolean underwater) {
        float alphaScale = (float) Math.max(0.08, Math.min(1.0, intensity));
        Color base = scaleColor(color, alphaScale);
        if (underwater) {
            Color tinted = underwaterTint(base);
            float scatterSize = Math.min(1.4f, size * (1.55f + (1f - alphaScale) * 0.6f));
            Particle.DustOptions core = new Particle.DustOptions(tinted, Math.min(1.1f, size * 1.25f));
            Particle.DustOptions haze = new Particle.DustOptions(scaleColor(tinted, 0.65), scatterSize);
            for (Player player : viewers) {
                player.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, core);
                player.spawnParticle(Particle.DUST, point, dens >= 1.5 ? 2 : 1, 0.03, 0.03, 0.03, 0, haze);
            }
        } else {
            Particle.DustOptions dust = new Particle.DustOptions(base, size);
            for (Player player : viewers) {
                player.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, dust);
            }
        }
    }

    private static void spawnTip(List<Player> viewers, Location tip, Color color, float size,
                                 double intensity, boolean underwater) {
        Color base = scaleColor(color, Math.max(0.15, intensity));
        if (underwater) {
            Color tinted = underwaterTint(base);
            Particle.DustOptions a = new Particle.DustOptions(tinted, Math.min(1.0f, size * 1.8f));
            Particle.DustOptions b = new Particle.DustOptions(scaleColor(tinted, 0.5), Math.min(1.3f, size * 2.4f));
            for (Player player : viewers) {
                player.spawnParticle(Particle.DUST, tip, 2, 0.03, 0.03, 0.03, 0, a);
                player.spawnParticle(Particle.DUST, tip, 1, 0.06, 0.06, 0.06, 0, b);
            }
        } else {
            Particle.DustOptions dust = new Particle.DustOptions(base, Math.min(0.7f, size * 1.6f));
            for (Player player : viewers) {
                player.spawnParticle(Particle.DUST, tip, 1, 0, 0, 0, 0, dust);
            }
        }
    }

    private static void flushSegment(List<Segment> out, Location a, Location b,
                                     float intensity, float widthScale, byte flags) {
        if (a == null || b == null || a.distanceSquared(b) < 1.0E-6) {
            return;
        }
        out.add(new Segment(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ(),
                Math.max(0.05f, intensity), widthScale, flags));
    }

    private static Color underwaterTint(Color color) {
        int r = (int) Math.round(color.getRed() * 0.42 + 25 * 0.58);
        int g = (int) Math.round(color.getGreen() * 0.42 + 170 * 0.58);
        int b = (int) Math.round(color.getBlue() * 0.42 + 215 * 0.58);
        return Color.fromRGB(clampByte((int) (r * 0.85)), clampByte((int) (g * 0.9)), clampByte(b));
    }

    private static Color scaleColor(Color color, double factor) {
        double f = Math.max(0, Math.min(1, factor));
        int r = (int) Math.round(color.getRed() * f);
        int g = (int) Math.round(color.getGreen() * f);
        int b = (int) Math.round(color.getBlue() * f);
        return Color.fromRGB(clampByte(Math.max(8, r)), clampByte(Math.max(8, g)), clampByte(Math.max(8, b)));
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
