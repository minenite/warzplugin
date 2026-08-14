package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

public final class GlassListener implements Listener {
    private final WarzPlugin plugin;

    public GlassListener(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack hand = event.getItemInHand();
        GlassType type = plugin.items().glassType(hand);
        if (type == null) {
            return;
        }
        boolean pane = plugin.items().isGlassPaneItem(hand);
        Block block = event.getBlockPlaced();
        block.setType(pane ? type.paneMaterial() : type.blockMaterial(), false);
        plugin.glass().mark(block, type);
        event.getPlayer().sendActionBar(net.kyori.adventure.text.Component.text(
                "Placed " + type.displayName() + (pane ? " Pane" : ""),
                net.kyori.adventure.text.format.NamedTextColor.AQUA));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!plugin.glass().isTacticalGlass(block)) {
            return;
        }
        GlassType type = plugin.glass().typeAt(block);
        boolean pane = plugin.glass().isPane(block);
        plugin.glass().unmark(block);
        event.setDropItems(false);
        if (type != null && event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            ItemStack drop = pane
                    ? plugin.items().createGlassPane(type)
                    : plugin.items().createGlassBlock(type);
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
        }
    }
}
