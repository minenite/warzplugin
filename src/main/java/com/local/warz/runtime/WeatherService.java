package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Server-authoritative tactical weather (sandstorm, …). Synced to Fabric companions.
 */
public final class WeatherService {
    public static final String CHANNEL = "pvpgunminus:weather";

    public static final byte EVENT_CLEAR = 0;
    public static final byte EVENT_SANDSTORM = 1;

    private static final int RAMP_TICKS = 80;

    private final WarzPlugin plugin;
    private byte event = EVENT_CLEAR;
    private float intensity;
    private float wind = 0.35f;
    private int remainingTicks = -1;
    private int rampTicks;
    private float rampFrom;
    private float rampTo;
    private int broadcastCooldown;
    private long lastAnnounceMs;

    public WeatherService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerChannel() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    public void unregisterChannel() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    public byte event() {
        return event;
    }

    public float intensity() {
        return intensity;
    }

    public float wind() {
        return wind;
    }

    public String eventName() {
        return switch (event) {
            case EVENT_SANDSTORM -> "sandstorm";
            default -> "clear";
        };
    }

    public void clear(boolean announce) {
        rampFrom = intensity;
        rampTo = 0f;
        rampTicks = RAMP_TICKS;
        wind = 0.2f;
        remainingTicks = RAMP_TICKS;
        broadcastCooldown = 0;
        broadcastAll();
        if (announce) {
            announceClearing();
        }
    }

    /**
     * @param durationSeconds ≤0 = indefinite until cleared
     * @param intensity01 0…1
     */
    public void startSandstorm(int durationSeconds, float intensity01, boolean announce) {
        float i = Math.max(0.2f, Math.min(1f, intensity01));
        event = EVENT_SANDSTORM;
        rampFrom = intensity;
        rampTo = i;
        rampTicks = RAMP_TICKS;
        wind = 0.55f + i * 0.4f;
        remainingTicks = durationSeconds <= 0 ? -1 : durationSeconds * 20;
        broadcastCooldown = 0;
        broadcastAll();
        if (announce) {
            announceStorm();
        }
    }

    public void tick() {
        if (rampTicks > 0) {
            rampTicks--;
            float t = 1f - (rampTicks / (float) RAMP_TICKS);
            intensity = rampFrom + (rampTo - rampFrom) * Math.max(0f, Math.min(1f, t));
            if (rampTicks == 0) {
                intensity = rampTo;
                if (intensity <= 0.01f) {
                    event = EVENT_CLEAR;
                    intensity = 0f;
                }
            }
        }

        if (event != EVENT_CLEAR && remainingTicks >= 0 && rampTo > 0.01f) {
            // Only count down once fully ramped in (or while holding)
            if (rampTicks == 0) {
                remainingTicks--;
                if (remainingTicks <= 0) {
                    clear(true);
                    return;
                }
            }
        } else if (event != EVENT_CLEAR && remainingTicks >= 0 && rampTo <= 0.01f) {
            // clearing ramp uses remainingTicks as safety; event clears when intensity hits 0
            remainingTicks--;
        }

        if (intensity > 0.05f && event == EVENT_SANDSTORM) {
            spawnAmbientDust();
        }

        if (--broadcastCooldown <= 0) {
            broadcastCooldown = intensity > 0.01f ? 40 : 100;
            if (intensity > 0.01f || event != EVENT_CLEAR || rampTicks > 0) {
                broadcastAll();
            }
        }
    }

    public void syncPlayer(Player player) {
        sendTo(player);
    }

    public void broadcastAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendTo(player);
        }
    }

    private void sendTo(Player player) {
        if (player == null || plugin.companions() == null || !plugin.companions().hasCompanion(player)) {
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1);
            out.writeByte(event);
            out.writeFloat(intensity);
            out.writeFloat(wind);
            out.writeInt(remainingTicks);
            player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
        } catch (IOException ignored) {
        }
    }

    private void announceStorm() {
        long now = System.currentTimeMillis();
        if (now - lastAnnounceMs < 1500L) {
            return;
        }
        lastAnnounceMs = now;
        Component msg = Component.text("Sandstorm rolling in — visibility collapsing", NamedTextColor.GOLD);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(msg);
            p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.25f, 0.55f);
        }
    }

    private void announceClearing() {
        long now = System.currentTimeMillis();
        if (now - lastAnnounceMs < 1500L) {
            return;
        }
        lastAnnounceMs = now;
        Component msg = Component.text("Weather clearing", NamedTextColor.AQUA);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(msg);
        }
    }

    private void spawnAmbientDust() {
        BlockData sand = Material.SAND.createBlockData();
        float i = intensity;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            Location base = player.getLocation();
            int bursts = 2 + (int) (i * 4);
            for (int n = 0; n < bursts; n++) {
                double ox = (rng.nextDouble() - 0.5) * 28;
                double oy = rng.nextDouble() * 10 + 1;
                double oz = (rng.nextDouble() - 0.5) * 28;
                Location at = base.clone().add(ox, oy, oz);
                world.spawnParticle(Particle.FALLING_DUST, at, (int) (8 + i * 18), 2.5, 1.2, 2.5, 0.0, sand);
                if (rng.nextBoolean()) {
                    world.spawnParticle(Particle.CLOUD, at, (int) (2 + i * 4), 1.5, 0.8, 1.5, 0.01);
                }
            }
            if (player.getTicksLived() % 40 == 0) {
                player.playSound(player.getLocation(), Sound.ITEM_ELYTRA_FLYING, 0.15f + i * 0.25f, 0.45f);
            }
        }
    }

    public static byte parseEvent(String name) {
        if (name == null) {
            return EVENT_CLEAR;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "sand", "sandstorm", "dust", "duststorm" -> EVENT_SANDSTORM;
            default -> EVENT_CLEAR;
        };
    }
}
