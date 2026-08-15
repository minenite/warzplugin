package com.local.warz.gui;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.runtime.GripType;
import com.local.warz.runtime.ItemFactory;
import com.local.warz.runtime.LaserModColor;
import com.local.warz.runtime.OpticType;
import com.local.warz.runtime.SuppressorType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Crafting-style gunsmith bench: suppressor / laser / light / adapter / optic / grip.
 */
public final class GunWorkbenchGui {
    public static final int SLOT_GUN = 10;
    public static final int SLOT_SUPPRESSOR = 12;
    public static final int SLOT_LASER = 14;
    public static final int SLOT_FLASHLIGHT = 16;
    public static final int SLOT_ADAPTER = 19;
    public static final int SLOT_OPTIC = 21;
    public static final int SLOT_GRIP = 23;
    public static final int SLOT_RESULT = 25;
    public static final int SLOT_CLOSE = 49;

    public static boolean isInputSlot(int slot) {
        return slot == SLOT_GUN || slot == SLOT_SUPPRESSOR || slot == SLOT_LASER
                || slot == SLOT_FLASHLIGHT || slot == SLOT_ADAPTER
                || slot == SLOT_OPTIC || slot == SLOT_GRIP;
    }

    public static final class Holder implements InventoryHolder {
        private final UUID playerId;
        private Inventory inventory;

        public Holder(UUID playerId) {
            this.playerId = playerId;
        }

        public UUID playerId() {
            return playerId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    private final WarzPlugin plugin;
    private final Map<UUID, Inventory> open = new HashMap<>();

    public GunWorkbenchGui(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        Holder holder = new Holder(player.getUniqueId());
        Inventory inv = ChestInventories.create(holder, 54,
                Component.text("Gun Workbench", NamedTextColor.GOLD));
        holder.setInventory(inv);
        decorate(inv);
        open.put(player.getUniqueId(), inv);
        player.openInventory(inv);
    }

    public void clear(Player player) {
        Inventory inv = open.remove(player.getUniqueId());
        if (inv != null) {
            returnInputs(player, inv);
        }
    }

    private void decorate(Inventory inv) {
        ItemStack pane = button(Material.GRAY_STAINED_GLASS_PANE, "&8 ", "&7Gunsmith");
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, pane);
        }
        inv.setItem(SLOT_RESULT, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&eResult",
                "&7Gun + parts → outfit",
                "&7Empty part slots + fitted gun → strip"));
        inv.setItem(1, button(Material.IRON_HORSE_ARMOR, "&7Gun",
                "&7Place gun here", "&7Fittings unpack into the slots"));
        inv.setItem(3, button(Material.IRON_BARS, "&7Suppressor",
                "&7Pistol / Rifle / Sniper / Shotgun"));
        inv.setItem(5, button(Material.REDSTONE_TORCH, "&7Laser Module",
                "&7Colors + IR — also tints EOTech reticle"));
        inv.setItem(7, button(Material.TORCH, "&7Light / PEQ",
                "&7Flashlight or AN/PEQ-15"));
        inv.setItem(18, button(Material.HOPPER, "&7Mag Adapter",
                "&7AK↔AR magazine well adapter"));
        inv.setItem(20, button(Material.SPYGLASS, "&7Optic",
                "&7Irons / RDS / EOTech / ACOG / scopes"));
        inv.setItem(22, button(Material.STICK, "&7Grip",
                "&7Vertical / angled / bipod / handstop"));
        inv.setItem(24, button(Material.ARROW, "&e→",
                "&7Take the outfitted gun"));
        inv.setItem(SLOT_CLOSE, button(Material.BARRIER, "&cClose", "&7Returns leftover parts"));
        inv.setItem(SLOT_GUN, null);
        inv.setItem(SLOT_SUPPRESSOR, null);
        inv.setItem(SLOT_LASER, null);
        inv.setItem(SLOT_FLASHLIGHT, null);
        inv.setItem(SLOT_ADAPTER, null);
        inv.setItem(SLOT_OPTIC, null);
        inv.setItem(SLOT_GRIP, null);
    }

    public void refreshResult(Inventory inv) {
        if (inv == null) {
            return;
        }
        ItemStack crafted = plugin.workbenches().craft(
                inv.getItem(SLOT_GUN),
                inv.getItem(SLOT_SUPPRESSOR),
                inv.getItem(SLOT_LASER),
                inv.getItem(SLOT_FLASHLIGHT),
                inv.getItem(SLOT_ADAPTER),
                inv.getItem(SLOT_OPTIC),
                inv.getItem(SLOT_GRIP),
                null);
        if (crafted != null) {
            boolean strip = plugin.workbenches().isStripMode(
                    inv.getItem(SLOT_GUN), inv.getItem(SLOT_SUPPRESSOR), inv.getItem(SLOT_LASER),
                    inv.getItem(SLOT_FLASHLIGHT), inv.getItem(SLOT_ADAPTER),
                    inv.getItem(SLOT_OPTIC), inv.getItem(SLOT_GRIP));
            ItemMeta meta = crafted.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(ItemFactory.colorize("&8────────")
                    .decoration(TextDecoration.ITALIC, false));
            if (strip) {
                lore.add(ItemFactory.colorize("&cStrip mode &7— click to remove all fittings")
                        .decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(ItemFactory.colorize("&aClick &7to apply parts / keep swaps")
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
            crafted.setItemMeta(meta);
            inv.setItem(SLOT_RESULT, crafted);
        } else {
            inv.setItem(SLOT_RESULT, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&eResult",
                    "&7Gun + parts → outfit",
                    "&7Empty part slots + fitted gun → strip",
                    "&7Swap: put a new optic/grip over the unpacked one"));
        }
    }

    public void sanitizeInputs(Player player, Inventory inv) {
        bounceIfInvalid(player, inv, SLOT_GUN, stack -> plugin.items().isGunItem(stack),
                "Only guns go in the gun slot");
        bounceIfInvalid(player, inv, SLOT_SUPPRESSOR, stack -> plugin.items().suppressorPartType(stack) != null,
                "Only suppressors go here");
        bounceIfInvalid(player, inv, SLOT_LASER, stack -> plugin.items().laserPartColor(stack) != null,
                "Only laser modules go here");
        bounceIfInvalid(player, inv, SLOT_FLASHLIGHT, stack -> plugin.items().isLightDevicePart(stack),
                "Only flashlight or AN/PEQ-15 modules go here");
        bounceIfInvalid(player, inv, SLOT_ADAPTER, stack -> plugin.items().isMagAdapterPart(stack),
                "Only AK↔AR mag adapters go here");
        bounceIfInvalid(player, inv, SLOT_OPTIC, stack -> plugin.items().opticPartType(stack) != null,
                "Only optic modules go here");
        bounceIfInvalid(player, inv, SLOT_GRIP, stack -> plugin.items().gripPartType(stack) != null,
                "Only grip modules go here");
    }

    public void unpackAttachments(Player player, Inventory inv) {
        if (inv == null) {
            return;
        }
        ItemStack gun = inv.getItem(SLOT_GUN);
        if (gun == null || gun.getType() == Material.AIR || !plugin.items().isGunItem(gun)) {
            return;
        }
        SuppressorType fittedSup = plugin.items().suppressorType(gun);
        LaserModColor fittedLaser = plugin.items().laserColor(gun);
        boolean fittedFlash = plugin.items().hasFlashlightMod(gun);
        boolean fittedPeq = plugin.items().hasPeq(gun);
        boolean fittedAdapter = plugin.items().hasMagAdapter(gun);
        OpticType fittedOptic = plugin.items().opticTypeStored(gun);
        GripType fittedGrip = plugin.items().gripType(gun);
        List<String> unpacked = new ArrayList<>();

        if (fittedSup != null && isEmpty(inv.getItem(SLOT_SUPPRESSOR))) {
            inv.setItem(SLOT_SUPPRESSOR, plugin.items().createSuppressorPart(fittedSup));
            plugin.items().setSuppressor(gun, (SuppressorType) null);
            unpacked.add("suppressor");
        }
        if (fittedLaser != null && fittedLaser.isInstalled() && isEmpty(inv.getItem(SLOT_LASER))) {
            inv.setItem(SLOT_LASER, plugin.items().createLaserModulePart(fittedLaser));
            plugin.items().setLaserMod(gun, LaserModColor.NONE);
            unpacked.add("laser");
        }
        if (fittedPeq && isEmpty(inv.getItem(SLOT_FLASHLIGHT))) {
            inv.setItem(SLOT_FLASHLIGHT, plugin.items().createPeq15Part());
            plugin.items().setPeq(gun, false);
            unpacked.add("PEQ");
        } else if (fittedFlash && isEmpty(inv.getItem(SLOT_FLASHLIGHT))) {
            inv.setItem(SLOT_FLASHLIGHT, plugin.items().createFlashlightModulePart());
            plugin.items().setFlashlightMod(gun, false);
            unpacked.add("light");
        }
        if (fittedAdapter && isEmpty(inv.getItem(SLOT_ADAPTER))) {
            inv.setItem(SLOT_ADAPTER, plugin.items().createMagAdapterAkAr());
            plugin.items().setMagAdapter(gun, false);
            unpacked.add("adapter");
        }
        if (fittedOptic != null && isEmpty(inv.getItem(SLOT_OPTIC))) {
            inv.setItem(SLOT_OPTIC, plugin.items().createOpticPart(fittedOptic));
            plugin.items().clearOptic(gun);
            unpacked.add(plainName(fittedOptic.displayName()));
        }
        if (fittedGrip.isInstalled() && isEmpty(inv.getItem(SLOT_GRIP))) {
            inv.setItem(SLOT_GRIP, plugin.items().createGripPart(fittedGrip));
            plugin.items().setGrip(gun, GripType.NONE);
            unpacked.add(plainName(fittedGrip.displayName()));
        }
        if (!unpacked.isEmpty()) {
            inv.setItem(SLOT_GUN, gun);
            if (player != null) {
                player.sendMessage(Component.text(
                        "Unpacked " + String.join(", ", unpacked)
                                + " — swap parts or empty slots + take Result to strip",
                        NamedTextColor.GRAY));
            }
        }
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0;
    }

    private void bounceIfInvalid(Player player, Inventory inv, int slot,
                                 java.util.function.Predicate<ItemStack> ok, String msg) {
        ItemStack stack = inv.getItem(slot);
        if (stack == null || stack.getType() == Material.AIR) {
            return;
        }
        if (ok.test(stack)) {
            return;
        }
        int dest = targetSlot(stack);
        if (dest >= 0 && dest != slot) {
            ItemStack occupying = inv.getItem(dest);
            if (!isEmpty(occupying)) {
                give(player, occupying);
            }
            inv.setItem(dest, stack);
            inv.setItem(slot, null);
            return;
        }
        inv.setItem(slot, null);
        give(player, stack);
        player.sendMessage(Component.text(msg, NamedTextColor.RED));
    }

    public int targetSlot(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return -1;
        }
        if (plugin.items().isGunItem(stack)) {
            return SLOT_GUN;
        }
        if (plugin.items().suppressorPartType(stack) != null) {
            return SLOT_SUPPRESSOR;
        }
        if (plugin.items().laserPartColor(stack) != null) {
            return SLOT_LASER;
        }
        if (plugin.items().isLightDevicePart(stack)) {
            return SLOT_FLASHLIGHT;
        }
        if (plugin.items().isMagAdapterPart(stack)) {
            return SLOT_ADAPTER;
        }
        if (plugin.items().opticPartType(stack) != null) {
            return SLOT_OPTIC;
        }
        if (plugin.items().gripPartType(stack) != null) {
            return SLOT_GRIP;
        }
        return -1;
    }

    public boolean tryShiftDeposit(Player player, Inventory inv, ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return false;
        }
        int target = targetSlot(stack);
        if (target < 0) {
            return false;
        }
        ItemStack existing = inv.getItem(target);
        if (existing != null && existing.getType() != Material.AIR) {
            give(player, existing);
        }
        ItemStack put = restampPart(stack);
        put.setAmount(1);
        inv.setItem(target, put);
        stack.setAmount(stack.getAmount() - 1);
        if (target == SLOT_GUN) {
            unpackAttachments(player, inv);
        }
        return true;
    }

    /**
     * Rebuild the part from type/CMD so CardForge clones that lost PDC still craft.
     */
    private ItemStack restampPart(ItemStack stack) {
        var items = plugin.items();
        if (items.isGunItem(stack)) {
            ItemStack copy = stack.clone();
            copy.setAmount(1);
            return copy;
        }
        OpticType optic = items.opticPartType(stack);
        if (optic != null) {
            return items.createOpticPart(optic);
        }
        SuppressorType sup = items.suppressorPartType(stack);
        if (sup != null) {
            return items.createSuppressorPart(sup);
        }
        LaserModColor laser = items.laserPartColor(stack);
        if (laser != null) {
            return items.createLaserModulePart(laser);
        }
        if (items.isPeq15Part(stack)) {
            return items.createPeq15Part();
        }
        if (items.isFlashlightModulePart(stack)) {
            return items.createFlashlightModulePart();
        }
        if (items.isMagAdapterPart(stack)) {
            return items.createMagAdapterAkAr();
        }
        GripType grip = items.gripPartType(stack);
        if (grip != null && grip.isInstalled()) {
            return items.createGripPart(grip);
        }
        ItemStack copy = stack.clone();
        copy.setAmount(1);
        return copy;
    }

    /** Sanitize, unpack, craft result, and push the chest to the client immediately. */
    public void applyLayout(Player player, Inventory inv) {
        sanitizeInputs(player, inv);
        unpackAttachments(player, inv);
        refreshResult(inv);
        if (player != null) {
            player.updateInventory();
        }
    }

    public void takeResult(Player player, Inventory inv) {
        ItemStack gun = inv.getItem(SLOT_GUN);
        ItemStack sup = inv.getItem(SLOT_SUPPRESSOR);
        ItemStack laser = inv.getItem(SLOT_LASER);
        ItemStack flash = inv.getItem(SLOT_FLASHLIGHT);
        ItemStack adapter = inv.getItem(SLOT_ADAPTER);
        ItemStack optic = inv.getItem(SLOT_OPTIC);
        ItemStack grip = inv.getItem(SLOT_GRIP);
        boolean strip = plugin.workbenches().isStripMode(gun, sup, laser, flash, adapter, optic, grip);
        ItemStack crafted = plugin.workbenches().craft(gun, sup, laser, flash, adapter, optic, grip, player);
        if (crafted == null) {
            return;
        }

        SuppressorType oldSup = plugin.items().suppressorType(gun);
        LaserModColor oldLaser = plugin.items().laserColor(gun);
        boolean oldFlash = plugin.items().hasFlashlightMod(gun);
        boolean oldPeq = plugin.items().hasPeq(gun);
        boolean oldAdapter = plugin.items().hasMagAdapter(gun);
        OpticType oldOptic = plugin.items().opticTypeStored(gun);
        GripType oldGrip = plugin.items().gripType(gun);
        SuppressorType newSupPart = plugin.items().suppressorPartType(sup);
        LaserModColor newLaserPart = plugin.items().laserPartColor(laser);
        boolean newFlashPart = plugin.items().isFlashlightModulePart(flash);
        boolean newPeqPart = plugin.items().isPeq15Part(flash);
        boolean newAdapterPart = plugin.items().isMagAdapterPart(adapter);
        OpticType newOpticPart = plugin.items().opticPartType(optic);
        GripType newGripPart = plugin.items().gripPartType(grip);

        consumeOne(inv, SLOT_GUN);
        if (newSupPart != null) {
            consumeOne(inv, SLOT_SUPPRESSOR);
        }
        if (newLaserPart != null) {
            consumeOne(inv, SLOT_LASER);
        }
        if (newFlashPart || newPeqPart) {
            consumeOne(inv, SLOT_FLASHLIGHT);
        }
        if (newAdapterPart) {
            consumeOne(inv, SLOT_ADAPTER);
        }
        if (newOpticPart != null) {
            consumeOne(inv, SLOT_OPTIC);
        }
        if (newGripPart != null) {
            consumeOne(inv, SLOT_GRIP);
        }

        if (strip) {
            List<String> stripped = new ArrayList<>();
            if (oldSup != null) {
                give(player, plugin.items().createSuppressorPart(oldSup));
                stripped.add(plainName(oldSup.displayName()));
            }
            if (oldLaser != null && oldLaser.isInstalled()) {
                give(player, plugin.items().createLaserModulePart(oldLaser));
                stripped.add("laser");
            }
            if (oldPeq) {
                give(player, plugin.items().createPeq15Part());
                stripped.add("AN/PEQ-15");
            } else if (oldFlash) {
                give(player, plugin.items().createFlashlightModulePart());
                stripped.add("flashlight");
            }
            if (oldAdapter) {
                give(player, plugin.items().createMagAdapterAkAr());
                stripped.add("AK↔AR adapter");
            }
            if (oldOptic != null) {
                give(player, plugin.items().createOpticPart(oldOptic));
                stripped.add(plainName(oldOptic.displayName()));
            }
            if (oldGrip.isInstalled()) {
                give(player, plugin.items().createGripPart(oldGrip));
                stripped.add(plainName(oldGrip.displayName()));
            }
            give(player, crafted);
            player.sendMessage(Component.text(
                    stripped.isEmpty() ? "Attachments removed"
                            : "Stripped: " + String.join(", ", stripped),
                    NamedTextColor.GOLD));
        } else {
            List<String> fitted = new ArrayList<>();
            List<String> swapped = new ArrayList<>();
            if (newSupPart != null) {
                fitted.add(plainName(newSupPart.displayName()));
                if (oldSup != null && oldSup != newSupPart) {
                    give(player, plugin.items().createSuppressorPart(oldSup));
                    swapped.add(plainName(oldSup.displayName()));
                }
            }
            if (newLaserPart != null) {
                fitted.add("laser");
                if (oldLaser != null && oldLaser.isInstalled() && oldLaser != newLaserPart) {
                    give(player, plugin.items().createLaserModulePart(oldLaser));
                    swapped.add("old laser");
                }
            }
            if (newFlashPart || newPeqPart) {
                fitted.add(newPeqPart ? "AN/PEQ-15" : "flashlight");
                if (oldFlash || oldPeq) {
                    if (oldPeq) {
                        give(player, plugin.items().createPeq15Part());
                    } else if (oldFlash) {
                        give(player, plugin.items().createFlashlightModulePart());
                    }
                    swapped.add("old light/PEQ");
                }
            }
            if (newAdapterPart) {
                fitted.add("AK↔AR adapter");
                if (oldAdapter) {
                    give(player, plugin.items().createMagAdapterAkAr());
                    swapped.add("adapter (duplicate)");
                }
            }
            if (newOpticPart != null) {
                fitted.add(plainName(newOpticPart.displayName()));
                if (oldOptic != null && oldOptic != newOpticPart) {
                    give(player, plugin.items().createOpticPart(oldOptic));
                    swapped.add(plainName(oldOptic.displayName()));
                }
            }
            if (newGripPart != null) {
                fitted.add(plainName(newGripPart.displayName()));
                if (oldGrip.isInstalled() && oldGrip != newGripPart) {
                    give(player, plugin.items().createGripPart(oldGrip));
                    swapped.add(plainName(oldGrip.displayName()));
                }
            }
            give(player, crafted);
            StringBuilder msg = new StringBuilder("Fitted: ");
            msg.append(fitted.isEmpty() ? "attachments" : String.join(", ", fitted));
            if (!swapped.isEmpty()) {
                msg.append(" · returned ").append(String.join(", ", swapped));
            }
            player.sendMessage(Component.text(msg.toString(), NamedTextColor.GOLD));
        }
        refreshResult(inv);
    }

    private static String plainName(String colored) {
        if (colored == null) {
            return "?";
        }
        return colored.replaceAll("&[0-9a-fk-or]", "").trim();
    }

    private static void consumeOne(Inventory inv, int slot) {
        ItemStack stack = inv.getItem(slot);
        if (stack == null) {
            return;
        }
        if (stack.getAmount() <= 1) {
            inv.setItem(slot, null);
        } else {
            stack.setAmount(stack.getAmount() - 1);
            inv.setItem(slot, stack);
        }
    }

    private void returnInputs(Player player, Inventory inv) {
        for (int slot : new int[]{SLOT_GUN, SLOT_SUPPRESSOR, SLOT_LASER, SLOT_FLASHLIGHT,
                SLOT_ADAPTER, SLOT_OPTIC, SLOT_GRIP}) {
            ItemStack stack = inv.getItem(slot);
            if (stack != null && stack.getType() != Material.AIR) {
                give(player, stack);
                inv.setItem(slot, null);
            }
        }
        inv.setItem(SLOT_RESULT, null);
    }

    private void give(Player player, ItemStack stack) {
        plugin.items().giveOrDrop(player, stack);
    }

    private static ItemStack button(Material mat, String name, String... loreLines) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(ItemFactory.colorize(name).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(ItemFactory.colorize(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }
}
