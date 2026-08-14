package com.local.warz.projectile;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.combat.ImpactEffects;
import com.local.warz.config.AmmoCaliber;
import com.local.warz.model.GunDefinition;
import com.local.warz.model.RoundDefinition;
import com.local.warz.runtime.BallisticsProfile;
import com.local.warz.runtime.BulletAudio;
import com.local.warz.runtime.CoverService;
import com.local.warz.runtime.WaterBallistics;
import com.local.warz.runtime.GlassService;
import com.local.warz.runtime.GlassType;
import com.local.warz.runtime.KillFeedService;
import com.local.warz.runtime.LaserCompanionBridge;
import com.local.warz.util.LaserBeams;
import com.local.warz.util.LaserOptics;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.ThrowableProjectile;
import org.bukkit.entity.WitherSkull;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class Bullet {
    /**
     * {@link LivingEntity#damage(double, org.bukkit.entity.Entity)} attributes damage to the
     * shooter Player. {@link com.local.warz.runtime.GunListener} cancels player-damager events
     * while a gun is held (to block melee punch) — so plugin gun damage must mark itself.
     */
    private static final ThreadLocal<Boolean> APPLYING_PLUGIN_DAMAGE = ThreadLocal.withInitial(() -> false);

    private final WarzPlugin plugin;
    private final UUID shooterId;
    private final GunDefinition gun;
    private final RoundDefinition round;
    private final Vector velocity;
    private final Location startLocation;
    private Entity projectile;
    private Location lastLocation;
    private LivingEntity stuckTo;
    private int ticks;
    private final int releaseTime;
    private boolean dead;
    private boolean active = true;
    private boolean released;
    private boolean destroyNextTick;
    private boolean remnantSpawned;
    private final boolean destroyWhenHit;
    private final String bulletType;
    private final boolean suppressed;
    private final Set<UUID> flybyHeard = BulletAudio.newHeardSet();
    private final Set<UUID> damagedEntities = new HashSet<>();
    /** Skip re-colliding the same glass cell for a few ticks after a pierce. */
    private String pierceIgnoreKey;
    private int pierceIgnoreTicks;
    /**
     * Plugin chunk tickets so long-range rockets (drone LAW) stay loaded past
     * simulation-distance from the shooter — otherwise WitherSkulls silently invalidate.
     */
    private World ticketWorld;
    private final Set<Long> chunkTickets = new HashSet<>();
    private static final int MAX_RICOCHETS = 2;
    private int ricochetCount;
    /** True after this round punched cover / glass before the killing hit. */
    private boolean wallbang;
    private boolean throughGlass;
    /** Fuse-only round has been converted from snowball → ground item roller. */
    private boolean rollingItem;
    /** Recent positions for a longer orange tracer ribbon (flares). */
    private final Deque<Location> tracerTrail = new ArrayDeque<>();
    private static final int FLARE_TRACER_TRAIL_TICKS = 18;
    /** Snapshot of the shooter's gun/throwable at fire time (attachments for kill feed). */
    private final ItemStack weaponSnapshot;
    /** Whether the shooter was ADS / scoped when this round was fired. */
    private final boolean aimedAtFire;
    /** Shooter fall distance (blocks) at fire time — for mid-air noscope trickshots. */
    private final float fallDistanceAtFire;
    /** Shooter eye location at fire time — for kill-feed range banding. */
    private final Location fireEyeLocation;
    /** Blocks of water this projectile has traveled through (underwater ballistics). */
    private double waterPathBlocks;
    private final boolean muzzleWetAtFire;

    public Bullet(WarzPlugin plugin, Player shooter, Vector velocity, GunDefinition gun, Projectile alreadyFired) {
        this(plugin, shooter, velocity, gun, alreadyFired, null, false);
    }

    public Bullet(WarzPlugin plugin, Player shooter, Vector velocity, GunDefinition gun,
                  Projectile alreadyFired, RoundDefinition round) {
        this(plugin, shooter, velocity, gun, alreadyFired, round, false);
    }

    public Bullet(WarzPlugin plugin, Player shooter, Vector velocity, GunDefinition gun,
                  Projectile alreadyFired, RoundDefinition round, boolean suppressed) {
        this(plugin, shooter, velocity, gun, alreadyFired, round, suppressed, -1);
    }

    /**
     * @param releaseTimeOverride remaining fuse ticks for cooked throwables; {@code < 0} = gun default
     */
    public Bullet(WarzPlugin plugin, Player shooter, Vector velocity, GunDefinition gun,
                  Projectile alreadyFired, RoundDefinition round, boolean suppressed,
                  int releaseTimeOverride) {
        this.plugin = plugin;
        this.shooterId = shooter.getUniqueId();
        this.gun = gun;
        this.round = round;
        this.suppressed = suppressed;
        this.velocity = velocity.clone();
        this.destroyWhenHit = gun.destroyBulletWhenHit();
        this.bulletType = gun.bulletType() == null ? "" : gun.bulletType().replace(" ", "").replace("_", "");
        ItemStack hand = shooter.getInventory().getItemInMainHand();
        this.weaponSnapshot = (hand != null && !hand.getType().isAir()) ? hand.clone() : null;
        boolean aimed = false;
        if (plugin.sessions() != null) {
            var session = plugin.sessions().get(shooter);
            if (session != null) {
                aimed = session.isAimedIn();
            }
        }
        this.aimedAtFire = aimed;
        this.fallDistanceAtFire = shooter.getFallDistance();
        this.fireEyeLocation = shooter.getEyeLocation().clone();
        this.muzzleWetAtFire = WaterBallistics.shooterMuzzleWet(shooter);
        this.waterPathBlocks = 0;

        if (gun.isLaser()) {
            fireLaser(shooter, velocity);
            this.startLocation = shooter.getEyeLocation();
            this.releaseTime = 1;
            return;
        }

        if (alreadyFired != null) {
            this.projectile = alreadyFired;
            silenceVanillaExplosive(projectile);
            applyBulletVisual(projectile);
        } else if (gun.throwable()) {
            ItemStack thrown = plugin.items().create(gun, 1);
            this.projectile = shooter.getWorld().dropItem(shooter.getEyeLocation(), thrown);
            ((org.bukkit.entity.Item) projectile).setPickupDelay(999999);
            projectile.setVelocity(velocity);
        } else {
            Class<? extends Projectile> type = resolveProjectileClass();
            this.projectile = shooter.launchProjectile(type, velocity);
            ((Projectile) projectile).setShooter(shooter);
            if (projectile instanceof Snowball snowball) {
                snowball.setGravity(true);
            }
            if (projectile instanceof Fireball fireball) {
                fireball.setDirection(velocity.clone().normalize());
            }
            // LAW / Javelin use wither skulls — kill vanilla blast so ImpactEffects owns crater + shock.
            silenceVanillaExplosive(projectile);
            applyBulletVisual(projectile);
        }

        this.startLocation = projectile.getLocation().clone();
        this.lastLocation = startLocation.clone();
        if (releaseTimeOverride >= 0) {
            this.releaseTime = Math.max(1, releaseTimeOverride);
        } else if (gun.releaseTime() == -1) {
            this.releaseTime = (20 * 4) + (gun.throwable() ? 0 : 400);
        } else {
            this.releaseTime = gun.releaseTime();
        }
        emitMuzzleFlash(shooter);
        if (!gun.throwable()) {
            ejectCasing(shooter);
        }
        // CardForge snowballs often never collide with living entities, so
        // ProjectileHitEvent / EntityDamage never fire. Hitscan the shot now.
        tryHitscanOnSpawn(shooter);
    }

    /**
     * Instant living-entity damage along the shot ray. Snowball/egg projectiles
     * are visual + block-hit only on this stack; they are not trusted to hurt mobs.
     */
    private void tryHitscanOnSpawn(Player shooter) {
        if (shooter == null || gun.throwable() || gun.isLaser() || dead) {
            return;
        }
        String t = bulletType == null ? "" : bulletType.toLowerCase(Locale.ROOT);
        if (t.contains("wither") || t.contains("fireball")) {
            return;
        }
        Vector dir = velocity.clone();
        if (dir.lengthSquared() < 1.0e-8) {
            dir = shooter.getEyeLocation().getDirection();
        }
        if (dir.lengthSquared() < 1.0e-8) {
            return;
        }
        dir.normalize();
        double range = round != null ? round.rangeFor(gun) : Math.max(1, gun.maxDistance());
        RayTraceResult result = LaserBeams.rayTraceIgnoringFoliage(
                shooter.getEyeLocation(),
                dir,
                range,
                0.35,
                entity -> entity instanceof LivingEntity living
                        && !living.getUniqueId().equals(shooterId)
        );
        if (result != null && result.getHitEntity() instanceof LivingEntity living) {
            Location at = result.getHitPosition() != null
                    ? result.getHitPosition().toLocation(shooter.getWorld())
                    : living.getLocation();
            hitEntity(living, at);
        }
    }

    /** Follow-up living hits along this tick's flight segment (snowball miss / fast bullets). */
    private void tryHitscanSegment(Location from, Location to) {
        if (dead || gun.throwable() || gun.isLaser() || from == null || to == null) {
            return;
        }
        if (from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return;
        }
        double dist = from.distance(to);
        if (dist < 0.02) {
            return;
        }
        Vector dir = to.toVector().subtract(from.toVector());
        RayTraceResult hit = LaserBeams.rayTraceIgnoringFoliage(
                from,
                dir,
                dist + 0.2,
                0.35,
                entity -> entity instanceof LivingEntity living
                        && !living.getUniqueId().equals(shooterId)
                        && !damagedEntities.contains(living.getUniqueId())
        );
        if (hit != null && hit.getHitEntity() instanceof LivingEntity living) {
            Location at = hit.getHitPosition() != null
                    ? hit.getHitPosition().toLocation(from.getWorld())
                    : living.getLocation();
            hitEntity(living, at);
        }
    }

    public RoundDefinition round() {
        return round;
    }

    public int damage() {
        return round != null ? round.damageFor(gun) : gun.gunDamage();
    }

    public static boolean isApplyingPluginDamage() {
        return Boolean.TRUE.equals(APPLYING_PLUGIN_DAMAGE.get());
    }

    /** Run {@code LivingEntity#damage} attributed to a gun shooter without melee-cancel. */
    public static void applyAttributedDamage(LivingEntity target, double amount, Player shooter) {
        if (target == null || !target.isValid() || amount <= 0) {
            return;
        }
        APPLYING_PLUGIN_DAMAGE.set(true);
        try {
            if (shooter != null) {
                target.damage(amount, shooter);
            } else {
                target.damage(amount);
            }
        } finally {
            APPLYING_PLUGIN_DAMAGE.set(false);
        }
    }

    public double knockbackStrength() {
        return round != null ? round.knockbackFor(gun) : gun.knockback();
    }

    private void emitMuzzleFlash(Player shooter) {
        LaserCompanionBridge bridge = plugin.laserBridge();
        if (bridge == null || shooter == null) {
            return;
        }
        boolean flash = gun.muzzleFlash();
        Color color = gun.muzzleColor();
        float scale = gun.muzzleScale();
        if (round != null) {
            flash = round.muzzleFlash();
            color = round.muzzleColor();
            scale = round.muzzleScale();
        }
        if (!flash) {
            return;
        }
        Location at = shooter.getEyeLocation().clone().add(shooter.getEyeLocation().getDirection().multiply(0.55));
        Vector dir = velocity.lengthSquared() > 0 ? velocity.clone().normalize() : at.getDirection();
        if (suppressed) {
            scale *= 0.22f;
        }
        bridge.broadcastMuzzleFlash(shooter, at, dir, color, scale, suppressed);
    }

    private Color fxColor() {
        if (round != null && round.tracer()) {
            return round.tracerColor();
        }
        if (gun.laserSightColor() != null && (gun.laserSight() || gun.isLaser())) {
            return gun.laserSightColor();
        }
        if (gun.isLaser()) {
            return Color.fromRGB(255, 48, 48);
        }
        return gun.muzzleColor();
    }

    private boolean wantTracer() {
        if (round != null) {
            return round.tracer();
        }
        return gun.hasSmokeTrail();
    }

    private Class<? extends Projectile> resolveProjectileClass() {
        String check = bulletType.toLowerCase();
        if (check.equals("egg")) return Egg.class;
        if (check.equals("arrow") || check.equals("crossbow")) return Arrow.class;
        if (check.equals("wither") || check.equals("witherskull")) return WitherSkull.class;
        if (check.equals("fireball") || check.equals("largefireball")) return LargeFireball.class;
        if (check.equals("smallfireball")) return SmallFireball.class;
        if (check.equals("enderpearl")) return EnderPearl.class;
        return Snowball.class;
    }

    /** Prevent wither skull / fireball vanilla blast from stealing the WarZ shock path. */
    private static void silenceVanillaExplosive(Entity entity) {
        if (entity instanceof org.bukkit.entity.Explosive explosive) {
            explosive.setYield(0f);
            explosive.setIsIncendiary(false);
        }
        if (entity instanceof WitherSkull skull) {
            skull.setCharged(false);
        }
    }

    /** Tiny gray in-flight speck (resource pack {@code pvpgunminus:bullet}). */
    private static final NamespacedKey BULLET_MODEL = new NamespacedKey("pvpgunminus", "bullet");
    /** Brass shell ejected from the receiver (resource pack {@code pvpgunminus:casing}). */
    private static final NamespacedKey CASING_MODEL = new NamespacedKey("pvpgunminus", "casing");

    private void applyBulletVisual(Entity entity) {
        if (!(entity instanceof ThrowableProjectile thrown)) {
            return;
        }
        // Keep arrow / fireball / wither skull vanilla looks
        if (!(thrown instanceof Snowball) && !(thrown instanceof Egg) && !(thrown instanceof EnderPearl)) {
            return;
        }
        ItemStack visual = new ItemStack(Material.IRON_NUGGET);
        ItemMeta meta = visual.getItemMeta();
        if (meta != null) {
            meta.setItemModel(BULLET_MODEL);
            visual.setItemMeta(meta);
        }
        thrown.setItem(visual);
    }

    /** Flings a brass casing out the right side of the gun (ejection-port style). */
    private void ejectCasing(Player shooter) {
        if (shooter == null || shooter.getWorld() == null) {
            return;
        }
        // Energy / laser-like guns have no brass; rockets / arrows neither
        String check = bulletType.toLowerCase();
        if (check.contains("wither") || check.contains("fireball") || check.contains("arrow")
                || check.contains("crossbow") || check.contains("ender")) {
            return;
        }
        if (gun.isLaser()) {
            return;
        }

        Location eye = shooter.getEyeLocation();
        Vector look = eye.getDirection().clone();
        if (look.lengthSquared() < 1.0E-6) {
            look = new Vector(0, 0, 1);
        } else {
            look.normalize();
        }
        Vector up = new Vector(0, 1, 0);
        Vector right = look.clone().crossProduct(up);
        if (right.lengthSquared() < 1.0E-4) {
            right = new Vector(1, 0, 0);
        } else {
            right.normalize();
        }

        // Slightly ahead + to the right of the barrel / receiver
        Location spawn = eye.clone()
                .add(look.clone().multiply(0.28))
                .add(right.clone().multiply(0.32))
                .add(0, -0.18, 0);

        ItemStack casing = new ItemStack(Material.GOLD_NUGGET);
        ItemMeta meta = casing.getItemMeta();
        if (meta != null) {
            meta.setItemModel(CASING_MODEL);
            casing.setItemMeta(meta);
        }

        org.bukkit.entity.Item dropped = shooter.getWorld().dropItem(spawn, casing);
        dropped.setPickupDelay(32767);
        try {
            dropped.setCanMobPickup(false);
            dropped.setUnlimitedLifetime(true);
        } catch (Throwable ignored) {
            // older API gaps
        }
        double side = 0.22 + Math.random() * 0.12;
        double upKick = 0.16 + Math.random() * 0.10;
        double forward = 0.04 + Math.random() * 0.06;
        Vector eject = right.clone().multiply(side)
                .add(new Vector(0, upKick, 0))
                .add(look.clone().multiply(forward));
        // Small random tumble
        eject.add(new Vector((Math.random() - 0.5) * 0.06, 0, (Math.random() - 0.5) * 0.06));
        dropped.setVelocity(eject);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (dropped.isValid() && !dropped.isDead()) {
                dropped.remove();
            }
        }, 45L);
    }

    private void fireLaser(Player shooter, Vector vec) {
        Location loc = shooter.getEyeLocation();
        Vector direction = vec.clone();
        if (direction.lengthSquared() == 0) {
            direction = loc.getDirection();
        }
        direction.normalize();
        double range = round != null ? round.rangeFor(gun) : Math.max(1, gun.maxDistance());
        // Ballistic / laser shots punch through leaves and plants
        RayTraceResult result = LaserBeams.rayTraceIgnoringFoliage(
                loc,
                direction,
                range,
                0.35,
                entity -> entity instanceof LivingEntity && !entity.equals(shooter)
        );

        Vector end = result == null
                ? loc.toVector().add(direction.clone().multiply(range))
                : result.getHitPosition();
        Location impact = end.toLocation(shooter.getWorld());
        Color beamColor = round != null && round.tracer()
                ? round.tracerColor()
                : (gun.laserSightColor() != null ? gun.laserSightColor() : Color.fromRGB(255, 40, 40));
        float beamWidth = gun.laserSightSize() > 0 ? Math.max(0.08f, gun.laserSightSize()) : 0.35f;

        LaserCompanionBridge bridge = plugin.laserBridge();
        if (bridge != null) {
            LaserOptics.BeamPath path = LaserOptics.traceFromTo(loc, impact, beamWidth, 2.0);
            List<Player> vanilla = bridge.vanillaViewersNear(loc);
            if (!vanilla.isEmpty()) {
                LaserOptics.spawnParticles(path, beamColor, beamWidth, 2.0, vanilla);
            }
            bridge.broadcastBeam(shooter, path, beamColor, beamWidth);
            boolean flash = round != null ? round.muzzleFlash() : gun.muzzleFlash();
            if (flash) {
                Color muzzle = round != null ? round.muzzleColor() : gun.muzzleColor();
                float scale = round != null ? round.muzzleScale() : Math.max(1.0f, gun.muzzleScale());
                float flashScale = suppressed ? scale * 0.22f : scale;
                bridge.broadcastMuzzleFlash(shooter, loc, direction, muzzle, flashScale, suppressed);
            }
            if (wantTracer()) {
                float width = round != null ? round.tracerWidth() : Math.max(0.04f, beamWidth * 0.35f);
                bridge.broadcastTracer(shooter, loc, impact, beamColor, width);
            }
            for (int i = 1; i <= 6; i++) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (shooter.isOnline()) {
                        bridge.broadcastBeam(shooter, path, beamColor, beamWidth);
                    }
                }, i);
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (shooter.isOnline()) {
                    bridge.clearBeam(shooter);
                }
            }, 8L);
        } else {
            double distance = loc.toVector().distance(end);
            Particle.DustOptions red = new Particle.DustOptions(
                    beamColor,
                    (float) plugin.getConfig().getDouble("laser-particle-size", 1.25)
            );
            for (double travelled = 0; travelled <= distance; travelled += 0.35) {
                Location point = loc.clone().add(direction.clone().multiply(travelled));
                shooter.getWorld().spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, red);
            }
        }

        if (result != null && result.getHitEntity() instanceof LivingEntity living) {
            hitEntity(living, impact);
        } else if (plugin.bigDrone() != null) {
            // Hitscan guns: enlarged airframe vs pilot AABB
            var air = plugin.bigDrone().rayTraceAirframe(loc, direction, range + 0.5, shooterId);
            air.ifPresent(pilot -> hitEntity(pilot, impact));
        }
        lastLocation = impact;
        ImpactEffects.apply(gun, shooter, impact, round, plugin);
        dead = true;
    }

    private void applyRoundHitEffects(LivingEntity living) {
        if (round == null) {
            return;
        }
        if (round.setFireTicks() > 0) {
            living.setFireTicks(Math.max(living.getFireTicks(), round.setFireTicks()));
        }
    }

    public void tick() {
        if (dead) {
            remove();
            return;
        }
        if (projectile == null) {
            dead = true;
            return;
        }
        // Snowballs die on block collide even when ProjectileHitEvent is cancelled —
        // recover fuse-only rounds into a rolling item before the fuse is lost.
        // Wither skulls / fireballs can despawn without a hit callback — still cook splash.
        if (!projectile.isValid()) {
            if (!gun.explodeOnImpact() && !released && !rollingItem) {
                Location at = lastLocation != null ? lastLocation.clone() : startLocation.clone();
                Vector kick = velocity.clone();
                if (kick.lengthSquared() < 0.04) {
                    kick = new Vector(
                            ThreadLocalRandom.current().nextDouble() - 0.5,
                            0.28,
                            ThreadLocalRandom.current().nextDouble() - 0.5).normalize().multiply(0.55);
                }
                if (!spawnRollingItem(at, kick)) {
                    dead = true;
                }
                return;
            }
            if (!released && (gun.explodeRadius() > 0 || gun.fireRadius() > 0 || gun.flashRadius() > 0
                    || (round != null && (round.explodeRadiusAdd() > 0 || round.fireRadiusAdd() > 0)))) {
                onHit();
            }
            dead = true;
            return;
        }
        ticks++;
        if (pierceIgnoreTicks > 0) {
            pierceIgnoreTicks--;
            if (pierceIgnoreTicks <= 0) {
                pierceIgnoreKey = null;
            }
        }
        Location previous = lastLocation == null ? null : lastLocation.clone();
        lastLocation = stuckTo != null ? stuckTo.getLocation().add(0, 0.5, 0) : projectile.getLocation();
        retainFlightChunks();
        if (previous != null) {
            tryHitscanSegment(previous, lastLocation);
        }

        // Underwater / through-water drag (guns still fire; ammo + attachments matter).
        if (!gun.throwable() && !rollingItem && lastLocation != null
                && WaterBallistics.isWater(lastLocation)) {
            if (previous != null && previous.getWorld() != null
                    && previous.getWorld().equals(lastLocation.getWorld())) {
                waterPathBlocks += Math.sqrt(previous.distanceSquared(lastLocation));
            } else {
                waterPathBlocks += 0.35;
            }
            if (!WaterBallistics.applyTick(velocity, lastLocation, fireEyeLocation, gun, round,
                    weaponSnapshot, plugin.items(), muzzleWetAtFire, waterPathBlocks)) {
                if (round != null && round.explodeRadiusAdd() > 0) {
                    onHit();
                }
                remove();
                return;
            }
            if (projectile.isValid()) {
                projectile.setVelocity(velocity.clone());
            }
            // Tiny underwater trail: sparse bubbles + micro water droplets
            World w = lastLocation.getWorld();
            if (w != null) {
                if (ticks % 2 == 0) {
                    w.spawnParticle(Particle.BUBBLE, lastLocation, 1, 0.02, 0.02, 0.02, 0.004);
                }
                if (ticks % 3 == 0) {
                    w.spawnParticle(Particle.BUBBLE_COLUMN_UP, lastLocation, 1, 0.015, 0.02, 0.015, 0.002);
                }
                // Droplets along the path segment (very small)
                if (previous != null && previous.getWorld() == w
                        && previous.distanceSquared(lastLocation) > 0.0001) {
                    Location mid = previous.clone().add(lastLocation).multiply(0.5);
                    w.spawnParticle(Particle.FALLING_WATER, mid, 1, 0.01, 0.01, 0.01, 0);
                    if (ticks % 4 == 0) {
                        w.spawnParticle(Particle.DRIPPING_WATER, lastLocation, 1, 0.02, 0.02, 0.02, 0);
                    }
                }
            }
        }

        if (rollingItem && projectile instanceof org.bukkit.entity.Item) {
            applyRollPhysics();
        } else if (gun.throwable() && projectile instanceof org.bukkit.entity.Item) {
            // Grenades / flash / molotov: settle quickly — don't skate across the floor.
            applyThrowableSettle();
        }

        if (isFlareProjectile()) {
            spawnFlareTrail(lastLocation);
        } else if (gun.hasSmokeTrail()) {
            lastLocation.getWorld().spawnParticle(Particle.SMOKE, lastLocation, 1, 0, 0, 0, 0);
        }

        // Item throwables don't fire ProjectileHitEvent — smash glass along the flight path.
        if (gun.throwable() && previous != null && lastLocation != null
                && previous.getWorld() != null && previous.getWorld().equals(lastLocation.getWorld())
                && previous.distanceSquared(lastLocation) > 0.0004) {
            smashGlassAlongSegment(previous, lastLocation);
        }

        if (previous != null && previous.getWorld() != null
                && previous.getWorld().equals(lastLocation.getWorld())
                && previous.distanceSquared(lastLocation) > 0.01) {
            BulletAudio.tickFlyby(previous, lastLocation, shooterId, gun, round, suppressed, flybyHeard);
        }

        if (wantTracer() && previous != null && previous.getWorld().equals(lastLocation.getWorld())
                && (isFlareProjectile() || gun.hasSmokeTrail() || ticks % 2 == 0)) {
            LaserCompanionBridge bridge = plugin.laserBridge();
            Player shooter = getShooter();
            if (bridge != null && shooter != null && previous.distanceSquared(lastLocation) > 0.0025) {
                float width = round != null ? round.tracerWidth() : (gun.hasSmokeTrail() ? 0.045f : 0.028f);
                Location trailFrom = previous;
                if (isFlareProjectile()) {
                    width = Math.max(width, 0.20f);
                    tracerTrail.addLast(lastLocation.clone());
                    while (tracerTrail.size() > FLARE_TRACER_TRAIL_TICKS) {
                        tracerTrail.removeFirst();
                    }
                    Location head = tracerTrail.peekFirst();
                    if (head != null && head.getWorld() != null && head.getWorld().equals(lastLocation.getWorld())) {
                        trailFrom = head;
                    }
                }
                bridge.broadcastTracer(shooter, trailFrom, lastLocation, fxColor(), width);
            }
        }

        // Inflated MQ-9 AABB (pilot hitbox alone is too small at altitude)
        if (!dead && !gun.throwable() && previous != null && plugin.bigDrone() != null) {
            var air = plugin.bigDrone().traceAirframeSegment(previous, lastLocation, shooterId);
            if (air.isPresent() && !damagedEntities.contains(air.get().getUniqueId())) {
                if (hitEntity(air.get(), lastLocation.clone())) {
                    return;
                }
            }
            var parked = plugin.bigDrone().traceParkedSegment(previous, lastLocation, shooterId);
            if (parked.isPresent()) {
                Player shooter = getShooter();
                if (shooter != null && plugin.bigDrone().absorbParkedHit(
                        parked.get(), shooter, gun, lastLocation, round)) {
                    if (destroyWhenHit && !"crossbow".equalsIgnoreCase(bulletType)) {
                        onHit();
                        remove();
                    }
                    return;
                }
            }
        }

        if ("crossbow".equalsIgnoreCase(bulletType) && released && ticks > releaseTime) {
            ImpactEffects.apply(gun, getShooter(), lastLocation, round, plugin);
            remove();
            return;
        }

        if (ticks > releaseTime) {
            if (gun.throwable() || gun.explodeRadius() > 0 || gun.fireRadius() > 0 || gun.flashRadius() > 0
                    || (round != null && (round.explodeRadiusAdd() > 0 || round.fireRadiusAdd() > 0))) {
                onHit();
            }
            // Must call remove() — manager only drops dead from the list; without this,
            // throwable Item entities stay forever with pickupDelay 999999 (bone on ground).
            remove();
            return;
        }

        if (active && !rollingItem && startLocation.getWorld().equals(lastLocation.getWorld())) {
            double dis = lastLocation.distance(startLocation);
            // maxDistance <= 0 means unlimited (grenades / flashbangs use this).
            double max = round != null ? round.rangeFor(gun) : gun.maxDistance();
            if (max > 0 && dis > max) {
                active = false;
                // Impact-fuse splash cooks off at max range; timed fuse waits for timeUntilRelease.
                boolean splash = gun.explodeRadius() > 0 || gun.fireRadius() > 0 || gun.flashRadius() > 0
                        || (round != null && (round.explodeRadiusAdd() > 0 || round.fireRadiusAdd() > 0));
                boolean timedFuse = gun.releaseTime() > 0 || !gun.explodeOnImpact();
                if (splash && !gun.canGoPastMaxDistance() && gun.explodeOnImpact() && !timedFuse) {
                    onHit();
                    remove();
                    return;
                }
                if (!gun.throwable() && !gun.canGoPastMaxDistance()) {
                    velocity.multiply(gun.explodeOnImpact() ? 0.25 : 0.55);
                }
            }
            // Throwables are Item entities — let vanilla gravity/friction run; re-forcing
            // launch velocity every tick makes nades float and detonate at your feet.
            if (!gun.throwable()) {
                if (usesFallBallistics()) {
                    applyFallBallistics();
                }
                projectile.setVelocity(velocity);
            }
        } else if (!gun.throwable() && usesFallBallistics() && projectile.isValid()) {
            // Past max-range still sink/smoke until life expires.
            applyFallBallistics();
            projectile.setVelocity(velocity);
        }

        // Fuse-only rollers need a little longer than the default 10s flight budget.
        // Long-range rockets (drone LAW) need enough ticks to actually reach maxDistance.
        int lifeTicks = Math.max(20 * 10, releaseTime + 40);
        double maxRange = round != null ? round.rangeFor(gun) : gun.maxDistance();
        double spd = Math.max(0.2, gun.bulletSpeed());
        if (maxRange > 100) {
            lifeTicks = Math.max(lifeTicks, (int) Math.ceil(maxRange / spd) + 60);
        }
        if (usesFallBallistics()) {
            // Slow hangers need more airtime than a flat laser bullet.
            lifeTicks = Math.max(lifeTicks, 20 * 10);
        }
        if (ticks > lifeTicks || destroyNextTick) {
            remove();
        }
    }

    private boolean isFlareProjectile() {
        String cal = round != null
                ? AmmoCaliber.normalize(round.caliber())
                : AmmoCaliber.normalize(gun.ammoCaliber());
        if ("flare".equals(cal) || "flares".equals(cal)) {
            return true;
        }
        String id = gun.fileName() == null ? "" : gun.fileName().toLowerCase(Locale.ROOT);
        return id.contains("flare");
    }

    private boolean usesFallBallistics() {
        return gun.fallSpeed() > 0.0 || isFlareProjectile()
                || SniperBallistics.isSniper(gun);
    }

    /**
     * Soft gravity from {@link GunDefinition#fallSpeed()} + light horizontal drag.
     * Terminal sink scales with fallSpeed so small values parachute slowly.
     */
    private void applyFallBallistics() {
        double fall = gun.fallSpeed();
        if (fall <= 0.0 && isFlareProjectile()) {
            fall = 0.01; // default slow parachute if YAML omitted fallSpeed
        }
        if (fall <= 0.0 && SniperBallistics.isSniper(gun)) {
            fall = SniperBallistics.DEFAULT_SNIPER_FALL;
        }
        if (fall <= 0.0) {
            return;
        }
        velocity.setY(velocity.getY() - fall);
        double terminal = Math.min(0.42, Math.max(0.045, fall * 8.5));
        if (velocity.getY() < -terminal) {
            velocity.setY(-terminal);
        }
        velocity.setX(velocity.getX() * 0.988);
        velocity.setZ(velocity.getZ() * 0.988);
        if (isFlareProjectile() && ticks % 3 == 0) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            velocity.setX(velocity.getX() + (rng.nextDouble() - 0.5) * 0.01);
            velocity.setZ(velocity.getZ() + (rng.nextDouble() - 0.5) * 0.01);
        }
    }

    /** Burning ember core + rising smoke for in-flight flares. */
    private void spawnFlareTrail(Location at) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        World world = at.getWorld();
        world.spawnParticle(Particle.FLAME, at, 3, 0.04, 0.04, 0.04, 0.01);
        world.spawnParticle(Particle.SMOKE, at, 2, 0.05, 0.08, 0.05, 0.01);
        if (ticks % 2 == 0) {
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at, 1, 0.06, 0.12, 0.06, 0.01);
        }
        if (ticks % 4 == 0) {
            world.spawnParticle(Particle.LAVA, at, 1, 0.02, 0.02, 0.02, 0);
        }
    }

    /**
     * Timed grenade / 40mm: bounce and roll on contact instead of detonating.
     * Converts the snowball into a dropped item so Paper can't silently kill the projectile.
     * @return true if the impact was handled (caller should not explode / remove)
     */
    public boolean tryRollImpact(BlockFace face) {
        if (released || dead || gun.explodeOnImpact()) {
            return false;
        }
        BlockFace hitFace = face != null && face.isCartesian() ? face : BlockFace.UP;
        Vector normal = hitFace.getDirection();
        Vector in = velocity.clone();
        if (projectile != null && projectile.isValid()) {
            Vector live = projectile.getVelocity();
            if (live != null && live.lengthSquared() > in.lengthSquared()) {
                in = live.clone();
            }
        }
        if (in.lengthSquared() < 1.0e-8) {
            in = new Vector(0, -0.35, 0);
        }
        double speed = Math.max(0.55, Math.sqrt(in.lengthSquared()));
        Vector reflected = LaserOptics.reflectDirection(in, normal);
        if (reflected.lengthSquared() < 1.0e-8) {
            reflected = new Vector(in.getX(), Math.abs(in.getY()) * 0.4 + 0.12, in.getZ());
        }

        boolean floor = hitFace == BlockFace.UP;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double keep = floor ? 0.82 : 0.55;
        Vector next = reflected.normalize().multiply(speed * keep);
        if (floor) {
            // Stronger hop + sideways tumble so it reads as a real bounce/roll
            next.setY(Math.min(0.55, 0.22 + Math.abs(in.getY()) * 0.45));
            next.setX(next.getX() + (rng.nextDouble() - 0.5) * 0.22);
            next.setZ(next.getZ() + (rng.nextDouble() - 0.5) * 0.22);
            double horiz = Math.hypot(next.getX(), next.getZ());
            if (horiz < 0.35) {
                double scale = 0.45 / Math.max(0.05, horiz);
                next.setX(next.getX() * scale);
                next.setZ(next.getZ() * scale);
            }
        } else {
            next.setY(Math.max(0.18, Math.abs(next.getY()) * 0.65));
        }

        Location at;
        if (projectile != null && projectile.isValid()) {
            at = projectile.getLocation().add(normal.clone().multiply(0.45));
        } else if (lastLocation != null) {
            at = lastLocation.clone().add(normal.clone().multiply(0.45));
        } else {
            at = startLocation.clone();
        }

        if (!spawnRollingItem(at, next)) {
            return false;
        }

        if (at.getWorld() != null) {
            at.getWorld().playSound(at, Sound.BLOCK_METAL_HIT, 0.7f, 1.2f + rng.nextFloat() * 0.35f);
            at.getWorld().playSound(at, Sound.ENTITY_IRON_GOLEM_STEP, 0.45f, 1.45f);
            at.getWorld().spawnParticle(Particle.CRIT, at, 8, 0.15, 0.08, 0.15, 0.03);
            at.getWorld().spawnParticle(Particle.SMOKE, at, 4, 0.1, 0.05, 0.1, 0.01);
        }
        return true;
    }

    private boolean spawnRollingItem(Location at, Vector bounceVel) {
        if (at == null || at.getWorld() == null || bounceVel == null) {
            return false;
        }
        Entity old = projectile;
        ItemStack visual = new ItemStack(Material.COAL);
        ItemMeta meta = visual.getItemMeta();
        if (meta != null) {
            meta.displayName(com.local.warz.runtime.ItemFactory.colorize("&840mm")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            visual.setItemMeta(meta);
        }
        org.bukkit.entity.Item item = at.getWorld().dropItem(at, visual);
        item.setPickupDelay(32767);
        item.setCanMobPickup(false);
        item.setUnlimitedLifetime(true);
        item.setGravity(true);
        item.setVelocity(bounceVel.clone());
        // Avoid stacking with other dropped coal while cooking
        try {
            item.setCanPlayerPickup(false);
        } catch (NoSuchMethodError ignored) {
            // older API
        }

        projectile = item;
        velocity.setX(bounceVel.getX());
        velocity.setY(bounceVel.getY());
        velocity.setZ(bounceVel.getZ());
        lastLocation = item.getLocation().clone();
        rollingItem = true;
        active = true;

        if (old != null && old.isValid() && !old.equals(item)) {
            old.remove();
        }
        return true;
    }

    /** Ground friction + little hops so the coal shell keeps tumbling until the fuse. */
    private void applyRollPhysics() {
        if (projectile == null || !projectile.isValid()) {
            return;
        }
        Vector v = projectile.getVelocity().clone();
        boolean grounded = projectile.isOnGround() || Math.abs(v.getY()) < 0.08;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (grounded) {
            v.setX(v.getX() * 0.94);
            v.setZ(v.getZ() * 0.94);
            double horiz = Math.hypot(v.getX(), v.getZ());
            if (horiz > 0.08) {
                // Occasional tiny hop while still sliding
                if (rng.nextInt(6) == 0) {
                    v.setY(0.12 + rng.nextDouble() * 0.12);
                } else {
                    v.setY(Math.max(v.getY(), 0.0));
                }
            } else if (horiz > 0.02) {
                // Nudge so it doesn't look glued in place
                v.setX(v.getX() + (rng.nextDouble() - 0.5) * 0.04);
                v.setZ(v.getZ() + (rng.nextDouble() - 0.5) * 0.04);
            }
        }
        projectile.setVelocity(v);
        velocity.setX(v.getX());
        velocity.setY(v.getY());
        velocity.setZ(v.getZ());
    }

    /**
     * Hand-thrown nades / flash / molotov: heavy floor friction and crushed bounce so they
     * land near where they hit instead of skating half a room.
     */
    private void applyThrowableSettle() {
        if (projectile == null || !projectile.isValid()) {
            return;
        }
        Vector v = projectile.getVelocity().clone();
        boolean grounded = projectile.isOnGround() || Math.abs(v.getY()) < 0.10;
        if (grounded) {
            // Kill rebound hops from vanilla item physics
            if (v.getY() > 0.0) {
                v.setY(v.getY() * 0.12);
            }
            v.setX(v.getX() * 0.62);
            v.setZ(v.getZ() * 0.62);
            if (Math.hypot(v.getX(), v.getZ()) < 0.09 && Math.abs(v.getY()) < 0.08) {
                v.setX(0.0);
                v.setY(0.0);
                v.setZ(0.0);
            }
            projectile.setVelocity(v);
            velocity.setX(v.getX());
            velocity.setY(v.getY());
            velocity.setZ(v.getZ());
        } else if (v.getY() < -0.15) {
            // Soften hard landings so the next bounce is smaller
            v.setX(v.getX() * 0.92);
            v.setZ(v.getZ() * 0.92);
            projectile.setVelocity(v);
        }
    }

    public void onHit() {
        if (released) {
            return;
        }
        released = true;
        if (projectile != null) {
            lastLocation = projectile.getLocation();
        }
        // Splash (flash / fire / explode / throwables) must run even if the thrower logged off.
        if (destroyWhenHit || gun.throwable() || gun.consumable()
                || gun.flashRadius() > 0 || gun.fireRadius() > 0 || gun.explodeRadius() > 0
                || (round != null && (round.explodeRadiusAdd() > 0 || round.fireRadiusAdd() > 0))) {
            ImpactEffects.apply(gun, getShooter(), lastLocation, round, plugin);
        }
    }

    public void remove() {
        dead = true;
        plugin.bullets().remove(this);
        Location dropAt = lastLocation;
        if (projectile != null) {
            dropAt = projectile.getLocation();
            projectile.remove();
            projectile = null;
        }
        if (!released) {
            onHit();
            if (lastLocation != null) {
                dropAt = lastLocation;
            }
        }
        spawnRemnant(dropAt);
        clearFlightChunks();
    }

    /** Keep long-range splash projectiles loaded past simulation-distance. */
    private void retainFlightChunks() {
        if (lastLocation == null || lastLocation.getWorld() == null || rollingItem) {
            return;
        }
        double max = round != null ? round.rangeFor(gun) : gun.maxDistance();
        boolean longRange = max > 96 || gun.explodeRadius() > 0;
        if (!longRange) {
            return;
        }
        // Wither / fireball rockets are the ones that vanish when chunks unload.
        String t = bulletType.toLowerCase(Locale.ROOT);
        if (!t.contains("wither") && !t.contains("fireball") && gun.explodeRadius() <= 0) {
            return;
        }
        World world = lastLocation.getWorld();
        if (ticketWorld != null && ticketWorld != world) {
            clearFlightChunks();
        }
        ticketWorld = world;
        int cx = lastLocation.getBlockX() >> 4;
        int cz = lastLocation.getBlockZ() >> 4;
        addFlightTicket(world, cx, cz);
        // One chunk ahead along velocity so the entity never steps into an unloaded chunk.
        if (velocity != null && velocity.lengthSquared() > 1.0e-6) {
            Vector ahead = velocity.clone().normalize().multiply(20.0);
            addFlightTicket(world,
                    (lastLocation.getBlockX() + (int) Math.round(ahead.getX())) >> 4,
                    (lastLocation.getBlockZ() + (int) Math.round(ahead.getZ())) >> 4);
        }
        // Drop tickets far behind the projectile (keep a short trail).
        if (chunkTickets.size() > 12) {
            pruneFlightTickets(cx, cz, 4);
        }
    }

    private void addFlightTicket(World world, int cx, int cz) {
        long key = packChunk(cx, cz);
        if (chunkTickets.add(key)) {
            world.addPluginChunkTicket(cx, cz, plugin);
        }
    }

    private void pruneFlightTickets(int keepCx, int keepCz, int radius) {
        if (ticketWorld == null) {
            return;
        }
        chunkTickets.removeIf(key -> {
            int cx = unpackChunkX(key);
            int cz = unpackChunkZ(key);
            if (Math.abs(cx - keepCx) <= radius && Math.abs(cz - keepCz) <= radius) {
                return false;
            }
            ticketWorld.removePluginChunkTicket(cx, cz, plugin);
            return true;
        });
    }

    private void clearFlightChunks() {
        if (ticketWorld != null) {
            for (long key : chunkTickets) {
                ticketWorld.removePluginChunkTicket(unpackChunkX(key), unpackChunkZ(key), plugin);
            }
        }
        chunkTickets.clear();
        ticketWorld = null;
    }

    private static long packChunk(int cx, int cz) {
        return (Integer.toUnsignedLong(cx) << 32) | Integer.toUnsignedLong(cz);
    }

    private static int unpackChunkX(long key) {
        return (int) (key >>> 32);
    }

    private static int unpackChunkZ(long key) {
        return (int) key;
    }

    private void spawnRemnant(Location location) {
        if (remnantSpawned || location == null || location.getWorld() == null || !gun.hasRemnant()) {
            return;
        }
        remnantSpawned = true;
        ItemStack stack;
        if (gun.remnantItem() == Material.GLASS_BOTTLE) {
            // Molotov leftover — tagged so it throws like an empty can
            stack = plugin.items().createBrokenGlassBottle(Math.max(1, gun.remnantAmount()));
        } else {
            stack = new ItemStack(gun.remnantItem(), Math.max(1, gun.remnantAmount()));
            if (gun.remnantName() != null && !gun.remnantName().isBlank()) {
                ItemMeta meta = stack.getItemMeta();
                meta.displayName(com.local.warz.runtime.ItemFactory.colorize(gun.remnantName())
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                stack.setItemMeta(meta);
            }
        }
        org.bukkit.entity.Item dropped = location.getWorld().dropItem(location, stack);
        dropped.setPickupDelay(Math.max(0, gun.remnantPickupDelay()));
        dropped.setVelocity(new Vector(0, 0.05, 0));
        int lifetime = gun.remnantLifetime();
        if (lifetime >= 0) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (dropped.isValid() && !dropped.isDead()) {
                    dropped.remove();
                }
            }, lifetime);
        }
    }

    public void setNextTickDestroy() {
        destroyNextTick = true;
    }

    public void setStuckTo(LivingEntity entity) {
        this.stuckTo = entity;
    }

    public boolean destroyWhenHit() {
        return destroyWhenHit;
    }

    public GunDefinition gun() {
        return gun;
    }

    public Entity getProjectile() {
        return projectile;
    }

    /**
     * Grenade Item entities: break tactical / vanilla glass along the last tick of travel
     * and push past the cell so physics doesn't stall the fuse path.
     */
    private void smashGlassAlongSegment(Location from, Location to) {
        if (from == null || to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return;
        }
        Vector delta = to.toVector().subtract(from.toVector());
        double dist = delta.length();
        if (dist < 0.05) {
            return;
        }
        Vector step = delta.clone().normalize().multiply(0.35);
        int steps = Math.min(24, (int) Math.ceil(dist / 0.35) + 1);
        Location cursor = from.clone();
        for (int i = 0; i < steps; i++) {
            Block block = cursor.getBlock();
            boolean smashed = false;
            if (plugin.glass() != null && plugin.glass().isTacticalGlass(block)) {
                smashed = plugin.glass().smashThroughForThrowable(this, block, BlockFace.NORTH);
            } else if (GlassService.breakVanillaGlass(block)) {
                smashed = true;
                if (projectile != null && projectile.isValid()) {
                    Vector dir = velocity.clone();
                    if (dir.lengthSquared() < 1.0e-8) {
                        dir = step.clone();
                    }
                    dir.normalize();
                    projectile.teleport(block.getLocation().add(0.5, 0.5, 0.5).add(dir.multiply(1.05)));
                    projectile.setVelocity(velocity.clone());
                }
            }
            if (smashed) {
                reassertVelocity();
            }
            cursor.add(step);
        }
    }

    /** Multiply in-flight velocity (e.g. energy loss punching glass). */
    public void scaleVelocity(double factor) {
        if (factor <= 0 || !Double.isFinite(factor)) {
            return;
        }
        velocity.multiply(Math.max(0.05, Math.min(1.5, factor)));
        if (projectile != null && projectile.isValid()) {
            projectile.setVelocity(velocity.clone());
        }
    }

    public boolean canRicochetMore() {
        return ricochetCount < MAX_RICOCHETS;
    }

    /** Redirect after a shallow metal/hard ricochet; keeps speed scaled by {@code retain}. */
    public void applyRicochet(Vector reflectedDir, double retain) {
        if (reflectedDir == null || reflectedDir.lengthSquared() < 1.0e-12) {
            return;
        }
        ricochetCount++;
        double speed = Math.sqrt(velocity.lengthSquared());
        if (speed < 1.0e-6) {
            speed = 1.0;
        }
        double keep = Math.max(0.2, Math.min(1.0, retain));
        Vector next = reflectedDir.clone().normalize().multiply(speed * keep);
        velocity.setX(next.getX());
        velocity.setY(next.getY());
        velocity.setZ(next.getZ());
        if (projectile != null && projectile.isValid()) {
            projectile.setVelocity(velocity.clone());
        }
    }

    /**
     * After a ricochet redirect: raycast along the new velocity for living targets.
     * Paper still kills snowballs on the bounce cell even when the hit event is cancelled.
     */
    public boolean continueAfterRicochet(Block bounced) {
        if (dead || bounced == null || bounced.getWorld() == null) {
            return false;
        }
        // Mark for kill-feed even if the follow-up ray misses — applyRicochet already bumped count.
        Vector dir = velocity.clone();
        if (dir.lengthSquared() < 1.0e-8) {
            return false;
        }
        dir.normalize();
        Location start = bounced.getLocation().add(0.5, 0.5, 0.5).add(dir.clone().multiply(0.9));
        double travelled = startLocation != null && startLocation.getWorld() != null
                && startLocation.getWorld().equals(start.getWorld())
                ? start.distance(startLocation) : 0;
        double maxRange = round != null ? round.rangeFor(gun) : gun.maxDistance();
        double remaining = Math.max(4.0, maxRange - travelled);
        BallisticsProfile shot = BallisticsProfile.of(this);
        UUID shooterUuid = shooterId;

        RayTraceResult hit = LaserBeams.rayTraceIgnoring(
                start,
                dir,
                remaining,
                0.55,
                entity -> entity instanceof LivingEntity
                        && entity.isValid()
                        && !entity.getUniqueId().equals(shooterUuid)
                        && !damagedEntities.contains(entity.getUniqueId()),
                block -> shouldSkipPierceBlock(block, bounced, shot)
        );
        if (hit == null || !(hit.getHitEntity() instanceof LivingEntity living)) {
            if (projectile != null && projectile.isValid()) {
                projectile.teleport(start.clone().add(dir.clone().multiply(0.4)));
                reassertVelocity();
            }
            return false;
        }
        Location at = hit.getHitPosition() != null
                ? hit.getHitPosition().toLocation(living.getWorld())
                : living.getLocation().add(0, living.getHeight() * 0.6, 0);
        return hitEntity(living, at);
    }

    public boolean isSuppressed() {
        return suppressed;
    }

    public void ignorePierceKey(String key, int ticks) {
        this.pierceIgnoreKey = key;
        this.pierceIgnoreTicks = Math.max(0, ticks);
    }

    public boolean ignoresPierce(String key) {
        return pierceIgnoreTicks > 0 && pierceIgnoreKey != null && pierceIgnoreKey.equals(key);
    }

    /** Re-apply stored velocity (vanilla sometimes zeroes projectiles on block hit). */
    public void reassertVelocity() {
        if (projectile != null && projectile.isValid() && velocity.lengthSquared() > 1.0e-8) {
            projectile.setVelocity(velocity.clone());
        }
    }

    /**
     * Paper still stops snowballs on block collision even when ProjectileHitEvent is cancelled.
     * After glass / soft-cover pierce, raycast for living targets past that cell and apply damage.
     * Skips further soft (and pen-able hard) cover along the path — not just the first block.
     */
    public boolean continueAfterPierce(Block pierced) {
        if (dead || pierced == null || pierced.getWorld() == null) {
            return false;
        }
        this.wallbang = true;
        if (plugin.glass() != null && plugin.glass().isTacticalGlass(pierced)) {
            this.throughGlass = true;
        }
        Vector dir = velocity.clone();
        if (dir.lengthSquared() < 1.0e-8) {
            return false;
        }
        dir.normalize();
        // Start just past the far face so we don't re-collide the cover cell we already punched.
        Location start = pierced.getLocation().add(0.5, 0.5, 0.5).add(dir.clone().multiply(0.72));
        double travelled = startLocation != null && startLocation.getWorld() != null
                && startLocation.getWorld().equals(start.getWorld())
                ? start.distance(startLocation) : 0;
        double maxRange = round != null ? round.rangeFor(gun) : gun.maxDistance();
        double remaining = Math.max(3.0, maxRange - travelled);
        BallisticsProfile shot = BallisticsProfile.of(this);
        UUID shooterUuid = shooterId;

        RayTraceResult hit = LaserBeams.rayTraceIgnoring(
                start,
                dir,
                remaining,
                0.55,
                entity -> entity instanceof LivingEntity
                        && entity.isValid()
                        && !entity.getUniqueId().equals(shooterUuid)
                        && !damagedEntities.contains(entity.getUniqueId()),
                block -> shouldSkipPierceBlock(block, pierced, shot)
        );
        if (hit == null || !(hit.getHitEntity() instanceof LivingEntity living)) {
            // Keep the round flying if vanilla left the projectile alive
            if (projectile != null && projectile.isValid()) {
                Location to = start.clone().add(dir.clone().multiply(0.35));
                projectile.teleport(to);
                reassertVelocity();
            }
            return false;
        }
        Location at = hit.getHitPosition() != null
                ? hit.getHitPosition().toLocation(living.getWorld())
                : living.getLocation().add(0, living.getHeight() * 0.6, 0);
        return hitEntity(living, at);
    }

    /** Blocks the pierce ray may pass through after the initial cover/glass hit. */
    private boolean shouldSkipPierceBlock(Block block, Block pierced, BallisticsProfile shot) {
        if (block == null) {
            return false;
        }
        if (LaserBeams.isFoliage(block)) {
            return true;
        }
        if (pierced != null
                && block.getX() == pierced.getX()
                && block.getY() == pierced.getY()
                && block.getZ() == pierced.getZ()
                && block.getWorld().equals(pierced.getWorld())) {
            return true;
        }
        if (pierceIgnoreKey != null && pierceIgnoreKey.equals(GlassService.key(block))) {
            return true;
        }
        CoverService.Kind kind = CoverService.classify(block);
        if (kind == CoverService.Kind.SOFT && shot.penetratesSoft()) {
            // Extra soft layers eat energy but don't stop the pierce ray
            scaleVelocity(Math.min(1.0, shot.softRetain() + 0.08));
            ignorePierceKey(GlassService.key(block), 6);
            return true;
        }
        if (kind == CoverService.Kind.HARD && shot.penetratesHard()) {
            scaleVelocity(shot.hardRetain());
            ignorePierceKey(GlassService.key(block), 4);
            return true;
        }
        if (plugin.glass() != null && plugin.glass().isTacticalGlass(block)) {
            GlassType type = plugin.glass().typeAt(block);
            if (type != null && (type.shatter() == GlassType.Shatter.INSTANT
                    || type.shatter() == GlassType.Shatter.DICE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Apply gun damage / knockback / round effects. Shared by projectile damage events and glass pierce.
     */
    public boolean hitEntity(LivingEntity hurt, Location hitAt) {
        if (dead || hurt == null || !hurt.isValid()) {
            return false;
        }
        if (!damagedEntities.add(hurt.getUniqueId())) {
            return false;
        }
        Player shooter = getShooter();
        if (shooter == null) {
            return false;
        }
        Location at = hitAt != null ? hitAt : hurt.getLocation().add(0, hurt.getHeight() * 0.6, 0);
        // MQ-9 airframe: structure damage, never kill the invisible pilot
        if (hurt instanceof Player pilot
                && plugin.bigDrone() != null
                && plugin.bigDrone().isPiloting(pilot)) {
            boolean hit = plugin.bigDrone().absorbBulletHit(pilot, shooter, at, gun, round);
            if (hit && destroyWhenHit && !"crossbow".equalsIgnoreCase(bulletType)) {
                onHit();
                remove();
            }
            return hit;
        }
        boolean headshot = ImpactEffects.isHeadshot(at, hurt, gun.canHeadshot());
        double dmg = damage() * (headshot ? 2.0 : 1.0);
        int armorPen = gun.armorPenetration();
        if (round != null) {
            armorPen += round.armorPenAdd();
        }
        dmg += Math.max(0, armorPen);
        // SPAS: one close-range blast should drop a zombie even if only one pellet lands.
        if (isSpas(gun) && isHordeUndead(hurt)) {
            Location from = fireEyeLocation != null ? fireEyeLocation : startLocation;
            if (from != null && from.getWorld() != null && from.getWorld().equals(at.getWorld())
                    && from.distance(at) <= 6.5) {
                double maxHp = 20.0;
                if (hurt.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null) {
                    maxHp = hurt.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                }
                dmg = Math.max(dmg, maxHp + 8.0);
            }
        }
        if (gun.resetHitCooldown()) {
            hurt.setNoDamageTicks(0);
        }
        if (hurt instanceof Player hitPlayer && plugin.medical() != null) {
            plugin.medical().flagBulletWound(hitPlayer.getUniqueId());
        }
        if (hurt instanceof Player hitPlayer && plugin.killFeed() != null) {
            var kind = headshot
                    ? com.local.warz.runtime.KillFeedService.HitKind.HEADSHOT
                    : (gun.throwable()
                    ? com.local.warz.runtime.KillFeedService.HitKind.THROWABLE
                    : com.local.warz.runtime.KillFeedService.HitKind.BULLET);
            double range = 0.0;
            Location from = fireEyeLocation != null ? fireEyeLocation : startLocation;
            if (from != null && from.getWorld() != null && from.getWorld().equals(at.getWorld())) {
                range = from.distance(at);
            }
            KillFeedService.ShotContext ctx = new KillFeedService.ShotContext();
            ctx.aimed = aimedAtFire;
            ctx.rangeBlocks = range;
            ctx.fallDistance = fallDistanceAtFire;
            ctx.suppressed = suppressed;
            ctx.wallbang = wallbang;
            ctx.throughGlass = throughGlass;
            ctx.ricochet = ricochetCount > 0;
            if (round != null) {
                ctx.roundId = round.fileName();
            }
            if (plugin.bigDrone() != null && plugin.bigDrone().isPiloting(shooter)) {
                ctx.fromDrone = true;
            }
            plugin.killFeed().record(hitPlayer, shooter, gun, kind, weaponSnapshot, ctx);
        }
        // Fire DoT i-frames otherwise swallow gunshot damage (finalDamage=0 → no bleed).
        hurt.setNoDamageTicks(0);
        applyAttributedDamage(hurt, Math.ceil(dmg), shooter);
        ImpactEffects.knockback(gun, hurt, velocity, knockbackStrength());
        applyRoundHitEffects(hurt);
        hurt.setNoDamageTicks(0);
        if (gun.resetHitCooldown()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (hurt.isValid()) {
                    hurt.setNoDamageTicks(0);
                }
            });
        }
        if (headshot && shooter.getGameMode() != GameMode.SPECTATOR) {
            shooter.playSound(shooter.getLocation(), Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.7f, 1.4f);
        }
        if ("crossbow".equalsIgnoreCase(bulletType)) {
            setStuckTo(hurt);
        } else if (destroyWhenHit) {
            onHit();
            remove();
        }
        return true;
    }

    public boolean alreadyDamaged(UUID entityId) {
        return entityId != null && damagedEntities.contains(entityId);
    }

    private static boolean isSpas(GunDefinition gun) {
        if (gun == null || gun.fileName() == null) {
            return false;
        }
        return gun.fileName().toLowerCase(java.util.Locale.ROOT).contains("spas");
    }

    private static boolean isHordeUndead(LivingEntity entity) {
        if (entity == null || entity instanceof Player) {
            return false;
        }
        if (entity instanceof org.bukkit.entity.Zombie) {
            return true;
        }
        String type = entity.getType().name();
        return type.contains("ZOMBIE") || type.equals("HUSK") || type.equals("PARCHED")
                || type.equals("DROWNED");
    }

    public String bulletType() {
        return bulletType;
    }

    public Vector velocity() {
        return velocity;
    }

    public Player getShooter() {
        return plugin.getServer().getPlayer(shooterId);
    }

    public boolean isDead() {
        return dead;
    }
}
