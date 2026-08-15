package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Placeable razor wire (cobweb): slows everyone, damages players only while they move.
 * Reclaimed with shears / Wire Cutters (right-click); left-click does nothing.
 * Cut sound matches ShearsBreakWebs: ENTITY_ITEM_BREAK @ volume 10, pitch 1.
 */
public final class RazorWireService implements Listener {
    private static final Set<EntityType> ZOMBIE_TYPES = EnumSet.of(
            EntityType.ZOMBIE,
            EntityType.HUSK,
            EntityType.ZOMBIE_VILLAGER,
            EntityType.ZOMBIFIED_PIGLIN,
            EntityType.DROWNED,
            EntityType.PARCHED
    );

    private static final double MOVE_THRESHOLD_SQ = 0.0004; // ~0.02 blocks
    private static final double PLAYER_DAMAGE = 1.0;
    private static final int DAMAGE_INTERVAL_TICKS = 10; // 0.5s while moving
    private static final int ZOMBIE_SLOW_TICKS = 40;
    private static final int ZOMBIE_SLOW_AMP = 1; // Slowness II

    private final WarzPlugin plugin;
    private final File file;
    private final Set<String> wires = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> lastLoc = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> damageCooldown = new ConcurrentHashMap<>();
    private BukkitTask task;
    private int tickCounter;

    public RazorWireService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "razor-wire.yml");
        load();
    }

    public void start() {
        if (task != null) {
            task.cancel();
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        save();
        lastLoc.clear();
        damageCooldown.clear();
    }

    public boolean isRazorWireBlock(Block block) {
        return block != null && block.getType() == Material.COBWEB && wires.contains(key(block));
    }

    private void tick() {
        // Players: damage only while moving inside razor wire
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
                lastLoc.remove(player.getUniqueId());
                damageCooldown.remove(player.getUniqueId());
                continue;
            }
            if (player.isDead()) {
                continue;
            }
            if (!occupiesRazorWire(player)) {
                lastLoc.remove(player.getUniqueId());
                damageCooldown.remove(player.getUniqueId());
                continue;
            }

            Location now = player.getLocation();
            Location prev = lastLoc.put(player.getUniqueId(), now.clone());
            boolean moving = false;
            if (prev != null && prev.getWorld() != null && prev.getWorld().equals(now.getWorld())) {
                moving = prev.distanceSquared(now) > MOVE_THRESHOLD_SQ;
            }

            int cd = damageCooldown.getOrDefault(player.getUniqueId(), 0);
            if (cd > 0) {
                damageCooldown.put(player.getUniqueId(), cd - 1);
            }
            if (moving && cd <= 0) {
                player.damage(PLAYER_DAMAGE);
                player.sendActionBar(ItemFactory.colorize("&cRazor wire!"));
                damageCooldown.put(player.getUniqueId(), DAMAGE_INTERVAL_TICKS);
            }
        }

        // Zombies: extra slowness while in razor wire (cobweb already slows; reinforce)
        tickCounter++;
        if (tickCounter % 10 == 0 && !wires.isEmpty()) {
            for (String entry : wires) {
                Block block = resolve(entry);
                if (block == null || block.getType() != Material.COBWEB) {
                    continue;
                }
                Location center = block.getLocation().add(0.5, 0.5, 0.5);
                for (Entity entity : block.getWorld().getNearbyEntities(center, 1.2, 1.5, 1.2)) {
                    if (!(entity instanceof LivingEntity living) || !ZOMBIE_TYPES.contains(living.getType())) {
                        continue;
                    }
                    if (occupiesRazorWire(living)) {
                        living.addPotionEffect(new PotionEffect(
                                PotionEffectType.SLOWNESS, ZOMBIE_SLOW_TICKS, ZOMBIE_SLOW_AMP, false, false, true));
                    }
                }
            }
        }
    }

    private Block resolve(String entry) {
        String[] p = entry.split(";");
        if (p.length != 4) {
            return null;
        }
        World world = Bukkit.getWorld(p[0]);
        if (world == null) {
            return null;
        }
        try {
            return world.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean occupiesRazorWire(Entity entity) {
        Location loc = entity.getLocation();
        Block feet = loc.getBlock();
        if (isRazorWireBlock(feet)) {
            return true;
        }
        Block body = loc.clone().add(0, 1, 0).getBlock();
        return isRazorWireBlock(body);
    }

    /* -------------------- place / break / cut -------------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack hand = event.getItemInHand();
        if (!plugin.items().isRazorWire(hand)) {
            return;
        }
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.COBWEB) {
            return;
        }
        wires.add(key(block));
        save();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isRazorWireBlock(event.getBlock())) {
            return;
        }
        // Left-click / mining does nothing — only shears right-click reclaim
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !isRazorWireBlock(block)) {
            return;
        }

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack hand = event.getItem();
        if (hand == null || hand.getType() != Material.SHEARS) {
            return;
        }

        event.setCancelled(true);
        reclaim(event.getPlayer(), block);
    }

    private void reclaim(Player player, Block block) {
        if (!isRazorWireBlock(block)) {
            return;
        }
        wires.remove(key(block));
        block.setType(Material.AIR);
        save();

        // ShearsBreakWebs: ITEM_BREAK → ENTITY_ITEM_BREAK, volume 10, pitch 1
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 10.0f, 1.0f);

        // addItem merges on vanilla similarity, so a reclaimed coil opened a new
        // slot next to wire that differed in any meta detail - anything placed
        // before razor wire had a model, for one. addItemMerging compares by what
        // the item *is*, which is what makes reclaiming stack onto what you hold.
        ItemStack wire = plugin.items().createRazorWire(1);
        ItemStack leftover = plugin.items().addItemMerging(player.getInventory(), wire);
        if (leftover != null && leftover.getAmount() > 0) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.2, 0.5), leftover);
        }
        player.updateInventory();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        removeExploded(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        removeExploded(event.blockList());
    }

    private void removeExploded(List<Block> blocks) {
        boolean changed = false;
        for (Block block : blocks) {
            if (wires.remove(key(block))) {
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (isRazorWireBlock(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (isRazorWireBlock(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastLoc.remove(id);
        damageCooldown.remove(id);
    }

    /* -------------------- persistence -------------------- */

    private static String key(Block block) {
        return key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private static String key(String world, int x, int y, int z) {
        return world.toLowerCase(Locale.ROOT) + ";" + x + ";" + y + ";" + z;
    }

    private void load() {
        wires.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> list = yaml.getStringList("blocks");
        for (String entry : list) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            wires.add(entry.trim().toLowerCase(Locale.ROOT));
        }
        // Drop stale entries whose block is no longer cobweb
        Iterator<String> it = wires.iterator();
        boolean changed = false;
        while (it.hasNext()) {
            String entry = it.next();
            String[] p = entry.split(";");
            if (p.length != 4) {
                it.remove();
                changed = true;
                continue;
            }
            World world = Bukkit.getWorld(p[0]);
            if (world == null) {
                continue;
            }
            try {
                Block block = world.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                if (block.getType() != Material.COBWEB) {
                    it.remove();
                    changed = true;
                }
            } catch (NumberFormatException ex) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("blocks", List.copyOf(new HashSet<>(wires)));
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save razor-wire.yml: " + e.getMessage());
        }
    }
}
