package com.local.warz.combat;

import com.local.warz.model.GunDefinition;
import com.local.warz.model.RoundDefinition;
import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.runtime.LaserCompanionBridge;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public final class ImpactEffects {
    /**
     * Ground flame sheet. This ran for forty-five seconds, re-drawing flames and
     * re-igniting anything standing in them every ten ticks, which is what made a
     * dragon's breath shot leave a burning patch long after the fight moved on.
     * A shot now flares and is gone.
     */
    private static final int FIRE_LINGER_TICKS = 20;
    private static final int FIRE_REFRESH_PERIOD = 5;
    private static final int ENTITY_FIRE_TICKS = 20 * 14;
    /**
     * Classic molotov flame cloud (old gun config):
     * {@code 6.0,0.1,200,FLAME,count=5,offset=2:0.4:2,speed=0.0}
     */
    private static final double CLASSIC_FLAME_Y = 0.1;
    /** Dense flame cloud. Was 200 ticks - ten seconds of standing fire. */
    private static final int CLASSIC_FLAME_DURATION_TICKS = 20;
    private static final int CLASSIC_FLAME_PERIOD_TICKS = 2; // 0.1s
    private static final int CLASSIC_FLAME_COUNT = 5;
    private static final double CLASSIC_FLAME_OX = 2.0;
    private static final double CLASSIC_FLAME_OY = 0.4;
    private static final double CLASSIC_FLAME_OZ = 2.0;
    private static final double CLASSIC_FLAME_SPEED = 0.0;
    /**
     * Smoke outlives the flames on purpose - a shot leaves a puff hanging rather
     * than vanishing outright - but not by twenty seconds.
     */
    private static final int FIRE_SMOKE_DURATION_TICKS = 20 * 4;
    private static final int FIRE_SMOKE_PERIOD_TICKS = 3;
    /** Within this distance, LOS is skipped (floor rays false-negative); facing still applies. */
    private static final double FLASH_CLOSE_ALWAYS = 2.75;
    /**
     * look · toFlash. Above this = full whiteout; between peripheral and this = partial;
     * below peripheral = looking away (no blindness).
     */
    private static final double FLASH_FULL_DOT = 0.20;
    private static final double FLASH_PERIPH_DOT = -0.15;
    /** Keep NVG/thermal from stripping whiteout / post-flash vision effects. */
    private static final Map<UUID, Long> FLASH_PROTECT_UNTIL_MS = new ConcurrentHashMap<>();

    private ImpactEffects() {
    }

    /** True while a flashbang / blast shock is driving this player's vision effects. */
    public static boolean isFlashProtected(Player player) {
        if (player == null) {
            return false;
        }
        Long until = FLASH_PROTECT_UNTIL_MS.get(player.getUniqueId());
        if (until == null) {
            return false;
        }
        if (until <= System.currentTimeMillis()) {
            FLASH_PROTECT_UNTIL_MS.remove(player.getUniqueId(), until);
            return false;
        }
        return true;
    }

    /** Keep NVG/thermal from stripping Darkness / Blindness for {@code durationMs}. */
    public static void protectVision(Player player, long durationMs) {
        if (player == null || durationMs <= 0) {
            return;
        }
        long until = System.currentTimeMillis() + durationMs;
        FLASH_PROTECT_UNTIL_MS.merge(player.getUniqueId(), until, Math::max);
    }

    public static void apply(GunDefinition gun, Player shooter, Location impact) {
        apply(gun, shooter, impact, null, null);
    }

    public static void apply(GunDefinition gun, Player shooter, Location impact, RoundDefinition round) {
        apply(gun, shooter, impact, round, null);
    }

    public static void apply(GunDefinition gun, Player shooter, Location impact,
                             RoundDefinition round, Plugin plugin) {
        if (impact == null || impact.getWorld() == null) {
            return;
        }
        explode(gun, shooter, impact, round, plugin);
        fireSpread(gun, shooter, impact, round, plugin);
        flash(gun, shooter, impact, plugin);
    }

    private static void explode(GunDefinition gun, Player shooter, Location impact,
                                RoundDefinition round, Plugin plugin) {
        double radius = gun.explodeRadius() + (round != null ? round.explodeRadiusAdd() : 0);
        if (radius <= 0) {
            return;
        }
        impact.getWorld().playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1.2f);
        impact.getWorld().spawnParticle(Particle.EXPLOSION, impact.clone().add(0, 0.5, 0), 2, 0.2, 0.2, 0.2, 0);
        // Allow larger visual blasts for big-radius guns (e.g. drone LAW).
        float power = (float) Math.min(7.0, Math.max(1.0, radius));
        boolean breakBlocks = gun.isLaser() || radius >= 2.0;
        // Set once the plugin's own carve has taken the terrain.
        boolean carvedTerrain = false;
        // Shock envelope is independent of crater size (blastShockRadius / Strength on the gun).
        double shockRadius = gun.blastShockRadius() > 0 ? gun.blastShockRadius()
                : (radius >= 6.0 ? 24.0 : 20.0);
        double shockStrength = gun.blastShockStrength() > 0 ? gun.blastShockStrength()
                : (radius >= 6.0 ? 1.35 : 1.0);
        if (plugin instanceof WarzPlugin warz && warz.blastShock() != null) {
            warz.blastShock().apply(impact.clone(), shockRadius, shockStrength);
        } else if (plugin != null) {
            var warzPlug = plugin.getServer().getPluginManager().getPlugin("WarZ");
            if (warzPlug instanceof WarzPlugin warz && warz.blastShock() != null) {
                warz.blastShock().apply(impact.clone(), shockRadius, shockStrength);
            }
        }
        // Never pass shooter as source — Paper can pin EntityExplodeEvent to the firer's body.
        // CardForge MOB+null explosions do not break blocks; carve the crater ourselves.
        if (breakBlocks) {
            WarzPlugin warzRegen = plugin instanceof WarzPlugin w ? w : null;
            if (warzRegen == null && plugin != null) {
                var plug = plugin.getServer().getPluginManager().getPlugin("WarzPlugin");
                if (plug instanceof WarzPlugin w) {
                    warzRegen = w;
                }
            }
            if (warzRegen != null && warzRegen.explosionRegen() != null) {
                warzRegen.explosionRegen().blastTerrain(impact, Math.max(power, radius));
                carvedTerrain = true;
            }
        }
        // The vanilla explosion is here for its damage, knockback and particles.
        // It must not break blocks as well: the carve above has already taken the
        // terrain, dropless, and letting vanilla break it a second time is what
        // scattered the crater across the ground as items.
        impact.getWorld().createExplosion(impact, power, false, breakBlocks && !carvedTerrain, null);
        // Companion FLIR / drone white-hot & black-hot — vanilla explode packets don't reach altitude.
        if (plugin instanceof WarzPlugin warzHeat && warzHeat.laserBridge() != null) {
            warzHeat.laserBridge().broadcastThermalBlast(impact.clone().add(0, 0.35, 0), (float) radius);
        } else if (plugin != null) {
            var warzPlug = plugin.getServer().getPluginManager().getPlugin("WarZ");
            if (warzPlug instanceof WarzPlugin warzHeat && warzHeat.laserBridge() != null) {
                warzHeat.laserBridge().broadcastThermalBlast(impact.clone().add(0, 0.35, 0), (float) radius);
            }
        }
        if (radius >= 6.0) {
            impact.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, impact.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0);
            impact.getWorld().spawnParticle(Particle.EXPLOSION, impact.clone().add(0, 0.5, 0), 8, 1.2, 0.6, 1.2, 0.05);
        }

        int damage = gun.explosionDamage() >= 0 ? gun.explosionDamage() : gun.gunDamage();
        if (round != null) {
            damage = Math.max(0, (int) Math.round(damage * round.damageMult()));
        }
        // UAV airframes (airborne + parked) take structure damage from bomb/rocket splash.
        if (plugin instanceof WarzPlugin warzDrone && warzDrone.bigDrone() != null) {
            warzDrone.bigDrone().absorbExplosionSplash(impact, radius, shooter, gun, round);
        } else if (plugin != null) {
            var plug = plugin.getServer().getPluginManager().getPlugin("WarZ");
            if (plug instanceof WarzPlugin warzDrone && warzDrone.bigDrone() != null) {
                warzDrone.bigDrone().absorbExplosionSplash(impact, radius, shooter, gun, round);
            }
        }
        for (Entity entity : impact.getWorld().getNearbyEntities(impact, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || skipSplashTarget(living, shooter, gun)) {
                continue;
            }
            if (!hasLos(impact.clone().add(0, 0.55, 0), living.getEyeLocation())) {
                continue;
            }
            if (gun.resetHitCooldown()) {
                living.setNoDamageTicks(0);
            }
            creditKill(plugin, shooter, living, gun,
                    gun.throwable()
                            ? com.local.warz.runtime.KillFeedService.HitKind.THROWABLE
                            : com.local.warz.runtime.KillFeedService.HitKind.EXPLODE);
            com.local.warz.projectile.Bullet.applyAttributedDamage(living, damage, shooter);
            if (round != null && round.setFireTicks() > 0) {
                living.setFireTicks(Math.max(living.getFireTicks(), round.setFireTicks()));
            }
            if (gun.resetHitCooldown()) {
                living.setNoDamageTicks(0);
            }
        }
    }

    private static void fireSpread(GunDefinition gun, Player shooter, Location impact,
                                   RoundDefinition round, Plugin plugin) {
        double radius = gun.fireRadius() + (round != null ? round.fireRadiusAdd() : 0);
        if (radius <= 0) {
            return;
        }
        World world = impact.getWorld();
        world.playSound(impact, Sound.ITEM_FLINTANDSTEEL_USE, 1.15f, 0.75f);
        world.playSound(impact, Sound.BLOCK_FIRE_AMBIENT, 1.25f, 0.85f);
        world.playSound(impact, Sound.ENTITY_GENERIC_BURN, 0.9f, 0.7f);

        // Burst — flames shoot outward, then settle on the ground
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location burstAt = impact.clone().add(0, 0.15, 0);
        for (int i = 0; i < 48; i++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            double speed = 0.18 + rng.nextDouble() * 0.55;
            double up = 0.08 + rng.nextDouble() * 0.42;
            world.spawnParticle(Particle.FLAME, burstAt,
                    0, Math.cos(angle) * speed, up, Math.sin(angle) * speed, 1.0);
        }
        for (int i = 0; i < 18; i++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            double speed = 0.12 + rng.nextDouble() * 0.35;
            world.spawnParticle(Particle.LAVA, burstAt,
                    0, Math.cos(angle) * speed, 0.2 + rng.nextDouble() * 0.25, Math.sin(angle) * speed, 0.08);
        }
        world.spawnParticle(Particle.EXPLOSION, impact.clone().add(0, 0.2, 0), 1, 0, 0, 0, 0);
        // Initial smoke mushroom — thick, already rising
        spawnFireSmokeColumn(world, impact, radius, 1.0f, rng, true);

        igniteNearby(gun, shooter, impact, round, radius, plugin);

        Plugin scheduler = plugin != null ? plugin : Bukkit.getPluginManager().getPlugin("WarZ");
        if (scheduler == null) {
            return;
        }
        Location center = impact.clone();

        // Classic dense FLAME cloud (old: 6.0,0.1,200,FLAME,count=5,offset=2:0.4:2,speed=0.0)
        Location flameCore = center.clone().add(0, CLASSIC_FLAME_Y, 0);
        AtomicInteger classicElapsed = new AtomicInteger(0);
        Bukkit.getScheduler().runTaskTimer(scheduler, task -> {
            int t = classicElapsed.addAndGet(CLASSIC_FLAME_PERIOD_TICKS);
            if (t > CLASSIC_FLAME_DURATION_TICKS || flameCore.getWorld() == null) {
                task.cancel();
                return;
            }
            // Center plume
            world.spawnParticle(Particle.FLAME, flameCore,
                    CLASSIC_FLAME_COUNT, CLASSIC_FLAME_OX, CLASSIC_FLAME_OY, CLASSIC_FLAME_OZ,
                    CLASSIC_FLAME_SPEED, null, true);
            // Fill out to fireRadius (~6) with the same style
            int fills = Math.max(1, (int) Math.round(radius / 2.5));
            for (int n = 0; n < fills; n++) {
                double ang = rng.nextDouble() * Math.PI * 2.0;
                double dist = rng.nextDouble() * radius;
                Location spot = center.clone().add(
                        Math.cos(ang) * dist, CLASSIC_FLAME_Y, Math.sin(ang) * dist);
                world.spawnParticle(Particle.FLAME, spot,
                        CLASSIC_FLAME_COUNT, CLASSIC_FLAME_OX, CLASSIC_FLAME_OY, CLASSIC_FLAME_OZ,
                        CLASSIC_FLAME_SPEED, null, true);
            }
        }, 0L, CLASSIC_FLAME_PERIOD_TICKS);

        // Rising smoke column above the flames (peaks early, then thins)
        AtomicInteger smokeElapsed = new AtomicInteger(0);
        Bukkit.getScheduler().runTaskTimer(scheduler, task -> {
            int t = smokeElapsed.addAndGet(FIRE_SMOKE_PERIOD_TICKS);
            if (t > FIRE_SMOKE_DURATION_TICKS || center.getWorld() == null) {
                task.cancel();
                return;
            }
            float life = t / (float) FIRE_SMOKE_DURATION_TICKS;
            // Heavy while flames are hot, then a long soft tail
            float intensity;
            if (life < 0.25f) {
                intensity = 0.85f + life * 0.6f;
            } else if (life < 0.55f) {
                intensity = 1.0f;
            } else {
                intensity = Math.max(0.18f, 1.0f - (life - 0.55f) / 0.45f);
            }
            spawnFireSmokeColumn(world, center, radius, intensity, rng, false);
        }, FIRE_SMOKE_PERIOD_TICKS, FIRE_SMOKE_PERIOD_TICKS);

        // Longer ground-sheet linger (particles only — no FIRE blocks)
        AtomicInteger elapsed = new AtomicInteger(0);
        Bukkit.getScheduler().runTaskTimer(scheduler, task -> {
            int t = elapsed.addAndGet(FIRE_REFRESH_PERIOD);
            if (t > FIRE_LINGER_TICKS || center.getWorld() == null) {
                task.cancel();
                return;
            }
            for (int i = -((int) radius); i <= radius; i++) {
                for (int k = -((int) radius); k <= radius; k++) {
                    if (i * i + k * k > radius * radius) {
                        continue;
                    }
                    Location nloc = center.clone().add(i + 0.5, 0.12, k + 0.5);
                    world.spawnParticle(Particle.FLAME, nloc, 2, 0.2, 0.05, 0.2, 0.005, null, true);
                }
            }
            if (t % 40 == 0) {
                world.playSound(center, Sound.BLOCK_FIRE_AMBIENT, 0.55f, 0.9f + rng.nextFloat() * 0.2f);
            }
            igniteNearby(gun, shooter, center, round, radius * 0.85, plugin);
        }, FIRE_REFRESH_PERIOD, FIRE_REFRESH_PERIOD);
    }

    /**
     * Layered smoke above ground flames: dark base wisps, rising campfire plume, high soft billows.
     * {@code intensity} 0…1+ scales density; birth burst adds an initial mushroom.
     */
    private static void spawnFireSmokeColumn(World world, Location center, double radius,
                                             float intensity, ThreadLocalRandom rng, boolean birthBurst) {
        if (world == null || center == null || intensity <= 0.05f) {
            return;
        }
        float i = Math.max(0.1f, Math.min(1.4f, intensity));
        double r = Math.max(1.5, radius);

        // Base — grey smoke peeling off the flame tops
        Location base = center.clone().add(0, 0.55, 0);
        int baseCount = Math.max(2, (int) Math.round(6 * i));
        world.spawnParticle(Particle.LARGE_SMOKE, base,
                baseCount, r * 0.45, 0.18, r * 0.45, 0.01, null, true);
        world.spawnParticle(Particle.SMOKE, base,
                Math.max(3, (int) Math.round(10 * i)), r * 0.55, 0.25, r * 0.55, 0.02, null, true);

        // Mid column — campfire smoke that actually rises
        int columns = birthBurst ? 10 : Math.max(3, (int) Math.round(4 + r * 0.7 * i));
        for (int n = 0; n < columns; n++) {
            double ang = rng.nextDouble() * Math.PI * 2.0;
            double dist = Math.sqrt(rng.nextDouble()) * r * (birthBurst ? 0.75 : 0.85);
            double y = 0.7 + rng.nextDouble() * 1.1;
            Location at = center.clone().add(Math.cos(ang) * dist, y, Math.sin(ang) * dist);
            double up = 0.06 + rng.nextDouble() * 0.10 * i;
            double driftX = (rng.nextDouble() - 0.5) * 0.03;
            double driftZ = (rng.nextDouble() - 0.5) * 0.03;
            // count=0 → directional particle (velocity from ox/oy/oz)
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at,
                    0, driftX, up, driftZ, 1.0, null, true);
            if (rng.nextFloat() < 0.35f * i) {
                world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, at.clone().add(0, 0.4, 0),
                        0, driftX * 0.6, up * 1.15, driftZ * 0.6, 1.0, null, true);
            }
        }

        // High soft billow — wider, slower, sits clearly above the fire
        Location high = center.clone().add(0, 2.2 + rng.nextDouble() * 0.8, 0);
        int highCount = Math.max(1, (int) Math.round((birthBurst ? 8 : 3) * i));
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, high,
                highCount, r * 0.7, 0.55, r * 0.7, 0.0, null, true);
        if (birthBurst || rng.nextFloat() < 0.45f * i) {
            world.spawnParticle(Particle.LARGE_SMOKE, high.clone().add(0, 0.6, 0),
                    Math.max(1, highCount / 2), r * 0.85, 0.4, r * 0.85, 0.005, null, true);
        }
    }

    private static void igniteNearby(GunDefinition gun, Player shooter, Location impact,
                                     RoundDefinition round, double radius, Plugin plugin) {
        for (Entity entity : impact.getWorld().getNearbyEntities(impact, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || skipSplashTarget(living, shooter, gun)) {
                continue;
            }
            if (!hasLos(living.getLocation().add(0, 0.5, 0), impact.clone().add(0, 0.5, 0))) {
                continue;
            }
            int fireTicks = round != null && round.setFireTicks() > 0 ? round.setFireTicks() : ENTITY_FIRE_TICKS;
            living.setFireTicks(Math.max(living.getFireTicks(), fireTicks));
            if (gun.resetHitCooldown()) {
                living.setNoDamageTicks(0);
            }
            creditKill(plugin, shooter, living, gun, com.local.warz.runtime.KillFeedService.HitKind.FIRE);
            com.local.warz.projectile.Bullet.applyAttributedDamage(living, 1, shooter);
            if (gun.resetHitCooldown()) {
                living.setNoDamageTicks(0);
            }
        }
    }

    private static void creditKill(Plugin plugin, Player shooter, LivingEntity living,
                                   GunDefinition gun, com.local.warz.runtime.KillFeedService.HitKind kind) {
        if (!(living instanceof Player victim) || shooter == null || gun == null || kind == null) {
            return;
        }
        WarzPlugin warz = null;
        if (plugin instanceof WarzPlugin wp) {
            warz = wp;
        } else if (plugin != null) {
            var p = plugin.getServer().getPluginManager().getPlugin("WarZ");
            if (p instanceof WarzPlugin wp) {
                warz = wp;
            }
        } else {
            var p = Bukkit.getPluginManager().getPlugin("WarZ");
            if (p instanceof WarzPlugin wp) {
                warz = wp;
            }
        }
        if (warz == null || warz.killFeed() == null) {
            return;
        }
        ItemStack hand = shooter.getInventory().getItemInMainHand();
        com.local.warz.runtime.KillFeedService.ShotContext ctx =
                new com.local.warz.runtime.KillFeedService.ShotContext();
        if (warz.sessions() != null) {
            var session = warz.sessions().get(shooter);
            if (session != null) {
                ctx.aimed = session.isAimedIn();
            }
        }
        if (shooter.getWorld().equals(victim.getWorld())) {
            ctx.rangeBlocks = shooter.getEyeLocation().distance(victim.getEyeLocation());
        }
        ctx.fallDistance = shooter.getFallDistance();
        if (warz.items() != null) {
            ctx.suppressed = warz.items().hasSuppressor(hand);
        }
        if (warz.bigDrone() != null && warz.bigDrone().isPiloting(shooter)) {
            ctx.fromDrone = true;
        }
        warz.killFeed().record(victim, shooter, gun, kind, hand, ctx);
    }

    private static void flash(GunDefinition gun, Player shooter, Location impact, Plugin plugin) {
        if (gun.flashRadius() <= 0) {
            return;
        }
        Location flashOrigin = impact.clone().add(0.0, 0.35, 0.0);
        World world = impact.getWorld();
        world.playSound(flashOrigin, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 1.35f, 1.95f);
        world.playSound(flashOrigin, Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.PLAYERS, 1.4f, 2.0f);
        world.playSound(flashOrigin, Sound.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.PLAYERS, 0.55f, 1.7f);

        Plugin scheduler = plugin != null ? plugin : Bukkit.getPluginManager().getPlugin("WarZ");
        spawnFlashbangDetonation(world, flashOrigin, scheduler);

        double radius = gun.flashRadius();
        for (Entity entity : world.getNearbyEntities(impact, radius, radius + 2.0, radius)) {
            if (!(entity instanceof LivingEntity living) || skipSplashTarget(living, shooter, gun)) {
                continue;
            }
            if (!canFlashReach(living, flashOrigin)) {
                continue;
            }
            double facing = flashFacingDot(living, flashOrigin);
            // Looking away — hear the bang (world sounds) but no vision whiteout.
            if (facing < FLASH_PERIPH_DOT) {
                if (living instanceof Player turned) {
                    applyFlashbangGlanceAway(turned);
                }
                continue;
            }
            float severity = facing >= FLASH_FULL_DOT ? 1.0f
                    : (float) ((facing - FLASH_PERIPH_DOT) / (FLASH_FULL_DOT - FLASH_PERIPH_DOT));
            severity = Math.max(0.35f, Math.min(1.0f, severity));
            if (living instanceof Player victim) {
                applyFlashbangToPlayer(victim, scheduler, severity);
            } else if (severity >= 0.55f) {
                int blind = (int) Math.round(20 * 5 * severity);
                int slow = (int) Math.round(20 * 4 * severity);
                living.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blind, 0));
                living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, 0));
            }
        }
    }

    /**
     * Staged flashbang VFX — sharp pop + white sparks, light combustion smoke (not a frag cloud).
     * 0ms FLASH+EXPLOSION → 0–100ms sparks → 100–500ms expanding dust → 0.5–3s thin smoke.
     */
    private static void spawnFlashbangDetonation(World world, Location origin, Plugin plugin) {
        Location core = origin.clone().add(0.0, 0.45, 0.0);

        // 0 ms — huge flash + small central blast
        safeParticle(world, Particle.FLASH, core, 3, 0.05, 0.05, 0.05, 0, null);
        safeParticle(world, Particle.EXPLOSION, core, 1, 0.0, 0.0, 0.0, 0, null);
        safeParticle(world, Particle.ELECTRIC_SPARK, core, 10, 0.15, 0.15, 0.15, 0.35, null);

        if (plugin == null) {
            // Best-effort one-shot if we have no scheduler
            burstFlashSparks(world, core, 1.0);
            burstFlashDust(world, core, 0.55);
            return;
        }

        // 0–100 ms (~0–2 ticks): radial FIREWORK + END_ROD + sharp sparks
        burstFlashSparks(world, core, 1.0);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (world.isChunkLoaded(core.getBlockX() >> 4, core.getBlockZ() >> 4)) {
                burstFlashSparks(world, core, 0.75);
            }
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (world.isChunkLoaded(core.getBlockX() >> 4, core.getBlockZ() >> 4)) {
                burstFlashSparks(world, core, 0.45);
            }
        }, 2L);

        // 100–500 ms (~2–10 ticks): rapidly expanding white/gray dust
        for (int tick = 2; tick <= 10; tick++) {
            final int t = tick;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!world.isChunkLoaded(core.getBlockX() >> 4, core.getBlockZ() >> 4)) {
                    return;
                }
                double spread = 0.25 + (t - 2) * 0.12;
                burstFlashDust(world, core, spread);
            }, t);
        }

        // 0.5–3 sec (~10–60 ticks): thin residual smoke, dissipating — not a frag cloud
        AtomicInteger smokeTick = new AtomicInteger(0);
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int n = smokeTick.getAndIncrement();
            if (n >= 25 || !world.isChunkLoaded(core.getBlockX() >> 4, core.getBlockZ() >> 4)) {
                task.cancel();
                return;
            }
            // Every 2 ticks for ~2.5s of the 0.5–3s window
            double fade = 1.0 - (n / 25.0);
            int smokeCount = Math.max(1, (int) Math.round(3 * fade));
            safeParticle(world, Particle.SMOKE, core.clone().add(0, 0.2 + n * 0.02, 0),
                    smokeCount, 0.18 * fade, 0.12, 0.18 * fade, 0.008, null);
            if (n % 3 == 0) {
                safeParticle(world, Particle.LARGE_SMOKE, core.clone().add(0, 0.25, 0),
                        1, 0.12 * fade, 0.1, 0.12 * fade, 0.004, null);
            }
            if (n % 5 == 0 && fade > 0.35) {
                safeParticle(world, Particle.CAMPFIRE_COSY_SMOKE, core.clone().add(0, 0.15, 0),
                        1, 0.08, 0.05, 0.08, 0.002, null);
            }
        }, 10L, 2L);
    }

    private static void burstFlashSparks(World world, Location core, double scale) {
        int firework = Math.max(4, (int) Math.round(18 * scale));
        int rods = Math.max(2, (int) Math.round(8 * scale));
        int sparks = Math.max(3, (int) Math.round(12 * scale));
        safeParticle(world, Particle.FIREWORK, core, firework, 0.35 * scale, 0.3 * scale, 0.35 * scale, 0.22, null);
        // Paper 26.2 END_ROD wants Color data
        safeParticle(world, Particle.END_ROD, core, rods, 0.28 * scale, 0.25 * scale, 0.28 * scale, 0.08, Color.WHITE);
        safeParticle(world, Particle.ELECTRIC_SPARK, core, sparks, 0.22 * scale, 0.2 * scale, 0.22 * scale, 0.4, null);
    }

    private static void burstFlashDust(World world, Location core, double spread) {
        Particle.DustOptions white = new Particle.DustOptions(Color.fromRGB(255, 255, 255), 1.35f);
        Particle.DustOptions gray = new Particle.DustOptions(Color.fromRGB(210, 210, 215), 1.05f);
        int count = Math.max(4, (int) Math.round(14 + spread * 18));
        safeParticle(world, Particle.DUST, core, count, spread, spread * 0.55, spread, 0.0, white);
        safeParticle(world, Particle.DUST, core, Math.max(3, count / 2), spread * 1.15, spread * 0.7, spread * 1.15, 0.0, gray);
    }

    private static void safeParticle(World world, Particle particle, Location loc,
                                     int count, double ox, double oy, double oz, double extra, Object data) {
        if (world == null || loc == null || particle == null || count <= 0) {
            return;
        }
        try {
            world.spawnParticle(particle, loc, count, ox, oy, oz, extra, data, true);
        } catch (IllegalArgumentException first) {
            try {
                if (data == null) {
                    world.spawnParticle(particle, loc, count, ox, oy, oz, extra, Color.WHITE, true);
                } else {
                    world.spawnParticle(particle, loc, count, ox, oy, oz, extra, null, true);
                }
            } catch (IllegalArgumentException ignored) {
                // Skip this particle type — never abort detonation / status FX.
            }
        }
    }

    /**
     * Flashbangs sit on the floor; feet→impact rays often hit the ground block and skip everyone.
     * Prefer eyes → elevated origin, and skip LOS only when very close (facing still checked).
     */
    private static boolean canFlashReach(LivingEntity living, Location flashOrigin) {
        Location eyes = living.getEyeLocation();
        if (eyes.getWorld() == null || !eyes.getWorld().equals(flashOrigin.getWorld())) {
            return false;
        }
        if (eyes.distanceSquared(flashOrigin) <= FLASH_CLOSE_ALWAYS * FLASH_CLOSE_ALWAYS) {
            return true;
        }
        Location aimHigh = flashOrigin.clone().add(0.0, 1.0, 0.0);
        Location aimLow = flashOrigin.clone().add(0.0, 0.15, 0.0);
        return hasLos(eyes, aimHigh) || hasLos(eyes, aimLow)
                || hasLos(living.getLocation().add(0.0, 1.0, 0.0), aimHigh);
    }

    /** 1 = staring at the bang, 0 = side-on, -1 = looking directly away. */
    private static double flashFacingDot(LivingEntity living, Location flashOrigin) {
        Location eyes = living.getEyeLocation();
        Vector toFlash = flashOrigin.clone().add(0.0, 0.55, 0.0).toVector().subtract(eyes.toVector());
        if (toFlash.lengthSquared() < 1.0E-6) {
            return 1.0; // standing on it — always cooked
        }
        toFlash.normalize();
        Vector look = eyes.getDirection();
        if (look.lengthSquared() < 1.0E-6) {
            return 0.0;
        }
        return look.normalize().dot(toFlash);
    }

    /** Looking away: soft ear ring only, no whiteout / blindness. */
    private static void applyFlashbangGlanceAway(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.35f, 1.9f);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 0.35f, 2.0f);
    }

    /**
     * Whiteout (scaled), tinnitus, then post-flash debuffs. {@code severity} 0.35…1 from facing.
     */
    private static void applyFlashbangToPlayer(Player player, Plugin plugin, float severity) {
        float sev = Math.max(0.35f, Math.min(1.0f, severity));
        final int whiteTicks = Math.max(20, Math.round(20 * 5 * sev));
        // Cover whiteout + post darkness so NVG/thermal ticks do not strip the FX.
        long protectMs = 8_000L + Math.round(14_000L * sev);
        FLASH_PROTECT_UNTIL_MS.put(player.getUniqueId(), System.currentTimeMillis() + protectMs);

        // Vanilla fallback wash (no title blocks). Companion clients get a true white HUD.
        player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, whiteTicks, 0, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, whiteTicks, 1, false, false, false));
        if (plugin instanceof WarzPlugin warz) {
            LaserCompanionBridge bridge = warz.laserBridge();
            if (bridge != null) {
                bridge.sendWhiteout(player, whiteTicks);
            }
        }
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.55f + 0.2f * sev, 2.0f);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.PLAYERS, 1.0f + 0.5f * sev, 2.0f);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 0.7f + 0.3f * sev, 2.0f);

        if (plugin == null) {
            return;
        }
        int tinnitusSec = Math.max(4, Math.round((10 + ThreadLocalRandom.current().nextInt(5)) * sev));
        int tinnitusTicks = 20 * tinnitusSec;
        AtomicInteger elapsed = new AtomicInteger(0);
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int t = elapsed.addAndGet(3);
            if (t > tinnitusTicks || !player.isOnline()) {
                task.cancel();
                return;
            }
            float pitch = 1.85f + ThreadLocalRandom.current().nextFloat() * 0.15f;
            float vol = (0.25f + 0.15f * sev) * (1.0f - (t / (float) tinnitusTicks));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, vol, pitch);
            if (t % 9 == 0) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.MASTER, vol * 0.6f, 2.0f);
            }
        }, 0L, 3L);

        final float postSev = sev;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || player.isDead()) {
                return;
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,
                    Math.max(20, Math.round(20 * 10 * postSev)), 0, false, true, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                    Math.max(20, Math.round(20 * 4 * postSev)), 0, false, true, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA,
                    Math.max(20, Math.round(20 * 7 * postSev)), 0, false, true, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,
                    Math.max(20, Math.round(20 * 5 * postSev)), 0, false, true, true));
        }, whiteTicks);
    }

    /**
     * Skip splash on the shooter for normal guns.
     * Throwables, consumables, and explosive rounds (grenade launcher / LAW / …) can hurt the firer.
     */
    private static boolean skipSplashTarget(LivingEntity living, Player shooter, GunDefinition gun) {
        // MQ-9 pilots are ejected to the pad on shoot-down — never splash-kill them as the airframe.
        if (living instanceof Player p && p.getScoreboardTags().contains("bigdrone")) {
            return true;
        }
        if (shooter == null || !living.equals(shooter)) {
            return false;
        }
        if (gun.throwable() || gun.consumable() || gun.explodeRadius() > 0) {
            return false;
        }
        return true;
    }

    public static void knockback(GunDefinition gun, LivingEntity target, Vector velocity) {
        knockback(gun, target, velocity, gun.knockback());
    }

    public static void knockback(GunDefinition gun, LivingEntity target, Vector velocity, double knockback) {
        if (knockback <= 0 || velocity == null || velocity.lengthSquared() == 0) {
            return;
        }
        if (Math.abs(target.getVelocity().getY()) > 0.05) {
            return;
        }
        Vector push = velocity.clone().normalize().setY(0.6).multiply(knockback / 4.0);
        target.setVelocity(push);
    }

    public static boolean isHeadshot(Location hit, LivingEntity hurt, boolean allowed) {
        if (!allowed || hit == null) {
            return false;
        }
        return Math.abs(hit.getY() - hurt.getEyeLocation().getY()) <= 0.26D;
    }

    private static boolean hasLos(Location from, Location to) {
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return false;
        }
        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();
        if (distance <= 0.01) {
            return true;
        }
        // Explosions / splash treat foliage as non-blocking (same as ballistics)
        RayTraceResult result = com.local.warz.util.LaserBeams.rayTraceIgnoringFoliage(
                from, direction.normalize(), distance, 0.0, null);
        return result == null || result.getHitBlock() == null;
    }
}
