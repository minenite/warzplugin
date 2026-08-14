package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Custom "Infected" status — uses the hunger potion effect (renamed in the companion lang).
 * Rare zombie/husk hits and dirty water apply it.
 */
public final class InfectionService implements Listener {
    private static final Set<EntityType> INFECTORS = EnumSet.of(
            EntityType.ZOMBIE,
            EntityType.HUSK,
            EntityType.ZOMBIE_VILLAGER,
            EntityType.PARCHED
    );
    /** 1-in-N chance on hit by zombie/husk. */
    public static final int ZOMBIE_HIT_CHANCE = 30;
    private static final int INFECTION_SECONDS = 10;
    private static final int EFFECT_REFRESH = 40;

    private final WarzPlugin plugin;
    private final Map<UUID, Long> infectedUntilMs = new ConcurrentHashMap<>();
    private BukkitTask task;

    public InfectionService(WarzPlugin plugin) {
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
        for (Player p : Bukkit.getOnlinePlayers()) {
            clear(p);
        }
        infectedUntilMs.clear();
    }

    public boolean isInfected(Player player) {
        if (player == null) {
            return false;
        }
        Long until = infectedUntilMs.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    public void infect(Player player, String reason) {
        if (player == null || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        long until = System.currentTimeMillis() + INFECTION_SECONDS * 1000L;
        infectedUntilMs.merge(player.getUniqueId(), until, Math::max);
        applyEffects(player);
        player.sendMessage(ItemFactory.colorize("&2&lInfected! &7"
                + (reason == null || reason.isBlank() ? "Seek clean water / rest." : reason)));
        player.sendActionBar(ItemFactory.colorize("&2&lInfected"));
    }

    public void clear(Player player) {
        if (player == null) {
            return;
        }
        infectedUntilMs.remove(player.getUniqueId());
        player.removePotionEffect(PotionEffectType.HUNGER);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Long until = infectedUntilMs.get(player.getUniqueId());
            if (until == null) {
                continue;
            }
            if (until <= now) {
                infectedUntilMs.remove(player.getUniqueId());
                player.removePotionEffect(PotionEffectType.HUNGER);
                player.sendActionBar(ItemFactory.colorize("&aInfection faded."));
                continue;
            }
            applyEffects(player);
            if (player.getTicksLived() % 60 < 20) {
                player.sendActionBar(ItemFactory.colorize("&2&lInfected"));
            }
        }
    }

    private void applyEffects(Player player) {
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.HUNGER, EFFECT_REFRESH + 10, 1, false, true, true));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onZombieHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!(event.getDamager() instanceof LivingEntity attacker)) {
            return;
        }
        if (!INFECTORS.contains(attacker.getType())) {
            return;
        }
        if (ThreadLocalRandom.current().nextInt(ZOMBIE_HIT_CHANCE) != 0) {
            return;
        }
        infect(victim, "A zombie bite got into your blood.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Keep infection across quit via until-ms map; drop if expired on next join tick.
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        clear(event.getPlayer());
    }
}
