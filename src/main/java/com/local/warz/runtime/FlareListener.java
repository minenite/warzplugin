package com.local.warz.runtime;

import com.local.warz.WarzKeys;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Throws road flares with the same arc style as smoke grenades. */
public final class FlareListener implements Listener {
    private static final long THROW_DEBOUNCE_MS = 100L;
    private static final int MAX_FLIGHT_TICKS = 80;

    private final WarzPlugin plugin;
    private final Map<UUID, Long> lastThrowMs = new ConcurrentHashMap<>();
    private final AtomicInteger inFlightCount = new AtomicInteger();

    public FlareListener(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack main = player.getInventory().getItemInMainHand();
        boolean mainFlare = plugin.items().isRoadFlare(main);

        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            if (mainFlare) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND || !mainFlare) {
            return;
        }

        event.setCancelled(true);

        long now = System.currentTimeMillis();
        Long prev = lastThrowMs.get(player.getUniqueId());
        if (prev != null && now - prev < THROW_DEBOUNCE_MS) {
            return;
        }
        lastThrowMs.put(player.getUniqueId(), now);

        player.setCooldown(main.getType(), 0);
        throwFlare(player, main);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (plugin.items().isRoadFlare(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    private void throwFlare(Player player, ItemStack stack) {
        FlareColor color = plugin.items().flareColor(stack);

        if (player.getGameMode() != GameMode.CREATIVE) {
            int amt = stack.getAmount();
            if (amt <= 1) {
                player.getInventory().setItemInMainHand(null);
            } else {
                stack.setAmount(amt - 1);
            }
        }

        Location start = player.getEyeLocation().clone();
        Vector vel = start.getDirection().normalize().multiply(1.15);
        vel.setY(vel.getY() * 0.78 + 0.22);
        UUID throwerId = player.getUniqueId();
        int flightId = inFlightCount.incrementAndGet();

        player.swingMainHand();
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 0.6f, 0.75f);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_WOOD_HIT, 0.35f, 1.4f);

        try {
            ItemStack crumb = plugin.items().createRoadFlare(color, 1);
            var crumbMeta = crumb.getItemMeta();
            crumbMeta.getPersistentDataContainer().set(
                    WarzKeys.of("flare_throw_id"),
                    org.bukkit.persistence.PersistentDataType.STRING,
                    "vis-" + flightId + "-" + System.nanoTime());
            crumb.setItemMeta(crumbMeta);
            var vis = player.getWorld().dropItem(start, crumb);
            vis.setPickupDelay(32767);
            vis.setVelocity(vel.clone());
            vis.setGravity(true);
            try {
                vis.setUnlimitedLifetime(true);
                vis.setCanMobPickup(false);
            } catch (Throwable ignored) {
                // ignore
            }
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (vis.isValid()) {
                    vis.remove();
                }
            }, MAX_FLIGHT_TICKS + 5L);
        } catch (Throwable ignored) {
            // visual only
        }

        new BukkitRunnable() {
            final Location pos = start.clone();
            final Vector v = vel.clone();
            final FlareColor flareColor = color;
            int ticks;

            @Override
            public void run() {
                ticks++;
                if (pos.getWorld() == null) {
                    cancel();
                    return;
                }

                v.setY(v.getY() - 0.045);
                Location next = pos.clone().add(v);

                Vector step = next.toVector().subtract(pos.toVector());
                double len = step.length();
                boolean hit = false;
                if (len > 1.0E-4) {
                    RayTraceResult trace = com.local.warz.util.LaserBeams.rayTraceIgnoringFoliage(
                            pos, step.normalize(), len + 0.15, 0.0, null);
                    if (trace != null && trace.getHitBlock() != null) {
                        pos.setX(trace.getHitPosition().getX());
                        pos.setY(trace.getHitPosition().getY());
                        pos.setZ(trace.getHitPosition().getZ());
                        hit = true;
                    } else {
                        pos.setX(next.getX());
                        pos.setY(next.getY());
                        pos.setZ(next.getZ());
                    }
                }

                boolean grounded = hit
                        || !pos.getBlock().getRelative(0, -1, 0).getType().isAir()
                        || v.lengthSquared() < 0.001;

                if ((grounded && ticks >= 4) || ticks >= MAX_FLIGHT_TICKS) {
                    cancel();
                    Location at = pos.clone();
                    Player thrower = plugin.getServer().getPlayer(throwerId);
                    if (plugin.flares() != null) {
                        plugin.flares().spawn(at, thrower, flareColor);
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }
}
