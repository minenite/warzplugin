package com.local.warz.gui;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.runtime.DroneDatalinkService;
import com.local.warz.runtime.DroneSeatService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.RayTraceResult;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /warz create hub — guns, drone seats, radio towers, satellites.
 */
public final class WarzCreateMenuService implements Listener {
    public enum PendingKind {
        SEAT,
        TOWER,
        SATELLITE
    }

    public static final class CreatePending {
        public final PendingKind kind;

        CreatePending(PendingKind kind) {
            this.kind = kind;
        }
    }

    private static final int GUI_SIZE = 27;

    private final WarzPlugin plugin;
    private final Map<UUID, CreatePending> pending = new ConcurrentHashMap<>();

    public WarzCreateMenuService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (player == null) {
            return;
        }
        CreateMenuHolder holder = new CreateMenuHolder(player.getUniqueId());
        Inventory inv = ChestInventories.create(holder, GUI_SIZE,
                Component.text("WarZ Create", NamedTextColor.DARK_PURPLE)
                        .decoration(TextDecoration.ITALIC, false));
        holder.inventory = inv;

        inv.setItem(11, menuIcon(Material.IRON_HORSE_ARMOR, "Create Gun",
                NamedTextColor.GREEN, "Open the gun editor (new weapon)"));
        inv.setItem(13, menuIcon(Material.OAK_STAIRS, "Drone Seat",
                NamedTextColor.AQUA, "Register stairs as UAV control seat"));
        inv.setItem(15, menuIcon(Material.BEACON, "Radio Tower",
                NamedTextColor.YELLOW, "Register block + link to seat"));
        inv.setItem(16, menuIcon(Material.LIGHTNING_ROD, "Satellite",
                NamedTextColor.LIGHT_PURPLE, "Register block + uplink to tower"));

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.displayName(Component.space());
        filler.setItemMeta(fm);
        for (int i = 0; i < GUI_SIZE; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, filler);
            }
        }

        player.openInventory(inv);
    }

    private static ItemStack menuIcon(Material mat, String name, NamedTextColor color, String loreLine) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                Component.text(loreLine, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof CreateMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!player.getUniqueId().equals(holder.playerId)) {
            return;
        }
        int slot = event.getRawSlot();
        player.closeInventory();
        switch (slot) {
            case 11 -> plugin.editor().openCreate(player);
            case 13 -> startSeatPending(player);
            case 15 -> startTowerPending(player);
            case 16 -> startSatellitePending(player);
            default -> {
            }
        }
    }

    private void startSeatPending(Player player) {
        Block stairs = lookingStairs(player);
        if (stairs != null && plugin.droneSeats() != null) {
            plugin.droneSeats().setLooking(player, DroneSeatService.VEHICLE_MQ9);
            return;
        }
        pending.put(player.getUniqueId(), new CreatePending(PendingKind.SEAT));
        player.sendMessage(Component.text("Look at stairs and right-click to register drone seat.",
                NamedTextColor.AQUA));
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.2f);
    }

    private void startTowerPending(Player player) {
        pending.put(player.getUniqueId(), new CreatePending(PendingKind.TOWER));
        player.sendMessage(Component.text("Right-click a block to register radio tower, then link a seat.",
                NamedTextColor.YELLOW));
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.0f);
    }

    private void startSatellitePending(Player player) {
        pending.put(player.getUniqueId(), new CreatePending(PendingKind.SATELLITE));
        player.sendMessage(Component.text("Right-click a block to register satellite, then uplink a tower.",
                NamedTextColor.LIGHT_PURPLE));
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 0.8f);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPendingInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("warz.admin")) {
            return;
        }

        // Datalink seat / sat completion (from tower registration flow)
        if (plugin.datalink() != null) {
            if (plugin.datalink().hasPendingSeatLink(player.getUniqueId())
                    && plugin.droneSeats() != null && plugin.droneSeats().isSeat(clicked)) {
                event.setCancelled(true);
                plugin.datalink().tryCompleteSeatLink(player, clicked);
                return;
            }
            if (plugin.datalink().hasPendingSatLink(player.getUniqueId())
                    && plugin.datalink().towerAt(clicked).isPresent()) {
                event.setCancelled(true);
                plugin.datalink().tryCompleteSatelliteLink(player, clicked);
                return;
            }
        }

        CreatePending state = pending.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        event.setCancelled(true);
        switch (state.kind) {
            case SEAT -> {
                if (!(clicked.getBlockData() instanceof Stairs)) {
                    player.sendMessage(Component.text("Must be a stairs block.", NamedTextColor.RED));
                    return;
                }
                pending.remove(player.getUniqueId());
                if (plugin.droneSeats() != null) {
                    plugin.droneSeats().registerSeat(player, clicked, DroneSeatService.VEHICLE_MQ9);
                }
            }
            case TOWER -> {
                pending.remove(player.getUniqueId());
                if (plugin.datalink() != null) {
                    plugin.datalink().registerTower(player, clicked);
                }
            }
            case SATELLITE -> {
                pending.remove(player.getUniqueId());
                if (plugin.datalink() != null) {
                    plugin.datalink().registerSatellite(player, clicked);
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
        if (plugin.datalink() != null) {
            plugin.datalink().clearPending(event.getPlayer().getUniqueId());
        }
    }

    private Block lookingStairs(Player player) {
        RayTraceResult hit = player.rayTraceBlocks(6.0);
        if (hit == null || hit.getHitBlock() == null) {
            return null;
        }
        Block block = hit.getHitBlock();
        if (!(block.getBlockData() instanceof Stairs)) {
            return null;
        }
        return block;
    }

    private static final class CreateMenuHolder implements InventoryHolder {
        final UUID playerId;
        Inventory inventory;

        CreateMenuHolder(UUID playerId) {
            this.playerId = playerId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
