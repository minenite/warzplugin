package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.projectile.Bullet;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tactical glass: caliber-aware penetration, persistent crack/hole marks synced to companions.
 */
public final class GlassService {
    public static final String CHANNEL = "pvpgunminus:glass";
    /** Glass channel wire version (exit-face UV fields since 2). */
    public static final int GLASS_PROTOCOL = 2;
    private static final byte ACT_UPSERT = 1;
    private static final byte ACT_CLEAR = 2;
    private static final byte ACT_FULL = 3;
    private static final int MAX_IMPACTS = 24;

    private final WarzPlugin plugin;
    private final File file;
    private final Map<String, String> placed = new ConcurrentHashMap<>();
    private final Map<String, Double> damage = new ConcurrentHashMap<>();
    private final Map<String, List<Impact>> impacts = new ConcurrentHashMap<>();

    public GlassService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tactical-glass.yml");
        load();
    }

    public void registerChannel() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    public void unregisterChannel() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    public boolean isTacticalGlass(Block block) {
        return block != null && placed.containsKey(key(block.getLocation()));
    }

    public GlassType typeAt(Block block) {
        if (block == null) {
            return null;
        }
        return GlassType.fromId(placed.get(key(block.getLocation())));
    }

    public boolean isPane(Block block) {
        if (block == null) {
            return false;
        }
        String name = block.getType().name();
        return name.endsWith("_GLASS_PANE") || name.equals("GLASS_PANE");
    }

    public void mark(Block block, GlassType type) {
        if (block == null || type == null || block.getWorld() == null) {
            return;
        }
        String k = key(block.getLocation());
        placed.put(k, type.id());
        damage.remove(k);
        impacts.remove(k);
        save();
        broadcastClear(block);
    }

    public void unmark(Block block) {
        if (block == null) {
            return;
        }
        String k = key(block.getLocation());
        if (placed.remove(k) != null) {
            damage.remove(k);
            impacts.remove(k);
            save();
            broadcastClear(block);
        }
    }

    public void syncViewer(Player viewer) {
        if (viewer == null || plugin.companions() == null || !plugin.companions().hasCompanion(viewer)) {
            return;
        }
        byte[] payload = encodeFull();
        if (payload != null) {
            viewer.sendPluginMessage(plugin, CHANNEL, payload);
        }
    }

    /**
     * Grenades / throwables: smash tactical glass and keep flying with no path interrupt.
     * @return true when this block was (or is) tactical glass and was cleared for passage
     */
    public boolean smashThroughForThrowable(Bullet bullet, Block block, BlockFace face) {
        if (bullet == null || block == null) {
            return false;
        }
        GlassType type = typeAt(block);
        if (type == null) {
            return false;
        }
        String k = key(block.getLocation());
        if (bullet.ignoresPierce(k)) {
            nudgeThrough(bullet, block, face);
            return true;
        }
        shatterQuiet(block, type);
        unmark(block);
        Location at = block.getLocation().add(0.5, 0.5, 0.5);
        World world = block.getWorld();
        if (world != null) {
            world.playSound(at, Sound.BLOCK_GLASS_BREAK, 1.1f, 1.05f);
        }
        // Keep full speed — throwables shouldn't lose arc/fuse timing to glass.
        nudgeThrough(bullet, block, face != null ? face : BlockFace.NORTH);
        bullet.ignorePierceKey(k, 10);
        bullet.reassertVelocity();
        plugin.getServer().getScheduler().runTask(plugin, bullet::reassertVelocity);
        return true;
    }

    /** Break vanilla glass / panes (non-tactical) so throwables can fly through. */
    public static boolean breakVanillaGlass(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        String name = type.name();
        boolean glass = type == Material.GLASS || type == Material.GLASS_PANE
                || type == Material.TINTED_GLASS
                || name.endsWith("_GLASS")
                || name.endsWith("_GLASS_PANE");
        if (!glass) {
            return false;
        }
        Location at = block.getLocation().add(0.5, 0.5, 0.5);
        World world = block.getWorld();
        block.setType(Material.AIR, true);
        if (world != null) {
            world.playSound(at, Sound.BLOCK_GLASS_BREAK, 1.0f, 1.0f);
            world.spawnParticle(org.bukkit.Particle.BLOCK, at, 18, 0.25, 0.25, 0.25, 0.02,
                    type.createBlockData());
        }
        return true;
    }

    /**
     * @return PENETRATE keep flying, STOP end bullet, NONE not our glass
     */
    public HitResult handleHit(Bullet bullet, Block block, BlockFace face) {
        GlassType type = typeAt(block);
        if (type == null || bullet == null) {
            return HitResult.NONE;
        }
        // Throwables always smash through — never stop or fuse-bounce on glass.
        if (bullet.gun() != null && bullet.gun().throwable()) {
            return smashThroughForThrowable(bullet, block, face) ? HitResult.PENETRATE : HitResult.NONE;
        }
        String k = key(block.getLocation());
        if (bullet.ignoresPierce(k)) {
            nudgeThrough(bullet, block, face);
            return HitResult.PENETRATE;
        }

        // Ray-box through the cell: real entry + exit faces (side shot → opposite side, etc.)
        ThroughTrace path = traceThrough(block, bullet, face);
        BlockFace hitFace = path.entryFace();
        boolean pane = isPane(block);
        double maxHp = type.integrityFor(pane);
        double taken = damage.getOrDefault(k, 0.0);

        BallisticsProfile shot = BallisticsProfile.of(bullet);
        double impactDmg = shot.impact();
        double pen = shot.pen();

        if (type == GlassType.BOROSILICATE && shot.thermal()) {
            impactDmg *= 0.65;
            pen *= 0.70;
        }
        if (type == GlassType.POLYCARBONATE && shot.thermal()) {
            impactDmg *= 1.35;
        }

        List<Impact> existing = impacts.getOrDefault(k, List.of());
        Impact nearHole = findNearbyImpact(existing, faceToByte(hitFace), path.entryU(), path.entryV());
        boolean throughExisting = nearHole != null && rollsThroughExistingHole(type, nearHole, shot);

        taken += impactDmg * (throughExisting ? 0.35 : 1.0);
        damage.put(k, taken);
        float damageRatio = (float) Math.min(1.0, taken / Math.max(0.01, maxHp));

        boolean canPen = throughExisting
                || decidesPenetration(type, pen, maxHp, taken, shot);
        float entryU = path.entryU();
        float entryV = path.entryV();
        float exitU = path.exitU();
        float exitV = path.exitV();
        // Tiny display jitter — keep entry/exit linked so the tunnel stays lined up
        float ju = (ThreadLocalRandom.current().nextFloat() - 0.5f) * 0.03f;
        float jv = (ThreadLocalRandom.current().nextFloat() - 0.5f) * 0.03f;
        entryU = clamp01(entryU + ju);
        entryV = clamp01(entryV + jv);
        exitU = clamp01(exitU + ju);
        exitV = clamp01(exitV + jv);
        Impact mark = buildImpact(type, hitFace, entryU, entryV, shot, damageRatio, canPen,
                path.exitFace(), exitU, exitV);
        face = hitFace; // use resolved face for pierce nudge below
        // Shooting an existing hole: enlarge it instead of stacking a second mark on top
        List<Impact> list = impacts.computeIfAbsent(k, id -> new ArrayList<>());
        if (throughExisting && nearHole != null) {
            list.remove(nearHole);
            float biggerHole = Math.min(0.22f, nearHole.holeR() * 1.25f + mark.holeR() * 0.35f);
            float biggerCrack = Math.min(0.75f, Math.max(nearHole.crackR(), mark.crackR()) * 1.1f);
            mark = new Impact(nearHole.face(), nearHole.u(), nearHole.v(),
                    biggerHole, biggerCrack, mark.style(),
                    (byte) Math.min(255, (nearHole.severity() & 0xFF) + 25), true,
                    mark.exitFace(), mark.exitU(), mark.exitV());
        }
        list.add(mark);
        while (list.size() > MAX_IMPACTS) {
            list.remove(0);
        }

        boolean destroy = shouldDestroy(type, taken, maxHp, canPen, shot, list.size());
        playImpactAudio(block, shot, canPen, destroy, type);
        broadcastUpsert(block, type, list, damageRatio);

        // Angled through-shot leaving a side face: also mark the neighboring glass block
        if (canPen && path.exitFace() != null && path.exitFace() != hitFace.getOppositeFace()) {
            stampSideNeighbor(block, path, mark, shot);
        } else if (canPen) {
            // Straight-through: still stamp the block beyond the exit if it's glass
            stampSideNeighbor(block, path, mark, shot);
        }

        if (destroy) {
            shatterQuiet(block, type);
            unmark(block);
            // Brittle collapses still let the round continue to the far side
            if (canPen || type.shatter() == GlassType.Shatter.INSTANT
                    || type.shatter() == GlassType.Shatter.DICE) {
                finishPierce(bullet, block, face, k, type, shot, throughExisting);
                return HitResult.PENETRATE;
            }
            return HitResult.STOP;
        }

        if (canPen) {
            finishPierce(bullet, block, face, k, type, shot, throughExisting);
            save();
            return HitResult.PENETRATE;
        }

        save();
        return HitResult.STOP;
    }

    /**
     * When a round leaves through a face into another tactical-glass cell, stamp that neighbor
     * so angled shots visibly continue into the side block.
     */
    private void stampSideNeighbor(Block block, ThroughTrace path, Impact from, BallisticsProfile shot) {
        if (block == null || path == null || from == null || path.exitFace() == null) {
            return;
        }
        Block next = block.getRelative(path.exitFace());
        if (!isTacticalGlass(next)) {
            return;
        }
        GlassType nextType = typeAt(next);
        if (nextType == null) {
            return;
        }
        String nk = key(next.getLocation());
        BlockFace nextEntry = path.exitFace().getOppositeFace();
        // Shared face plane: neighbor entry UV matches our exit UV
        float nu = path.exitU();
        float nv = path.exitV();
        ThroughTrace nextPath = traceFromFace(next, nextEntry, nu, nv, directionInto(path.exitFace()));
        boolean nextPane = isPane(next);
        double maxHp = nextType.integrityFor(nextPane);
        double taken = damage.getOrDefault(nk, 0.0) + shot.impact() * 0.55;
        damage.put(nk, taken);
        float damageRatio = (float) Math.min(1.0, taken / Math.max(0.01, maxHp));
        boolean hole = true;
        Impact mark = buildImpact(nextType, nextPath.entryFace(), nextPath.entryU(), nextPath.entryV(),
                shot, damageRatio, hole, nextPath.exitFace(), nextPath.exitU(), nextPath.exitV());
        List<Impact> list = impacts.computeIfAbsent(nk, id -> new ArrayList<>());
        list.add(mark);
        while (list.size() > MAX_IMPACTS) {
            list.remove(0);
        }
        broadcastUpsert(next, nextType, list, damageRatio);
        if (shouldDestroy(nextType, taken, maxHp, true, shot, list.size())) {
            shatterQuiet(next, nextType);
            unmark(next);
        } else {
            save();
        }
    }

    private static Vector directionInto(BlockFace exitFace) {
        // Direction of travel leaving through exitFace
        return exitFace.getDirection().clone();
    }

    /** Continue a ray from a known entry face/UV through this block to its exit. */
    private ThroughTrace traceFromFace(Block block, BlockFace entry, float u, float v, Vector dir) {
        if (dir == null || dir.lengthSquared() < 1.0e-12) {
            return new ThroughTrace(entry, u, v, entry.getOppositeFace(), u, v);
        }
        dir = dir.clone().normalize();
        double[] p = facePoint(entry, u, v);
        // Start just inside so we don't re-hit the entry plane
        double ox = p[0] + dir.getX() * 0.02;
        double oy = p[1] + dir.getY() * 0.02;
        double oz = p[2] + dir.getZ() * 0.02;
        ExitHit exit = marchExit(ox, oy, oz, dir);
        return new ThroughTrace(entry, clamp01(u), clamp01(v),
                exit.face(), exit.u(), exit.v());
    }

    private void finishPierce(Bullet bullet, Block block, BlockFace face, String key,
                              GlassType type, BallisticsProfile shot, boolean throughExisting) {
        double retain = type.penRetain() * shot.retainBonus();
        if (throughExisting) {
            // Already-open path: lose far less energy
            retain = Math.max(retain, 0.72) * rehitRetainBonus(type);
        }
        if (type.shatter() == GlassType.Shatter.INSTANT || type.shatter() == GlassType.Shatter.DICE) {
            retain = Math.max(retain, 0.82);
        }
        if (type.shatter() == GlassType.Shatter.FLEX && !throughExisting) {
            retain *= 0.55;
        }
        bullet.scaleVelocity(Math.max(0.45, Math.min(0.98, retain)));
        nudgeThrough(bullet, block, face);
        bullet.ignorePierceKey(key, 8);
        bullet.reassertVelocity();
        // Vanilla often zeroes projectile velocity on the hit tick — reapply next tick
        plugin.getServer().getScheduler().runTask(plugin, bullet::reassertVelocity);
        plugin.getServer().getScheduler().runTaskLater(plugin, bullet::reassertVelocity, 1L);
    }

    /**
     * Chance a follow-up round finds the same hole / weak spot and slips through.
     * Brittle glass ≈ always; laminated/windshield sometimes; BR/ballistic rarely.
     */
    private boolean rollsThroughExistingHole(GlassType type, Impact prior, BallisticsProfile shot) {
        if (prior == null || !prior.hole()) {
            // Cracked-but-no-hole: only soft glass / acrylic get a partial chance
            if (prior == null) {
                return false;
            }
            double crackChance = switch (type) {
                case STANDARD, TEMPERED, AUTO_SIDE, ONE_WAY, BOROSILICATE, FUSED_QUARTZ -> 0.55;
                case ACRYLIC, GLASS_CERAMIC -> 0.25;
                case LAMINATED, WIRED, AUTO_WINDSHIELD -> 0.12;
                default -> 0.04;
            };
            return ThreadLocalRandom.current().nextDouble() < crackChance * (0.7 + shot.penNorm());
        }
        double base = switch (type) {
            case STANDARD, TEMPERED, AUTO_SIDE, ONE_WAY, BOROSILICATE, FUSED_QUARTZ -> 0.97;
            case AUTO_WINDSHIELD -> 0.70;
            case LAMINATED, WIRED, ACRYLIC, GLASS_CERAMIC -> 0.45;
            case POLYCARBONATE -> 0.18;
            case BR_LAMINATED -> 0.12;
            case BALLISTIC_THICK -> 0.06;
        };
        // Bigger hole / stronger round → more likely
        double holeBonus = Math.min(0.25, prior.holeR() * 1.8);
        double apBonus = shot.penNorm() * 0.15;
        return ThreadLocalRandom.current().nextDouble() < Math.min(0.99, base + holeBonus + apBonus);
    }

    private double rehitRetainBonus(GlassType type) {
        return switch (type.shatter()) {
            case INSTANT, DICE -> 1.08;
            case PUNCH_HOLE -> 1.12;
            case HOLD -> 1.15;
            case FLEX -> 1.25;
            case CRATER -> 1.20;
        };
    }

    private Impact findNearbyImpact(List<Impact> list, byte face, float u, float v) {
        Impact best = null;
        double bestDist = Double.MAX_VALUE;
        for (Impact i : list) {
            // Match entry face or this shot landing on a prior exit (far-side follow-up)
            boolean onEntry = i.face() == face;
            boolean onExit = i.exitFace() == face;
            if (!onEntry && !onExit) {
                continue;
            }
            double du = (onEntry ? i.u() : i.exitU()) - u;
            double dv = (onEntry ? i.v() : i.exitV()) - v;
            double dist = Math.sqrt(du * du + dv * dv);
            double radius = Math.max(0.06, i.hole() ? i.holeR() * 1.35 : i.crackR() * 0.45);
            if (dist <= radius && dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    private static byte oppositeFace(byte face) {
        return switch (face) {
            case 0 -> 1; // DOWN ↔ UP
            case 1 -> 0;
            case 2 -> 3; // NORTH ↔ SOUTH
            case 3 -> 2;
            case 4 -> 5; // WEST ↔ EAST
            case 5 -> 4;
            default -> face;
        };
    }

    private boolean decidesPenetration(GlassType type, double pen, double maxHp, double taken, BallisticsProfile shot) {
        // Window / tempered / side glass: rounds punch through immediately
        if (type.shatter() == GlassType.Shatter.INSTANT || type.shatter() == GlassType.Shatter.DICE) {
            return true;
        }
        double resist = type.penResist() * maxHp;
        return switch (type.shatter()) {
            case PUNCH_HOLE -> pen >= resist * 0.48;
            case HOLD -> pen >= resist * 0.68;
            case FLEX -> pen >= resist * 1.20;
            case CRATER -> pen >= resist * 1.05 && taken >= maxHp * 0.70;
            default -> pen >= resist * 0.55;
        };
    }

    private boolean shouldDestroy(GlassType type, double taken, double maxHp, boolean canPen,
                                  BallisticsProfile shot, int impactCount) {
        return switch (type.shatter()) {
            case INSTANT -> taken >= maxHp * 0.45
                    || (canPen && taken >= maxHp * 0.28)
                    || (impactCount >= 3 && type.fragmentation() > 0.7);
            case DICE -> taken >= maxHp * 0.62
                    || (canPen && shot.impact() >= maxHp * 0.35 && taken >= maxHp * 0.4)
                    || impactCount >= 4;
            case HOLD, PUNCH_HOLE -> taken >= maxHp;
            case FLEX -> taken >= maxHp * 1.35;
            case CRATER -> taken >= maxHp;
        };
    }

    private Impact buildImpact(GlassType type, BlockFace face, float u, float v,
                               BallisticsProfile shot, float damageRatio, boolean hole,
                               BlockFace exitFace, float exitU, float exitV) {
        float calHole = switch (shot.caliber()) {
            case "pistol", "handgun" -> 0.045f;
            case "sniper", "heavy" -> 0.085f;
            case "shotgun", "shot" -> 0.10f;
            case "rocket", "launcher" -> 0.16f;
            case "energy", "plasma", "laser" -> 0.07f;
            default -> 0.06f; // rifle
        };
        float holeR = hole ? calHole * (0.85f + shot.penNorm() * 0.4f) : calHole * 0.35f;
        // Crack bloom — resistant glass: wide white web, brittle: dense shatter-like web
        float crackMul = switch (type.visual()) {
            case DENSE_WEB, WHITE_ZONE, OPAQUE_HOLE -> 3.8f;
            case CRAZE_COLLAPSE -> 4.2f;
            case SPIDERWEB_COLLAPSE, DENSE_LOCAL -> 3.2f;
            case RADIAL, SHARP_RADIAL, CONCHOIDAL -> 2.8f;
            case CRATER_CLOUDY -> 2.4f;
            case MESH -> 2.6f;
            case MIRROR_FLAKE -> 2.2f;
            case DENT -> 1.4f;
        };
        float crackR = Math.min(0.78f, holeR * crackMul * (0.75f + damageRatio * 0.9f)
                * (0.7f + (float) type.fragmentation() * 0.6f) * shot.crackScale());
        if (!hole && type.shatter() == GlassType.Shatter.FLEX) {
            holeR *= 0.4f;
            crackR *= 0.7f;
        }
        if (!hole && (type == GlassType.BR_LAMINATED || type == GlassType.BALLISTIC_THICK)) {
            // No clean hole — white impact bloom
            holeR *= 0.25f;
            crackR = Math.min(0.7f, crackR * 1.35f);
        }
        // Caliber scales mark severity (pistol tick vs sniper spiderweb)
        int sevBase = (int) (40 + damageRatio * 180 + (hole ? 35 : 0) + shot.crackScale() * 40f);
        byte severity = (byte) Math.max(1, Math.min(255, sevBase));
        byte style = (byte) type.visual().ordinal();
        byte faceId = faceToByte(face);
        byte exitId = faceToByte(exitFace != null ? exitFace : face.getOppositeFace());
        return new Impact(faceId, u, v, holeR, crackR, style, severity, hole,
                exitId, clamp01(exitU), clamp01(exitV));
    }

    /**
     * Aim-ray through this block: entry where the shot hits, exit where it leaves
     * (opposite face on a straight shot, an adjacent side face when angled).
     */
    private ThroughTrace traceThrough(Block block, Bullet bullet, BlockFace reported) {
        Vector dir = bullet.velocity();
        if (dir == null || dir.lengthSquared() < 1.0e-12) {
            BlockFace in = reported != null && reported.isCartesian() ? reported : BlockFace.NORTH;
            return new ThroughTrace(in, 0.5f, 0.5f, in.getOppositeFace(), 0.5f, 0.5f);
        }
        dir = dir.clone().normalize();

        // Prefer the shooter's eye ray so aim point on the face (edge vs center) is accurate
        double ox;
        double oy;
        double oz;
        Player shooter = bullet.getShooter();
        if (shooter != null && shooter.isOnline() && shooter.getWorld().equals(block.getWorld())) {
            Location eye = shooter.getEyeLocation();
            ox = eye.getX() - block.getX();
            oy = eye.getY() - block.getY();
            oz = eye.getZ() - block.getZ();
            // Use look direction if it still points at this block; else keep bullet velocity
            Vector look = eye.getDirection();
            if (look.lengthSquared() > 1.0e-8) {
                look.normalize();
                if (look.dot(dir) > 0.55) {
                    dir = look;
                }
            }
        } else {
            Entity proj = bullet.getProjectile();
            if (proj != null && proj.isValid()) {
                Location p = proj.getLocation();
                ox = p.getX() - block.getX() - dir.getX() * 3.0;
                oy = p.getY() - block.getY() - dir.getY() * 3.0;
                oz = p.getZ() - block.getZ() - dir.getZ() * 3.0;
            } else {
                ox = 0.5 - dir.getX() * 3.0;
                oy = 0.5 - dir.getY() * 3.0;
                oz = 0.5 - dir.getZ() * 3.0;
            }
        }

        AabbHit hit = rayUnitCube(ox, oy, oz, dir.getX(), dir.getY(), dir.getZ());
        if (hit == null) {
            // Aim ray missed cube math — fall back to reported face + march exit along velocity
            BlockFace in = reported != null && reported.isCartesian()
                    ? reported
                    : dominantEntryFace(dir);
            float[] uv = approxUvFromProjectile(block, in, bullet);
            ExitHit exit = marchExit(
                    facePoint(in, uv[0], uv[1])[0] + dir.getX() * 0.02,
                    facePoint(in, uv[0], uv[1])[1] + dir.getY() * 0.02,
                    facePoint(in, uv[0], uv[1])[2] + dir.getZ() * 0.02,
                    dir);
            return new ThroughTrace(in, uv[0], uv[1], exit.face(), exit.u(), exit.v());
        }
        return new ThroughTrace(hit.entry(), hit.entryU(), hit.entryV(),
                hit.exit(), hit.exitU(), hit.exitV());
    }

    private float[] approxUvFromProjectile(Block block, BlockFace face, Bullet bullet) {
        Entity proj = bullet.getProjectile();
        double x = 0.5;
        double y = 0.5;
        double z = 0.5;
        if (proj != null && proj.isValid()) {
            x = proj.getLocation().getX() - block.getX();
            y = proj.getLocation().getY() - block.getY();
            z = proj.getLocation().getZ() - block.getZ();
        }
        return localToUv(face, x, y, z);
    }

    private static BlockFace dominantEntryFace(Vector dir) {
        double ax = Math.abs(dir.getX());
        double ay = Math.abs(dir.getY());
        double az = Math.abs(dir.getZ());
        if (ax >= ay && ax >= az) {
            return dir.getX() >= 0 ? BlockFace.WEST : BlockFace.EAST;
        }
        if (ay >= ax && ay >= az) {
            return dir.getY() >= 0 ? BlockFace.DOWN : BlockFace.UP;
        }
        return dir.getZ() >= 0 ? BlockFace.NORTH : BlockFace.SOUTH;
    }

    /**
     * Kay–Kajiya unit-cube intersection in local block space [0,1]^3.
     * Returns null if the ray misses.
     */
    private static AabbHit rayUnitCube(double ox, double oy, double oz,
                                       double dx, double dy, double dz) {
        double tMin = Double.NEGATIVE_INFINITY;
        double tMax = Double.POSITIVE_INFINITY;
        BlockFace entry = null;
        BlockFace exit = null;

        // X
        if (Math.abs(dx) < 1.0e-12) {
            if (ox < 0.0 || ox > 1.0) {
                return null;
            }
        } else {
            double inv = 1.0 / dx;
            double t0 = (0.0 - ox) * inv;
            double t1 = (1.0 - ox) * inv;
            BlockFace f0 = BlockFace.WEST;
            BlockFace f1 = BlockFace.EAST;
            if (t0 > t1) {
                double tmp = t0;
                t0 = t1;
                t1 = tmp;
                BlockFace tf = f0;
                f0 = f1;
                f1 = tf;
            }
            if (t0 > tMin) {
                tMin = t0;
                entry = f0;
            }
            if (t1 < tMax) {
                tMax = t1;
                exit = f1;
            }
        }
        // Y
        if (Math.abs(dy) < 1.0e-12) {
            if (oy < 0.0 || oy > 1.0) {
                return null;
            }
        } else {
            double inv = 1.0 / dy;
            double t0 = (0.0 - oy) * inv;
            double t1 = (1.0 - oy) * inv;
            BlockFace f0 = BlockFace.DOWN;
            BlockFace f1 = BlockFace.UP;
            if (t0 > t1) {
                double tmp = t0;
                t0 = t1;
                t1 = tmp;
                BlockFace tf = f0;
                f0 = f1;
                f1 = tf;
            }
            if (t0 > tMin) {
                tMin = t0;
                entry = f0;
            }
            if (t1 < tMax) {
                tMax = t1;
                exit = f1;
            }
        }
        // Z
        if (Math.abs(dz) < 1.0e-12) {
            if (oz < 0.0 || oz > 1.0) {
                return null;
            }
        } else {
            double inv = 1.0 / dz;
            double t0 = (0.0 - oz) * inv;
            double t1 = (1.0 - oz) * inv;
            BlockFace f0 = BlockFace.NORTH;
            BlockFace f1 = BlockFace.SOUTH;
            if (t0 > t1) {
                double tmp = t0;
                t0 = t1;
                t1 = tmp;
                BlockFace tf = f0;
                f0 = f1;
                f1 = tf;
            }
            if (t0 > tMin) {
                tMin = t0;
                entry = f0;
            }
            if (t1 < tMax) {
                tMax = t1;
                exit = f1;
            }
        }

        if (entry == null || exit == null || tMax < tMin || tMax < 0.0) {
            return null;
        }
        if (tMin < 0.0) {
            tMin = 0.0;
        }
        double eX = ox + dx * tMin;
        double eY = oy + dy * tMin;
        double eZ = oz + dz * tMin;
        double xX = ox + dx * tMax;
        double xY = oy + dy * tMax;
        double xZ = oz + dz * tMax;
        float[] eUv = localToUv(entry, eX, eY, eZ);
        float[] xUv = localToUv(exit, xX, xY, xZ);
        return new AabbHit(entry, eUv[0], eUv[1], exit, xUv[0], xUv[1]);
    }

    /** From an interior point, find the first face the direction leaves through. */
    private static ExitHit marchExit(double x, double y, double z, Vector dir) {
        double dx = dir.getX();
        double dy = dir.getY();
        double dz = dir.getZ();
        double bestT = Double.POSITIVE_INFINITY;
        BlockFace best = BlockFace.SOUTH;

        if (Math.abs(dx) > 1.0e-12) {
            for (double plane : new double[]{0.0, 1.0}) {
                double t = (plane - x) / dx;
                if (t < 1.0e-4 || t >= bestT) {
                    continue;
                }
                double yy = y + dy * t;
                double zz = z + dz * t;
                if (yy < -0.001 || yy > 1.001 || zz < -0.001 || zz > 1.001) {
                    continue;
                }
                bestT = t;
                best = plane < 0.5 ? BlockFace.WEST : BlockFace.EAST;
            }
        }
        if (Math.abs(dy) > 1.0e-12) {
            for (double plane : new double[]{0.0, 1.0}) {
                double t = (plane - y) / dy;
                if (t < 1.0e-4 || t >= bestT) {
                    continue;
                }
                double xx = x + dx * t;
                double zz = z + dz * t;
                if (xx < -0.001 || xx > 1.001 || zz < -0.001 || zz > 1.001) {
                    continue;
                }
                bestT = t;
                best = plane < 0.5 ? BlockFace.DOWN : BlockFace.UP;
            }
        }
        if (Math.abs(dz) > 1.0e-12) {
            for (double plane : new double[]{0.0, 1.0}) {
                double t = (plane - z) / dz;
                if (t < 1.0e-4 || t >= bestT) {
                    continue;
                }
                double xx = x + dx * t;
                double yy = y + dy * t;
                if (xx < -0.001 || xx > 1.001 || yy < -0.001 || yy > 1.001) {
                    continue;
                }
                bestT = t;
                best = plane < 0.5 ? BlockFace.NORTH : BlockFace.SOUTH;
            }
        }
        if (!(bestT < Double.POSITIVE_INFINITY)) {
            return new ExitHit(BlockFace.SOUTH, 0.5f, 0.5f);
        }
        float[] uv = localToUv(best, x + dx * bestT, y + dy * bestT, z + dz * bestT);
        return new ExitHit(best, uv[0], uv[1]);
    }

    private static double[] facePoint(BlockFace face, float u, float v) {
        return switch (face) {
            case NORTH -> new double[]{u, v, 0.0};
            case SOUTH -> new double[]{u, v, 1.0};
            case WEST -> new double[]{0.0, v, u};
            case EAST -> new double[]{1.0, v, u};
            case DOWN -> new double[]{u, 0.0, v};
            case UP -> new double[]{u, 1.0, v};
            default -> new double[]{u, v, 0.0};
        };
    }

    private record ExitHit(BlockFace face, float u, float v) {
    }

    private record AabbHit(BlockFace entry, float entryU, float entryV,
                           BlockFace exit, float exitU, float exitV) {
    }

    private static float[] localToUv(BlockFace face, double x, double y, double z) {
        float u;
        float v;
        switch (face) {
            case UP, DOWN -> {
                u = (float) x;
                v = (float) z;
            }
            case EAST, WEST -> {
                u = (float) z;
                v = (float) y;
            }
            default -> {
                u = (float) x;
                v = (float) y;
            }
        }
        return new float[]{clamp01(u), clamp01(v)};
    }

    private record ThroughTrace(BlockFace entryFace, float entryU, float entryV,
                                BlockFace exitFace, float exitU, float exitV) {
    }

    private static float clamp01(float x) {
        // Keep near-edge aim points so angled shots can still exit a side face
        return Math.max(0.02f, Math.min(0.98f, x));
    }

    private static byte faceToByte(BlockFace face) {
        if (face == null) {
            return 2; // NORTH
        }
        return switch (face) {
            case DOWN -> 0;
            case UP -> 1;
            case NORTH -> 2;
            case SOUTH -> 3;
            case WEST -> 4;
            case EAST -> 5;
            default -> 2;
        };
    }

    private void playImpactAudio(Block block, BallisticsProfile shot, boolean canPen, boolean destroy,
                                 GlassType type) {
        Location at = block.getLocation().add(0.5, 0.5, 0.5);
        World world = block.getWorld();
        float calPitch = switch (shot.caliber()) {
            case "pistol", "handgun" -> 1.45f;
            case "sniper", "heavy" -> 0.72f;
            case "shotgun", "shot" -> 0.88f;
            case "rocket", "launcher" -> 0.5f;
            case "energy", "plasma", "laser" -> 1.6f;
            case "arrow", "bolt" -> 1.25f;
            default -> 1.05f;
        };
        float vol = 0.55f + shot.penNorm() * 0.45f;
        if (destroy) {
            // Shatter audio comes from shatterQuiet — only a sharp pre-crack here
            world.playSound(at, Sound.BLOCK_GLASS_HIT, vol * 0.7f, calPitch * 1.2f);
            return;
        }
        if (canPen) {
            // Punch-through: high crack + brief tinkle
            world.playSound(at, Sound.BLOCK_GLASS_HIT, vol, calPitch * 1.35f);
            world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_HIT, vol * 0.45f, calPitch * 1.1f);
        } else {
            // Stopped / crater: heavier thud
            world.playSound(at, Sound.BLOCK_GLASS_HIT, vol * 1.05f, calPitch * 0.85f);
            if (type.shatter() == GlassType.Shatter.FLEX || type.shatter() == GlassType.Shatter.HOLD) {
                world.playSound(at, Sound.ITEM_SHIELD_BLOCK, 0.25f, calPitch);
            }
        }
    }

    private void shatterQuiet(Block block, GlassType type) {
        Location at = block.getLocation().add(0.5, 0.5, 0.5);
        World world = block.getWorld();
        block.setType(Material.AIR, false);
        float pitch = switch (type.shatter()) {
            case DICE -> 1.45f;
            case FLEX -> 0.6f;
            case CRATER -> 0.48f;
            case PUNCH_HOLE -> 1.15f;
            default -> 1.0f;
        };
        world.playSound(at, Sound.BLOCK_GLASS_BREAK, 1.05f, pitch);
        world.playSound(at, Sound.BLOCK_DECORATED_POT_BREAK, 0.35f, pitch * 0.9f);
        // Tiny dust pop only — cracks were already shown as overlays
        world.spawnParticle(org.bukkit.Particle.CLOUD, at, 4, 0.15, 0.15, 0.15, 0.01);
    }

    private void nudgeThrough(Bullet bullet, Block block, BlockFace face) {
        Entity proj = bullet.getProjectile();
        if (proj == null || !proj.isValid()) {
            return;
        }
        Vector dir = bullet.velocity().clone();
        if (dir.lengthSquared() < 1.0e-8) {
            dir = face != null ? face.getDirection().multiply(-1) : new Vector(0, 0, 1);
        } else {
            dir.normalize();
        }
        // Prefer the hit-face exit so we clear the cell even on grazing angles
        if (face != null && face != BlockFace.SELF) {
            Vector out = face.getDirection().multiply(-1);
            if (dir.dot(out) < 0.35) {
                dir = dir.clone().multiply(0.55).add(out.multiply(0.55));
                if (dir.lengthSquared() > 1.0e-8) {
                    dir.normalize();
                }
            }
        }
        // Push fully past the glass cell so the projectile doesn't re-collide
        Location to = block.getLocation().add(0.5, 0.5, 0.5).add(dir.clone().multiply(1.15));
        proj.teleport(to);
        proj.setVelocity(bullet.velocity().clone());
    }

    public record Impact(byte face, float u, float v, float holeR, float crackR,
                         byte style, byte severity, boolean hole,
                         byte exitFace, float exitU, float exitV) {
    }

    public enum HitResult {
        NONE, PENETRATE, STOP
    }

    private void broadcastUpsert(Block block, GlassType type, List<Impact> list, float damageRatio) {
        byte[] payload = encodeUpsert(block, type, list, damageRatio);
        sendNear(block.getLocation(), payload);
    }

    private void broadcastClear(Block block) {
        byte[] payload = encodeClear(block);
        sendNear(block.getLocation(), payload);
    }

    private void sendNear(Location loc, byte[] payload) {
        if (payload == null || plugin.companions() == null || loc.getWorld() == null) {
            return;
        }
        double r2 = 96 * 96;
        for (Player viewer : loc.getWorld().getPlayers()) {
            if (!plugin.companions().hasCompanion(viewer)) {
                continue;
            }
            if (viewer.getLocation().distanceSquared(loc) > r2) {
                continue;
            }
            viewer.sendPluginMessage(plugin, CHANNEL, payload);
        }
    }

    private byte[] encodeUpsert(Block block, GlassType type, List<Impact> list, float damageRatio) {
        try {
            var bos = new ByteArrayOutputStream();
            var out = new DataOutputStream(bos);
            out.writeByte(GLASS_PROTOCOL);
            out.writeByte(ACT_UPSERT);
            writeWorld(out, block.getWorld());
            out.writeInt(block.getX());
            out.writeInt(block.getY());
            out.writeInt(block.getZ());
            out.writeByte(type.ordinal());
            out.writeByte((int) Math.max(0, Math.min(255, damageRatio * 255)));
            out.writeByte(Math.min(255, list.size()));
            for (Impact i : list) {
                writeImpact(out, i);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeImpact(DataOutputStream out, Impact i) throws IOException {
        out.writeByte(i.face());
        out.writeByte((int) (i.u() * 255));
        out.writeByte((int) (i.v() * 255));
        out.writeByte((int) Math.max(1, Math.min(255, i.holeR() * 255)));
        out.writeByte((int) Math.max(1, Math.min(255, i.crackR() * 255)));
        out.writeByte(i.style());
        out.writeByte(i.severity());
        out.writeByte(i.hole() ? 1 : 0);
        out.writeByte(i.exitFace());
        out.writeByte((int) (i.exitU() * 255));
        out.writeByte((int) (i.exitV() * 255));
    }

    private byte[] encodeClear(Block block) {
        try {
            var bos = new ByteArrayOutputStream();
            var out = new DataOutputStream(bos);
            out.writeByte(GLASS_PROTOCOL);
            out.writeByte(ACT_CLEAR);
            writeWorld(out, block.getWorld());
            out.writeInt(block.getX());
            out.writeInt(block.getY());
            out.writeInt(block.getZ());
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] encodeFull() {
        try {
            record Entry(String worldKey, int x, int y, int z, GlassType type, String key) {
            }
            List<Entry> entries = new ArrayList<>();
            for (String k : placed.keySet()) {
                GlassType type = GlassType.fromId(placed.get(k));
                if (type == null) {
                    continue;
                }
                int last = k.lastIndexOf(':');
                int ySep = k.lastIndexOf(':', last - 1);
                int xSep = k.lastIndexOf(':', ySep - 1);
                if (xSep < 0) {
                    continue;
                }
                entries.add(new Entry(
                        k.substring(0, xSep),
                        Integer.parseInt(k.substring(xSep + 1, ySep)),
                        Integer.parseInt(k.substring(ySep + 1, last)),
                        Integer.parseInt(k.substring(last + 1)),
                        type, k));
            }
            var bos = new ByteArrayOutputStream();
            var out = new DataOutputStream(bos);
            out.writeByte(GLASS_PROTOCOL);
            out.writeByte(ACT_FULL);
            out.writeShort(entries.size());
            for (Entry e : entries) {
                writeWorldKey(out, e.worldKey());
                out.writeInt(e.x());
                out.writeInt(e.y());
                out.writeInt(e.z());
                out.writeByte(e.type().ordinal());
                double maxHp = e.type().integrity();
                double taken = damage.getOrDefault(e.key(), 0.0);
                out.writeByte((int) Math.max(0, Math.min(255, (taken / Math.max(1, maxHp)) * 255)));
                List<Impact> list = impacts.getOrDefault(e.key(), List.of());
                out.writeByte(Math.min(255, list.size()));
                for (Impact i : list) {
                    writeImpact(out, i);
                }
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeWorld(DataOutputStream out, World world) throws IOException {
        writeWorldKey(out, world.getKey().toString());
    }

    private static void writeWorldKey(DataOutputStream out, String worldKey) throws IOException {
        byte[] raw = worldKey.getBytes(StandardCharsets.UTF_8);
        out.writeShort(raw.length);
        out.write(raw);
    }

    public static String key(Block block) {
        return block == null ? "" : key(block.getLocation());
    }

    private static String key(Location loc) {
        return loc.getWorld().getKey() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    public void flush() {
        save();
    }

    private void load() {
        placed.clear();
        damage.clear();
        impacts.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (yaml.isConfigurationSection("glass")) {
            var sec = yaml.getConfigurationSection("glass");
            if (sec != null) {
                for (String k : sec.getKeys(false)) {
                    placed.put(k, sec.getString(k));
                }
            }
        }
        if (yaml.isConfigurationSection("damage")) {
            var sec = yaml.getConfigurationSection("damage");
            if (sec != null) {
                for (String k : sec.getKeys(false)) {
                    damage.put(k, sec.getDouble(k));
                }
            }
        }
        if (yaml.isConfigurationSection("impacts")) {
            var sec = yaml.getConfigurationSection("impacts");
            if (sec != null) {
                for (String k : sec.getKeys(false)) {
                    if (!placed.containsKey(k)) {
                        continue;
                    }
                    List<String> raw = sec.getStringList(k);
                    List<Impact> list = new ArrayList<>();
                    for (String line : raw) {
                        Impact impact = decodeImpactLine(line);
                        if (impact != null) {
                            list.add(impact);
                        }
                    }
                    if (!list.isEmpty()) {
                        impacts.put(k, list);
                    }
                }
            }
        }
        plugin.getLogger().info("Tactical glass loaded: " + placed.size() + " panes/blocks, "
                + impacts.size() + " with crack marks");
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (var e : placed.entrySet()) {
            yaml.set("glass." + e.getKey(), e.getValue());
        }
        for (var e : damage.entrySet()) {
            if (placed.containsKey(e.getKey())) {
                yaml.set("damage." + e.getKey(), e.getValue());
            }
        }
        for (var e : impacts.entrySet()) {
            if (!placed.containsKey(e.getKey()) || e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            List<String> lines = new ArrayList<>(e.getValue().size());
            for (Impact impact : e.getValue()) {
                lines.add(encodeImpactLine(impact));
            }
            yaml.set("impacts." + e.getKey(), lines);
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save tactical-glass.yml: " + ex.getMessage());
        }
    }

    /** Wire-stable CSV for YAML persistence. */
    private static String encodeImpactLine(Impact i) {
        return i.face() + "," + i.u() + "," + i.v() + "," + i.holeR() + "," + i.crackR() + ","
                + (i.style() & 0xFF) + "," + (i.severity() & 0xFF) + "," + (i.hole() ? 1 : 0) + ","
                + i.exitFace() + "," + i.exitU() + "," + i.exitV();
    }

    private static Impact decodeImpactLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] p = line.split(",");
        if (p.length < 11) {
            return null;
        }
        try {
            return new Impact(
                    (byte) Integer.parseInt(p[0].trim()),
                    Float.parseFloat(p[1].trim()),
                    Float.parseFloat(p[2].trim()),
                    Float.parseFloat(p[3].trim()),
                    Float.parseFloat(p[4].trim()),
                    (byte) Integer.parseInt(p[5].trim()),
                    (byte) Integer.parseInt(p[6].trim()),
                    Integer.parseInt(p[7].trim()) != 0,
                    (byte) Integer.parseInt(p[8].trim()),
                    Float.parseFloat(p[9].trim()),
                    Float.parseFloat(p[10].trim())
            );
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
