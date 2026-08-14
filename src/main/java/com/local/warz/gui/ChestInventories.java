package com.local.warz.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * CardForge's Adventure {@code createInventory(..., Component)} overloads used to
 * be stubs that returned null. Prefer those APIs, but fall back to legacy titles.
 */
public final class ChestInventories {
    private ChestInventories() {
    }

    public static Inventory create(InventoryHolder holder, int size, Component title) {
        Inventory inv = Bukkit.createInventory(holder, size, title);
        if (inv != null) {
            return inv;
        }
        return Bukkit.createInventory(holder, size, legacy(title));
    }

    public static Inventory create(InventoryHolder holder, InventoryType type, Component title) {
        Inventory inv = Bukkit.createInventory(holder, type, title);
        if (inv != null) {
            return inv;
        }
        return Bukkit.createInventory(holder, type, legacy(title));
    }

    private static String legacy(Component title) {
        return LegacyComponentSerializer.legacySection().serialize(title);
    }
}
