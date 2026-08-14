package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.model.RoundDefinition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Mag load/unload, empty-stack merge/split, anti-dupe craft dump, auto-fill.
 * Loaded mags never stack; only empty mags stack.
 */
public final class MagazineListener implements Listener {
    private final WarzPlugin plugin;

    public MagazineListener(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();

        // Shift-click loose rounds → fill matching mags in the player's inventory.
        if (event.isShiftClick()
                && current != null
                && !current.getType().isAir()
                && plugin.items().roundOf(current).isPresent()
                && !plugin.items().isMagazine(current)) {
            int loaded = plugin.items().fillMatchingMagsFrom(player, current);
            if (loaded > 0) {
                event.setCancelled(true);
                boolean fromPlayerInv = event.getClickedInventory() instanceof PlayerInventory;
                if (current.getAmount() <= 0) {
                    event.setCurrentItem(null);
                } else if (fromPlayerInv) {
                    event.setCurrentItem(current);
                } else {
                    plugin.items().giveOrDrop(player, current.clone());
                    event.setCurrentItem(null);
                }
                player.sendActionBar(ItemFactory.colorize("&aLoaded &f" + loaded + " &7into mag"));
                player.updateInventory();
                return;
            }
        }

        // Loaded magazines must never merge - each holds its own rounds, and letting
        // them stack would fold one lot into the other.
        if (plugin.items().isMagazine(cursor) && plugin.items().isMagazine(current)
                && cursor.getAmount() > 0 && current.getAmount() > 0
                && (plugin.items().magazineCount(cursor) > 0 || plugin.items().magazineCount(current) > 0)
                && plugin.items().magazineType(cursor) == plugin.items().magazineType(current)) {
            event.setCancelled(true);
            player.sendActionBar(ItemFactory.colorize("&cLoaded mags can't stack"));
            player.updateInventory();
            return;
        }

        // Empty magazines are left to the server itself. They are ordinary
        // identical items now, so stacking, splitting and shift-clicking all work
        // on their own - and doing it by hand here is what left a stack drawn
        // wrongly until the window was touched, and what broke on a right-click.

        // Cursor ammo → slot mag (same material OK — .50 clay mag + clay rounds)
        if (plugin.items().roundOf(cursor).isPresent() && plugin.items().isMagazine(current)) {
            event.setCancelled(true);
            ItemStack mag = current;
            ItemStack restEmpties = null;
            if (mag.getAmount() > 1) {
                restEmpties = mag.clone();
                restEmpties.setAmount(mag.getAmount() - 1);
                plugin.items().setMagazineContents(restEmpties, 0, null);
                mag = mag.clone();
                mag.setAmount(1);
                plugin.items().setMagazineContents(mag, 0, null);
            }
            int before = plugin.items().magazineCount(mag);
            int took = plugin.items().fillMagazineFrom(mag, cursor);
            if (took > 0) {
                event.setCurrentItem(mag);
                event.setCursor(cursor.getAmount() <= 0 ? null : cursor);
                if (restEmpties != null) {
                    plugin.items().giveOrDrop(player, restEmpties);
                }
                player.sendActionBar(ItemFactory.colorize("&aLoaded &f" + took
                        + " &7→ [" + plugin.items().magazineCount(mag)
                        + "/" + plugin.items().magazineType(mag).capacity() + "]"));
                player.updateInventory();
            } else {
                if (restEmpties != null) {
                    event.setCurrentItem(current);
                }
                MagazineType mt = plugin.items().magazineType(mag);
                Optional<RoundDefinition> r = plugin.items().roundOf(cursor);
                if (mt != null && r.isPresent()
                        && !com.local.warz.config.AmmoCaliber.sameFamily(r.get().caliber(), mt.caliber())) {
                    player.sendActionBar(ItemFactory.colorize("&cWrong caliber for this mag"));
                } else if (mt != null && before >= mt.capacity()) {
                    player.sendActionBar(ItemFactory.colorize("&eMag full"));
                }
            }
            return;
        }

        // Cursor mag ← slot ammo
        if (plugin.items().isMagazine(cursor) && plugin.items().roundOf(current).isPresent()) {
            event.setCancelled(true);
            ItemStack mag = cursor;
            ItemStack restEmpties = null;
            if (mag.getAmount() > 1) {
                restEmpties = mag.clone();
                restEmpties.setAmount(mag.getAmount() - 1);
                plugin.items().setMagazineContents(restEmpties, 0, null);
                mag = mag.clone();
                mag.setAmount(1);
                plugin.items().setMagazineContents(mag, 0, null);
            }
            int before = plugin.items().magazineCount(mag);
            int took = plugin.items().fillMagazineFrom(mag, current);
            if (took > 0) {
                event.setCursor(mag);
                event.setCurrentItem(current.getAmount() <= 0 ? null : current);
                if (restEmpties != null) {
                    plugin.items().giveOrDrop(player, restEmpties);
                }
                player.sendActionBar(ItemFactory.colorize("&aLoaded &f" + took
                        + " &7→ [" + plugin.items().magazineCount(mag)
                        + "/" + plugin.items().magazineType(mag).capacity() + "]"));
                player.updateInventory();
            } else {
                MagazineType mt = plugin.items().magazineType(mag);
                Optional<RoundDefinition> r = plugin.items().roundOf(current);
                if (mt != null && r.isPresent()
                        && !com.local.warz.config.AmmoCaliber.sameFamily(r.get().caliber(), mt.caliber())) {
                    player.sendActionBar(ItemFactory.colorize("&cWrong caliber for this mag"));
                } else if (mt != null && before >= mt.capacity()) {
                    player.sendActionBar(ItemFactory.colorize("&eMag full"));
                }
            }
            return;
        }

        // Same-material safety: never let vanilla merge clay/seed mag stacks with ammo stacks
        if (cursor != null && current != null
                && !cursor.getType().isAir() && !current.getType().isAir()
                && cursor.getType() == current.getType()
                && ((plugin.items().isMagazine(cursor) && plugin.items().roundOf(current).isPresent())
                || (plugin.items().isMagazine(current) && plugin.items().roundOf(cursor).isPresent()))) {
            event.setCancelled(true);
            return;
        }

        // Shift-click loaded mag → unload
        // Shift-left = all; Shift-right = next type only; plain right on loaded = top 5
        if (plugin.items().isMagazine(current) && plugin.items().magazineCount(current) > 0) {
            if (event.getClick() == ClickType.SHIFT_LEFT) {
                event.setCancelled(true);
                sanitizeInPlace(player, current);
                int n = plugin.items().unloadMagazineTo(player.getInventory(), current);
                event.setCurrentItem(current);
                if (n > 0) {
                    player.sendActionBar(ItemFactory.colorize("&eUnloaded &f" + n + " &7rounds"));
                }
                return;
            }
            if (event.getClick() == ClickType.SHIFT_RIGHT) {
                event.setCancelled(true);
                sanitizeInPlace(player, current);
                int n = plugin.items().unloadMagazineNextType(player.getInventory(), current);
                event.setCurrentItem(current);
                if (n > 0) {
                    player.sendActionBar(ItemFactory.colorize(
                            "&eUnloaded &f" + n + " &7of next type"));
                }
                return;
            }
            if (event.getClick() == ClickType.RIGHT
                    && (cursor == null || cursor.getType().isAir())) {
                event.setCancelled(true);
                sanitizeInPlace(player, current);
                int n = plugin.items().unloadMagazinePartial(player.getInventory(), current, 5);
                event.setCurrentItem(current);
                if (n > 0) {
                    player.sendActionBar(ItemFactory.colorize(
                            "&eUnloaded &f" + n + " &7(top of mag)"));
                }
            }
        }
    }

    /** After any inventory click, fix illegal loaded stacks (anti-dupe). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClickMonitor(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> sanitizeInventory(player));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        ItemStack old = event.getOldCursor();
        if (!plugin.items().isMagazine(old)) {
            return;
        }
        // Never drag-distribute loaded mags (vanilla clones NBT → dupe)
        if (plugin.items().magazineCount(old) > 0) {
            event.setCancelled(true);
            return;
        }
        if (event.getRawSlots().size() > 1 && plugin.items().magazineCount(old) > 0) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragMonitor(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> sanitizeInventory(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHotbarSwap(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            sanitizeInventory(player);
            tryAutoFillHeld(player);
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack mag = findLoneLoadedMag(event.getInventory().getMatrix());
        if (mag == null) {
            return;
        }
        // Only single loaded mags (amount 1) — stacked loaded is illegal
        if (mag.getAmount() != 1) {
            event.getInventory().setResult(null);
            return;
        }
        int count = plugin.items().magazineCount(mag);
        String rid = plugin.items().magazineRoundId(mag);
        if (rid == null || count <= 0) {
            return;
        }
        Optional<RoundDefinition> round = plugin.rounds().get(rid);
        if (round.isEmpty()) {
            return;
        }
        // Preview: next type × total (mixed dumps still give every type on craft).
        ItemStack preview = plugin.items().createRound(round.get(), count);
        if (plugin.items().magazineLoadCounts(mag).size() > 1) {
            var meta = preview.getItemMeta();
            meta.displayName(ItemFactory.colorize("&eUnload mixed mag &7(" + count + ")"));
            preview.setItemMeta(meta);
        }
        event.getInventory().setResult(preview);
    }

    /**
     * Fully manual craft: cancel vanilla, take the mag, give ammo, return empty mag.
     * Prevents player-inventory 2×2 craft dupes from partial consumes / result cloning.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory() instanceof CraftingInventory craft)) {
            return;
        }
        ItemStack[] matrix = craft.getMatrix();
        ItemStack mag = findLoneLoadedMag(matrix);
        if (mag == null) {
            return;
        }
        MagazineType type = plugin.items().magazineType(mag);
        int count = plugin.items().magazineCount(mag);
        if (type == null || count <= 0 || mag.getAmount() != 1) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        // Clear mag from matrix first so a re-trigger can't dump twice
        ItemStack[] cleared = matrix.clone();
        for (int i = 0; i < cleared.length; i++) {
            if (cleared[i] != null && plugin.items().isMagazine(cleared[i])
                    && plugin.items().magazineCount(cleared[i]) > 0) {
                cleared[i] = null;
            }
        }
        craft.setMatrix(cleared);
        craft.setResult(null);

        // Work on a copy so extract empties the right stack
        ItemStack magCopy = mag.clone();
        List<ItemStack> ammoStacks = plugin.items().extractMagazineAmmo(magCopy);
        ItemStack empty = plugin.items().createMagazine(type, 0, null, 1);

        if (event.isShiftClick()) {
            plugin.items().giveOrDrop(player, ammoStacks);
        } else if (ammoStacks.size() == 1) {
            ItemStack ammo = ammoStacks.get(0);
            ItemStack onCursor = event.getCursor();
            if (onCursor == null || onCursor.getType().isAir()) {
                player.setItemOnCursor(ammo);
            } else if (onCursor.isSimilar(ammo)
                    && onCursor.getAmount() + ammo.getAmount() <= onCursor.getMaxStackSize()) {
                onCursor.setAmount(onCursor.getAmount() + ammo.getAmount());
                player.setItemOnCursor(onCursor);
            } else {
                plugin.items().giveOrDrop(player, ammo);
            }
        } else {
            // Mixed: put first on cursor if free, rest to inv
            ItemStack onCursor = event.getCursor();
            if ((onCursor == null || onCursor.getType().isAir()) && !ammoStacks.isEmpty()) {
                player.setItemOnCursor(ammoStacks.remove(0));
            }
            plugin.items().giveOrDrop(player, ammoStacks);
        }
        plugin.items().giveOrDrop(player, empty);
        player.sendActionBar(ItemFactory.colorize(
                "&eEmptied mag &7→ &f" + count + " &7rounds + empty mag"));
    }

    private void sanitizeInventory(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        List<ItemStack> extras = new ArrayList<>();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (!plugin.items().isMagazine(it)) {
                continue;
            }
            extras.addAll(plugin.items().applyMagazineStackRules(it));
            inv.setItem(i, it);
        }
        ItemStack off = inv.getItemInOffHand();
        if (plugin.items().isMagazine(off)) {
            extras.addAll(plugin.items().applyMagazineStackRules(off));
            inv.setItemInOffHand(off);
        }
        ItemStack cursor = player.getItemOnCursor();
        if (plugin.items().isMagazine(cursor)) {
            extras.addAll(plugin.items().applyMagazineStackRules(cursor));
            player.setItemOnCursor(cursor.getAmount() <= 0 ? null : cursor);
        }
        plugin.items().giveOrDrop(player, extras);
    }

    private void sanitizeInPlace(Player player, ItemStack mag) {
        List<ItemStack> extras = plugin.items().applyMagazineStackRules(mag);
        plugin.items().giveOrDrop(player, extras);
    }

    private void tryAutoFillHeld(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!plugin.items().isMagazine(hand)) {
            return;
        }
        // Peel one from empty stack before auto-fill
        if (hand.getAmount() > 1 && plugin.items().magazineCount(hand) <= 0) {
            ItemStack rest = hand.clone();
            rest.setAmount(hand.getAmount() - 1);
            plugin.items().setMagazineContents(rest, 0, null);
            hand.setAmount(1);
            plugin.items().setMagazineContents(hand, 0, null);
            int n = plugin.items().autoFillMagazine(player.getInventory(), hand);
            player.getInventory().setItemInMainHand(hand);
            plugin.items().giveOrDrop(player, rest);
            if (n > 0) {
                player.sendActionBar(ItemFactory.colorize("&aAuto-loaded &f" + n
                        + " &7→ [" + plugin.items().magazineCount(hand)
                        + "/" + plugin.items().magazineType(hand).capacity() + "]"));
            }
            return;
        }
        if (plugin.items().magazineCount(hand) >= plugin.items().magazineTotalCapacity(hand)) {
            return;
        }
        if (hand.getAmount() != 1) {
            return;
        }
        int n = plugin.items().autoFillMagazine(player.getInventory(), hand);
        if (n > 0) {
            player.getInventory().setItemInMainHand(hand);
            player.sendActionBar(ItemFactory.colorize("&aAuto-loaded &f" + n
                    + " &7→ [" + plugin.items().magazineCount(hand)
                    + "/" + plugin.items().magazineType(hand).capacity() + "]"));
        }
    }

    private ItemStack findLoneLoadedMag(ItemStack[] matrix) {
        if (matrix == null) {
            return null;
        }
        ItemStack found = null;
        for (ItemStack it : matrix) {
            if (it == null || it.getType().isAir()) {
                continue;
            }
            if (!plugin.items().isMagazine(it) || plugin.items().magazineCount(it) <= 0) {
                return null;
            }
            if (found != null) {
                return null;
            }
            found = it;
        }
        return found;
    }
}
