package com.local.warz.runtime;

import com.local.warz.WarzKeys;
import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.model.GunDefinition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MQ-9 crash sites: hold crater regen until the wreckage barrel is fully looted, then restore + clear fire.
 * Persists sites + explosion holds across restart; ambient smoke only ticks near players.
 */
public final class CrashSiteService implements Listener {
    /** Crater hold / absorb radius (blast + rim). */
    public static final double HOLD_RADIUS = 14.0;
    private static final int FIRE_RADIUS = 7;
    private static final double AMBIENCE_RANGE = 72.0;
    /** MQ-9 slant range for crater smoke / thermal cues. */
    private static final double DRONE_SMOKE_RANGE = 820.0;
    private static final float THERMAL_SMOKE_HEIGHT = 36f;
    private static final int PROP_COUNT = 6;
    private static final Material[] WRECK_BLOCKS = {
            Material.GRAY_CONCRETE, Material.LIGHT_GRAY_CONCRETE, Material.IRON_BLOCK,
            Material.DEEPSLATE_TILES, Material.SMOOTH_BASALT, Material.CYAN_TERRACOTTA,
            Material.POLISHED_DEEPSLATE, Material.NETHERITE_BLOCK
    };
    private static final NvgGear.Variant[] FIXED_NODS = {
            NvgGear.Variant.GREEN, NvgGear.Variant.WHITE, NvgGear.Variant.AMBER,
            NvgGear.Variant.BLUE, NvgGear.Variant.RED, NvgGear.Variant.TRUE_COLOR
    };
    private static final ThermalGear.Variant[] FIXED_FLIRS = {
            ThermalGear.Variant.WHITE_HOT, ThermalGear.Variant.BLACK_HOT,
            ThermalGear.Variant.IRONBOW, ThermalGear.Variant.RAINBOW, ThermalGear.Variant.FUSION
    };

    private final WarzPlugin plugin;
    private final NamespacedKey siteKey;
    private final NamespacedKey propKey;
    private final File file;
    private final Map<String, CrashSite> sites = new ConcurrentHashMap<>();
    private BukkitTask ambienceTask;
    private int ambiencePhase;

    public CrashSiteService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.siteKey = WarzKeys.of("crash_site");
        this.propKey = WarzKeys.of("crash_prop");
        this.file = new File(plugin.getDataFolder(), "crash_sites.yml");
        load();
        startAmbience();
    }

    public Collection<CrashSiteView> listSites() {
        List<CrashSiteView> out = new ArrayList<>();
        for (CrashSite s : sites.values()) {
            int holdBlocks = plugin.explosionRegen() != null ? plugin.explosionRegen().holdSize(s.holdId) : 0;
            out.add(new CrashSiteView(s.id, s.center, s.barrelLoc, s.createdMs, holdBlocks, s.propIds.size()));
        }
        out.sort((a, b) -> Long.compare(b.createdMs(), a.createdMs()));
        return out;
    }

    public int siteCount() {
        return sites.size();
    }

    /** True if this crash-site id is still active (barrel not yet finished). */
    public boolean isActiveSite(String siteId) {
        return siteId != null && sites.containsKey(siteId);
    }

    /** Force-complete every site (regen + clear fire/props/barrel). */
    public int clearAll() {
        List<String> ids = new ArrayList<>(sites.keySet());
        for (String id : ids) {
            completeSite(id, true);
        }
        save();
        return ids.size();
    }

    /**
     * After a drone impact explosion — hold crater until the wreckage barrel is emptied,
     * then release regen. Prefer {@link #spawnAfterCrash(Location, String)} with a hold
     * created via {@link ExplosionRegenService#beginHold} <b>before</b> the blast.
     */
    public void spawnAfterCrash(Location boom) {
        String holdId = null;
        if (plugin.explosionRegen() != null) {
            holdId = plugin.explosionRegen().holdNear(boom, HOLD_RADIUS);
            plugin.explosionRegen().finalizeHold(holdId);
        }
        spawnAfterCrash(boom, holdId);
    }

    /** @param holdId crater batch from {@link ExplosionRegenService#beginHold} / absorb (may be null) */
    public void spawnAfterCrash(Location boom, String holdId) {
        if (boom == null || boom.getWorld() == null) {
            if (holdId != null && plugin.explosionRegen() != null) {
                plugin.explosionRegen().releaseHold(holdId);
            }
            return;
        }
        String siteId = UUID.randomUUID().toString();
        Block barrelBlock = placeBarrel(boom);
        if (barrelBlock == null) {
            if (holdId != null && plugin.explosionRegen() != null) {
                plugin.explosionRegen().releaseHold(holdId);
            }
            return;
        }
        // Pull any late explode-event captures into the same hold (next tick too).
        if (holdId != null && plugin.explosionRegen() != null) {
            plugin.explosionRegen().absorbNearIntoHold(holdId, boom, HOLD_RADIUS);
            plugin.explosionRegen().finalizeHold(holdId);
        }
        CrashSite site = new CrashSite(siteId, holdId, boom.clone(), barrelBlock.getLocation().clone(),
                System.currentTimeMillis());
        sites.put(siteId, site);
        setupWreckageBarrel(barrelBlock, siteId);
        spawnWreckageProps(site);
        final Location barrelLoc = barrelBlock.getLocation().clone();
        final String hid = holdId;
        final Location boomCopy = boom.clone();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (hid != null && plugin.explosionRegen() != null && sites.containsKey(siteId)) {
                plugin.explosionRegen().absorbNearIntoHold(hid, boomCopy, HOLD_RADIUS);
            }
            Block b = barrelLoc.getBlock();
            if (b.getType() != Material.BARREL || !sites.containsKey(siteId)) {
                return;
            }
            if (b.getState() instanceof Barrel barrel && inventoryEmpty(barrel.getInventory())) {
                setupWreckageBarrel(b, siteId);
            }
        });
        int held = holdId != null && plugin.explosionRegen() != null
                ? plugin.explosionRegen().holdSize(holdId) : 0;
        plugin.getLogger().info("UAV crash site " + shortId(siteId) + " — holding " + held
                + " crater blocks until wreckage is looted");
        save();
    }

    private void startAmbience() {
        if (ambienceTask != null) {
            ambienceTask.cancel();
        }
        ambienceTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (sites.isEmpty()) {
                return;
            }
            ambiencePhase++;
            boolean refreshFire = (ambiencePhase % 4) == 0;
            for (CrashSite site : sites.values()) {
                tickAmbience(site, refreshFire);
            }
        }, 20L, 12L);
    }

    private void tickAmbience(CrashSite site, boolean refreshFire) {
        World world = site.center.getWorld();
        if (world == null) {
            return;
        }
        int cx = site.center.getBlockX() >> 4;
        int cz = site.center.getBlockZ() >> 4;
        if (!world.isChunkLoaded(cx, cz)) {
            return;
        }
        boolean nearGround = anyPlayerNear(world, site.center, AMBIENCE_RANGE);
        boolean droneNear = anyDronePilotNear(world, site.center, DRONE_SMOKE_RANGE);
        if (!nearGround && !droneNear) {
            return;
        }
        if (nearGround) {
            ensureProps(site);
        }
        // Thick visual plume for nearby players; force=true so altitude drones still get particles
        smokePlume(world, site.center, droneNear || nearGround);
        // Companion thermal column for MQ-9 white-hot / black-hot (client filters palette)
        if (droneNear && plugin.laserBridge() != null && (ambiencePhase % 2) == 0) {
            plugin.laserBridge().broadcastThermalSmoke(site.center.clone().add(0, 0.4, 0), THERMAL_SMOKE_HEIGHT);
        }
        if (refreshFire && nearGround) {
            refreshFire(site.center, FIRE_RADIUS);
        }
    }

    private static boolean anyPlayerNear(World world, Location center, double range) {
        double r2 = range * range;
        for (Player p : world.getPlayers()) {
            if (p.getWorld() != world) {
                continue;
            }
            if (p.getLocation().distanceSquared(center) <= r2) {
                return true;
            }
        }
        return false;
    }

    private boolean anyDronePilotNear(World world, Location center, double range) {
        if (plugin.bigDrone() == null) {
            return false;
        }
        double r2 = range * range;
        for (Player p : plugin.bigDrone().onlinePilots()) {
            if (p.getWorld() != world) {
                continue;
            }
            if (p.getLocation().distanceSquared(center) <= r2) {
                return true;
            }
        }
        return false;
    }

    /** Dense rising column — force particles when drones/nearby observers need long-range draw. */
    private static void smokePlume(World world, Location center, boolean force) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        // Multi-stem mushroom so the crater reads as a wreck fire, not a candle
        int stems = 3;
        for (int stem = 0; stem < stems; stem++) {
            double baseX = center.getX() + (stem - 1) * 0.85 + rng.nextGaussian() * 0.45;
            double baseZ = center.getZ() + rng.nextGaussian() * 0.55 + (stem == 1 ? 0.3 : -0.15);
            double baseY = center.getY() + 0.35;
            for (int h = 0; h < 22; h++) {
                if (rng.nextFloat() > 0.88f) {
                    continue;
                }
                double y = baseY + h * 1.05 + rng.nextDouble() * 0.35;
                double drift = 0.08 + h * 0.055;
                Location at = new Location(world,
                        baseX + rng.nextGaussian() * drift,
                        y,
                        baseZ + rng.nextGaussian() * drift);
                world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, at, 0, 0.0, 0.08, 0.0, 0.012, null, force);
                if (h < 8) {
                    world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at, 0, 0.0, 0.05, 0.0, 0.01, null, force);
                }
                if (h < 5) {
                    world.spawnParticle(Particle.LARGE_SMOKE, at, 2, 0.25, 0.15, 0.25, 0.008, null, force);
                }
            }
        }
        // Ground boil
        world.spawnParticle(Particle.LARGE_SMOKE, center.clone().add(0, 0.5, 0),
                18, 2.2, 0.5, 2.2, 0.02, null, force);
        world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, center.clone().add(0, 0.8, 0),
                10, 1.6, 0.4, 1.6, 0.01, null, force);
        if (rng.nextInt(3) == 0) {
            world.spawnParticle(Particle.FLAME, center.clone().add(0, 0.35, 0),
                    16, 1.8, 0.4, 1.8, 0.012, null, force);
            world.spawnParticle(Particle.LAVA, center.clone().add(0, 0.2, 0),
                    6, 1.2, 0.2, 1.2, 0.0, null, force);
        }
        if (rng.nextInt(8) == 0) {
            world.playSound(center, Sound.BLOCK_FIRE_AMBIENT, 0.75f, 0.65f + rng.nextFloat() * 0.25f);
        }
    }

    private void spawnWreckageProps(CrashSite site) {
        World world = site.center.getWorld();
        if (world == null) {
            return;
        }
        removeProps(site);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location anchor = site.barrelLoc != null ? site.barrelLoc.clone().add(0.5, 0.05, 0.5)
                : site.center.clone();
        for (int i = 0; i < PROP_COUNT; i++) {
            double ang = (Math.PI * 2.0 * i) / PROP_COUNT + rng.nextDouble() * 0.4;
            double dist = 1.1 + rng.nextDouble() * 1.8;
            Location at = anchor.clone().add(Math.cos(ang) * dist, rng.nextDouble() * 0.35 - 0.1,
                    Math.sin(ang) * dist);
            Material mat = WRECK_BLOCKS[rng.nextInt(WRECK_BLOCKS.length)];
            // Prefer netherite sparingly
            if (mat == Material.NETHERITE_BLOCK && rng.nextInt(4) != 0) {
                mat = Material.GRAY_CONCRETE;
            }
            BlockData data = mat.createBlockData();
            float yaw = rng.nextFloat() * 360f;
            float tip = 15f + rng.nextFloat() * 55f;
            float scale = 0.35f + rng.nextFloat() * 0.45f;
            final String siteId = site.id;
            BlockDisplay display = world.spawn(at, BlockDisplay.class, d -> {
                d.setBlock(data);
                d.setGravity(false);
                d.setPersistent(true);
                d.setInvulnerable(true);
                d.setSilent(true);
                d.setBillboard(Display.Billboard.FIXED);
                d.setBrightness(new Display.Brightness(10, 10));
                d.getPersistentDataContainer().set(propKey, PersistentDataType.STRING, siteId);
                d.setTransformation(new Transformation(
                        new Vector3f(0f, 0f, 0f),
                        new AxisAngle4f((float) Math.toRadians(tip), 1f, 0.15f, 0.05f),
                        new Vector3f(scale, scale * (0.55f + rng.nextFloat() * 0.5f), scale),
                        new AxisAngle4f((float) Math.toRadians(yaw), 0f, 1f, 0f)
                ));
                d.setInterpolationDuration(0);
                d.setTeleportDuration(0);
            });
            site.propIds.add(display.getUniqueId());
        }
    }

    private void ensureProps(CrashSite site) {
        World world = site.center.getWorld();
        if (world == null) {
            return;
        }
        int alive = 0;
        for (UUID id : site.propIds) {
            Entity e = world.getEntity(id);
            if (e instanceof BlockDisplay && e.isValid()) {
                alive++;
            }
        }
        if (alive < PROP_COUNT / 2) {
            spawnWreckageProps(site);
            save();
        }
    }

    private void removeProps(CrashSite site) {
        World world = site.center.getWorld();
        if (world != null) {
            for (UUID id : site.propIds) {
                Entity e = world.getEntity(id);
                if (e != null) {
                    e.remove();
                }
            }
            // Sweep orphans tagged for this site (chunk must be loaded)
            if (world.isChunkLoaded(site.center.getBlockX() >> 4, site.center.getBlockZ() >> 4)) {
                for (Entity e : world.getNearbyEntities(site.center, 8, 6, 8)) {
                    if (!(e instanceof BlockDisplay)) {
                        continue;
                    }
                    String tag = e.getPersistentDataContainer().get(propKey, PersistentDataType.STRING);
                    if (site.id.equals(tag)) {
                        e.remove();
                    }
                }
            }
        }
        site.propIds.clear();
    }

    private Block placeBarrel(Location boom) {
        World world = boom.getWorld();
        if (world == null) {
            return null;
        }
        int x = boom.getBlockX();
        int z = boom.getBlockZ();
        int startY = boom.getBlockY();
        Block floor = null;
        for (int y = startY; y >= startY - 10; y--) {
            Block b = world.getBlockAt(x, y, z);
            if (b.getType().isSolid() && b.getType() != Material.BARREL) {
                floor = b;
                break;
            }
        }
        if (floor == null) {
            floor = world.getBlockAt(x, Math.max(world.getMinHeight() + 1, startY - 1), z);
            if (!floor.getType().isSolid()) {
                floor.setType(Material.STONE, false);
            }
        }
        Block above = floor.getRelative(0, 1, 0);
        if (!above.getType().isAir() && above.getType() != Material.FIRE && above.getType() != Material.SOUL_FIRE
                && above.getType() != Material.BARREL) {
            above.breakNaturally(false);
        }
        above.setType(Material.BARREL, false);
        world.playSound(above.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_ANVIL_LAND, 0.7f, 0.55f);
        return above;
    }

    private void setupWreckageBarrel(Block barrelBlock, String siteId) {
        if (!(barrelBlock.getState() instanceof Barrel barrel)) {
            return;
        }
        barrel.customName(Component.text("UAV Wreckage", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        barrel.getPersistentDataContainer().set(siteKey, PersistentDataType.STRING, siteId);

        Inventory inv = barrel.getSnapshotInventory();
        inv.clear();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        inv.setItem(0, plugin.items().createPeq15Part());
        inv.setItem(1, plugin.items().createNvgHelmet(FIXED_NODS[rng.nextInt(FIXED_NODS.length)]));
        inv.setItem(2, plugin.items().createThermalHelmet(FIXED_FLIRS[rng.nextInt(FIXED_FLIRS.length)]));

        GunDefinition law = plugin.registry().get("law").orElse(null);
        if (law != null) {
            inv.setItem(3, plugin.items().create(law, 1));
        }
        barrel.update(true, false);
    }

    private String siteIdOf(Block block) {
        if (block == null || block.getType() != Material.BARREL || !(block.getState() instanceof TileState tile)) {
            return null;
        }
        return tile.getPersistentDataContainer().get(siteKey, PersistentDataType.STRING);
    }

    private boolean isCrashBarrel(Block block) {
        String id = siteIdOf(block);
        return id != null && sites.containsKey(id);
    }

    private static boolean inventoryEmpty(Inventory inv) {
        if (inv == null) {
            return true;
        }
        for (ItemStack stack : inv.getContents()) {
            if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
                return false;
            }
        }
        return true;
    }

    private void tryCompleteFromInventory(Inventory inv) {
        if (inv == null) {
            return;
        }
        InventoryHolder holder = inv.getHolder();
        Block block = null;
        if (holder instanceof Barrel barrel) {
            block = barrel.getBlock();
        } else if (holder instanceof Block b) {
            block = b;
        }
        if (block == null) {
            return;
        }
        String siteId = siteIdOf(block);
        if (siteId == null || !sites.containsKey(siteId)) {
            return;
        }
        if (!inventoryEmpty(inv)) {
            return;
        }
        completeSite(siteId, true);
    }

    private void completeSite(String siteId, boolean removeBarrel) {
        CrashSite site = sites.remove(siteId);
        if (site == null) {
            return;
        }
        removeProps(site);
        clearFire(site.center, FIRE_RADIUS + 2);
        if (removeBarrel && site.barrelLoc != null && site.barrelLoc.getWorld() != null) {
            Block b = site.barrelLoc.getBlock();
            if (b.getType() == Material.BARREL && siteId.equals(siteIdOf(b))) {
                if (b.getState() instanceof Barrel barrel) {
                    barrel.getInventory().clear();
                }
                b.setType(Material.AIR, false);
            }
        }
        if (site.holdId != null && plugin.explosionRegen() != null) {
            int n = plugin.explosionRegen().releaseHold(site.holdId);
            plugin.getLogger().info("Crash site " + shortId(siteId) + " cleared — releasing "
                    + n + " crater blocks");
        }
        World w = site.center.getWorld();
        if (w != null) {
            w.playSound(site.center, Sound.BLOCK_FIRE_EXTINGUISH, 1.1f, 0.9f);
            w.spawnParticle(Particle.CLOUD, site.center.clone().add(0, 0.5, 0),
                    30, 2.0, 0.6, 2.0, 0.02);
        }
        save();
    }

    static void refreshFire(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int dy = -2; dy <= 3; dy++) {
                    Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (block.getType() == Material.BARREL) {
                        continue;
                    }
                    if (!block.getType().isAir() && block.getType() != Material.FIRE
                            && block.getType() != Material.SOUL_FIRE) {
                        continue;
                    }
                    Block below = block.getRelative(0, -1, 0);
                    if (!below.getType().isSolid() || below.getType() == Material.BARREL) {
                        continue;
                    }
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    double chance = dist < 2.5 ? 0.55 : (dist < 4.5 ? 0.35 : 0.18);
                    if (rng.nextDouble() > chance) {
                        continue;
                    }
                    block.setType(Material.FIRE, false);
                }
            }
        }
    }

    static void clearFire(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int dy = -3; dy <= 4; dy++) {
                    Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (block.getType() == Material.FIRE || block.getType() == Material.SOUL_FIRE) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private void load() {
        sites.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("sites");
        if (root == null) {
            return;
        }
        int loaded = 0;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            String worldName = s.getString("world");
            World world = worldName == null ? null : Bukkit.getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("Crash site " + shortId(id) + ": world missing (" + worldName + ")");
                continue;
            }
            Location center = new Location(world, s.getDouble("cx"), s.getDouble("cy"), s.getDouble("cz"));
            Location barrel = new Location(world, s.getDouble("bx"), s.getDouble("by"), s.getDouble("bz"));
            String holdId = s.getString("hold-id");
            long created = s.getLong("created", System.currentTimeMillis());
            CrashSite site = new CrashSite(id, holdId, center, barrel, created);
            List<String> propStrs = s.getStringList("props");
            for (String p : propStrs) {
                try {
                    site.propIds.add(UUID.fromString(p));
                } catch (IllegalArgumentException ignored) {
                }
            }
            // Orphan / grief: barrel gone → finish the site so the crater can regen
            Block b = barrel.getBlock();
            if (b.getType() != Material.BARREL || !id.equals(siteIdOf(b))) {
                plugin.getLogger().info("Crash site " + shortId(id) + ": barrel missing — releasing hold");
                if (holdId != null && plugin.explosionRegen() != null) {
                    plugin.explosionRegen().releaseHold(holdId);
                }
                removeProps(site);
                continue;
            }
            sites.put(id, site);
            loaded++;
        }
        if (loaded > 0) {
            plugin.getLogger().info("Loaded " + loaded + " UAV crash site(s).");
            // Defer prop repair until worlds finish ticking
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                for (CrashSite site : sites.values()) {
                    ensureProps(site);
                }
                save();
            }, 40L);
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        int i = 0;
        for (CrashSite site : sites.values()) {
            if (site.center.getWorld() == null) {
                continue;
            }
            String path = "sites." + site.id;
            yaml.set(path + ".world", site.center.getWorld().getName());
            yaml.set(path + ".cx", site.center.getX());
            yaml.set(path + ".cy", site.center.getY());
            yaml.set(path + ".cz", site.center.getZ());
            yaml.set(path + ".bx", site.barrelLoc.getX());
            yaml.set(path + ".by", site.barrelLoc.getY());
            yaml.set(path + ".bz", site.barrelLoc.getZ());
            yaml.set(path + ".hold-id", site.holdId);
            yaml.set(path + ".created", site.createdMs);
            List<String> props = new ArrayList<>();
            for (UUID u : site.propIds) {
                props.add(u.toString());
            }
            yaml.set(path + ".props", props);
            i++;
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save crash_sites.yml: " + e.getMessage());
        }
        if (i == 0 && file.exists()) {
            // keep empty file as valid empty state
        }
    }

    private static String shortId(String id) {
        if (id == null) {
            return "?";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        tryCompleteFromInventory(event.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        // Empty check after the click mutates the barrel (same tick, after event).
        Inventory top = event.getView().getTopInventory();
        plugin.getServer().getScheduler().runTask(plugin, () -> tryCompleteFromInventory(top));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        plugin.getServer().getScheduler().runTask(plugin, () -> tryCompleteFromInventory(top));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isCrashBarrel(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        if (event.getPlayer() != null) {
            event.getPlayer().sendMessage(Component.text("Loot the UAV wreckage barrel first.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isCrashBarrel);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isCrashBarrel);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        InventoryHolder src = event.getSource().getHolder();
        if (src instanceof Barrel barrel && isCrashBarrel(barrel.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** Save + stop ambience (does not clear sites from disk). */
    public void shutdown() {
        if (ambienceTask != null) {
            ambienceTask.cancel();
            ambienceTask = null;
        }
        save();
        if (plugin.explosionRegen() != null) {
            plugin.explosionRegen().persistHolds();
        }
        sites.clear();
    }

    public record CrashSiteView(String id, Location center, Location barrel, long createdMs,
                                int holdBlocks, int props) {
        public String shortId() {
            return id.length() <= 8 ? id : id.substring(0, 8);
        }

        public String worldName() {
            return center != null && center.getWorld() != null
                    ? center.getWorld().getName()
                    : "?";
        }
    }

    private static final class CrashSite {
        final String id;
        final String holdId;
        final Location center;
        final Location barrelLoc;
        final long createdMs;
        final List<UUID> propIds = new ArrayList<>();

        CrashSite(String id, String holdId, Location center, Location barrelLoc, long createdMs) {
            this.id = id;
            this.holdId = holdId;
            this.center = center;
            this.barrelLoc = barrelLoc;
            this.createdMs = createdMs;
        }
    }
}
