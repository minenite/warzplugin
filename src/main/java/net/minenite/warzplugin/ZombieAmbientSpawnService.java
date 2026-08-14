package net.minenite.warzplugin;

import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Sparse ambient zombie/husk spawns near players, day or night. CUSTOM spawns
 * trigger {@link GroundEmergeListener} dig-up FX. Vanilla natural density is
 * left alone; this just keeps the apocalypse present without flooding.
 */
public final class ZombieAmbientSpawnService {

    private final WarzPlugin plugin;
    private boolean enabled;
    private int intervalTicks;
    private double chancePerPlayer;
    private int minRange;
    private int maxRange;
    private int maxNearPlayer;
    private int nearRadius;
    private double huskChance;
    private BukkitTask task;

    public ZombieAmbientSpawnService(WarzPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("zombie-spawns.enabled", true);
        this.intervalTicks = Math.max(40, plugin.getConfig().getInt("zombie-spawns.interval-ticks", 100));
        this.chancePerPlayer = clamp01(plugin.getConfig().getDouble("zombie-spawns.chance-per-player", 0.35));
        this.minRange = Math.max(8, plugin.getConfig().getInt("zombie-spawns.min-range", 14));
        this.maxRange = Math.max(minRange + 1, plugin.getConfig().getInt("zombie-spawns.max-range", 38));
        this.maxNearPlayer = Math.max(1, plugin.getConfig().getInt("zombie-spawns.max-near-player", 8));
        this.nearRadius = Math.max(16, plugin.getConfig().getInt("zombie-spawns.near-radius", 48));
        this.huskChance = clamp01(plugin.getConfig().getDouble("zombie-spawns.husk-chance", 0.28));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void start() {
        stop();
        if (!enabled) {
            return;
        }
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, intervalTicks, intervalTicks);
        plugin.getLogger().info("Zombie ambient spawns on (every " + intervalTicks
                + "t, ~" + (int) (chancePerPlayer * 100) + "% per player, dig-up FX).");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        try {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            for (Player player : Bukkit.getOnlinePlayers()) {
                try {
                    if (!shouldConsider(player)) {
                        continue;
                    }
                    if (rng.nextDouble() > chancePerPlayer) {
                        continue;
                    }
                    if (countNearbyUndead(player) >= maxNearPlayer) {
                        continue;
                    }
                    Location spot = findSpot(player, rng);
                    if (spot == null) {
                        continue;
                    }
                    EntityType type = rng.nextDouble() < huskChance ? EntityType.HUSK : EntityType.ZOMBIE;
                    trySpawn(spot, type);
                } catch (Throwable perPlayer) {
                    plugin.getLogger().warning("Ambient zombie tick failed for "
                            + player.getName() + ": " + perPlayer.getMessage());
                }
            }
        } catch (Throwable fatal) {
            plugin.getLogger().warning("Ambient zombie tick failed: " + fatal.getMessage());
        }
    }

    private static boolean shouldConsider(Player player) {
        if (!player.isOnline() || player.isDead()) {
            return false;
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.SPECTATOR || mode == GameMode.CREATIVE) {
            return false;
        }
        World world = player.getWorld();
        return world != null && world.getEnvironment() == World.Environment.NORMAL;
    }

    private int countNearbyUndead(Player player) {
        int count = 0;
        double r2 = (double) nearRadius * nearRadius;
        for (Entity entity : player.getNearbyEntities(nearRadius, nearRadius, nearRadius)) {
            if (entity.getType() != EntityType.ZOMBIE && entity.getType() != EntityType.HUSK
                    && entity.getType() != EntityType.ZOMBIE_VILLAGER
                    && entity.getType() != EntityType.PARCHED) {
                continue;
            }
            if (entity.getLocation().distanceSquared(player.getLocation()) <= r2) {
                count++;
            }
        }
        return count;
    }

    private Location findSpot(Player player, ThreadLocalRandom rng) {
        World world = player.getWorld();
        Location base = player.getLocation();
        for (int attempt = 0; attempt < 10; attempt++) {
            double dist = minRange + rng.nextDouble() * (maxRange - minRange);
            double angle = rng.nextDouble() * Math.PI * 2.0;
            int x = base.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = base.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            int y = surfaceY(world, x, z, base.getBlockY());
            if (y == Integer.MIN_VALUE) {
                continue;
            }
            Location feet = new Location(world, x + 0.5, y, z + 0.5);
            if (!isSpawnable(feet)) {
                continue;
            }
            // Prefer not spawning directly in the player's view cone at close range.
            if (dist < 24 && lookingAt(player, feet)) {
                continue;
            }
            return feet;
        }
        return null;
    }

    private static int surfaceY(World world, int x, int z, int nearY) {
        int max = Math.min(world.getMaxHeight() - 2, nearY + 24);
        int min = Math.max(world.getMinHeight() + 1, nearY - 32);
        for (int y = max; y >= min; y--) {
            Block ground = world.getBlockAt(x, y, z);
            Block feet = world.getBlockAt(x, y + 1, z);
            Block head = world.getBlockAt(x, y + 2, z);
            if (!ground.getType().isSolid() || ground.isLiquid()) {
                continue;
            }
            if (!isClear(feet) || !isClear(head)) {
                continue;
            }
            return y + 1;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean isClear(Block block) {
        Material type = block.getType();
        return block.isPassable() && !type.name().contains("LEAVES") && type != Material.COBWEB;
    }

    private static boolean isSpawnable(Location feet) {
        World world = feet.getWorld();
        if (world == null) {
            return false;
        }
        Block at = feet.getBlock();
        Block below = feet.clone().add(0, -1, 0).getBlock();
        Block above = feet.clone().add(0, 1, 0).getBlock();
        if (!below.getType().isSolid() || below.isLiquid()) {
            return false;
        }
        if (!isClear(at) || !isClear(above)) {
            return false;
        }
        // Avoid roofs / tiny caves that feel like spam in buildings.
        if (below.getType().name().contains("SLAB") || below.getType().name().contains("STAIR")) {
            return false;
        }
        return true;
    }

    private static boolean lookingAt(Player player, Location target) {
        org.bukkit.util.Vector to = target.toVector().subtract(player.getEyeLocation().toVector());
        if (to.lengthSquared() < 1.0e-4) {
            return true;
        }
        to.normalize();
        return player.getEyeLocation().getDirection().normalize().dot(to) > 0.82;
    }

    private void trySpawn(Location feet, EntityType type) {
        World world = feet.getWorld();
        if (world == null) {
            return;
        }
        Class<? extends Entity> clazz = type.getEntityClass();
        if (clazz == null || !LivingEntity.class.isAssignableFrom(clazz)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Class<? extends LivingEntity> livingClass = (Class<? extends LivingEntity>) clazz;
        try {
            // Prefer class spawn (CUSTOM reason) so dig-up FX runs; avoid the
            // broken spawnEntity(loc, type, reason) overload path on older jars.
            world.spawn(feet, livingClass, CreatureSpawnEvent.SpawnReason.CUSTOM, false, living -> {
                living.setRemoveWhenFarAway(true);
                living.setCanPickupItems(false);
            });
        } catch (Throwable failed) {
            plugin.getLogger().warning("Ambient zombie spawn failed: " + failed.getClass().getSimpleName()
                    + ": " + failed.getMessage());
        }
    }

    private static double clamp01(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
