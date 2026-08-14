package net.minenite.warzplugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import com.local.warz.runtime.ItemFactory;
import com.local.warz.runtime.ProfileStatsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;

/** Clan roster GUI — heads with stats hover; click opens /profile. */
public final class ClanGuiService {

    public static final class Holder implements InventoryHolder {
        private final UUID viewerId;
        private final String clanTag;
        private final Map<Integer, UUID> slotMembers;
        private Inventory inventory;

        public Holder(UUID viewerId, String clanTag, Map<Integer, UUID> slotMembers) {
            this.viewerId = viewerId;
            this.clanTag = clanTag;
            this.slotMembers = slotMembers;
        }

        public UUID viewerId() {
            return viewerId;
        }

        public String clanTag() {
            return clanTag;
        }

        public UUID memberAt(int slot) {
            return slotMembers.get(slot);
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

    public ClanGuiService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player viewer, ClanService.Clan clan) {
        if (viewer == null || clan == null || plugin.clans() == null) {
            return;
        }
        List<Map.Entry<UUID, ClanRole>> members = plugin.clans().sortedMembers(clan);
        int size = Math.min(54, Math.max(27, ((Math.max(members.size(), 1) - 1) / 9 + 1) * 9));
        Map<Integer, UUID> slots = new HashMap<>();
        Holder holder = new Holder(viewer.getUniqueId(), clan.tag, slots);
        String title = ChatColor.DARK_PURPLE + "[" + clan.tag + "] "
                + ChatColor.WHITE + "Clan";
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inv);

        int slot = 0;
        for (Map.Entry<UUID, ClanRole> e : members) {
            if (slot >= size) {
                break;
            }
            inv.setItem(slot, memberHead(e.getKey(), e.getValue()));
            slots.put(slot, e.getKey());
            slot++;
        }
        viewer.openInventory(inv);
    }

    public void handleClick(Player viewer, int slot, Holder holder) {
        if (viewer == null || holder == null) {
            return;
        }
        UUID id = holder.memberAt(slot);
        if (id == null || plugin.profileGui() == null) {
            return;
        }
        OfflinePlayer subject = Bukkit.getOfflinePlayer(id);
        viewer.closeInventory();
        plugin.profileGui().open(viewer, subject);
    }

    private ItemStack memberHead(UUID id, ClanRole role) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        String name = op.getName() != null ? op.getName() : "Unknown";
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(op);
        }
        String roleLabel = switch (role) {
            case OWNER -> "&6&nOwner";
            case MOD -> "&d&oMod";
            case MEMBER -> "&7Member";
        };
        meta.displayName(ItemFactory.colorize("&f" + name)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(ItemFactory.colorize(roleLabel).decoration(TextDecoration.ITALIC, false));
        boolean online = Bukkit.getPlayer(id) != null;
        lore.add(ItemFactory.colorize(online ? "&aOnline" : "&8Offline")
                .decoration(TextDecoration.ITALIC, false));
        if (plugin.profileStats() != null) {
            ProfileStatsService.Stats stats = plugin.profileStats().get(id);
            double kdr = ProfileStatsService.kdrValue(stats.playerKills, stats.pvpDeaths);
            String color = ProfileStatsService.kdrColorCode(kdr, stats.playerKills, stats.pvpDeaths);
            lore.add(ItemFactory.colorize("&7Kills: &f" + String.format(Locale.US, "%,d", stats.playerKills))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(ItemFactory.colorize("&7Deaths: &f" + String.format(Locale.US, "%,d", stats.pvpDeaths))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(ItemFactory.colorize("&7KDR: " + color + String.format(Locale.US, "%.2f", kdr))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(ItemFactory.colorize("&8Click to open profile")
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }
}
