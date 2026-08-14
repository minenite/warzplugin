package net.minenite.warzplugin;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.scheduler.BukkitTask;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Non-builder block place/break is session-only: originals are remembered and
 * restored on restart (and on clean disable before the world saves).
 *
 * <p>Permanent builds require the network display tag {@code BUILDER} from
 * {@code /tag builder} (ServerPlugin ranks.json) — not vanilla entity tags,
 * and not op status.
 */
public final class TransientBlocksService implements Listener {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final TypeToken<Map<String, String>> MAP_TYPE = new TypeToken<>() {
    };

    private final WarzPlugin plugin;
    private final Path storeFile;
    /** block key → original BlockData as string (first change only). */
    private final ConcurrentHashMap<String, String> originals = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private volatile boolean dirty;
    private BukkitTask saveTask;

    public TransientBlocksService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.storeFile = plugin.getDataFolder().toPath().resolve("transient-blocks.json");
        reload();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("transient-blocks.enabled", true);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Permanent builds only for staff who are <em>showing</em> as BUILDER:
     * held rank must be SMOD or higher, and {@code /tag builder} must be active.
     * A player whose real rank is just BUILDER never gets this, even though they
     * naturally display as BUILDER.
     */
    public boolean isBuilder(Player player) {
        if (player == null) {
            return false;
        }
        // ServerPlugin writes ranks.json; pick up /tag changes immediately.
        RankStore ranks = plugin.ranks();
        ranks.reload();
        if (!ranks.rankOf(player.getUniqueId()).atLeast(Rank.SMOD)) {
            return false;
        }
        return ranks.displayedRankOf(player.getUniqueId()) == Rank.BUILDER;
    }

    /** Load leftover journal (crash recovery) and restore before players join. */
    public void restoreFromDisk() {
        if (!enabled) {
            return;
        }
        Map<String, String> loaded = readFile();
        if (loaded.isEmpty()) {
            return;
        }
        originals.clear();
        originals.putAll(loaded);
        int restored = restoreAll();
        originals.clear();
        dirty = true;
        saveNow();
        plugin.getLogger().info("Transient blocks: restored " + restored
                + " non-builder change(s) from previous session.");
    }

    public void start() {
        if (!enabled) {
            return;
        }
        // Periodic flush so a hard crash still has most of the journal.
        this.saveTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::saveIfDirty, 100L, 100L);
    }

    public void stop() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (!enabled) {
            return;
        }
        // Revert before the world is saved on shutdown so the map stays pristine
        // except for builder (and already-committed) edits.
        int restored = restoreAll();
        originals.clear();
        dirty = true;
        saveNow();
        if (restored > 0) {
            plugin.getLogger().info("Transient blocks: reverted " + restored
                    + " non-builder change(s) before save.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlock();
        if (isBuilder(player)) {
            commit(block);
            return;
        }
        rememberOriginal(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();
        if (isBuilder(player)) {
            commit(event.getBlockPlaced());
            if (event instanceof BlockMultiPlaceEvent multi) {
                for (BlockState replaced : multi.getReplacedBlockStates()) {
                    commit(replaced.getBlock());
                }
            }
            return;
        }
        rememberOriginal(event.getBlockReplacedState());
        if (event instanceof BlockMultiPlaceEvent multi) {
            for (BlockState replaced : multi.getReplacedBlockStates()) {
                rememberOriginal(replaced);
            }
        }
    }

    private void rememberOriginal(Block block) {
        if (block == null) {
            return;
        }
        rememberOriginal(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                block.getBlockData().getAsString());
    }

    private void rememberOriginal(BlockState state) {
        if (state == null) {
            return;
        }
        rememberOriginal(state.getWorld().getName(), state.getX(), state.getY(), state.getZ(),
                state.getBlockData().getAsString());
    }

    private void rememberOriginal(String world, int x, int y, int z, String data) {
        String key = key(world, x, y, z);
        // First change at this spot keeps the true baseline for the session.
        if (originals.putIfAbsent(key, data) == null) {
            dirty = true;
        }
    }

    private void commit(Block block) {
        if (block == null) {
            return;
        }
        if (originals.remove(key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ())) != null) {
            dirty = true;
        }
    }

    private int restoreAll() {
        int count = 0;
        for (Map.Entry<String, String> entry : originals.entrySet()) {
            if (apply(entry.getKey(), entry.getValue())) {
                count++;
            }
        }
        return count;
    }

    private boolean apply(String key, String dataString) {
        ParsedKey parsed = parseKey(key);
        if (parsed == null) {
            return false;
        }
        World world = Bukkit.getWorld(parsed.world);
        if (world == null) {
            return false;
        }
        try {
            BlockData data = Bukkit.createBlockData(dataString);
            Block block = world.getBlockAt(parsed.x, parsed.y, parsed.z);
            // false = no physics neighbor updates (keeps floating saplings etc.).
            block.setBlockData(data, false);
            return true;
        } catch (IllegalArgumentException bad) {
            plugin.getLogger().warning("Transient blocks: bad data for " + key + ": " + bad.getMessage());
            return false;
        }
    }

    private void saveIfDirty() {
        if (dirty) {
            saveNow();
        }
    }

    private synchronized void saveNow() {
        try {
            Files.createDirectories(storeFile.getParent());
            Map<String, String> snapshot = new LinkedHashMap<>(originals);
            try (Writer writer = Files.newBufferedWriter(storeFile)) {
                GSON.toJson(snapshot, writer);
            }
            dirty = false;
        } catch (IOException failed) {
            plugin.getLogger().log(Level.WARNING, "Could not save transient-blocks.json", failed);
        }
    }

    private Map<String, String> readFile() {
        if (!Files.isRegularFile(storeFile)) {
            return Map.of();
        }
        try (Reader reader = Files.newBufferedReader(storeFile)) {
            Map<String, String> map = GSON.fromJson(reader, MAP_TYPE.getType());
            return map == null ? Map.of() : map;
        } catch (Exception failed) {
            plugin.getLogger().log(Level.WARNING, "Could not read transient-blocks.json", failed);
            return Map.of();
        }
    }

    private static String key(String world, int x, int y, int z) {
        return world + "|" + x + "," + y + "," + z;
    }

    private static ParsedKey parseKey(String key) {
        int bar = key.indexOf('|');
        if (bar <= 0) {
            return null;
        }
        String world = key.substring(0, bar);
        String[] parts = key.substring(bar + 1).split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new ParsedKey(world, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    private record ParsedKey(String world, int x, int y, int z) {
    }
}
