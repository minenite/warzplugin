package net.minenite.warzplugin;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Cancel interaction in the profile chest GUI; clan slot opens clan roster. */
public final class ProfileGuiListener implements Listener {
    private final WarzPlugin plugin;

    public ProfileGuiListener(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof ProfileGuiService.Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (plugin.profileGui() != null) {
            plugin.profileGui().handleClick(player, event.getSlot(), holder, event.isRightClick());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ProfileGuiService.Holder) {
            event.setCancelled(true);
        }
    }
}
