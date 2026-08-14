package com.local.warz.runtime;

import com.local.warz.WarzKeys;
import net.minenite.warzplugin.WarzPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent profile counters (kills / deaths / loot opens / playtime). */
public final class ProfileStatsService implements Listener {
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();
    private static final Set<EntityType> ZOMBIES = EnumSet.of(
            EntityType.ZOMBIE,
            EntityType.HUSK,
            EntityType.ZOMBIE_VILLAGER,
            EntityType.ZOMBIFIED_PIGLIN,
            EntityType.PARCHED
    );
    private static final long MIN_MS = 60_000L;
    private static final long HOUR_MS = 60 * MIN_MS;
    private static final long DAY_MS = 24 * HOUR_MS;
    private static final long WEEK_MS = 7 * DAY_MS;
    private static final long MONTH_MS = 30 * DAY_MS;
    private static final long YEAR_MS = 365 * DAY_MS;

    public static final class Stats {
        public long zombieKills;
        public long playerKills;
        public long pvpDeaths;
        public long chestsLooted;
        /** Accumulated online time (ms), not including the current session. */
        public long playtimeMs;
        public String lastName;

        public Stats copy() {
            Stats s = new Stats();
            s.zombieKills = zombieKills;
            s.playerKills = playerKills;
            s.pvpDeaths = pvpDeaths;
            s.chestsLooted = chestsLooted;
            s.playtimeMs = playtimeMs;
            s.lastName = lastName;
            return s;
        }
    }

    private final WarzPlugin plugin;
    private final File file;
    private final NamespacedKey crashSiteKey;
    private final Map<UUID, Stats> stats = new ConcurrentHashMap<>();
    /** Dedup PvP credit when both kill-feed and getKiller() fire for one death. */
    private final Set<UUID> pvpCountedVictims = ConcurrentHashMap.newKeySet();
    /** Session start wall-clock ms for online players. */
    private final Map<UUID, Long> sessionStartMs = new ConcurrentHashMap<>();
    private BukkitTask playtimeTask;

    public ProfileStatsService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "profiles.yml");
        this.crashSiteKey = WarzKeys.of("crash_site");
    }

    public void start() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            beginSession(p);
        }
        playtimeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flushAllSessions, 20L * 60, 20L * 60);
    }

    public void stop() {
        if (playtimeTask != null) {
            playtimeTask.cancel();
            playtimeTask = null;
        }
        flushAllSessions();
        save();
    }

    public void load() {
        stats.clear();
        if (!file.exists()) {
            return;
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = cfg.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection sec = players.getConfigurationSection(key);
                if (sec == null) {
                    continue;
                }
                Stats s = new Stats();
                s.zombieKills = sec.getLong("zombie-kills", 0L);
                s.playerKills = sec.getLong("player-kills", 0L);
                s.pvpDeaths = sec.getLong("pvp-deaths", 0L);
                s.chestsLooted = sec.getLong("chests-looted", 0L);
                s.playtimeMs = sec.getLong("playtime-ms", 0L);
                s.lastName = sec.getString("name");
                stats.put(id, s);
            } catch (IllegalArgumentException ignored) {
                // skip bad uuid
            }
        }
        plugin.getLogger().info("Loaded " + stats.size() + " player profile(s)");
    }

    public void save() {
        FileConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Stats> e : stats.entrySet()) {
            String path = "players." + e.getKey();
            Stats s = e.getValue();
            cfg.set(path + ".zombie-kills", s.zombieKills);
            cfg.set(path + ".player-kills", s.playerKills);
            cfg.set(path + ".pvp-deaths", s.pvpDeaths);
            cfg.set(path + ".chests-looted", s.chestsLooted);
            cfg.set(path + ".playtime-ms", s.playtimeMs);
            if (s.lastName != null) {
                cfg.set(path + ".name", s.lastName);
            }
        }
        try {
            cfg.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save profiles.yml: " + ex.getMessage());
        }
    }

    public Stats get(UUID id) {
        if (id == null) {
            return new Stats();
        }
        Stats s = stats.get(id);
        return s == null ? new Stats() : s.copy();
    }

    public Stats getOrCreate(UUID id) {
        return stats.computeIfAbsent(id, u -> new Stats());
    }

    public void touchName(UUID id, String name) {
        if (id == null || name == null || name.isBlank()) {
            return;
        }
        Stats s = getOrCreate(id);
        if (!name.equals(s.lastName)) {
            s.lastName = name;
            save();
        }
    }

    public void recordZombieKill(UUID killerId) {
        if (killerId == null) {
            return;
        }
        getOrCreate(killerId).zombieKills++;
        save();
    }

    /** Player kill + victim PvP death (deduped per victim death). */
    public void recordPvP(UUID killerId, UUID victimId) {
        if (killerId == null || victimId == null || killerId.equals(victimId)) {
            return;
        }
        if (!pvpCountedVictims.add(victimId)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> pvpCountedVictims.remove(victimId));
        getOrCreate(killerId).playerKills++;
        getOrCreate(victimId).pvpDeaths++;
        save();
    }

    public void recordChestLoot(UUID playerId) {
        if (playerId == null) {
            return;
        }
        getOrCreate(playerId).chestsLooted++;
        save();
    }

    /** Total playtime including the current session if online. */
    public long getPlaytimeMs(UUID id) {
        if (id == null) {
            return 0L;
        }
        Stats s = stats.get(id);
        long base = s == null ? 0L : s.playtimeMs;
        Long start = sessionStartMs.get(id);
        if (start != null) {
            base += Math.max(0L, System.currentTimeMillis() - start);
        }
        return base;
    }

    public static double kdrValue(long kills, long deaths) {
        if (deaths <= 0L) {
            return kills <= 0L ? 0.0 : (double) kills;
        }
        return (double) kills / (double) deaths;
    }

    /** Good ≥ 2.0 {@code &a}, okay ≥ 1.0 {@code &6}, bad {@code &c}. */
    public static String kdrColorCode(double ratio, long kills, long deaths) {
        if (kills <= 0L && deaths <= 0L) {
            return "&6";
        }
        if (ratio >= 2.0) {
            return "&a";
        }
        if (ratio >= 1.0) {
            return "&6";
        }
        return "&c";
    }

    public static String formatPlaytime(long ms) {
        if (ms < MIN_MS) {
            return "<1m";
        }
        long remaining = ms;
        long years = remaining / YEAR_MS;
        remaining %= YEAR_MS;
        long months = remaining / MONTH_MS;
        remaining %= MONTH_MS;
        long weeks = remaining / WEEK_MS;
        remaining %= WEEK_MS;
        long days = remaining / DAY_MS;
        remaining %= DAY_MS;
        long hours = remaining / HOUR_MS;
        remaining %= HOUR_MS;
        long mins = remaining / MIN_MS;

        List<String> parts = new ArrayList<>();
        if (years > 0) {
            parts.add(years + "y");
        }
        if (months > 0) {
            parts.add(months + "mo");
        }
        if (weeks > 0) {
            parts.add(weeks + "w");
        }
        if (days > 0) {
            parts.add(days + "d");
        }
        if (hours > 0) {
            parts.add(hours + "h");
        }
        if (mins > 0 || parts.isEmpty()) {
            parts.add(mins + "m");
        }
        // Cap to the three largest units for readability.
        if (parts.size() > 3) {
            parts = parts.subList(0, 3);
        }
        return String.join(" ", parts);
    }

    /** Hover body for kill-feed usernames. */
    public Component statsHover(UUID id) {
        Stats s = get(id);
        double ratio = kdrValue(s.playerKills, s.pvpDeaths);
        String color = kdrColorCode(ratio, s.playerKills, s.pvpDeaths);
        String name = s.lastName != null ? s.lastName : "Player";
        return LEGACY.deserialize(
                "&f&l" + name + "\n"
                        + "&7Kills: &f" + String.format(Locale.US, "%,d", s.playerKills) + "\n"
                        + "&7Deaths: &f" + String.format(Locale.US, "%,d", s.pvpDeaths) + "\n"
                        + "&7KDR: " + color + String.format(Locale.US, "%.2f", ratio)
        ).decoration(TextDecoration.ITALIC, false);
    }

    /** Colored name with profile stats hover (preserves leading color from legacy chunk). */
    public Component nameWithStatsHover(UUID id, String name, String leadingLegacyColor, boolean bold) {
        String display = name == null ? "Someone" : name;
        String color = leadingLegacyColor == null || leadingLegacyColor.isBlank() ? "&6" : leadingLegacyColor;
        Component base = LEGACY.deserialize(color + display)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, bold);
        if (id == null) {
            return base;
        }
        return base.hoverEvent(HoverEvent.showText(statsHover(id)));
    }

    private void beginSession(Player player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        sessionStartMs.putIfAbsent(id, System.currentTimeMillis());
        touchName(id, player.getName());
    }

    private void endSession(UUID id) {
        if (id == null) {
            return;
        }
        Long start = sessionStartMs.remove(id);
        if (start == null) {
            return;
        }
        long delta = Math.max(0L, System.currentTimeMillis() - start);
        if (delta > 0L) {
            getOrCreate(id).playtimeMs += delta;
        }
    }

    private void flushAllSessions() {
        long now = System.currentTimeMillis();
        boolean dirty = false;
        for (Map.Entry<UUID, Long> e : sessionStartMs.entrySet()) {
            long start = e.getValue();
            long delta = Math.max(0L, now - start);
            if (delta > 0L) {
                getOrCreate(e.getKey()).playtimeMs += delta;
                e.setValue(now);
                dirty = true;
            }
        }
        if (dirty) {
            save();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        beginSession(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        endSession(event.getPlayer().getUniqueId());
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!ZOMBIES.contains(entity.getType())) {
            return;
        }
        Player killer = entity.getKiller();
        if (killer == null) {
            return;
        }
        touchName(killer.getUniqueId(), killer.getName());
        recordZombieKill(killer.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        touchName(victim.getUniqueId(), victim.getName());
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        touchName(killer.getUniqueId(), killer.getName());
        recordPvP(killer.getUniqueId(), victim.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Block block = blockOf(event.getInventory().getHolder());
        if (block == null) {
            return;
        }
        boolean lootChest = plugin.lootRestock() != null && plugin.lootRestock().isLootChest(block);
        boolean crashBarrel = isCrashBarrel(block);
        if (!lootChest && !crashBarrel) {
            return;
        }
        touchName(player.getUniqueId(), player.getName());
        recordChestLoot(player.getUniqueId());
    }

    private boolean isCrashBarrel(Block block) {
        if (block == null || block.getType() != Material.BARREL) {
            return false;
        }
        if (!(block.getState() instanceof Barrel barrel)) {
            return false;
        }
        String id = barrel.getPersistentDataContainer().get(crashSiteKey, PersistentDataType.STRING);
        if (id == null) {
            return false;
        }
        return plugin.crashSites() != null && plugin.crashSites().isActiveSite(id);
    }

    private static Block blockOf(InventoryHolder holder) {
        if (holder instanceof Chest chest) {
            return chest.getBlock();
        }
        if (holder instanceof DoubleChest dbl) {
            InventoryHolder left = dbl.getLeftSide();
            if (left instanceof Chest lc) {
                return lc.getBlock();
            }
            InventoryHolder right = dbl.getRightSide();
            if (right instanceof Chest rc) {
                return rc.getBlock();
            }
        }
        if (holder instanceof Barrel barrel) {
            return barrel.getBlock();
        }
        return null;
    }

    /** Resolve offline target for /profile &lt;name&gt;. */
    public OfflinePlayer resolvePlayer(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        for (Map.Entry<UUID, Stats> e : stats.entrySet()) {
            if (e.getValue().lastName != null && e.getValue().lastName.equalsIgnoreCase(name)) {
                return Bukkit.getOfflinePlayer(e.getKey());
            }
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        if (off.hasPlayedBefore() || off.isOnline()) {
            return off;
        }
        return null;
    }
}
