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

/**
 * Smoke throws — entity-free arc simulation (no item merge / overwrite bugs).
 * Each throw is an independent runnable; many can be in flight and active at once.
 */
public final class SmokeListener implements Listener {
    private static final long THROW_DEBOUNCE_MS = 80L;
    private static final int MAX_FLIGHT_TICKS = 80;

    private final WarzPlugin plugin;
    private final Map<UUID, Long> lastThrowMs = new ConcurrentHashMap<>();
    private final AtomicInteger inFlightCount = new AtomicInteger();

    public SmokeListener(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    /** No entity tick needed anymore. */
    public void tick() {
        // flights are BukkitRunnables
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack main = player.getInventory().getItemInMainHand();
        SmokeType mainType = plugin.items().smokeType(main);

        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            if (mainType != null) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND || mainType == null) {
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
        throwSmoke(player, main, mainType);
    }

    private void throwSmoke(Player player, ItemStack stack, SmokeType type) {
        if (player.getGameMode() != GameMode.CREATIVE) {
            int amt = stack.getAmount();
            if (amt <= 1) {
                player.getInventory().setItemInMainHand(null);
            } else {
                stack.setAmount(amt - 1);
            }
        }

        Location start = player.getEyeLocation().clone();
        Vector vel = start.getDirection().normalize().multiply(1.2);
        vel.setY(vel.getY() * 0.78 + 0.24);
        UUID throwerId = player.getUniqueId();
        int flightId = inFlightCount.incrementAndGet();

        player.swingMainHand();
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 0.65f, 0.7f);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, 0.4f, 1.35f);

        // Tiny visual crumb so it feels like a thrown grenade (does not own the fuse)
        try {
            ItemStack crumb = plugin.items().createSmokeGrenade(type, 1);
            var crumbMeta = crumb.getItemMeta();
            crumbMeta.getPersistentDataContainer().set(
                    WarzKeys.of("smoke_throw_id"),
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
            int ticks;

            @Override
            public void run() {
                ticks++;
                if (pos.getWorld() == null) {
                    cancel();
                    return;
                }

                // Gravity + integrate
                v.setY(v.getY() - 0.045);
                Location next = pos.clone().add(v);

                // Block collision along the step
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

                if ((grounded && ticks >= 5) || ticks >= MAX_FLIGHT_TICKS) {
                    cancel();
                    Location at = pos.clone();
                    snapNearGround(at);
                    Player thrower = plugin.getServer().getPlayer(throwerId);
                    if (plugin.smoke() != null) {
                        plugin.smoke().spawn(at, type, thrower);
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static void snapNearGround(Location at) {
        if (at.getWorld() == null) {
            return;
        }
        var block = at.getBlock();
        for (int i = 0; i < 12 && block.getType().isAir(); i++) {
            at.add(0, -0.5, 0);
            block = at.getBlock();
        }
        if (!block.getType().isAir()) {
            at.setY(block.getY() + 1.05);
        }
    }
}
