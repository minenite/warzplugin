package com.local.warz.runtime;

import com.local.warz.WarzKeys;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Candle;
import org.bukkit.block.data.type.Light;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Road flares — colored item tipped sideways on the ground (never places a torch as the visual).
 * Vanilla {@link Material#LIGHT} handles the lightmap; a matching colored torch/candle feeds
 * Complementary ACT tinted lighting. Companions get a bloom glow packet and hide the emitter mesh.
 */
public final class FlareService {
    public static final String CHANNEL = "pvpgunminus:flare";
    public static final byte ACTION_UPSERT = 1;
    public static final byte ACTION_REMOVE = 2;
    public static final byte ACTION_CLEAR_ALL = 3;
    public static final byte ACTION_FULL = 5;

    /** ~20 minutes — typical roadside flare burn time. */
    public static final int BURN_TICKS = 20 * 60 * 20;
    private static final double BURN_RADIUS = 0.95;
    private static final double BURN_RADIUS_SQ = BURN_RADIUS * BURN_RADIUS;
    private static final double BURN_Y_SLACK = 1.35;
    private static final int FLARE_FIRE_TICKS = 25;

    private final WarzPlugin plugin;
    private final Map<Integer, Flare> flares = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Set<UUID> flareBurning = ConcurrentHashMap.newKeySet();

    public FlareService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerChannel() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    public void unregisterChannel() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    public Flare spawn(Location at, Player thrower) {
        return spawn(at, thrower, FlareColor.RED);
    }

    public Flare spawn(Location at, Player thrower, FlareColor color) {
        if (at == null || at.getWorld() == null) {
            return null;
        }
        FlareColor c = color != null ? color : FlareColor.RED;
        Location snap = at.clone();
        snapNearGround(snap);
        snap.setX(Math.floor(snap.getX()) + 0.5);
        snap.setZ(Math.floor(snap.getZ()) + 0.5);
        snap.setY(Math.floor(snap.getY()) + 0.06);

        int id = nextId.getAndIncrement();
        World world = snap.getWorld();
        float yaw = ThreadLocalRandom.current().nextFloat() * 360f;

        ItemDisplay display = spawnVisual(world, snap, id, yaw, c);

        Set<Block> lights = new HashSet<>();
        Block colorLight = null;
        int level = 15;
        colorLight = placeColorLight(snap, c);
        applyLights(snap, lights, level, colorLight);

        Flare flare = new Flare(id, world.getUID(),
                snap.getX(), snap.getY(), snap.getZ(), yaw, c,
                display.getUniqueId(), lights, colorLight,
                thrower != null ? thrower.getUniqueId() : null);
        flare.lastLightLevel = level;
        flares.put(id, flare);

        world.playSound(snap, Sound.ITEM_FLINTANDSTEEL_USE, 1.0f, 0.85f);
        world.playSound(snap, Sound.BLOCK_FIRE_AMBIENT, 0.85f, 1.05f);
        burst(world, tipOf(flare), c);
        broadcastFull();

        plugin.getLogger().info("Road flare #" + id + " lit (" + c.id() + ")"
                + (thrower != null ? " by " + thrower.getName() : "")
                + " burn=" + (BURN_TICKS / 20) + "s active=" + flares.size());
        return flare;
    }

    public void syncViewer(Player viewer) {
        if (viewer == null || plugin.companions() == null || !plugin.companions().hasCompanion(viewer)) {
            return;
        }
        byte[] payload = encodeFull(List.copyOf(flares.values()));
        if (payload != null) {
            viewer.sendPluginMessage(plugin, CHANNEL, payload);
        }
    }

    public void tick() {
        List<Integer> doomed = new ArrayList<>();
        for (Flare f : flares.values()) {
            try {
                tickOne(f, doomed);
            } catch (Throwable t) {
                plugin.getLogger().warning("Flare #" + f.id + " tick failed: " + t.getMessage());
            }
        }
        for (Integer id : doomed) {
            extinguish(id);
        }
        tickContactFire();
        if (!flares.isEmpty() && plugin.getServer().getCurrentTick() % 40 == 0) {
            broadcastFull();
        }
    }

    private void tickOne(Flare f, List<Integer> doomed) {
        f.age++;
        World world = plugin.getServer().getWorld(f.worldId);
        if (world == null || f.age >= BURN_TICKS) {
            doomed.add(f.id);
            return;
        }

        ItemDisplay display = keepDisplay(world, f);
        if (display == null) {
            display = spawnVisual(world, new Location(world, f.x, f.y, f.z), f.id, f.yaw, f.color);
            f.displayId = display.getUniqueId();
        }
        if (f.colorLight == null || !isOurColorLight(f.colorLight, f.color)) {
            f.colorLight = placeColorLight(new Location(world, f.x, f.y, f.z), f.color);
        }

        Location tip = tipOf(f);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        FlareColor c = f.color;

        // Hot core + colored bloom fuel for Iris
        fx(world, c.tipFlameParticle(), tip, 8, 0.06, 0.1, 0.06, 0.012);
        fx(world, Particle.CAMPFIRE_COSY_SMOKE, tip.clone().add(0, 0.25, 0),
                2 + rng.nextInt(2), 0.08, 0.28, 0.08, 0.006);
        world.spawnParticle(Particle.DUST, tip.clone().add(0, 0.12, 0), 14,
                0.14, 0.22, 0.14, 0.0,
                new Particle.DustOptions(c.tipColor(), 1.35f), true);
        world.spawnParticle(Particle.DUST, tip.clone().add(0, 0.32, 0), 10,
                0.18, 0.3, 0.18, 0.0,
                new Particle.DustOptions(c.smokeColor(), 1.05f), true);
        world.spawnParticle(Particle.END_ROD, tip.clone().add(0, 0.2, 0), 2,
                0.08, 0.16, 0.08, 0.01);
        if (rng.nextInt(2) == 0) {
            fx(world, Particle.SMOKE, tip.clone().add(0, 0.35, 0), 2, 0.07, 0.18, 0.07, 0.012);
        }
        if (c.useFlame() && rng.nextInt(8) == 0) {
            fx(world, Particle.LAVA, tip, 1, 0.04, 0.05, 0.04, 0.0);
        }
        if (rng.nextInt(18) == 0) {
            world.spawnParticle(Particle.FLASH, tip.clone().add(0, 0.15, 0), 1, 0, 0, 0, 0);
        }

        if (f.age % 3 == 0) {
            int level = flickerLevel(f.age);
            if (level != f.lastLightLevel) {
                applyLights(new Location(world, f.x, f.y, f.z), f.lights, level, f.colorLight);
                f.lastLightLevel = level;
            }
            try {
                int b = Math.max(10, Math.min(15, level));
                display.setBrightness(new Display.Brightness(b, b));
            } catch (Throwable ignored) {
                // ignore
            }
        }

        if (f.age % 28 == 0) {
            world.playSound(tip, Sound.BLOCK_FIRE_AMBIENT, 0.35f + rng.nextFloat() * 0.2f,
                    1.05f + rng.nextFloat() * 0.3f);
        }
        if (f.age > BURN_TICKS - 20 * 30) {
            fx(world, Particle.SMOKE, tip, 3, 0.08, 0.16, 0.08, 0.015);
        }
    }

    private void tickContactFire() {
        Set<UUID> onFlareNow = new HashSet<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR || !player.isValid()) {
                continue;
            }
            if (standingOnFlare(player)) {
                onFlareNow.add(player.getUniqueId());
                player.setFireTicks(FLARE_FIRE_TICKS);
                flareBurning.add(player.getUniqueId());
            }
        }
        for (UUID id : List.copyOf(flareBurning)) {
            if (onFlareNow.contains(id)) {
                continue;
            }
            flareBurning.remove(id);
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && player.isValid() && player.getFireTicks() > 0) {
                player.setFireTicks(0);
            }
        }
    }

    private boolean standingOnFlare(Player player) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return false;
        }
        UUID worldId = world.getUID();
        double px = loc.getX();
        double py = loc.getY();
        double pz = loc.getZ();
        for (Flare f : flares.values()) {
            if (!f.worldId.equals(worldId)) {
                continue;
            }
            double dx = px - f.x;
            double dz = pz - f.z;
            if (dx * dx + dz * dz > BURN_RADIUS_SQ) {
                continue;
            }
            if (Math.abs(py - f.y) <= BURN_Y_SLACK) {
                return true;
            }
        }
        return false;
    }

    public void clearAll() {
        for (Integer id : List.copyOf(flares.keySet())) {
            extinguish(id);
        }
        for (UUID id : List.copyOf(flareBurning)) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && player.getFireTicks() > 0) {
                player.setFireTicks(0);
            }
        }
        flareBurning.clear();
        broadcastRaw(encodeClearAll());
    }

    public int activeCount() {
        return flares.size();
    }

    private void extinguish(int id) {
        Flare f = flares.remove(id);
        if (f == null) {
            return;
        }
        World world = plugin.getServer().getWorld(f.worldId);
        if (world == null) {
            broadcastRaw(encodeRemove(id));
            return;
        }
        var ent = world.getEntity(f.displayId);
        if (ent != null) {
            ent.remove();
        }
        for (Block light : f.lights) {
            if (light.getType() == Material.LIGHT) {
                light.setType(Material.AIR, false);
            }
        }
        f.lights.clear();
        if (f.colorLight != null && isOurColorLight(f.colorLight, f.color)) {
            f.colorLight.setType(Material.AIR, false);
        }
        f.colorLight = null;
        Location at = new Location(world, f.x, f.y + 0.2, f.z);
        world.playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.55f, 1.1f);
        fx(world, Particle.SMOKE, at, 12, 0.18, 0.22, 0.18, 0.025);
        broadcastRaw(encodeRemove(id));
    }

    private ItemDisplay spawnVisual(World world, Location snap, int id, float yaw, FlareColor color) {
        FlareColor c = color != null ? color : FlareColor.RED;
        ItemStack visual = new ItemStack(c.material(), 1);
        ItemMeta meta = visual.getItemMeta();
        meta.getPersistentDataContainer().set(plugin.items().flareKey(), PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(plugin.items().flareColorKey(), PersistentDataType.STRING, c.id());
        meta.getPersistentDataContainer().set(
                WarzKeys.of("flare_entity"),
                PersistentDataType.INTEGER, id);
        visual.setItemMeta(meta);

        Location spawnAt = snap.clone();
        spawnAt.setYaw(yaw);
        spawnAt.setPitch(0f);

        return world.spawn(spawnAt, ItemDisplay.class, d -> {
            d.setItemStack(visual);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setGravity(false);
            d.setPersistent(false);
            d.setInvulnerable(true);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(
                    new Vector3f(0f, 0.05f, 0f),
                    new AxisAngle4f((float) Math.toRadians(90), 1f, 0f, 0f),
                    new Vector3f(1.35f, 1.35f, 1.35f),
                    new AxisAngle4f()
            ));
            d.setInterpolationDuration(0);
            d.setTeleportDuration(0);
        });
    }

    private ItemDisplay keepDisplay(World world, Flare f) {
        var ent = world.getEntity(f.displayId);
        if (!(ent instanceof ItemDisplay display) || !display.isValid()) {
            return null;
        }
        Location want = new Location(world, f.x, f.y, f.z, f.yaw, 0f);
        if (display.getLocation().distanceSquared(want) > 0.0025) {
            display.teleport(want);
        }
        return display;
    }

    private static void burst(World world, Location tip, FlareColor c) {
        fx(world, c.tipFlameParticle(), tip, 22, 0.1, 0.12, 0.1, 0.018);
        world.spawnParticle(Particle.DUST, tip, 28, 0.2, 0.24, 0.2, 0.0,
                new Particle.DustOptions(c.tipColor(), 1.6f), true);
        world.spawnParticle(Particle.END_ROD, tip, 8, 0.12, 0.18, 0.12, 0.02);
        world.spawnParticle(Particle.FLASH, tip, 1, 0, 0, 0, 0);
    }

    private static void fx(World world, Particle particle, Location loc,
                           int count, double ox, double oy, double oz, double extra) {
        world.spawnParticle(particle, loc, count, ox, oy, oz, extra, null, true);
    }

    private static int flickerLevel(int age) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double wave = Math.sin(age * 0.55) * 2.2
                + Math.sin(age * 1.37) * 1.4
                + Math.sin(age * 2.91) * 0.8;
        int level = (int) Math.round(13.2 + wave + (rng.nextDouble() - 0.5) * 2.2);
        return Math.max(10, Math.min(15, level));
    }

    private Block placeColorLight(Location origin, FlareColor color) {
        Block at = preferAir(origin.getBlock());
        if (!isLightable(at.getType()) && at.getType() != color.colorLightBlock()) {
            return null;
        }
        Material mat = color.colorLightBlock();
        if (color.colorLightIsCandle()) {
            at.setType(mat, false);
            if (at.getBlockData() instanceof Candle candle) {
                candle.setLit(true);
                candle.setCandles(candle.getMaximumCandles());
                at.setBlockData(candle, false);
            }
        } else {
            at.setType(mat, false);
        }
        return at;
    }

    private static boolean isOurColorLight(Block block, FlareColor color) {
        if (block == null || color == null) {
            return false;
        }
        if (block.getType() != color.colorLightBlock()) {
            return false;
        }
        if (color.colorLightIsCandle() && block.getBlockData() instanceof Candle candle) {
            return candle.isLit();
        }
        return true;
    }

    private void applyLights(Location origin, Set<Block> lights, int coreLevel, Block colorLight) {
        Set<Block> next = new HashSet<>();
        Block base = origin.getBlock();
        // Keep vanilla lightmap strong even when the colored emitter is a weak torch/candle
        Block up = preferAir(base.getRelative(BlockFace.UP));
        if (colorLight == null || !up.equals(colorLight)) {
            tryPlaceLight(up, coreLevel, next);
        }
        Block side = preferAir(base.getRelative(BlockFace.NORTH));
        if (colorLight == null || !side.equals(colorLight)) {
            tryPlaceLight(side, Math.max(7, coreLevel - 4), next);
        }
        for (Block old : lights) {
            if (!next.contains(old) && old.getType() == Material.LIGHT) {
                if (colorLight == null || !old.equals(colorLight)) {
                    old.setType(Material.AIR, false);
                }
            }
        }
        lights.clear();
        lights.addAll(next);
    }

    private static Block preferAir(Block block) {
        if (isLightable(block.getType()) || isColorEmitter(block.getType())) {
            return block;
        }
        Block up = block.getRelative(BlockFace.UP);
        if (isLightable(up.getType()) || isColorEmitter(up.getType())) {
            return up;
        }
        return block;
    }

    private static boolean isColorEmitter(Material type) {
        return type == Material.REDSTONE_TORCH || type == Material.SOUL_TORCH
                || type == Material.LIME_CANDLE || type == Material.RED_CANDLE
                || type == Material.BLUE_CANDLE;
    }

    private static boolean isLightable(Material type) {
        return type.isAir() || type == Material.LIGHT || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }

    private static void tryPlaceLight(Block block, int level, Set<Block> lights) {
        if (block == null || !isLightable(block.getType())) {
            return;
        }
        int clamped = Math.max(0, Math.min(15, level));
        if (block.getType() == Material.LIGHT && block.getBlockData() instanceof Light existing
                && existing.getLevel() == clamped) {
            lights.add(block);
            return;
        }
        block.setType(Material.LIGHT, false);
        if (block.getBlockData() instanceof Light light) {
            light.setLevel(clamped);
            block.setBlockData(light, false);
        }
        lights.add(block);
    }

    private static Location tipOf(Flare f) {
        World world = org.bukkit.Bukkit.getWorld(f.worldId);
        return new Location(world, f.x, f.y + 0.18, f.z);
    }

    private static void snapNearGround(Location at) {
        if (at.getWorld() == null) {
            return;
        }
        var block = at.getBlock();
        for (int i = 0; i < 16 && block.getType().isAir(); i++) {
            at.add(0, -0.5, 0);
            block = at.getBlock();
        }
        if (!block.getType().isAir()) {
            at.setY(block.getY() + 1.0);
        }
    }

    private void broadcastFull() {
        byte[] payload = encodeFull(List.copyOf(flares.values()));
        if (payload != null) {
            broadcastRaw(payload);
        }
    }

    private void broadcastRaw(byte[] payload) {
        if (payload == null || plugin.companions() == null) {
            return;
        }
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (plugin.companions().hasCompanion(viewer)) {
                viewer.sendPluginMessage(plugin, CHANNEL, payload);
            }
        }
    }

    private static byte[] encodeRemove(int id) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(CompanionClients.PROTOCOL);
            out.writeByte(ACTION_REMOVE);
            out.writeInt(id);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] encodeClearAll() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(2);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(CompanionClients.PROTOCOL);
            out.writeByte(ACTION_CLEAR_ALL);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] encodeFull(List<Flare> list) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16 + list.size() * 28);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(CompanionClients.PROTOCOL);
            out.writeByte(ACTION_FULL);
            out.writeShort(list.size());
            for (Flare f : list) {
                out.writeInt(f.id);
                out.writeByte(f.color.colorId());
                out.writeFloat((float) f.x);
                out.writeFloat((float) (f.y + 0.18));
                out.writeFloat((float) f.z);
                out.writeByte(Math.max(8, Math.min(15, f.lastLightLevel <= 0 ? 14 : f.lastLightLevel)));
                out.writeInt(f.color.bloomRgb());
                if (f.colorLight != null) {
                    out.writeByte(1);
                    out.writeInt(f.colorLight.getX());
                    out.writeInt(f.colorLight.getY());
                    out.writeInt(f.colorLight.getZ());
                } else {
                    out.writeByte(0);
                }
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    public static final class Flare {
        final int id;
        final UUID worldId;
        final double x, y, z;
        final float yaw;
        final FlareColor color;
        UUID displayId;
        final Set<Block> lights;
        Block colorLight;
        final UUID throwerId;
        int age;
        int lastLightLevel = -1;

        Flare(int id, UUID worldId, double x, double y, double z, float yaw, FlareColor color,
              UUID displayId, Set<Block> lights, Block colorLight, UUID throwerId) {
            this.id = id;
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.color = color != null ? color : FlareColor.RED;
            this.displayId = displayId;
            this.lights = lights;
            this.colorLight = colorLight;
            this.throwerId = throwerId;
        }
    }
}
