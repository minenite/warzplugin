package net.minenite.warzplugin;

import com.local.warz.gui.ChestInventories;
import com.local.warz.runtime.ItemFactory;
import com.local.warz.runtime.ProfileStatsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Single-chest profile stats GUI. */
public final class ProfileGuiService {
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();
    /** Top-right slot for clan shortcut. */
    public static final int SLOT_CLAN = 8;
    /** Own-profile invite row start (bottom of single chest). */
    public static final int SLOT_INVITE_START = 18;

    public static final class Holder implements InventoryHolder {
        private final UUID viewerId;
        private final UUID subjectId;
        private final String clanTag;
        private final Map<Integer, String> inviteSlots;
        private Inventory inventory;

        public Holder(UUID viewerId, UUID subjectId, String clanTag, Map<Integer, String> inviteSlots) {
            this.viewerId = viewerId;
            this.subjectId = subjectId;
            this.clanTag = clanTag;
            this.inviteSlots = inviteSlots;
        }

        public UUID viewerId() {
            return viewerId;
        }

        public UUID subjectId() {
            return subjectId;
        }

        /** Clan tag when subject is in a clan; null otherwise. */
        public String clanTag() {
            return clanTag;
        }

        public String inviteAt(int slot) {
            return inviteSlots.get(slot);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    private final WarzPlugin plugin;

    public ProfileGuiService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player viewer, OfflinePlayer subject) {
        if (viewer == null || subject == null || subject.getUniqueId() == null) {
            return;
        }
        UUID id = subject.getUniqueId();
        String name = resolveName(subject);
        if (plugin.profileStats() != null) {
            plugin.profileStats().touchName(id, name);
        }
        ProfileStatsService.Stats stats = plugin.profileStats() != null
                ? plugin.profileStats().get(id)
                : new ProfileStatsService.Stats();

        ClanService.Clan clan = plugin.clans() != null ? plugin.clans().clanOf(id) : null;
        String clanTag = clan != null ? clan.tag : null;

        String titleRaw = "&f" + name + "'s &b&lProfile:";
        Component title = LEGACY.deserialize(titleRaw)
                .decoration(TextDecoration.ITALIC, false);

        Map<Integer, String> inviteSlots = new HashMap<>();
        Holder holder = new Holder(viewer.getUniqueId(), id, clanTag, inviteSlots);
        Inventory inv = ChestInventories.create(holder, 27, title);
        holder.setInventory(inv);

        long playMs = plugin.profileStats() != null
                ? plugin.profileStats().getPlaytimeMs(id)
                : 0L;
        inv.setItem(0, playtimeItem(playMs));
        inv.setItem(SLOT_CLAN, clanItem(id, clan));
        inv.setItem(11, playerKillItem(stats.playerKills));
        inv.setItem(12, deathItem(stats.pvpDeaths));
        inv.setItem(13, kdrItem(stats.playerKills, stats.pvpDeaths));
        inv.setItem(14, zombieItem(stats.zombieKills));
        inv.setItem(15, chestItem(stats.chestsLooted));

        boolean own = viewer.getUniqueId().equals(id);
        if (own && clan == null && plugin.clans() != null) {
            List<String> pending = plugin.clans().pendingInvites(id);
            int slot = SLOT_INVITE_START;
            for (String tag : pending) {
                if (slot >= 27) {
                    break;
                }
                ClanService.Clan invited = plugin.clans().get(tag);
                if (invited == null) {
                    continue;
                }
                inv.setItem(slot, inviteItem(invited));
                inviteSlots.put(slot, tag);
                slot++;
            }
        }

        viewer.openInventory(inv);
    }

    public void handleClick(Player viewer, int slot, Holder holder, boolean rightClick) {
        if (viewer == null || holder == null) {
            return;
        }
        if (plugin.clans() == null) {
            return;
        }

        String inviteTag = holder.inviteAt(slot);
        if (inviteTag != null) {
            if (!viewer.getUniqueId().equals(holder.subjectId())) {
                return;
            }
            if (rightClick) {
                viewer.sendMessage(ItemFactory.colorize(plugin.clans().declineInvite(viewer, inviteTag)));
            } else {
                viewer.sendMessage(ItemFactory.colorize(plugin.clans().join(viewer, inviteTag)));
            }
            open(viewer, viewer);
            return;
        }

        if (slot != SLOT_CLAN || holder.clanTag() == null) {
            return;
        }
        if (plugin.clanGui() == null) {
            return;
        }
        ClanService.Clan clan = plugin.clans().get(holder.clanTag());
        if (clan == null) {
            viewer.sendMessage(ItemFactory.colorize("&cThat clan no longer exists."));
            open(viewer, Bukkit.getOfflinePlayer(holder.subjectId()));
            return;
        }
        viewer.closeInventory();
        plugin.clanGui().open(viewer, clan);
    }

    private ItemStack inviteItem(ClanService.Clan clan) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(ItemFactory.colorize("&e&lInvite: &f[" + clan.tag + "]")
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(ItemFactory.colorize("&7Members: &f" + clan.members.size())
                .decoration(TextDecoration.ITALIC, false));
        double kdr = plugin.clans().combinedKdr(clan);
        lore.add(ItemFactory.colorize("&7Clan KDR: &f" + String.format(Locale.US, "%.2f", kdr))
                .decoration(TextDecoration.ITALIC, false));
        lore.add(ItemFactory.colorize("&aLeft-click &7→ join")
                .decoration(TextDecoration.ITALIC, false));
        lore.add(ItemFactory.colorize("&cRight-click &7→ decline")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private String resolveName(OfflinePlayer subject) {
        if (subject.getName() != null) {
            return subject.getName();
        }
        if (plugin.profileStats() != null) {
            String stored = plugin.profileStats().get(subject.getUniqueId()).lastName;
            if (stored != null && !stored.isBlank()) {
                return stored;
            }
        }
        return "Unknown";
    }

    private ItemStack clanItem(UUID subjectId, ClanService.Clan clan) {
        if (clan == null) {
            ItemStack stack = new ItemStack(Material.GRAY_DYE);
            ItemMeta meta = stack.getItemMeta();
            meta.displayName(ItemFactory.colorize("&8&lNo Clan")
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    ItemFactory.colorize("&7Not in a clan")
                            .decoration(TextDecoration.ITALIC, false)
            ));
            stack.setItemMeta(meta);
            return stack;
        }
        ClanRole role = clan.roleOf(subjectId);
        String roleLabel = switch (role == null ? ClanRole.MEMBER : role) {
            case OWNER -> "&6&nOwner";
            case MOD -> "&d&oMod";
            case MEMBER -> "&7Member";
        };
        boolean top = clan.tag.equals(plugin.clans().topKdrClanTag());
        ItemStack stack = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = stack.getItemMeta();
        if (top) {
            meta.displayName(ItemFactory.colorize("&6&k!&r &5&l[" + clan.tag + "] &6&k!&r")
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            meta.displayName(ItemFactory.colorize("&5&lClan &f[" + clan.tag + "]")
                    .decoration(TextDecoration.ITALIC, false));
        }
        List<Component> lore = new ArrayList<>();
        lore.add(ItemFactory.colorize("&7Role: " + roleLabel)
                .decoration(TextDecoration.ITALIC, false));
        double kdr = plugin.clans().combinedKdr(clan);
        long kills = plugin.clans().combinedKills(clan);
        long deaths = 0L;
        if (plugin.profileStats() != null) {
            for (UUID mid : clan.members.keySet()) {
                deaths += plugin.profileStats().get(mid).pvpDeaths;
            }
        }
        String color = ProfileStatsService.kdrColorCode(kdr, kills, deaths);
        lore.add(ItemFactory.colorize("&7Clan KDR: " + color
                        + String.format(Locale.US, "%.2f", kdr))
                .decoration(TextDecoration.ITALIC, false));
        lore.add(ItemFactory.colorize("&7Members: &f" + clan.members.size())
                .decoration(TextDecoration.ITALIC, false));
        lore.add(ItemFactory.colorize("&eClick &7to view clan")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack playtimeItem(long playMs) {
        ItemStack stack = new ItemStack(Material.CLOCK);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(ItemFactory.colorize("&e&lPlaytime")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                ItemFactory.colorize("&7Active: &f" + ProfileStatsService.formatPlaytime(playMs))
                        .decoration(TextDecoration.ITALIC, false),
                ItemFactory.colorize("&8Time spent on this server")
                        .decoration(TextDecoration.ITALIC, false)
        ));
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack zombieItem(long count) {
        ItemStack stack = new ItemStack(Material.ZOMBIE_HEAD);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(ItemFactory.colorize("&2&lZombie Kills")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                ItemFactory.colorize("&7Total: &f" + format(count))
                        .decoration(TextDecoration.ITALIC, false)
        ));
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack playerKillItem(long count) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(ItemFactory.colorize("&c&lPlayer Kills")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                ItemFactory.colorize("&7Total: &f" + format(count))
                        .decoration(TextDecoration.ITALIC, false)
        ));
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack deathItem(long count) {
        ItemStack stack = new ItemStack(Material.SKELETON_SKULL);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(ItemFactory.colorize("&8&lPlayer Deaths")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                ItemFactory.colorize("&7Killed by players: &f" + format(count))
                        .decoration(TextDecoration.ITALIC, false)
        ));
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack kdrItem(long kills, long deaths) {
        double ratio = ProfileStatsService.kdrValue(kills, deaths);
        String color = ProfileStatsService.kdrColorCode(ratio, kills, deaths);
        ItemStack stack = new ItemStack(Material.EMERALD);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(ItemFactory.colorize("&a&lKDR")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                ItemFactory.colorize("&7Kill / Death: " + color + String.format("%.2f", ratio))
                        .decoration(TextDecoration.ITALIC, false),
                ItemFactory.colorize("&8" + format(kills) + " kills / " + format(deaths) + " deaths")
                        .decoration(TextDecoration.ITALIC, false)
        ));
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack chestItem(long count) {
        ItemStack stack = new ItemStack(Material.CHEST);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(ItemFactory.colorize("&6&lChests Looted")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                ItemFactory.colorize("&7Opened: &f" + format(count))
                        .decoration(TextDecoration.ITALIC, false),
                ItemFactory.colorize("&8Loot chests & crash sites")
                        .decoration(TextDecoration.ITALIC, false)
        ));
        stack.setItemMeta(meta);
        return stack;
    }

    private static String format(long n) {
        return String.format("%,d", n);
    }
}
