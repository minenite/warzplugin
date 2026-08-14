package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-authoritative smoke clouds. Synced to Fabric companions for NVG/thermal optics.
 */
public final class SmokeService {
    public static final String CHANNEL = "pvpgunminus:smoke";

    public static final byte ACTION_UPSERT = 1;
    public static final byte ACTION_REMOVE = 2;
    public static final byte ACTION_CLEAR_ALL = 3;
    public static final byte ACTION_SYNC = 4;
    /** Full authoritative snapshot — client replaces its entire cloud map. */
    public static final byte ACTION_FULL = 5;

    private final WarzPlugin plugin;
    private final Map<Integer, Cloud> clouds = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private float windHeading = (float) (Math.PI * 0.25);
    private int tickCounter;

    public SmokeService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerChannel() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    public void unregisterChannel() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    public void clearAll() {
        clouds.clear();
        broadcastRaw(encodeClearAll());
    }

    public Cloud spawn(Location at, SmokeType type, Player thrower) {
        if (at == null || at.getWorld() == null || type == null) {
            return null;
        }
        int id = nextId.getAndIncrement();
        double gy = at.getY();
        Cloud cloud = new Cloud(id, type, at.getWorld().getUID(),
                at.getX(), gy, at.getZ(), gy);
        clouds.put(id, cloud);
        at.getWorld().playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.9f, 0.65f);
        at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 0.7f, 0.85f);
        spawnParticles(at.getWorld(), cloud, true);
        broadcastFull();
        plugin.getLogger().info("Smoke #" + id + " " + type.plainName()
                + " active=" + clouds.size()
                + (thrower != null ? " by " + thrower.getName() : ""));
        return cloud;
    }

    public int activeCount() {
        return clouds.size();
    }

    /**
     * Peak IR / thermal obscuration at a point (0…1) from IR, thermal, or multispectral smoke.
     * Used to deny Javelin LOAL through / into obscurants.
     */
    public float irObscurationAt(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return 0f;
        }
        UUID worldId = loc.getWorld().getUID();
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();
        float peak = 0f;
        for (Cloud c : clouds.values()) {
            if (!worldId.equals(c.worldId) || c.density < 0.08f) {
                continue;
            }
            // Only IR-relevant smokes defeat seekers
            if (!c.type.irPrimary() && c.type.thermalBlock() < 0.35f) {
                continue;
            }
            double dx = x - c.x;
            double dy = y - c.y;
            double dz = z - c.z;
            double r = Math.max(1.0, c.radius);
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > r * r) {
                continue;
            }
            float falloff = 1f - (float) (Math.sqrt(d2) / r);
            float strength = c.density * Math.max(c.type.thermalBlock(), c.type.irPrimary() ? 0.85f : 0f) * falloff;
            if (strength > peak) {
                peak = strength;
            }
        }
        return Math.min(1f, peak);
    }

    /** True when IR seekers should fail at this point. */
    public boolean blocksIrSeekers(Location loc) {
        return irObscurationAt(loc) >= 0.35f;
    }

    public void syncViewer(Player viewer) {
        if (viewer == null || plugin.companions() == null || !plugin.companions().hasCompanion(viewer)) {
            return;
        }
        byte[] payload = encodeFull(List.copyOf(clouds.values()));
        if (payload != null) {
            viewer.sendPluginMessage(plugin, CHANNEL, payload);
        }
    }

    private void broadcastFull() {
        byte[] payload = encodeFull(List.copyOf(clouds.values()));
        if (payload != null) {
            broadcastRaw(payload);
        }
    }

    public void tick() {
        tickCounter++;
        // Mild heading wander; sandstorm pushes wind harder
        float windMag = plugin.weather() != null ? plugin.weather().wind() : 0.25f;
        windHeading += (ThreadLocalRandom.current().nextFloat() - 0.5f) * 0.012f;
        float wx = (float) Math.cos(windHeading) * windMag;
        float wz = (float) Math.sin(windHeading) * windMag;

        List<Integer> doomed = new ArrayList<>();
        for (Cloud c : clouds.values()) {
            c.age++;
            int lifeTicks = Math.max(1, c.type.lifeTicks());
            float life = c.age / (float) lifeTicks;

            // Peak quickly in real time (ticks), not as a huge fraction of long-lived smokes.
            // buildRate >1 = faster; <1 = slower — but all types are opaque within ~1–3s.
            float buildTicks = MthClamp(55f / Math.max(0.35f, c.type.buildRate()), 12f, 70f);
            // Stay at full density for most of the life, fade only near the end
            float fadeStart = MthClamp(1f - 0.22f * c.type.dissipateRate(), 0.72f, 0.92f);
            if (c.type == SmokeType.QUICK) {
                buildTicks = 8f;
                fadeStart = 0.55f;
            } else if (c.type == SmokeType.PERSISTENT) {
                buildTicks = 40f;
                fadeStart = 0.88f;
            }

            float dens;
            if (c.age < buildTicks) {
                dens = c.type.densityMax() * (c.age / buildTicks);
            } else if (life < fadeStart) {
                dens = c.type.densityMax();
            } else {
                float fadeT = (life - fadeStart) / Math.max(0.01f, 1f - fadeStart);
                float fade = MthClamp(fadeT * Math.min(1.1f, c.type.dissipateRate()), 0f, 1f);
                dens = c.type.densityMax() * (1f - fade);
            }
            dens = MthClamp(dens, 0f, 1f);
            c.density = dens;

            float growT = MthClamp(c.age / Math.max(10f, buildTicks * 0.85f), 0f, 1f);
            // Start big immediately so the bank reads as a large cloud
            float startR = 0.72f;
            c.radius = c.type.maxRadius() * (startR + (1f - startR) * growT);

            float maxLift = switch (c.type) {
                case SIGNAL -> 9.0f;
                case PERSISTENT, BLACK, MULTISPECTRAL -> 7.0f;
                case QUICK -> 5.0f;
                default -> 6.0f;
            };
            maxLift = Math.min(maxLift, 2.2f + c.type.riseHeight() * 0.5f);
            float liftT = MthClamp(c.age / 18f, 0f, 1f);
            // Fast pop-up then settle slightly lower while staying on the ground sheet
            float pop = maxLift * (1f - (1f - liftT) * (1f - liftT));
            float settle = life > 0.15f ? MthClamp((life - 0.15f) * 0.35f, 0f, 0.35f) : 0f;
            float bob = (float) Math.sin(c.age * 0.07 + c.id) * 0.1f * windMag;
            c.y = c.groundY + pop * (1f - settle) + bob;

            // Horizontal wind only
            float drift = 0.045f * c.type.windFactor();
            c.x += wx * drift;
            c.z += wz * drift;
            if (c.type == SmokeType.WIND || windMag > 0.6f) {
                c.x += wx * drift * 0.55f;
                c.z += wz * drift * 0.55f;
            }
            if (c.y > c.groundY + maxLift + 0.35f) {
                c.y = c.groundY + maxLift;
            }

            World world = Bukkit.getWorld(c.worldId);
            // Only cull when lifetime ends or almost fully faded near the end
            if (world == null || c.age >= lifeTicks || (life > 0.9f && dens < 0.03f)) {
                doomed.add(c.id);
                continue;
            }
            // Heavy particle bank every tick while dense
            if (c.density > 0.05f) {
                spawnParticles(world, c, false);
            }
        }

        boolean removed = false;
        for (Integer id : doomed) {
            clouds.remove(id);
            removed = true;
        }
        // One FULL snapshot keeps every concurrent cloud in lockstep on companions
        if (removed || (!clouds.isEmpty() && tickCounter % 5 == 0)) {
            broadcastFull();
        }
    }

    private void spawnParticles(World world, Cloud c, boolean birthBurst) {
        Location loc = new Location(world, c.x, c.y, c.z);
        float early = MthClamp(1f - c.age / 40f, 0.4f, 1f);
        int active = Math.max(1, clouds.size());
        // Lots of particles — share budget lightly across concurrent clouds
        float share = birthBurst ? 1.35f : (1f / (float) Math.sqrt(active));
        int count = Math.max(55, (int) ((110 + c.density * 140 + c.radius * 10f) * (0.8f + early * 0.45f) * share));
        if (birthBurst) {
            count = Math.max(count, 140);
        }
        double spread = Math.max(2.2, c.radius * 0.72);
        double rise = Math.max(1.8, Math.min(6.5, (1.4 + c.type.riseHeight() * 0.2) * (0.8f + early * 0.85f)));
        float size = Math.max(3.2f, Math.min(8.0f, c.radius * 0.42f * (0.95f + early * 0.3f)));
        Color color = Color.fromRGB(c.type.rgb());
        if (c.type.irPrimary()) {
            count = Math.max(24, count / 2);
            color = Color.fromRGB(0xA0A8B0);
            size *= 0.8f;
        }
        Particle.DustOptions dust = new Particle.DustOptions(color, size);
        Particle.DustOptions dustFine = new Particle.DustOptions(color, size * 0.55f);
        Particle.DustOptions dustHuge = new Particle.DustOptions(color, size * 1.35f);

        // Force=true so clients always see them
        world.spawnParticle(Particle.DUST, loc, count, spread, rise * 0.5, spread, 0.0, dust, true);
        world.spawnParticle(Particle.DUST, loc.clone().add(0, rise * 0.4, 0), Math.max(20, count / 2),
                spread * 0.65, rise * (0.55 + early * 0.45), spread * 0.65, 0.0, dustFine, true);
        world.spawnParticle(Particle.DUST, loc, Math.max(16, count / 3),
                spread * 0.85, rise * 0.35, spread * 0.85, 0.0, dustHuge, true);

        if (c.density > 0.06f && !c.type.irPrimary()) {
            int cosy = Math.max(18, (int) (count * 0.55));
            // Paper 26: Void-typed particles need null data before force=true
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc, cosy,
                    spread * 0.7, rise * 0.5 * early + 0.45, spread * 0.7, 0.012, null, true);
            world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, loc, Math.max(10, count / 4),
                    spread * 0.35, rise * (0.6 + early * 0.45), spread * 0.35, 0.008, null, true);
            world.spawnParticle(Particle.LARGE_SMOKE, loc, Math.max(12, count / 4),
                    spread * 0.5, rise * 0.4, spread * 0.5, 0.025, null, true);
            world.spawnParticle(Particle.CLOUD, loc, Math.max(14, count / 3),
                    spread * 0.55, rise * 0.55, spread * 0.55, 0.015, null, true);
            if (early > 0.45f || birthBurst) {
                world.spawnParticle(Particle.WHITE_ASH, loc, Math.max(20, count / 3),
                        spread * 0.8, rise * 0.7, spread * 0.8, 0.02, null, true);
            }
        } else if (c.type.irPrimary() && c.density > 0.08f) {
            // Subtle ash for IR so NVG still has something in the world
            world.spawnParticle(Particle.WHITE_ASH, loc, Math.max(12, count / 3),
                    spread * 0.6, rise * 0.45, spread * 0.6, 0.01, null, true);
        }
    }

    private static float MthClamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void broadcastUpsert(Cloud cloud) {
        broadcastFull();
    }

    private void broadcastRemove(int id) {
        broadcastFull();
    }

    private void broadcastRaw(byte[] payload) {
        if (payload == null || plugin.companions() == null) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (plugin.companions().hasCompanion(viewer)) {
                viewer.sendPluginMessage(plugin, CHANNEL, payload);
            }
        }
    }

    private static byte[] encodeFull(List<Cloud> list) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1);
            out.writeByte(ACTION_FULL);
            out.writeShort(Math.min(64, list.size()));
            int n = 0;
            for (Cloud c : list) {
                if (n++ >= 64) {
                    break;
                }
                writeCloud(out, c);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] encodeUpsert(Cloud c) {
        return encodeFull(List.of(c));
    }

    private static byte[] encodeRemove(int id) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1);
            out.writeByte(ACTION_REMOVE);
            out.writeInt(id);
            return bytes.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] encodeClearAll() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1);
            out.writeByte(ACTION_CLEAR_ALL);
            return bytes.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] encodeSync(List<Cloud> list) {
        return encodeFull(list);
    }

    private static void writeCloud(DataOutputStream out, Cloud c) throws IOException {
        out.writeInt(c.id);
        out.writeByte(c.type.id());
        out.writeFloat((float) c.x);
        out.writeFloat((float) c.y);
        out.writeFloat((float) c.z);
        out.writeFloat(c.radius);
        out.writeFloat(c.density);
        out.writeShort(Math.min(Short.MAX_VALUE, c.age));
        out.writeShort(Math.min(Short.MAX_VALUE, c.type.lifeTicks()));
        out.writeByte(c.type.irPrimary() ? 1 : 0);
        out.writeFloat(c.type.nvgWash());
        out.writeFloat(c.type.thermalBlock());
        out.writeInt(c.type.rgb());
    }

    public static final class Cloud {
        final int id;
        final SmokeType type;
        final UUID worldId;
        final double groundY;
        double x, y, z;
        float radius;
        float density;
        int age;

        Cloud(int id, SmokeType type, UUID worldId, double x, double y, double z, double groundY) {
            this.id = id;
            this.type = type;
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.groundY = groundY;
            this.radius = type.maxRadius() * 0.2f;
            this.density = 0.05f;
            this.age = 0;
        }
    }
}
