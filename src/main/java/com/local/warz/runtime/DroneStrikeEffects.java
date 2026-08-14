package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.combat.ImpactEffects;
import com.local.warz.model.GunDefinition;
import com.local.warz.model.RoundDefinition;
import com.local.warz.projectile.Bullet;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.concurrent.ThreadLocalRandom;

/**
 * UAV warhead presentation: flashes, delayed distant boom, glass shatter, pilot camera cues.
 */
public final class DroneStrikeEffects {
    public static final String CHANNEL_STRIKE_FX = "pvpgunminus:drone_strike_fx";
    private static final double SOUND_SPEED = 68.0; // blocks/s (game-feel, not real 343)

    private final WarzPlugin plugin;

    public DroneStrikeEffects(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerChannel() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_STRIKE_FX);
    }

    public void unregisterChannel() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL_STRIKE_FX);
    }

    public void detonate(Location at, Player shooter, GunDefinition gun, RoundDefinition round,
                         MunitionProfile profile) {
        if (at == null || at.getWorld() == null || profile == null) {
            return;
        }
        switch (profile.warhead()) {
            case KINETIC -> { /* R9X owns its own blades path */ }
            case AIRBURST -> airburst(at, shooter, gun, round, profile);
            case CONCUSSION -> concussion(at, shooter, gun, round, profile);
            case PENETRATOR -> penetrator(at, shooter, gun, round, profile);
            case HEAVY -> heavy(at, shooter, gun, round, profile);
            case SONAR -> sonar(at, shooter, profile);
            case BLAST -> blast(at, shooter, gun, round, profile, false);
        }
    }

    private static final long SONAR_COOLDOWN_MS = 45_000L;
    private static final java.util.Map<java.util.UUID, Long> sonarCooldownUntil = new java.util.concurrent.ConcurrentHashMap<>();

    /** Non-lethal marker: Glowing on LOS targets in radius for 90s (45s shooter cooldown). */
    private void sonar(Location at, Player shooter, MunitionProfile profile) {
        World w = at.getWorld();
        double radius = Math.min(120.0, Math.max(32.0, profile.effectRadius()));
        if (shooter != null) {
            long now = System.currentTimeMillis();
            Long until = sonarCooldownUntil.get(shooter.getUniqueId());
            if (until != null && now < until) {
                long left = (until - now + 999) / 1000;
                shooter.sendMessage(Component.text(
                        "Sonar cooling down — " + left + "s", NamedTextColor.GRAY));
                return;
            }
            sonarCooldownUntil.put(shooter.getUniqueId(), now + SONAR_COOLDOWN_MS);
        }
        w.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.PLAYERS, 2.2f, 0.55f);
        w.playSound(at, Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.PLAYERS, 0.55f, 1.6f);
        w.spawnParticle(Particle.END_ROD, at.clone().add(0, 0.5, 0), 80, 1.2, 0.4, 1.2, 0.08);
        w.spawnParticle(Particle.SONIC_BOOM, at, 1, 0, 0, 0, 0);
        for (int ring = 1; ring <= 4; ring++) {
            double r = Math.min(48.0, radius * (0.12 * ring));
            for (int i = 0; i < 36; i++) {
                double ang = (Math.PI * 2 * i) / 36.0;
                Location p = at.clone().add(Math.cos(ang) * r, 0.2, Math.sin(ang) * r);
                w.spawnParticle(Particle.END_ROD, p, 1, 0, 0, 0, 0);
            }
        }
        int duration = 20 * 90; // 90 seconds
        PotionEffect glow = new PotionEffect(PotionEffectType.GLOWING, duration, 0, false, true, true);
        int tagged = 0;
        for (Player p : w.getPlayers()) {
            if (!p.isOnline() || p.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (p.getLocation().distanceSquared(at) > radius * radius) {
                continue;
            }
            // Line-of-sight from blast — walls block the mark.
            Location eye = p.getEyeLocation();
            var hit = w.rayTraceBlocks(at, eye.toVector().subtract(at.toVector()).normalize(),
                    Math.min(radius, at.distance(eye) + 0.5),
                    org.bukkit.FluidCollisionMode.NEVER, true);
            if (hit != null && hit.getHitBlock() != null
                    && hit.getHitPosition().distanceSquared(eye.toVector()) > 2.25) {
                continue;
            }
            p.addPotionEffect(glow);
            tagged++;
            p.sendMessage(Component.text("SONAR MARK — glowing for 90 seconds", NamedTextColor.AQUA));
        }
        if (shooter != null && shooter.isOnline()) {
            shooter.sendMessage(Component.text(
                    "Sonar marker — tagged " + tagged + " (LOS) within " + (int) radius + " blocks",
                    NamedTextColor.AQUA));
        }
        cuePilots(at, Math.min(64.0, radius * 0.35), 0.45f);
    }

    private void blast(Location at, Player shooter, GunDefinition gun, RoundDefinition round,
                       MunitionProfile profile, boolean heavy) {
        World w = at.getWorld();
        flashImmediate(at, heavy ? 1.4f : 1.0f);
        scheduleDelayedBoom(at, heavy ? 1.6f : 1.15f, heavy ? 0.55f : 0.85f);
        kickDust(at, profile.effectRadius());
        if (heavy) {
            shatterGlass(at, profile.effectRadius() * 1.35);
        } else {
            shatterGlass(at, profile.effectRadius() * 0.85);
        }
        // Structural crater via ImpactEffects (uses gun explode radius + round adds).
        ImpactEffects.apply(gun, shooter, at, round, plugin);
        if (plugin.blastShock() != null && profile.shockStrength() > 0) {
            plugin.blastShock().apply(at.clone(), Math.max(12.0, profile.effectRadius() * 2.2),
                    profile.shockStrength());
        }
        damageNearby(at, profile.effectRadius(), shooter, gun, round, heavy ? 48 : 32);
        cuePilots(at, profile.effectRadius() * 1.8, heavy ? 0.95f : 0.7f);
        structureSplash(at, profile.effectRadius(), shooter, gun, round);
    }

    private void concussion(Location at, Player shooter, GunDefinition gun, RoundDefinition round,
                            MunitionProfile profile) {
        World w = at.getWorld();
        // Sharp white/orange flash + pressure ring — modest crater.
        w.spawnParticle(Particle.FLASH, at.clone().add(0, 0.4, 0), 4, 0.1, 0.1, 0.1, 0);
        w.spawnParticle(Particle.EXPLOSION, at, 3, 0.3, 0.2, 0.3, 0.02);
        w.spawnParticle(Particle.END_ROD, at, 40, 0.2, 0.05, 0.2, 0.35);
        pressureRing(at, profile.effectRadius());
        scheduleDelayedBoom(at, 1.35f, 0.7f);
        shatterGlass(at, profile.effectRadius() * 1.6);
        kickDust(at, profile.effectRadius());
        // Modest block break
        if (plugin.explosionRegen() != null) {
            plugin.explosionRegen().blastTerrain(at, 3.5);
        }
        w.createExplosion(at, 2.2f, false, true, null);
        if (plugin.blastShock() != null) {
            plugin.blastShock().apply(at.clone(), 28.0, profile.shockStrength());
        }
        damageNearby(at, profile.effectRadius(), shooter, gun, round, 36);
        cuePilots(at, profile.effectRadius() * 2.0, 1.0f);
        structureSplash(at, profile.effectRadius() * 0.7, shooter, gun, round);
    }

    private void airburst(Location at, Player shooter, GunDefinition gun, RoundDefinition round,
                          MunitionProfile profile) {
        World w = at.getWorld();
        w.spawnParticle(Particle.EXPLOSION, at, 4, 0.4, 0.4, 0.4, 0.02);
        w.spawnParticle(Particle.SMOKE, at, 30, 0.8, 0.6, 0.8, 0.04);
        w.spawnParticle(Particle.CRIT, at, 40, 1.0, 0.8, 1.0, 0.2);
        scheduleDelayedBoom(at, 1.25f, 1.2f);
        // No terrain crater for AA / Sidewinder
        w.createExplosion(at, 1.4f, false, false, null);
        damageNearby(at, profile.effectRadius(), shooter, gun, round, 40);
        cuePilots(at, profile.effectRadius() * 1.5, 0.65f);
        structureSplash(at, profile.effectRadius(), shooter, gun, round);
    }

    private void penetrator(Location at, Player shooter, GunDefinition gun, RoundDefinition round,
                            MunitionProfile profile) {
        World w = at.getWorld();
        // Vanish into structure briefly, then deep boom.
        w.playSound(at, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.9f, 0.55f);
        w.spawnParticle(Particle.CLOUD, at, 12, 0.3, 0.2, 0.3, 0.01);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (at.getWorld() == null) {
                return;
            }
            flashImmediate(at, 1.5f);
            scheduleDelayedBoom(at, 1.7f, 0.45f);
            w.spawnParticle(Particle.EXPLOSION_EMITTER, at, 2, 0.2, 0.2, 0.2, 0);
            w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at.clone().add(0, 1, 0), 50, 1.2, 1.5, 1.2, 0.02);
            kickDust(at, profile.effectRadius());
            shatterGlass(at, profile.effectRadius() * 1.2);
            if (plugin.explosionRegen() != null) {
                plugin.explosionRegen().blastTerrain(at, 6.0);
            }
            w.createExplosion(at, 4.5f, false, true, null);
            if (plugin.blastShock() != null) {
                plugin.blastShock().apply(at.clone(), 32.0, profile.shockStrength());
            }
            if (plugin.laserBridge() != null) {
                plugin.laserBridge().broadcastThermalBlast(at.clone().add(0, 0.4, 0),
                        (float) profile.effectRadius());
            }
            damageNearby(at, profile.effectRadius(), shooter, gun, round, 55);
            cuePilots(at, profile.effectRadius() * 2.2, 1.0f);
            structureSplash(at, profile.effectRadius(), shooter, gun, round);
        }, 8L);
    }

    private void heavy(Location at, Player shooter, GunDefinition gun, RoundDefinition round,
                       MunitionProfile profile) {
        World w = at.getWorld();
        flashImmediate(at, 1.8f);
        scheduleDelayedBoom(at, 1.9f, 0.4f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, at, 3, 0.5, 0.3, 0.5, 0);
        w.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, at.clone().add(0, 1.5, 0), 80, 2.5, 2.0, 2.5, 0.01);
        pressureRing(at, profile.effectRadius() * 0.7);
        kickDust(at, profile.effectRadius());
        shatterGlass(at, profile.effectRadius() * 1.5);
        ImpactEffects.apply(gun, shooter, at, round, plugin);
        if (plugin.explosionRegen() != null) {
            plugin.explosionRegen().blastTerrain(at, 7.5);
        }
        w.createExplosion(at, 6.0f, false, true, null);
        if (plugin.blastShock() != null) {
            plugin.blastShock().apply(at.clone(), 40.0, profile.shockStrength());
        }
        damageNearby(at, profile.effectRadius(), shooter, gun, round, 64);
        cuePilots(at, profile.effectRadius() * 2.5, 1.0f);
        structureSplash(at, profile.effectRadius(), shooter, gun, round);
    }

    private void flashImmediate(Location at, float intensity) {
        World w = at.getWorld();
        w.spawnParticle(Particle.FLASH, at.clone().add(0, 0.5, 0), Math.max(1, Math.round(2 * intensity)),
                0.08, 0.08, 0.08, 0);
        w.spawnParticle(Particle.FLAME, at, Math.round(12 * intensity), 0.4, 0.3, 0.4, 0.02);
        // Near players hear a thin crack instantly; boom is delayed.
        for (Player p : w.getPlayers()) {
            if (!p.isOnline()) {
                continue;
            }
            double d = p.getLocation().distance(at);
            if (d > 96) {
                continue;
            }
            float vol = (float) (0.35 * intensity * Math.max(0.15, 1.0 - d / 96.0));
            p.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.PLAYERS, vol, 1.4f);
        }
    }

    /** Flash first; boom arrives later by distance. */
    public void scheduleDelayedBoom(Location at, float volume, float pitch) {
        World w = at.getWorld();
        if (w == null) {
            return;
        }
        for (Player p : w.getPlayers()) {
            if (!p.isOnline() || p.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            double dist = p.getLocation().distance(at);
            if (dist > 220) {
                continue;
            }
            long delayTicks = Math.max(0L, Math.min(60L, Math.round((dist / SOUND_SPEED) * 20.0)));
            float vol = (float) (volume * Math.max(0.08, 1.0 - dist / 220.0));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || p.getWorld() == null || !p.getWorld().equals(w)) {
                    return;
                }
                p.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, vol, pitch);
                if (volume >= 1.5f) {
                    p.playSound(at, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS,
                            vol * 0.35f, 0.55f);
                }
            }, delayTicks);
        }
    }

    private void pressureRing(Location at, double radius) {
        World w = at.getWorld();
        for (int ring = 1; ring <= 3; ring++) {
            double r = radius * (0.35 * ring);
            for (int i = 0; i < 24; i++) {
                double ang = (Math.PI * 2 * i) / 24.0;
                Location p = at.clone().add(Math.cos(ang) * r, 0.15, Math.sin(ang) * r);
                w.spawnParticle(Particle.CLOUD, p, 1, 0, 0, 0, 0);
                w.spawnParticle(Particle.CRIT, p, 1, 0, 0, 0, 0);
            }
        }
    }

    private void kickDust(Location at, double radius) {
        World w = at.getWorld();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int n = (int) Math.min(40, 8 + radius * 2);
        for (int i = 0; i < n; i++) {
            double ox = (rng.nextDouble() - 0.5) * radius;
            double oz = (rng.nextDouble() - 0.5) * radius;
            Location p = at.clone().add(ox, 0.1, oz);
            Block below = p.getBlock().getRelative(0, -1, 0);
            if (below.getType().isAir()) {
                continue;
            }
            w.spawnParticle(Particle.BLOCK, p, 4, 0.2, 0.05, 0.2, 0.02, below.getBlockData());
            w.spawnParticle(Particle.CLOUD, p, 2, 0.15, 0.05, 0.15, 0.01);
        }
    }

    private void shatterGlass(Location at, double radius) {
        World w = at.getWorld();
        int r = (int) Math.ceil(radius);
        int cx = at.getBlockX();
        int cy = at.getBlockY();
        int cz = at.getBlockZ();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int y = cy - r; y <= cy + r; y++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material t = b.getType();
                    String tn = t.name();
                    if (!tn.contains("GLASS") && !tn.contains("PANE")) {
                        continue;
                    }
                    if (b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(at) > radius * radius) {
                        continue;
                    }
                    w.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5),
                            8, 0.2, 0.2, 0.2, 0.02, b.getBlockData());
                    b.setType(Material.AIR, false);
                    w.playSound(b.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.7f, 1.1f);
                }
            }
        }
    }

    private void damageNearby(Location at, double radius, Player shooter, GunDefinition gun,
                              RoundDefinition round, int baseDamage) {
        World w = at.getWorld();
        double r2 = radius * radius;
        int dmg = baseDamage;
        if (round != null) {
            dmg = Math.max(1, (int) Math.round(dmg * round.damageMult()));
        }
        for (Entity e : w.getNearbyEntities(at, radius, radius, radius)) {
            if (!(e instanceof LivingEntity living)) {
                continue;
            }
            if (living instanceof Player p) {
                if (shooter != null && p.getUniqueId().equals(shooter.getUniqueId())) {
                    continue;
                }
                if (plugin.bigDrone() != null && plugin.bigDrone().isPiloting(p)) {
                    continue; // structure splash handles airframes
                }
            }
            if (living.getLocation().add(0, 1, 0).distanceSquared(at) > r2) {
                continue;
            }
            double before = living.getHealth();
            living.setNoDamageTicks(0);
            Bullet.applyAttributedDamage(living, dmg, shooter);
            Vector push = living.getLocation().toVector().subtract(at.toVector());
            if (push.lengthSquared() > 1.0e-4) {
                push.normalize().multiply(0.6).setY(0.25);
                living.setVelocity(living.getVelocity().add(push));
            }
            if (shooter != null && (living.isDead() || living.getHealth() <= 0)) {
                if (plugin.bigDrone() != null) {
                    plugin.bigDrone().announceGuidedKill(shooter, living, round);
                }
            }
        }
    }

    private void structureSplash(Location at, double radius, Player shooter,
                                 GunDefinition gun, RoundDefinition round) {
        if (plugin.bigDrone() != null) {
            plugin.bigDrone().absorbExplosionSplash(at, radius, shooter, gun, round);
        }
    }

    /** Pilot-safe camera artifacts (bloom/shake/static) — not ground BlastFx. */
    public void cuePilots(Location at, double radius, float severity) {
        if (plugin.bigDrone() == null || plugin.companions() == null) {
            return;
        }
        double r2 = radius * radius;
        byte[] payload = encodeStrikeFx(severity);
        if (payload == null) {
            return;
        }
        for (Player p : at.getWorld().getPlayers()) {
            if (!plugin.bigDrone().isPiloting(p)) {
                continue;
            }
            Location air = plugin.bigDrone().droneWorldLocation(p);
            if (air == null || air.distanceSquared(at) > r2) {
                continue;
            }
            if (!plugin.companions().hasCompanion(p)) {
                p.sendActionBar(Component.text("OPTICS — BLAST INTERFERENCE", NamedTextColor.GOLD));
                continue;
            }
            p.sendPluginMessage(plugin, CHANNEL_STRIKE_FX, payload);
        }
    }

    private static byte[] encodeStrikeFx(float severity) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(1); // protocol
            out.writeByte(1); // fmt
            out.writeFloat(Math.max(0.15f, Math.min(1f, severity)));
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
