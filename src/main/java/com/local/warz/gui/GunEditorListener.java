package com.local.warz.gui;

import net.minenite.warzplugin.WarzPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class GunEditorListener implements Listener {
    private final WarzPlugin plugin;

    public GunEditorListener(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof GunEditorService.Holder)) {
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
        plugin.editor().handleClick(player, event.getSlot(), left, right, shift);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof GunEditorService.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof GunEditorService.Holder)) {
            return;
        }
        // Keep session for chat prompts; clear only if not prompting.
        if (!plugin.editor().hasPrompt(player)) {
            // keep draft in memory so reopen is possible via command
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!plugin.editor().hasPrompt(player)) {
            return;
        }
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        event.setCancelled(true);
        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.editor().handleChat(player, message));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.editor().clear(event.getPlayer());
    }
}
