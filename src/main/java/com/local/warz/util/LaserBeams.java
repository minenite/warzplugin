package com.local.warz.util;

import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Light;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

public final class LaserBeams {
    public static final double N_AIR = 1.0003;
    public static final double N_WATER = 1.333;

    private LaserBeams() {
    }

    public static Color parseColor(String raw, Color fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "red" -> Color.RED;
            case "orange" -> Color.ORANGE;
            case "yellow" -> Color.YELLOW;
            case "lime", "green" -> Color.LIME;
            case "aqua", "cyan" -> Color.AQUA;
            case "blue" -> Color.BLUE;
            case "purple", "fuchsia", "magenta" -> Color.FUCHSIA;
            case "pink" -> Color.fromRGB(255, 105, 180);
            case "white" -> Color.WHITE;
            case "black" -> Color.BLACK;
            case "gray", "grey" -> Color.GRAY;
            default -> parseRgb(raw, fallback);
        };
    }

    private static Color parseRgb(String raw, Color fallback) {
        String value = raw.trim();
        try {
            if (value.startsWith("#")) {
                String hex = value.substring(1);
                if (hex.length() == 3) {
                    hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1)
                            + hex.charAt(2) + hex.charAt(2);
                }
                int rgb = Integer.parseInt(hex, 16);
                return Color.fromRGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            }
            if (value.contains(",")) {
                String[] parts = value.split(",");
                if (parts.length >= 3) {
                    return Color.fromRGB(
                            clampByte(Integer.parseInt(parts[0].trim())),
                            clampByte(Integer.parseInt(parts[1].trim())),
                            clampByte(Integer.parseInt(parts[2].trim()))
                    );
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return fallback;
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /**
     * Leaves / plants / crops / vines — visible lasers stop here; IR lasers and gun projectiles pass.
     */
    public static boolean isFoliage(Material type) {
        if (type == null || type.isAir()) {
            return false;
        }
        if (Tag.LEAVES.isTagged(type) || Tag.REPLACEABLE.isTagged(type)
                || Tag.FLOWERS.isTagged(type) || Tag.SAPLINGS.isTagged(type)
                || Tag.CROPS.isTagged(type)) {
            return true;
        }
        String name = type.name();
        return name.contains("VINE")
                || name.equals("SUGAR_CANE") || name.equals("BAMBOO") || name.equals("BAMBOO_SAPLING")
                || name.equals("CACTUS") || name.equals("SWEET_BERRY_BUSH") || name.equals("COCOA")
                || name.equals("KELP") || name.equals("KELP_PLANT")
                || name.equals("SEAGRASS") || name.equals("TALL_SEAGRASS") || name.equals("LILY_PAD")
                || name.equals("BIG_DRIPLEAF") || name.equals("BIG_DRIPLEAF_STEM")
                || name.equals("SMALL_DRIPLEAF") || name.equals("SPORE_BLOSSOM")
                || name.equals("GLOW_LICHEN") || name.equals("HANGING_ROOTS")
                || name.equals("MOSS_CARPET") || name.equals("PALE_HANGING_MOSS")
                || name.equals("NETHER_SPROUTS") || name.equals("CRIMSON_ROOTS")
                || name.equals("WARPED_ROOTS") || name.equals("NETHER_WART")
                || name.equals("CHORUS_PLANT") || name.equals("CHORUS_FLOWER")
                || name.equals("DEAD_BUSH") || name.equals("FERN") || name.equals("LARGE_FERN")
                || name.equals("SHORT_GRASS") || name.equals("TALL_GRASS") || name.equals("BUSH")
                || name.equals("FIREFLY_BUSH") || name.equals("WILDFLOWERS") || name.equals("LEAF_LITTER");
    }

    public static boolean isFoliage(Block block) {
        return block != null && isFoliage(block.getType());
    }

    /** Visible laser default — plants block the beam. */
    public static boolean isLaserPassable(Block block) {
        return isLaserPassable(block, false);
    }

    /**
     * Glass, panes, iron bars, water, air, light — laser sight passes through.
     * Plants/leaves: only when {@code infrared} is true.
     */
    public static boolean isLaserPassable(Block block, boolean infrared) {
        if (block == null) {
            return true;
        }
        Material type = block.getType();
        if (type.isAir() || type == Material.LIGHT || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
            return true;
        }
        if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
            return true;
        }
        if (isFoliage(type)) {
            return infrared;
        }
        if (type == Material.IRON_BARS) {
            return true;
        }
        // Tinted glass is a mirror for laser sights (not passable — see LaserOptics bounce).
        if (type == Material.TINTED_GLASS) {
            return false;
        }
        String name = type.name();
        if (name.equals("GLASS")) {
            return true;
        }
        return name.endsWith("_GLASS") || name.equals("GLASS_PANE") || name.endsWith("_GLASS_PANE");
    }

    /**
     * World ray that skips foliage (leaves/plants) so bullets / lasers can punch through brush.
     */
    public static RayTraceResult rayTraceIgnoringFoliage(
            Location start, Vector direction, double maxDistance,
            double raySize, Predicate<Entity> entityFilter) {
        return rayTraceIgnoring(start, direction, maxDistance, raySize, entityFilter, LaserBeams::isFoliage);
    }

    /**
     * World ray that skips blocks matching {@code skipBlock} (e.g. foliage + just-pierced glass).
     */
    public static RayTraceResult rayTraceIgnoring(
            Location start, Vector direction, double maxDistance,
            double raySize, Predicate<Entity> entityFilter, Predicate<Block> skipBlock) {
        if (start == null || start.getWorld() == null || direction == null || maxDistance <= 0) {
            return null;
        }
        Vector dir = direction.clone();
        if (dir.lengthSquared() < 1.0E-8) {
            return null;
        }
        dir.normalize();
        Location cursor = start.clone();
        double remaining = maxDistance;
        for (int hop = 0; hop < 64 && remaining > 0.05; hop++) {
            RayTraceResult hit = start.getWorld().rayTrace(
                    cursor, dir, remaining, FluidCollisionMode.NEVER, true, raySize, entityFilter);
            if (hit == null) {
                return null;
            }
            if (hit.getHitEntity() != null) {
                return hit;
            }
            Block block = hit.getHitBlock();
            if (block == null || skipBlock == null || !skipBlock.test(block)) {
                return hit;
            }
            // Step past this skipped cell and keep going
            Vector hitPos = hit.getHitPosition();
            cursor = hitPos.toLocation(start.getWorld()).add(dir.clone().multiply(0.35));
            remaining = maxDistance - cursor.toVector().distance(start.toVector());
        }
        return null;
    }

    public static boolean isUnderwater(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
            return true;
        }
        return block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }

    public static Location muzzleOrigin(Location eye, double offsetRight, double offsetUp, double offsetForward) {
        Vector forward = eye.getDirection().clone();
        if (forward.lengthSquared() == 0) {
            forward = new Vector(0, 0, 1);
        }
        forward.normalize();
        Vector right = forward.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6) {
            right = new Vector(1, 0, 0);
        } else {
            right.normalize();
        }
        Vector up = right.clone().crossProduct(forward).normalize();
        return eye.clone().add(
                right.multiply(offsetRight)
                        .add(up.multiply(offsetUp))
                        .add(forward.multiply(offsetForward))
        );
    }

    /**
     * Crosshair aim point. Passes through glass / panes / iron bars / water.
     * Stops on solid blocks or living entities.
     */
    public static Location aimPoint(Player viewer, Location eye, double range, boolean includeEntities) {
        return aimPoint(viewer, eye, range, includeEntities, false);
    }

    public static Location aimPoint(Player viewer, Location eye, double range, boolean includeEntities,
                                    boolean infrared) {
        Vector dir = eye.getDirection().clone();
        if (dir.lengthSquared() == 0) {
            dir = new Vector(0, 0, 1);
        }
        dir.normalize();
        double step = 0.2;
        Location lastPassable = eye.clone();
        for (double travelled = step; travelled <= range; travelled += step) {
            Location point = eye.clone().add(dir.clone().multiply(travelled));
            if (!isLaserPassable(point.getBlock(), infrared)) {
                return lastPassable;
            }
            if (includeEntities) {
                for (Entity entity : point.getWorld().getNearbyEntities(point, 0.2, 0.2, 0.2)) {
                    if (entity instanceof LivingEntity living
                            && (viewer == null || !living.equals(viewer))
                            && living.isValid()
                            && !living.isDead()) {
                        return point;
                    }
                }
            }
            lastPassable = point;
        }
        return eye.clone().add(dir.multiply(range));
    }

    /**
     * Draws an optical laser path: refraction / reflection / scattering / absorption / TIR.
     * @return final beam tip location
     */
    public static Location drawOptical(Location start, Vector initialDir, double maxRange,
                                       Color color, float size, double density) {
        return drawOptical(start, initialDir, maxRange, color, size, density, false);
    }

    public static Location drawOptical(Location start, Vector initialDir, double maxRange,
                                       Color color, float size, double density, boolean infrared) {
        if (start == null || start.getWorld() == null || initialDir == null || maxRange <= 0) {
            return start;
        }
        Vector dir = initialDir.clone();
        if (dir.lengthSquared() == 0) {
            return start;
        }
        dir.normalize();

        World world = start.getWorld();
        Location pos = start.clone();
        boolean inWater = isUnderwater(pos.getBlock());
        double travelled = 0;
        double intensity = 1.0;
        float particleSize = Math.max(0.08f, size);
        double dens = Math.max(0.1, density);
        double step = Math.max(0.03, Math.min(0.14, (particleSize * 0.4) / dens));
        int guard = 0;
        Location tip = start.clone();

        while (travelled < maxRange && intensity > 0.04 && guard++ < 2500) {
            Location next = pos.clone().add(dir.clone().multiply(step));
            if (!isLaserPassable(next.getBlock(), infrared)) {
                tip = pos.clone();
                spawnTip(world, tip, color, particleSize, intensity, inWater);
                break;
            }

            boolean nextWater = isUnderwater(next.getBlock());
            if (inWater != nextWater) {
                // Flat water surface normal (up). Good enough for lakes/oceans.
                Vector normal = new Vector(0, 1, 0);
                if (!inWater) {
                    // Air -> water: partial reflection + refraction (Snell).
                    Vector reflected = reflect(dir, normal);
                    drawShortRay(world, pos, reflected, Math.min(4.0, maxRange - travelled),
                            color, particleSize * 0.7f, intensity * 0.22, dens, false);
                    Vector refracted = refract(dir, normal, N_AIR, N_WATER);
                    if (refracted != null) {
                        dir = refracted;
                    }
                    intensity *= 0.82; // energy lost to reflection
                    inWater = true;
                } else {
                    // Water -> air: refract, or total internal reflection.
                    Vector outward = new Vector(0, 1, 0);
                    Vector refracted = refract(dir, outward, N_WATER, N_AIR);
                    if (refracted == null) {
                        // TIR — bounce back into the water.
                        dir = reflect(dir, new Vector(0, -1, 0));
                        intensity *= 0.92;
                    } else {
                        // Fresnel-ish: weak internal reflection spark + exit beam
                        Vector internal = reflect(dir, new Vector(0, -1, 0));
                        drawShortRay(world, pos, internal, Math.min(2.0, maxRange - travelled),
                                color, particleSize * 0.55f, intensity * 0.12, dens, true);
                        dir = refracted;
                        intensity *= 0.88;
                        inWater = false;
                    }
                }
                next = pos.clone().add(dir.clone().multiply(step));
                if (!isLaserPassable(next.getBlock(), infrared)) {
                    tip = pos.clone();
                    break;
                }
                nextWater = isUnderwater(next.getBlock());
                inWater = nextWater;
            }

            spawnBeamParticle(world, next, color, particleSize, dens, intensity, inWater);
            if (inWater) {
                // Absorption — water eats intensity with distance.
                intensity *= Math.pow(0.965, step * 4.0);
            }

            pos = next;
            tip = next;
            travelled += step;
        }
        spawnTip(world, tip, color, particleSize, intensity, inWater);
        return tip;
    }

    /** Convenience: optical beam from muzzle toward an aim point. */
    public static Location drawFromTo(Location start, Location end, Color color, float size, double density) {
        return drawFromTo(start, end, color, size, density, false);
    }

    public static Location drawFromTo(Location start, Location end, Color color, float size, double density,
                                      boolean infrared) {
        if (start == null || end == null || start.getWorld() == null) {
            return start;
        }
        Vector delta = end.toVector().subtract(start.toVector());
        double range = Math.max(0.5, delta.length() + 0.5);
        if (delta.lengthSquared() < 1.0E-6) {
            return start;
        }
        return drawOptical(start, delta.normalize(), range, color, size, density, infrared);
    }

    private static void drawShortRay(World world, Location origin, Vector direction, double length,
                                     Color color, float size, double intensity, double dens, boolean underwater) {
        if (direction == null || direction.lengthSquared() == 0 || intensity < 0.03 || length <= 0) {
            return;
        }
        Vector dir = direction.clone().normalize();
        double step = Math.max(0.05, 0.12 / Math.max(0.5, dens));
        Location pos = origin.clone();
        double travelled = 0;
        double power = intensity;
        int guard = 0;
        while (travelled < length && power > 0.03 && guard++ < 120) {
            pos.add(dir.clone().multiply(step));
            if (!isLaserPassable(pos.getBlock())) {
                break;
            }
            boolean water = isUnderwater(pos.getBlock());
            spawnBeamParticle(world, pos, color, size, dens, power, water || underwater);
            if (water || underwater) {
                power *= Math.pow(0.96, step * 4.0);
            } else {
                power *= 0.985;
            }
            travelled += step;
        }
    }

    private static void spawnBeamParticle(World world, Location point, Color color, float size,
                                          double dens, double intensity, boolean underwater) {
        float alphaScale = (float) Math.max(0.08, Math.min(1.0, intensity));
        Color base = scaleColor(color, alphaScale);
        if (underwater) {
            // Scattering: wider, softer, slightly blue-shifted; more particles.
            Color tinted = underwaterTint(base);
            float scatterSize = Math.min(1.4f, size * (1.55f + (1f - alphaScale) * 0.6f));
            Particle.DustOptions core = new Particle.DustOptions(tinted, Math.min(1.1f, size * 1.25f));
            Particle.DustOptions haze = new Particle.DustOptions(
                    scaleColor(tinted, 0.65),
                    scatterSize
            );
            world.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, core, true);
            world.spawnParticle(Particle.DUST, point, dens >= 1.5 ? 2 : 1, 0.03, 0.03, 0.03, 0, haze, true);
        } else {
            Particle.DustOptions dust = new Particle.DustOptions(base, size);
            world.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, dust, true);
        }
    }

    private static void spawnTip(World world, Location tip, Color color, float size, double intensity, boolean underwater) {
        if (tip == null || world == null) {
            return;
        }
        Color base = scaleColor(color, Math.max(0.15, intensity));
        if (underwater) {
            Color tinted = underwaterTint(base);
            world.spawnParticle(Particle.DUST, tip, 2, 0.03, 0.03, 0.03, 0,
                    new Particle.DustOptions(tinted, Math.min(1.0f, size * 1.8f)), true);
            world.spawnParticle(Particle.DUST, tip, 1, 0.06, 0.06, 0.06, 0,
                    new Particle.DustOptions(scaleColor(tinted, 0.5), Math.min(1.3f, size * 2.4f)), true);
        } else {
            world.spawnParticle(Particle.DUST, tip, 1, 0, 0, 0, 0,
                    new Particle.DustOptions(base, Math.min(0.7f, size * 1.6f)), true);
        }
    }

    private static Color underwaterTint(Color color) {
        int r = (int) Math.round(color.getRed() * 0.42 + 25 * 0.58);
        int g = (int) Math.round(color.getGreen() * 0.42 + 170 * 0.58);
        int b = (int) Math.round(color.getBlue() * 0.42 + 215 * 0.58);
        return Color.fromRGB(clampByte((int) (r * 0.85)), clampByte((int) (g * 0.9)), clampByte(b));
    }

    private static Color scaleColor(Color color, double factor) {
        double f = Math.max(0, Math.min(1, factor));
        // Mix toward dark rather than pure black so dust stays visible.
        int r = (int) Math.round(color.getRed() * f);
        int g = (int) Math.round(color.getGreen() * f);
        int b = (int) Math.round(color.getBlue() * f);
        return Color.fromRGB(clampByte(Math.max(8, r)), clampByte(Math.max(8, g)), clampByte(Math.max(8, b)));
    }

    /** Reflect incident direction across a normal. Both should be normalized-ish. */
    private static Vector reflect(Vector incident, Vector normal) {
        Vector i = incident.clone().normalize();
        Vector n = normal.clone().normalize();
        return i.subtract(n.multiply(2.0 * i.dot(n))).normalize();
    }

    /**
     * Snell's law refraction. {@code normal} points toward the incident medium.
     * Returns null on total internal reflection.
     */
    private static Vector refract(Vector incident, Vector normal, double n1, double n2) {
        Vector i = incident.clone().normalize();
        Vector n = normal.clone().normalize();
        double cosi = clamp(-1, 1, -i.dot(n));
        // Ensure normal faces the incoming ray
        if (i.dot(n) > 0) {
            n.multiply(-1);
            cosi = clamp(-1, 1, -i.dot(n));
        }
        double eta = n1 / n2;
        double k = 1.0 - eta * eta * (1.0 - cosi * cosi);
        if (k < 0) {
            return null; // TIR
        }
        // R = eta * I + (eta * cosi - sqrt(k)) * N
        return i.multiply(eta).add(n.multiply(eta * cosi - Math.sqrt(k))).normalize();
    }

    private static double clamp(double min, double max, double value) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Single LIGHT at the beam tip (air cell just before the hit).
     */
    public static Set<Block> updateTipLight(Location muzzle, Location tip, int lightLevel,
                                            Set<Block> previous) {
        Set<Block> next = new HashSet<>();
        if (tip == null || tip.getWorld() == null || lightLevel <= 0) {
            clearLights(previous);
            return next;
        }
        int level = Math.max(1, Math.min(12, lightLevel));
        Light data = (Light) Material.LIGHT.createBlockData();
        data.setLevel(level);

        Block spot = findTipAirCell(muzzle, tip);
        if (spot != null) {
            Material type = spot.getType();
            if (type.isAir() || type == Material.LIGHT || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
                spot.setBlockData(data, false);
                next.add(spot);
            }
        }

        if (previous != null) {
            for (Block old : previous) {
                if (!next.contains(old) && old.getType() == Material.LIGHT) {
                    old.setType(Material.AIR, false);
                }
            }
        }
        return next;
    }

    /** Prefer air at/near tip; if tip is inside solid, step back toward the muzzle. */
    private static Block findTipAirCell(Location muzzle, Location tip) {
        Block at = tip.getBlock();
        Material type = at.getType();
        if (type.isAir() || type == Material.LIGHT || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
            return at;
        }
        if (muzzle == null || muzzle.getWorld() == null || !muzzle.getWorld().equals(tip.getWorld())) {
            for (Block n : new Block[]{at.getRelative(0, 1, 0), at.getRelative(0, -1, 0),
                    at.getRelative(1, 0, 0), at.getRelative(-1, 0, 0),
                    at.getRelative(0, 0, 1), at.getRelative(0, 0, -1)}) {
                Material t = n.getType();
                if (t.isAir() || t == Material.LIGHT || t == Material.CAVE_AIR || t == Material.VOID_AIR) {
                    return n;
                }
            }
            return null;
        }
        Vector back = muzzle.toVector().subtract(tip.toVector());
        if (back.lengthSquared() < 1.0E-6) {
            return null;
        }
        back.normalize();
        for (double d = 0.15; d <= 1.35; d += 0.2) {
            Location probe = tip.clone().add(back.clone().multiply(d));
            Block b = probe.getBlock();
            Material t = b.getType();
            if (t.isAir() || t == Material.LIGHT || t == Material.CAVE_AIR || t == Material.VOID_AIR) {
                return b;
            }
        }
        return null;
    }

    public static Set<Block> updateBeamLights(Location start, Location end, int lightLevel,
                                              double spacing, Set<Block> previous) {
        Set<Block> next = new HashSet<>();
        if (start == null || end == null || start.getWorld() == null || end.getWorld() == null
                || lightLevel <= 0) {
            clearLights(previous);
            return next;
        }
        Vector delta = end.toVector().subtract(start.toVector());
        double distance = delta.length();
        if (distance < 0.05) {
            clearLights(previous);
            return next;
        }
        Vector dir = delta.normalize();
        double step = Math.max(0.75, spacing);
        int level = Math.max(1, Math.min(15, lightLevel));
        Light data = (Light) Material.LIGHT.createBlockData();
        data.setLevel(level);

        for (double travelled = 0.35; travelled <= distance; travelled += step) {
            Location point = start.clone().add(dir.clone().multiply(travelled));
            Block block = point.getBlock();
            Material type = block.getType();
            if (type.isAir() || type == Material.LIGHT || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
                block.setBlockData(data, false);
                next.add(block);
            }
        }
        Block tip = end.getBlock();
        if (tip.getType().isAir() || tip.getType() == Material.LIGHT
                || tip.getType() == Material.CAVE_AIR || tip.getType() == Material.VOID_AIR) {
            tip.setBlockData(data, false);
            next.add(tip);
        }

        if (previous != null) {
            for (Block old : previous) {
                if (!next.contains(old) && old.getType() == Material.LIGHT) {
                    old.setType(Material.AIR, false);
                }
            }
        }
        return next;
    }

    public static void clearLights(Set<Block> lights) {
        if (lights == null || lights.isEmpty()) {
            return;
        }
        for (Block block : lights) {
            if (block.getType() == Material.LIGHT) {
                block.setType(Material.AIR, false);
            }
        }
        lights.clear();
    }

    public static String colorToConfig(Color color) {
        if (color == null) {
            return "#FF0000";
        }
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }
}
