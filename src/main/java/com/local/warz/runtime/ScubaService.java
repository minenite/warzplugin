package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scuba gear: helmet + tank = underwater breathing.
 * Wetsuit legs + boots hold body heat in water; below 95°F → freeze overlay + slow damage.
 * Sidebar shows body temp and blood volume.
 */
public final class ScubaService implements Listener {
    private static final double NORMAL_TEMP_F = 98.6;
    private static final double DAMAGE_TEMP_F = 95.0;
    private static final double MIN_TEMP_F = 82.0;
    private static final double COOL_PER_TICK = 0.008;   // ~0.16°F/s in water without wetsuit
    private static final double WARM_PER_TICK = 0.012;   // recover when dry / suited
    private static final int COLD_DAMAGE_INTERVAL = 40;  // 2s
    private static final double COLD_DAMAGE = 1.0;
    private static final int BOARD_EVERY = 10;
    private static final String OBJ_NAME = "warz_temp";

    private final WarzPlugin plugin;
    private final Map<UUID, Double> bodyTemp = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> coldDamageCd = new ConcurrentHashMap<>();
    /** Set while applying hypothermia tick damage — MedicalService must not roll bleed. */
    private final java.util.Set<UUID> coldDamageActive = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    private BukkitTask task;
    private int tick;

    public ScubaService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) {
            task.cancel();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            ensureTemp(player);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (boards.containsKey(player.getUniqueId())) {
                player.setScoreboard(main);
            }
        }
        boards.clear();
        bodyTemp.clear();
        coldDamageCd.clear();
    }

    private void tick() {
        tick++;
        boolean updateBoard = tick % BOARD_EVERY == 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            tickPlayer(player, updateBoard);
        }
    }

    private void tickPlayer(Player player, boolean updateBoard) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            bodyTemp.put(player.getUniqueId(), NORMAL_TEMP_F);
            clearFreezeIfOurs(player);
            return;
        }
        if (player.isDead()) {
            return;
        }

        PlayerInventory inv = player.getInventory();
        ItemFactory items = plugin.items();
        if (items == null) {
            return;
        }
        boolean helmet = items.isScubaHelmet(inv.getHelmet());
        boolean tank = items.isScubaTank(inv.getChestplate());
        boolean legs = items.isWetsuitLeggings(inv.getLeggings());
        boolean boots = items.isWetsuitBoots(inv.getBoots());
        boolean wetsuit = legs && boots;

        // Helmet + tank → breathe underwater
        if (helmet && tank && (player.isUnderWater() || eyeInWater(player))) {
            player.setRemainingAir(player.getMaximumAir());
        }

        double temp = ensureTemp(player);
        boolean wet = player.isInWater() || player.isUnderWater() || player.isInWaterOrBubbleColumn();
        if (wet && !wetsuit) {
            temp -= COOL_PER_TICK;
        } else {
            temp += WARM_PER_TICK;
        }
        temp = clamp(temp, MIN_TEMP_F, NORMAL_TEMP_F);
        bodyTemp.put(player.getUniqueId(), temp);

        applyFreezeOverlay(player, temp);

        if (temp < DAMAGE_TEMP_F) {
            int cd = coldDamageCd.getOrDefault(player.getUniqueId(), 0);
            if (cd > 0) {
                coldDamageCd.put(player.getUniqueId(), cd - 1);
            } else {
                UUID id = player.getUniqueId();
                coldDamageActive.add(id);
                try {
                    player.damage(COLD_DAMAGE);
                } finally {
                    coldDamageActive.remove(id);
                }
                player.sendActionBar(ItemFactory.colorize("&bHypothermia… &7Wear a &3Wetsuit"));
                coldDamageCd.put(player.getUniqueId(), COLD_DAMAGE_INTERVAL);
            }
        } else {
            coldDamageCd.remove(player.getUniqueId());
        }
        // Sidebar lives in ScoreboardService so the two HUDs do not steal the board.
    }

    private void applyFreezeOverlay(Player player, double temp) {
        if (inPowderSnow(player)) {
            return; // leave vanilla powdered-snow freeze alone
        }
        if (temp >= DAMAGE_TEMP_F) {
            if (player.getFreezeTicks() > 0) {
                player.setFreezeTicks(0);
            }
            return;
        }
        double severity = (DAMAGE_TEMP_F - temp) / (DAMAGE_TEMP_F - MIN_TEMP_F); // 0..1
        severity = clamp(severity, 0.0, 1.0);
        int max = Math.max(2, player.getMaxFreezeTicks());
        // Overlay only — stay under max so vanilla freeze damage doesn't stack with ours
        int ticks = (int) Math.round((max - 1) * (0.30 + 0.70 * severity));
        player.setFreezeTicks(Math.min(max - 1, Math.max(1, ticks)));
    }

    private void clearFreezeIfOurs(Player player) {
        if (!inPowderSnow(player) && player.getFreezeTicks() > 0) {
            player.setFreezeTicks(0);
        }
    }

    private static boolean inPowderSnow(Player player) {
        return player.getLocation().getBlock().getType() == Material.POWDER_SNOW
                || player.getEyeLocation().getBlock().getType() == Material.POWDER_SNOW;
    }

    private static boolean eyeInWater(Player player) {
        Material eye = player.getEyeLocation().getBlock().getType();
        return eye == Material.WATER || eye == Material.BUBBLE_COLUMN;
    }

    private double ensureTemp(Player player) {
        return bodyTemp.computeIfAbsent(player.getUniqueId(), id -> NORMAL_TEMP_F);
    }

    /** True for the duration of a hypothermia {@code player.damage} call. */
    public boolean isColdDamageActive(Player player) {
        return player != null && coldDamageActive.contains(player.getUniqueId());
    }

    public boolean isHypothermic(Player player) {
        return player != null && ensureTemp(player) < DAMAGE_TEMP_F;
    }

    public double bodyTemp(Player player) {
        if (player == null) {
            return NORMAL_TEMP_F;
        }
        return ensureTemp(player);
    }

    /** Green = good, orange = cooling, red = hypothermia danger (&lt;95°F). */
    private static String tempColor(double tempF) {
        if (tempF < DAMAGE_TEMP_F) {
            return "&c"; // dangerously cold
        }
        if (tempF < 97.5) {
            return "&6"; // moderate / cooling (orange/gold)
        }
        return "&a"; // good
    }

    private void attachBoard(Player player) {
        if (boards.containsKey(player.getUniqueId())) {
            player.setScoreboard(boards.get(player.getUniqueId()));
            return;
        }
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective(
                OBJ_NAME,
                Criteria.DUMMY,
                ItemFactory.colorize("&b&lVitals")
        );
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        obj.numberFormat(NumberFormat.blank());
        boards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
    }

    private void updateSidebar(Player player, double tempF) {
        attachBoard(player);
        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null) {
            return;
        }
        Objective obj = board.getObjective(OBJ_NAME);
        if (obj == null) {
            return;
        }
        for (String entry : board.getEntries()) {
            board.resetScores(entry);
        }

        double blood = plugin.medical() != null ? plugin.medical().bloodLiters(player) : MedicalService.MAX_BLOOD_L;
        double thirstVal = plugin.thirst() != null ? plugin.thirst().thirst(player) : ThirstService.MAX_THIRST;
        String deg = legacy(String.format(Locale.US, "%s%.1f\u00B0F", tempColor(tempF), tempF));
        String bloodLine = legacy(String.format(Locale.US, "%sBlood: %.1fL", bloodColor(blood), blood));
        String thirstLine = legacy(String.format(Locale.US, "%sThirst: %.0f%%", thirstColor(thirstVal), thirstVal));
        setLine(obj, deg + "§0", 3);
        setLine(obj, bloodLine + "§1", 2);
        setLine(obj, thirstLine + "§2", 1);
    }

    /** Green ≥60%, orange 30–60%, red &lt;30%. */
    private static String thirstColor(double thirst) {
        if (thirst < ThirstService.THIRST_CRITICAL) {
            return "&c";
        }
        if (thirst < ThirstService.THIRST_ORANGE) {
            return "&6";
        }
        return "&a";
    }

    /** Green ≥75%, orange 50–75%, red &lt;50%. */
    private static String bloodColor(double liters) {
        if (liters < MedicalService.BLOOD_CRITICAL_L) {
            return "&c";
        }
        if (liters < MedicalService.BLOOD_ORANGE_L) {
            return "&6";
        }
        return "&a";
    }

    private static void setLine(Objective obj, String entry, int score) {
        if (entry.length() > 40) {
            entry = entry.substring(0, 40);
        }
        obj.getScore(entry).setScore(score);
    }

    private static String legacy(String ampersand) {
        Component c = ItemFactory.colorize(ampersand);
        return LegacyComponentSerializer.legacySection().serialize(c);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        bodyTemp.putIfAbsent(event.getPlayer().getUniqueId(), NORMAL_TEMP_F);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        coldDamageCd.remove(id);
        bodyTemp.remove(id);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        bodyTemp.put(player.getUniqueId(), NORMAL_TEMP_F);
        coldDamageCd.remove(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> clearFreezeIfOurs(player));
    }
}
