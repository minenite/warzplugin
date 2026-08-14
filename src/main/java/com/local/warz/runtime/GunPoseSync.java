package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Broadcasts gun third-person pose flags to Fabric companions (scoreboard tags do not sync).
 * Flags: bit0=gun, bit1=aim, bit2=fire, bit3=javelin_scope, bit4=reload
 */
public final class GunPoseSync {
    public static final String CHANNEL = "pvpgunminus:gun_pose";

    public static final byte FLAG_GUN = 1;
    public static final byte FLAG_AIM = 2;
    public static final byte FLAG_FIRE = 4;
    public static final byte FLAG_SCOPE = 8;
    public static final byte FLAG_RELOAD = 16;

    private final WarzPlugin plugin;
    private final Map<UUID, Byte> lastFlags = new ConcurrentHashMap<>();

    public GunPoseSync(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerChannel() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    public void unregisterChannel() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    public void tickPlayer(Player player) {
        if (player == null) {
            return;
        }
        byte flags = readFlags(player);
        Byte prev = lastFlags.put(player.getUniqueId(), flags);
        if (prev == null || prev != flags) {
            broadcast(player.getUniqueId(), flags);
        }
    }

    public void clear(Player player) {
        if (player == null) {
            return;
        }
        lastFlags.remove(player.getUniqueId());
        broadcast(player.getUniqueId(), (byte) 0);
    }

    public void clearAll() {
        for (UUID id : lastFlags.keySet()) {
            broadcast(id, (byte) 0);
        }
        lastFlags.clear();
    }

    /** Push every known pose to one companion (on hello). */
    public void syncViewer(Player viewer) {
        if (viewer == null || plugin.companions() == null || !plugin.companions().hasCompanion(viewer)) {
            return;
        }
        for (Player subject : plugin.getServer().getOnlinePlayers()) {
            byte flags = lastFlags.getOrDefault(subject.getUniqueId(), readFlags(subject));
            lastFlags.put(subject.getUniqueId(), flags);
            send(viewer, subject.getUniqueId(), flags);
        }
    }

    private byte readFlags(Player player) {
        byte flags = 0;
        var tags = player.getScoreboardTags();
        if (tags.contains("pgm_gun")) {
            flags |= FLAG_GUN;
        }
        if (tags.contains("pgm_aim") || tags.contains(JavelinService.SCOPE_TAG)) {
            flags |= FLAG_AIM;
        }
        if (tags.contains("pgm_fire")) {
            flags |= FLAG_FIRE;
        }
        if (tags.contains(JavelinService.SCOPE_TAG) || magnifyingOpticAds(player)) {
            flags |= FLAG_SCOPE;
        }
        if (tags.contains("pgm_reload")) {
            flags |= FLAG_RELOAD;
        }
        return flags;
    }

    /** Magnifying optic ADS (ACOG/scope) — not Javelin (handled via SCOPE_TAG). */
    private boolean magnifyingOpticAds(Player player) {
        if (player == null || !player.getScoreboardTags().contains("pgm_aim")) {
            return false;
        }
        var hand = player.getInventory().getItemInMainHand();
        OpticType optic = plugin.items().resolvedOptic(hand);
        return optic != null && optic.magnifying();
    }

    private void broadcast(UUID subject, byte flags) {
        if (plugin.companions() == null) {
            return;
        }
        byte[] payload = encode(subject, flags);
        if (payload == null) {
            return;
        }
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (plugin.companions().hasCompanion(viewer)) {
                viewer.sendPluginMessage(plugin, CHANNEL, payload);
            }
        }
    }

    private void send(Player viewer, UUID subject, byte flags) {
        byte[] payload = encode(subject, flags);
        if (payload != null) {
            viewer.sendPluginMessage(plugin, CHANNEL, payload);
        }
    }

    private static byte[] encode(UUID subject, byte flags) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1);
            out.writeLong(subject.getMostSignificantBits());
            out.writeLong(subject.getLeastSignificantBits());
            out.writeByte(flags);
            return bytes.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
