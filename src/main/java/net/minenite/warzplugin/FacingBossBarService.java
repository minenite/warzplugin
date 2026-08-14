package net.minenite.warzplugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.scheduler.BukkitTask;

/**
 * Per-player boss-bar compass tape. Only shown while the player has a compass
 * in their inventory. Heading marker stays centered; the tape scrolls under it.
 *
 * <pre>
 * |  :  :  N  |  :  :  NE  |  :  :  E  |
 *                    ▲
 * </pre>
 */
public final class FacingBossBarService implements Listener {

    private static final String[] DIRS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final String REVOLUTION;
    private static final int CHARS_PER_REV;
    private static final int LABEL_CENTER;
    private static final int WINDOW = 41;

    static {
        StringBuilder loop = new StringBuilder();
        for (String dir : DIRS) {
            loop.append("|  :  :  ").append(String.format("%-2s", dir)).append("  ");
        }
        REVOLUTION = loop.toString();
        CHARS_PER_REV = REVOLUTION.length();
        LABEL_CENTER = ("|  :  :  ".length()) + 1;
    }

    private final WarzPlugin plugin;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, String> lastTitle = new HashMap<>();
    private BukkitTask task;
    private final boolean enabled;

    public FacingBossBarService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("facing-boss-bar.enabled", true);
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void start() {
        if (!this.enabled) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            attach(player);
        }
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        for (BossBar bar : this.bars.values()) {
            bar.removeAll();
        }
        this.bars.clear();
        this.lastTitle.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!this.enabled) {
            return;
        }
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (event.getPlayer().isOnline()) {
                attach(event.getPlayer());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        detach(event.getPlayer().getUniqueId());
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            BossBar bar = this.bars.get(player.getUniqueId());
            if (bar == null) {
                attach(player);
                bar = this.bars.get(player.getUniqueId());
                if (bar == null) {
                    continue;
                }
            }
            boolean show = hasCompass(player);
            bar.setVisible(show);
            if (!show) {
                continue;
            }
            retargetVanillaCompasses(player);
            retargetVanillaCompasses(player);
            String title = tapeTitle(player.getLocation().getYaw());
            if (title.equals(this.lastTitle.get(player.getUniqueId()))) {
                continue;
            }
            this.lastTitle.put(player.getUniqueId(), title);
            bar.setTitle(title);
            bar.setColor(BarColor.WHITE);
            bar.setProgress(1.0);
        }
    }

    private void attach(Player player) {
        UUID id = player.getUniqueId();
        if (this.bars.containsKey(id)) {
            return;
        }
        String title = tapeTitle(player.getLocation().getYaw());
        BossBar bar = Bukkit.createBossBar(title, BarColor.WHITE, BarStyle.SOLID);
        bar.setProgress(1.0);
        boolean show = hasCompass(player);
        bar.setVisible(show);
        bar.addPlayer(player);
        this.bars.put(id, bar);
        this.lastTitle.put(id, title);
    }

    private void detach(UUID id) {
        BossBar bar = this.bars.remove(id);
        this.lastTitle.remove(id);
        if (bar != null) {
            bar.removeAll();
        }
    }

    /** Plain compass; the client forces the needle to true north. */
    static ItemStack northCompass(World world) {
        ItemStack compass = new ItemStack(Material.COMPASS);
        if (compass.getItemMeta() instanceof CompassMeta meta) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&fCompass"));
            if (meta.hasLodestone()) {
                meta.clearLodestone();
            }
            compass.setItemMeta(meta);
        }
        return compass;
    }

    /**
     * Strip lodestone trackers left from the failed far-north hack so the
     * needle is a normal compass (client points it north).
     */
    static void retargetVanillaCompasses(Player player) {
        if (player == null) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        boolean dirty = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (clearLodestone(stack)) {
                contents[i] = stack;
                dirty = true;
            }
        }
        if (dirty) {
            inv.setContents(contents);
        }
    }

    static boolean pointTrueNorth(ItemStack stack, World world) {
        return clearLodestone(stack);
    }

    private static boolean clearLodestone(ItemStack stack) {
        if (stack == null || stack.getType() != Material.COMPASS) {
            return false;
        }
        if (!(stack.getItemMeta() instanceof CompassMeta meta)) {
            return false;
        }
        if (!meta.isLodestoneCompass()) {
            return false;
        }
        meta.clearLodestone();
        stack.setItemMeta(meta);
        return true;
    }

    static boolean hasCompass(Player player) {
        if (player == null) {
            return false;
        }
        PlayerInventory inv = player.getInventory();
        if (inv.contains(Material.COMPASS)) {
            return true;
        }
        ItemStack off = inv.getItemInOffHand();
        return off != null && off.getType() == Material.COMPASS;
    }

    /**
     * Minecraft yaw: 0° south, 90° west, 180° north, 270° east.
     * Compass heading 0° = north.
     */
    static String tapeTitle(float yaw) {
        float heading = yaw + 180.0f;
        heading %= 360.0f;
        if (heading < 0.0f) {
            heading += 360.0f;
        }
        int center = LABEL_CENTER + Math.round((heading / 360.0f) * CHARS_PER_REV);
        int half = WINDOW / 2;
        String tape = REVOLUTION + REVOLUTION + REVOLUTION;
        int from = CHARS_PER_REV + center - half;
        int to = from + WINDOW;
        if (from < 0 || to > tape.length()) {
            from = Math.max(0, from);
            to = Math.min(tape.length(), from + WINDOW);
        }
        String slice = tape.substring(from, to);
        int mid = slice.length() / 2;
        String left = slice.substring(0, mid);
        String right = slice.substring(Math.min(slice.length(), mid + 1));
        return ChatColor.DARK_GRAY + left
                + ChatColor.WHITE + ChatColor.BOLD + "▲"
                + ChatColor.DARK_GRAY + right;
    }
}
