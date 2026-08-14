package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Ambient lava heat: unprotected players ignite within {@link #HAZARD_RANGE} blocks;
 * Fire Proximity Suit holders are safe until within {@link #SUIT_SAFE_RANGE}.
 */
public final class LavaHeatService {
    public static final double HAZARD_RANGE = 15.0;
    public static final double SUIT_SAFE_RANGE = 1.0;

    private final WarzPlugin plugin;
    private BukkitTask task;

    public LavaHeatService(WarzPlugin plugin) {
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
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR
                    || player.isDead()) {
                continue;
            }
            // Already standing in lava — vanilla handles that.
            Material feet = player.getLocation().getBlock().getType();
            Material below = player.getLocation().clone().subtract(0, 0.2, 0).getBlock().getType();
            if (feet == Material.LAVA || below == Material.LAVA) {
                continue;
            }

            double dist = nearestLavaDistance(player.getLocation());
            if (dist > HAZARD_RANGE) {
                continue;
            }

            boolean suited = plugin.items().isWearingFireProximitySuit(player);
            if (suited && dist > SUIT_SAFE_RANGE) {
                // Suit keeps you cool outside the 1-block danger close.
                if (player.getFireTicks() > 0 && player.getFireTicks() < 40) {
                    player.setFireTicks(0);
                }
                continue;
            }

            // Closer = hotter. At range edge ~smolder; at 1 block = heavy.
            double proximity = 1.0 - (dist / HAZARD_RANGE);
            proximity = Math.max(0.05, Math.min(1.0, proximity));
            if (suited) {
                // Inside 1 block even suited — light up, milder than bare.
                proximity = Math.max(0.55, proximity);
            }

            int fireTicks = (int) (40 + proximity * proximity * 140);
            if (player.getFireTicks() < fireTicks) {
                player.setFireTicks(fireTicks);
            }
            double dmg = 0.15 + proximity * proximity * 1.85;
            if (suited) {
                dmg *= 0.55;
            }
            player.setNoDamageTicks(0);
            if (plugin.medical() != null) {
                plugin.medical().damageWithoutBleed(player, dmg);
            } else {
                player.damage(dmg);
            }
        }
    }

    /** Euclidean distance to nearest lava block center, or {@link Double#MAX_VALUE}. */
    private static double nearestLavaDistance(Location origin) {
        if (origin == null || origin.getWorld() == null) {
            return Double.MAX_VALUE;
        }
        World world = origin.getWorld();
        int r = (int) Math.ceil(HAZARD_RANGE);
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        double best = Double.MAX_VALUE;
        double rangeSq = HAZARD_RANGE * HAZARD_RANGE;
        // Dense near the player, coarser farther out (still catches lava pits).
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int horizSq = dx * dx + dz * dz;
                if (horizSq > rangeSq) {
                    continue;
                }
                int step = horizSq <= 9 ? 1 : (horizSq <= 64 ? 2 : 3);
                if ((Math.abs(dx) % step) != 0 || (Math.abs(dz) % step) != 0) {
                    continue;
                }
                int yMax = Math.min(r, 8);
                int yMin = -Math.min(r, 12);
                for (int dy = yMin; dy <= yMax; dy += step) {
                    double dsq = (double) horizSq + dy * dy;
                    if (dsq > rangeSq || dsq >= best) {
                        continue;
                    }
                    Block b = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    Material t = b.getType();
                    if (t != Material.LAVA && t != Material.LAVA_CAULDRON) {
                        continue;
                    }
                    double cx = b.getX() + 0.5 - origin.getX();
                    double cy = b.getY() + 0.5 - origin.getY();
                    double cz = b.getZ() + 0.5 - origin.getZ();
                    best = cx * cx + cy * cy + cz * cz;
                    if (best <= SUIT_SAFE_RANGE * SUIT_SAFE_RANGE) {
                        return Math.sqrt(best);
                    }
                }
            }
        }
        return best == Double.MAX_VALUE ? Double.MAX_VALUE : Math.sqrt(best);
    }
}
