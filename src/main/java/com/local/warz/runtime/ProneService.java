package com.local.warz.runtime;

import com.local.warz.WarzKeys;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import net.minenite.warzplugin.WarzPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Double-tap crouch → prone / crawl. Jump or crouch again to stand.
 * Companion clients get an explicit {@code pvpgunminus:prone} packet (tags do not sync).
 */
public final class ProneService implements Listener, PluginMessageListener {
    public static final String TAG = "pgm_prone";
    public static final String CHANNEL = "pvpgunminus:prone";
    public static final String CHANNEL_REQ = "pvpgunminus:prone_req";

    private static final byte ACTION_TOGGLE = 0;
    private static final byte ACTION_ENTER = 1;
    private static final byte ACTION_EXIT = 2;

    private static final long DOUBLE_TAP_MS = 500L;
    private static final double SPEED_MULT = -0.72;

    private final WarzPlugin plugin;
    private final NamespacedKey speedKey;
    private final Map<UUID, Long> lastSneakPressMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> ignoreSneakUntilMs = new ConcurrentHashMap<>();
    private final Set<UUID> prone = ConcurrentHashMap.newKeySet();

    public ProneService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.speedKey = WarzKeys.of("prone_slow");
    }

    public void registerChannel() {
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL);
        messenger.registerIncomingPluginChannel(plugin, CHANNEL_REQ, this);
    }

    public void unregisterChannel() {
        var messenger = plugin.getServer().getMessenger();
        messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL);
        messenger.unregisterIncomingPluginChannel(plugin, CHANNEL_REQ, this);
    }

    public boolean isProne(Player player) {
        return player != null && prone.contains(player.getUniqueId());
    }

    public void tick() {
        for (UUID id : Set.copyOf(prone)) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || !player.isOnline()) {
                prone.remove(id);
                continue;
            }
            if (plugin.bigDrone() != null && plugin.bigDrone().isPiloting(player)) {
                exitProne(player, false);
                continue;
            }
            if (player.getVehicle() != null || player.isFlying()) {
                exitProne(player, false);
                continue;
            }
            applyCrawlPose(player);
            if (!player.getScoreboardTags().contains(TAG)) {
                player.addScoreboardTag(TAG);
            }
            ensureSlow(player);
        }
    }

    public void toggle(Player player) {
        if (isProne(player)) {
            exitProne(player, true);
        } else {
            enterProne(player);
        }
    }

    public void enterProne(Player player) {
        if (player == null || isProne(player)) {
            return;
        }
        if (!player.isOnGround() || player.isInsideVehicle() || player.isFlying()) {
            sync(player, false);
            return;
        }
        if (plugin.bigDrone() != null && plugin.bigDrone().isPiloting(player)) {
            return;
        }
        prone.add(player.getUniqueId());
        ignoreSneakUntilMs.put(player.getUniqueId(), System.currentTimeMillis() + 400L);
        player.addScoreboardTag(TAG);
        player.setSneaking(false);
        applyCrawlPose(player);
        ensureSlow(player);
        sync(player, true);
        player.sendActionBar(Component.text("PRONE — crouch or jump to stand", NamedTextColor.GRAY));
        plugin.getLogger().info(player.getName() + " entered prone");
    }

    public void exitProne(Player player, boolean announce) {
        if (player == null || !isProne(player)) {
            if (player != null) {
                sync(player, false);
            }
            return;
        }
        if (plugin.medical() != null && plugin.medical().isBloodCollapsed(player)) {
            if (announce) {
                player.sendActionBar(Component.text("Too weak to stand — blood too low", NamedTextColor.DARK_RED));
            }
            return;
        }
        prone.remove(player.getUniqueId());
        ignoreSneakUntilMs.put(player.getUniqueId(), System.currentTimeMillis() + 400L);
        player.removeScoreboardTag(TAG);
        clearSlow(player);
        player.setSwimming(false);
        player.setPose(Pose.STANDING, false);
        sync(player, false);
        if (announce) {
            player.sendActionBar(Component.text("Standing", NamedTextColor.DARK_GRAY));
        }
    }

    /** Swimming flag + fixed swim pose = lie flat / crawl on land. */
    private static void applyCrawlPose(Player player) {
        player.setSwimming(true);
        player.setPose(Pose.SWIMMING, true);
    }

    public void sync(Player subject, boolean isProne) {
        if (subject == null || plugin.companions() == null) {
            return;
        }
        byte[] payload = encode(subject.getUniqueId(), isProne);
        if (payload == null) {
            return;
        }
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (!plugin.companions().hasCompanion(viewer)) {
                continue;
            }
            viewer.sendPluginMessage(plugin, CHANNEL, payload);
        }
    }

    /** Send every current prone player to one companion (on hello). */
    public void syncPlayer(Player viewer) {
        if (viewer == null || plugin.companions() == null || !plugin.companions().hasCompanion(viewer)) {
            return;
        }
        for (UUID id : Set.copyOf(prone)) {
            Player subject = plugin.getServer().getPlayer(id);
            if (subject == null) {
                continue;
            }
            byte[] payload = encode(id, true);
            if (payload != null) {
                viewer.sendPluginMessage(plugin, CHANNEL, payload);
            }
        }
        // Also confirm viewer's own state
        byte[] self = encode(viewer.getUniqueId(), isProne(viewer));
        if (self != null) {
            viewer.sendPluginMessage(plugin, CHANNEL, self);
        }
    }

    private static byte[] encode(UUID playerId, boolean isProne) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1);
            out.writeLong(playerId.getMostSignificantBits());
            out.writeLong(playerId.getLeastSignificantBits());
            out.writeByte(isProne ? 1 : 0);
            return bytes.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    public void clearAll() {
        for (UUID id : Set.copyOf(prone)) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) {
                exitProne(p, false);
            } else {
                prone.remove(id);
            }
        }
        lastSneakPressMs.clear();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL_REQ.equals(channel) || player == null) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            int protocol = in.readUnsignedByte();
            if (protocol != 1) {
                return;
            }
            byte action = (byte) in.readUnsignedByte();
            switch (action) {
                case ACTION_ENTER -> enterProne(player);
                case ACTION_EXIT -> exitProne(player, true);
                default -> toggle(player);
            }
        } catch (IOException ignored) {
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (plugin.bigDrone() != null && plugin.bigDrone().isPiloting(player)) {
            return;
        }

        if (!event.isSneaking()) {
            return;
        }

        long now = System.currentTimeMillis();
        Long ignoreUntil = ignoreSneakUntilMs.get(player.getUniqueId());
        if (ignoreUntil != null && now < ignoreUntil) {
            event.setCancelled(true);
            player.setSneaking(false);
            return;
        }

        if (isProne(player)) {
            // Sniper ADS: Shift = hold breath — jump to stand instead
            GunPlayerSession session = plugin.sessions() != null
                    ? plugin.sessions().get(player) : null;
            if (session != null && session.sniperAdsBlockingProneSneak()) {
                return; // allow sneak for breath; do not stand
            }
            exitProne(player, true);
            event.setCancelled(true);
            player.setSneaking(false);
            return;
        }

        // Companion double-taps via packet; server double-tap only without companion
        if (plugin.companions() != null && plugin.companions().hasCompanion(player)) {
            return;
        }

        Long last = lastSneakPressMs.put(player.getUniqueId(), now);
        if (last != null && now - last <= DOUBLE_TAP_MS) {
            if (player.isOnGround() && !player.isInsideVehicle() && !player.isFlying()) {
                enterProne(player);
                event.setCancelled(true);
                player.setSneaking(false);
                lastSneakPressMs.remove(player.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJump(PlayerJumpEvent event) {
        if (isProne(event.getPlayer())) {
            exitProne(event.getPlayer(), true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        exitProne(event.getEntity(), false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        exitProne(event.getPlayer(), false);
        lastSneakPressMs.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        exitProne(event.getPlayer(), false);
    }

    private void ensureSlow(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr == null) {
            return;
        }
        if (attr.getModifier(speedKey) == null) {
            attr.addTransientModifier(new AttributeModifier(
                    speedKey, SPEED_MULT, AttributeModifier.Operation.ADD_SCALAR));
        }
    }

    private void clearSlow(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr != null) {
            attr.removeModifier(speedKey);
        }
    }
}
