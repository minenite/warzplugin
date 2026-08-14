package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Ensures WarZ items carry compact lore + PDC detail for the companion Shift tooltip.
 * (Inventory Shift does not sneak — expansion is handled client-side.)
 */
public final class GunTooltipListener implements Listener {
    private final WarzPlugin plugin;

    public GunTooltipListener(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refreshInventory(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            refreshInventory(player);
        }
    }

    private void refreshInventory(Player player) {
        if (player == null || plugin.items() == null) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        for (ItemStack stack : inv.getContents()) {
            touch(stack);
        }
        for (ItemStack stack : inv.getArmorContents()) {
            touch(stack);
        }
        touch(inv.getItemInOffHand());
        touch(player.getItemOnCursor());
    }

    private void touch(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || plugin.items() == null) {
            return;
        }
        // Never "complete" a creative stub with lore alone — CreativeMaterializeListener
        // must rebuild via ItemFactory.create first.
        if (plugin.items().looksLikeCreativeStub(stack)) {
            return;
        }
        if (plugin.items().isGunItem(stack)) {
            plugin.items().applyGunInventoryLore(stack, false);
        } else if (plugin.items().isMagazine(stack)) {
            plugin.items().refreshMagazineLore(stack);
        } else if (plugin.items().isAttachmentPart(stack)) {
            plugin.items().refreshAttachmentPartLore(stack);
        }
    }
}
