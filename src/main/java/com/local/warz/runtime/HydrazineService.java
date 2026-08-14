package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Post-landing hydrazine leak around X-37B pads. Uses POISON (renamed in companion lang).
 * Severity scales with time-in-zone and proximity.
 */
public final class HydrazineService implements Listener {
    private static final double RADIUS = 10.0;
    private static final long LEAK_MS = 10L * 60L * 1000L;
    private static final double EXPOSURE_DECAY = 0.35; // per second outside zone
    private static final int EFFECT_TICKS = 40;

    private final WarzPlugin plugin;
    private final Map<UUID, LeakZone> zones = new ConcurrentHashMap<>();
    private final Map<UUID, Double> exposure = new ConcurrentHashMap<>();
    private final Map<UUID, Long> stunUntil = new ConcurrentHashMap<>();
    private BukkitTask task;

    public HydrazineService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) {
            task.cancel();
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        zones.clear();
        exposure.clear();
        stunUntil.clear();
    }

    /** Call when an X-37B cleanly parks / lands. */
    public void startLeak(DronePadService.ParkedPad pad) {
        if (pad == null || plugin.dronePads() == null) {
            return;
        }
        BigDroneType type = plugin.dronePads().typeOf(pad);
        if (type != BigDroneType.X37B) {
            return;
        }
        Location center = plugin.dronePads().airframeCenter(pad);
        if (center == null || center.getWorld() == null) {
            return;
        }
        zones.put(pad.id, new LeakZone(pad.id, center.getWorld().getUID(),
                center.getX(), center.getY(), center.getZ(),
                System.currentTimeMillis() + LEAK_MS));
        World world = center.getWorld();
        world.playSound(center, Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 0.6f);
        world.spawnParticle(Particle.CLOUD, center.clone().add(0, 0.4, 0), 20, 0.6, 0.3, 0.6, 0.02);
    }

    public void clearLeak(UUID padId) {
        if (padId != null) {
            zones.remove(padId);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        zones.entrySet().removeIf(e -> e.getValue().untilMs <= now);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            // Airborne pilots skip leak zones.
            if (plugin.bigDrone() != null && plugin.bigDrone().isPiloting(player)
                    && plugin.bigDrone().isAirframeAirbornePublic(player)) {
                decay(player.getUniqueId());
                continue;
            }

            LeakZone nearest = null;
            double bestDist = Double.MAX_VALUE;
            Location pl = player.getLocation();
            for (LeakZone zone : zones.values()) {
                if (!zone.worldId.equals(pl.getWorld().getUID())) {
                    continue;
                }
                double dx = pl.getX() - zone.x;
                double dy = pl.getY() - zone.y;
                double dz = pl.getZ() - zone.z;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist <= RADIUS && dist < bestDist) {
                    bestDist = dist;
                    nearest = zone;
                }
            }

            UUID id = player.getUniqueId();
            if (nearest == null) {
                decay(id);
                continue;
            }

            // Hazmat oversuit — sealed against hydrazine vapor.
            if (plugin.items().isWearingHazmatSuit(player)) {
                exposure.remove(id);
                continue;
            }

            // Closer = faster accumulation (edge ~0.4/s, center ~2.2/s).
            double proximity = 1.0 - (bestDist / RADIUS);
            double gain = 0.4 + proximity * 1.8;
            double exp = Math.min(100.0, exposure.getOrDefault(id, 0.0) + gain);
            exposure.put(id, exp);
            Tier tier = Tier.of(exp);
            applyTier(player, tier, proximity);
        }

        // Stun expiry
        Iterator<Map.Entry<UUID, Long>> it = stunUntil.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            if (e.getValue() <= now) {
                it.remove();
            }
        }
    }

    private void decay(UUID id) {
        Double cur = exposure.get(id);
        if (cur == null) {
            return;
        }
        double next = cur - EXPOSURE_DECAY;
        if (next <= 0.1) {
            exposure.remove(id);
        } else {
            exposure.put(id, next);
        }
    }

    private void applyTier(Player player, Tier tier, double proximity) {
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.POISON, EFFECT_TICKS + 10, Math.min(3, tier.ordinal()),
                false, true, true));

        switch (tier) {
            case MILD -> {
                player.addPotionEffect(effect(PotionEffectType.NAUSEA, 1, 0));
                player.addPotionEffect(effect(PotionEffectType.WEAKNESS, 1, 0));
                player.addPotionEffect(effect(PotionEffectType.MINING_FATIGUE, 1, 0));
                if (ThreadLocalRandom.current().nextInt(3) == 0) {
                    player.getWorld().spawnParticle(Particle.CLOUD,
                            player.getEyeLocation(), 3, 0.15, 0.1, 0.15, 0.01);
                    player.getWorld().playSound(player.getLocation(),
                            Sound.ENTITY_PLAYER_BREATH, 0.35f, 0.7f);
                }
            }
            case MODERATE -> {
                player.addPotionEffect(effect(PotionEffectType.NAUSEA, 2, 1));
                player.addPotionEffect(effect(PotionEffectType.WEAKNESS, 2, 1));
                player.addPotionEffect(effect(PotionEffectType.SLOWNESS, 2, 0));
                player.setFoodLevel(Math.min(player.getFoodLevel(), 14));
                nudgeLook(player, 0.4f);
            }
            case SEVERE -> {
                player.addPotionEffect(effect(PotionEffectType.NAUSEA, 3, 2));
                player.addPotionEffect(effect(PotionEffectType.WEAKNESS, 3, 2));
                player.addPotionEffect(effect(PotionEffectType.SLOWNESS, 3, 1));
                if (player.getTicksLived() % 40 < 20) {
                    damageNoBleed(player, 1.0);
                }
                nudgeLook(player, 1.2f);
            }
            case CRITICAL -> {
                player.addPotionEffect(effect(PotionEffectType.NAUSEA, 4, 2));
                player.addPotionEffect(effect(PotionEffectType.WEAKNESS, 4, 2));
                player.addPotionEffect(effect(PotionEffectType.SLOWNESS, 4, 1));
                player.setSprinting(false);
                player.setFoodLevel(Math.min(player.getFoodLevel(), 6));
                if (player.getTicksLived() % 20 < 10) {
                    damageNoBleed(player, 1.5);
                }
                jitterMove(player, 0.12);
                nudgeLook(player, 2.5f);
                if (ThreadLocalRandom.current().nextInt(8) == 0) {
                    stun(player, 25);
                }
            }
            case EXTREME -> {
                player.addPotionEffect(effect(PotionEffectType.NAUSEA, 5, 3));
                player.addPotionEffect(effect(PotionEffectType.WEAKNESS, 5, 3));
                player.addPotionEffect(effect(PotionEffectType.SLOWNESS, 5, 2));
                player.setSprinting(false);
                damageNoBleed(player, 2.5);
                jitterMove(player, 0.28);
                nudgeLook(player, 6f);
                if (ThreadLocalRandom.current().nextInt(4) == 0) {
                    stun(player, 45);
                    damageNoBleed(player, 4.0);
                    player.getWorld().playSound(player.getLocation(),
                            Sound.ENTITY_PLAYER_HURT, 1f, 0.5f);
                }
            }
        }

        player.sendActionBar(ItemFactory.colorize(
                "&a&lHydrazine Poisoning &8· &f" + tier.label));
    }

    private void stun(Player player, int ticks) {
        stunUntil.put(player.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOWNESS, ticks, 4, false, false, false));
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.BLINDNESS, Math.min(ticks, 30), 0, false, false, false));
        player.setVelocity(new Vector(0, player.getVelocity().getY(), 0));
    }

    private static void nudgeLook(Player player, float intensity) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        float yaw = player.getLocation().getYaw() + (r.nextFloat() - 0.5f) * intensity;
        float pitch = Math.max(-90f, Math.min(90f,
                player.getLocation().getPitch() + (r.nextFloat() - 0.5f) * intensity * 0.6f));
        Location loc = player.getLocation();
        loc.setYaw(yaw);
        loc.setPitch(pitch);
        player.teleport(loc);
    }

    private static void jitterMove(Player player, double strength) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Vector v = player.getVelocity();
        v.add(new Vector(
                (r.nextDouble() - 0.5) * strength,
                0,
                (r.nextDouble() - 0.5) * strength));
        player.setVelocity(v);
    }

    private void damageNoBleed(Player player, double amount) {
        if (plugin.medical() != null) {
            plugin.medical().damageWithoutBleed(player, amount);
        } else {
            player.damage(amount);
        }
    }

    private static PotionEffect effect(PotionEffectType type, int seconds, int amplifier) {
        return new PotionEffect(type, Math.max(20, seconds * 20), amplifier, false, true, true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        exposure.remove(event.getPlayer().getUniqueId());
        stunUntil.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        exposure.remove(event.getPlayer().getUniqueId());
        stunUntil.remove(event.getPlayer().getUniqueId());
        event.getPlayer().removePotionEffect(PotionEffectType.POISON);
    }

    private enum Tier {
        MILD("Mild"),
        MODERATE("Moderate"),
        SEVERE("Severe"),
        CRITICAL("Critical"),
        EXTREME("Extreme");

        final String label;

        Tier(String label) {
            this.label = label;
        }

        static Tier of(double exposure) {
            if (exposure >= 80) {
                return EXTREME;
            }
            if (exposure >= 55) {
                return CRITICAL;
            }
            if (exposure >= 35) {
                return SEVERE;
            }
            if (exposure >= 15) {
                return MODERATE;
            }
            return MILD;
        }
    }

    private static final class LeakZone {
        final UUID padId;
        final UUID worldId;
        final double x;
        final double y;
        final double z;
        final long untilMs;

        LeakZone(UUID padId, UUID worldId, double x, double y, double z, long untilMs) {
            this.padId = padId;
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.untilMs = untilMs;
        }
    }
}
