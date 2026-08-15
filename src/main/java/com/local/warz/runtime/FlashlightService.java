package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.util.LaserBeams;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Light;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handheld + gun-mounted flashlight: toggled with right-click (sneak+RMB on guns),
 * projects a look-direction spotlight pool biased forward along the aim ray.
 */
public final class FlashlightService implements Listener {
    private static final double RANGE = 28.0;
    private static final int CORE_LEVEL = 14;
    private static final int RING_LEVEL = 10;
    private static final int EDGE_LEVEL = 6;

    private final WarzPlugin plugin;
    private final Map<UUID, Set<Block>> activeLights = new ConcurrentHashMap<>();
    private final Map<UUID, Long> toggleCooldownMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> inventoryClickMs = new ConcurrentHashMap<>();
    private BukkitTask task;
    private int strobePhase;

    public FlashlightService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // Old path planted vanilla LIGHT blocks along the aim ray. That reads as
        // Minecraft blobs and fights Complementary. MineniteClient draws a real
        // look-cone instead, so leftover blocks are just swept.
        for (UUID id : activeLights.keySet().toArray(new UUID[0])) {
            clear(id);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : activeLights.keySet().toArray(new UUID[0])) {
            clear(id);
        }
        toggleCooldownMs.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            inventoryClickMs.put(player.getUniqueId(), System.currentTimeMillis());
            Bukkit.getScheduler().runTask(plugin, () -> {
                clearEmptyStacks(player);
                player.updateInventory();
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        inventoryClickMs.put(player.getUniqueId(), System.currentTimeMillis());
        Bukkit.getScheduler().runTask(plugin, () -> {
            clearEmptyStacks(player);
            player.updateInventory();
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // Only process the hand that actually holds the flashlight (avoids double-toggle).
        EquipmentSlot hand = event.getHand();
        if (hand == null) {
            return;
        }
        Player player = event.getPlayer();
        if (plugin.bigDrone() != null && plugin.bigDrone().isPiloting(player)) {
            return;
        }
        // CardForge still fires interact while a slot is being clicked. That is
        // how an offhand light kept toggling from the old hotbar cell.
        Long invAt = inventoryClickMs.get(player.getUniqueId());
        if (invAt != null && System.currentTimeMillis() - invAt < 400L) {
            return;
        }
        ItemStack stack = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        // Handheld only here — gun-mounted light/laser toggles via Z (PeqService).
        if (!plugin.items().isFlashlight(stack)) {
            return;
        }
        event.setCancelled(true);

        if (!tryToggle(player, stack, hand == EquipmentSlot.OFF_HAND)) {
            return;
        }
    }

    /**
     * Toggle a gun-mounted flashlight (called from {@link GunListener} on sneak+RMB).
     *
     * @return true if handled
     */
    public boolean tryToggleGunFlashlight(Player player, ItemStack gun) {
        if (player == null || gun == null || !plugin.items().hasFlashlightMod(gun)) {
            return false;
        }
        return tryToggle(player, gun, false);
    }

    private boolean tryToggle(Player player, ItemStack stack, boolean offHand) {
        long now = System.currentTimeMillis();
        Long last = toggleCooldownMs.get(player.getUniqueId());
        if (last != null && now - last < 250L) {
            return true;
        }
        toggleCooldownMs.put(player.getUniqueId(), now);

        boolean next = !plugin.items().isFlashlightOn(stack);
        plugin.items().setFlashlightOn(stack, next);
        writeFlashlight(player, stack, offHand);
        player.sendActionBar(net.kyori.adventure.text.Component.text(
                next ? "Flashlight ON" : "Flashlight OFF",
                next ? net.kyori.adventure.text.format.NamedTextColor.YELLOW
                        : net.kyori.adventure.text.format.NamedTextColor.GRAY));
        if (!next) {
            clear(player.getUniqueId());
        }
        return true;
    }

    /** Write the toggled stack only into a slot that already holds a flashlight. */
    private void writeFlashlight(Player player, ItemStack stack, boolean preferOffHand) {
        PlayerInventory inv = player.getInventory();
        if (preferOffHand && plugin.items().isFlashlight(inv.getItemInOffHand())) {
            inv.setItemInOffHand(stack);
            return;
        }
        if (!preferOffHand && plugin.items().isFlashlight(inv.getItemInMainHand())) {
            inv.setItemInMainHand(stack);
            return;
        }
        if (plugin.items().isFlashlight(inv.getItemInOffHand())) {
            inv.setItemInOffHand(stack);
            return;
        }
        if (plugin.items().isFlashlight(inv.getItemInMainHand())) {
            inv.setItemInMainHand(stack);
            return;
        }
        for (int i = 0; i < 36; i++) {
            if (plugin.items().isFlashlight(inv.getItem(i))) {
                inv.setItem(i, stack);
                return;
            }
        }
    }

    private static void clearEmptyStacks(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && (it.getType().isAir() || it.getAmount() <= 0)) {
                inv.setItem(i, null);
            }
        }
        ItemStack off = inv.getItemInOffHand();
        if (off != null && (off.getType().isAir() || off.getAmount() <= 0)) {
            inv.setItemInOffHand(null);
        }
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && (cursor.getType().isAir() || cursor.getAmount() <= 0)) {
            player.setItemOnCursor(null);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        clear(id);
        toggleCooldownMs.remove(id);
        inventoryClickMs.remove(id);
    }

    private void tick() {
        strobePhase = (strobePhase + 1) % 4;
        boolean strobeLit = strobePhase < 2;
        Set<UUID> live = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.bigDrone() != null && plugin.bigDrone().isPiloting(player)) {
                clear(player.getUniqueId());
                continue;
            }
            LightKind kind = activeLightKind(player);
            if (kind == LightKind.NONE) {
                clear(player.getUniqueId());
                continue;
            }
            if (kind == LightKind.STROBE && !strobeLit) {
                clear(player.getUniqueId());
                continue;
            }
            live.add(player.getUniqueId());
            Location eye = player.getEyeLocation();
            Vector dir = eye.getDirection();
            if (dir.lengthSquared() < 1.0e-6) {
                continue;
            }
            dir.normalize();

            // Start a bit ahead of the eyes so the pool sits forward of the muzzle / hand.
            Location origin = eye.clone().add(dir.clone().multiply(0.85));

            Set<Block> prev = activeLights.computeIfAbsent(player.getUniqueId(), id -> new HashSet<>());
            Set<Block> next = new HashSet<>();

            RayTraceResult hit = player.getWorld().rayTraceBlocks(origin, dir, RANGE, FluidCollisionMode.NEVER, true);
            if (hit != null && hit.getHitPosition() != null) {
                Location impact = hit.getHitPosition().toLocation(player.getWorld());
                // Bias into the space in front of the surface (more forward punch).
                impact.add(dir.clone().multiply(0.45));
                placeSpotlightPool(impact, dir, hit.getHitBlockFace(), next);
            } else {
                // Empty air / sky: throw a forward pool so the beam still reads ahead.
                Location airFocus = origin.clone().add(dir.clone().multiply(Math.min(RANGE * 0.55, 16.0)));
                placeSpotlightPool(airFocus, dir, null, next);
            }

            clearRemoved(prev, next);
            prev.clear();
            prev.addAll(next);
        }
        Iterator<UUID> it = activeLights.keySet().iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            if (!live.contains(id)) {
                LaserBeams.clearLights(activeLights.get(id));
                it.remove();
            }
        }
    }

    private enum LightKind {
        NONE, FLASH, STROBE
    }

    /**
     * Small light pool at the aim point, biased forward along look.
     * LIGHT blocks are omnidirectional, so we keep the footprint tight.
     */
    private static void placeSpotlightPool(Location center, Vector look, BlockFace face, Set<Block> next) {
        Vector forward = look.clone().normalize();

        // Core sits forward of the aim sample.
        Location core = center.clone().add(forward.clone().multiply(0.65));
        placeLight(airCell(core), CORE_LEVEL, next);

        Vector up = new Vector(0, 1, 0);
        Vector right = forward.clone().crossProduct(up);
        if (right.lengthSquared() < 1.0e-4) {
            right = forward.clone().crossProduct(new Vector(1, 0, 0));
        }
        right.normalize();
        Vector ortho = right.clone().crossProduct(forward).normalize();

        if (face != null) {
            Vector n = face.getDirection();
            if (n.lengthSquared() > 1.0e-4) {
                n.normalize();
                Vector a = n.clone().crossProduct(Math.abs(n.getY()) > 0.9 ? new Vector(1, 0, 0) : up);
                if (a.lengthSquared() > 1.0e-4) {
                    a.normalize();
                    Vector b = n.clone().crossProduct(a).normalize();
                    right = a;
                    ortho = b;
                }
            }
        }

        double[] radii = {0.85, 1.55};
        int[] levels = {RING_LEVEL, EDGE_LEVEL};
        for (int r = 0; r < radii.length; r++) {
            double radius = radii[r];
            int level = levels[r];
            int spokes = r == 0 ? 6 : 8;
            for (int i = 0; i < spokes; i++) {
                double ang = (Math.PI * 2.0 * i) / spokes;
                Vector offset = right.clone().multiply(Math.cos(ang) * radius)
                        .add(ortho.clone().multiply(Math.sin(ang) * radius))
                        .add(forward.clone().multiply(0.35));
                Location p = center.clone().add(offset);
                placeLight(airCell(p), level, next);
            }
        }

        // Extra forward throw past the aim point.
        Location ahead = center.clone().add(forward.clone().multiply(1.55));
        placeLight(airCell(ahead), RING_LEVEL, next);
        Location far = center.clone().add(forward.clone().multiply(2.4));
        placeLight(airCell(far), EDGE_LEVEL, next);
    }

    /** Prefer an air / LIGHT cell; nudge toward air if the sample landed in solid. */
    private static Block airCell(Location loc) {
        Block block = loc.getBlock();
        if (isLightable(block.getType())) {
            return block;
        }
        for (BlockFace face : new BlockFace[]{
                BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN
        }) {
            Block n = block.getRelative(face);
            if (isLightable(n.getType())) {
                return n;
            }
        }
        return block;
    }

    private static boolean isLightable(Material type) {
        return type.isAir() || type == Material.LIGHT || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }

    private LightKind activeLightKind(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (plugin.items().isFlashlight(main) && plugin.items().isFlashlightOn(main)) {
            return LightKind.FLASH;
        }
        if (plugin.items().canToggleOptic(main)) {
            PeqMode mode = plugin.items().opticMode(main);
            if (mode == PeqMode.STROBE) {
                return LightKind.STROBE;
            }
            if (mode == PeqMode.FLASH) {
                return LightKind.FLASH;
            }
        } else if (plugin.items().hasFlashlightMod(main) && plugin.items().isFlashlightOn(main)) {
            return LightKind.FLASH;
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (plugin.items().isFlashlight(off) && plugin.items().isFlashlightOn(off)) {
            return LightKind.FLASH;
        }
        return LightKind.NONE;
    }

    private static void placeLight(Block block, int level, Set<Block> next) {
        if (block == null) {
            return;
        }
        Material type = block.getType();
        if (!isLightable(type)) {
            return;
        }
        int clamped = Math.max(1, Math.min(15, level));
        if (block.getType() == Material.LIGHT && block.getBlockData() instanceof Light existing
                && existing.getLevel() == clamped) {
            next.add(block);
            return;
        }
        Light data = (Light) Material.LIGHT.createBlockData();
        data.setLevel(clamped);
        // applyPhysics=false avoids full light rebuild thrash (fuzzy textures under Iris)
        block.setBlockData(data, false);
        next.add(block);
    }

    private static void clearRemoved(Set<Block> previous, Set<Block> next) {
        if (previous == null) {
            return;
        }
        for (Block old : previous) {
            if (!next.contains(old) && old.getType() == Material.LIGHT) {
                old.setType(Material.AIR, false);
            }
        }
    }

    private void clear(UUID id) {
        Set<Block> lights = activeLights.remove(id);
        if (lights == null) {
            return;
        }
        for (Block block : lights) {
            if (block.getType() == Material.LIGHT) {
                block.setType(Material.AIR, false);
            }
        }
        lights.clear();
    }
}
