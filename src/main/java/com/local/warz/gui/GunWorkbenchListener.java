package com.local.warz.gui;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class GunWorkbenchListener implements Listener {
    private final WarzPlugin plugin;

    public GunWorkbenchListener(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack hand = event.getItemInHand();
        if (!plugin.items().isGunWorkbenchItem(hand)) {
            return;
        }
        Block block = event.getBlockPlaced();
        block.setType(com.local.warz.runtime.GunWorkbenchService.BLOCK_TYPE, false);
        plugin.workbenches().mark(block.getLocation());
        event.getPlayer().sendMessage(net.kyori.adventure.text.Component.text(
                "Gun Workbench placed", net.kyori.adventure.text.format.NamedTextColor.GOLD));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!plugin.workbenches().isWorkbench(block)) {
            return;
        }
        plugin.workbenches().unmark(block.getLocation());
        event.setDropItems(false);
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            ItemStack drop = plugin.items().createGunWorkbenchItem();
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !plugin.workbenches().isWorkbench(block)) {
            return;
        }
        event.setCancelled(true);
        plugin.workbenchGui().open(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof GunWorkbenchGui.Holder)) {
            return;
        }

        int topSize = top.getSize();
        int raw = event.getRawSlot();
        boolean shift = event.isShiftClick()
                || event.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY;

        // Player inventory / hotbar — allow normal pickup/move, handle shift-deposit
        if (raw >= topSize) {
            if (shift) {
                ItemStack clicked = event.getCurrentItem();
                if (clicked != null && clicked.getType() != org.bukkit.Material.AIR) {
                    event.setCancelled(true);
                    if (plugin.workbenchGui().tryShiftDeposit(player, top, clicked)) {
                        event.setCurrentItem(clicked.getAmount() <= 0 ? null : clicked);
                        // Refresh NOW so CardForge's DENY sendAllDataToRemote includes the
                        // completed gun. Next-tick refresh left the result slot stale.
                        plugin.workbenchGui().applyLayout(player, top);
                    }
                }
            }
            // else: do NOT cancel — let the player pick up guns/parts onto the cursor
            return;
        }

        // Top inventory (workbench UI)
        int slot = event.getSlot();
        if (GunWorkbenchGui.isInputSlot(slot)) {
            // Allow vanilla place/take; refresh next tick after vanilla clicked()
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.workbenchGui().applyLayout(player, top));
            return; // not cancelled
        }

        event.setCancelled(true);
        if (slot == GunWorkbenchGui.SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == GunWorkbenchGui.SLOT_RESULT) {
            plugin.workbenchGui().takeResult(player, top);
            return;
        }
        // Decorative panes — shift/click a held attachment onto the correct rail
        ItemStack cursor = event.getCursor();
        if (cursor != null && cursor.getType() != org.bukkit.Material.AIR) {
            if (plugin.workbenchGui().tryShiftDeposit(player, top, cursor)) {
                event.setCursor(cursor.getAmount() <= 0 ? null : cursor);
                plugin.workbenchGui().applyLayout(player, top);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GunWorkbenchGui.Holder)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        int topSize = top.getSize();
        // Only allow drags that touch input slots (and/or player inv). Block decorative top slots.
        for (int raw : event.getRawSlots()) {
            if (raw < topSize && !GunWorkbenchGui.isInputSlot(raw)) {
                event.setCancelled(true);
                return;
            }
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (event.getWhoClicked() instanceof Player player) {
                plugin.workbenchGui().applyLayout(player, top);
            }
        });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (event.getInventory().getHolder() instanceof GunWorkbenchGui.Holder) {
            plugin.workbenchGui().clear(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.workbenchGui().clear(event.getPlayer());
    }
}
