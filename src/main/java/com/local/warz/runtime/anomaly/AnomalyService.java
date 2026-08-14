package com.local.warz.runtime.anomaly;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.runtime.NvgGear;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-owned paranormal contacts. Synced via {@link #CHANNEL} for Red-NV client render only.
 */
public final class AnomalyService {
    public static final String CHANNEL = "pvpgunminus:anomaly_vis";
    private static final int PROTOCOL = 1;
    private static final int MAX_ANOMALIES = 24;
    private static final int MAX_PER_VIEWER_AREA = 6;
    private static final int POSE_HISTORY = 20 * 22; // ~22s
    private static final double SYNC_RANGE = 96.0;

    private final WarzPlugin plugin;
    private final Map<UUID, Anomaly> byId = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Anomaly.PoseSample>> poseHistory = new ConcurrentHashMap<>();
    private final Random rng = new Random();
    private boolean registered;

    public AnomalyService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!registered) {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
            registered = true;
        }
    }

    public void stop() {
        byId.clear();
        poseHistory.clear();
        broadcastEmpty();
        if (registered) {
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
            registered = false;
        }
    }

    public Collection<Anomaly> all() {
        return List.copyOf(byId.values());
    }

    public int clearAll() {
        int n = byId.size();
        byId.clear();
        broadcastEmpty();
        return n;
    }

    public Anomaly spawn(AnomalyType type, Location at) {
        if (type == null || at == null || at.getWorld() == null) {
            return null;
        }
        if (byId.size() >= MAX_ANOMALIES) {
            cullOldest();
        }
        Anomaly a = new Anomaly(type, at.getWorld(), at.getX(), at.getY(), at.getZ(), at.getYaw());
        a.homeX = at.getX();
        a.homeY = at.getY();
        a.homeZ = at.getZ();
        a.hasHome = true;
        configureDefaults(a);
        byId.put(a.id, a);
        return a;
    }

    public Anomaly spawnNear(Player player, AnomalyType type) {
        if (player == null || type == null) {
            return null;
        }
        // Mid-range ahead — visible under Red NV, not neon-in-your-face.
        double dist = switch (type) {
            case OBSERVER, WATCHER, SHADOW -> 22.0;
            case STALKER, RESIDUAL, CRAWLER, VOID, DISTORTION -> 20.0;
            case ECHO, STATIC_MAN, BURN_IN, FALSE_CONTACT, GLITCH -> 26.0;
            case MIMIC, IMPOSTER -> 24.0;
            default -> 22.0;
        };
        Location at = spawnInFront(player, dist);
        Anomaly a = spawn(type, at);
        if (a != null) {
            a.targetPlayer = player.getUniqueId();
            if (type == AnomalyType.MIMIC || type == AnomalyType.IMPOSTER || type == AnomalyType.ECHO) {
                a.skinUuid = player.getUniqueId();
                Player other = nearestOther(player, 48);
                if (other != null && (type == AnomalyType.MIMIC || type == AnomalyType.IMPOSTER)) {
                    a.skinUuid = other.getUniqueId();
                    a.targetPlayer = other.getUniqueId();
                }
            }
            if (type == AnomalyType.ECHO) {
                fillEchoFromHistory(a, player);
            }
            if (type == AnomalyType.RESIDUAL) {
                buildResidualPath(a, at);
            }
            broadcast();
        }
        return a;
    }

    /** ~dist blocks ahead at standing height, facing the player. */
    private Location spawnInFront(Player player, double dist) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().clone();
        dir.setY(0);
        if (dir.lengthSquared() < 1e-4) {
            dir = new Vector(0, 0, 1);
        } else {
            dir.normalize();
        }
        Location at = player.getLocation().clone().add(dir.multiply(dist));
        at.setY(player.getWorld().getHighestBlockYAt(at) + 1.0);
        Vector toPlayer = player.getEyeLocation().toVector().subtract(at.toVector());
        if (toPlayer.lengthSquared() > 1e-4) {
            at.setDirection(toPlayer);
        }
        return at;
    }

    public void tick() {
        recordPoseHistory();
        if (plugin.getServer().getCurrentTick() % 40 == 0) {
            ambientMaintain();
        }
        Iterator<Map.Entry<UUID, Anomaly>> it = byId.entrySet().iterator();
        while (it.hasNext()) {
            Anomaly a = it.next().getValue();
            a.age++;
            if (a.world == null || Bukkit.getWorld(a.world.getUID()) == null || a.age > a.lifeTicks) {
                it.remove();
                continue;
            }
            if (a.coolDown > 0) {
                a.coolDown--;
            }
            if (cullIfTooClose(a)) {
                it.remove();
                continue;
            }
            tickOne(a);
        }
        if (plugin.getServer().getCurrentTick() % 2 == 0) {
            broadcast();
        }
    }

    /**
     * Only despawn when literally in the player's face. Mid-range (12–30) must stay
     * alive — that is the intended Red NV contact band.
     */
    private boolean cullIfTooClose(Anomaly a) {
        final double personal = 6.0;
        for (Player p : a.world.getPlayers()) {
            if (p.getLocation().distance(a.location()) < personal) {
                return true;
            }
        }
        return false;
    }

    private void tickOne(Anomaly a) {
        List<Player> viewers = nvgViewersNear(a.location(), SYNC_RANGE);
        Player nearest = nearestViewer(a, viewers);
        boolean observed = isObserved(a, viewers);
        boolean inFovAny = anyInFov(a, viewers);
        double distNearest = nearest != null
                ? nearest.getLocation().distance(a.location()) : Double.MAX_VALUE;

        a.interference = 0f;
        for (Player v : viewers) {
            double d = v.getLocation().distance(a.location());
            if (d < 40) {
                a.interference = Math.max(a.interference, (float) (1.0 - d / 40.0));
            }
        }
        if (a.type == AnomalyType.STATIC_MAN) {
            a.interference = Math.min(1f, a.interference + 0.55f);
            a.visible = (a.age / 2) % 3 != 0;
        }

        switch (a.type) {
            case SHADOW -> tickShadow(a, nearest, distNearest, observed);
            case ECHO -> tickEcho(a);
            case WATCHER -> tickWatcher(a, nearest, observed);
            case CRAWLER -> tickCrawler(a, inFovAny);
            case STATIC_MAN -> { /* flicker above */ }
            case RESIDUAL -> tickResidual(a);
            case MIMIC -> tickMimic(a, nearest, distNearest, observed);
            case STALKER -> tickStalker(a, nearest, inFovAny);
            case VOID -> {
                a.voidMask = true;
                a.visible = true;
            }
            case BURN_IN -> tickBurnIn(a, viewers);
            case DISTORTION -> {
                a.noBody = true;
                a.distort = true;
                a.visible = true;
            }
            case FALSE_CONTACT -> tickFalseContact(a, nearest, observed);
            case GLITCH -> tickGlitch(a);
            case OBSERVER -> {
                if (nearest != null) {
                    faceToward(a, nearest.getEyeLocation());
                }
            }
            case IMPOSTER -> tickImposter(a, nearest, distNearest, observed);
        }
    }

    private void tickShadow(Anomaly a, Player nearest, double dist, boolean observed) {
        if (nearest != null) {
            faceToward(a, nearest.getEyeLocation());
        }
        if (dist < 8.0 || (observed && dist < 14.0 && dist < a.lastApproachDist - 0.4)) {
            byId.remove(a.id);
            return;
        }
        a.lastApproachDist = dist;
        a.visible = true;
    }

    private void tickEcho(Anomaly a) {
        if (a.echoClip.isEmpty()) {
            return;
        }
        if (a.age < a.echoDelayTicks) {
            a.visible = false;
            return;
        }
        a.visible = true;
        int idx = Math.min(a.echoClip.size() - 1, a.echoCursor);
        Anomaly.PoseSample s = a.echoClip.get(idx);
        a.x = s.x();
        a.y = s.y();
        a.z = s.z();
        a.yaw = s.yaw();
        a.pitch = s.pitch();
        a.echoCursor++;
        if (a.echoCursor >= a.echoClip.size()) {
            byId.remove(a.id);
        }
    }

    private void tickWatcher(Anomaly a, Player nearest, boolean observed) {
        a.scaleY = 1.35f;
        a.bright = true; // eyes
        if (!observed && nearest != null) {
            double d = nearest.getLocation().distance(a.location());
            // Creep closer but hold a mid-range standoff so it stays visible.
            if (d > 14.0) {
                stepToward(a, nearest.getLocation(), 0.08);
            }
            faceToward(a, nearest.getEyeLocation());
        } else if (nearest != null) {
            faceToward(a, nearest.getEyeLocation());
        }
        a.visible = true;
    }

    private void tickCrawler(Anomaly a, boolean inFov) {
        a.scaleY = 0.55f;
        if (!inFov && a.coolDown <= 0) {
            Location cover = findCoverNear(a.location(), 10);
            if (cover != null) {
                a.setPos(cover);
            } else {
                a.x += (rng.nextDouble() - 0.5) * 6;
                a.z += (rng.nextDouble() - 0.5) * 6;
                snapToGround(a);
            }
            a.coolDown = 12 + rng.nextInt(20);
        }
        a.visible = true;
    }

    private void tickResidual(Anomaly a) {
        if (a.path.size() < 2) {
            return;
        }
        Location to = a.path.get(a.pathIndex % a.path.size());
        Vector delta = to.toVector().subtract(new Vector(a.x, a.y, a.z));
        double len = delta.length();
        if (len < 0.25) {
            a.pathIndex = (a.pathIndex + 1) % a.path.size();
        } else {
            delta.normalize().multiply(0.12);
            a.x += delta.getX();
            a.y += delta.getY();
            a.z += delta.getZ();
            a.yaw = (float) Math.toDegrees(Math.atan2(-delta.getX(), delta.getZ()));
        }
        a.visible = true;
        a.lifeTicks = Integer.MAX_VALUE / 4;
    }

    private void tickMimic(Anomaly a, Player nearest, double dist, boolean observed) {
        Player src = a.targetPlayer != null ? Bukkit.getPlayer(a.targetPlayer) : null;
        // Prefer a different online player; otherwise hold world home (solo-safe).
        if (src != null && src.isOnline() && nearest != null && !src.getUniqueId().equals(nearest.getUniqueId())) {
            Location eye = src.getLocation();
            Vector away = eye.toVector().subtract(new Vector(a.homeX, a.homeY, a.homeZ));
            away.setY(0);
            if (away.lengthSquared() < 1e-4) {
                away = eye.getDirection().clone().setY(0);
            }
            if (away.lengthSquared() < 1e-4) {
                away = new Vector(1, 0, 0);
            } else {
                away.normalize();
            }
            double standOff = 18.0 + Math.sin(a.age * 0.05) * 2.0;
            Location copy = eye.clone().subtract(away.clone().multiply(standOff));
            copy.add(Math.sin(a.age * 0.07) * 0.8, 0, Math.cos(a.age * 0.05) * 0.8);
            copy.setY(src.getWorld().getHighestBlockYAt(copy) + 1);
            a.x = copy.getX();
            a.y = copy.getY();
            a.z = copy.getZ();
            a.yaw = eye.getYaw() + (float) Math.sin(a.age * 0.2) * 25f;
            a.skinUuid = src.getUniqueId();
        } else {
            // Solo: jitter around fixed home — does not slide with camera look.
            double hx = a.hasHome ? a.homeX : a.x;
            double hz = a.hasHome ? a.homeZ : a.z;
            a.x = hx + Math.sin(a.age * 0.07) * 1.2;
            a.z = hz + Math.cos(a.age * 0.05) * 1.2;
            snapToGround(a);
            a.yaw = (float) (a.age * 3f);
            if (nearest != null) {
                a.skinUuid = nearest.getUniqueId();
            }
        }
        if (dist < 10.0 || (observed && dist < 16.0)) {
            a.stuckTicks++;
            if (a.stuckTicks > 25) {
                byId.remove(a.id);
            }
        } else {
            a.stuckTicks = 0;
        }
        a.visible = true;
    }

    private void tickStalker(Anomaly a, Player nearest, boolean inFov) {
        if (nearest != null && !inFov) {
            double d = nearest.getLocation().distance(a.location());
            if (d > 12.0) {
                stepToward(a, nearest.getLocation(), 0.06);
            }
        }
        a.visible = true;
        a.interference = Math.max(a.interference, 0.25f);
    }

    private void tickBurnIn(Anomaly a, List<Player> viewers) {
        a.bright = true;
        a.visible = true;
        float maxLook = 0f;
        for (Player v : viewers) {
            if (lookingAt(v, a, 8.0)) {
                maxLook = Math.max(maxLook, 1f);
            }
        }
        if (maxLook > 0f) {
            a.lookProgress = Math.min(1f, a.lookProgress + 0.04f);
        } else {
            a.lookProgress = Math.max(0f, a.lookProgress - 0.01f);
        }
    }

    private void tickFalseContact(Anomaly a, Player nearest, boolean observed) {
        a.bright = true;
        a.scaleY = 0.9f;
        if (nearest != null && lookingAt(nearest, a, 6.0) && observed) {
            byId.remove(a.id);
            return;
        }
        // Drift at FOV edge relative to target
        if (nearest != null && a.age % 10 == 0) {
            Vector right = nearest.getLocation().getDirection().clone().setY(0);
            if (right.lengthSquared() > 1e-4) {
                right.normalize();
                Vector side = new Vector(-right.getZ(), 0, right.getX()).multiply(rng.nextBoolean() ? 1 : -1);
                Location edge = nearest.getEyeLocation().add(right.multiply(18)).add(side.multiply(8));
                edge.setY(nearest.getWorld().getHighestBlockYAt(edge) + 1);
                a.setPos(edge);
            }
        }
        a.visible = true;
        if (a.age > 20 * 12) {
            byId.remove(a.id);
        }
    }

    private void tickGlitch(Anomaly a) {
        if (a.coolDown <= 0) {
            a.x += (rng.nextDouble() - 0.5) * 4;
            a.z += (rng.nextDouble() - 0.5) * 4;
            snapToGround(a);
            a.partCount = 2 + rng.nextInt(3);
            for (int i = 0; i < a.partCount; i++) {
                a.partOx[i] = (float) ((rng.nextDouble() - 0.5) * 1.2);
                a.partOy[i] = (float) (rng.nextDouble() * 1.4);
                a.partOz[i] = (float) ((rng.nextDouble() - 0.5) * 1.2);
            }
            a.coolDown = 4 + rng.nextInt(8);
        }
        a.visible = true;
    }

    private void tickImposter(Anomaly a, Player nearest, double dist, boolean observed) {
        Player src = a.targetPlayer != null ? Bukkit.getPlayer(a.targetPlayer) : null;
        boolean foreign = src != null && src.isOnline()
                && (nearest == null || !src.getUniqueId().equals(nearest.getUniqueId()));
        if (foreign) {
            a.skinUuid = src.getUniqueId();
            Location loc = src.getLocation();
            Vector side = loc.getDirection().clone().setY(0);
            if (side.lengthSquared() < 1e-4) {
                side = new Vector(0, 0, 1);
            } else {
                side.normalize();
            }
            Vector perp = new Vector(-side.getZ(), 0, side.getX());
            Location at = loc.clone().add(perp.multiply(16)).add(side.multiply(8));
            if (a.age % 40 >= 25) {
                at.add(Math.sin(a.age * 0.03) * 0.4, 0, Math.cos(a.age * 0.03) * 0.4);
            }
            at.setY(src.getWorld().getHighestBlockYAt(at) + 1);
            a.x = at.getX();
            a.y = at.getY();
            a.z = at.getZ();
            a.yaw = loc.getYaw() + 90f; // wrong head
            a.pitch = loc.getPitch() * 0.2f;
        } else {
            // Solo: stand at home with wrong stillness / sudden fidget — no look-vector slide.
            double hx = a.hasHome ? a.homeX : a.x;
            double hz = a.hasHome ? a.homeZ : a.z;
            if (a.age % 40 < 25) {
                a.x = hx;
                a.z = hz;
            } else {
                a.x = hx + Math.sin(a.age * 0.03) * 0.35;
                a.z = hz + Math.cos(a.age * 0.03) * 0.35;
            }
            snapToGround(a);
            a.yaw = (nearest != null ? nearest.getLocation().getYaw() : a.yaw) + 90f;
            a.pitch = 0f;
            if (nearest != null) {
                a.skinUuid = nearest.getUniqueId();
            }
        }
        if (dist < 8.0 || (observed && dist < 12.0 && a.stuckTicks++ > 30)) {
            byId.remove(a.id);
        }
        a.visible = true;
    }

    private void ambientMaintain() {
        List<Player> wearers = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (NvgGear.isWearingNvg(plugin, p) && isDarkEnough(p)) {
                wearers.add(p);
            }
        }
        if (wearers.isEmpty()) {
            return;
        }
        for (Player p : wearers) {
            int near = countNear(p.getLocation(), 64);
            if (near >= MAX_PER_VIEWER_AREA) {
                continue;
            }
            if (rng.nextDouble() > 0.35) {
                continue;
            }
            AnomalyType type = pickAmbientType();
            // Ambient uses ring spawn (not always dead-ahead), mid/far band.
            Location at = findSpawnSite(p, type);
            if (at == null) {
                continue;
            }
            Anomaly a = spawn(type, at);
            if (a != null) {
                a.targetPlayer = p.getUniqueId();
                a.skinUuid = p.getUniqueId();
                Player other = nearestOther(p, 64);
                if (other != null && (type == AnomalyType.MIMIC || type == AnomalyType.IMPOSTER
                        || type == AnomalyType.ECHO)) {
                    a.skinUuid = other.getUniqueId();
                    a.targetPlayer = other.getUniqueId();
                }
                if (type == AnomalyType.RESIDUAL) {
                    buildResidualPath(a, at);
                }
                if (type == AnomalyType.ECHO) {
                    fillEchoFromHistory(a, p);
                }
            }
        }
    }

    private AnomalyType pickAmbientType() {
        // All 15 can appear; common silhouettes weighted higher, specials rarer.
        AnomalyType[] weighted = {
                AnomalyType.SHADOW, AnomalyType.SHADOW, AnomalyType.OBSERVER, AnomalyType.OBSERVER,
                AnomalyType.WATCHER, AnomalyType.WATCHER, AnomalyType.STALKER, AnomalyType.STALKER,
                AnomalyType.RESIDUAL, AnomalyType.CRAWLER, AnomalyType.VOID, AnomalyType.DISTORTION,
                AnomalyType.ECHO, AnomalyType.STATIC_MAN, AnomalyType.BURN_IN,
                AnomalyType.FALSE_CONTACT, AnomalyType.GLITCH, AnomalyType.MIMIC, AnomalyType.IMPOSTER
        };
        return weighted[rng.nextInt(weighted.length)];
    }

    private void configureDefaults(Anomaly a) {
        switch (a.type) {
            case WATCHER -> a.scaleY = 1.35f;
            case CRAWLER -> a.scaleY = 0.55f;
            case VOID -> a.voidMask = true;
            case DISTORTION -> {
                a.noBody = true;
                a.distort = true;
            }
            case BURN_IN -> a.bright = true;
            case FALSE_CONTACT -> {
                a.bright = true;
                a.lifeTicks = 20 * 12;
            }
            case ECHO -> a.echoDelayTicks = 0;
            case SHADOW -> a.lifeTicks = 20 * 45;
            case STATIC_MAN -> a.lifeTicks = 20 * 30;
            default -> {
            }
        }
    }

    private void recordPoseHistory() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Deque<Anomaly.PoseSample> q = poseHistory.computeIfAbsent(p.getUniqueId(),
                    id -> new ArrayDeque<>(POSE_HISTORY));
            Location loc = p.getLocation();
            q.addLast(new Anomaly.PoseSample(loc.getX(), loc.getY(), loc.getZ(),
                    loc.getYaw(), loc.getPitch()));
            while (q.size() > POSE_HISTORY) {
                q.removeFirst();
            }
        }
    }

    private void fillEchoFromHistory(Anomaly a, Player near) {
        a.echoClip.clear();
        Deque<Anomaly.PoseSample> q = poseHistory.get(near.getUniqueId());
        a.skinUuid = near.getUniqueId();
        if (q == null || q.size() < 20) {
            Player other = nearestOther(near, 48);
            if (other != null) {
                Deque<Anomaly.PoseSample> oq = poseHistory.get(other.getUniqueId());
                if (oq != null && oq.size() >= 20) {
                    q = oq;
                    a.skinUuid = other.getUniqueId();
                }
            }
        }
        if (q != null && !q.isEmpty()) {
            int start = Math.max(0, q.size() - 20 * 8);
            int i = 0;
            for (Anomaly.PoseSample s : q) {
                if (i++ >= start) {
                    a.echoClip.add(s);
                }
            }
        }
        // Solo / fresh join: synthesize a walking loop near the anomaly home so Echo still plays.
        if (a.echoClip.size() < 20) {
            synthesizeEchoClip(a, near);
        }
        a.echoDelayTicks = 0;
        a.echoCursor = 0;
    }

    /** Build a ~4s figure-eight clip around home so Echo works without pose history. */
    private void synthesizeEchoClip(Anomaly a, Player near) {
        a.echoClip.clear();
        double cx = a.hasHome ? a.homeX : a.x;
        double cz = a.hasHome ? a.homeZ : a.z;
        World w = a.world != null ? a.world : near.getWorld();
        int samples = 20 * 4;
        for (int i = 0; i < samples; i++) {
            double t = i / (double) samples * Math.PI * 2;
            double ox = Math.cos(t) * 3.5;
            double oz = Math.sin(t * 2) * 2.2;
            double x = cx + ox;
            double z = cz + oz;
            double y = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1.0;
            float yaw = (float) Math.toDegrees(Math.atan2(-ox, oz));
            a.echoClip.add(new Anomaly.PoseSample(x, y, z, yaw, 0f));
        }
        a.skinUuid = near.getUniqueId();
    }

    private void buildResidualPath(Anomaly a, Location start) {
        a.path.clear();
        Location cur = start.clone();
        for (int i = 0; i < 8; i++) {
            a.path.add(cur.clone());
            cur.add((rng.nextDouble() - 0.5) * 6, 0, (rng.nextDouble() - 0.5) * 6);
            cur.setY(cur.getWorld().getHighestBlockYAt(cur) + 1);
        }
    }

    private Location findSpawnSite(Player player, AnomalyType type) {
        World w = player.getWorld();
        Location base = player.getLocation();
        for (int attempt = 0; attempt < 12; attempt++) {
            double dist;
            double angle = rng.nextDouble() * Math.PI * 2;
            switch (type) {
                case SHADOW, STALKER -> dist = 18 + rng.nextDouble() * 22; // 18–40
                case OBSERVER, WATCHER -> dist = 20 + rng.nextDouble() * 25; // 20–45
                case FALSE_CONTACT -> dist = 16 + rng.nextDouble() * 12;
                case CRAWLER, RESIDUAL, VOID -> dist = 16 + rng.nextDouble() * 16;
                default -> dist = 18 + rng.nextDouble() * 20;
            }
            double x = base.getX() + Math.cos(angle) * dist;
            double z = base.getZ() + Math.sin(angle) * dist;
            int y = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1;
            if (type == AnomalyType.OBSERVER || type == AnomalyType.WATCHER) {
                y += 1 + rng.nextInt(3);
                // Prefer rooftops / high ground
                Block below = w.getBlockAt((int) x, y - 1, (int) z);
                if (below.getType().isAir()) {
                    y = w.getHighestBlockYAt((int) x, (int) z) + 1;
                }
            }
            Location loc = new Location(w, x + 0.5, y, z + 0.5);
            Vector toPlayer = player.getEyeLocation().toVector().subtract(loc.toVector());
            if (toPlayer.lengthSquared() > 1e-4) {
                loc.setDirection(toPlayer);
            }
            return loc;
        }
        return null;
    }

    private Location findCoverNear(Location from, double radius) {
        if (from == null || from.getWorld() == null) {
            return null;
        }
        World w = from.getWorld();
        for (int i = 0; i < 8; i++) {
            double x = from.getX() + (rng.nextDouble() - 0.5) * radius * 2;
            double z = from.getZ() + (rng.nextDouble() - 0.5) * radius * 2;
            int y = w.getHighestBlockYAt((int) x, (int) z);
            Block b = w.getBlockAt((int) x, y, (int) z);
            Material m = b.getType();
            if (m.isSolid() && m.isOccluding()) {
                return new Location(w, x + 0.5, y + 1, z + 0.5, from.getYaw(), 0);
            }
        }
        return null;
    }

    private void snapToGround(Anomaly a) {
        if (a.world == null) {
            return;
        }
        int y = a.world.getHighestBlockYAt((int) Math.floor(a.x), (int) Math.floor(a.z)) + 1;
        a.y = y;
    }

    private void stepToward(Anomaly a, Location target, double speed) {
        if (target == null) {
            return;
        }
        Vector d = target.toVector().subtract(new Vector(a.x, a.y, a.z));
        d.setY(0);
        if (d.lengthSquared() < 1e-4) {
            return;
        }
        d.normalize().multiply(speed);
        a.x += d.getX();
        a.z += d.getZ();
        snapToGround(a);
        a.yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
    }

    private void faceToward(Anomaly a, Location target) {
        if (target == null) {
            return;
        }
        Vector d = target.toVector().subtract(new Vector(a.x, a.y + 1.6, a.z));
        if (d.lengthSquared() < 1e-6) {
            return;
        }
        a.yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        a.pitch = (float) Math.toDegrees(-Math.asin(d.getY() / d.length()));
    }

    private boolean isObserved(Anomaly a, List<Player> viewers) {
        for (Player v : viewers) {
            if (lookingAt(v, a, 12.0) && hasLos(v, a)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyInFov(Anomaly a, List<Player> viewers) {
        for (Player v : viewers) {
            if (lookingAt(v, a, 22.0)) {
                return true;
            }
        }
        return false;
    }

    private boolean lookingAt(Player v, Anomaly a, double maxAngleDeg) {
        Location eye = v.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Vector to = new Vector(a.x - eye.getX(), (a.y + 1.0) - eye.getY(), a.z - eye.getZ());
        if (to.lengthSquared() < 1e-6) {
            return true;
        }
        to.normalize();
        double dot = dir.dot(to);
        double cos = Math.cos(Math.toRadians(maxAngleDeg));
        return dot >= cos;
    }

    private boolean hasLos(Player v, Anomaly a) {
        Location eye = v.getEyeLocation();
        Location target = new Location(a.world, a.x, a.y + 1.4, a.z);
        RayTraceResult hit = a.world.rayTraceBlocks(eye, target.toVector().subtract(eye.toVector()).normalize(),
                eye.distance(target), FluidCollisionMode.NEVER, true);
        return hit == null || hit.getHitBlock() == null
                || hit.getHitPosition().distanceSquared(target.toVector()) < 1.5;
    }

    private List<Player> nvgViewersNear(Location loc, double range) {
        List<Player> out = new ArrayList<>();
        if (loc == null || loc.getWorld() == null) {
            return out;
        }
        double r2 = range * range;
        for (Player p : loc.getWorld().getPlayers()) {
            if (!NvgGear.isWearingNvg(plugin, p)) {
                continue;
            }
            if (p.getLocation().distanceSquared(loc) <= r2) {
                out.add(p);
            }
        }
        return out;
    }

    private Player nearestViewer(Anomaly a, List<Player> viewers) {
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player v : viewers) {
            double d = v.getLocation().distanceSquared(a.location());
            if (d < bestD) {
                bestD = d;
                best = v;
            }
        }
        return best;
    }

    private Player nearestOther(Player self, double range) {
        Player best = null;
        double bestD = range * range;
        for (Player p : self.getWorld().getPlayers()) {
            if (p.equals(self)) {
                continue;
            }
            double d = p.getLocation().distanceSquared(self.getLocation());
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private int countNear(Location loc, double range) {
        int n = 0;
        double r2 = range * range;
        for (Anomaly a : byId.values()) {
            if (a.world != null && a.world.equals(loc.getWorld())
                    && a.location().distanceSquared(loc) <= r2) {
                n++;
            }
        }
        return n;
    }

    private boolean isDarkEnough(Player p) {
        long time = p.getWorld().getTime();
        boolean night = time > 12000 && time < 23000;
        int light = p.getLocation().getBlock().getLightLevel();
        return night || light <= 7;
    }

    private void cullOldest() {
        Anomaly oldest = null;
        for (Anomaly a : byId.values()) {
            if (oldest == null || a.age > oldest.age) {
                oldest = a;
            }
        }
        if (oldest != null) {
            byId.remove(oldest.id);
        }
    }

    public void syncViewer(Player viewer) {
        if (viewer == null || plugin.companions() == null || !plugin.companions().hasCompanion(viewer)) {
            return;
        }
        byte[] payload = encodeFor(viewer);
        if (payload != null) {
            viewer.sendPluginMessage(plugin, CHANNEL, payload);
        }
    }

    private void broadcast() {
        if (plugin.companions() == null) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!plugin.companions().hasCompanion(viewer)) {
                continue;
            }
            byte[] payload = encodeFor(viewer);
            if (payload != null) {
                viewer.sendPluginMessage(plugin, CHANNEL, payload);
            }
        }
    }

    private void broadcastEmpty() {
        if (plugin.companions() == null) {
            return;
        }
        byte[] empty = encodeList(List.of());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (plugin.companions().hasCompanion(viewer)) {
                viewer.sendPluginMessage(plugin, CHANNEL, empty);
            }
        }
    }

    private byte[] encodeFor(Player viewer) {
        List<Anomaly> list = new ArrayList<>();
        Location eye = viewer.getLocation();
        double r2 = SYNC_RANGE * SYNC_RANGE;
        for (Anomaly a : byId.values()) {
            if (a.world == null || !a.world.equals(viewer.getWorld())) {
                continue;
            }
            if (eye.distanceSquared(a.location()) > r2) {
                continue;
            }
            list.add(a);
        }
        list.sort((x, y) -> Double.compare(
                eye.distanceSquared(x.location()),
                eye.distanceSquared(y.location())));
        if (list.size() > 16) {
            list = list.subList(0, 16);
        }
        return encodeList(list);
    }

    private byte[] encodeList(List<Anomaly> list) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(128 + list.size() * 64);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(PROTOCOL);
            out.writeByte(Math.min(32, list.size()));
            for (int i = 0; i < Math.min(32, list.size()); i++) {
                Anomaly a = list.get(i);
                out.writeLong(a.id.getMostSignificantBits());
                out.writeLong(a.id.getLeastSignificantBits());
                out.writeByte(a.type.id);
                out.writeDouble(a.x);
                out.writeDouble(a.y);
                out.writeDouble(a.z);
                out.writeFloat(a.yaw);
                out.writeFloat(a.pitch);
                int flags = 0;
                if (a.visible) {
                    flags |= 1;
                }
                if (a.type == AnomalyType.STATIC_MAN && !a.visible) {
                    flags &= ~1;
                }
                if (a.bright) {
                    flags |= 4;
                }
                if (a.noBody) {
                    flags |= 8;
                }
                if (a.voidMask) {
                    flags |= 16;
                }
                if (a.distort) {
                    flags |= 32;
                }
                if (a.type == AnomalyType.WATCHER) {
                    flags |= 64; // eyes
                }
                out.writeByte(flags);
                out.writeFloat(a.scaleY);
                out.writeFloat(a.interference);
                out.writeFloat(a.lookProgress);
                if (a.skinUuid != null) {
                    out.writeByte(1);
                    out.writeLong(a.skinUuid.getMostSignificantBits());
                    out.writeLong(a.skinUuid.getLeastSignificantBits());
                } else {
                    out.writeByte(0);
                }
                int parts = Math.min(4, a.partCount);
                out.writeByte(parts);
                for (int p = 0; p < parts; p++) {
                    out.writeFloat(a.partOx[p]);
                    out.writeFloat(a.partOy[p]);
                    out.writeFloat(a.partOz[p]);
                }
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    public String describe(Anomaly a) {
        return String.format(Locale.ROOT, "%s %s @ %.0f %.0f %.0f",
                a.id.toString().substring(0, 8),
                a.type.name(),
                a.x, a.y, a.z);
    }
}
