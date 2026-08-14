package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces thin companion creative-tab stacks with full {@link ItemFactory} items.
 * Uses inventory events plus a C2S {@code pvpgunminus:creative_give} channel (authoritative).
 */
public final class CreativeMaterializeListener implements Listener, PluginMessageListener {
    public static final String CHANNEL_CREATIVE_GIVE = "pvpgunminus:creative_give";

    private final WarzPlugin plugin;
    /** Rate-limit duplicate give packets per player+slot. */
    private final Map<String, Long> recent = new ConcurrentHashMap<>();

    public CreativeMaterializeListener(WarzPlugin plugin) {
        this.plugin = plugin;
        var messenger = plugin.getServer().getMessenger();
        messenger.registerIncomingPluginChannel(plugin, CHANNEL_CREATIVE_GIVE, this);
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweepCreativePlayers, 10L, 5L);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL_CREATIVE_GIVE.equals(channel) || player == null || message == null) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            in.readUnsignedByte(); // protocol
            int slot = readVarInt(in);
            int amount = Math.max(1, Math.min(64, readVarInt(in)));
            int len = in.readUnsignedShort();
            if (len <= 0 || len > 256) {
                return;
            }
            byte[] raw = in.readNBytes(len);
            String give = new String(raw, StandardCharsets.UTF_8).trim();
            if (give.isEmpty() || give.length() > 128) {
                return;
            }
            String dedupe = player.getUniqueId() + ":" + slot + ":" + give;
            long now = System.currentTimeMillis();
            Long prev = recent.put(dedupe, now);
            if (prev != null && now - prev < 150L) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> applyGive(player, slot, amount, give));
        } catch (IOException ignored) {
        }
    }

    private void applyGive(Player player, int slot, int amount, String give) {
        if (player == null || !player.isOnline() || plugin.items() == null) {
            return;
        }
        ItemStack full = plugin.items().materializeGiveSpec(give, amount, null);
        if (full == null || full.getType().isAir()) {
            plugin.getLogger().warning("[creative_give] unknown give '" + give + "' for " + player.getName());
            return;
        }
        if (slot < 0) {
            player.setItemOnCursor(full);
        } else {
            PlayerInventory inv = player.getInventory();
            if (slot >= 0 && slot < inv.getSize()) {
                inv.setItem(slot, full);
            } else {
                player.getInventory().addItem(full);
            }
        }
        player.updateInventory();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack cursor = event.getCursor();
        ItemStack full = plugin.items().materializeWarZ(cursor);
        if (full != null) {
            event.setCursor(full);
        }
        // Also try warz_give even when identity keys failed to deserialize.
        String give = plugin.items().warzGiveId(cursor);
        if (full == null && give != null) {
            ItemStack fromGive = plugin.items().materializeGiveSpec(give, Math.max(1, cursor.getAmount()), cursor);
            if (fromGive != null) {
                event.setCursor(fromGive);
            }
        }
        for (long delay = 0; delay <= 5; delay++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> materializeInventory(player), delay);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        materializeInventory(player);
        Bukkit.getScheduler().runTask(plugin, () -> materializeInventory(player));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            materializeInventory(player);
        }
    }

    private void sweepCreativePlayers() {
        if (plugin.items() == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 5_000L;
        recent.entrySet().removeIf(e -> e.getValue() < cutoff);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.CREATIVE || hasAnyStub(player)) {
                materializeInventory(player);
            }
        }
    }

    private boolean hasAnyStub(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (plugin.items().looksLikeCreativeStub(stack)) {
                return true;
            }
        }
        return plugin.items().looksLikeCreativeStub(player.getItemOnCursor());
    }

    private void materializeInventory(Player player) {
        if (player == null || !player.isOnline() || plugin.items() == null) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack full = replace(contents[i]);
            if (full != null) {
                contents[i] = full;
                changed = true;
            }
        }
        if (changed) {
            inv.setContents(contents);
        }

        ItemStack[] armor = inv.getArmorContents();
        boolean armorChanged = false;
        for (int i = 0; i < armor.length; i++) {
            ItemStack full = replace(armor[i]);
            if (full != null) {
                armor[i] = full;
                armorChanged = true;
            }
        }
        if (armorChanged) {
            inv.setArmorContents(armor);
        }

        ItemStack off = replace(inv.getItemInOffHand());
        if (off != null) {
            inv.setItemInOffHand(off);
        }

        ItemStack cursor = replace(player.getItemOnCursor());
        if (cursor != null) {
            player.setItemOnCursor(cursor);
        }
    }

    private ItemStack replace(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        if (plugin.items().looksLikeCreativeStub(stack)) {
            ItemStack full = plugin.items().materializeWarZ(stack);
            if (full != null) {
                return full;
            }
            String give = plugin.items().warzGiveId(stack);
            if (give != null) {
                return plugin.items().materializeGiveSpec(give, Math.max(1, stack.getAmount()), stack);
            }
        }
        return null;
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        byte current;
        while (true) {
            current = in.readByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                break;
            }
            position += 7;
            if (position >= 32) {
                throw new IOException("VarInt too big");
            }
        }
        return value;
    }
}
