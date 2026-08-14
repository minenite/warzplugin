package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Placeable chain-link fence (iron bars). Only these marked bars are climbable —
 * vanilla / creative iron bars stay non-climbable.
 */
public final class ChainlinkService implements Listener {
    public static final byte OP_UPSERT = 1;
    public static final byte OP_REMOVE = 2;
    public static final byte OP_FULL = 3;

    private static final double CLIMB_UP = 0.22;
    private static final double CLIMB_HOLD = 0.0;
    private static final double CLIMB_DOWN = -0.18;

    private final WarzPlugin plugin;
    private final File file;
    private final Set<String> bars = ConcurrentHashMap.newKeySet();
    private final Set<String> suppressVanillaDrop = ConcurrentHashMap.newKeySet();
    private BukkitTask task;

    public ChainlinkService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "chainlink.yml");
        load();
    }

    public void start() {
        if (task != null) {
            task.cancel();
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickClimb, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        save();
    }

    public boolean isChainlinkBlock(Block block) {
        return block != null && block.getType() == Material.IRON_BARS && bars.contains(key(block));
    }

    /** Remove a marked panel (break / crater). @return true if it was chainlink. */
    public boolean unmark(Block block) {
        if (block == null) {
            return false;
        }
        String k = key(block);
        if (!bars.remove(k)) {
            return false;
        }
        save();
        broadcast(OP_REMOVE, k);
        return true;
    }

    /** Drop chainlink as merged stacks so broken panels don't scatter as singles. */
    public void dropStacked(Location loc, int amount) {
        if (loc == null || loc.getWorld() == null || amount <= 0) {
            return;
        }
        int left = amount;
        for (org.bukkit.entity.Entity entity : loc.getWorld().getNearbyEntities(loc, 2.5, 2.5, 2.5)) {
            if (!(entity instanceof Item item)) {
                continue;
            }
            ItemStack stack = item.getItemStack();
            if (stack == null || !plugin.items().isChainlink(stack)) {
                continue;
            }
            int max = Math.max(1, stack.getMaxStackSize());
            int space = max - stack.getAmount();
            if (space <= 0) {
                continue;
            }
            int take = Math.min(space, left);
            stack.setAmount(stack.getAmount() + take);
            item.setItemStack(stack);
            left -= take;
            if (left <= 0) {
                return;
            }
        }
        while (left > 0) {
            int n = Math.min(64, left);
            Item dropped = loc.getWorld().dropItem(loc.clone(), plugin.items().createChainlink(n));
            dropped.setPickupDelay(10);
            left -= n;
        }
    }

    private void tickClimb() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR || player.isDead() || player.isFlying()) {
                continue;
            }
            if (!touchingChainlink(player)) {
                continue;
            }
            var input = player.getCurrentInput();
            boolean jump = input != null && input.isJump();
            boolean sneak = player.isSneaking() || (input != null && input.isSneak());
            // Walking on the ground next to the fence must feel normal — only
            // engage climb physics when jumping up, sneaking down, or already airborne.
            if (player.isOnGround() && !jump) {
                continue;
            }
            Vector vel = player.getVelocity();
            if (jump) {
                vel.setY(Math.max(vel.getY(), CLIMB_UP));
            } else if (sneak) {
                vel.setY(CLIMB_DOWN);
            } else if (!player.isOnGround()) {
                // Cling in air against the fence — do not touch X/Z
                if (vel.getY() < CLIMB_HOLD) {
                    vel.setY(CLIMB_HOLD);
                }
            } else {
                continue;
            }
            player.setVelocity(vel);
            player.setFallDistance(0f);
        }
    }

    private boolean touchingChainlink(Player player) {
        Location loc = player.getLocation();
        // Feet / body / eyes, plus horizontal neighbors (hugging the fence)
        Block[] checks = {
                loc.getBlock(),
                loc.clone().add(0, 0.9, 0).getBlock(),
                loc.clone().add(0, 1.6, 0).getBlock(),
                loc.getBlock().getRelative(BlockFace.NORTH),
                loc.getBlock().getRelative(BlockFace.SOUTH),
                loc.getBlock().getRelative(BlockFace.EAST),
                loc.getBlock().getRelative(BlockFace.WEST),
                loc.clone().add(0, 1, 0).getBlock().getRelative(BlockFace.NORTH),
                loc.clone().add(0, 1, 0).getBlock().getRelative(BlockFace.SOUTH),
                loc.clone().add(0, 1, 0).getBlock().getRelative(BlockFace.EAST),
                loc.clone().add(0, 1, 0).getBlock().getRelative(BlockFace.WEST),
        };
        for (Block b : checks) {
            if (isChainlinkBlock(b)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack hand = event.getItemInHand();
        if (!plugin.items().isChainlink(hand)) {
            return;
        }
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.IRON_BARS) {
            return;
        }
        String k = key(block);
        bars.add(k);
        save();
        broadcast(OP_UPSERT, k);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isChainlinkBlock(block)) {
            return;
        }
        String k = key(block);
        bars.remove(k);
        suppressVanillaDrop.add(k);
        save();
        broadcast(OP_REMOVE, k);
        event.setDropItems(false);
        event.setExpToDrop(0);
        dropStacked(block.getLocation().add(0.5, 0.2, 0.5), 1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> suppressVanillaDrop.remove(k), 2L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent event) {
        if (!suppressVanillaDrop.contains(key(event.getBlock()))) {
            return;
        }
        event.getItems().removeIf(item -> {
            ItemStack stack = item.getItemStack();
            return stack != null && stack.getType() == Material.IRON_BARS && !plugin.items().isChainlink(stack);
        });
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
            String k = key(block);
            if (bars.remove(k)) {
                changed = true;
                broadcast(OP_REMOVE, k);
            }
        }
        if (changed) {
            save();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (isChainlinkBlock(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (isChainlinkBlock(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> syncFull(event.getPlayer()), 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // no per-player state
    }

    /** Called when a companion hello arrives so climb prediction works. */
    public void syncFull(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        CompanionClients companions = plugin.companions();
        if (companions == null || !companions.hasCompanion(player)) {
            // Still try — client ignores if no receiver; hello may arrive same tick
        }
        byte[] payload = encodeFull();
        if (payload != null) {
            player.sendPluginMessage(plugin, CompanionClients.CHANNEL_CHAINLINK, payload);
        }
    }

    private void broadcast(byte op, String key) {
        byte[] payload = encodeOne(op, key);
        if (payload == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendPluginMessage(plugin, CompanionClients.CHANNEL_CHAINLINK, payload);
        }
    }

    private byte[] encodeOne(byte op, String key) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(CompanionClients.PROTOCOL);
            out.writeByte(op);
            writeUtf(out, key);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] encodeFull() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 + bars.size() * 24);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(CompanionClients.PROTOCOL);
            out.writeByte(OP_FULL);
            Set<String> snap = new HashSet<>(bars);
            out.writeInt(snap.size());
            for (String k : snap) {
                writeUtf(out, k);
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeUtf(DataOutputStream out, String s) throws IOException {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(raw.length);
        out.write(raw);
    }

    private static String key(Block block) {
        return key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private static String key(String world, int x, int y, int z) {
        return world.toLowerCase(Locale.ROOT) + ";" + x + ";" + y + ";" + z;
    }

    private void load() {
        bars.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String entry : yaml.getStringList("blocks")) {
            if (entry != null && !entry.isBlank()) {
                bars.add(entry.trim().toLowerCase(Locale.ROOT));
            }
        }
        Iterator<String> it = bars.iterator();
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
                if (block.getType() != Material.IRON_BARS) {
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
        yaml.set("blocks", List.copyOf(new HashSet<>(bars)));
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save chainlink.yml: " + e.getMessage());
        }
    }
}
