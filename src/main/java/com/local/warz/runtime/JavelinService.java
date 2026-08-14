package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.model.GunDefinition;
import com.local.warz.model.RoundDefinition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Javelin lock-on + guided missile vs MQ-9 family. Active flares always decoy.
 * Stealth airframes (RQ-170 / X-47B) deny LOAL; IR smoke also denies lock.
 * LAW / dumb rockets still hit stealth airframes via normal bullet absorb.
 */
public final class JavelinService {
    public static final String CHANNEL_LOCK = "pvpgunminus:javelin_lock";
    public static final String GUN_ID = "javelin";
    public static final String SCOPE_TAG = "javelin_scope";

    private static final int LOCK_TICKS = 28;
    private static final double LOCK_RANGE = 256.0;
    private static final double MIN_LOCK_DIST = 8.0;
    private static final double MIN_ALT_ABOVE = 4.0;
    private static final double LOCK_CONE_DOT = 0.985;
    private static final double LOCK_NEAR_MISS = 3.5;
    /**
     * Cruise speed (blocks/tick). Must cover {@link #LOCK_RANGE} within {@link #MAX_MISSILE_TICKS}:
     * 1.15 × 360 ≈ 414 &gt; 256m lock envelope.
     */
    private static final double MISSILE_SPEED = 1.15;
    /** Was 240 (~216m @ 0.9) — far locks timed out before impact. */
    private static final int MAX_MISSILE_TICKS = 360;
    /** Larger airframe after 3× mesh scale. */
    private static final double HIT_RADIUS = 4.5;
    private static final int MIN_FLIGHT_TICKS = 12;
    private static final int DENY_MSG_COOLDOWN_TICKS = 20;

    private final WarzPlugin plugin;
    private final Map<UUID, LockState> locks = new ConcurrentHashMap<>();
    private final List<Missile> missiles = new ArrayList<>();
    private final Map<UUID, Integer> denyCooldown = new ConcurrentHashMap<>();

    public JavelinService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerChannel() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_LOCK);
    }

    public void unregisterChannel() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL_LOCK);
    }

    public static boolean isJavelin(GunDefinition def) {
        return def != null && GUN_ID.equalsIgnoreCase(def.fileName());
    }

    public boolean hasHardLock(Player player) {
        LockState st = locks.get(player.getUniqueId());
        return st != null && st.locked && st.targetPilot != null;
    }

    public void tick() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            denyCooldown.compute(player.getUniqueId(), (id, v) -> {
                if (v == null) {
                    return null;
                }
                int n = v - 1;
                return n <= 0 ? null : n;
            });
            GunPlayerSession session = plugin.sessions().get(player);
            if (session == null) {
                clearLock(player);
                continue;
            }
            Optional<GunDefinition> held = session.heldGun(player.getInventory().getItemInMainHand());
            boolean javelin = held.isPresent() && isJavelin(held.get());
            boolean aiming = javelin && session.isAimedIn();
            if (!aiming) {
                player.removeScoreboardTag(SCOPE_TAG);
                clearLock(player);
                continue;
            }
            player.addScoreboardTag(SCOPE_TAG);

            SightResult sight = findDroneInSight(player);
            LockState st = locks.computeIfAbsent(player.getUniqueId(), id -> new LockState());
            if (sight.denyReason() != null) {
                st.progress = Math.max(0, st.progress - 4);
                st.locked = false;
                st.targetPilot = null;
                maybeDenyMessage(player, sight.denyReason());
            } else if (sight.pilotId() == null) {
                st.progress = Math.max(0, st.progress - 3);
                st.locked = false;
                st.targetPilot = null;
            } else if (st.targetPilot != null && !st.targetPilot.equals(sight.pilotId())) {
                st.progress = 4;
                st.locked = false;
                st.targetPilot = sight.pilotId();
            } else {
                st.targetPilot = sight.pilotId();
                st.progress = Math.min(LOCK_TICKS, st.progress + 1);
                st.locked = st.progress >= LOCK_TICKS;
            }
            if (st.targetPilot != null && st.progress > 0 && plugin.bigDrone() != null) {
                Player pilot = plugin.getServer().getPlayer(st.targetPilot);
                if (pilot != null && plugin.bigDrone().isPiloting(pilot)) {
                    plugin.bigDrone().warnJavelinLock(pilot, st.locked);
                }
            }
            sendLock(player, st);
        }
        tickMissiles();
    }

    private void maybeDenyMessage(Player shooter, String reason) {
        Integer cd = denyCooldown.get(shooter.getUniqueId());
        if (cd != null && cd > 0) {
            return;
        }
        denyCooldown.put(shooter.getUniqueId(), DENY_MSG_COOLDOWN_TICKS);
        shooter.sendActionBar(Component.text(reason, NamedTextColor.RED));
        shooter.playSound(shooter.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.55f, 0.55f);
    }

    private SightResult findDroneInSight(Player shooter) {
        BigDroneService drones = plugin.bigDrone();
        if (drones == null) {
            return SightResult.none();
        }
        Location eye = shooter.getEyeLocation();
        Vector look = eye.getDirection().normalize();
        UUID best = null;
        String deny = null;
        double bestScore = Double.MAX_VALUE;
        double bestDenyScore = Double.MAX_VALUE;
        for (Player pilot : drones.onlinePilots()) {
            Location drone = drones.droneWorldLocation(pilot);
            if (drone == null || drone.getWorld() == null || !drone.getWorld().equals(eye.getWorld())) {
                continue;
            }
            Vector to = drone.toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist < MIN_LOCK_DIST || dist > LOCK_RANGE) {
                continue;
            }
            if (drone.getY() < eye.getY() + MIN_ALT_ABOVE) {
                continue;
            }
            double along = to.dot(look);
            if (along < MIN_LOCK_DIST * 0.5) {
                continue;
            }
            Vector closest = look.clone().multiply(along);
            double miss = to.clone().subtract(closest).length();
            Vector dir = to.clone().normalize();
            double dot = dir.dot(look);
            boolean inCone = dot >= LOCK_CONE_DOT;
            boolean nearRay = miss <= LOCK_NEAR_MISS;
            if (!inCone && !nearRay) {
                continue;
            }
            double score = miss * 4.0 + dist * 0.01;
            if (drones.isStealth(pilot)) {
                if (score < bestDenyScore) {
                    bestDenyScore = score;
                    deny = "LOAL DENIED — STEALTH AIRFRAME";
                }
                continue;
            }
            if (plugin.smoke() != null && (plugin.smoke().blocksIrSeekers(drone)
                    || plugin.smoke().blocksIrSeekers(eye))) {
                if (score < bestDenyScore) {
                    bestDenyScore = score;
                    deny = "LOAL DENIED — IR OBSCURED";
                }
                continue;
            }
            if (score < bestScore) {
                bestScore = score;
                best = pilot.getUniqueId();
                deny = null;
            }
        }
        if (best != null) {
            return SightResult.lock(best);
        }
        if (deny != null) {
            return SightResult.denied(deny);
        }
        return SightResult.none();
    }

    /**
     * @return true if a guided missile was launched (caller should skip dumb bullet).
     */
    public boolean tryLaunchGuided(Player shooter, GunDefinition def, RoundDefinition round) {
        LockState st = locks.get(shooter.getUniqueId());
        if (st == null || !st.locked || st.targetPilot == null) {
            return false;
        }
        Player pilot = plugin.getServer().getPlayer(st.targetPilot);
        if (pilot == null || plugin.bigDrone() == null || !plugin.bigDrone().isPiloting(pilot)) {
            return false;
        }
        if (plugin.bigDrone().isStealth(pilot)) {
            maybeDenyMessage(shooter, "LOAL DENIED — STEALTH AIRFRAME");
            st.locked = false;
            st.progress = 0;
            st.targetPilot = null;
            sendLock(shooter, st);
            return true; // consume click — no dumb fire while scoping stealth
        }
        Location start = shooter.getEyeLocation().clone().add(shooter.getEyeLocation().getDirection().multiply(0.8));
        missiles.add(new Missile(shooter.getUniqueId(), st.targetPilot, start, def, round));
        plugin.bigDrone().warnMissileInbound(pilot);
        shooter.sendMessage(Component.text("Javelin lock — missile away", NamedTextColor.GOLD));
        shooter.playSound(shooter.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.2f, 0.7f);
        shooter.getWorld().playSound(start, Sound.ENTITY_GENERIC_EXPLODE, 0.35f, 1.8f);
        st.locked = false;
        st.progress = 0;
        st.targetPilot = null;
        sendLock(shooter, st);
        return true;
    }

    private void tickMissiles() {
        Iterator<Missile> it = missiles.iterator();
        while (it.hasNext()) {
            Missile m = it.next();
            m.ticks++;
            Player pilot = plugin.getServer().getPlayer(m.targetPilot);
            if (pilot == null || !pilot.isOnline() || plugin.bigDrone() == null
                    || !plugin.bigDrone().isPiloting(pilot) || m.ticks > MAX_MISSILE_TICKS) {
                // Hold terminal until min flight time so flares are usable even up-close
                if (m.ticks >= MIN_FLIGHT_TICKS && pilot != null && plugin.bigDrone() != null
                        && plugin.bigDrone().isPiloting(pilot)) {
                    // Far locks used to fall out of the sky with no effect — finish the job if close.
                    Location aim = plugin.bigDrone().droneWorldLocation(pilot);
                    if (aim != null && m.pos.distanceSquared(aim) < 64) {
                        impact(m, pilot);
                    }
                }
                it.remove();
                continue;
            }
            Location aim = plugin.bigDrone().droneWorldLocation(pilot);
            if (aim == null) {
                it.remove();
                continue;
            }
            Vector to = aim.toVector().subtract(m.pos.toVector());
            double dist = to.length();
            if (dist < HIT_RADIUS && m.ticks >= MIN_FLIGHT_TICKS) {
                impact(m, pilot);
                it.remove();
                continue;
            }
            if (dist > 1.0e-4) {
                Vector step = to.normalize().multiply(Math.min(MISSILE_SPEED, dist));
                m.pos.add(step);
            }
            World w = m.pos.getWorld();
            if (w != null) {
                w.spawnParticle(Particle.FLAME, m.pos, 2, 0.05, 0.05, 0.05, 0.01);
                w.spawnParticle(Particle.SMOKE, m.pos, 1, 0.02, 0.02, 0.02, 0.0);
                if (m.ticks % 3 == 0) {
                    w.playSound(m.pos, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.35f, 1.6f);
                }
            }
        }
    }

    private void impact(Missile m, Player pilot) {
        boolean flares = plugin.bigDrone().isFlareActive(pilot);
        if (flares) {
            Location at = plugin.bigDrone().droneWorldLocation(pilot);
            if (at != null) {
                spawnFlareBurst(at);
            }
            pilot.sendMessage(Component.text("FLARES decoyed the missile!", NamedTextColor.GREEN));
            Player shooter = plugin.getServer().getPlayer(m.shooter);
            if (shooter != null) {
                shooter.sendMessage(Component.text("Missile decoyed by flares", NamedTextColor.GRAY));
            }
            return;
        }
        plugin.bigDrone().shootDown(pilot, m.shooter, "javelin");
    }

    public void clearLock(Player player) {
        if (player == null) {
            return;
        }
        LockState st = locks.remove(player.getUniqueId());
        if (st != null) {
            st.locked = false;
            sendLock(player, st);
        }
        player.removeScoreboardTag(SCOPE_TAG);
    }

    private void sendLock(Player player, LockState st) {
        if (plugin.companions() == null || !plugin.companions().hasCompanion(player)) {
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1);
            out.writeByte(st.locked ? 1 : 0);
            out.writeByte(Math.max(0, Math.min(100, (int) Math.round(100.0 * st.progress / LOCK_TICKS))));
            player.sendPluginMessage(plugin, CHANNEL_LOCK, bytes.toByteArray());
        } catch (IOException ignored) {
        }
    }

    /** Deploy visual flare burst around the drone (called by BigDroneService). */
    public void spawnFlareBurst(Location center) {
        if (center == null || center.getWorld() == null) {
            return;
        }
        World world = center.getWorld();
        for (int i = 0; i < 24; i++) {
            double ang = (Math.PI * 2 * i) / 24.0;
            double r = 2.5 + (i % 3) * 0.8;
            Location p = center.clone().add(Math.cos(ang) * r, 0.4 + (i % 5) * 0.35, Math.sin(ang) * r);
            world.spawnParticle(Particle.FLAME, p, 4, 0.15, 0.2, 0.15, 0.02);
            world.spawnParticle(Particle.SMOKE, p, 2, 0.1, 0.1, 0.1, 0.01);
        }
        world.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.6f, 0.6f);
    }

    private record SightResult(UUID pilotId, String denyReason) {
        static SightResult none() {
            return new SightResult(null, null);
        }

        static SightResult lock(UUID id) {
            return new SightResult(id, null);
        }

        static SightResult denied(String reason) {
            return new SightResult(null, reason);
        }
    }

    private static final class LockState {
        int progress;
        boolean locked;
        UUID targetPilot;
    }

    private static final class Missile {
        final UUID shooter;
        final UUID targetPilot;
        final Location pos;
        final GunDefinition def;
        final RoundDefinition round;
        int ticks;

        Missile(UUID shooter, UUID targetPilot, Location pos, GunDefinition def, RoundDefinition round) {
            this.shooter = shooter;
            this.targetPilot = targetPilot;
            this.pos = pos.clone();
            this.def = def;
            this.round = round;
        }
    }
}
