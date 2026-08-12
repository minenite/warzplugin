package net.minenite.warzplugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * DayZ / Origins-style humanity: Survivor, Hero 1–4, Bandit 1–4.
 *
 * <p>Default 2500 (Survivor). Below 2500 regenerates slowly back to 2500.
 * Ten zombie kills in about a minute awards +300. PvP uses the Origins formulas.
 */
public final class HumanityService implements Listener {

    public static final int DEFAULT = 2500;
    public static final int REGEN_CAP = 2500;

    /** Hero thresholds (strictly greater than). */
    public static final int HERO_1 = 2900;
    public static final int HERO_2 = 6500;
    public static final int HERO_3 = 15000;
    public static final int HERO_4 = 25000;

    /** Bandit thresholds (strictly less than). */
    public static final int BANDIT_1 = 2000;
    public static final int BANDIT_2 = -6500;
    public static final int BANDIT_3 = -15000;
    public static final int BANDIT_4 = -25000;

    private static final int REGEN_PER_MINUTE = 50;
    private static final long ZOMBIE_WINDOW_MS = 60_000L;
    private static final int ZOMBIE_BATCH = 10;
    private static final int ZOMBIE_REWARD = 300;

    public enum Kind {
        SURVIVOR,
        HERO,
        BANDIT
    }

    public record Standing(Kind kind, int level) {
        /** 0 for survivor; 1–4 for hero/bandit. */
        public int levelOrZero() {
            return kind == Kind.SURVIVOR ? 0 : level;
        }
    }

    private final WarzPlugin plugin;
    private final File file;
    private final Map<UUID, Integer> humanity = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> recentZombieKills = new ConcurrentHashMap<>();
    private BukkitTask regenTask;
    private BukkitTask saveTask;

    public HumanityService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "humanity.yml");
    }

    public void start() {
        load();
        if (regenTask != null) {
            regenTask.cancel();
        }
        // Once a minute: nudge anyone below 2500 toward Survivor baseline.
        regenTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRegen, 20L * 60, 20L * 60);
        saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::save, 20L * 60, 20L * 60);
    }

    public void stop() {
        if (regenTask != null) {
            regenTask.cancel();
            regenTask = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        save();
    }

    public int get(UUID id) {
        if (id == null) {
            return DEFAULT;
        }
        return humanity.getOrDefault(id, DEFAULT);
    }

    public int get(Player player) {
        return player == null ? DEFAULT : get(player.getUniqueId());
    }

    public Standing standingOf(UUID id) {
        return standingFor(get(id));
    }

    public Standing standingOf(Player player) {
        return standingFor(get(player));
    }

    public static Standing standingFor(int h) {
        if (h > HERO_4) {
            return new Standing(Kind.HERO, 4);
        }
        if (h > HERO_3) {
            return new Standing(Kind.HERO, 3);
        }
        if (h > HERO_2) {
            return new Standing(Kind.HERO, 2);
        }
        if (h > HERO_1) {
            return new Standing(Kind.HERO, 1);
        }
        if (h < BANDIT_4) {
            return new Standing(Kind.BANDIT, 4);
        }
        if (h < BANDIT_3) {
            return new Standing(Kind.BANDIT, 3);
        }
        if (h < BANDIT_2) {
            return new Standing(Kind.BANDIT, 2);
        }
        if (h < BANDIT_1) {
            return new Standing(Kind.BANDIT, 1);
        }
        return new Standing(Kind.SURVIVOR, 0);
    }

    public void set(UUID id, int value) {
        if (id == null) {
            return;
        }
        humanity.put(id, value);
    }

    public void add(UUID id, int delta) {
        if (id == null || delta == 0) {
            return;
        }
        humanity.put(id, get(id) + delta);
    }

    /**
     * Chat / scoreboard tag, e.g. {@code &7[Survivor]}, {@code &a[Hero 1]}, {@code &c[Bandit 3]}.
     */
    public String chatTagFragment(UUID id) {
        Standing s = standingOf(id);
        return switch (s.kind()) {
            case SURVIVOR -> "&7[&fSurvivor&7] ";
            case HERO -> heroColor(s.level()) + "[Hero " + s.level() + "] ";
            case BANDIT -> banditColor(s.level()) + "[Bandit " + s.level() + "] ";
        };
    }

    public String scoreboardLine(Player player) {
        int h = get(player);
        Standing s = standingFor(h);
        String label = switch (s.kind()) {
            case SURVIVOR -> "&7Survivor";
            case HERO -> heroColor(s.level()) + "Hero " + s.level();
            case BANDIT -> banditColor(s.level()) + "Bandit " + s.level();
        };
        String valueColor = h >= REGEN_CAP ? "&a" : (h >= BANDIT_1 ? "&e" : "&c");
        return color(label + " &8| " + valueColor + formatHumanity(h));
    }

    private static String heroColor(int level) {
        return switch (level) {
            case 1 -> "&a";
            case 2 -> "&2";
            case 3 -> "&b";
            default -> "&6";
        };
    }

    private static String banditColor(int level) {
        return switch (level) {
            case 1 -> "&c";
            case 2 -> "&4";
            case 3 -> "&4&l";
            default -> "&8&l";
        };
    }

    private static String formatHumanity(int h) {
        return String.format("%,d", h);
    }

    private static String color(String raw) {
        return ChatColor.translateAlternateColorCodes('&', raw == null ? "" : raw);
    }

    private void tickRegen() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            int h = get(player);
            if (h < REGEN_CAP) {
                add(player.getUniqueId(), Math.min(REGEN_PER_MINUTE, REGEN_CAP - h));
            }
        }
    }

    /* -------------------- combat -------------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        Entity dead = event.getEntity();
        if (dead instanceof Player) {
            return;
        }
        if (!isZombieLike(dead)) {
            return;
        }
        Deque<Long> times = recentZombieKills.computeIfAbsent(
                killer.getUniqueId(), id -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > ZOMBIE_WINDOW_MS) {
            times.removeFirst();
        }
        if (times.size() >= ZOMBIE_BATCH) {
            times.clear();
            Standing before = standingOf(killer);
            add(killer.getUniqueId(), ZOMBIE_REWARD);
            notifyChange(killer, before, "+" + ZOMBIE_REWARD + " humanity (zombies)");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        Standing victimBefore = standingOf(victim);
        int deathDelta = deathDelta(victimBefore);
        if (deathDelta != 0) {
            add(victim.getUniqueId(), deathDelta);
            String sign = deathDelta > 0 ? "+" : "";
            notifyChange(victim, victimBefore, sign + deathDelta + " humanity (death)");
        }

        if (killer == null || killer.equals(victim)) {
            return;
        }
        Standing killerBefore = standingOf(killer);
        Standing target = victimBefore;
        int delta = killDelta(killerBefore, target);
        if (delta != 0) {
            add(killer.getUniqueId(), delta);
            String sign = delta > 0 ? "+" : "";
            notifyChange(killer, killerBefore, sign + delta + " humanity");
        }
    }

    /**
     * Origins PvP formulas.
     *
     * <pre>
     * Bandit:  survivor -(200 - 100*Bp); bandit +200*(3-Bp)*Bt; hero -500*Ht
     * Survivor: survivor -500; bandit +400*Bt; hero -500*Ht
     * Hero:    survivor -(100+100*Hp)*Hp; bandit +300*Bt; hero -500*Ht
     * </pre>
     */
    static int killDelta(Standing killer, Standing target) {
        int kp = killer.levelOrZero();
        int tp = target.levelOrZero();
        return switch (killer.kind()) {
            case BANDIT -> switch (target.kind()) {
                case SURVIVOR -> -(200 - (100 * kp));
                case BANDIT -> 200 * (3 - kp) * Math.max(1, tp);
                case HERO -> -500 * Math.max(1, tp);
            };
            case SURVIVOR -> switch (target.kind()) {
                case SURVIVOR -> -500;
                case BANDIT -> 400 * Math.max(1, tp);
                case HERO -> -500 * Math.max(1, tp);
            };
            case HERO -> switch (target.kind()) {
                case SURVIVOR -> -((100 + (100 * kp)) * kp);
                case BANDIT -> 300 * Math.max(1, tp);
                case HERO -> -500 * Math.max(1, tp);
            };
        };
    }

    /** Bandit death: +(200*(B-1)); Hero death: -(100*(H-1)); Survivor: 0. */
    static int deathDelta(Standing standing) {
        return switch (standing.kind()) {
            case BANDIT -> 200 * (standing.level() - 1);
            case HERO -> -(100 * (standing.level() - 1));
            case SURVIVOR -> 0;
        };
    }

    private static boolean isZombieLike(Entity entity) {
        if (entity instanceof Zombie) {
            return true;
        }
        if (!(entity instanceof Monster)) {
            return false;
        }
        String name = entity.getType().name();
        return name.contains("ZOMBIE") || name.contains("DROWNED") || name.contains("HUSK")
                || name.contains("ZOGLIN") || name.equals("PHANTOM");
    }

    private void notifyChange(Player player, Standing before, String reason) {
        Standing after = standingOf(player);
        player.sendMessage(ChatColor.GRAY + reason + ChatColor.DARK_GRAY + " → "
                + ChatColor.WHITE + formatHumanity(get(player)));
        if (before.kind() != after.kind() || before.level() != after.level()) {
            player.sendMessage(ChatColor.GOLD + "Standing: "
                    + ChatColor.translateAlternateColorCodes('&',
                    chatTagFragment(player.getUniqueId()).trim()));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        humanity.putIfAbsent(event.getPlayer().getUniqueId(), DEFAULT);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        recentZombieKills.remove(event.getPlayer().getUniqueId());
    }

    /* -------------------- persistence -------------------- */

    private void load() {
        humanity.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yaml.getConfigurationSection("players");
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                humanity.put(id, sec.getInt(key, DEFAULT));
            } catch (IllegalArgumentException ignored) {
                // skip
            }
        }
        plugin.getLogger().info("Loaded humanity for " + humanity.size() + " player(s)");
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> e : humanity.entrySet()) {
            yaml.set("players." + e.getKey(), e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save humanity.yml: " + ex.getMessage());
        }
    }
}
