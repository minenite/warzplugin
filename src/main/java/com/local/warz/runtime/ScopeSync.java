package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.model.GunDefinition;
import com.local.warz.projectile.SniperBallistics;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S2C optic ADS state for Fabric companions.
 * Channel {@code pvpgunminus:scope}.
 * <p>
 * Wire v3: protocol, active, zeroYards, gunId, flags, breathPct, speed×100, fall×10000,
 * hudKind, opticId, reticleRgb, gripId, fov×1000.
 * Flags: bit0 prone, bit1 rested, bit2 holdingBreath.
 */
public final class ScopeSync {
    public static final String CHANNEL = "pvpgunminus:scope";
    public static final int SCOPE_PROTOCOL = 3;

    public static final byte FLAG_PRONE = 1;
    public static final byte FLAG_RESTED = 2;
    public static final byte FLAG_BREATH = 4;

    private final WarzPlugin plugin;
    private final Map<UUID, Long> lastKey = new ConcurrentHashMap<>();

    public ScopeSync(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerChannel() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    public void unregisterChannel() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    public void tickPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        push(player, false);
    }

    public void force(Player player) {
        if (player == null) {
            return;
        }
        lastKey.remove(player.getUniqueId());
        push(player, true);
    }

    public void clear(Player player) {
        if (player == null) {
            return;
        }
        lastKey.remove(player.getUniqueId());
        send(player, false, 100, "", (byte) 0, 100, 4.5, SniperBallistics.DEFAULT_SNIPER_FALL,
                OpticType.HudKind.IRONS.wire(), "irons", 0xFF2828, "", 0.92f);
    }

    private void push(Player player, boolean force) {
        CompanionClients companions = plugin.companions();
        if (companions == null || !companions.hasCompanion(player)) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        Optional<String> gunId = plugin.items().gunId(hand);
        Optional<GunDefinition> def = gunId.flatMap(id -> plugin.registry().get(id));
        boolean aiming = player.getScoreboardTags().contains("pgm_aim");
        OpticType optic = plugin.items().resolvedOptic(hand);
        if (optic == null) {
            optic = OpticType.IRONS;
        }
        boolean active = aiming && def.isPresent() && !def.get().throwable() && !player.isDead();
        int zero = active ? plugin.items().zeroYards(hand) : 100;
        String id = active ? gunId.orElse("") : "";
        boolean prone = plugin.prone() != null && plugin.prone().isProne(player);
        GripType grip = plugin.items().gripType(hand);
        boolean rested = active && (SniperBallistics.hasRifleRest(player, prone)
                || (grip.isBipod() && (prone || player.isSneaking())));
        boolean holdingBreath = false;
        int breathPct = 100;
        double speed = 4.5;
        double fall = SniperBallistics.DEFAULT_SNIPER_FALL;
        if (active && def.isPresent()) {
            speed = SniperBallistics.bulletSpeed(def.get());
            fall = SniperBallistics.fallSpeed(def.get());
            GunPlayerSession session = plugin.sessions() != null
                    ? plugin.sessions().get(player) : null;
            if (session != null) {
                holdingBreath = session.isHoldingBreath();
                breathPct = Math.round(session.breathStamina() * 100f);
            } else {
                holdingBreath = player.isSneaking();
            }
        }
        byte flags = 0;
        if (prone) {
            flags |= FLAG_PRONE;
        }
        if (rested) {
            flags |= FLAG_RESTED;
        }
        if (holdingBreath) {
            flags |= FLAG_BREATH;
        }
        OpticType.HudKind hud = optic != null ? optic.hudKind() : OpticType.HudKind.IRONS;
        String opticId = optic != null ? optic.id() : "irons";
        int rgb = plugin.items().reticleRgb(hand, optic);
        String gripId = grip.isInstalled() ? grip.id() : "";
        float fov = optic != null ? optic.fovForZero(zero) : 0.92f;

        long key = (active ? 1L : 0L) << 56
                | ((long) (zero & 0xFFFF) << 40)
                | ((long) (flags & 0xFF) << 32)
                | ((long) (breathPct & 0xFF) << 24)
                | ((long) (hud.wire() & 0xFF) << 16)
                | ((opticId.hashCode() ^ gripId.hashCode() ^ rgb) & 0xFFFFL);
        Long prev = lastKey.put(player.getUniqueId(), key);
        if (!force && prev != null && prev == key) {
            return;
        }
        send(player, active, zero, id, flags, breathPct, speed, fall,
                hud.wire(), opticId, rgb, gripId, fov);
    }

    private void send(Player player, boolean active, int zeroYards, String gunId,
                      byte flags, int breathPct, double bulletSpeed, double fallSpeed,
                      byte hudKind, String opticId, int reticleRgb, String gripId, float fovMult) {
        byte[] payload = encode(active, zeroYards, gunId == null ? "" : gunId,
                flags, breathPct, bulletSpeed, fallSpeed, hudKind, opticId, reticleRgb, gripId, fovMult);
        if (payload != null) {
            player.sendPluginMessage(plugin, CHANNEL, payload);
        }
    }

    private static byte[] encode(boolean active, int zeroYards, String gunId,
                                 byte flags, int breathPct, double bulletSpeed, double fallSpeed,
                                 byte hudKind, String opticId, int reticleRgb, String gripId, float fovMult) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(96);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(SCOPE_PROTOCOL);
            out.writeByte(active ? 1 : 0);
            out.writeShort(Math.max(50, Math.min(1000, zeroYards)));
            writeUtf(out, gunId, 64);
            out.writeByte(flags);
            out.writeByte(Math.max(0, Math.min(100, breathPct)));
            out.writeShort(Math.max(50, Math.min(20000, (int) Math.round(bulletSpeed * 100.0))));
            out.writeShort(Math.max(0, Math.min(20000, (int) Math.round(fallSpeed * 10000.0))));
            out.writeByte(hudKind);
            writeUtf(out, opticId == null ? "irons" : opticId, 32);
            out.writeInt(reticleRgb);
            writeUtf(out, gripId == null ? "" : gripId, 24);
            out.writeShort(Math.max(50, Math.min(1000, (int) Math.round(fovMult * 1000.0))));
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeUtf(DataOutputStream out, String s, int max) throws IOException {
        byte[] idBytes = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        int n = Math.min(max, idBytes.length);
        out.writeShort(n);
        out.write(idBytes, 0, n);
    }
}
