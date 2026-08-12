package net.minenite.warzplugin;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Blocks taking items out of the clan roster GUI. */
public final class ClanGuiListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof ClanGuiService.Holder)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ClanGuiService.Holder) {
            event.setCancelled(true);
        }
    }
}
