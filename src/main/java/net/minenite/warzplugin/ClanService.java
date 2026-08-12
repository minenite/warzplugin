package net.minenite.warzplugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Persistent clans: create / invite / join / leave / promote / demote / admin. */
public final class ClanService {

    public static final int MAX_TAG_LEN = 5;

    public static final class Clan {
        public final String tag;
        public UUID ownerId;
        public final Map<UUID, ClanRole> members = new ConcurrentHashMap<>();

        public Clan(String tag, UUID ownerId) {
            this.tag = tag;
            this.ownerId = ownerId;
            this.members.put(ownerId, ClanRole.OWNER);
        }

        public ClanRole roleOf(UUID id) {
            return id == null ? null : members.get(id);
        }

        public boolean isMember(UUID id) {
            return id != null && members.containsKey(id);
        }
    }

    private final WarzPlugin plugin;
    private final File file;
    private final Map<String, Clan> clans = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerClan = new ConcurrentHashMap<>();
    /** Invitee → pending clan tags (uppercase). */
    private final Map<UUID, Set<String>> invites = new ConcurrentHashMap<>();

    public ClanService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "clans.yml");
    }

    public void load() {
        clans.clear();
        playerClan.clear();
        invites.clear();
        if (!file.exists()) {
            return;
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("clans");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection sec = root.getConfigurationSection(key);
                if (sec == null) {
                    continue;
                }
                String tag = normalizeTag(key);
                if (tag == null) {
                    continue;
                }
                UUID owner;
                try {
                    owner = UUID.fromString(sec.getString("owner", ""));
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                Clan clan = new Clan(tag, owner);
                clan.members.clear();
                ConfigurationSection mem = sec.getConfigurationSection("members");
                if (mem != null) {
                    for (String mid : mem.getKeys(false)) {
                        try {
                            UUID id = UUID.fromString(mid);
                            ClanRole role = ClanRole.valueOf(
                                    mem.getString(mid, "MEMBER").toUpperCase(Locale.ROOT));
                            clan.members.put(id, role);
                            if (role == ClanRole.OWNER) {
                                clan.ownerId = id;
                            }
                        } catch (Exception ignored) {
                            // skip
                        }
                    }
                }
                if (!clan.members.containsKey(clan.ownerId)) {
                    clan.members.put(clan.ownerId, ClanRole.OWNER);
                }
                clans.put(tag, clan);
                for (UUID id : clan.members.keySet()) {
                    playerClan.put(id, tag);
                }
            }
        }
        ConfigurationSection inv = cfg.getConfigurationSection("invites");
        if (inv != null) {
            for (String key : inv.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    Set<String> tags = ConcurrentHashMap.newKeySet();
                    if (inv.isString(key)) {
                        String tag = normalizeTag(inv.getString(key));
                        if (tag != null && clans.containsKey(tag)) {
                            tags.add(tag);
                        }
                    } else {
                        for (String raw : inv.getStringList(key)) {
                            String tag = normalizeTag(raw);
                            if (tag != null && clans.containsKey(tag)) {
                                tags.add(tag);
                            }
                        }
                    }
                    if (!tags.isEmpty()) {
                        invites.put(id, tags);
                    }
                } catch (IllegalArgumentException ignored) {
                    // skip
                }
            }
        }
        pruneInvites();
        plugin.getLogger().info("Loaded " + clans.size() + " clan(s)");
    }

    public void save() {
        pruneInvites();
        FileConfiguration cfg = new YamlConfiguration();
        for (Clan clan : clans.values()) {
            String path = "clans." + clan.tag;
            cfg.set(path + ".owner", clan.ownerId.toString());
            for (Map.Entry<UUID, ClanRole> e : clan.members.entrySet()) {
                cfg.set(path + ".members." + e.getKey(), e.getValue().name());
            }
        }
        for (Map.Entry<UUID, Set<String>> e : invites.entrySet()) {
            cfg.set("invites." + e.getKey(), new ArrayList<>(e.getValue()));
        }
        try {
            cfg.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save clans.yml: " + ex.getMessage());
        }
    }

    public void pruneInvites() {
        invites.entrySet().removeIf(e -> {
            if (clanOf(e.getKey()) != null) {
                return true;
            }
            e.getValue().removeIf(tag -> !clans.containsKey(tag));
            return e.getValue().isEmpty();
        });
    }

    public static String normalizeTag(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (t.isEmpty() || t.length() > MAX_TAG_LEN) {
            return null;
        }
        return t;
    }

    public Clan get(String tag) {
        String n = normalizeTag(tag);
        return n == null ? null : clans.get(n);
    }

    public Clan clanOf(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        String tag = playerClan.get(playerId);
        return tag == null ? null : clans.get(tag);
    }

    public ClanRole roleOf(UUID playerId) {
        Clan c = clanOf(playerId);
        return c == null ? null : c.roleOf(playerId);
    }

    public Collection<Clan> allClans() {
        return List.copyOf(clans.values());
    }

    public List<String> pendingInvites(UUID playerId) {
        pruneInvites();
        if (playerId == null || clanOf(playerId) != null) {
            return List.of();
        }
        Set<String> set = invites.get(playerId);
        if (set == null || set.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(set);
        out.sort(String::compareTo);
        return out;
    }

    public String create(Player owner, String rawTag) {
        if (owner == null) {
            return "&cPlayers only.";
        }
        if (clanOf(owner.getUniqueId()) != null) {
            return "&cLeave your current clan first.";
        }
        String tag = normalizeTag(rawTag);
        if (tag == null) {
            return "&cClan name must be 1–" + MAX_TAG_LEN + " letters/numbers.";
        }
        if (clans.containsKey(tag)) {
            return "&cThat clan already exists.";
        }
        Clan clan = new Clan(tag, owner.getUniqueId());
        clans.put(tag, clan);
        playerClan.put(owner.getUniqueId(), tag);
        invites.remove(owner.getUniqueId());
        save();
        broadcast("&a" + owner.getName() + " &7created clan &f[" + tag + "]&7.");
        return "&aCreated clan &f[" + tag + "]&a.";
    }

    public String invite(Player actor, OfflinePlayer target) {
        if (actor == null || target == null || target.getUniqueId() == null) {
            return "&cUnknown player.";
        }
        Clan clan = clanOf(actor.getUniqueId());
        if (clan == null) {
            return "&cYou're not in a clan.";
        }
        ClanRole role = clan.roleOf(actor.getUniqueId());
        if (role != ClanRole.OWNER && role != ClanRole.MOD) {
            return "&cOnly the owner or clan mods can invite.";
        }
        UUID tid = target.getUniqueId();
        if (clan.isMember(tid)) {
            return "&cThey're already in your clan.";
        }
        if (clanOf(tid) != null) {
            return "&cThey're already in another clan.";
        }
        invites.computeIfAbsent(tid, u -> ConcurrentHashMap.newKeySet()).add(clan.tag);
        save();
        Player online = target.getPlayer();
        if (online != null) {
            online.sendMessage(color(
                    "&aInvited to clan &f[" + clan.tag + "]&a. Use &f/clan join "
                            + clan.tag + "&a."));
        }
        String name = target.getName() != null ? target.getName() : "player";
        return "&aInvited &f" + name + " &ato &f[" + clan.tag + "]&a.";
    }

    public String declineInvite(Player player, String rawTag) {
        if (player == null) {
            return "&cPlayers only.";
        }
        String tag = normalizeTag(rawTag);
        if (tag == null) {
            return "&cInvalid clan name.";
        }
        Set<String> set = invites.get(player.getUniqueId());
        if (set == null || !set.remove(tag)) {
            return "&cNo invite from &f[" + tag + "]&c.";
        }
        if (set.isEmpty()) {
            invites.remove(player.getUniqueId());
        }
        save();
        return "&eDeclined invite to &f[" + tag + "]&e.";
    }

    public String join(Player player, String rawTagOrNull) {
        if (player == null) {
            return "&cPlayers only.";
        }
        if (clanOf(player.getUniqueId()) != null) {
            return "&cYou're already in a clan. Use /clan leave first.";
        }
        pruneInvites();
        Set<String> pending = invites.get(player.getUniqueId());
        String tag;
        if (rawTagOrNull == null || rawTagOrNull.isBlank()) {
            if (pending == null || pending.isEmpty()) {
                return "&cNo pending invite. Use &f/clan join <clan>&c after being invited.";
            }
            if (pending.size() > 1) {
                return "&cYou have multiple invites. Use &f/clan join <clan>&c.";
            }
            tag = pending.iterator().next();
        } else {
            tag = normalizeTag(rawTagOrNull);
            if (tag == null) {
                return "&cInvalid clan name.";
            }
            if (pending == null || !pending.contains(tag)) {
                return "&cYou need an invite to join &f[" + tag + "]&c.";
            }
        }
        Clan clan = clans.get(tag);
        if (clan == null) {
            if (pending != null) {
                pending.remove(tag);
                if (pending.isEmpty()) {
                    invites.remove(player.getUniqueId());
                }
            }
            save();
            return "&cThat clan no longer exists.";
        }
        clan.members.put(player.getUniqueId(), ClanRole.MEMBER);
        playerClan.put(player.getUniqueId(), tag);
        invites.remove(player.getUniqueId());
        save();
        broadcast("&a" + player.getName() + " &7joined clan &f[" + tag + "]&7.");
        return "&aJoined clan &f[" + tag + "]&a.";
    }

    public String leave(Player player) {
        if (player == null) {
            return "&cPlayers only.";
        }
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            return "&cYou're not in a clan.";
        }
        ClanRole role = clan.roleOf(player.getUniqueId());
        String tag = clan.tag;
        String name = player.getName();
        if (role == ClanRole.OWNER) {
            disbandInternal(tag, name + " left");
            return "&cYou left and disbanded &f[" + tag + "]&c.";
        }
        clan.members.remove(player.getUniqueId());
        playerClan.remove(player.getUniqueId());
        save();
        broadcast("&e" + name + " &7left clan &f[" + tag + "]&7.");
        return "&eLeft clan &f[" + tag + "]&e.";
    }

    public String promote(Player actor, OfflinePlayer target) {
        return setRole(actor, target, true);
    }

    public String demote(Player actor, OfflinePlayer target) {
        return setRole(actor, target, false);
    }

    private String setRole(Player actor, OfflinePlayer target, boolean promote) {
        if (actor == null || target == null || target.getUniqueId() == null) {
            return "&cUnknown player.";
        }
        Clan clan = clanOf(actor.getUniqueId());
        if (clan == null) {
            return "&cYou're not in a clan.";
        }
        if (clan.roleOf(actor.getUniqueId()) != ClanRole.OWNER) {
            return "&cOnly the clan owner can promote/demote.";
        }
        UUID tid = target.getUniqueId();
        if (!clan.isMember(tid)) {
            return "&cThey're not in your clan.";
        }
        ClanRole current = clan.roleOf(tid);
        if (current == ClanRole.OWNER) {
            return "&cYou can't change the owner's role.";
        }
        String name = target.getName() != null ? target.getName() : "player";
        if (promote) {
            if (current == ClanRole.MOD) {
                return "&cThey're already a clan mod.";
            }
            clan.members.put(tid, ClanRole.MOD);
            save();
            notifyClan(clan, "&a" + name + " &7was promoted to clan &fmod&7.");
            return "&aPromoted &f" + name + " &ato clan mod.";
        }
        if (current != ClanRole.MOD) {
            return "&cThey're not a clan mod.";
        }
        clan.members.put(tid, ClanRole.MEMBER);
        save();
        notifyClan(clan, "&e" + name + " &7was demoted to member.");
        return "&eDemoted &f" + name + "&e.";
    }

    public String adminDelete(CommandSender actor, String rawTag) {
        String tag = normalizeTag(rawTag);
        if (tag == null || !clans.containsKey(tag)) {
            return "&cUnknown clan.";
        }
        String by = actor != null ? actor.getName() : "Console";
        disbandInternal(tag, "deleted by " + by);
        return "&cDeleted clan &f[" + tag + "]&c.";
    }

    public String adminKick(CommandSender actor, OfflinePlayer target) {
        if (target == null || target.getUniqueId() == null) {
            return "&cUnknown player.";
        }
        Clan clan = clanOf(target.getUniqueId());
        if (clan == null) {
            return "&cThey're not in a clan.";
        }
        if (clan.roleOf(target.getUniqueId()) == ClanRole.OWNER) {
            return "&cCan't kick the owner. Use &f/clan setowner &cor &f/clan delete&c.";
        }
        String name = target.getName() != null ? target.getName() : "player";
        String tag = clan.tag;
        clan.members.remove(target.getUniqueId());
        playerClan.remove(target.getUniqueId());
        save();
        notifyClan(clan, "&c" + name + " &7was kicked from the clan by staff.");
        broadcast("&c" + name + " &7was kicked from clan &f[" + tag + "]&7.");
        Player online = target.getPlayer();
        if (online != null) {
            online.sendMessage(color("&cYou were kicked from clan &f[" + tag + "]&c."));
        }
        return "&aKicked &f" + name + " &afrom &f[" + tag + "]&a.";
    }

    public String adminSetOwner(CommandSender actor, String rawTag, OfflinePlayer target) {
        String tag = normalizeTag(rawTag);
        Clan clan = tag == null ? null : clans.get(tag);
        if (clan == null) {
            return "&cUnknown clan.";
        }
        if (target == null || target.getUniqueId() == null) {
            return "&cUnknown player.";
        }
        UUID tid = target.getUniqueId();
        Clan other = clanOf(tid);
        if (other != null && other != clan) {
            return "&cThey're already in another clan. Kick/leave them first.";
        }
        UUID oldOwner = clan.ownerId;
        if (oldOwner.equals(tid)) {
            return "&cThey're already the owner.";
        }
        if (!clan.isMember(tid)) {
            clan.members.put(tid, ClanRole.MEMBER);
            playerClan.put(tid, tag);
            invites.remove(tid);
        }
        if (oldOwner != null && clan.members.containsKey(oldOwner)) {
            clan.members.put(oldOwner, ClanRole.MOD);
        }
        clan.ownerId = tid;
        clan.members.put(tid, ClanRole.OWNER);
        save();
        String name = target.getName() != null ? target.getName() : "player";
        notifyClan(clan, "&6" + name + " &7is now the owner of &f[" + tag + "]&7.");
        broadcast("&6" + name + " &7is now the owner of clan &f[" + tag + "]&7.");
        return "&aSet &f" + name + " &aas owner of &f[" + tag + "]&a.";
    }

    private void disbandInternal(String tag, String reason) {
        Clan clan = clans.remove(tag);
        if (clan == null) {
            return;
        }
        for (UUID id : new ArrayList<>(clan.members.keySet())) {
            playerClan.remove(id);
        }
        invites.values().forEach(set -> set.remove(tag));
        pruneInvites();
        save();
        String why = reason == null || reason.isBlank() ? "disbanded" : reason;
        broadcast("&cClan &f[" + tag + "] &cwas disbanded &8(" + why + ")&c.");
    }

    /**
     * Chat tag fragment. Owner underlined, mod italic:
     * {@code &7[&nABCD&7] }
     */
    public String chatTagFragment(UUID playerId) {
        Clan clan = clanOf(playerId);
        if (clan == null) {
            return "";
        }
        ClanRole role = clan.roleOf(playerId);
        String style = "";
        if (role == ClanRole.OWNER) {
            style = "&n";
        } else if (role == ClanRole.MOD) {
            style = "&o";
        }
        return "&7[" + style + clan.tag + "&7] ";
    }

    public List<Map.Entry<UUID, ClanRole>> sortedMembers(Clan clan) {
        if (clan == null) {
            return List.of();
        }
        List<Map.Entry<UUID, ClanRole>> list = new ArrayList<>(clan.members.entrySet());
        list.sort(Comparator
                .comparingInt((Map.Entry<UUID, ClanRole> e) -> switch (e.getValue()) {
                    case OWNER -> 0;
                    case MOD -> 1;
                    case MEMBER -> 2;
                })
                .thenComparing(e -> {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
                    return op.getName() != null ? op.getName().toLowerCase(Locale.ROOT) : e.getKey().toString();
                }));
        return list;
    }

    private void notifyClan(Clan clan, String message) {
        if (clan == null || message == null) {
            return;
        }
        String colored = color(message);
        for (UUID id : clan.members.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.sendMessage(colored);
            }
        }
    }

    private void broadcast(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String colored = color(message);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(colored);
        }
        Bukkit.getConsoleSender().sendMessage(colored);
    }

    static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }
}
