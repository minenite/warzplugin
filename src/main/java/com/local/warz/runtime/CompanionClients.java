package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks Fabric companion clients that can render cinematic lasers. */
public final class CompanionClients implements PluginMessageListener {
    public static final String CHANNEL_HELLO = "pvpgunminus:hello";
    public static final String CHANNEL_FEATURES = "pvpgunminus:features";
    public static final String CHANNEL_LASER = "pvpgunminus:laser";
    public static final String CHANNEL_CLEAR = "pvpgunminus:laser_clear";
    public static final String CHANNEL_FLASH = "pvpgunminus:laser_flash";
    public static final String CHANNEL_FX = "pvpgunminus:fx";
    public static final String CHANNEL_BLOOD = "pvpgunminus:blood";
    public static final String CHANNEL_BLAST = "pvpgunminus:blast";
    public static final String CHANNEL_CHAINLINK = "pvpgunminus:chainlink";
    public static final int PROTOCOL = 1;

    /** Companion: passable + climbable leaves (client mixins stay off until this bit is set). */
    public static final int FEATURE_LEAVES = 1;
    /** Reserved — chainlink is position-synced separately. */
    public static final int FEATURE_CHAINLINK = 1 << 1;

    private static final long TTL_MS = 35_000L;

    private final WarzPlugin plugin;
    private final Map<UUID, Long> companionUntil = new ConcurrentHashMap<>();

    public CompanionClients(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL_FEATURES);
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL_LASER);
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL_CLEAR);
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL_FLASH);
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL_FX);
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL_BLOOD);
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL_BLAST);
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL_CHAINLINK);
        messenger.registerIncomingPluginChannel(plugin, CHANNEL_HELLO, this);
    }

    public void unregister() {
        var messenger = plugin.getServer().getMessenger();
        messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL_FEATURES);
        messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL_LASER);
        messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL_CLEAR);
        messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL_FLASH);
        messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL_FX);
        messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL_BLOOD);
        messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL_BLAST);
        messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL_CHAINLINK);
        messenger.unregisterIncomingPluginChannel(plugin, CHANNEL_HELLO, this);
        companionUntil.clear();
    }

    /** Active WarZ world rules for companion clients. */
    public int featureFlags() {
        int flags = 0;
        if (plugin.getConfig().getBoolean("companion.features.leaves", true)) {
            flags |= FEATURE_LEAVES;
        }
        if (plugin.getConfig().getBoolean("companion.features.chainlink", true)) {
            flags |= FEATURE_CHAINLINK;
        }
        return flags;
    }

    public void sendFeatures(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        byte[] payload = encodeFeatures(featureFlags());
        if (payload != null) {
            player.sendPluginMessage(plugin, CHANNEL_FEATURES, payload);
        }
    }

    private static byte[] encodeFeatures(int flags) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(PROTOCOL);
            out.writeInt(flags);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    public boolean hasCompanion(Player player) {
        if (player == null) {
            return false;
        }
        Long until = companionUntil.get(player.getUniqueId());
        return until != null && until >= System.currentTimeMillis();
    }

    public void forget(UUID id) {
        if (id != null) {
            companionUntil.remove(id);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL_HELLO.equals(channel)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            int protocol = in.readUnsignedByte();
            if (protocol != PROTOCOL) {
                plugin.getLogger().info("Ignoring laser companion hello from " + player.getName()
                        + " (protocol " + protocol + ")");
                return;
            }
            String version = readUtf(in);
            companionUntil.put(player.getUniqueId(), System.currentTimeMillis() + TTL_MS);
            plugin.getLogger().info("Laser companion linked: " + player.getName() + " v" + version
                    + " features=0x" + Integer.toHexString(featureFlags()));
            // Enable WarZ-only client rules (passable/climbable leaves, …) before other sync.
            sendFeatures(player);
            if (plugin.weather() != null) {
                plugin.weather().syncPlayer(player);
            }
            if (plugin.prone() != null) {
                plugin.prone().syncPlayer(player);
            }
            if (plugin.gunPoses() != null) {
                plugin.gunPoses().syncViewer(player);
            }
            if (plugin.bigDrone() != null) {
                plugin.bigDrone().syncViewer(player);
            }
            if (plugin.droneMeshPose() != null) {
                plugin.droneMeshPose().syncViewer(player);
            }
            if (plugin.smoke() != null) {
                plugin.smoke().syncViewer(player);
            }
            if (plugin.flares() != null) {
                plugin.flares().syncViewer(player);
            }
            if (plugin.workbenches() != null) {
                plugin.workbenches().syncViewer(player);
            }
            if (plugin.glass() != null) {
                plugin.glass().syncViewer(player);
            }
            if (plugin.chainlink() != null) {
                plugin.chainlink().syncFull(player);
            }
            if (plugin.anomalies() != null) {
                plugin.anomalies().syncViewer(player);
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Bad laser companion hello from " + player.getName() + ": " + ex.getMessage());
        }
    }

    private static String readUtf(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        if (len < 0 || len > 128) {
            throw new IOException("bad utf length " + len);
        }
        byte[] raw = in.readNBytes(len);
        return new String(raw, StandardCharsets.UTF_8);
    }
}
