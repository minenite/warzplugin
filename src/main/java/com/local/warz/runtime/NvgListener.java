package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

/** Applies realistic NVG wear effects while the Shadow Company helmet is equipped. */
public final class NvgListener implements Listener {
    private final WarzPlugin plugin;
    private BukkitTask task;

    public NvgListener(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                NvgGear.tickWearer(plugin, player);
                ThermalGear.tickWearer(plugin, player);
            }
        }, 10L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Effects expire naturally; nothing sticky to clear.
    }
}
