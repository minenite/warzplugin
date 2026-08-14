package net.minenite.warzplugin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * Sapling items are decoration leftovers — never leave them on the ground.
 * Blocks still stay put via CardForge / world-freeze; this only cleans items.
 */
public final class SaplingItemCleanupService implements Listener {

    private final WarzPlugin plugin;
    private BukkitTask task;

    public SaplingItemCleanupService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("sapling-item-despawn", true);
    }

    public void start() {
        stop();
        if (!isEnabled()) {
            return;
        }
        // Sweep already-dropped saplings (chunk load leftovers, race windows).
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::purgeAllWorlds, 20L, 40L);
        plugin.getLogger().info("Sapling item auto-despawn on.");
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!isEnabled()) {
            return;
        }
        if (isSaplingItem(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        if (!isEnabled()) {
            return;
        }
        if (isSaplingItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!isEnabled()) {
            return;
        }
        event.getItems().removeIf(item -> isSaplingItem(item.getItemStack()));
    }

    private void purgeAllWorlds() {
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (isSaplingItem(item.getItemStack())) {
                    item.remove();
                }
            }
        }
    }

    static boolean isSaplingItem(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        Material type = stack.getType();
        if (Tag.ITEMS_SAPLINGS.isTagged(type)) {
            return true;
        }
        String name = type.name();
        return name.endsWith("_SAPLING")
                || name.equals("MANGROVE_PROPAGULE")
                || name.equals("BAMBOO_SAPLING")
                || name.endsWith("_FUNGUS");
    }
}
