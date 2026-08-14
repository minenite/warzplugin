package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Handles companion {@code peq_req} (Z-key) to cycle AN/PEQ / optic modes. */
public final class PeqService implements PluginMessageListener {
    public static final String CHANNEL_REQ = "pvpgunminus:peq_req";
    private static final long COOLDOWN_MS = 180L;

    private final WarzPlugin plugin;
    private final Map<UUID, Long> cooldownMs = new ConcurrentHashMap<>();

    public PeqService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerChannel() {
        var messenger = plugin.getServer().getMessenger();
        messenger.registerIncomingPluginChannel(plugin, CHANNEL_REQ, this);
    }

    public void unregisterChannel() {
        var messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin, CHANNEL_REQ, this);
        cooldownMs.clear();
    }

    /** Cycle optic mode on the held gun. Returns true if handled. */
    public boolean cycleHeld(Player player) {
        if (player == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = cooldownMs.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) {
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!plugin.items().canToggleOptic(hand)) {
            return false;
        }
        cooldownMs.put(player.getUniqueId(), now);
        PeqMode next = plugin.items().cycleOpticMode(hand);
        player.getInventory().setItemInMainHand(hand);
        String label = plugin.items().opticDeviceLabel(hand, next);
        NamedTextColor color = actionBarColor(hand, next);
        String prefix = plugin.items().hasPeq(hand) ? "AN/PEQ-15: " : "Device: ";
        player.sendActionBar(Component.text(prefix + label, color));
        return true;
    }

    private NamedTextColor actionBarColor(ItemStack hand, PeqMode mode) {
        if (mode == null) {
            return NamedTextColor.GRAY;
        }
        return switch (mode) {
            case OFF -> NamedTextColor.GRAY;
            case IR -> NamedTextColor.GREEN;
            case GREEN -> {
                LaserModColor laser = plugin.items().laserColor(hand);
                yield switch (laser) {
                    case RED -> NamedTextColor.RED;
                    case BLUE -> NamedTextColor.BLUE;
                    case YELLOW -> NamedTextColor.YELLOW;
                    case ORANGE -> NamedTextColor.GOLD;
                    case PURPLE, PINK -> NamedTextColor.LIGHT_PURPLE;
                    case CYAN -> NamedTextColor.AQUA;
                    case WHITE -> NamedTextColor.WHITE;
                    case GREEN -> NamedTextColor.GREEN;
                    default -> NamedTextColor.GREEN;
                };
            }
            case FLASH -> NamedTextColor.YELLOW;
            case STROBE -> NamedTextColor.GOLD;
        };
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL_REQ.equals(channel) || player == null) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            int protocol = in.readUnsignedByte();
            if (protocol != 1) {
                return;
            }
            // action byte reserved (0 = cycle)
            in.readUnsignedByte();
            cycleHeld(player);
        } catch (IOException ignored) {
        }
    }
}
