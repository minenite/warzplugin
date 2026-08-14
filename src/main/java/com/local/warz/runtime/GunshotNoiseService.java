package com.local.warz.runtime;

import com.local.warz.event.GunFireEvent;
import com.local.warz.model.GunDefinition;
import com.local.warz.model.RoundDefinition;
import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Unsuppressed gunfire yanks nearby zombies toward the shooter and can pull
 * one extra spawn at the edge of the report. Suppressors cut the radius hard;
 * subsonic + can is nearly silent.
 */
public final class GunshotNoiseService implements Listener {
    private static final long SPAWN_COOLDOWN_MS = 2500L;

    private final WarzPlugin plugin;
    private final Map<UUID, Long> lastExtraSpawn = new ConcurrentHashMap<>();

    public GunshotNoiseService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGunFire(GunFireEvent event) {
        if (!plugin.getConfig().getBoolean("gunshot-noise.enabled", true)) {
            return;
        }
        Player player = event.getPlayer();
        GunDefinition gun = event.getGun();
        if (player == null || !player.isOnline() || gun == null) {
            return;
        }
        if (gun.throwable() || gun.consumable() || gun.isLaser()) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean suppressed = plugin.items() != null && plugin.items().hasSuppressor(hand);
        RoundDefinition round = peekRound(hand, gun);
        boolean subsonic = BulletAudio.isSubsonic(round);
        BulletAudio.Profile profile = BulletAudio.profileFor(BulletAudio.caliberOf(gun, round));
        double hear = profile.hearDistance();
        if (subsonic) {
            hear *= 0.55;
        }
        if (suppressed) {
            hear *= subsonic ? 0.22 : 0.32;
        }
        if (WaterBallistics.shooterMuzzleWet(player)) {
            hear *= 0.5;
        }
        hear = Math.max(6.0, hear);
        Location at = player.getLocation();
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        aggroUndead(player, at, hear);
        if (!suppressed && hear >= 32.0) {
            maybeSpawnExtra(player, at, hear);
        }
    }

    private RoundDefinition peekRound(ItemStack hand, GunDefinition gun) {
        if (plugin.items() == null || plugin.rounds() == null) {
            return null;
        }
        if (hand != null && plugin.items().hasChamberRound(hand)) {
            String cid = plugin.items().chamberRound(hand);
            if (cid != null) {
                return plugin.rounds().get(cid).orElse(null);
            }
        }
        return null;
    }

    private void aggroUndead(Player shooter, Location at, double hear) {
        double r = hear;
        for (Entity entity : at.getWorld().getNearbyEntities(at, r, r, r)) {
            if (!(entity instanceof Creature mob) || !isNoiseUndead(entity.getType())) {
                continue;
            }
            if (entity.getLocation().distanceSquared(at) > r * r) {
                continue;
            }
            LivingEntity current = mob.getTarget();
            if (current instanceof Player other && other != shooter && other.isOnline()) {
                double toShooter = entity.getLocation().distanceSquared(at);
                double toCurrent = entity.getLocation().distanceSquared(other.getLocation());
                if (toCurrent + 36.0 < toShooter) {
                    continue;
                }
            }
            try {
                mob.setTarget(shooter);
            } catch (Throwable ignored) {
            }
        }
    }

    private void maybeSpawnExtra(Player player, Location at, double hear) {
        double chance = plugin.getConfig().getDouble("gunshot-noise.spawn-chance", 0.18);
        if (chance <= 0.0 || ThreadLocalRandom.current().nextDouble() > chance) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastExtraSpawn.get(player.getUniqueId());
        if (last != null && now - last < SPAWN_COOLDOWN_MS) {
            return;
        }
        Location spot = findEdgeSpot(at, Math.max(16.0, hear * 0.55), hear * 0.95);
        if (spot == null) {
            return;
        }
        EntityType type = ThreadLocalRandom.current().nextDouble() < 0.28
                ? EntityType.HUSK : EntityType.ZOMBIE;
        if (trySpawn(spot, type, player)) {
            lastExtraSpawn.put(player.getUniqueId(), now);
        }
    }

    private static boolean isNoiseUndead(EntityType type) {
        return type == EntityType.ZOMBIE
                || type == EntityType.HUSK
                || type == EntityType.ZOMBIE_VILLAGER
                || type == EntityType.PARCHED;
    }

    private Location findEdgeSpot(Location base, double min, double max) {
        World world = base.getWorld();
        if (world == null) {
            return null;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 8; attempt++) {
            double dist = min + rng.nextDouble() * Math.max(1.0, max - min);
            double angle = rng.nextDouble() * Math.PI * 2.0;
            int x = base.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = base.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            int y = surfaceY(world, x, z, base.getBlockY());
            if (y == Integer.MIN_VALUE) {
                continue;
            }
            Location feet = new Location(world, x + 0.5, y, z + 0.5);
            Block below = feet.clone().add(0, -1, 0).getBlock();
            if (!below.getType().isSolid() || below.isLiquid()) {
                continue;
            }
            if (!feet.getBlock().isPassable() || !feet.clone().add(0, 1, 0).getBlock().isPassable()) {
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

    private boolean trySpawn(Location feet, EntityType type, Player target) {
        World world = feet.getWorld();
        if (world == null) {
            return false;
        }
        Class<? extends Entity> clazz = type.getEntityClass();
        if (clazz == null || !LivingEntity.class.isAssignableFrom(clazz)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        Class<? extends LivingEntity> livingClass = (Class<? extends LivingEntity>) clazz;
        try {
            LivingEntity spawned = world.spawn(feet, livingClass, CreatureSpawnEvent.SpawnReason.CUSTOM, false, living -> {
                living.setRemoveWhenFarAway(true);
                living.setCanPickupItems(false);
            });
            if (spawned instanceof Creature mob) {
                mob.setTarget(target);
            }
            return true;
        } catch (Throwable failed) {
            return false;
        }
    }
}
