package com.local.warz.gui;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class GiveGunMenuListener implements Listener {
	private final WarzPlugin plugin;

	public GiveGunMenuListener(WarzPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onClick(InventoryClickEvent event) {
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		if (!(event.getInventory().getHolder() instanceof GiveGunMenuService.Holder)) {
			return;
		}
		event.setCancelled(true);
		if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
			return;
		}
		boolean left = event.isLeftClick();
		boolean right = event.isRightClick();
		boolean shift = event.isShiftClick();
		if (event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP
				|| event.getClick() == ClickType.MIDDLE) {
			left = false;
			right = false;
		}
		plugin.giveMenu().handleClick(player, event.getSlot(), left, right, shift);
	}

	@EventHandler
	public void onDrag(InventoryDragEvent event) {
		if (event.getInventory().getHolder() instanceof GiveGunMenuService.Holder) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		plugin.giveMenu().clear(event.getPlayer());
	}
}
