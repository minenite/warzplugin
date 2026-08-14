package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures blocks broken by explosions and restores them slowly to their exact prior state.
 */
public final class ExplosionRegenService implements Listener {
    private final WarzPlugin plugin;
    private static final long RECENT_AREA_MS = 25_000L;

    private final Deque<RegenEntry> queue = new ArrayDeque<>();
    private final Set<String> queuedKeys = new HashSet<>();
    /**
     * Keys currently in the live queue.
     *
     * <p>Mirrors {@link #queue} so "is this block already waiting?" is a lookup
     * rather than a walk of the whole queue. It was a walk, called once per entry
     * from releaseHold, which made releasing a large crater quadratic - slowest
     * exactly when a big blast had just landed.
     */
    private final Set<String> liveKeys = new HashSet<>();
    /** Pending entries per chunk, so a regen-area test does not scan every entry. */
    private final Map<Long, Integer> pendingByChunk = new HashMap<>();
    /**
     * Holds that exist only for the tick it takes to see what a blast destroyed.
     *
     * <p>They are never written to disk: a hold created and released within one
     * tick has nothing to survive a restart, and saving it meant three full
     * rewrites of the holds file per grenade.
     */
    private final Set<String> transientHolds = new HashSet<>();
    /** Held crater batches (e.g. drone crash loot) — still in queuedKeys, not in the live queue. */
    private final Map<String, List<RegenEntry>> holds = new HashMap<>();
    private final Object lock = new Object();
    /** Chunk keys recently involved in regen — used for player suffocation rescue. */
    private final Map<Long, Long> recentChunks = new ConcurrentHashMap<>();

    private boolean enabled = true;
    private int ticksPerBlock = 6;
    private int maxBlocksPerTick = 32;
    private int drainSeconds = 20;
    private Sound sound = Sound.BLOCK_STONE_PLACE;
    private float volume = 0.12f;
    private float pitch = 1.55f;
    private int cooldown;

    public ExplosionRegenService(WarzPlugin plugin) {
        this.plugin = plugin;
        reloadFromConfig();
    }

    public void reloadFromConfig() {
        FileConfiguration cfg = plugin.getConfig();
        enabled = cfg.getBoolean("explosion-regen.enabled", true);
        ticksPerBlock = Math.max(1, cfg.getInt("explosion-regen.ticks-per-block", 6));
        maxBlocksPerTick = Math.max(1, cfg.getInt("explosion-regen.max-blocks-per-tick", 32));
        drainSeconds = Math.max(1, cfg.getInt("explosion-regen.drain-seconds", 20));
        volume = (float) Math.max(0.0, Math.min(1.0, cfg.getDouble("explosion-regen.volume", 0.12)));
        pitch = (float) Math.max(0.5, Math.min(2.0, cfg.getDouble("explosion-regen.pitch", 1.55)));
        String soundName = cfg.getString("explosion-regen.sound", "BLOCK_STONE_PLACE");
        sound = parseSound(soundName, Sound.BLOCK_STONE_PLACE);
        cooldown = 0;
    }

    public boolean enabled() {
        return enabled;
    }

    public int queueSize() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /** Persist toggle to config.yml. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        plugin.getConfig().set("explosion-regen.enabled", enabled);
        plugin.saveConfig();
        if (!enabled) {
            clearQueue();
        }
    }

    public boolean toggle() {
        setEnabled(!enabled);
        return enabled;
    }

    /** Clears the live regen queue only — crash-site holds are preserved. */
    public void clearQueue() {
        synchronized (lock) {
            queue.clear();
            liveKeys.clear();
            pendingByChunk.clear();
            // Keep queuedKeys that still belong to holds
            Set<String> holdKeys = new HashSet<>();
            for (List<RegenEntry> batch : holds.values()) {
                for (RegenEntry e : batch) {
                    holdKeys.add(e.key);
                }
            }
            queuedKeys.retainAll(holdKeys);
            cooldown = 0;
        }
    }

    /** Drop every hold without restoring (used with crash-site force clear + flush). */
    public void discardHold(String holdId) {
        if (holdId == null || holdId.isBlank()) {
            return;
        }
        synchronized (lock) {
            transientHolds.remove(holdId);
            List<RegenEntry> batch = holds.remove(holdId);
            if (batch == null) {
                return;
            }
            for (RegenEntry e : batch) {
                countChunk(e.location, -1);
                if (!queueContainsKey(e.key)) {
                    queuedKeys.remove(e.key);
                }
            }
        }
    }

    public int holdCount() {
        synchronized (lock) {
            return holds.size();
        }
    }

    public int holdSize(String holdId) {
        if (holdId == null) {
            return 0;
        }
        synchronized (lock) {
            List<RegenEntry> batch = holds.get(holdId);
            return batch == null ? 0 : batch.size();
        }
    }

    /**
     * Pull pending regen entries near {@code center} out of the live queue into a hold.
     * Keys stay reserved so later blasts keep the original snapshots. Returns hold id (never null).
     */
    public String holdNear(Location center, double radius) {
        String id = UUID.randomUUID().toString();
        if (center == null || center.getWorld() == null || radius <= 0) {
            synchronized (lock) {
                holds.put(id, new ArrayList<>());
            }
            persistHolds();
            return id;
        }
        synchronized (lock) {
            holds.put(id, new ArrayList<>());
        }
        absorbNearIntoHold(id, center, radius);
        persistHolds();
        return id;
    }

    /**
     * Snapshot intact solids in a sphere into a new hold <b>before</b> the blast.
     * Keys are reserved so the explode capture path won't double-queue them.
     * Call {@link #finalizeHold(String)} after the explosion to drop survivors.
     */
    public String beginHold(Location center, double radius) {
        return beginHold(center, radius, true);
    }

    /**
     * @param persistent whether this hold should survive a restart. Crash sites
     *                   must; a hold that lives one tick has nothing to save, and
     *                   writing it cost three rewrites of the holds file per blast.
     */
    private String beginHold(Location center, double radius, boolean persistent) {
        String id = UUID.randomUUID().toString();
        if (!persistent) {
            synchronized (lock) {
                transientHolds.add(id);
            }
        }
        List<RegenEntry> batch = new ArrayList<>();
        if (center == null || center.getWorld() == null || radius <= 0) {
            synchronized (lock) {
                holds.put(id, batch);
            }
            persistHoldsUnlessTransient(id);
            return id;
        }
        World world = center.getWorld();
        int r = (int) Math.ceil(radius);
        double r2 = radius * radius;
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        synchronized (lock) {
            for (int x = cx - r; x <= cx + r; x++) {
                for (int y = cy - r; y <= cy + r; y++) {
                    for (int z = cz - r; z <= cz + r; z++) {
                        double dx = (x + 0.5) - center.getX();
                        double dy = (y + 0.5) - center.getY();
                        double dz = (z + 0.5) - center.getZ();
                        if (dx * dx + dy * dy + dz * dz > r2) {
                            continue;
                        }
                        Block block = world.getBlockAt(x, y, z);
                        if (skip(block.getType())) {
                            continue;
                        }
                        String key = key(block);
                        if (queuedKeys.contains(key)) {
                            continue;
                        }
                        BlockState state = block.getState();
                        BlockData data = block.getBlockData().clone();
                        batch.add(new RegenEntry(key, block.getLocation().clone(), state, data, 0));
                        queuedKeys.add(key);
                        countChunk(block.getLocation(), 1);
                        markRecent(block.getLocation());
                    }
                }
            }
            holds.put(id, batch);
        }
        persistHoldsUnlessTransient(id);
        return id;
    }

    /**
     * CardForge never fires {@code EntityExplodeEvent} / {@code BlockExplodeEvent},
     * so explode-listener capture never runs. Snapshot solids now, then next tick
     * queue whatever the blast actually destroyed into the live regen queue.
     */
    public void armBlast(Location center, double radius) {
        if (!enabled || center == null || center.getWorld() == null || radius <= 0) {
            return;
        }
        String id = beginHold(center, radius, false);
        Bukkit.getScheduler().runTask(plugin, () -> {
            finalizeHold(id);
            releaseHold(id);
        });
    }

    /**
     * Snapshot (if regen is on) then actually destroy blocks.
     * CardForge {@code createExplosion(breakBlocks=true)} uses MOB + null source,
     * so NeoForge keeps the terrain intact — LAW / Javelin / nades never cratered.
     */
    public void blastTerrain(Location center, double radius) {
        if (center == null || center.getWorld() == null || radius <= 0) {
            return;
        }
        // Snapshotting the whole sphere first meant a BlockState for every solid
        // block within it - thousands per rocket - only to throw away the ones
        // that survived. The carve already decides what breaks, so each block is
        // captured as it is broken: exact, and nothing is allocated for a block
        // that was never going to move.
        carveCrater(center, radius, enabled);
    }

    /**
     * Vanilla-ish crater. Skips unbreakables and loot chests. Chainlink is
     * unmarked and dropped as a merged stack.
     */
    public int carveCrater(Location center, double radius) {
        return carveCrater(center, radius, false);
    }

    /**
     * @param capture snapshot each block as it breaks, so regen can put it back
     */
    private int carveCrater(Location center, double radius, boolean capture) {
        if (center == null || center.getWorld() == null || radius <= 0) {
            return 0;
        }
        List<RegenEntry> captured = capture ? new ArrayList<>() : null;
        World world = center.getWorld();
        int ri = (int) Math.ceil(radius + 0.35);
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        int broken = 0;
        int chainlinkDrops = 0;
        ChainlinkService chainlink = plugin.chainlink();
        for (int x = cx - ri; x <= cx + ri; x++) {
            for (int y = cy - ri; y <= cy + ri; y++) {
                for (int z = cz - ri; z <= cz + ri; z++) {
                    double dx = (x + 0.5) - center.getX();
                    double dy = (y + 0.5) - center.getY();
                    double dz = (z + 0.5) - center.getZ();
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist > radius + 0.35) {
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (skip(type)) {
                        continue;
                    }
                    if (plugin.lootRestock() != null && plugin.lootRestock().isLootChest(block)) {
                        continue;
                    }
                    float resistance;
                    try {
                        resistance = type.getBlastResistance();
                    } catch (Throwable ignored) {
                        resistance = Math.max(0f, type.getHardness() * 5f);
                    }
                    float remaining = (float) ((radius - dist) * 1.15);
                    float needed = (resistance + 0.3f) * 0.3f;
                    if (remaining < needed) {
                        continue;
                    }
                    if (chainlink != null && chainlink.unmark(block)) {
                        chainlinkDrops++;
                    }
                    if (captured != null) {
                        // Taken before the block goes, which is the only moment its
                        // state is still readable.
                        captured.add(new RegenEntry(key(block), block.getLocation().clone(),
                                block.getState(), block.getBlockData().clone(), 0));
                    }
                    block.setType(Material.AIR, false);
                    broken++;
                }
            }
        }
        if (chainlinkDrops > 0 && chainlink != null) {
            chainlink.dropStacked(center.clone().add(0, 0.2, 0), chainlinkDrops);
        }
        if (captured != null && !captured.isEmpty()) {
            // Furthest first, so a crater fills from its rim inwards.
            Location c = center.clone();
            captured.sort(Comparator.comparingDouble((RegenEntry e) -> e.location.distanceSquared(c)).reversed());
            synchronized (lock) {
                for (RegenEntry entry : captured) {
                    if (queuedKeys.contains(entry.key)) {
                        // Already waiting with an older snapshot - that one is the
                        // state worth putting back.
                        continue;
                    }
                    queuedKeys.add(entry.key);
                    queueAdd(entry);
                    markRecent(entry.location);
                }
            }
        }
        return broken;
    }

    /**
     * After a blast: drop hold entries whose blocks survived (still not air/fire).
     * @return remaining held block count
     */
    public int finalizeHold(String holdId) {
        if (holdId == null || holdId.isBlank()) {
            return 0;
        }
        synchronized (lock) {
            List<RegenEntry> batch = holds.get(holdId);
            if (batch == null) {
                return 0;
            }
            Iterator<RegenEntry> it = batch.iterator();
            while (it.hasNext()) {
                RegenEntry e = it.next();
                if (e.location == null || e.location.getWorld() == null) {
                    it.remove();
                    queuedKeys.remove(e.key);
                    continue;
                }
                Material type = e.location.getBlock().getType();
                boolean destroyed = type.isAir() || type == Material.FIRE || type == Material.SOUL_FIRE;
                if (!destroyed) {
                    it.remove();
                    countChunk(e.location, -1);
                    if (!queueContainsKey(e.key) && !holdContainsKeyExcept(holdId, e.key)) {
                        queuedKeys.remove(e.key);
                    }
                }
            }
        }
        persistHoldsUnlessTransient(holdId);
        return holdSize(holdId);
    }

    /** Move live-queue regen entries near {@code center} into an existing hold. */
    public int absorbNearIntoHold(String holdId, Location center, double radius) {
        if (holdId == null || holdId.isBlank() || center == null || center.getWorld() == null || radius <= 0) {
            return 0;
        }
        double r2 = radius * radius;
        World world = center.getWorld();
        int moved = 0;
        synchronized (lock) {
            List<RegenEntry> batch = holds.get(holdId);
            if (batch == null) {
                batch = new ArrayList<>();
                holds.put(holdId, batch);
            }
            Set<String> heldKeys = new HashSet<>();
            for (RegenEntry e : batch) {
                heldKeys.add(e.key);
            }
            Iterator<RegenEntry> it = queue.iterator();
            while (it.hasNext()) {
                RegenEntry e = it.next();
                if (e.location == null || e.location.getWorld() == null || !e.location.getWorld().equals(world)) {
                    continue;
                }
                if (e.location.distanceSquared(center) > r2) {
                    continue;
                }
                queueRemove(it, e);
                if (heldKeys.add(e.key)) {
                    batch.add(e);
                    countChunk(e.location, 1);
                    moved++;
                }
                queuedKeys.add(e.key);
            }
        }
        if (moved > 0) {
            persistHolds();
        }
        return moved;
    }

    private boolean holdContainsKeyExcept(String exceptHoldId, String key) {
        for (Map.Entry<String, List<RegenEntry>> e : holds.entrySet()) {
            if (e.getKey().equals(exceptHoldId)) {
                continue;
            }
            for (RegenEntry entry : e.getValue()) {
                if (entry.key.equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Re-queue a held batch so the crater can regenerate. @return entries released */
    public int releaseHold(String holdId) {
        if (holdId == null || holdId.isBlank()) {
            return 0;
        }
        List<RegenEntry> batch;
        boolean wasTransient;
        synchronized (lock) {
            wasTransient = transientHolds.remove(holdId);
            batch = holds.remove(holdId);
            if (batch == null || batch.isEmpty()) {
                if (!wasTransient) {
                    persistHolds();
                }
                return 0;
            }
            for (RegenEntry entry : batch) {
                countChunk(entry.location, -1);
                queueAdd(entry);
                queuedKeys.add(entry.key);
                markRecent(entry.location);
            }
        }
        if (!wasTransient) {
            persistHolds();
        }
        return batch.size();
    }

    public boolean hasHold(String holdId) {
        if (holdId == null) {
            return false;
        }
        synchronized (lock) {
            return holds.containsKey(holdId);
        }
    }

    private File holdsFile() {
        return new File(plugin.getDataFolder(), "explosion_holds.yml");
    }

    /** Persist held crater snapshots (crash sites survive restart). */
    /** Saves unless every hold is transient, in which case there is nothing to save. */
    private void persistHoldsUnlessTransient(String holdId) {
        synchronized (lock) {
            if (transientHolds.contains(holdId)) {
                return;
            }
        }
        persistHolds();
    }

    public void persistHolds() {
        File file = holdsFile();
        YamlConfiguration yaml = new YamlConfiguration();
        synchronized (lock) {
            for (Map.Entry<String, List<RegenEntry>> e : holds.entrySet()) {
                if (transientHolds.contains(e.getKey())) {
                    continue;
                }
                List<Map<String, Object>> rows = new ArrayList<>(e.getValue().size());
                for (RegenEntry entry : e.getValue()) {
                    if (entry.location == null || entry.location.getWorld() == null || entry.data == null) {
                        continue;
                    }
                    Map<String, Object> row = new HashMap<>();
                    row.put("world", entry.location.getWorld().getName());
                    row.put("x", entry.location.getBlockX());
                    row.put("y", entry.location.getBlockY());
                    row.put("z", entry.location.getBlockZ());
                    row.put("data", entry.data.getAsString());
                    rows.add(row);
                }
                yaml.set("holds." + e.getKey(), rows);
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save explosion_holds.yml: " + ex.getMessage());
        }
    }

    public void loadHolds() {
        File file = holdsFile();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("holds");
        if (root == null) {
            return;
        }
        int total = 0;
        synchronized (lock) {
            for (String holdId : root.getKeys(false)) {
                List<?> rows = root.getList(holdId);
                if (rows == null || rows.isEmpty()) {
                    holds.put(holdId, new ArrayList<>());
                    continue;
                }
                List<RegenEntry> batch = new ArrayList<>();
                for (Object raw : rows) {
                    if (!(raw instanceof Map<?, ?> map)) {
                        continue;
                    }
                    String worldName = stringVal(map.get("world"));
                    String dataStr = stringVal(map.get("data"));
                    if (worldName == null || dataStr == null) {
                        continue;
                    }
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        continue;
                    }
                    int x = intVal(map.get("x"));
                    int y = intVal(map.get("y"));
                    int z = intVal(map.get("z"));
                    BlockData data;
                    try {
                        data = Bukkit.createBlockData(dataStr);
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }
                    Location loc = new Location(world, x, y, z);
                    String key = world.getUID() + ":" + x + "," + y + "," + z;
                    batch.add(new RegenEntry(key, loc, null, data, 0));
                    queuedKeys.add(key);
                    countChunk(loc, 1);
                }
                holds.put(holdId, batch);
                total += batch.size();
            }
        }
        if (total > 0) {
            plugin.getLogger().info("Loaded " + holds.size() + " explosion hold(s) (" + total + " blocks).");
        }
    }

    private static String stringVal(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int intVal(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** True if this player is standing in a chunk that is regenerating / recently regenerated. */
    public boolean isInRegenArea(Player player) {
        if (!enabled || player == null || player.getWorld() == null) {
            return false;
        }
        Location loc = player.getLocation();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        long now = System.currentTimeMillis();
        pruneRecent(now);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Long until = recentChunks.get(chunkKey(player.getWorld().getUID().getMostSignificantBits(),
                        cx + dx, cz + dz));
                if (until != null && until >= now) {
                    return true;
                }
            }
        }
        // Anything still pending in this chunk or the ones around it counts. This
        // used to walk every queued and held entry, per player, every tick it was
        // asked - which is worst exactly when a big crater is waiting.
        long world = player.getWorld().getUID().getMostSignificantBits();
        synchronized (lock) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (pendingByChunk.containsKey(chunkKey(world, cx + dx, cz + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Instantly restore every pending block (including holds). @return how many were placed */
    public int flushNow() {
        List<RegenEntry> all;
        synchronized (lock) {
            all = new ArrayList<>(queue);
            for (List<RegenEntry> batch : holds.values()) {
                all.addAll(batch);
            }
            queue.clear();
            liveKeys.clear();
            pendingByChunk.clear();
            holds.clear();
            transientHolds.clear();
            queuedKeys.clear();
            cooldown = 0;
        }
        persistHolds();
        int placed = 0;
        for (RegenEntry entry : all) {
            if (restore(entry, false)) {
                placed++;
            }
        }
        return placed;
    }

    public void tick() {
        if (!enabled) {
            return;
        }

        int budget;
        List<RegenEntry> batch;
        synchronized (lock) {
            int pending = queue.size();
            if (pending == 0) {
                return;
            }

            // A single block every ticks-per-block is 3 blocks a second, which
            // looks right for a grenade and is hopeless for anything larger: an
            // 800-block crater took four minutes, and simultaneous blasts queued
            // behind each other rather than filling together. The gentle pacing is
            // kept while the backlog is small, and the rate rises to whatever
            // clears the queue within drain-seconds once it is not.
            int needed = (int) Math.ceil(pending / (double) (drainSeconds * 20));
            if (needed <= 1) {
                if (cooldown > 0) {
                    cooldown--;
                    return;
                }
                cooldown = ticksPerBlock;
                budget = 1;
            } else {
                cooldown = 0;
                budget = Math.min(needed, maxBlocksPerTick);
            }

            batch = new ArrayList<>(budget);
            for (int i = 0; i < budget; i++) {
                RegenEntry next = queuePoll();
                if (next == null) {
                    break;
                }
                batch.add(next);
            }
        }

        // One sound for the whole tick. At the catch-up rate a sound per block
        // would be dozens a second in the same spot.
        boolean playedSound = false;
        for (RegenEntry entry : batch) {
            boolean restored = restore(entry, !playedSound);
            if (restored) {
                playedSound = true;
                synchronized (lock) {
                    queuedKeys.remove(entry.key);
                }
                continue;
            }
            synchronized (lock) {
                // Retry later - do not drop the block forever
                queuedKeys.add(entry.key);
                queueAdd(entry);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!enabled) {
            return;
        }
        event.setYield(0f);
        capture(new ArrayList<>(event.blockList()), event.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!enabled) {
            return;
        }
        event.setYield(0f);
        Location center = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        capture(new ArrayList<>(event.blockList()), center);
    }

    private void capture(List<Block> blocks, Location center) {
        if (blocks == null || blocks.isEmpty() || center == null || center.getWorld() == null) {
            return;
        }
        List<RegenEntry> batch = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            if (block == null || skip(block.getType())) {
                continue;
            }
            String key = key(block);
            // Snapshot immediately while the block is still intact
            BlockState state = block.getState();
            BlockData data = block.getBlockData().clone();
            batch.add(new RegenEntry(key, block.getLocation().clone(), state, data, 0));
        }
        if (batch.isEmpty()) {
            return;
        }
        Location c = center.clone();
        batch.sort(Comparator.comparingDouble((RegenEntry e) -> e.location.distanceSquared(c)).reversed());

        synchronized (lock) {
            for (RegenEntry entry : batch) {
                if (queuedKeys.contains(entry.key)) {
                    // Already waiting to restore the original — keep that snapshot
                    continue;
                }
                queuedKeys.add(entry.key);
                queueAdd(entry);
                markRecent(entry.location);
            }
        }

        // Next tick: re-queue anything that is air/fire but somehow fell out of the queue
        // (back-to-back blasts / failed restores). Uses the snapshots we just took.
        final List<RegenEntry> verify = List.copyOf(batch);
        Bukkit.getScheduler().runTask(plugin, () -> verifyCaptured(verify));
    }

    private void verifyCaptured(List<RegenEntry> batch) {
        if (!enabled || batch.isEmpty()) {
            return;
        }
        synchronized (lock) {
            for (RegenEntry entry : batch) {
                Block block = entry.location.getBlock();
                Material type = block.getType();
                boolean destroyed = type.isAir() || type == Material.FIRE || type == Material.SOUL_FIRE;
                if (!destroyed) {
                    continue;
                }
                if (queuedKeys.contains(entry.key)) {
                    continue; // still scheduled — fine
                }
                // Lost from queue but still missing in the world - put it back
                queuedKeys.add(entry.key);
                queueAdd(entry);
            }
        }
    }

    private boolean queueContainsKey(String key) {
        return liveKeys.contains(key);
    }

    /** Adds to the live queue, keeping the key set and per-chunk counts in step. */
    private void queueAdd(RegenEntry entry) {
        if (!liveKeys.add(entry.key)) {
            return;
        }
        queue.addLast(entry);
        countChunk(entry.location, 1);
    }

    private RegenEntry queuePoll() {
        RegenEntry entry = queue.pollFirst();
        if (entry != null) {
            liveKeys.remove(entry.key);
            countChunk(entry.location, -1);
        }
        return entry;
    }

    /** Removes through an iterator already positioned on {@code entry}. */
    private void queueRemove(Iterator<RegenEntry> it, RegenEntry entry) {
        it.remove();
        liveKeys.remove(entry.key);
        countChunk(entry.location, -1);
    }

    private void countChunk(Location loc, int delta) {
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        long key = chunkKey(loc.getWorld().getUID().getMostSignificantBits(),
                loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        pendingByChunk.merge(key, delta, (a, b) -> {
            int sum = a + b;
            return sum <= 0 ? null : sum;
        });
    }

    private boolean restore(RegenEntry entry, boolean playSound) {
        Location loc = entry.location;
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        World world = loc.getWorld();
        Block block = loc.getBlock();

        // Somebody has filled this space since the blast. Putting the old block
        // back would delete whatever they built, so the entry is simply done.
        Material current = block.getType();
        if (!current.isAir() && current != Material.FIRE && current != Material.SOUL_FIRE) {
            return true;
        }

        try {
            // CardForge often no-ops BlockState.update; force the cloned BlockData first.
            if (entry.data != null) {
                block.setBlockData(entry.data, false);
            } else if (entry.state != null) {
                entry.state.update(true, false);
            } else {
                return false;
            }
        } catch (Exception ex) {
            try {
                if (entry.data != null) {
                    block.setBlockData(entry.data, false);
                } else if (entry.state != null) {
                    entry.state.update(true, false);
                } else {
                    return false;
                }
            } catch (Exception ignored) {
                return false;
            }
        }
        // Confirm something solid came back; retry with the other API if needed.
        if (block.getType().isAir()) {
            try {
                if (entry.state != null) {
                    entry.state.update(true, false);
                }
                if (block.getType().isAir() && entry.data != null) {
                    block.setBlockData(entry.data, false);
                }
            } catch (Exception ignored) {
                return false;
            }
        }
        if (block.getType().isAir()) {
            return false;
        }
        if (playSound) {
            world.playSound(loc, sound, volume, pitch);
        }
        markRecent(loc);
        rescuePlayersNear(loc);
        return true;
    }

    private void markRecent(Location loc) {
        if (loc.getWorld() == null) {
            return;
        }
        long until = System.currentTimeMillis() + RECENT_AREA_MS;
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        recentChunks.put(chunkKey(loc.getWorld().getUID().getMostSignificantBits(), cx, cz), until);
    }

    private void pruneRecent(long now) {
        Iterator<Map.Entry<Long, Long>> it = recentChunks.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < now) {
                it.remove();
            }
        }
    }

    private void rescuePlayersNear(Location loc) {
        if (loc.getWorld() == null) {
            return;
        }
        GroundEmergeListener emerge = plugin.groundEmerge();
        if (emerge == null) {
            return;
        }
        for (Player player : loc.getWorld().getNearbyPlayers(loc, 2.25)) {
            if (emerge.isEmerging(player)) {
                continue;
            }
            if (!isEmbedded(player)) {
                continue;
            }
            emerge.emergeFromGround(player);
        }
    }

    private static boolean isEmbedded(Player player) {
        Block feet = player.getLocation().getBlock();
        Block body = player.getLocation().clone().add(0, 0.9, 0).getBlock();
        Block eyes = player.getEyeLocation().getBlock();
        return isSuffocating(feet) || isSuffocating(body) || isSuffocating(eyes);
    }

    private static boolean isSuffocating(Block block) {
        if (block == null || block.isEmpty() || block.isPassable() || block.isLiquid()) {
            return false;
        }
        return block.getType().isOccluding() || block.getType().isSolid();
    }

    private static long chunkKey(long worldMsb, int cx, int cz) {
        return (worldMsb ^ (((long) cx) << 32) ^ (cz & 0xffffffffL));
    }

    private static boolean skip(Material type) {
        if (type == null || type.isAir()) {
            return true;
        }
        return type == Material.FIRE
                || type == Material.SOUL_FIRE
                || type == Material.TNT
                || type == Material.BEDROCK
                || type == Material.BARRIER
                || type == Material.COMMAND_BLOCK
                || type == Material.CHAIN_COMMAND_BLOCK
                || type == Material.REPEATING_COMMAND_BLOCK
                || type == Material.STRUCTURE_BLOCK
                || type == Material.JIGSAW;
    }

    private static String key(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private static Sound parseSound(String raw, Sound fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT).replace('.', '_').replace(' ', '_');
        try {
            return Sound.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private record RegenEntry(String key, Location location, BlockState state, BlockData data, int retries) {
    }
}
