package net.minenite.warzplugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import io.papermc.paper.scoreboard.numbers.NumberFormat;

/**
 * WarZ vitals sidebar — body temp, blood, thirst — matching the Paper WarZ HUD.
 *
 * <p>Temp drops in water without a wetsuit; helmet+tank breathe underwater.
 * Blood drains from damage / bleeding and regenerates with hunger.
 * Thirst drains over time and faster while sprinting; water bottles restore it.
 */
public final class ScoreboardService implements Listener {

    public static final double NORMAL_TEMP_F = 98.6;
    public static final double DAMAGE_TEMP_F = 95.0;
    public static final double MIN_TEMP_F = 82.0;
    public static final double MAX_BLOOD_L = 5.0;
    public static final double BLOOD_ORANGE_L = MAX_BLOOD_L * 0.75;
    public static final double BLOOD_CRITICAL_L = MAX_BLOOD_L * 0.50;
    public static final double MAX_THIRST = 100.0;
    public static final double THIRST_ORANGE = 60.0;
    public static final double THIRST_CRITICAL = 30.0;

    private static final double COOL_PER_TICK = 0.008;
    private static final double WARM_PER_TICK = 0.012;
    private static final int COLD_DAMAGE_INTERVAL = 40;
    private static final double COLD_DAMAGE = 1.0;
    private static final int BOARD_EVERY = 10;
    private static final String OBJ_NAME = "warz_vitals";

    private static final double PASSIVE_DRAIN_PER_SEC = 0.015;
    private static final double SPRINT_DRAIN_PER_SEC = 0.18;
    private static final double NORMAL_BLEED_L_PER_SEC = 0.025;
    private static final double BLOOD_REGEN_MAX_L_PER_SEC = 0.04;

    private static final Color SCUBA_MASK_COLOR = Color.fromRGB(0x4A, 0xC0, 0xD4);
    private static final Color SCUBA_TANK_COLOR = Color.fromRGB(0x2A, 0x2A, 0x2E);
    private static final Color WETSUIT_COLOR = Color.fromRGB(0x0A, 0x2F, 0x38);

    private static final String SCUBA_HELMET = "scuba_helmet";
    private static final String SCUBA_TANK = "scuba_tank";
    private static final String WETSUIT_LEGS = "wetsuit_legs";
    private static final String WETSUIT_BOOTS = "wetsuit_boots";
    private static final String BANDAGE = "bandage";

    private final WarzPlugin plugin;
    private final NamespacedKey gearKey;
    private final Map<UUID, Double> bodyTemp = new ConcurrentHashMap<>();
    private final Map<UUID, Double> bloodLiters = new ConcurrentHashMap<>();
    private final Map<UUID, Double> thirst = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> bleeding = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> coldDamageCd = new ConcurrentHashMap<>();
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    private BukkitTask task;
    private int tick;
    private int secCounter;

    public ScoreboardService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.gearKey = new NamespacedKey(plugin, "gear");
    }

    public void start() {
        if (task != null) {
            task.cancel();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            ensureVitals(player);
            attachBoard(player);
            updateSidebar(player);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        plugin.getLogger().info("Vitals scoreboard started");
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
        bloodLiters.clear();
        thirst.clear();
        bleeding.clear();
        coldDamageCd.clear();
    }

    public double bloodLiters(Player player) {
        return ensureBlood(player);
    }

    public double thirst(Player player) {
        return ensureThirst(player);
    }

    public double bodyTemp(Player player) {
        return ensureTemp(player);
    }

    public void giveScubaSet(Player player) {
        PlayerInventory inv = player.getInventory();
        inv.addItem(createScubaHelmet(), createScubaTank(), createWetsuitLeggings(),
                createWetsuitBoots(), createBandage(8));
        player.sendMessage(ChatColor.AQUA + "Scuba set + bandages given.");
    }

    /* -------------------- gear -------------------- */

    public ItemStack createScubaHelmet() {
        return leatherGear(Material.LEATHER_HELMET, SCUBA_MASK_COLOR, SCUBA_HELMET,
                ChatColor.AQUA + "Scuba Helmet",
                ChatColor.GRAY + "Dive mask — wear on head",
                ChatColor.YELLOW + "With Scuba Tank " + ChatColor.GRAY + "→ breathe underwater");
    }

    public ItemStack createScubaTank() {
        return leatherGear(Material.LEATHER_CHESTPLATE, SCUBA_TANK_COLOR, SCUBA_TANK,
                ChatColor.DARK_GRAY + "Scuba Tank",
                ChatColor.GRAY + "Air tank — wear on torso",
                ChatColor.YELLOW + "With Scuba Helmet " + ChatColor.GRAY + "→ breathe underwater");
    }

    public ItemStack createWetsuitLeggings() {
        return leatherGear(Material.LEATHER_LEGGINGS, WETSUIT_COLOR, WETSUIT_LEGS,
                ChatColor.DARK_AQUA + "Wetsuit Leggings",
                ChatColor.GRAY + "Insulated wetsuit bottoms",
                ChatColor.GRAY + "Wear with boots to hold body heat in water");
    }

    public ItemStack createWetsuitBoots() {
        return leatherGear(Material.LEATHER_BOOTS, WETSUIT_COLOR, WETSUIT_BOOTS,
                ChatColor.DARK_AQUA + "Wetsuit Boots",
                ChatColor.GRAY + "Insulated wetsuit footwear",
                ChatColor.GRAY + "Wear with leggings to hold body heat in water");
    }

    public ItemStack createBandage(int amount) {
        ItemStack stack = new ItemStack(Material.PAPER, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(gearKey, PersistentDataType.STRING, BANDAGE);
        meta.setDisplayName(ChatColor.WHITE + "Bandage");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Right-click to stop bleeding");
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack leatherGear(Material type, Color color, String id, String name, String... loreLines) {
        ItemStack stack = new ItemStack(type, 1);
        LeatherArmorMeta meta = (LeatherArmorMeta) stack.getItemMeta();
        meta.setColor(color);
        meta.getPersistentDataContainer().set(gearKey, PersistentDataType.STRING, id);
        meta.setDisplayName(name);
        List<String> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(line);
        }
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private String gearId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(gearKey, PersistentDataType.STRING);
    }

    private boolean isScubaHelmet(ItemStack stack) {
        return SCUBA_HELMET.equals(gearId(stack));
    }

    private boolean isScubaTank(ItemStack stack) {
        return SCUBA_TANK.equals(gearId(stack));
    }

    private boolean isWetsuitLeggings(ItemStack stack) {
        return WETSUIT_LEGS.equals(gearId(stack));
    }

    private boolean isWetsuitBoots(ItemStack stack) {
        return WETSUIT_BOOTS.equals(gearId(stack));
    }

    private boolean isBandage(ItemStack stack) {
        return BANDAGE.equals(gearId(stack));
    }

    /* -------------------- tick -------------------- */

    private void tick() {
        tick++;
        secCounter++;
        boolean updateBoard = tick % BOARD_EVERY == 0;
        boolean second = secCounter % 20 == 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            tickPlayer(player, updateBoard, second);
        }
    }

    private void tickPlayer(Player player, boolean updateBoard, boolean second) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            bodyTemp.put(player.getUniqueId(), NORMAL_TEMP_F);
            bloodLiters.put(player.getUniqueId(), MAX_BLOOD_L);
            thirst.put(player.getUniqueId(), MAX_THIRST);
            bleeding.remove(player.getUniqueId());
            clearFreezeIfOurs(player);
            if (updateBoard) {
                updateSidebar(player);
            }
            return;
        }
        if (player.isDead()) {
            return;
        }

        PlayerInventory inv = player.getInventory();
        boolean helmet = isScubaHelmet(inv.getHelmet());
        boolean tank = isScubaTank(inv.getChestplate());
        boolean wetsuit = isWetsuitLeggings(inv.getLeggings()) && isWetsuitBoots(inv.getBoots());

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
                player.damage(COLD_DAMAGE);
                player.sendActionBar(ChatColor.AQUA + "Hypothermia… " + ChatColor.GRAY
                        + "Wear a " + ChatColor.DARK_AQUA + "Wetsuit");
                coldDamageCd.put(player.getUniqueId(), COLD_DAMAGE_INTERVAL);
            }
        } else {
            coldDamageCd.remove(player.getUniqueId());
        }

        if (second) {
            tickBloodSecond(player);
            tickThirstSecond(player);
        }

        if (updateBoard) {
            updateSidebar(player);
        }
    }

    private void tickBloodSecond(Player player) {
        double blood = ensureBlood(player);
        if (Boolean.TRUE.equals(bleeding.get(player.getUniqueId()))) {
            blood -= NORMAL_BLEED_L_PER_SEC;
            player.sendActionBar(ChatColor.RED + "Bleeding… " + ChatColor.GRAY
                    + "Use a " + ChatColor.WHITE + "Bandage");
        } else if (blood < MAX_BLOOD_L) {
            double foodFactor = Math.max(0, player.getFoodLevel()) / 20.0;
            blood += BLOOD_REGEN_MAX_L_PER_SEC * foodFactor;
        }
        blood = clamp(blood, 0.0, MAX_BLOOD_L);
        bloodLiters.put(player.getUniqueId(), blood);
        if (blood <= 0.0 && !player.isDead()) {
            bleeding.remove(player.getUniqueId());
            player.sendActionBar(ChatColor.DARK_RED.toString() + ChatColor.BOLD + "You bled out…");
            player.setHealth(0.0);
        }
    }

    private void tickThirstSecond(Player player) {
        double t = ensureThirst(player);
        t -= PASSIVE_DRAIN_PER_SEC;
        if (player.isSprinting()) {
            t -= SPRINT_DRAIN_PER_SEC;
        }
        thirst.put(player.getUniqueId(), clamp(t, 0.0, MAX_THIRST));
    }

    /* -------------------- sidebar -------------------- */

    private void attachBoard(Player player) {
        if (boards.containsKey(player.getUniqueId())) {
            player.setScoreboard(boards.get(player.getUniqueId()));
            return;
        }
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective(
                OBJ_NAME,
                Criteria.DUMMY,
                ChatColor.AQUA.toString() + ChatColor.BOLD + "Vitals");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        // Hide the 3/2/1 score numbers on the right of the sidebar.
        obj.numberFormat(NumberFormat.blank());
        boards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
    }

    private void updateSidebar(Player player) {
        attachBoard(player);
        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null) {
            return;
        }
        Objective obj = board.getObjective(OBJ_NAME);
        if (obj == null) {
            return;
        }
        obj.numberFormat(NumberFormat.blank());
        for (String entry : board.getEntries()) {
            board.resetScores(entry);
        }

        double tempF = ensureTemp(player);
        double blood = ensureBlood(player);
        double thirstVal = ensureThirst(player);
        String deg = color(String.format(Locale.US, "%s%.1f\u00B0F", tempColor(tempF), tempF));
        String bloodLine = color(String.format(Locale.US, "%sBlood: %.1fL", bloodColor(blood), blood));
        String thirstLine = color(String.format(Locale.US, "%sThirst: %.0f%%", thirstColor(thirstVal), thirstVal));
        setLine(obj, deg + ChatColor.BLACK, 3);
        setLine(obj, bloodLine + ChatColor.DARK_BLUE, 2);
        setLine(obj, thirstLine + ChatColor.DARK_GREEN, 1);
    }

    private static String tempColor(double tempF) {
        if (tempF < DAMAGE_TEMP_F) {
            return "&c";
        }
        if (tempF < 97.5) {
            return "&6";
        }
        return "&a";
    }

    private static String bloodColor(double liters) {
        if (liters < BLOOD_CRITICAL_L) {
            return "&c";
        }
        if (liters < BLOOD_ORANGE_L) {
            return "&6";
        }
        return "&a";
    }

    private static String thirstColor(double value) {
        if (value < THIRST_CRITICAL) {
            return "&c";
        }
        if (value < THIRST_ORANGE) {
            return "&6";
        }
        return "&a";
    }

    private static void setLine(Objective obj, String entry, int score) {
        if (entry.length() > 40) {
            entry = entry.substring(0, 40);
        }
        Score line = obj.getScore(entry);
        line.setScore(score);
        line.numberFormat(NumberFormat.blank());
    }

    private static String color(String ampersand) {
        return ChatColor.translateAlternateColorCodes('&', ampersand == null ? "" : ampersand);
    }

    /* -------------------- helpers -------------------- */

    private void ensureVitals(Player player) {
        ensureTemp(player);
        ensureBlood(player);
        ensureThirst(player);
    }

    private double ensureTemp(Player player) {
        return bodyTemp.computeIfAbsent(player.getUniqueId(), id -> NORMAL_TEMP_F);
    }

    private double ensureBlood(Player player) {
        return bloodLiters.computeIfAbsent(player.getUniqueId(), id -> MAX_BLOOD_L);
    }

    private double ensureThirst(Player player) {
        return thirst.computeIfAbsent(player.getUniqueId(), id -> MAX_THIRST);
    }

    private void applyFreezeOverlay(Player player, double temp) {
        if (inPowderSnow(player)) {
            return;
        }
        if (temp >= DAMAGE_TEMP_F) {
            if (player.getFreezeTicks() > 0) {
                player.setFreezeTicks(0);
            }
            return;
        }
        double severity = (DAMAGE_TEMP_F - temp) / (DAMAGE_TEMP_F - MIN_TEMP_F);
        severity = clamp(severity, 0.0, 1.0);
        int max = Math.max(2, player.getMaxFreezeTicks());
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

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /* -------------------- events -------------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        double loss = Math.max(0.05, event.getFinalDamage() * 0.12);
        double blood = clamp(ensureBlood(player) - loss, 0.0, MAX_BLOOD_L);
        bloodLiters.put(player.getUniqueId(), blood);
        if (ThreadLocalRandom.current().nextDouble() < 0.35
                || event.getFinalDamage() >= 4.0) {
            bleeding.put(player.getUniqueId(), true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        Material type = item.getType();
        if (type != Material.POTION && type != Material.HONEY_BOTTLE && type != Material.MILK_BUCKET) {
            return;
        }
        Player player = event.getPlayer();
        double add = type == Material.HONEY_BOTTLE ? 25.0 : type == Material.MILK_BUCKET ? 15.0 : 35.0;
        thirst.put(player.getUniqueId(), clamp(ensureThirst(player) + add, 0.0, MAX_THIRST));
    }

    @EventHandler
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        org.bukkit.event.block.Action action = event.getAction();
        if (action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isBandage(hand)) {
            return;
        }
        event.setCancelled(true);
        if (!Boolean.TRUE.equals(bleeding.get(player.getUniqueId()))) {
            player.sendMessage(ChatColor.GRAY + "You're not bleeding.");
            return;
        }
        bleeding.remove(player.getUniqueId());
        hand.setAmount(hand.getAmount() - 1);
        player.sendMessage(ChatColor.GREEN + "Bleeding stopped.");
        player.sendActionBar(ChatColor.GREEN + "Bandaged");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ensureVitals(player);
        Bukkit.getScheduler().runTask(plugin, () -> {
            attachBoard(player);
            updateSidebar(player);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        boards.remove(id);
        coldDamageCd.remove(id);
        bodyTemp.remove(id);
        bloodLiters.remove(id);
        thirst.remove(id);
        bleeding.remove(id);
        event.getPlayer().setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        bodyTemp.put(player.getUniqueId(), NORMAL_TEMP_F);
        bloodLiters.put(player.getUniqueId(), MAX_BLOOD_L);
        thirst.put(player.getUniqueId(), MAX_THIRST);
        bleeding.remove(player.getUniqueId());
        coldDamageCd.remove(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            clearFreezeIfOurs(player);
            attachBoard(player);
            updateSidebar(player);
        });
    }
}
