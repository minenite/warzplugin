package net.minenite.warzplugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

/**
 * Loot restock: 600s XP-level timer, weighted chest pools, zones 1–7, chest protection.
 * XP bar fill is thirst (see {@link com.local.warz.runtime.ThirstService}); the level number is this timer.
 *
 * <p>Ported from the Paper WarZ plugin. When creating a chest, slot rows set rarity:
 * top row = common, middle = uncommon, bottom+ = rare (weighted rolls).
 */
public final class LootRestockService implements Listener {

    public static final int RESTOCK_SECONDS = 600;
    public static final int MIN_ZONE = 1;
    public static final int MAX_ZONE = 7;

    public enum Rarity {
        COMMON(10),
        UNCOMMON(4),
        RARE(1);

        public final int weight;

        Rarity(int weight) {
            this.weight = weight;
        }
    }

    /** Default rolls per zone (higher zones = scarcer drops). */
    private static final int[] DEFAULT_ITEMS_PER_ZONE = {0, 4, 4, 3, 3, 3, 2, 2};

    private final WarzPlugin plugin;
    private final File file;
    private final Map<String, LootChest> chests = new LinkedHashMap<>();
    private final Map<Integer, Zone> zones = new HashMap<>();
    private final Map<UUID, PendingChest> pendingZone = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastZone = new ConcurrentHashMap<>();
    private final int[] itemsPerZone = DEFAULT_ITEMS_PER_ZONE.clone();

    private int remaining = RESTOCK_SECONDS;
    private BukkitTask task;

    public LootRestockService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "loot-restock.yml");
        load();
    }

    public void start() {
        remaining = RESTOCK_SECONDS;
        applyTimerToAll();
        if (task != null) {
            task.cancel();
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSecond, 20L, 20L);
        plugin.getLogger().info("Loot restock timer started at " + RESTOCK_SECONDS
                + "s (" + chests.size() + " chests, " + zones.size() + " zones).");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        save();
    }

    public int remainingSeconds() {
        return remaining;
    }

    private void tickSecond() {
        remaining--;
        if (remaining <= 0) {
            forceReloot(true);
        } else {
            applyTimerToAll();
        }
    }

    /**
     * Restock all loot chests and reset the 600s timer.
     *
     * @return number of chests restocked
     */
    public int forceReloot(boolean announce) {
        int stocked = restockAll();
        remaining = RESTOCK_SECONDS;
        applyTimerToAll();
        if (announce) {
            Bukkit.broadcastMessage(ChatColor.AQUA.toString() + ChatColor.BOLD + "Chests Relooted");
        }
        return stocked;
    }

    private void applyTimerToAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyTimer(player);
        }
    }

    private void applyTimer(Player player) {
        if (player == null) {
            return;
        }
        int level = Math.max(0, remaining);
        player.setLevel(level);
        if (plugin.thirst() != null) {
            plugin.thirst().applyExpBar(player);
        }
    }

    public boolean beginCreateChest(Player player) {
        Block block = targetContainer(player);
        if (block == null
                || (block.getType() != Material.CHEST
                && block.getType() != Material.TRAPPED_CHEST
                && block.getType() != Material.BARREL)
                || !(block.getState() instanceof Container container)) {
            player.sendMessage(ChatColor.RED + "Look at a chest or barrel within 5 blocks.");
            return false;
        }

        List<LootEntry> template = snapshotPool(container.getInventory());
        if (template.isEmpty()) {
            player.sendMessage(ChatColor.RED
                    + "Put the restock items in the chest first, then run /warz createchest.");
            return false;
        }

        Location loc = block.getLocation();
        pendingZone.put(player.getUniqueId(), new PendingChest(loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), template));

        Map<Rarity, Integer> counts = countRarities(template);
        player.sendMessage(ChatColor.GREEN + "Chest template saved ("
                + ChatColor.WHITE + template.size() + " items" + ChatColor.GREEN + ").");
        player.sendMessage(color("&7Rarity from rows: &fcommon &7(top) / &euncommon &7(mid) / &crare &7(bottom+)"));
        player.sendMessage(color("&8Pool — &fC:&a" + counts.getOrDefault(Rarity.COMMON, 0)
                + " &8| &eU:" + counts.getOrDefault(Rarity.UNCOMMON, 0)
                + " &8| &cR:" + counts.getOrDefault(Rarity.RARE, 0)));
        player.sendMessage(ChatColor.YELLOW + "What zone is this chest? Type a number "
                + ChatColor.GOLD + ChatColor.BOLD + "1–7" + ChatColor.YELLOW + " in chat.");
        return true;
    }

    public boolean deleteLookingChest(Player player) {
        Block block = targetContainer(player);
        if (block == null) {
            player.sendMessage(ChatColor.RED + "Look at a loot chest to remove.");
            return false;
        }
        String key = findChestKey(block);
        if (key == null || chests.remove(key) == null) {
            player.sendMessage(ChatColor.RED + "That chest is not a WarZ loot chest.");
            return false;
        }
        save();
        player.sendMessage(ChatColor.GREEN + "Removed loot chest (now unprotected).");
        return true;
    }

    public boolean createZone(Player player, int zoneId) {
        if (zoneId < MIN_ZONE || zoneId > MAX_ZONE) {
            player.sendMessage(ChatColor.RED + "Zone must be " + MIN_ZONE + "–" + MAX_ZONE + ".");
            return false;
        }
        Plugin wePlugin = Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (wePlugin == null || !wePlugin.isEnabled()) {
            player.sendMessage(ChatColor.RED + "WorldEdit is required for /warz createzone.");
            return false;
        }
        try {
            Object session = wePlugin.getClass().getMethod("getSession", Player.class).invoke(wePlugin, player);
            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Class<?> weWorldClass = Class.forName("com.sk89q.worldedit.world.World");
            Object weWorld = adapter.getMethod("adapt", World.class).invoke(null, player.getWorld());
            Object region = session.getClass().getMethod("getSelection", weWorldClass).invoke(session, weWorld);
            Object min = region.getClass().getMethod("getMinimumPoint").invoke(region);
            Object max = region.getClass().getMethod("getMaximumPoint").invoke(region);
            int minX = ((Number) min.getClass().getMethod("x").invoke(min)).intValue();
            int maxX = ((Number) max.getClass().getMethod("x").invoke(max)).intValue();
            int minZ = ((Number) min.getClass().getMethod("z").invoke(min)).intValue();
            int maxZ = ((Number) max.getClass().getMethod("z").invoke(max)).intValue();
            if (minX > maxX) {
                int t = minX;
                minX = maxX;
                maxX = t;
            }
            if (minZ > maxZ) {
                int t = minZ;
                minZ = maxZ;
                maxZ = t;
            }
            Zone zone = new Zone(zoneId, player.getWorld().getName(), minX, maxX, minZ, maxZ);
            zones.put(zoneId, zone);
            save();
            int area = (maxX - minX + 1) * (maxZ - minZ + 1);
            player.sendMessage(ChatColor.GREEN + "Zone " + zoneId + " set ("
                    + ChatColor.YELLOW + area + " blocks 2D" + ChatColor.GREEN
                    + ") — restocks " + itemsForZone(zoneId) + " items/chest.");
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            player.sendMessage(ChatColor.RED + "WorldEdit API mismatch: " + missing.getMessage());
            return false;
        } catch (java.lang.reflect.InvocationTargetException failed) {
            Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
            if (cause.getClass().getName().contains("IncompleteRegion")) {
                player.sendMessage(ChatColor.RED + "Make a WorldEdit selection first (wand: pos1 + pos2).");
                return false;
            }
            player.sendMessage(ChatColor.RED + "WorldEdit error: " + cause.getMessage());
            plugin.getLogger().warning("createzone failed: " + cause);
            return false;
        } catch (Throwable t) {
            player.sendMessage(ChatColor.RED + "WorldEdit error: " + t.getMessage());
            plugin.getLogger().warning("createzone failed: " + t);
            return false;
        }
    }

    public void sendStatus(Player player) {
        player.sendMessage(ChatColor.GOLD + "Loot restock in "
                + ChatColor.YELLOW + remaining + "s"
                + ChatColor.GRAY + "  |  chests: " + chests.size()
                + "  zones: " + zones.size());
        int z = zoneAt(player.getLocation());
        player.sendMessage(ChatColor.AQUA + "You are in Zone " + z
                + " (restocks " + itemsForZone(z) + "/chest)"
                + ChatColor.GRAY + (z >= 2 ? zoneLootHint(z) : " — default zone"));
    }

    public List<String> listChests() {
        List<String> out = new ArrayList<>();
        for (LootChest c : chests.values()) {
            Map<Rarity, Integer> counts = countRarities(c.pool);
            out.add(c.world + " " + c.x + "," + c.y + "," + c.z
                    + " zone=" + c.zone
                    + " rolls=" + itemsForZone(c.zone)
                    + " pool=" + c.pool.size()
                    + " C/U/R=" + counts.getOrDefault(Rarity.COMMON, 0)
                    + "/" + counts.getOrDefault(Rarity.UNCOMMON, 0)
                    + "/" + counts.getOrDefault(Rarity.RARE, 0));
        }
        return out;
    }

    private int itemsForZone(int zone) {
        if (zone < MIN_ZONE || zone > MAX_ZONE) {
            return 3;
        }
        return Math.max(1, itemsPerZone[zone]);
    }

    private int restockAll() {
        int ok = 0;
        for (LootChest chest : new ArrayList<>(chests.values())) {
            if (restockOne(chest)) {
                ok++;
            }
        }
        return ok;
    }

    private boolean restockOne(LootChest data) {
        World world = Bukkit.getWorld(data.world);
        if (world == null) {
            return false;
        }
        Block block = world.getBlockAt(data.x, data.y, data.z);
        if (!(block.getState() instanceof Container container)) {
            return false;
        }
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST
                && block.getType() != Material.BARREL) {
            return false;
        }
        Inventory inv = container.getInventory();
        inv.clear();

        List<LootEntry> pool = new ArrayList<>();
        for (LootEntry e : data.pool) {
            if (e != null && e.stack != null && !e.stack.getType().isAir() && e.stack.getAmount() > 0) {
                pool.add(e);
            }
        }
        if (pool.isEmpty()) {
            return false;
        }

        int n = Math.min(itemsForZone(data.zone), pool.size());
        List<ItemStack> picked = weightedPickDistinct(pool, n);
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) {
            slots.add(i);
        }
        Collections.shuffle(slots, ThreadLocalRandom.current());
        for (int i = 0; i < picked.size() && i < slots.size(); i++) {
            inv.setItem(slots.get(i), picked.get(i));
        }
        return true;
    }

    private static List<ItemStack> weightedPickDistinct(List<LootEntry> pool, int count) {
        List<LootEntry> remaining = new ArrayList<>(pool);
        List<ItemStack> out = new ArrayList<>(count);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < count && !remaining.isEmpty(); i++) {
            int total = 0;
            for (LootEntry e : remaining) {
                total += Math.max(1, e.weight);
            }
            int roll = rng.nextInt(total);
            int acc = 0;
            LootEntry chosen = remaining.get(remaining.size() - 1);
            for (LootEntry e : remaining) {
                acc += Math.max(1, e.weight);
                if (roll < acc) {
                    chosen = e;
                    break;
                }
            }
            remaining.remove(chosen);
            out.add(chosen.stack.clone());
        }
        return out;
    }

    private static List<LootEntry> snapshotPool(Inventory inv) {
        List<LootEntry> out = new ArrayList<>();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            Rarity rarity = rarityForSlot(i);
            out.add(new LootEntry(stack.clone(), rarity.weight, rarity));
        }
        return out;
    }

    static Rarity rarityForSlot(int slot) {
        int row = slot / 9;
        if (row <= 0) {
            return Rarity.COMMON;
        }
        if (row == 1) {
            return Rarity.UNCOMMON;
        }
        return Rarity.RARE;
    }

    private static Map<Rarity, Integer> countRarities(List<LootEntry> pool) {
        Map<Rarity, Integer> map = new EnumMap<>(Rarity.class);
        for (LootEntry e : pool) {
            map.merge(e.rarity, 1, Integer::sum);
        }
        return map;
    }

    private static Block targetContainer(Player player) {
        RayTraceResult hit = player.rayTraceBlocks(5.0);
        if (hit == null || hit.getHitBlock() == null) {
            return null;
        }
        return hit.getHitBlock();
    }

    private static String keyOf(String world, int x, int y, int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    private static String keyOf(Block block) {
        return keyOf(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public boolean isLootChest(Block block) {
        return findChestKey(block) != null;
    }

    private String findChestKey(Block block) {
        if (block == null) {
            return null;
        }
        String direct = keyOf(block);
        if (chests.containsKey(direct)) {
            return direct;
        }
        if (!(block.getState() instanceof Chest chest)) {
            return null;
        }
        InventoryHolder holder = chest.getInventory().getHolder();
        if (holder instanceof DoubleChest dbl) {
            InventoryHolder left = dbl.getLeftSide();
            InventoryHolder right = dbl.getRightSide();
            if (left instanceof Chest lc) {
                String k = keyOf(lc.getBlock());
                if (chests.containsKey(k)) {
                    return k;
                }
            }
            if (right instanceof Chest rc) {
                String k = keyOf(rc.getBlock());
                if (chests.containsKey(k)) {
                    return k;
                }
            }
        }
        return null;
    }

    private int zoneAt(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return 1;
        }
        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        for (Zone zone : zones.values()) {
            if (zone.contains(world, x, z)) {
                return zone.id;
            }
        }
        return 1;
    }

    private String zoneLootHint(int zoneId) {
        Set<String> names = new LinkedHashSet<>();
        for (LootChest c : chests.values()) {
            if (c.zone != zoneId) {
                continue;
            }
            for (LootEntry e : c.pool) {
                ItemStack stack = e.stack;
                if (stack == null || stack.getType().isAir()) {
                    continue;
                }
                String name;
                ItemMeta meta = stack.getItemMeta();
                if (meta != null && meta.hasDisplayName()) {
                    name = ChatColor.stripColor(meta.getDisplayName());
                } else {
                    name = pretty(stack.getType());
                }
                names.add(name);
                if (names.size() >= 8) {
                    break;
                }
            }
            if (names.size() >= 8) {
                break;
            }
        }
        if (names.isEmpty()) {
            return " — no loot chests tagged for this zone yet.";
        }
        return " — possible loot: " + String.join(", ", names)
                + (names.size() >= 8 ? "…" : "");
    }

    private static String pretty(Material mat) {
        String n = mat.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private void finishPending(Player player, int zoneId) {
        PendingChest pending = pendingZone.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }
        String key = keyOf(pending.world, pending.x, pending.y, pending.z);
        LootChest chest = new LootChest(pending.world, pending.x, pending.y, pending.z, zoneId, pending.pool);
        chests.put(key, chest);
        save();
        int rolls = itemsForZone(zoneId);
        player.sendMessage(ChatColor.GREEN + "Loot chest created in Zone " + zoneId
                + " (" + pending.pool.size() + " weighted pool, restocks "
                + rolls + " items). Protected from break/explode/hoppers.");
        restockOne(chest);
        player.sendMessage(ChatColor.GRAY + "First random draw applied.");
    }

    /* -------------------- protection -------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isLootChest(block)) {
            return;
        }
        Player player = event.getPlayer();
        if (plugin.mayManageLoot(player) && player.isSneaking()) {
            String key = findChestKey(block);
            if (key != null) {
                chests.remove(key);
                save();
            }
            player.sendMessage(ChatColor.YELLOW + "Loot chest unregistered and broken.");
            return;
        }
        event.setCancelled(true);
        player.sendMessage(ChatColor.RED
                + "Loot chests are protected. Admins: /warz delchest or sneak-break.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isLootChest);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isLootChest);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (isLootChest(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (isLootChest(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (isLootChest(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (holderIsLootChest(event.getSource().getHolder())
                || holderIsLootChest(event.getDestination().getHolder())) {
            event.setCancelled(true);
        }
    }

    private boolean holderIsLootChest(InventoryHolder holder) {
        if (holder == null) {
            return false;
        }
        if (holder instanceof DoubleChest dbl) {
            return holderIsLootChest(dbl.getLeftSide()) || holderIsLootChest(dbl.getRightSide());
        }
        if (holder instanceof Container container) {
            return isLootChest(container.getBlock());
        }
        return false;
    }

    /* -------------------- other events -------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player) {
            return;
        }
        event.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExpOrbSpawn(org.bukkit.event.entity.EntitySpawnEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.ExperienceOrb) {
            event.setCancelled(true);
            event.getEntity().remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExpChange(PlayerExpChangeEvent event) {
        event.setAmount(0);
        if (plugin.thirst() != null) {
            plugin.thirst().applyExpBar(event.getPlayer());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> applyTimer(event.getPlayer()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()
                && from.getWorld() == to.getWorld())) {
            return;
        }
        Player player = event.getPlayer();
        int zone = zoneAt(to);
        Integer prev = lastZone.get(player.getUniqueId());
        if (prev != null && prev == zone) {
            return;
        }
        lastZone.put(player.getUniqueId(), zone);
        if (zone >= 2) {
            player.sendMessage(color("&7&l&oZone: &6" + zone));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PendingChest pending = pendingZone.get(player.getUniqueId());
        if (pending == null) {
            return;
        }
        String plain = event.getMessage().trim();
        event.setCancelled(true);
        Integer zoneId = parseZone(plain);
        if (zoneId == null) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    player.sendMessage(ChatColor.RED + "Type a zone number from 1 to 7 (or 'cancel')."));
            return;
        }
        if (zoneId == -1) {
            pendingZone.remove(player.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () ->
                    player.sendMessage(ChatColor.YELLOW + "Chest create cancelled."));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> finishPending(player, zoneId));
    }

    private static Integer parseZone(String plain) {
        if (plain.equalsIgnoreCase("cancel") || plain.equalsIgnoreCase("c")) {
            return -1;
        }
        try {
            int z = Integer.parseInt(plain);
            if (z < MIN_ZONE || z > MAX_ZONE) {
                return null;
            }
            return z;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }

    /* -------------------- persistence -------------------- */

    private void load() {
        chests.clear();
        zones.clear();
        System.arraycopy(DEFAULT_ITEMS_PER_ZONE, 0, itemsPerZone, 0, DEFAULT_ITEMS_PER_ZONE.length);
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection settings = yaml.getConfigurationSection("settings.items-per-zone");
        if (settings != null) {
            for (int z = MIN_ZONE; z <= MAX_ZONE; z++) {
                if (settings.contains(String.valueOf(z))) {
                    itemsPerZone[z] = Math.max(1, settings.getInt(String.valueOf(z)));
                }
            }
        }
        ConfigurationSection chestSec = yaml.getConfigurationSection("chests");
        if (chestSec != null) {
            for (String key : chestSec.getKeys(false)) {
                ConfigurationSection sec = chestSec.getConfigurationSection(key);
                if (sec == null) {
                    continue;
                }
                String world = sec.getString("world");
                int x = sec.getInt("x");
                int y = sec.getInt("y");
                int z = sec.getInt("z");
                int zone = sec.getInt("zone", 1);
                List<LootEntry> pool = loadPool(sec);
                if (world == null || pool.isEmpty()) {
                    continue;
                }
                chests.put(keyOf(world, x, y, z), new LootChest(world, x, y, z, zone, pool));
            }
        }
        ConfigurationSection zoneSec = yaml.getConfigurationSection("zones");
        if (zoneSec != null) {
            for (String key : zoneSec.getKeys(false)) {
                ConfigurationSection sec = zoneSec.getConfigurationSection(key);
                if (sec == null) {
                    continue;
                }
                int id = sec.getInt("id", -1);
                if (id < MIN_ZONE || id > MAX_ZONE) {
                    continue;
                }
                zones.put(id, new Zone(id, sec.getString("world", "world"),
                        sec.getInt("minX"), sec.getInt("maxX"),
                        sec.getInt("minZ"), sec.getInt("maxZ")));
            }
        }
        plugin.getLogger().info("Loaded " + chests.size() + " loot chests, " + zones.size() + " zones.");
        if (!chests.isEmpty() || !zones.isEmpty()) {
            save();
        }
    }

    private static List<LootEntry> loadPool(ConfigurationSection sec) {
        List<LootEntry> pool = new ArrayList<>();
        for (int i = 0; i < 54; i++) {
            String base = "pool." + i;
            if (!sec.contains(base + ".item")) {
                break;
            }
            ItemStack stack = sec.getItemStack(base + ".item");
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            String rarityName = sec.getString(base + ".rarity", "COMMON");
            Rarity rarity;
            try {
                rarity = Rarity.valueOf(rarityName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                rarity = Rarity.COMMON;
            }
            int weight = sec.getInt(base + ".weight", rarity.weight);
            pool.add(new LootEntry(stack.clone(), Math.max(1, weight), rarity));
        }
        if (!pool.isEmpty()) {
            return pool;
        }
        List<?> raw = sec.getList("items");
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof ItemStack stack && !stack.getType().isAir()) {
                    pool.add(new LootEntry(stack.clone(), Rarity.COMMON.weight, Rarity.COMMON));
                }
            }
        }
        return pool;
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (int z = MIN_ZONE; z <= MAX_ZONE; z++) {
            yaml.set("settings.items-per-zone." + z, itemsPerZone[z]);
        }
        for (LootChest c : chests.values()) {
            String path = "chests." + keyOf(c.world, c.x, c.y, c.z);
            yaml.set(path + ".world", c.world);
            yaml.set(path + ".x", c.x);
            yaml.set(path + ".y", c.y);
            yaml.set(path + ".z", c.z);
            yaml.set(path + ".zone", c.zone);
            int i = 0;
            for (LootEntry e : c.pool) {
                String base = path + ".pool." + i;
                yaml.set(base + ".rarity", e.rarity.name());
                yaml.set(base + ".weight", e.weight);
                yaml.set(base + ".item", e.stack.clone());
                i++;
            }
        }
        for (Zone z : zones.values()) {
            String path = "zones." + z.id;
            yaml.set(path + ".id", z.id);
            yaml.set(path + ".world", z.world);
            yaml.set(path + ".minX", z.minX);
            yaml.set(path + ".maxX", z.maxX);
            yaml.set(path + ".minZ", z.minZ);
            yaml.set(path + ".maxZ", z.maxZ);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save loot-restock.yml: " + e.getMessage());
        }
    }

    private record PendingChest(String world, int x, int y, int z, List<LootEntry> pool) {
    }

    private record LootChest(String world, int x, int y, int z, int zone, List<LootEntry> pool) {
    }

    private record LootEntry(ItemStack stack, int weight, Rarity rarity) {
    }

    private record Zone(int id, String world, int minX, int maxX, int minZ, int maxZ) {
        boolean contains(String w, int x, int z) {
            return world.equals(w) && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }
}
