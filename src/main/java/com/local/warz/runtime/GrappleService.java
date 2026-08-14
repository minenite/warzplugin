package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grappling hook modeled on AeroGrapple / Snowgears:
 * <ul>
 *   <li>PlayerFishEvent only (FISHING / IN_GROUND / FAILED_ATTEMPT / REEL_IN / CAUGHT_*)</li>
 *   <li>Single velocity impulse — no multi-tick fly loop (that caused sky-launch)</li>
 *   <li>Y from Aero's distance formula; sticky dampen a few ticks later</li>
 * </ul>
 */
public final class GrappleService implements Listener {
    public static final int MAX_USES = 5;

    /** Stronger than Aero basic (1.0); closer to air_hook x/z 1.5 with a bit more climb. */
    private static final Vector PULL_MULT = new Vector(1.65, 1.4, 1.65);
    private static final Vector THROW_MULT = new Vector(1.1, 1.05, 1.1);
    private static final double Y_SCALE = 1.35;
    private static final double MAX_Y = 1.85;
    private static final long FALL_PROTECT_MS = 4500L;

    private final WarzPlugin plugin;
    private final Map<UUID, Long> fallProtectUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> useCooldownUntil = new ConcurrentHashMap<>();

    public GrappleService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        // no tick loop — impulse based
    }

    public void stop() {
        fallProtectUntil.clear();
        useCooldownUntil.clear();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand() != null ? event.getHand() : EquipmentSlot.HAND;
        ItemStack rod = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (!plugin.items().isGrapplingHook(rod)) {
            return;
        }

        FishHook hook = event.getHook();
        UUID id = player.getUniqueId();

        switch (event.getState()) {
            case FISHING -> {
                // Cast — boost throw like Aero handleThrownHook
                if (hook != null) {
                    hook.setApplyLure(false);
                    hook.setMinWaitTime(20_000);
                    hook.setMaxWaitTime(20_000);
                    Vector vel = hook.getVelocity().clone().multiply(THROW_MULT);
                    // Looking up: slight extra loft (Aero pitch check ~ looking skyward)
                    if (player.getLocation().getPitch() < -15f) {
                        vel.setY(vel.getY() * 1.15);
                    }
                    hook.setVelocity(vel);
                }
                player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 0.85f, 1.05f);
            }
            case IN_GROUND, FAILED_ATTEMPT -> {
                // Aero: both mean "hook hit something solid" — pull on this click
                event.setCancelled(true);
                event.setExpToDrop(0);
                if (hook == null) {
                    return;
                }
                pullSelf(player, hand, rod, hook.getLocation().clone());
                if (hook.isValid()) {
                    hook.remove();
                }
            }
            case REEL_IN -> {
                // Reel with bobber still out — pull to bobber (air or missed IN_GROUND)
                event.setCancelled(true);
                event.setExpToDrop(0);
                if (hook == null || !hook.isValid()) {
                    return;
                }
                Location dest = hook.getLocation().clone();
                pullSelf(player, hand, rod, dest);
                if (hook.isValid()) {
                    hook.remove();
                }
            }
            case CAUGHT_ENTITY -> {
                // Pull yourself to the entity (WarZ: self-pull only)
                event.setCancelled(true);
                event.setExpToDrop(0);
                Location dest = event.getCaught() != null
                        ? event.getCaught().getLocation().clone()
                        : (hook != null ? hook.getLocation().clone() : null);
                if (dest != null) {
                    pullSelf(player, hand, rod, dest);
                }
                if (hook != null && hook.isValid()) {
                    hook.remove();
                }
            }
            case CAUGHT_FISH, BITE, LURED -> {
                event.setCancelled(true);
                event.setExpToDrop(0);
            }
            default -> {
            }
        }
    }

    /**
     * Aero getY + normalize*mult + replace velocity (cleaner yank than add),
     * no early sticky dampen (that was killing the swing mid-air).
     */
    private void pullSelf(Player player, EquipmentSlot hand, ItemStack rod, Location hookLoc) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (useCooldownUntil.getOrDefault(id, 0L) > now) {
            return;
        }
        useCooldownUntil.put(id, now + 250L);

        Location from = player.getLocation();
        if (hookLoc.getWorld() == null || from.getWorld() == null
                || !hookLoc.getWorld().equals(from.getWorld())) {
            return;
        }

        Location latch = hookLoc.clone();
        double d = latch.distance(from);
        if (d < 0.5) {
            return;
        }

        double y = Math.min(MAX_Y, aeroY(latch, from, d) * Y_SCALE);

        Vector impulse = latch.toVector().subtract(from.toVector()).normalize();
        impulse.setY(y);
        impulse.multiply(PULL_MULT);

        fallProtectUntil.put(id, now + FALL_PROTECT_MS);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || player.isDead()) {
                return;
            }
            // Replace velocity for a consistent yank (standing still still gets full pull)
            player.setVelocity(impulse);
            player.setFallDistance(0f);
            player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f, 0.75f);

            // Soft settle only after the swing has traveled — and only if still airborne high
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline() || player.isDead()) {
                    return;
                }
                if (player.isOnGround()) {
                    Vector damp = player.getVelocity().clone().multiply(new Vector(0.45, 0.2, 0.45));
                    player.setVelocity(damp);
                }
                player.setFallDistance(0f);
            }, 12L);
        }, 2L);

        consumeUse(player, hand, rod);
    }

    /**
     * AeroGrapple getY (whole expression / 25):
     * {@code [ ((1+d)*dy/d) - ((-0.08)*d) ] / 25} then ±0.5 bias.
     */
    private static double aeroY(Location hook, Location player, double d) {
        double dy = hook.getY() - player.getY();
        double y = (((1.0 + d) * dy / d) - ((-0.08) * d)) / 25.0;
        if (y > 0.0) {
            y += 0.5;
        } else {
            y -= 0.5;
        }
        return y;
    }

    private void consumeUse(Player player, EquipmentSlot hand, ItemStack rod) {
        int uses = plugin.items().grappleUses(rod) - 1;
        if (uses <= 0) {
            if (hand == EquipmentSlot.OFF_HAND) {
                player.getInventory().setItemInOffHand(null);
            } else {
                player.getInventory().setItemInMainHand(null);
            }
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1.4f);
            player.sendActionBar(ItemFactory.colorize("&cGrappling hook spent"));
            return;
        }
        ItemStack updated = plugin.items().withGrappleUses(rod, uses);
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(updated);
        } else {
            player.getInventory().setItemInMainHand(updated);
        }
        player.sendActionBar(ItemFactory.colorize("&7Grapple uses: &e" + uses + "&7/" + MAX_USES));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        Long until = fallProtectUntil.get(player.getUniqueId());
        if (until != null && until > System.currentTimeMillis()) {
            event.setCancelled(true);
            player.setFallDistance(0f);
        } else if (until != null) {
            fallProtectUntil.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        fallProtectUntil.remove(id);
        useCooldownUntil.remove(id);
    }
}
