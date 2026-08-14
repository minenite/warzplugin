package com.local.warz.runtime;

import com.destroystokyo.paper.ClientOption;
import com.destroystokyo.paper.SkinParts;
import com.local.warz.WarzKeys;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.config.AmmoCaliber;
import com.local.warz.model.GunDefinition;
import com.local.warz.model.RoundDefinition;
import com.local.warz.combat.ImpactEffects;
import com.local.warz.projectile.Bullet;
import com.local.warz.util.LaserBeams;
import com.local.warz.util.LaserOptics;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Deployable MQ-9 Reaper (mq9reaper): pad on ground, invisible pilot; companion draws mesh.
 */
public final class BigDroneService {
    /** Player-facing name (legacy default — prefer {@link BigDroneType#displayName()}). */
    public static final String DISPLAY_NAME = "MQ-9 Reaper";
    /** Short id for items / chat. */
    public static final String DISPLAY_ID = "mq9reaper";

    public BigDroneType typeOfPad(UUID padId) {
        if (padId == null || plugin.dronePads() == null) {
            return BigDroneType.MQ9;
        }
        return plugin.dronePads().typeOf(padId);
    }

    public BigDroneType typeOf(Player pilot) {
        return session(pilot).map(s -> s.type != null ? s.type : typeOfPad(s.parkedPadId))
                .orElse(BigDroneType.MQ9);
    }

    /** Javelin / seekers: true if this pilot's airframe is stealth. */
    public boolean isStealth(Player pilot) {
        return pilot != null && typeOf(pilot).stealth();
    }

    /** Player-facing airframe label (never hardcode MQ-9 for typed pads). */
    public static String airframeLabel(BigDroneType type) {
        return type != null ? type.displayName() : DISPLAY_NAME;
    }

    private String airframeLabel(DroneSession session) {
        if (session == null) {
            return DISPLAY_NAME;
        }
        if (session.type != null) {
            return session.type.displayName();
        }
        return airframeLabel(typeOfPad(session.parkedPadId));
    }

    public static final String CHANNEL_OPTIC = "pvpgunminus:drone_optic";
    public static final String CHANNEL_ZOOM = "pvpgunminus:drone_zoom";
    /** C2S: scroll/hotbar adjust — protocol, kind (0=zoom,1=speed,2=width), signed delta. */
    public static final String CHANNEL_ADJUST = "pvpgunminus:drone_adjust";
    /** S2C: military UAV OSD telemetry for the Fabric companion HUD. */
    public static final String CHANNEL_HUD = "pvpgunminus:drone_hud";
    /**
     * S2C: all active BigDrone airframes for every companion.
     * Scoreboard tags do not sync — remotes need this to draw the MQ-9.
     */
    public static final String CHANNEL_DRONE_VIS = "pvpgunminus:drone_vis";
    /** S2C: brief operator hit cue (edge chevron + critical + shake). */
    public static final String CHANNEL_DRONE_HIT = "pvpgunminus:drone_hit";
    public static final String PILOT_TAG = "bigdrone";

    /** Small-arms structure points — takes a lot of rounds to bring down. */
    public static final int STRUCTURE_MAX = 160;
    private static final int AIRFRAME_BAR_SEGMENTS = 20;
    /**
     * 3× MQ-9 mesh half-extents (from {@code bigdrone_mesh.bin} local AABB).
     * Local X = wings, Z = nose–tail; Y from belly up. Matches client {@code MODEL_SCALE=3}.
     */
    private static final double MESH_HALF_X = 8.285;
    private static final double MESH_HALF_Y = 1.642;
    private static final double MESH_HALF_Z = 4.431;
    /** Mid-body above pad feet (mesh Y center × scale). */
    public static final double MESH_CENTER_Y = 1.642;
    /** Max reach to open payload / radiolink on a parked hull (not long-range). */
    public static final double PARKED_USE_RANGE = 5.0;
    /** Loose axis-aligned fallback for flying pilots (OBB preferred for parked). */
    private static final double AIRFRAME_HALF_X = MESH_HALF_X;
    private static final double AIRFRAME_HALF_Y = MESH_HALF_Y;
    private static final double AIRFRAME_HALF_Z = MESH_HALF_Z;
    public static final byte HIT_EDGE_LEFT = 1;
    public static final byte HIT_EDGE_RIGHT = 2;
    public static final byte HIT_EDGE_TOP = 3;
    public static final byte HIT_EDGE_BOTTOM = 4;
    public static final byte FAULT_NONE = 0;
    public static final byte FAULT_FLIGHT = 1;
    public static final byte FAULT_PROPULSION = 2;
    public static final byte FAULT_DATALINK = 3;
    public static final byte FAULT_OPTICS = 4;
    public static final byte FAULT_FUEL = 5;
    public static final byte FAULT_STRUCTURAL = 6;
    /**
     * True while seat-mannequin damage is being mirrored onto the invisible pilot.
     * Lets explosion / melee through the pilot damage filter.
     */
    private static final ThreadLocal<Boolean> SEAT_PROXY_DAMAGE = ThreadLocal.withInitial(() -> false);

    public static boolean isSeatProxyDamage() {
        return Boolean.TRUE.equals(SEAT_PROXY_DAMAGE.get());
    }

    public static final byte ADJUST_ZOOM = 0;
    public static final byte ADJUST_SPEED = 1;
    public static final byte ADJUST_WIDTH = 2;
    /** Cycle payload-bay next-to-fire while holding LAW. */
    public static final byte ADJUST_BAY = 3;

    /** Flight mode bytes for {@link #CHANNEL_HUD}. */
    public static final byte MODE_MANUAL = 0;
    public static final byte MODE_AUTO = 1;
    public static final byte MODE_ORBIT = 2;
    public static final byte MODE_LOITER = 3;

    public static final byte FLAG_HAS_TARGET = 1;
    public static final byte FLAG_LASER_ARM = 2;
    public static final byte FLAG_RECORDING = 4;
    public static final byte FLAG_IR_ON = 8;
    public static final byte FLAG_MISSILE_WARN = 16;
    public static final byte FLAG_FLARES_READY = 32;
    public static final byte FLAG_LOCK_WARN = 64;
    public static final byte FLAG_LOCK_HARD = (byte) 128;

    public static final int FLARE_CHARGES_MAX = 3;
    /** Covers full missile life (up to ~12s) plus lock warn before launch. */
    private static final long FLARE_ACTIVE_MS = 20000L;
    private static final long MISSILE_WARN_MS = 4000L;
    private static final long LOCK_WARN_MS = 1500L;

    private static final double DRONE_IR_RANGE = 256.0;
    private static final float DRONE_IR_WIDTH = 0.14f;
    /**
     * Must match companion {@code BigDroneCameraMixin}: first-person view is shifted to the
     * nose / belly sensor ball. Laser + Hellfire aim from this origin or the tip won't sit
     * under the crosshair (eye-origin parallax).
     */
    private static final double SENSOR_FORWARD = 3.4;
    private static final double SENSOR_DOWN = 0.72;
    private static final double FAR_ENTITY_RADIUS = 192.0;

    private static final Material PAD_MATERIAL = Material.LODESTONE;
    /** Grace ticks after takeoff before terrain collision can kill the airframe. */
    private static final int COLLISION_GRACE_TICKS = 50;
    private static final float DEFAULT_FLY_SPEED = 0.05f;
    /** Down-viewer pitch when engaging autopilot. */
    private static final float DOWN_VIEW_PITCH = 72f;
    private static final int MAX_ZOOM = 8;
    /** How hard we chase the desired point on the circle (flight controller). */
    private static final double ORBIT_POS_LERP = 0.42;
    /**
     * Mouse movement (deg/tick) that counts as "I want to look somewhere else". Low
     * enough that a slow deliberate pan keeps registering — otherwise the gimbal would
     * grab the camera back mid-pan and fight you.
     */
    private static final float ORBIT_LOOK_DEADZONE_DEG = 0.5f;
    /**
     * Ticks of no mouse input before the camera re-locks onto the target. Generous on
     * purpose: it has to survive the natural pauses inside a look-around, so the camera
     * only re-locks once you've actually settled.
     */
    private static final int ORBIT_GIMBAL_IDLE_TICKS = 8;
    /** Cap on camera tracking (deg/tick) — only bites while catching up to a new target. */
    private static final float ORBIT_GIMBAL_MAX_STEP_DEG = 5.0f;
    /** Beyond this per-tick step (blocks) a glide can't keep up and we snap instead. */
    private static final double GLIDE_MAX_STEP = 3.0;
    /** Flight drag eats part of an applied velocity; bias up so the glide lands on target. */
    private static final double GLIDE_DRAG_COMP = 1.1;
    private static final double ORBIT_MIN_RADIUS = 8.0;
    private static final double ORBIT_MAX_RADIUS = 96.0;
    private static final double ORBIT_WIDTH_STEP = 4.0;
    private static final int ORBIT_TERRAIN_MAX_LIFT = 24;
    /** Angular cap (rad/tick) so a tight circle doesn't spin absurdly fast. */
    private static final double ORBIT_MAX_ANGLE_STEP = 0.05;
    /** Blocks/tick the drone may transit toward a NEW circle after a retarget. */
    private static final double ORBIT_TRANSIT_SPEED = 2.2;
    /** Blocks/tick vertical while orbiting. */
    private static final double ORBIT_VERT_SPEED = 0.8;
    /** Minimum height held above the POI. */
    private static final double ORBIT_MIN_HEIGHT_OVER_POI = 4.0;
    /** Blocks/tick at 1.0x drone speed while on autopilot (not orbiting). */
    private static final double CRUISE_SPEED = 0.85;
    private static final double DRONE_LAW_ACCURACY = 0.004;

    /** Fixed-wing manual: min/max airspeed as fraction of cruise×speed dial. */
    private static final double FW_MIN_SPEED_FRAC = 0.55;
    private static final double FW_MAX_SPEED_FRAC = 1.25;
    private static final double FW_ACCEL = 0.038;
    private static final double FW_BANK_RATE_DEG = 2.8;
    private static final double FW_PITCH_RATE = 0.048;
    private static final double FW_STALL_SINK = 0.09;
    private static final float FW_FLY_SPEED = 0.02f;
    /** Fraction of cruise IAS required before the nose can rotate / climb. */
    private static final double FW_ROTATE_SPEED_FRAC = 0.62;
    /** Clearance (blocks) treated as runway / touchdown contact. */
    private static final double FW_GROUND_CLEARANCE = 3.2;
    /** Must be this close to the home pad (horiz) to park via Exit. */
    private static final double LANDING_PARK_DIST = 10.0;
    /** Extra aft pull for small airframes (matches companion BigDroneCameraMixin). */
    private static final double SENSOR_SMALL_AFT_PULL = 2.8;

    public enum OpticMode {
        NORMAL, NVG, THERMAL
    }

    /**
     * Flight phase label for HUD / gating. TAKEOFF/LANDING/LANDED are soft labels only —
     * stick always owns the airframe (no scripted climb or approach).
     */
    public enum FlightPhase {
        CRUISE,
        TAKEOFF,
        LANDING,
        /** Wheels on deck after approach / touchdown (HUD shows LANDED). */
        LANDED
    }

    private final WarzPlugin plugin;
    private final Map<UUID, DroneSession> sessions = new ConcurrentHashMap<>();
    /** Shot-down airframes diving to impact (drawn via drone_vis; not tied to a pilot session). */
    private final Map<UUID, CrashWreck> crashes = new ConcurrentHashMap<>();
    /** Recent impact zones — ground deaths from the dive blast / fire get a kill-feed line. */
    private final Map<UUID, CrashKillZone> crashKillZones = new ConcurrentHashMap<>();
    /** playerId → crashKillZone id (set while taking crash damage). */
    private final Map<UUID, UUID> markedCrashVictim = new ConcurrentHashMap<>();
    /** Victims shredded by a spinning MQ-9 prop → custom death line. */
    private final Map<UUID, String> markedPropVictim = new ConcurrentHashMap<>();
    /** UAV weapon kills → death-message line (avoids vanilla + broadcast doubles). */
    private final Map<UUID, Component> markedWeaponKillFeed = new ConcurrentHashMap<>();
    /** Unmanned MQ-9 after seat operator killed — 10s window for takeover. */
    private static final int ORPHAN_TICKS = 200;
    private static final double ORPHAN_CRUISE_SPEED = 0.35;
    /** Fuel units per can (matches {@link DronePadService#FUEL_UNITS_PER_CAN}). */
    private static final int FUEL_UNITS_PER_CAN = 2400;

    private final Map<UUID, OrphanFlight> orphans = new ConcurrentHashMap<>();
    /** Pilot/pad UUID → last structure hit ms (avoids direct-hit + splash double-dip). */
    private final Map<UUID, Long> recentStructureHitMs = new ConcurrentHashMap<>();
    /** Drone-launched heat-seeking AA missiles. */
    private final List<AaMissile> aaMissiles = new ArrayList<>();
    private static final double AA_SPEED = 1.35;
    private static final int AA_MAX_TICKS = 280;
    private static final double AA_HIT_RADIUS = 4.2;
    private static final double AA_ACQUIRE_RANGE = 180.0;
    private static final double AA_ACQUIRE_DOT = 0.55;
    private static final int AA_MIN_FLIGHT = 8;
    /** AGM-114R9X Hellfire — laser-guided kinetic blade warhead (no boom). */
    private final List<HellfireR9x> r9xMissiles = new ArrayList<>();
    private static final double R9X_SPEED = 1.45;
    private static final int R9X_MAX_TICKS = 320;
    private static final int R9X_MIN_FLIGHT = 10;
    private static final double R9X_HIT_RADIUS = 1.6;
    private static final double R9X_KILL_RADIUS = 2.75;
    private static final double R9X_RANGE = 420.0;
    private static final int R9X_BLADE_DEPLOY_DIST = 8;
    /** Laser / glide / dual / multi guided UAV strikes (non-R9X, non-IR-AA). */
    private final List<GuidedStrike> guidedStrikes = new ArrayList<>();
    private static final int GUIDED_MIN_FLIGHT = 8;
    private static final double GUIDED_HIT_RADIUS = 1.85;
    /** HUD guidance state bytes (companion OSD). */
    public static final byte GUIDE_NONE = 0;
    public static final byte GUIDE_TRACK = 1;
    public static final byte GUIDE_LASER = 2;
    public static final byte GUIDE_RADAR = 3;
    public static final byte GUIDE_LOST = 4;
    public static final byte GUIDE_REACQUIRE = 5;
    public static final byte GUIDE_IIR = 6;
    public static final byte GUIDE_MMW = 7;
    public static final byte GUIDE_SAL = 8;
    public static final byte GUIDE_LOCK = 9;
    /** Seat-killed operators — next respawn goes to world spawn (full death cycle). */
    private final Set<UUID> forceWorldSpawnOnRespawn = ConcurrentHashMap.newKeySet();

    private static final float CRASH_PITCH_START = 28f;
    private static final float CRASH_PITCH_MAX = 58f;
    private static final double CRASH_GRAVITY = 0.045;
    private static final double CRASH_MAX_FALL = 1.85;
    private static final int CRASH_FIRE_RADIUS = 7;
    private static final float CRASH_BLAST_POWER = 7.5f;
    private static final long CRASH_KILL_WINDOW_MS = 12_000L;
    private static final double CRASH_KILL_RADIUS = 16.0;

    public BigDroneService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isPiloting(Player player) {
        return player != null && sessions.containsKey(player.getUniqueId());
    }

    /** Online pilots currently in a BigDrone session. */
    public List<Player> onlinePilots() {
        List<Player> out = new ArrayList<>();
        for (UUID id : sessions.keySet()) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null && p.isOnline()) {
                out.add(p);
            }
        }
        return out;
    }

    /** World position of the airframe center (Javelin lock / missile aim point). */
    public Location droneWorldLocation(Player pilot) {
        if (!isPiloting(pilot)) {
            return null;
        }
        // Mid-body of the 3× mesh (matches parked airframeCenter).
        return pilot.getLocation().clone().add(0, MESH_CENTER_Y, 0);
    }

    /**
     * Ray vs inflated MQ-9 AABB (larger than the invisible pilot).
     * @return nearest piloting player whose airframe the segment intersects
     */
    public Optional<Player> rayTraceAirframe(Location start, Vector direction, double range, UUID excludeShooter) {
        if (start == null || start.getWorld() == null || direction == null || range <= 0) {
            return Optional.empty();
        }
        Vector dir = direction.clone();
        if (dir.lengthSquared() < 1.0e-10) {
            return Optional.empty();
        }
        dir.normalize();
        Player best = null;
        double bestT = range + 1.0;
        for (Player pilot : onlinePilots()) {
            if (excludeShooter != null && excludeShooter.equals(pilot.getUniqueId())) {
                continue;
            }
            if (!start.getWorld().equals(pilot.getWorld())) {
                continue;
            }
            Location c = droneWorldLocation(pilot);
            if (c == null) {
                continue;
            }
            float scale = typeOf(pilot).meshScale();
            double t = rayAabbT(start.toVector(), dir, c.toVector(),
                    AIRFRAME_HALF_X * scale, AIRFRAME_HALF_Y * scale, AIRFRAME_HALF_Z * scale);
            if (t >= 0.0 && t <= range && t < bestT) {
                bestT = t;
                best = pilot;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Ray vs parked MQ-9 oriented hull (mesh OBB) — payload bay / radiolink / salvage.
     * Only hits the drawn airframe, not empty air around a fat Interaction box.
     */
    public Optional<DronePadService.ParkedPad> rayTraceParkedPad(Player player, double range) {
        if (player == null || plugin.dronePads() == null || range <= 0) {
            return Optional.empty();
        }
        Location start = player.getEyeLocation();
        Vector dir = start.getDirection();
        if (dir.lengthSquared() < 1.0e-10) {
            return Optional.empty();
        }
        dir.normalize();
        DronePadService.ParkedPad best = null;
        double bestT = range + 1.0;
        for (DronePadService.ParkedPad pad : plugin.dronePads().parkedForVis()) {
            Location c = plugin.dronePads().airframeCenter(pad);
            if (c == null || c.getWorld() == null || !c.getWorld().equals(start.getWorld())) {
                continue;
            }
            double t = rayMeshObbT(start.toVector(), dir, c.toVector(), pad.yaw);
            if (t >= 0.0 && t <= range && t < bestT) {
                bestT = t;
                best = pad;
            }
        }
        return Optional.ofNullable(best);
    }

    /** True when the player's look ray intersects this pad's mesh OBB within range. */
    public boolean lookingAtParkedHull(Player player, DronePadService.ParkedPad pad, double range) {
        if (player == null || pad == null || plugin.dronePads() == null || range <= 0) {
            return false;
        }
        Location start = player.getEyeLocation();
        Location c = plugin.dronePads().airframeCenter(pad);
        if (c == null || c.getWorld() == null || !c.getWorld().equals(start.getWorld())) {
            return false;
        }
        // Must be near the airframe — Interaction catcher is wide and would otherwise
        // accept looks from far outside normal use distance.
        if (start.distanceSquared(c) > range * range) {
            return false;
        }
        Vector dir = start.getDirection();
        if (dir.lengthSquared() < 1.0e-10) {
            return false;
        }
        dir.normalize();
        double t = rayMeshObbT(start.toVector(), dir, c.toVector(), pad.yaw);
        return t >= 0.0 && t <= range;
    }

    /** Standing close enough and looking at the parked mesh (payload / radiolink). */
    public boolean canUseParkedHull(Player player, DronePadService.ParkedPad pad) {
        return lookingAtParkedHull(player, pad, PARKED_USE_RANGE);
    }

    /** Avoid spamming empty drone_vis forever after the last exit. */
    private boolean lastVisWasEmpty = true;

    /**
     * Datalink quality 0–1 via {@link DroneDatalinkService} when seat-linked.
     */
    public double datalinkSignal(Location airframe, String seatKey, Location seatLoc) {
        if (plugin.datalink() != null) {
            return plugin.datalink().computeSignal(airframe, seatKey, seatLoc);
        }
        if (seatLoc == null || airframe == null || airframe.getWorld() == null
                || seatLoc.getWorld() == null || !seatLoc.getWorld().equals(airframe.getWorld())) {
            return 1.0;
        }
        double dist = airframe.distance(seatLoc);
        return Math.max(0.0, Math.min(1.0, 1.0 - dist / 180.0));
    }

    private static boolean isCleanParkExit(String reason) {
        return reason != null && (reason.equals("manual") || reason.equals("command")
                || reason.equals("disconnect") || reason.equals("landing"));
    }

    /**
     * True when the airframe is still flying — not near the pad and not skimming terrain.
     * Clean exits while airborne abandon the MQ-9 into a crash dive.
     */
    /** Public for hydrazine / external systems — true while the airframe is still flying. */
    public boolean isAirframeAirbornePublic(Player pilot) {
        if (pilot == null) {
            return false;
        }
        DroneSession session = sessions.get(pilot.getUniqueId());
        return session != null && isAirframeAirborne(pilot, session);
    }

    private boolean isAirframeAirborne(Player pilot, DroneSession session) {
        Location air = droneWorldLocation(pilot);
        if (air == null || air.getWorld() == null) {
            return true;
        }
        World world = air.getWorld();
        // Landing at / near the home pad counts as on the ground.
        if (session != null && session.parkedPadId != null && plugin.dronePads() != null) {
            Optional<DronePadService.ParkedPad> pad = plugin.dronePads().padById(session.parkedPadId);
            if (pad.isPresent()) {
                Location padFeet = plugin.dronePads().airframeLocation(pad.get());
                if (padFeet != null && padFeet.getWorld() != null && padFeet.getWorld().equals(world)
                        && air.distanceSquared(padFeet) < 6.0 * 6.0) {
                    return false;
                }
            }
        }
        RayTraceResult hit = world.rayTraceBlocks(
                air, new Vector(0, -1, 0), 48.0, FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitPosition() == null) {
            return true;
        }
        double clearance = air.getY() - hit.getHitPosition().getY();
        return clearance > 3.5;
    }

    /** Segment previous→current vs parked MQ-9 AABB. */
    public Optional<DronePadService.ParkedPad> traceParkedSegment(Location from, Location to, UUID excludeShooter) {
        if (from == null || to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())
                || plugin.dronePads() == null) {
            return Optional.empty();
        }
        Vector delta = to.toVector().subtract(from.toVector());
        double len = delta.length();
        if (len < 1.0e-4) {
            return Optional.empty();
        }
        Vector dir = delta.clone().normalize();
        DronePadService.ParkedPad best = null;
        double bestT = len + 1.0;
        for (DronePadService.ParkedPad pad : plugin.dronePads().parkedForVis()) {
            Location c = plugin.dronePads().airframeCenter(pad);
            if (c == null || c.getWorld() == null || !c.getWorld().equals(from.getWorld())) {
                continue;
            }
            double t = rayMeshObbT(from.toVector(), dir, c.toVector(), pad.yaw);
            if (t >= 0.0 && t <= len && t < bestT) {
                bestT = t;
                best = pad;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Gun hit on a parked UAV — structure damage, no pilot HP.
     * @return true if the hit was consumed
     */
    public boolean absorbParkedHit(DronePadService.ParkedPad pad, Player shooter, GunDefinition gun, Location loc) {
        return absorbParkedHit(pad, shooter, gun, loc, null);
    }

    public boolean absorbParkedHit(DronePadService.ParkedPad pad, Player shooter, GunDefinition gun,
                                   Location loc, RoundDefinition round) {
        if (pad == null || plugin.dronePads() == null) {
            return false;
        }
        if (isParkedPadInUse(pad.id)) {
            return false;
        }
        BigDroneType type = plugin.dronePads().typeOf(pad);
        int dmg = structureDamageFor(gun, type, round);
        if (dmg <= 0) {
            dmg = 1;
        }
        recentStructureHitMs.put(pad.id, System.currentTimeMillis());
        pad.structureHp = Math.max(0, pad.structureHp - dmg);
        int maxHp = type.structureMax();
        Location at = loc != null ? loc : plugin.dronePads().airframeCenter(pad);
        if (at != null && at.getWorld() != null) {
            at.getWorld().spawnParticle(Particle.CRIT, at, 6, 0.4, 0.3, 0.4, 0.02);
        }
        if (shooter != null && shooter.isOnline()) {
            boolean critical = pad.structureHp <= maxHp / 4;
            playAirframeHitSound(shooter, at != null ? at : shooter.getEyeLocation(), critical);
            int pct = (int) Math.round(100.0 * pad.structureHp / Math.max(1, maxHp));
            shooter.sendActionBar(Component.text(type.displayName() + " (parked) ", NamedTextColor.GRAY)
                    .append(Component.text(pct + "%", pct > 25 ? NamedTextColor.GREEN : NamedTextColor.RED)));
        }
        if (pad.structureHp <= 0) {
            detonateParkedAirframe(pad, shooter);
        } else {
            plugin.dronePads().persistPads();
        }
        return true;
    }

    /** Segment previous→current vs airframe (projectile flight). */
    public Optional<Player> traceAirframeSegment(Location from, Location to, UUID excludeShooter) {
        if (from == null || to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return Optional.empty();
        }
        Vector delta = to.toVector().subtract(from.toVector());
        double len = delta.length();
        if (len < 1.0e-4) {
            return Optional.empty();
        }
        return rayTraceAirframe(from, delta, len, excludeShooter);
    }

    /**
     * Absorb a gun hit into airframe structure (no pilot HP loss).
     * @return true if the hit was consumed by a piloted UAV
     */
    public boolean absorbBulletHit(Player pilot, Player shooter, Location hitAt, GunDefinition gun) {
        return absorbBulletHit(pilot, shooter, hitAt, gun, null);
    }

    public boolean absorbBulletHit(Player pilot, Player shooter, Location hitAt,
                                   GunDefinition gun, RoundDefinition round) {
        if (pilot == null || !isPiloting(pilot)) {
            return false;
        }
        DroneSession session = sessions.get(pilot.getUniqueId());
        if (session == null) {
            return false;
        }
        BigDroneType type = session.type != null ? session.type : BigDroneType.MQ9;
        int dmg = structureDamageFor(gun, type, round);
        if (dmg <= 0) {
            return true;
        }
        recentStructureHitMs.put(pilot.getUniqueId(), System.currentTimeMillis());
        int before = session.structureHp;
        session.structureHp = Math.max(0, session.structureHp - dmg);
        if (session.parkedPadId != null && plugin.dronePads() != null) {
            plugin.dronePads().padById(session.parkedPadId).ifPresent(pad -> pad.structureHp = session.structureHp);
        }
        byte fault = maybeTriggerFault(session, before, session.structureHp);
        byte edge = hitEdgeToward(pilot, shooter != null ? shooter.getEyeLocation() : hitAt);
        byte severity = (byte) Math.min(100, 18 + dmg * 4 + (fault != FAULT_NONE ? 35 : 0));
        sendHitCue(pilot, edge, fault, severity);
        playAirframeHitSound(pilot, fault != FAULT_NONE);
        if (shooter != null && shooter.isOnline()) {
            sendShooterStructureBar(shooter, session);
        }
        if (session.structureHp <= 0) {
            UUID shooterId = shooter != null ? shooter.getUniqueId() : null;
            String weapon = gun != null && gun.fileName() != null ? gun.fileName() : "gunfire";
            if (round != null && DronePadService.ROUND_AA.equalsIgnoreCase(round.fileName())) {
                weapon = "aa_missile";
            }
            shootDown(pilot, shooterId, weapon);
        }
        return true;
    }

    /**
     * Rocket / bomb splash vs airborne + parked airframes (pilots are never HP-splashed).
     */
    public void absorbExplosionSplash(Location impact, double radius, Player shooter,
                                      GunDefinition gun, RoundDefinition round) {
        if (impact == null || impact.getWorld() == null || radius <= 0) {
            return;
        }
        World world = impact.getWorld();
        double r2 = radius * radius;
        long now = System.currentTimeMillis();
        UUID shooterId = shooter != null ? shooter.getUniqueId() : null;

        for (UUID pilotId : sessions.keySet().toArray(new UUID[0])) {
            if (shooterId != null && shooterId.equals(pilotId)) {
                continue; // own airframe
            }
            Long recent = recentStructureHitMs.get(pilotId);
            if (recent != null && now - recent < 280L) {
                continue; // already ate the direct hit this detonation
            }
            Player pilot = Bukkit.getPlayer(pilotId);
            if (pilot == null || !pilot.isOnline() || !isPiloting(pilot)) {
                continue;
            }
            Location at = droneWorldLocation(pilot);
            if (at == null || at.getWorld() == null || !at.getWorld().equals(world)) {
                continue;
            }
            if (at.distanceSquared(impact) > r2) {
                continue;
            }
            absorbBulletHit(pilot, shooter, at, gun, round);
        }

        if (plugin.dronePads() == null) {
            return;
        }
        for (DronePadService.ParkedPad pad : plugin.dronePads().allPads()) {
            if (pad == null || isParkedPadInUse(pad.id)) {
                continue;
            }
            Long recent = recentStructureHitMs.get(pad.id);
            if (recent != null && now - recent < 280L) {
                continue;
            }
            Location at = plugin.dronePads().airframeCenter(pad);
            if (at == null || at.getWorld() == null || !at.getWorld().equals(world)) {
                continue;
            }
            if (at.distanceSquared(impact) > r2) {
                continue;
            }
            recentStructureHitMs.put(pad.id, now);
            absorbParkedHit(pad, shooter, gun, at, round);
        }
    }

    /**
     * Structure damage. Rockets / AA punch through stealth plating (full damage).
     * Small arms use {@link BigDroneType#bulletDamageTaken()}.
     */
    private static int structureDamageFor(GunDefinition gun, BigDroneType type) {
        return structureDamageFor(gun, type, null);
    }

    private static int structureDamageFor(GunDefinition gun, BigDroneType type, RoundDefinition round) {
        BigDroneType t = type != null ? type : BigDroneType.MQ9;
        if (gun == null) {
            return Math.max(1, Math.round(1 * t.bulletDamageTaken()));
        }
        String id = gun.fileName() == null ? "" : gun.fileName().toLowerCase(Locale.ROOT);
        String roundId = round != null && round.fileName() != null
                ? round.fileName().toLowerCase(Locale.ROOT) : "";
        String cal = AmmoCaliber.normalize(gun.ammoCaliber());
        // Guided / dumb rockets ignore composite stealth armor — visual AA counterplay.
        if (id.contains("javelin") || "rocket_aa".equals(roundId)) {
            return Math.max(90, (t.structureMax() * 2) / 3);
        }
        // R9X kinetic blades shred a parked/airborne hull locally — no blast radius.
        if ("rocket_r9x".equals(roundId)) {
            return Math.max(70, t.structureMax() / 2);
        }
        if (cal.equals("rocket") || id.equals("law") || id.equals("law_drone") || id.contains("rpg")
                || id.contains("m79") || id.contains("aa12") || cal.contains("40mm")) {
            int base = Math.max(36, (int) Math.round(48 * (160.0 / Math.max(100, t.structureMax()))));
            if ("rocket_he".equals(roundId)) {
                return Math.max(base, 55);
            }
            if ("rocket_ap".equals(roundId)) {
                return Math.max(base, 50);
            }
            return base;
        }
        float taken = t.bulletDamageTaken();
        int base;
        if (gun.gunDamage() >= 18 || cal.contains("50") || cal.contains(".50")) {
            base = 2;
        } else {
            base = 1;
        }
        return Math.max(1, Math.round(base * taken));
    }

    private byte maybeTriggerFault(DroneSession session, int before, int after) {
        int max = session.type != null ? session.type.structureMax() : STRUCTURE_MAX;
        // Threshold crossings → amber/red system warnings
        int[] marks = {max * 3 / 4, max / 2, max / 4, max / 10};
        byte[] faults = {FAULT_OPTICS, FAULT_DATALINK, FAULT_FLIGHT, FAULT_PROPULSION, FAULT_FUEL, FAULT_STRUCTURAL};
        for (int mark : marks) {
            if (before > mark && after <= mark) {
                byte fault = faults[ThreadLocalRandom.current().nextInt(faults.length)];
                session.lastFault = fault;
                session.faultUntilMs = System.currentTimeMillis() + 4500L;
                return fault;
            }
        }
        // Occasional critical on heavy hits even between thresholds
        if (before - after >= 40 && ThreadLocalRandom.current().nextBoolean()) {
            byte fault = faults[ThreadLocalRandom.current().nextInt(faults.length)];
            session.lastFault = fault;
            session.faultUntilMs = System.currentTimeMillis() + 4500L;
            return fault;
        }
        return FAULT_NONE;
    }

    private static byte hitEdgeToward(Player pilot, Location threat) {
        if (pilot == null || threat == null || threat.getWorld() == null
                || !threat.getWorld().equals(pilot.getWorld())) {
            return HIT_EDGE_BOTTOM;
        }
        Vector to = threat.toVector().subtract(pilot.getEyeLocation().toVector());
        if (to.lengthSquared() < 1.0e-6) {
            return HIT_EDGE_BOTTOM;
        }
        to.normalize();
        float yaw = pilot.getLocation().getYaw();
        float pitch = pilot.getLocation().getPitch();
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        // Camera basis: forward, right, up
        Vector forward = new Vector(-Math.sin(yawRad) * Math.cos(pitchRad), -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad));
        Vector right = new Vector(Math.cos(yawRad), 0, Math.sin(yawRad));
        Vector up = right.clone().crossProduct(forward);
        double lx = to.dot(right);
        double ly = to.dot(up);
        double lz = to.dot(forward);
        // Prefer lateral/vertical over forward (incoming-fire RWR style)
        if (Math.abs(lx) >= Math.abs(ly) && (Math.abs(lx) > 0.15 || lz < 0.2)) {
            return lx >= 0 ? HIT_EDGE_RIGHT : HIT_EDGE_LEFT;
        }
        return ly >= 0 ? HIT_EDGE_TOP : HIT_EDGE_BOTTOM;
    }

    private void sendHitCue(Player pilot, byte edge, byte fault, byte severity) {
        if (pilot == null || plugin.companions() == null || !plugin.companions().hasCompanion(pilot)) {
            // Vanilla fallback
            if (fault != FAULT_NONE) {
                pilot.sendActionBar(Component.text(faultLabel(fault), NamedTextColor.GOLD));
            }
            return;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(1); // protocol
            out.writeByte(edge);
            out.writeByte(fault);
            out.writeByte(severity);
            out.flush();
            pilot.sendPluginMessage(plugin, CHANNEL_DRONE_HIT, bos.toByteArray());
        } catch (IOException ignored) {
        }
    }

    private static String faultLabel(byte fault) {
        return switch (fault) {
            case FAULT_FLIGHT -> "⚠ FLIGHT CTRL DEGRADED";
            case FAULT_PROPULSION -> "⚠ PROPULSION FAULT";
            case FAULT_DATALINK -> "⚠ DATALINK UNSTABLE";
            case FAULT_OPTICS -> "⚠ OPTICS DEGRADED";
            case FAULT_FUEL -> "⚠ FUEL SYS WARNING";
            case FAULT_STRUCTURAL -> "⚠ STRUCTURAL DAMAGE";
            default -> "⚠ IMPACT";
        };
    }

    private void playAirframeHitSound(Player pilot, boolean critical) {
        playAirframeHitSound(pilot, pilot.getEyeLocation(), critical);
    }

    /** Metallic netherite/anvil hit — played to the hearer at the impact (pilot or shooter). */
    private void playAirframeHitSound(Player hearer, Location at, boolean critical) {
        if (hearer == null || !hearer.isOnline()) {
            return;
        }
        Location src = at != null ? at : hearer.getEyeLocation();
        float vol = critical ? 0.55f : 0.32f;
        float pitch = critical ? 0.7f : (0.85f + ThreadLocalRandom.current().nextFloat() * 0.35f);
        hearer.playSound(src, Sound.BLOCK_ANVIL_HIT, vol * 0.45f, pitch + 0.4f);
        hearer.playSound(src, Sound.BLOCK_NETHERITE_BLOCK_HIT, vol, pitch);
        if (critical) {
            hearer.playSound(src, Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.25f, 1.6f);
        }
    }

    private void sendShooterStructureBar(Player shooter, DroneSession session) {
        int hp = Math.max(0, session.structureHp);
        int max = session.type != null ? session.type.structureMax() : STRUCTURE_MAX;
        int filled = (int) Math.round((hp / (double) Math.max(1, max)) * AIRFRAME_BAR_SEGMENTS);
        filled = Math.max(0, Math.min(AIRFRAME_BAR_SEGMENTS, filled));
        StringBuilder bar = new StringBuilder(AIRFRAME_BAR_SEGMENTS);
        for (int i = 0; i < AIRFRAME_BAR_SEGMENTS; i++) {
            bar.append(i < filled ? '█' : '─');
        }
        int pct = (int) Math.round(100.0 * hp / Math.max(1, max));
        NamedTextColor color = pct > 50 ? NamedTextColor.GREEN
                : (pct > 25 ? NamedTextColor.GOLD : NamedTextColor.RED);
        String name = session.type != null ? session.type.displayName() : DISPLAY_NAME;
        shooter.sendActionBar(Component.text(name + " ", NamedTextColor.GRAY)
                .append(Component.text(bar.toString(), color))
                .append(Component.text(" " + pct + "%", NamedTextColor.DARK_GRAY)));
    }

    /**
     * Ray vs mesh OBB (client applies {@code Ry(-yaw)} then scale).
     * @return entry t ≥ 0, or -1
     */
    private static double rayMeshObbT(Vector origin, Vector dir, Vector center, float yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        double ox = origin.getX() - center.getX();
        double oy = origin.getY() - center.getY();
        double oz = origin.getZ() - center.getZ();
        // Inverse of client Ry(-yaw): lx = cos*wx + sin*wz, lz = -sin*wx + cos*wz
        double lx = cos * ox + sin * oz;
        double ly = oy;
        double lz = -sin * ox + cos * oz;
        double dx = cos * dir.getX() + sin * dir.getZ();
        double dy = dir.getY();
        double dz = -sin * dir.getX() + cos * dir.getZ();
        return rayAabbLocalT(lx, ly, lz, dx, dy, dz, MESH_HALF_X, MESH_HALF_Y, MESH_HALF_Z);
    }

    /** Slab test in local space against ±half extents. */
    private static double rayAabbLocalT(double ox, double oy, double oz,
                                        double dx, double dy, double dz,
                                        double hx, double hy, double hz) {
        double tmin = 0.0;
        double tmax = Double.POSITIVE_INFINITY;
        double[] o = {ox, oy, oz};
        double[] d = {dx, dy, dz};
        double[] min = {-hx, -hy, -hz};
        double[] max = {hx, hy, hz};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1.0e-10) {
                if (o[i] < min[i] || o[i] > max[i]) {
                    return -1;
                }
                continue;
            }
            double inv = 1.0 / d[i];
            double t1 = (min[i] - o[i]) * inv;
            double t2 = (max[i] - o[i]) * inv;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) {
                return -1;
            }
        }
        return tmin >= 0 ? tmin : (tmax >= 0 ? 0 : -1);
    }

    /** Slab test: ray origin+dir*t vs AABB centered at c. @return t or -1 */
    private static double rayAabbT(Vector origin, Vector dir, Vector c,
                                   double hx, double hy, double hz) {
        return rayAabbLocalT(
                origin.getX() - c.getX(), origin.getY() - c.getY(), origin.getZ() - c.getZ(),
                dir.getX(), dir.getY(), dir.getZ(),
                hx, hy, hz);
    }

    public void warnMissileInbound(Player pilot) {
        session(pilot).ifPresent(session -> {
            session.missileWarnUntilMs = System.currentTimeMillis() + MISSILE_WARN_MS;
            session.lockWarnUntilMs = 0L;
            session.lockHard = false;
            pilot.sendActionBar(Component.text("⚠ MISSILE INBOUND — DEPLOY FLARES", NamedTextColor.RED));
            pilot.playSound(pilot.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.5f);
        });
    }

    public void clearMissileWarn(Player pilot) {
        session(pilot).ifPresent(session -> session.missileWarnUntilMs = 0L);
    }

    /** Pilot alert while a Javelin is painting / hard-locked on this drone. */
    public void warnJavelinLock(Player pilot, boolean hardLocked) {
        session(pilot).ifPresent(session -> {
            long now = System.currentTimeMillis();
            session.lockWarnUntilMs = now + LOCK_WARN_MS;
            session.lockHard = hardLocked;
            if (now < session.missileWarnUntilMs) {
                return; // missile inbound owns the banner
            }
            if (hardLocked) {
                pilot.sendActionBar(Component.text("⚠ JAVELIN LOCKED ON YOU", NamedTextColor.RED));
                if (session.ticks % 12 == 0) {
                    pilot.playSound(pilot.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.8f);
                }
            } else {
                pilot.sendActionBar(Component.text("⚠ JAVELIN ACQUIRING LOCK", NamedTextColor.GOLD));
                if (session.ticks % 20 == 0) {
                    pilot.playSound(pilot.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.2f);
                }
            }
        });
    }

    public boolean isFlareActive(Player pilot) {
        DroneSession session = sessions.get(pilot.getUniqueId());
        return session != null && System.currentTimeMillis() < session.flareActiveUntilMs;
    }

    /** End the decoy window after it eats one missile (redeploy for the next). */
    public void consumeFlareDecoy(Player pilot) {
        session(pilot).ifPresent(session -> {
            session.flareActiveUntilMs = 0L;
            refreshFlareItem(pilot, session);
        });
    }

    public void deployFlares(Player player) {
        DroneSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.flareCharges <= 0) {
            player.sendMessage(Component.text("No flares left", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.5f);
            return;
        }
        long now = System.currentTimeMillis();
        if (now < session.flareCooldownUntilMs) {
            player.sendMessage(Component.text("Flares recharging…", NamedTextColor.YELLOW));
            return;
        }
        session.flareCharges--;
        if (session.parkedPadId != null && plugin.dronePads() != null) {
            plugin.dronePads().padById(session.parkedPadId).ifPresent(pad -> {
                pad.flareCharges = Math.max(0, session.flareCharges);
                plugin.dronePads().persistPads();
            });
        }
        session.flareActiveUntilMs = now + FLARE_ACTIVE_MS;
        session.flareCooldownUntilMs = now + 1500L;
        refreshFlareItem(player, session);
        Location at = droneWorldLocation(player);
        if (plugin.javelin() != null && at != null) {
            plugin.javelin().spawnFlareBurst(at);
        }
        player.sendMessage(Component.text(
                "FLARES DEPLOYED (" + session.flareCharges + " LEFT)",
                NamedTextColor.GOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.1f, 0.55f);
    }

    /**
     * Destroy the airframe — eject pilot to the pad (alive), start a diving crash wreck.
     * Ground impact owns the crater / fire / boom (no mid-air detonation).
     */
    public void shootDown(Player pilot, UUID shooterId, String weapon) {
        if (!isPiloting(pilot)) {
            return;
        }
        String air = airframeLabel(sessions.get(pilot.getUniqueId()));
        beginCrashFromPilot(pilot);
        Player shooter = shooterId != null ? plugin.getServer().getPlayer(shooterId) : null;
        // Pad first — never leave a dead body in the sky.
        exit(pilot, "shot down");
        // Brief i-frames so a late splash tick can't kill them after landing.
        pilot.setNoDamageTicks(Math.max(pilot.getNoDamageTicks(), 40));
        if (pilot.getFireTicks() > 0) {
            pilot.setFireTicks(0);
        }
        // Public kill feed for everyone (including operator)
        if (shooter != null) {
            boolean javelin = weapon != null && weapon.equalsIgnoreCase("javelin");
            boolean aa = weapon != null && weapon.equalsIgnoreCase("aa_missile");
            String verb = javelin ? "javelined" : (aa ? "heat-seekered" : "shot down");
            Component line = Component.empty()
                    .append(feedPlayer(shooter.getUniqueId(), shooter.getName()))
                    .append(feedLegacy(" &7&o" + verb + " "))
                    .append(feedPlayer(pilot.getUniqueId(), pilot.getName()))
                    .append(feedLegacy("&7&o's &6" + air));
            if (!javelin && !aa) {
                line = line.append(feedLegacy(" &7&owith &f" + gunKillFeedName(weapon)));
            }
            broadcastFeed(line);
        }
    }

    /** Plain gun label for kill feed ("with a M4A1"). */
    private String gunKillFeedName(String weaponId) {
        if (weaponId == null || weaponId.isBlank() || weaponId.equalsIgnoreCase("gunfire")) {
            return "gun";
        }
        return plugin.registry().get(weaponId)
                .map(def -> {
                    String raw = def.displayName() == null ? def.fileName() : def.displayName();
                    String plain = PlainTextComponentSerializer.plainText().serialize(ItemFactory.colorize(raw));
                    if (plain == null || plain.isBlank()) {
                        return def.fileName();
                    }
                    return plain.trim();
                })
                .orElse(weaponId);
    }

    private static String feedName(String name) {
        return name == null || name.isBlank() ? "Someone" : name;
    }

    /** Gold name with profile-stats hover when possible. */
    private Component feedPlayer(UUID id, String name) {
        String n = feedName(name);
        if (plugin.profileStats() != null) {
            return plugin.profileStats().nameWithStatsHover(id, n, "&6", false);
        }
        return ItemFactory.colorize("&6" + n).decoration(TextDecoration.ITALIC, false);
    }

    private static Component feedLegacy(String ampersand) {
        return ItemFactory.colorize(ampersand == null ? "" : ampersand)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** One voice for all public drone lines: gold names, italic gray verbs, white weapons. */
    private void broadcastFeed(String ampersandLine) {
        if (ampersandLine == null || ampersandLine.isBlank()) {
            return;
        }
        broadcastFeed(feedLegacy(ampersandLine));
    }

    private void broadcastFeed(Component line) {
        if (line == null) {
            return;
        }
        plugin.getServer().sendMessage(line);
    }

    /**
     * Unified public crash line: {@code &6notch &7&ocrashed their &6RQ-170 &7&o— reason}.
     * No operator → {@code &6MQ-9 Reaper crashed.}
     */
    private void announceCrash(UUID ownerId, String ownerName, String airframe, String reason) {
        String air = airframe == null || airframe.isBlank() ? DISPLAY_NAME : airframe;
        if (ownerName == null || ownerName.isBlank()) {
            broadcastFeed("&6" + air + " crashed.");
            return;
        }
        String why = reason == null || reason.isBlank() ? "impact" : reason;
        broadcastFeed(Component.empty()
                .append(feedPlayer(ownerId, ownerName))
                .append(feedLegacy(" &7&ocrashed their &6" + air + " &7&o— " + why)));
    }

    private void announceFuelCrash(Player pilot, String airframe) {
        if (pilot == null) {
            announceCrash(null, null, airframe, "ran out of fuel");
            return;
        }
        announceCrash(pilot.getUniqueId(), pilot.getName(), airframe, "ran out of fuel");
    }

    private void announceOutOfRangeCrash(Player pilot, String airframe) {
        if (pilot == null) {
            announceCrash(null, null, airframe, "out of range");
            return;
        }
        announceCrash(pilot.getUniqueId(), pilot.getName(), airframe, "out of range");
    }

    private void announcePilotImpactCrash(Player pilot, DroneSession session) {
        if (pilot == null) {
            announceCrash(null, "Someone", airframeLabel(session), "impact");
            return;
        }
        announceCrash(pilot.getUniqueId(), pilot.getName(), airframeLabel(session), "impact");
    }

    /**
     * Kill credit for guided UAV munitions. Players get a death-message line (no double chat);
     * non-players broadcast immediately.
     */
    public void announceGuidedKill(Player shooter, LivingEntity victim, RoundDefinition round) {
        if (shooter == null || victim == null) {
            return;
        }
        String victimName = victim instanceof Player p ? p.getName() : victim.getName();
        if (victimName == null || victimName.isBlank()) {
            return;
        }
        String weapon = "UAV strike";
        if (round != null) {
            weapon = PlainTextComponentSerializer.plainText().serialize(
                    ItemFactory.colorize(round.displayName() != null ? round.displayName() : round.fileName()));
            if (weapon == null || weapon.isBlank()) {
                weapon = round.fileName();
            }
        }
        announceWeaponKill(shooter, victim, "killed", weapon);
    }

    /** R9X / blade kills — same feed voice as guided. */
    private void announceWeaponKill(Player shooter, LivingEntity victim, String verb, String weapon) {
        if (shooter == null || victim == null) {
            return;
        }
        String victimName = victim instanceof Player p ? p.getName() : victim.getName();
        if (victimName == null || victimName.isBlank()) {
            return;
        }
        Component line = Component.empty()
                .append(feedPlayer(shooter.getUniqueId(), shooter.getName()))
                .append(feedLegacy(" &7&o" + verb + " "));
        if (victim instanceof Player p) {
            line = line.append(feedPlayer(p.getUniqueId(), victimName));
        } else {
            line = line.append(feedLegacy("&6" + feedName(victimName)));
        }
        line = line.append(feedLegacy(" &7&owith &f" + weapon));
        if (victim instanceof Player p) {
            markedWeaponKillFeed.put(p.getUniqueId(), line);
        } else {
            broadcastFeed(line);
        }
    }

    /** Parked MQ-9 destroyed by gunfire — full tarmac cook-off (blast / fire / crater). */
    private void detonateParkedAirframe(DronePadService.ParkedPad pad, Player destroyer) {
        if (pad == null || plugin.dronePads() == null) {
            return;
        }
        String ownerName = null;
        if (pad.owner != null) {
            Player online = Bukkit.getPlayer(pad.owner);
            if (online != null) {
                ownerName = online.getName();
            } else {
                String offline = Bukkit.getOfflinePlayer(pad.owner).getName();
                if (offline != null && !offline.isBlank()) {
                    ownerName = offline;
                }
            }
        }
        if (ownerName == null || ownerName.isBlank()) {
            ownerName = "Someone";
        }
        String air = plugin.dronePads().typeOf(pad).displayName();
        Location boom = plugin.dronePads().airframeCenter(pad);
        if (boom == null) {
            boom = plugin.dronePads().airframeLocation(pad);
        }
        if (boom != null && boom.getWorld() != null) {
            World world = boom.getWorld();
            registerCrashKillZone(boom, ownerName, air);
            String holdId = beginCrashCraterHold(boom);
            world.createExplosion(boom, CRASH_BLAST_POWER, false, false, null);
            if (plugin.explosionRegen() != null) {
                plugin.explosionRegen().carveCrater(boom, CRASH_BLAST_POWER);
            }
            finalizeCrashCraterHold(holdId, boom);
            world.spawnParticle(Particle.EXPLOSION_EMITTER, boom.clone().add(0, 0.5, 0), 2, 0.25, 0.25, 0.25, 0);
            world.spawnParticle(Particle.LAVA, boom.clone().add(0, 0.3, 0), 40, 1.6, 0.4, 1.6, 0.08);
            world.spawnParticle(Particle.LARGE_SMOKE, boom.clone().add(0, 0.6, 0), 50, 2.0, 0.8, 2.0, 0.03);
            world.playSound(boom, Sound.ENTITY_GENERIC_EXPLODE, 2.4f, 0.55f);
            if (plugin.blastShock() != null) {
                plugin.blastShock().apply(boom.clone(), 28.0, 1.45);
            }
            if (plugin.laserBridge() != null) {
                plugin.laserBridge().broadcastThermalBlast(boom.clone().add(0, 0.4, 0), 9f);
            }
            igniteCrashSite(boom, CRASH_FIRE_RADIUS);
            if (plugin.crashSites() != null) {
                plugin.crashSites().spawnAfterCrash(boom.clone(), holdId);
            } else if (holdId != null && plugin.explosionRegen() != null) {
                plugin.explosionRegen().releaseHold(holdId);
            }
        }
        if (destroyer != null && destroyer.isOnline()) {
            broadcastFeed(Component.empty()
                    .append(feedPlayer(destroyer.getUniqueId(), destroyer.getName()))
                    .append(feedLegacy(" &7&odestroyed "))
                    .append(feedPlayer(pad.owner, ownerName))
                    .append(feedLegacy("&7&o's parked &6" + air)));
        }
        plugin.dronePads().destroyPad(pad.id);
        broadcastDroneVis();
    }

    /** Snapshot airframe pose and spawn a diving wreck (call before {@link #exit}). */
    public void beginCrashFromPilot(Player pilot) {
        if (pilot == null || !isPiloting(pilot)) {
            return;
        }
        DroneSession session = sessions.get(pilot.getUniqueId());
        Location from = droneWorldLocation(pilot);
        if (from == null || from.getWorld() == null) {
            from = pilot.getLocation().clone();
        }
        Vector heading;
        if (session != null && session.cruiseDir != null && session.cruiseDir.lengthSquared() > 1.0e-6) {
            heading = session.cruiseDir.clone();
        } else {
            heading = pilot.getLocation().getDirection();
        }
        float yaw = session != null ? bodyYawDegrees(session, pilot) : from.getYaw();
        String air = airframeLabel(session);
        // Airframe is lost — never reappear on the pad after a crash / shootdown.
        destroyLinkedPad(session);
        startCrash(from, heading, yaw, pilot.getUniqueId(), pilot.getName(), air);
    }

    /** Remove the parked MQ-9 permanently (shot down, crash, mid-air collision). */
    private void destroyLinkedPad(DroneSession session) {
        if (session == null || session.parkedPadId == null || plugin.dronePads() == null) {
            return;
        }
        UUID padId = session.parkedPadId;
        session.parkedPadId = null;
        if (session.padBlockLocation != null && session.padBlockLocation.getWorld() != null) {
            Block padBlock = session.padBlockLocation.getBlock();
            clearChunkPadOwner(padBlock);
            if (padBlock.getType() == PAD_MATERIAL) {
                padBlock.setType(Material.AIR, false);
            }
        }
        plugin.dronePads().destroyPad(padId);
    }

    /** Clear legacy chunk PDC ownership for a pad cell. */
    public void clearChunkPadOwner(Block block) {
        if (block == null) {
            return;
        }
        block.getChunk().getPersistentDataContainer().remove(padChunkKey(block));
    }

    /**
     * Mid-flight collision with terrain / buildings → dive wreck + boom.
     * Near the ground (landing / taxi) belly-floor overlap and downward probes are relaxed
     * so a flare onto the pad does not count as a crash; walls / cliff faces still kill.
     * @return true if the session was ended (caller should skip the rest of the tick)
     */
    private boolean tickTerrainCollision(Player player, DroneSession session) {
        if (player == null || session == null || session.ticks < COLLISION_GRACE_TICKS) {
            return false;
        }
        Location center = droneWorldLocation(player);
        if (center == null || center.getWorld() == null) {
            return false;
        }
        World world = center.getWorld();
        double clearance = groundClearance(player);
        boolean landingSoft = clearance <= FW_GROUND_CLEARANCE + 5.5;
        if (airframeOverlapsSolid(world, center, landingSoft)) {
            announcePilotImpactCrash(player, session);
            beginCrashFromPilot(player);
            exit(player, "crash");
            return true;
        }
        // Prefer airframe motion (glide velocity can lag / include look-pitch sink).
        Vector vel = player.getVelocity().clone();
        if (session.cruiseDir != null && session.airspeed > 0.05) {
            Vector fly = session.cruiseDir.clone().multiply(session.airspeed);
            fly.setY(vel.getY());
            if (fly.lengthSquared() > vel.lengthSquared() * 0.25) {
                vel = fly;
            }
        }
        double speed = vel.length();
        if (speed < 0.18) {
            return false;
        }
        Vector probe = vel.normalize();
        // Flare / settle: ignore mostly-downward probes into the deck while low.
        if (landingSoft && probe.getY() < -0.35 && Math.hypot(probe.getX(), probe.getZ()) < 0.85) {
            return false;
        }
        double reach = Math.max(1.8, speed * 3.5 + 1.0);
        RayTraceResult hit = world.rayTraceBlocks(center, probe, reach, FluidCollisionMode.NEVER, true);
        if (hit != null && hit.getHitBlock() != null && isCrashSolid(hit.getHitBlock())) {
            if (landingSoft && hit.getHitPosition() != null
                    && hit.getHitPosition().getY() <= center.getY() - 0.35) {
                // Struck the ground from above — landing, not a mid-air impact.
                return false;
            }
            announcePilotImpactCrash(player, session);
            beginCrashFromPilot(player);
            exit(player, "crash");
            return true;
        }
        return false;
    }

    private boolean airframeOverlapsSolid(World world, Location center, boolean landingSoft) {
        // Keep collision box modest vs full wingspan so taxi near hangars is fair.
        double hx = AIRFRAME_HALF_X * 0.55;
        double hyBelow = landingSoft ? 0.28 : AIRFRAME_HALF_Y * 0.55;
        double hyAbove = AIRFRAME_HALF_Y * 0.45;
        double hz = AIRFRAME_HALF_Z * 0.55;
        int minX = (int) Math.floor(center.getX() - hx);
        int maxX = (int) Math.floor(center.getX() + hx);
        int minY = (int) Math.floor(center.getY() - hyBelow);
        int maxY = (int) Math.floor(center.getY() + hyAbove);
        int minZ = (int) Math.floor(center.getZ() - hz);
        int maxZ = (int) Math.floor(center.getZ() + hz);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (isCrashSolid(world.getBlockAt(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isCrashSolid(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        if (type.isAir() || !type.isSolid()) {
            return false;
        }
        // Soft / foliage — clip through like passable leaves.
        String name = type.name();
        return !(name.contains("LEAVES") || name.contains("LEAF")
                || name.contains("GRASS") || name.contains("FERN")
                || name.contains("VINE") || name.contains("SNOW")
                || name.equals("COBWEB") || name.contains("FLOWER")
                || name.contains("SAPLING") || name.contains("MUSHROOM"));
    }

    private void startCrash(Location from, Vector heading, float yaw) {
        startCrash(from, heading, yaw, null, null, null);
    }

    private void startCrash(Location from, Vector heading, float yaw, UUID ownerId, String ownerName,
                            String airframe) {
        if (from == null || from.getWorld() == null) {
            return;
        }
        Vector h = heading == null ? new Vector(0, 0, 1) : heading.clone();
        h.setY(0);
        if (h.lengthSquared() < 1.0e-6) {
            h = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
        }
        h.normalize();
        CrashWreck wreck = new CrashWreck();
        wreck.id = UUID.randomUUID();
        wreck.pos = from.clone();
        wreck.yaw = yaw;
        wreck.pitch = CRASH_PITCH_START;
        // Nose-down dive with forward speed — not a vertical drop.
        wreck.vel = h.multiply(0.62).add(new Vector(0, -0.28, 0));
        wreck.ticks = 0;
        wreck.ownerId = ownerId;
        wreck.ownerName = ownerName;
        wreck.airframeName = airframe == null || airframe.isBlank() ? DISPLAY_NAME : airframe;
        crashes.put(wreck.id, wreck);
        from.getWorld().playSound(from, Sound.ENTITY_GENERIC_EXPLODE, 1.3f, 0.55f);
        from.getWorld().spawnParticle(Particle.LARGE_SMOKE, from, 18, 0.6, 0.4, 0.6, 0.02);
        from.getWorld().spawnParticle(Particle.FLAME, from, 12, 0.4, 0.3, 0.4, 0.02);
        broadcastDroneVis();
    }

    private void tickCrashes() {
        if (crashes.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, CrashWreck>> it = crashes.entrySet().iterator();
        while (it.hasNext()) {
            CrashWreck wreck = it.next().getValue();
            World world = wreck.pos.getWorld();
            if (world == null) {
                it.remove();
                continue;
            }
            wreck.ticks++;
            wreck.vel.setY(Math.max(-CRASH_MAX_FALL, wreck.vel.getY() - CRASH_GRAVITY));
            wreck.vel.setX(wreck.vel.getX() * 0.997);
            wreck.vel.setZ(wreck.vel.getZ() * 0.997);
            // Ease nose further down while falling (not past ~58° — angled crash, not vertical).
            if (wreck.pitch < CRASH_PITCH_MAX) {
                wreck.pitch = Math.min(CRASH_PITCH_MAX, wreck.pitch + 1.35f);
            }
            Location prev = wreck.pos.clone();
            Vector step = wreck.vel.clone();
            double stepLen = step.length();
            if (stepLen < 1.0e-4) {
                step = new Vector(0, -0.2, 0);
                stepLen = 0.2;
            }
            RayTraceResult hit = world.rayTraceBlocks(
                    prev, step.clone().normalize(), Math.max(0.35, stepLen + 0.35),
                    FluidCollisionMode.NEVER, true);
            boolean impact = false;
            Location impactAt = null;
            if (hit != null && hit.getHitPosition() != null) {
                impact = true;
                impactAt = hit.getHitPosition().toLocation(world);
            } else {
                Location next = prev.clone().add(step);
                Block below = next.getBlock();
                if (!below.getType().isAir() && below.getType().isSolid()) {
                    impact = true;
                    impactAt = below.getLocation().add(0.5, 1.0, 0.5);
                } else if (next.getY() < world.getMinHeight() + 1) {
                    impact = true;
                    impactAt = next;
                } else {
                    wreck.pos = next;
                }
            }
            // Trail
            world.spawnParticle(Particle.LARGE_SMOKE, wreck.pos, 3, 0.25, 0.15, 0.25, 0.01);
            world.spawnParticle(Particle.FLAME, wreck.pos, 2, 0.15, 0.1, 0.15, 0.01);
            if (wreck.ticks % 5 == 0) {
                world.playSound(wreck.pos, Sound.ENTITY_PHANTOM_FLAP, 0.45f, 0.35f);
            }
            if (impact) {
                crashImpact(wreck, impactAt != null ? impactAt : wreck.pos);
                it.remove();
            } else if (wreck.ticks > 20 * 45) {
                // Safety timeout
                crashImpact(wreck, wreck.pos);
                it.remove();
            }
        }
    }

    private void crashImpact(CrashWreck wreck, Location at) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        World world = at.getWorld();
        Location boom = at.clone();
        registerCrashKillZone(boom, wreck.ownerName, wreck.airframeName);
        // Unmanned wreck (abandoned / timed-out orphan) — short public line at impact.
        if (wreck.ownerName == null || wreck.ownerName.isBlank()) {
            announceCrash(wreck.ownerId, null, wreck.airframeName, "impact");
        }
        // Larger crater — regen held until the wreckage barrel is fully looted.
        String holdId = beginCrashCraterHold(boom);
        world.createExplosion(boom, CRASH_BLAST_POWER, false, false, null);
        if (plugin.explosionRegen() != null) {
            plugin.explosionRegen().carveCrater(boom, CRASH_BLAST_POWER);
        }
        finalizeCrashCraterHold(holdId, boom);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, boom.clone().add(0, 0.5, 0), 3, 0.3, 0.3, 0.3, 0);
        world.spawnParticle(Particle.EXPLOSION, boom.clone().add(0, 0.4, 0), 14, 1.8, 0.7, 1.8, 0.05);
        world.spawnParticle(Particle.LAVA, boom.clone().add(0, 0.3, 0), 55, 2.0, 0.5, 2.0, 0.1);
        world.spawnParticle(Particle.LARGE_SMOKE, boom.clone().add(0, 0.6, 0), 70, 2.4, 1.0, 2.4, 0.04);
        world.playSound(boom, Sound.ENTITY_GENERIC_EXPLODE, 2.6f, 0.5f);
        world.playSound(boom, Sound.ENTITY_GENERIC_EXPLODE, 1.8f, 0.75f);
        if (plugin.blastShock() != null) {
            plugin.blastShock().apply(boom.clone(), 30.0, 1.55);
        }
        if (plugin.laserBridge() != null) {
            plugin.laserBridge().broadcastThermalBlast(boom.clone().add(0, 0.4, 0), 10f);
        }
        igniteCrashSite(boom, CRASH_FIRE_RADIUS);
        if (plugin.crashSites() != null) {
            plugin.crashSites().spawnAfterCrash(boom.clone(), holdId);
        } else if (holdId != null && plugin.explosionRegen() != null) {
            plugin.explosionRegen().releaseHold(holdId);
        }
        final Location linger = boom.clone();
        final java.util.concurrent.atomic.AtomicInteger lingerTicks = new java.util.concurrent.atomic.AtomicInteger(0);
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            int t = lingerTicks.addAndGet(8);
            if (t > 20 * 12 || linger.getWorld() == null) {
                task.cancel();
                return;
            }
            linger.getWorld().spawnParticle(Particle.FLAME, linger.clone().add(0, 0.4, 0),
                    28, 2.2, 0.6, 2.2, 0.015, null, true);
            linger.getWorld().spawnParticle(Particle.LARGE_SMOKE, linger.clone().add(0, 0.8, 0),
                    16, 1.9, 0.9, 1.9, 0.015, null, true);
            if (ThreadLocalRandom.current().nextInt(2) == 0) {
                linger.getWorld().playSound(linger, Sound.BLOCK_FIRE_AMBIENT, 0.85f, 0.75f);
            }
        }, 1L, 8L);
        broadcastDroneVis();
    }

    /** Snapshot crater solids before blast so regen can wait on wreckage loot. */
    private String beginCrashCraterHold(Location boom) {
        if (plugin.explosionRegen() == null || boom == null) {
            return null;
        }
        return plugin.explosionRegen().beginHold(boom, CrashSiteService.HOLD_RADIUS);
    }

    private void finalizeCrashCraterHold(String holdId, Location boom) {
        if (holdId == null || plugin.explosionRegen() == null) {
            return;
        }
        plugin.explosionRegen().absorbNearIntoHold(holdId, boom, CrashSiteService.HOLD_RADIUS);
        plugin.explosionRegen().finalizeHold(holdId);
    }

    private void igniteCrashSite(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!block.getType().isAir() && block.getType() != Material.FIRE
                            && block.getType() != Material.SOUL_FIRE) {
                        continue;
                    }
                    Block below = block.getRelative(0, -1, 0);
                    if (!below.getType().isSolid()) {
                        continue;
                    }
                    // Dense fire in the crater, thicker rim
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    double chance = dist < 2.8 ? 0.98 : (dist < 4.5 ? 0.82 : 0.55);
                    if (rng.nextDouble() > chance) {
                        continue;
                    }
                    block.setType(Material.FIRE, false);
                }
            }
        }
        world.playSound(center, Sound.ITEM_FLINTANDSTEEL_USE, 1.2f, 0.7f);
        world.playSound(center, Sound.BLOCK_FIRE_AMBIENT, 1.4f, 0.75f);
    }

    private void registerCrashKillZone(Location boom, String ownerName, String airframe) {
        if (boom == null || boom.getWorld() == null) {
            return;
        }
        pruneCrashKillZones();
        UUID id = UUID.randomUUID();
        String air = airframe == null || airframe.isBlank() ? DISPLAY_NAME : airframe;
        crashKillZones.put(id, new CrashKillZone(id, boom.clone(),
                System.currentTimeMillis() + CRASH_KILL_WINDOW_MS, ownerName, air));
    }

    /** Tag players hurt by a recent MQ-9 impact blast / fire so death messages can attribute it. */
    public void markCrashDamage(Player player, EntityDamageEvent.DamageCause cause) {
        if (player == null || cause == null) {
            return;
        }
        boolean blast = cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
        boolean fire = cause == EntityDamageEvent.DamageCause.FIRE
                || cause == EntityDamageEvent.DamageCause.FIRE_TICK
                || cause == EntityDamageEvent.DamageCause.LAVA
                || cause == EntityDamageEvent.DamageCause.HOT_FLOOR;
        if (!blast && !fire) {
            return;
        }
        pruneCrashKillZones();
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        double r2 = CRASH_KILL_RADIUS * CRASH_KILL_RADIUS;
        CrashKillZone best = null;
        double bestDist = Double.MAX_VALUE;
        for (CrashKillZone zone : crashKillZones.values()) {
            if (zone.boom.getWorld() == null || !zone.boom.getWorld().equals(world)) {
                continue;
            }
            double d2 = zone.boom.distanceSquared(loc);
            if (d2 <= r2 && d2 < bestDist) {
                bestDist = d2;
                best = zone;
            }
        }
        if (best != null) {
            markedCrashVictim.put(player.getUniqueId(), best.id);
        }
    }

    /**
     * If this death was from an MQ-9 crash impact, replace the chat death message.
     * @return true when the custom line was applied
     */
    public boolean applyCrashCasualtyDeathMessage(PlayerDeathEvent event) {
        if (event == null || event.getEntity() == null) {
            return false;
        }
        Player victim = event.getEntity();
        pruneCrashKillZones();
        UUID zoneId = markedCrashVictim.remove(victim.getUniqueId());
        CrashKillZone zone = zoneId != null ? crashKillZones.get(zoneId) : null;
        // Fallback: died of blast/fire still standing in a hot crash zone
        if (zone == null) {
            EntityDamageEvent last = victim.getLastDamageCause();
            if (last != null) {
                markCrashDamage(victim, last.getCause());
                zoneId = markedCrashVictim.remove(victim.getUniqueId());
                zone = zoneId != null ? crashKillZones.get(zoneId) : null;
            }
        }
        if (zone == null || System.currentTimeMillis() > zone.untilMs) {
            return false;
        }
        if (!zone.counted.add(victim.getUniqueId())) {
            return false;
        }
        int n = zone.counted.size();
        String air = zone.airframeName == null || zone.airframeName.isBlank()
                ? DISPLAY_NAME : zone.airframeName;
        // Victim name is hoverable; keep running casualty count.
        Component line = Component.empty()
                .append(feedPlayer(victim.getUniqueId(), victim.getName()))
                .append(feedLegacy(" &7&odied in the &6" + air + " &7&ocrash"));
        if (n > 1) {
            line = line.append(feedLegacy(" &8(&f" + n + " &8casualties)"));
        }
        event.deathMessage(line);
        return true;
    }

    /**
     * Aft-hazard death line (prop disk / jet intake). Suffix stored by {@link #tickPropellerHazard}.
     * @return true when applied
     */
    public boolean applyPropDeathMessage(PlayerDeathEvent event) {
        if (event == null || event.getEntity() == null) {
            return false;
        }
        String suffix = markedPropVictim.remove(event.getEntity().getUniqueId());
        if (suffix == null || suffix.isBlank()) {
            return false;
        }
        Player victim = event.getEntity();
        event.deathMessage(Component.empty()
                .append(feedPlayer(victim.getUniqueId(), victim.getName()))
                .append(feedLegacy(suffix)));
        return true;
    }

    /**
     * Guided / R9X kill line applied as the death message (single chat line).
     * @return true when applied
     */
    public boolean applyWeaponKillDeathMessage(PlayerDeathEvent event) {
        if (event == null || event.getEntity() == null) {
            return false;
        }
        Component line = markedWeaponKillFeed.remove(event.getEntity().getUniqueId());
        if (line == null) {
            return false;
        }
        event.deathMessage(line);
        return true;
    }

    /**
     * While an operator is flying, the spinning pusher prop shreds anyone who walks into the disk.
     */
    private void tickPropellerHazard(Player pilot, DroneSession session) {
        if (pilot == null || session == null) {
            return;
        }
        Location center = droneWorldLocation(pilot);
        if (center == null || center.getWorld() == null) {
            return;
        }
        float yaw = bodyYawDegrees(session, pilot);
        double rad = Math.toRadians(yaw);
        // Nose = yaw forward; prop is aft of mesh center.
        Vector forward = new Vector(-Math.sin(rad), 0.0, Math.cos(rad));
        Location prop = center.clone().subtract(forward.clone().multiply(MESH_HALF_Z * 0.92));
        prop.add(0, 0.35, 0);
        final double radiusSq = 2.65 * 2.65;
        for (Player other : prop.getWorld().getPlayers()) {
            if (other == null || !other.isOnline() || other.isDead()) {
                continue;
            }
            if (other.getUniqueId().equals(pilot.getUniqueId())) {
                continue;
            }
            if (isPiloting(other)) {
                continue;
            }
            GameMode mode = other.getGameMode();
            if (mode == GameMode.SPECTATOR || mode == GameMode.CREATIVE) {
                continue;
            }
            Location body = other.getLocation().clone().add(0, 1.0, 0);
            if (body.getWorld() == null || !body.getWorld().equals(prop.getWorld())) {
                continue;
            }
            if (body.distanceSquared(prop) > radiusSq) {
                continue;
            }
            if (Math.abs(body.getY() - prop.getY()) > 2.4) {
                continue;
            }
            markedPropVictim.put(other.getUniqueId(), aftHazardDeathSuffix(session));
            other.getWorld().playSound(prop, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 0.55f);
            other.getWorld().spawnParticle(Particle.CRIT, prop, 18, 0.55, 0.35, 0.55, 0.08);
            other.setNoDamageTicks(0);
            other.damage(10_000.0);
            if (!other.isDead() && other.getHealth() > 0) {
                other.setHealth(0.0);
            }
        }
    }

    /** Legacy chat fragment after the victim name (includes leading space). */
    private String aftHazardDeathSuffix(DroneSession session) {
        BigDroneType type = session != null ? session.type : null;
        String name = type != null ? type.displayName() : DISPLAY_NAME;
        if (type == BigDroneType.X47B) {
            return " &7&owas inhaled by &6" + name + "&7&o's turbine";
        }
        if (type == BigDroneType.X37B) {
            return " &7&ogot torch-cut by &6" + name + "&7&o's thruster plume";
        }
        return " &7&owalked into &6" + name + "&7&o's prop";
    }

    private void pruneCrashKillZones() {
        long now = System.currentTimeMillis();
        crashKillZones.entrySet().removeIf(e -> e.getValue().untilMs < now);
        markedCrashVictim.entrySet().removeIf(e -> !crashKillZones.containsKey(e.getValue()));
    }

    private static final class CrashKillZone {
        final UUID id;
        final Location boom;
        final long untilMs;
        final String ownerName;
        final String airframeName;
        final Set<UUID> counted = ConcurrentHashMap.newKeySet();

        CrashKillZone(UUID id, Location boom, long untilMs, String ownerName, String airframeName) {
            this.id = id;
            this.boom = boom;
            this.untilMs = untilMs;
            this.ownerName = ownerName;
            this.airframeName = airframeName;
        }
    }

    private static final class CrashWreck {
        UUID id;
        Location pos;
        float yaw;
        float pitch;
        Vector vel;
        int ticks;
        /** Operator display name when known (fuel / seat / death crashes). */
        String ownerName;
        UUID ownerId;
        /** Typed airframe label for kill / casualty chat. */
        String airframeName;
    }

    private void refreshFlareItem(Player player, DroneSession session) {
        boolean hot = System.currentTimeMillis() < session.flareActiveUntilMs;
        player.getInventory().setItem(HOTBAR_FLARES, plugin.items().createDroneFlareControl(
                session.flareCharges, FLARE_CHARGES_MAX, hot));
    }

    public Optional<DroneSession> session(Player player) {
        if (player == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(player.getUniqueId()));
    }

    public boolean isPad(Block block) {
        if (block == null) {
            return false;
        }
        if (plugin.dronePads() != null && plugin.dronePads().padAt(block).isPresent()) {
            return true;
        }
        // Legacy lodestone pads (pre–virtual pad).
        return block.getType() == PAD_MATERIAL && chunkPadOwner(block).isPresent();
    }

    /**
     * Resolve pad cell from a right-click: the clicked block itself, or the air cell above
     * (ground under a virtual pad).
     */
    public Optional<Block> findPadFromClick(Block clicked) {
        if (clicked == null) {
            return Optional.empty();
        }
        if (isPad(clicked)) {
            return Optional.of(clicked);
        }
        Block above = clicked.getRelative(org.bukkit.block.BlockFace.UP);
        if (isPad(above)) {
            return Optional.of(above);
        }
        return Optional.empty();
    }

    public Optional<UUID> padOwner(Block block) {
        if (block == null) {
            return Optional.empty();
        }
        if (plugin.dronePads() != null) {
            Optional<DronePadService.ParkedPad> pad = plugin.dronePads().padAt(block);
            if (pad.isPresent()) {
                return Optional.of(pad.get().owner);
            }
        }
        return chunkPadOwner(block);
    }

    private Optional<UUID> chunkPadOwner(Block block) {
        if (block == null) {
            return Optional.empty();
        }
        String raw = block.getChunk().getPersistentDataContainer()
                .get(padChunkKey(block), PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private NamespacedKey padChunkKey(Block block) {
        return WarzKeys.of("bd_" + enc(block.getX()) + "_" + enc(block.getY()) + "_" + enc(block.getZ()));
    }

    private static String enc(int n) {
        return n < 0 ? "m" + (-n) : Integer.toString(n);
    }

    private void writePadOwner(Block block, UUID owner) {
        Chunk chunk = block.getChunk();
        chunk.getPersistentDataContainer().set(padChunkKey(block), PersistentDataType.STRING, owner.toString());
    }

    public void removePadData(Block block) {
        if (block == null) {
            return;
        }
        block.getChunk().getPersistentDataContainer().remove(padChunkKey(block));
        if (plugin.dronePads() != null) {
            plugin.dronePads().removePad(block);
        }
        broadcastDroneVis();
    }

    public boolean isParkedPadInUse(UUID padId) {
        if (padId == null) {
            return false;
        }
        if (orphans.containsKey(padId)) {
            return true;
        }
        for (DroneSession session : sessions.values()) {
            if (padId.equals(session.parkedPadId)) {
                return true;
            }
        }
        return false;
    }

    public boolean tryPlace(Player player, Block against, org.bukkit.block.BlockFace face) {
        if (player == null || against == null || face == null) {
            return false;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!plugin.items().isBigDroneItem(hand)) {
            return false;
        }
        BigDroneType placeType = plugin.items().droneType(hand);
        Block target = against.getRelative(face);
        if (!target.getType().isAir() && !target.isReplaceable()) {
            player.sendMessage(Component.text("Cannot place " + placeType.displayName() + " here", NamedTextColor.RED));
            return true;
        }
        // Virtual pad — no lodestone / marker block; mesh + drone_pads.yml only.
        if (target.getType() == PAD_MATERIAL) {
            target.setType(Material.AIR, false);
        }
        if (plugin.dronePads() != null && plugin.dronePads().padAt(target).isPresent()) {
            player.sendMessage(Component.text("A drone is already parked here", NamedTextColor.RED));
            return true;
        }
        writePadOwner(target, player.getUniqueId());
        if (plugin.dronePads() != null) {
            plugin.dronePads().createPad(player, target, hand);
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            hand.setAmount(hand.getAmount() - 1);
        }
        player.sendMessage(Component.text(
                placeType.displayName()
                        + " deployed — nose faces you. RMB the airframe = payload bay. Radiolink → seat.",
                NamedTextColor.AQUA));
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.4f);
        broadcastDroneVis();
        return true;
    }

    /** Pad enter removed — deploy on ground, radiolink, then use a drone seat. */
    public boolean tryEnter(Player player, Block pad) {
        return false;
    }

    /**
     * Enter from a registered stairs seat: leave a sitting mannequin (player skin) in the chair
     * and fly the invisible pilot into the MQ-9.
     */
    public boolean tryEnterSeat(Player player, Block stairs) {
        if (player == null || stairs == null || plugin.droneSeats() == null) {
            return false;
        }
        Optional<DroneSeatService.Seat> seatOpt = plugin.droneSeats().seatAt(stairs);
        if (seatOpt.isEmpty()) {
            return false;
        }
        DroneSeatService.Seat seat = seatOpt.get();
        if (!DroneSeatService.VEHICLE_MQ9.equals(seat.vehicle())) {
            player.sendMessage(Component.text("Unknown seat vehicle: " + seat.vehicle(), NamedTextColor.RED));
            return true;
        }
        if (!(stairs.getBlockData() instanceof org.bukkit.block.data.type.Stairs)) {
            player.sendMessage(Component.text("Seat regenerating — try again when the stairs return", NamedTextColor.YELLOW));
            return true;
        }
        if (sessions.containsKey(player.getUniqueId())) {
            player.sendMessage(Component.text("Already piloting a drone", NamedTextColor.YELLOW));
            return true;
        }
        Optional<UUID> occ = plugin.droneSeats().occupant(stairs);
        if (occ.isPresent() && !occ.get().equals(player.getUniqueId())) {
            Player other = Bukkit.getPlayer(occ.get());
            String name = other != null ? other.getName() : "someone";
            player.sendMessage(Component.text("Seat in use by " + name, NamedTextColor.RED));
            return true;
        }
        if (plugin.dronePads() == null) {
            player.sendMessage(Component.text("Drone pads unavailable", NamedTextColor.RED));
            return true;
        }
        Optional<DronePadService.ParkedPad> linked = plugin.dronePads().padForSeat(seat.key());
        OrphanFlight orphanBySeat = orphanForSeat(seat.key());
        // Orphan takeover: seat→pad map can briefly look empty after a seat kill; resolve via orphan.
        if (linked.isEmpty() && orphanBySeat != null) {
            linked = plugin.dronePads().padById(orphanBySeat.padId);
        }
        if (linked.isEmpty()) {
            player.sendMessage(Component.text(
                    "No drone radiolinked to this seat — use Radiolink on the drone, then this chair.",
                    NamedTextColor.YELLOW));
            return true;
        }
        DronePadService.ParkedPad pad = linked.get();
        String air = plugin.dronePads().typeOf(pad).displayName();
        OrphanFlight orphan = orphans.get(pad.id);
        if (orphan == null) {
            orphan = orphanBySeat;
        }
        if (isParkedPadInUse(pad.id) && orphan == null) {
            player.sendMessage(Component.text("That " + air + " is already airborne", NamedTextColor.RED));
            return true;
        }
        GunDefinition law = resolveDroneLaw();
        if (law == null) {
            player.sendMessage(Component.text("LAW gun definition missing — cannot enter", NamedTextColor.RED));
            return true;
        }

        Location sitAt = plugin.droneSeats().sitLocation(seat);
        if (sitAt == null || sitAt.getWorld() == null) {
            player.sendMessage(Component.text("Seat world not loaded", NamedTextColor.RED));
            return true;
        }
        Location padAir = plugin.dronePads().airframeLocation(pad);
        if (padAir == null || padAir.getWorld() == null) {
            player.sendMessage(Component.text("Linked " + air + " world not loaded", NamedTextColor.RED));
            return true;
        }
        Block padBlock = padAir.getWorld().getBlockAt(pad.x, pad.y, pad.z);
        // Orphan airframe is already flying — pad cell may be virtual; don't require lodestone.
        if (orphan == null && !isPad(padBlock)) {
            player.sendMessage(Component.text(
                    "Linked " + air + " pad is missing — re-place and radiolink.", NamedTextColor.RED));
            return true;
        }
        if (orphan == null && plugin.dronePads().fuelPercent(pad) <= 0.0) {
            boolean x37 = plugin.dronePads().typeOf(pad) == BigDroneType.X37B;
            player.sendMessage(Component.text(
                    x37
                            ? "No fuel — cannot take off. Load Hydrazine Fuel in the payload bay."
                            : "No fuel — cannot take off. Load Jet Fuel in the payload bay.",
                    NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
            return true;
        }

        ItemStack[] savedArmor = snapshotArmor(player);
        ItemStack savedOffhand = cloneOrNull(player.getInventory().getItemInOffHand());
        ItemStack[] savedHotbar = snapshotHotbar(player);
        int savedHeld = player.getInventory().getHeldItemSlot();
        boolean savedAllowFlight = player.getAllowFlight();
        boolean savedFlying = player.isFlying();
        float savedFlySpeed = player.getFlySpeed();
        GameMode savedMode = player.getGameMode();
        ItemStack mainHand = cloneOrNull(player.getInventory().getItemInMainHand());

        UUID bodyId = spawnSeatMannequin(player, sitAt, savedArmor, savedOffhand, mainHand);
        if (bodyId == null) {
            player.sendMessage(Component.text("Failed to seat operator body", NamedTextColor.RED));
            return true;
        }

        // Take over orphan or lift parked airframe off the ground.
        Location flyTo;
        String previousPilot = null;
        UUID previousPilotId = null;
        final boolean fromOrphan = orphan != null;
        if (fromOrphan) {
            flyTo = orphan.pos.clone();
            flyTo.setYaw(orphan.yaw);
            flyTo.setPitch(10f);
            previousPilot = orphan.previousPilotName;
            previousPilotId = orphan.previousPilotId;
            orphans.remove(pad.id);
        } else {
            // Stay on the parked pad feet — do not hop the mesh into a hover on enter.
            flyTo = padAir.clone();
            flyTo.setYaw(pad.yaw);
            flyTo.setPitch(10f);
        }
        player.teleport(flyTo);
        player.setAllowFlight(true);
        player.setFlying(true);
        applyPilotInvisibility(player);
        clearPilotEquipment(player);
        player.setCollidable(false);
        player.addScoreboardTag(PILOT_TAG);

        applyHotbarControls(player);
        player.getInventory().setHeldItemSlot(0);

        BigDroneType enterType = plugin.dronePads().typeOf(pad);
        int rockets = enterType.cargoBay()
                ? plugin.dronePads().cargoCount(pad.id)
                : plugin.dronePads().rocketCount(pad.id);
        // padStandLocation = seat (mannequin + exit teleport). Airframe flies from padAir.
        DroneSession session = new DroneSession(
                player.getUniqueId(),
                sitAt.clone(),
                padBlock.getLocation(),
                bodyId,
                new BlockDisplay[0],
                savedArmor,
                savedOffhand,
                savedHotbar,
                savedHeld,
                savedAllowFlight,
                savedFlying,
                savedFlySpeed,
                savedMode,
                rockets
        );
        session.fromSeat = true;
        session.seatKey = seat.key();
        session.seatYaw = sitAt.getYaw();
        session.parkedPadId = pad.id;
        session.type = plugin.dronePads().typeOf(pad);
        session.structureHp = fromOrphan ? orphan.structureHp : pad.structureHp;
        if (fromOrphan && orphan.cruiseDir != null && orphan.cruiseDir.lengthSquared() > 1.0e-6) {
            Vector dir = orphan.cruiseDir.clone();
            dir.setY(0);
            if (dir.lengthSquared() > 1.0e-6) {
                session.cruiseDir = dir.normalize();
            }
        }
        if (fromOrphan) {
            if (orphan.airspeed > 0.05) {
                session.airspeed = orphan.airspeed;
            }
            if (!Double.isNaN(orphan.cruiseAltitude)) {
                session.cruiseAltitude = orphan.cruiseAltitude;
            }
            if (orphan.orbit && orphan.orbitCenter != null) {
                session.orbit = true;
                session.orbitLocked = true;
                session.orbitCenter = orphan.orbitCenter.clone();
                session.orbitTarget = orphan.orbitCenter.clone();
                session.orbitRadius = orphan.orbitRadius;
                session.orbitAngle = orphan.orbitAngle;
                session.orbitHeight = orphan.orbitHeight;
                session.orbitSpeed = orphan.orbitSpeed;
                session.manualControl = false;
            }
        } else {
            session.cruiseDir = yawToForward(flyTo.getYaw());
        }
        session.headingDeg = flyTo.getYaw();
        session.flareCharges = Math.max(0, Math.min(FLARE_CHARGES_MAX, pad.flareCharges));
        sessions.put(player.getUniqueId(), session);
        session.seatArmedAtMs = System.currentTimeMillis() + 3000L;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && isPiloting(player) && sessions.containsKey(player.getUniqueId())) {
                player.sendMessage(Component.text("Seat armor online.", NamedTextColor.GREEN));
            }
        }, 60L);
        plugin.droneSeats().markOccupied(seat.key(), player.getUniqueId());
        applyZoom(player, session);
        try {
            session.savedViewDistance = player.getViewDistance();
            player.setViewDistance(Math.max(16, session.savedViewDistance));
        } catch (Throwable ignored) {
            session.savedViewDistance = -1;
        }

        applyDroneFlySpeed(player, session);
        if (fromOrphan) {
            // Mid-air hijack — already flying; hand off to autopilot cruise.
            session.flightPhase = FlightPhase.CRUISE;
            session.airspeed = cruiseSpeed(session);
            session.throttle = 0.75;
            engageAutopilot(player, session, true);
            player.sendMessage(Component.text(
                    session.type.displayName() + " online — CONTROLLABLE takeover"
                            + " — prop spinning. LAW rockets: " + rockets
                            + " / " + session.type.missileSlots()
                            + ". Control = MANUAL fixed-wing.",
                    NamedTextColor.AQUA));
            String prior = previousPilot != null ? previousPilot : "Someone";
            broadcastFeed(Component.empty()
                    .append(feedPlayer(player.getUniqueId(), player.getName()))
                    .append(feedLegacy(" &7&otook over "))
                    .append(feedPlayer(previousPilotId, prior))
                    .append(feedLegacy("&7&o's &6" + session.type.displayName())));
        } else {
            beginTakeoff(player, session);
            player.sendMessage(Component.text(
                    session.type.displayName() + " MANUAL — W throttle · rotate ~"
                            + (int) (FW_ROTATE_SPEED_FRAC * 100) + "% IAS · pitch to climb"
                            + " — LAW rockets: " + rockets
                            + " / " + session.type.missileSlots()
                            + ". Exit: land anywhere to park, or abandon mid-air (AUTOPILOT).",
                    NamedTextColor.AQUA));
        }
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.5f, 1.6f);
        broadcastDroneVis();
        return true;
    }

    private UUID spawnSeatMannequin(Player player, Location sitAt, ItemStack[] armor,
                                    ItemStack offhand, ItemStack mainHand) {
        World world = sitAt.getWorld();
        if (world == null) {
            return null;
        }
        NamespacedKey bodyKey = WarzKeys.of("drone_seat_body");
        Mannequin body = world.spawn(sitAt, Mannequin.class, m -> {
            m.setPersistent(true);
            m.setRemoveWhenFarAway(false);
            // Damageable stand-in for the pilot — shoot / melee / explode at the seat.
            m.setInvulnerable(false);
            m.setSilent(true);
            m.setGravity(false);
            m.setAI(false);
            m.setCollidable(true);
            m.setImmovable(true);
            m.customName(null);
            m.setCustomNameVisible(false);
            m.setDescription(null);
            try {
                var maxHp = m.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                if (maxHp != null) {
                    maxHp.setBaseValue(20.0);
                }
                m.setHealth(20.0);
            } catch (Throwable ignored) {
            }
            try {
                m.setProfile(ResolvableProfile.resolvableProfile(player.getPlayerProfile()));
            } catch (Throwable ignored) {
            }
            try {
                SkinParts skin = player.getClientOption(ClientOption.SKIN_PARTS);
                if (skin != null) {
                    m.setSkinParts(skin);
                }
            } catch (Throwable ignored) {
            }
            EntityEquipment eq = m.getEquipment();
            if (eq != null) {
                eq.setHelmet(cloneOrNull(armor != null && armor.length > 0 ? armor[0] : null));
                eq.setChestplate(cloneOrNull(armor != null && armor.length > 1 ? armor[1] : null));
                eq.setLeggings(cloneOrNull(armor != null && armor.length > 2 ? armor[2] : null));
                eq.setBoots(cloneOrNull(armor != null && armor.length > 3 ? armor[3] : null));
                eq.setItemInMainHand(cloneOrNull(mainHand));
                eq.setItemInOffHand(cloneOrNull(offhand));
            }
            m.getPersistentDataContainer().set(bodyKey, PersistentDataType.STRING, player.getUniqueId().toString());
        });
        body.setRotation(sitAt.getYaw(), 0f);
        body.setVelocity(new Vector(0, 0, 0));
        if (Mannequin.validPoses().contains(Pose.SITTING)) {
            body.setPose(Pose.SITTING);
        } else if (Mannequin.validPoses().contains(Pose.SNEAKING)) {
            body.setPose(Pose.SNEAKING);
        }
        return body.getUniqueId();
    }

    /** Exit pilot session without crashing the airframe (orphan / link-lost handoff). */
    public void exitAirborneForTakeover(Player player) {
        exitAirborneForTakeover(player, true);
    }

    /**
     * @param announce when false (seat-kill death), skip the “link lost” chat line
     */
    public void exitAirborneForTakeover(Player player, boolean announce) {
        if (player == null) {
            return;
        }
        DroneSession session = sessions.remove(player.getUniqueId());
        clearPilotInvisibility(player);
        player.removeScoreboardTag(PILOT_TAG);
        sendZoomLevel(player, 0);
        // Mid-air abandon — operator intentionally left; static DISCONNECTED.
        sendHudClear(player, disconnectBanner(session, "disconnect"));
        if (plugin.droneSeats() != null) {
            plugin.droneSeats().clearOccupied(player.getUniqueId());
        }
        if (session == null) {
            broadcastDroneVis();
            return;
        }
        clearDisplays(session);
        removeBody(session);
        if (plugin.laserBridge() != null) {
            plugin.laserBridge().clearBeam(player);
        }
        clearForcedEntities(player, session);
        if (session.savedViewDistance >= 2) {
            player.setViewDistance(session.savedViewDistance);
        }
        restoreHotbar(player, session);
        restorePilotEquipment(player, session);
        player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, session.savedHeldSlot)));
        player.setCollidable(true);
        player.setFlySpeed(session.savedFlySpeed);
        player.setAllowFlight(session.savedAllowFlight);
        if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
            player.setFlying(session.savedAllowFlight && session.savedFlying);
        } else {
            player.setFlying(session.savedFlying);
        }
        player.setFallDistance(0f);
        Location back = session.padStandLocation.clone();
        if (back.getWorld() != null) {
            player.teleport(back);
        }
        clearPilotInvisibility(player);
        if (plugin.dronePads() != null) {
            plugin.dronePads().tickInteractEntities();
        }
        broadcastDroneVis();
        player.sendActionBar(Component.empty());
        if (announce && !player.isDead()) {
            String air = airframeLabel(session);
            player.sendMessage(Component.text(
                    "Link lost — " + air + " still airborne (10s to retake seat).",
                    NamedTextColor.YELLOW));
        }
    }

    /**
     * Pilot died while flying. Seat operators leave an orphan airframe (radiolink kept);
     * other deaths scrap the MQ-9.
     */
    public void handlePilotDeath(PlayerDeathEvent event) {
        Player pilot = event.getEntity();
        if (pilot == null) {
            return;
        }
        if (isPiloting(pilot)) {
            DroneSession session = sessions.get(pilot.getUniqueId());
            if (session != null && session.fromSeat && session.parkedPadId != null) {
                Entity killer = pilot.getKiller();
                if (!orphans.containsKey(session.parkedPadId)) {
                    startOrphanFromPilot(pilot, session, killer);
                }
                // Do NOT destroyLinkedPad — seat→pad radiolink must survive for takeover.
                exitAirborneForTakeover(pilot, false);
                forceWorldSpawnOnRespawn.add(pilot.getUniqueId());
            } else {
                beginCrashFromPilot(pilot);
                exit(pilot, "death");
            }
        } else {
            clearPilotInvisibility(pilot);
        }
    }

    /** Seat-killed pilots respawn at world spawn (not bed / anchor). */
    public void applySeatKillRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        if (!forceWorldSpawnOnRespawn.remove(event.getPlayer().getUniqueId())) {
            return;
        }
        World world = event.getRespawnLocation() != null ? event.getRespawnLocation().getWorld() : null;
        if (world == null) {
            world = event.getPlayer().getWorld();
        }
        if (world != null) {
            event.setRespawnLocation(world.getSpawnLocation());
        }
        clearPilotInvisibility(event.getPlayer());
    }

    /**
     * Clean Exit: park when settled on the ground (anywhere).
     * Mid-air Exit abandons the airframe on AUTOPILOT until fuel runs out or takeover.
     * Emergencies still call {@link #exit}.
     */
    public void requestExit(Player player, String reason) {
        if (player == null) {
            return;
        }
        DroneSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            exit(player, reason);
            return;
        }
        if ("manual".equals(reason) || "command".equals(reason)) {
            boolean grounded = !isAirframeAirborne(player, session);
            if (grounded) {
                player.sendMessage(Component.text("Touchdown — parking", NamedTextColor.GREEN));
                exit(player, "landing");
                return;
            }
            // Mid-air abandon — keep flying unmanned (orbit if locked, else cruise).
            boolean wasOrbit = session.orbit && session.orbitCenter != null;
            if (session.parkedPadId != null && !orphans.containsKey(session.parkedPadId)) {
                startAbandonedOrphan(player, session);
            }
            String air = airframeLabel(session);
            exitAirborneForTakeover(player, false);
            player.sendMessage(Component.text(
                    "Abandoned " + air + (wasOrbit ? " — ORBIT AUTOPILOT" : " — AUTOPILOT")
                            + " until fuel runs out or someone retakes the seat.",
                    NamedTextColor.YELLOW));
            return;
        }
        exit(player, reason);
    }

    public void exit(Player player, String reason) {
        if (player == null) {
            return;
        }
        // Disconnect while airborne: scrap the airframe. Manual/command use landing / RTB.
        DroneSession peek = sessions.get(player.getUniqueId());
        String exitReason = reason;
        if (peek != null && "disconnect".equals(reason) && isAirframeAirborne(player, peek)) {
            beginCrashFromPilot(player);
            exitReason = "abandoned — crashing";
        }
        DroneSession session = sessions.remove(player.getUniqueId());
        // Always clear leftover pilot cloak — death/respawn can leave setInvisible stuck
        // even when the session map was already emptied.
        clearPilotInvisibility(player);
        player.removeScoreboardTag(PILOT_TAG);
        sendZoomLevel(player, 0);
        sendHudClear(player, disconnectBanner(session != null ? session : peek, exitReason));
        broadcastDroneVis();
        if (plugin.droneSeats() != null) {
            plugin.droneSeats().clearOccupied(player.getUniqueId());
        }
        if (session == null) {
            return;
        }
        reason = exitReason;
        if (isCleanParkExit(reason) && session.parkedPadId != null && plugin.dronePads() != null) {
            plugin.dronePads().padById(session.parkedPadId).ifPresent(pad -> {
                pad.structureHp = session.structureHp;
                pad.flareCharges = Math.max(0, Math.min(FLARE_CHARGES_MAX, session.flareCharges));
                if (plugin.hydrazine() != null
                        && (session.type == BigDroneType.X37B
                        || BigDroneType.X37B.equals(plugin.dronePads().typeOf(pad)))) {
                    plugin.hydrazine().startLeak(pad);
                }
            });
            plugin.dronePads().persistPads();
        }
        clearDisplays(session);
        removeBody(session);
        if (plugin.laserBridge() != null) {
            plugin.laserBridge().clearBeam(player);
        }
        clearForcedEntities(player, session);
        if (session.savedViewDistance >= 2) {
            player.setViewDistance(session.savedViewDistance);
        }

        restoreHotbar(player, session);
        restorePilotEquipment(player, session);
        player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, session.savedHeldSlot)));

        player.setCollidable(true);
        player.setFlySpeed(session.savedFlySpeed);
        player.setAllowFlight(session.savedAllowFlight);
        if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
            player.setFlying(session.savedAllowFlight && session.savedFlying);
        } else {
            player.setFlying(session.savedFlying);
        }
        player.setFallDistance(0f);
        Location back = session.padStandLocation.clone();
        if (back.getWorld() != null) {
            player.teleport(back);
        }
        // Teleport / potion race: force-visible again after landing at the pad.
        clearPilotInvisibility(player);
        // After airframe loss, ignore splash from the dive/LAW boom at the seat.
        // Do NOT grant this for "seat destroyed" — a seat bomb must be able to kill them.
        if (reason != null && (reason.contains("crash") || reason.contains("shot")
                || reason.equals("death"))) {
            player.setNoDamageTicks(Math.max(player.getNoDamageTicks(), 100));
            player.setFireTicks(0);
            player.setFallDistance(0f);
        }
        if (plugin.dronePads() != null) {
            plugin.dronePads().tickInteractEntities();
        }
        broadcastDroneVis();
        player.sendActionBar(Component.empty());
        String air = airframeLabel(session);
        player.sendMessage(Component.text("Exited " + air
                        + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")"),
                NamedTextColor.GRAY));
    }

    public void exitAll() {
        for (UUID id : sessions.keySet().toArray(new UUID[0])) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                exit(player, "shutdown");
            } else {
                DroneSession orphan = sessions.remove(id);
                if (orphan != null) {
                    clearDisplays(orphan);
                    removeBody(orphan);
                }
            }
        }
        crashes.clear();
        orphans.clear();
    }

    public void forceExitAtPad(Block pad) {
        Optional<UUID> owner = padOwner(pad);
        if (owner.isEmpty()) {
            return;
        }
        Player pilot = plugin.getServer().getPlayer(owner.get());
        if (pilot != null && isPiloting(pilot)) {
            DroneSession session = sessions.get(pilot.getUniqueId());
            if (session != null && sameBlock(session.padBlockLocation, pad.getLocation())) {
                exit(pilot, "pad broken");
            }
        }
    }

    public void clearPad(Block pad) {
        if (pad == null) {
            return;
        }
        forceExitAtPad(pad);
        removePadData(pad);
        if (pad.getType() == PAD_MATERIAL) {
            pad.setType(Material.AIR, false);
        }
    }

    public void handleControlUse(Player player, String controlId, boolean leftClick) {
        if (controlId == null || !isPiloting(player)) {
            return;
        }
        switch (controlId) {
            case "fire" -> {
                if (leftClick) {
                    cycleBay(player, 1);
                } else {
                    fire(player);
                }
            }
            case "orbit" -> handleOrbitUse(player, leftClick);
            case "control" -> toggleControl(player);
            case "optic" -> cycleOptics(player);
            case "zoom", "zoom_in" -> adjustZoom(player, leftClick ? -1 : 1);
            case "zoom_out" -> adjustZoom(player, -1);
            case "speed", "orbit_speed", "orbit_fast" -> adjustOrbitSpeed(player, leftClick ? -1 : 1);
            case "orbit_slow" -> adjustOrbitSpeed(player, -1);
            case "width", "orbit_width", "orbit_wide" -> adjustOrbitWidth(player, leftClick ? -1 : 1);
            case "orbit_narrow" -> adjustOrbitWidth(player, -1);
            case "ir", "ir_laser", "laser" -> toggleIrLaser(player);
            case "flares", "flare" -> deployFlares(player);
            case "exit" -> requestExit(player, "manual");
            default -> {
            }
        }
    }

    public void toggleIrLaser(Player player) {
        session(player).ifPresent(session -> {
            session.irLaser = !session.irLaser;
            if (!session.irLaser && plugin.laserBridge() != null) {
                plugin.laserBridge().clearBeam(player);
            }
            player.sendMessage(Component.text(
                    session.irLaser ? "IR designator ON" : "IR designator OFF",
                    session.irLaser ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f,
                    session.irLaser ? 1.6f : 0.9f);
        });
    }

    /**
     * Manual = fixed-wing stick (throttle / bank / pitch).
     * Autopilot = hold horizontal course + down viewer (look free; does not steer).
     */
    public void toggleControl(Player player) {
        session(player).ifPresent(session -> {
            if (session.manualControl && !isAirframeAirborne(player, session)) {
                player.sendMessage(Component.text(
                        "Climb out before AUTOPILOT", NamedTextColor.GRAY));
                return;
            }
            if (session.manualControl) {
                engageAutopilot(player, session, true);
                player.sendMessage(Component.text(
                        "AUTOPILOT — holding course · down viewer (Orbit = circle look target)",
                        NamedTextColor.GREEN));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.55f, 0.8f);
            } else {
                beginManualFixedWing(player, session);
                player.sendMessage(Component.text(
                        "MANUAL — W/S throttle · A/D bank · look pitch climb/dive · no hover",
                        NamedTextColor.YELLOW));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.55f, 1.4f);
            }
        });
    }

    private void engageAutopilot(Player player, DroneSession session, boolean downView) {
        refreshCruiseFromMotion(player, session);
        ensureHorizontalCruise(player, session);
        if (session.flightPhase != FlightPhase.LANDING
                && session.flightPhase != FlightPhase.LANDED) {
            session.flightPhase = FlightPhase.CRUISE;
        }
        session.manualControl = false;
        session.stallWarn = false;
        session.orbit = false;
        session.orbitLocked = false;
        session.orbitCenter = null;
        session.orbitTarget = null;
        session.orbitTargetBlock = null;
        session.orbitRadius = 0;
        session.orbitLookYaw = Float.NaN;
        session.orbitLookPitch = Float.NaN;
        clearGimbalState(session);
        session.cruiseAltitude = player.getLocation().getY();
        if (downView) {
            Location look = player.getLocation();
            look.setPitch(DOWN_VIEW_PITCH);
            player.teleport(look);
        }
    }

    /** Horizontal unit vector from yaw (never uses look pitch — looking down must not dive). */
    private static Vector yawForward(Player player) {
        return yawToForward(player.getLocation().getYaw());
    }

    private static Vector yawToForward(float yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        return new Vector(-Math.sin(yaw), 0.0, Math.cos(yaw)).normalize();
    }

    private static float wrapYaw(float yaw) {
        float y = yaw % 360f;
        if (y < -180f) {
            y += 360f;
        }
        if (y >= 180f) {
            y -= 360f;
        }
        return y;
    }

    private static void ensureHorizontalCruise(Player player, DroneSession session) {
        if (session.cruiseDir != null && session.cruiseDir.lengthSquared() > 1.0e-6) {
            session.cruiseDir.setY(0);
            if (session.cruiseDir.lengthSquared() > 1.0e-6) {
                session.cruiseDir.normalize();
                return;
            }
        }
        session.cruiseDir = yawForward(player);
    }

    /** Client scroll / companion packet: kind = ADJUST_* , delta typically ±1. */
    public void handleAdjust(Player player, byte kind, int delta) {
        if (!isPiloting(player) || delta == 0) {
            return;
        }
        int step = delta > 0 ? 1 : -1;
        switch (kind) {
            case ADJUST_ZOOM -> adjustZoom(player, step);
            case ADJUST_SPEED -> adjustOrbitSpeed(player, step);
            case ADJUST_WIDTH -> adjustOrbitWidth(player, step);
            case ADJUST_BAY -> cycleBay(player, step);
            default -> {
            }
        }
    }

    /** LMB on LAW: rotate next-to-fire rocket in the bay. */
    public void cycleBay(Player player, int delta) {
        session(player).ifPresent(session -> {
            long now = System.currentTimeMillis();
            if (now < session.bayCycleUntilMs) {
                return;
            }
            session.bayCycleUntilMs = now + 220L;
            if (session.parkedPadId == null || plugin.dronePads() == null) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.4f, 0.6f);
                return;
            }
            Optional<String> next = plugin.dronePads().rotateRockets(session.parkedPadId, delta);
            session.ammo = plugin.dronePads().rocketCount(session.parkedPadId);
            if (next.isEmpty()) {
                player.sendMessage(Component.text("Bay empty", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.7f);
                return;
            }
            session.armedCueUntilMs = System.currentTimeMillis() + 2500L;
            String rid = next.get();
            String name = plugin.rounds().get(rid)
                    .map(r -> PlainTextComponentSerializer.plainText().serialize(
                            ItemFactory.colorize(r.displayName())))
                    .orElse(rid);
            String role = plugin.rounds().get(rid).map(RoundBlurbs::describe).orElse("");
            sendPilotActionBar(player, Component.text("ARMED · " + name, NamedTextColor.GOLD));
            player.sendMessage(Component.text("ARMED · " + name
                    + (role.isBlank() ? "" : " — " + role), NamedTextColor.GOLD));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.55f);
            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 0.45f, 1.3f);
        });
    }

    /**
     * Orbit: 1st RMB engages (look retargets POI). 2nd+ RMB locks the circle at the
     * current / aimed POI — free look while the drone keeps circling. LMB stops.
     */
    public void handleOrbitUse(Player player, boolean leftClick) {
        session(player).ifPresent(session -> {
            if (!isAirframeAirborne(player, session)) {
                player.sendMessage(Component.text(
                        "Orbit unavailable on the ground — climb out first", NamedTextColor.GRAY));
                return;
            }
            if (leftClick) {
                if (session.orbit) {
                    stopOrbit(player);
                }
                return;
            }
            if (session.manualControl) {
                engageAutopilot(player, session, false);
            }
            // Already orbiting: RMB locks (or re-locks) the POI and frees the camera.
            if (session.orbit && session.orbitTarget != null) {
                if (!session.orbitLocked) {
                    // Lock whatever we're circling now (no need to re-aim).
                    session.orbitLocked = true;
                    clearGimbalState(session);
                    player.sendMessage(Component.text(
                            "Orbit LOCKED — free look · RMB re-lock aim · LMB stop",
                            NamedTextColor.AQUA));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.65f, 1.35f);
                    return;
                }
                // Already locked: RMB sets a new locked POI from crosshair.
                if (!beginOrbit(player, session)) {
                    return;
                }
                session.orbitLocked = true;
                clearGimbalState(session);
                player.sendMessage(Component.text("Orbit re-locked on new point — free look", NamedTextColor.AQUA));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.55f, 1.7f);
                return;
            }
            if (!beginOrbit(player, session)) {
                return;
            }
            session.orbit = true;
            session.orbitLocked = false;
            player.sendMessage(Component.text(
                    "Orbit ON — look retargets · RMB again to LOCK free-look · LMB stop",
                    NamedTextColor.GREEN));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.4f);
        });
    }

    /** F-key: toggle orbit on/off. */
    public void toggleOrbit(Player player) {
        session(player).ifPresent(session -> {
            if (session.orbit) {
                stopOrbit(player);
                return;
            }
            handleOrbitUse(player, false);
        });
    }

    public void stopOrbit(Player player) {
        session(player).ifPresent(session -> {
            if (!session.orbit) {
                return;
            }
            double tx = -Math.sin(session.orbitAngle);
            double tz = Math.cos(session.orbitAngle);
            session.cruiseDir = new Vector(tx, 0, tz);
            if (session.cruiseDir.lengthSquared() > 1.0e-6) {
                session.cruiseDir.normalize();
            } else {
                session.cruiseDir = yawForward(player);
            }
            session.cruiseAltitude = player.getLocation().getY();
            session.orbit = false;
            session.orbitLocked = false;
            session.orbitCenter = null;
            session.orbitTarget = null;
            session.orbitTargetBlock = null;
            session.orbitLookYaw = Float.NaN;
            session.orbitLookPitch = Float.NaN;
            clearGimbalState(session);
            // Re-seed radius from standoff on the next lock.
            session.orbitRadius = 0;
            player.sendMessage(Component.text("Orbit OFF — autopilot cruise", NamedTextColor.YELLOW));
        });
    }

    /**
     * Explicit POI lock. Returns false if no real block under the crosshair (open sky).
     */
    private boolean beginOrbit(Player player, DroneSession session) {
        Block block = raycastSolidBlock(player);
        if (block == null) {
            player.sendMessage(Component.text("No orbit target — aim at a block", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.7f);
            return false;
        }
        Location target = blockCenter(block);
        Location here = player.getLocation();
        double dx = here.getX() - target.getX();
        double dy = here.getY() - target.getY();
        double dz = here.getZ() - target.getZ();
        double horiz = Math.hypot(dx, dz);

        if (!session.orbit || session.orbitTarget == null) {
            session.orbitHeight = here.getY() - target.getY();
        }

        session.orbitTargetBlock = block.getLocation();
        session.orbitTarget = target.clone();
        session.orbitCenter = target.clone();
        session.orbitLookYaw = here.getYaw();
        session.orbitLookPitch = here.getPitch();
        clearGimbalState(session);
        // Baseline the look gate so the very next tick doesn't immediately re-raycast.

        if (horiz < 0.75) {
            // Directly overhead — don't use atan2(0,0); pick angle from yaw and min radius.
            double yawRad = Math.toRadians(here.getYaw());
            session.orbitAngle = -yawRad; // matches Bukkit forward ≈ (-sin yaw, 0, cos yaw)
            session.orbitRadius = ORBIT_MIN_RADIUS;
        } else {
            session.orbitAngle = Math.atan2(dz, dx);
            // Always re-seed from horizontal standoff on each lock (Width can change later).
            session.orbitRadius = clampRadius(Math.max(horiz, ORBIT_MIN_RADIUS));
        }
        // Prefer a bit of height over the POI if we were nearly level with it.
        if (session.orbitHeight < ORBIT_MIN_HEIGHT_OVER_POI) {
            session.orbitHeight = Math.max(8.0, Math.abs(dy));
        }
        return true;
    }

    private static double clampRadius(double radius) {
        if (radius < ORBIT_MIN_RADIUS) {
            return ORBIT_MIN_RADIUS;
        }
        double max = ORBIT_MAX_RADIUS;
        // Wide-area ISR platforms can orbit farther out.
        // (session-less clamp used before type is known — caller may re-clamp)
        if (radius > max) {
            return max;
        }
        return radius;
    }

    private double clampRadius(double radius, DroneSession session) {
        double max = ORBIT_MAX_RADIUS;
        if (session != null && session.type != null && session.type.wideArea()) {
            max = 160.0;
        }
        if (radius < ORBIT_MIN_RADIUS) {
            return ORBIT_MIN_RADIUS;
        }
        if (radius > max) {
            return max;
        }
        return radius;
    }

    public void cycleOptics(Player player) {
        session(player).ifPresent(session -> {
            BigDroneType type = session.type != null ? session.type : BigDroneType.MQ9;
            session.optic = nextOptic(session.optic, type);
            applyOptic(player, session);
            String label = switch (session.optic) {
                case NORMAL -> "Normal";
                case NVG -> type.waterVision() ? "NVG + Water (H = palette)" : "NVG (H = palette)";
                case THERMAL -> type.waterVision() ? "Thermal + Water (H = palette)" : "Thermal (H = palette)";
            };
            player.sendMessage(Component.text("Drone optic: " + label, NamedTextColor.AQUA));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.6f);
        });
    }

    private static OpticMode nextOptic(OpticMode cur, BigDroneType type) {
        return switch (cur) {
            case NORMAL -> type.hasNvg() ? OpticMode.NVG
                    : (type.hasThermal() ? OpticMode.THERMAL : OpticMode.NORMAL);
            case NVG -> type.hasThermal() ? OpticMode.THERMAL : OpticMode.NORMAL;
            case THERMAL -> OpticMode.NORMAL;
        };
    }

    public void adjustZoom(Player player, int delta) {
        session(player).ifPresent(session -> {
            int maxZ = session.type != null ? session.type.maxZoom() : MAX_ZOOM;
            int next = Math.max(0, Math.min(maxZ, session.zoomLevel + delta));
            if (next == session.zoomLevel) {
                return;
            }
            session.zoomLevel = next;
            applyZoom(player, session);
            sendPilotActionBar(player, Component.text("Zoom " + session.zoomLevel + "/" + maxZ, NamedTextColor.YELLOW));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.45f, 1.0f + session.zoomLevel * 0.1f);
        });
    }

    public void adjustOrbitSpeed(Player player, int delta) {
        session(player).ifPresent(session -> {
            // 8 steps: 0.25x … 2.0x — drives orbit angular speed and free-flight cruise.
            int step = Math.round(session.orbitSpeed * 4f);
            int next = Math.max(1, Math.min(8, step + delta));
            session.orbitSpeed = next / 4f;
            applyDroneFlySpeed(player, session);
            sendPilotActionBar(player, Component.text(
                    "Drone speed " + String.format(java.util.Locale.ROOT, "%.2fx", session.orbitSpeed),
                    NamedTextColor.GREEN));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 0.8f + session.orbitSpeed * 0.3f);
        });
    }

    public void adjustOrbitWidth(Player player, int delta) {
        session(player).ifPresent(session -> {
            double base = session.orbitRadius > 0 ? session.orbitRadius : 16.0;
            double next = clampRadius(base + delta * ORBIT_WIDTH_STEP, session);
            if (Math.abs(next - session.orbitRadius) < 0.01) {
                return;
            }
            session.orbitRadius = next;
            sendPilotActionBar(player, Component.text(
                    "Orbit width " + String.format(java.util.Locale.ROOT, "%.0fm", next),
                    NamedTextColor.GREEN));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.45f,
                    0.7f + (float) (next / ORBIT_MAX_RADIUS));
        });
    }

    /** Action-bar feedback only for clients without the companion OSD. */
    private void sendPilotActionBar(Player player, Component message) {
        if (plugin.companions() != null && plugin.companions().hasCompanion(player)) {
            return;
        }
        player.sendActionBar(message);
    }

    public void fire(Player player) {
        DroneSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        GunDefinition law = resolveDroneLaw();
        if (law == null) {
            player.sendMessage(Component.text("LAW definition missing", NamedTextColor.RED));
            return;
        }
        long now = System.currentTimeMillis();
        long delayMs = Math.max(1, law.bulletDelayTime()) * 50L;
        if (now - session.lastFireMs < delayMs) {
            return;
        }
        BigDroneType fireType = session.type != null ? session.type : BigDroneType.MQ9;
        if (fireType.cargoBay()) {
            fireCargoDrop(player, session, law);
            return;
        }
        RoundDefinition rocketRound = null;
        if (session.parkedPadId != null && plugin.dronePads() != null) {
            Optional<String> rocketId = plugin.dronePads().consumeRocket(session.parkedPadId);
            if (rocketId.isEmpty()) {
                session.ammo = 0;
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1.4f);
                player.sendMessage(ItemFactory.colorize(law.outOfAmmoMessage()));
                return;
            }
            rocketRound = plugin.rounds().get(rocketId.get()).orElse(null);
            session.ammo = plugin.dronePads().rocketCount(session.parkedPadId);
        } else {
            if (session.ammo <= 0) {
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1.4f);
                player.sendMessage(ItemFactory.colorize(law.outOfAmmoMessage()));
                return;
            }
            session.ammo--;
        }
        session.lastFireMs = now;

        Location eye = player.getEyeLocation();
        // Own rocket splash must not erase the chair operator; enemy seat bombs still can.
        shieldSeatFromOwnOrdnance(session);

        MunitionProfile profile = rocketRound != null
                ? MunitionProfile.ofRound(rocketRound.fileName()).orElse(null) : null;
        session.guidanceHud = profile != null ? profile.hudMode() : MunitionProfile.HudMode.NONE;
        session.trackQuality = 0;
        if (profile != null && profile.guidance() == MunitionProfile.Guidance.IR_AA) {
            fireHeatSeeker(player, session, law, rocketRound);
        } else if (profile != null && profile.warhead() == MunitionProfile.Warhead.KINETIC) {
            fireHellfireR9x(player, session, law, rocketRound);
        } else if (profile != null && profile.guided()) {
            fireGuidedStrike(player, session, law, rocketRound, profile);
        } else {
            double speed = Math.max(0.4, law.bulletSpeed());
            double accuracy = Math.min(law.accuracy(), DRONE_LAW_ACCURACY);
            Vector vec = createShotVector(player, accuracy, speed);
            WitherSkull skull = player.getWorld().spawn(eye, WitherSkull.class, s -> {
                s.setShooter(player);
                s.setDirection(vec.clone().normalize());
                s.setVelocity(vec);
                s.setCharged(false);
                s.setIsIncendiary(false);
                s.setYield(0f);
                s.setPersistent(true);
            });
            Bullet bullet = new Bullet(plugin, player, vec, law, skull, rocketRound);
            plugin.bullets().add(bullet);
        }

        boolean quiet = profile != null && (profile.glide()
                || profile.warhead() == MunitionProfile.Warhead.KINETIC);
        player.getWorld().playSound(eye, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH,
                quiet ? 0.7f : 1.2f, quiet ? 0.9f : 0.55f);
        if (!quiet) {
            player.getWorld().playSound(eye, Sound.ENTITY_GENERIC_EXPLODE, 0.35f, 1.6f);
        }
        for (Sound sound : law.gunSounds()) {
            try {
                if (law.localGunSound()) {
                    player.playSound(eye, sound, (float) law.gunVolume(), 1.2f);
                } else {
                    player.getWorld().playSound(eye, sound, (float) law.gunVolume(), 1.2f);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        String roundLabel = rocketRound != null
                ? PlainTextComponentSerializer.plainText().serialize(
                ItemFactory.colorize(rocketRound.displayName() != null
                        ? rocketRound.displayName() : rocketRound.fileName()))
                : "LAW";
        if (roundLabel == null || roundLabel.isBlank()) {
            roundLabel = rocketRound != null ? rocketRound.fileName() : "LAW";
        }
        sendPilotActionBar(player, Component.text(roundLabel + " " + session.ammo + " left", NamedTextColor.GOLD));
    }

    /** X-37B: LAW fire ejects the next cargo stack toward the look point. */
    private void fireCargoDrop(Player player, DroneSession session, GunDefinition law) {
        if (session.parkedPadId == null || plugin.dronePads() == null) {
            player.sendMessage(Component.text("No cargo bay linked", NamedTextColor.RED));
            return;
        }
        Optional<ItemStack> cargo = plugin.dronePads().consumeCargoFront(session.parkedPadId);
        if (cargo.isEmpty()) {
            session.ammo = 0;
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1.4f);
            player.sendMessage(Component.text("Cargo bay empty", NamedTextColor.GRAY));
            return;
        }
        session.lastFireMs = System.currentTimeMillis();
        session.ammo = plugin.dronePads().cargoCount(session.parkedPadId);

        Location eye = player.getEyeLocation();
        Location aim = LaserBeams.aimPoint(player, eye, 160.0, true, true);
        if (aim == null) {
            aim = eye.clone().add(eye.getDirection().normalize().multiply(48));
        }
        ItemStack drop = cargo.get();
        Location start = eye.clone().add(eye.getDirection().normalize().multiply(1.5));
        Vector vel = aim.toVector().subtract(start.toVector());
        if (vel.lengthSquared() < 1.0e-4) {
            vel = eye.getDirection().clone();
        }
        vel.normalize().multiply(1.55);

        org.bukkit.entity.Item entity = player.getWorld().dropItem(start, drop);
        entity.setPickupDelay(40);
        entity.setVelocity(vel);
        entity.setGlowing(true);
        final Location target = aim.clone();
        final UUID entityId = entity.getUniqueId();
        // Steer toward aim for ~1.2s then settle.
        for (int i = 1; i <= 12; i++) {
            final int step = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Entity e = Bukkit.getEntity(entityId);
                if (!(e instanceof org.bukkit.entity.Item item) || !item.isValid()) {
                    return;
                }
                if (step >= 12) {
                    item.teleport(target);
                    item.setVelocity(new Vector(0, -0.05, 0));
                    item.setGlowing(false);
                    item.setPickupDelay(10);
                    return;
                }
                Vector to = target.toVector().subtract(item.getLocation().toVector());
                if (to.lengthSquared() < 0.36) {
                    item.teleport(target);
                    item.setVelocity(new Vector(0, -0.05, 0));
                    item.setGlowing(false);
                    item.setPickupDelay(10);
                    return;
                }
                item.setVelocity(to.normalize().multiply(1.35));
            }, i * 2L);
        }

        player.getWorld().playSound(eye, Sound.ENTITY_SNOWBALL_THROW, 0.9f, 0.7f);
        player.getWorld().playSound(eye, Sound.BLOCK_PISTON_EXTEND, 0.45f, 1.4f);
        String name = drop.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        sendPilotActionBar(player, Component.text(
                "CARGO DROP · " + name + " · " + session.ammo + " left", NamedTextColor.AQUA));
    }

    /**
     * AGM-114R9X — laser-designated Hellfire with kinetic blade warhead.
     * No explosive: pop-out blades + mass kill a tiny radius only.
     */
    private void fireHellfireR9x(Player player, DroneSession session, GunDefinition law, RoundDefinition round) {
        Location sensor = sensorCameraOrigin(player, session);
        Location aim = LaserBeams.aimPoint(player, sensor, R9X_RANGE, true, true);
        if (aim == null) {
            aim = sensor.clone().add(sensor.getDirection().normalize().multiply(64));
        }
        // Drop clear of the airframe, then dive onto the laser spot.
        Location start = sensor.clone().add(sensor.getDirection().normalize().multiply(1.2));
        Vector to = aim.toVector().subtract(start.toVector());
        if (to.lengthSquared() < 1.0e-4) {
            to = sensor.getDirection().clone();
        }
        Vector vel = to.normalize().multiply(R9X_SPEED);
        HellfireR9x m = new HellfireR9x(player.getUniqueId(), start, vel, aim.clone(), law, round);
        r9xMissiles.add(m);
        player.sendMessage(Component.text("AGM-114R9X — laser track · kinetic blades", NamedTextColor.WHITE));
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.45f, 1.8f);
    }

    private void fireGuidedStrike(Player player, DroneSession session, GunDefinition law,
                                  RoundDefinition round, MunitionProfile profile) {
        Location sensor = sensorCameraOrigin(player, session);
        double range = Math.max(80.0, profile.range());
        Location aim = LaserBeams.aimPoint(player, sensor, range, true, true);
        if (aim == null) {
            aim = sensor.clone().add(sensor.getDirection().normalize().multiply(48));
        }
        Location start = sensor.clone().add(sensor.getDirection().normalize().multiply(1.35));
        Vector to = aim.toVector().subtract(start.toVector());
        if (to.lengthSquared() < 1.0e-4) {
            to = sensor.getDirection().clone();
        }
        double speed = Math.max(0.35, profile.speed());
        // Glide bombs drop quieter with a shallower initial dive.
        if (profile.glide()) {
            to.setY(Math.min(to.getY(), -0.15));
            if (to.lengthSquared() < 1.0e-4) {
                to = new Vector(0, -1, 0);
            }
        }
        Vector vel = to.normalize().multiply(speed);
        GuidedStrike m = new GuidedStrike(player.getUniqueId(), start, vel, aim.clone(), law, round, profile);
        m.lastKnown = aim.clone();
        m.guideState = GUIDE_LASER;
        m.trackQuality = 70;
        if (profile.guidance() == MunitionProfile.Guidance.MULTI) {
            m.entityTarget = acquireGroundTrack(player, start, vel.clone().normalize());
            m.guideState = m.entityTarget != null ? GUIDE_IIR : GUIDE_SAL;
        }
        guidedStrikes.add(m);
        session.guidanceHud = profile.hudMode();
        session.guideState = m.guideState;
        session.trackQuality = m.trackQuality;
        String label = switch (profile) {
            case ROCKET_MAC -> "AGM-114M MAC — laser · concussion";
            case ROCKET_ROMEO -> "AGM-114R Romeo — laser · HE";
            case ROCKET_JAGM -> "JAGM — dual-mode · TRACK/LASER";
            case GBU_VIPER -> "GBU-Viper — laser glide";
            case GBU_SGM -> "GBU-SGM — laser glide";
            case GBU_SDB -> "GBU-SDB — penetrator";
            case GBU_STORM -> "StormBreaker — multi-seek";
            case GBU_PAVEWAY -> "Paveway — laser bomb";
            case GBU_SONAR -> "GBU-Sonar — marker · LOS glow 120m / 90s";
            default -> profile.id() + " — guided";
        };
        player.sendMessage(Component.text(label, NamedTextColor.AQUA));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.55f, 1.4f);
    }

    private void tickGuidedStrikes() {
        if (guidedStrikes.isEmpty()) {
            return;
        }
        Iterator<GuidedStrike> it = guidedStrikes.iterator();
        while (it.hasNext()) {
            GuidedStrike m = it.next();
            m.ticks++;
            int maxTicks = (int) Math.max(120, Math.min(480, m.profile.range() / Math.max(0.3, m.profile.speed()) + 40));
            if (m.ticks > maxTicks || m.pos.getWorld() == null) {
                it.remove();
                continue;
            }
            Player shooter = Bukkit.getPlayer(m.shooter);
            updateGuidedAim(m, shooter);
            double speed = Math.max(0.35, m.profile.speed());
            if (m.profile.glide() && m.ticks > 12 && !m.wingsOut) {
                m.wingsOut = true;
                World ww = m.pos.getWorld();
                if (ww != null) {
                    ww.playSound(m.pos, Sound.ITEM_ARMOR_EQUIP_ELYTRA, 0.8f, 1.2f);
                    ww.spawnParticle(Particle.CLOUD, m.pos, 8, 0.3, 0.1, 0.3, 0.01);
                }
            }
            // Terminal dive for glides / SDB / paveway
            if (m.profile.glide() && m.ticks > maxTicks / 2) {
                speed = Math.min(speed * 1.35, speed + 0.35);
            }
            Vector toAim = m.aim.toVector().subtract(m.pos.toVector());
            double dist = toAim.length();
            if (m.ticks >= GUIDED_MIN_FLIGHT) {
                Block block = m.pos.getBlock();
                if (isCrashSolid(block) || dist <= GUIDED_HIT_RADIUS) {
                    impactGuidedStrike(m);
                    it.remove();
                    continue;
                }
                Location prev = m.pos.clone().subtract(m.vel);
                if (traceAirframeSegment(prev, m.pos, m.shooter).isPresent()
                        || traceParkedSegment(prev, m.pos, m.shooter).isPresent()) {
                    impactGuidedStrike(m);
                    it.remove();
                    continue;
                }
                boolean hitEntity = false;
                for (Entity e : m.pos.getWorld().getNearbyEntities(m.pos, 1.6, 1.6, 1.6)) {
                    if (!(e instanceof LivingEntity living) || !living.isValid()) {
                        continue;
                    }
                    if (living instanceof Player p) {
                        if (p.getUniqueId().equals(m.shooter)) {
                            continue;
                        }
                        if (isPiloting(p)) {
                            hitEntity = true;
                            break;
                        }
                    }
                    hitEntity = true;
                    break;
                }
                if (hitEntity) {
                    impactGuidedStrike(m);
                    it.remove();
                    continue;
                }
            }
            if (dist > 1.0e-4) {
                double blend = m.profile.glide() ? 0.42 : 0.62;
                Vector desired = toAim.normalize().multiply(speed);
                m.vel = m.vel.multiply(1.0 - blend).add(desired.multiply(blend));
                if (m.vel.lengthSquared() > 1.0e-6) {
                    m.vel.normalize().multiply(speed);
                }
            }
            m.pos.add(m.vel);
            World w = m.pos.getWorld();
            if (w != null) {
                if (m.profile.glide()) {
                    w.spawnParticle(Particle.CLOUD, m.pos, 1, 0.05, 0.02, 0.05, 0.0);
                    if (m.wingsOut) {
                        w.spawnParticle(Particle.END_ROD, m.pos, 1, 0.08, 0.02, 0.08, 0.0);
                    }
                } else {
                    w.spawnParticle(Particle.SMOKE, m.pos, 1, 0.02, 0.02, 0.02, 0.0);
                    w.spawnParticle(Particle.FLAME, m.pos, 1, 0.02, 0.02, 0.02, 0.0);
                }
                if (m.ticks % 6 == 0 && !m.profile.glide()) {
                    w.playSound(m.pos, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.18f, 1.25f);
                }
            }
            if (shooter != null) {
                session(shooter).ifPresent(s -> {
                    s.guidanceHud = m.profile.hudMode();
                    s.guideState = m.guideState;
                    s.trackQuality = m.trackQuality;
                    if (m.aim != null && m.aim.getWorld() != null) {
                        s.seekerX = (float) m.aim.getX();
                        s.seekerY = (float) m.aim.getY();
                        s.seekerZ = (float) m.aim.getZ();
                    }
                });
            }
        }
    }

    /** IR / thermal smoke spoofs dual-mode + multi-seek (Storm/JAGM reacquire / IIR). */
    private boolean irSpoofed(Location at) {
        return plugin.smoke() != null && plugin.smoke().blocksIrSeekers(at);
    }

    private void updateGuidedAim(GuidedStrike m, Player shooter) {
        Location laserAim = null;
        if (shooter != null && shooter.isOnline() && isPiloting(shooter)) {
            DroneSession sess = sessions.get(shooter.getUniqueId());
            Location sensor = sensorCameraOrigin(shooter, sess);
            laserAim = LaserBeams.aimPoint(shooter, sensor, m.profile.range(), true, true);
            if (laserAim != null && laserAim.getWorld() != null
                    && laserAim.getWorld().equals(m.pos.getWorld())) {
                m.lastKnown = laserAim.clone();
            }
        }
        MunitionProfile.Guidance g = m.profile.guidance();
        if (g == MunitionProfile.Guidance.LASER || g == MunitionProfile.Guidance.GLIDE
                || g == MunitionProfile.Guidance.GLIDE_PEN || g == MunitionProfile.Guidance.LASER_BOMB) {
            if (laserAim != null) {
                m.aim = laserAim.clone();
                m.guideState = GUIDE_LASER;
                m.trackQuality = 90;
            } else if (m.lastKnown != null) {
                m.aim = m.lastKnown.clone();
                m.guideState = GUIDE_LOST;
                m.trackQuality = Math.max(20, m.trackQuality - 3);
            }
            return;
        }
        if (g == MunitionProfile.Guidance.DUAL) {
            if (laserAim != null) {
                m.aim = laserAim.clone();
                m.guideState = GUIDE_LASER;
                m.trackQuality = 95;
                m.lostTicks = 0;
            } else if (m.lastKnown != null) {
                m.lostTicks++;
                m.aim = m.lastKnown.clone();
                if (m.lostTicks < 40) {
                    m.guideState = GUIDE_TRACK;
                    m.trackQuality = 60;
                } else if (m.lostTicks < 80) {
                    m.guideState = GUIDE_LOST;
                    m.trackQuality = 25;
                } else {
                    // Reacquire — IR/thermal smoke spoofs dual-mode seekers
                    LivingEntity re = nearestLivingNear(m.lastKnown, 18.0, m.shooter);
                    if (re != null && !irSpoofed(re.getLocation())) {
                        m.aim = re.getLocation().add(0, re.getHeight() * 0.5, 0);
                        m.lastKnown = m.aim.clone();
                        m.guideState = GUIDE_REACQUIRE;
                        m.trackQuality = 55;
                        m.lostTicks = 20;
                    } else {
                        if (re != null && irSpoofed(re.getLocation())) {
                            m.guideState = GUIDE_LOST;
                            m.trackQuality = 10;
                        } else {
                            m.guideState = GUIDE_RADAR;
                            m.trackQuality = 15;
                        }
                    }
                }
            }
            return;
        }
        if (g == MunitionProfile.Guidance.MULTI) {
            // Prefer laser (SAL), else IIR entity track, else MMW coast
            if (laserAim != null) {
                m.aim = laserAim.clone();
                m.guideState = GUIDE_SAL;
                m.trackQuality = 92;
                return;
            }
            if (m.entityTarget != null) {
                Entity e = Bukkit.getEntity(m.entityTarget);
                if (e instanceof LivingEntity living && living.isValid()) {
                    Location body = living.getLocation().add(0, living.getHeight() * 0.45, 0);
                    if (irSpoofed(body)) {
                        m.entityTarget = null;
                        m.guideState = GUIDE_LOST;
                        m.trackQuality = 12;
                    } else {
                        m.aim = body;
                        m.lastKnown = m.aim.clone();
                        m.guideState = GUIDE_IIR;
                        m.trackQuality = 80;
                        return;
                    }
                } else {
                    m.entityTarget = null;
                }
            }
            UUID next = acquireGroundTrack(shooter, m.pos, m.vel.clone().normalize());
            if (next != null) {
                Entity e = Bukkit.getEntity(next);
                if (e != null && !irSpoofed(e.getLocation())) {
                    m.entityTarget = next;
                    m.aim = e.getLocation().add(0, 1, 0);
                    m.guideState = GUIDE_IIR;
                    m.trackQuality = 70;
                    return;
                }
            }
            if (m.lastKnown != null) {
                m.aim = m.lastKnown.clone();
                m.guideState = GUIDE_MMW;
                m.trackQuality = 40;
            }
        }
    }

    private UUID acquireGroundTrack(Player shooter, Location from, Vector forward) {
        if (from == null || from.getWorld() == null || forward == null || forward.lengthSquared() < 1.0e-6) {
            return null;
        }
        Vector dir = forward.clone().normalize();
        UUID best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : from.getWorld().getNearbyEntities(from, 96, 48, 96)) {
            if (!(e instanceof LivingEntity living) || !living.isValid()) {
                continue;
            }
            if (living instanceof Player p) {
                if (shooter != null && p.getUniqueId().equals(shooter.getUniqueId())) {
                    continue;
                }
                if (isPiloting(p)) {
                    continue;
                }
            }
            Vector to = living.getLocation().toVector().subtract(from.toVector());
            double dist = to.length();
            if (dist < 4 || dist > 110) {
                continue;
            }
            double dot = to.normalize().dot(dir);
            if (dot < 0.35) {
                continue;
            }
            double score = dist / Math.max(0.2, dot);
            if (score < bestScore) {
                bestScore = score;
                best = living.getUniqueId();
            }
        }
        return best;
    }

    private LivingEntity nearestLivingNear(Location at, double radius, UUID excludeShooter) {
        if (at == null || at.getWorld() == null) {
            return null;
        }
        LivingEntity best = null;
        double bestD = radius * radius;
        for (Entity e : at.getWorld().getNearbyEntities(at, radius, radius, radius)) {
            if (!(e instanceof LivingEntity living) || !living.isValid()) {
                continue;
            }
            if (living instanceof Player p) {
                if (p.getUniqueId().equals(excludeShooter) || isPiloting(p)) {
                    continue;
                }
            }
            double d = living.getLocation().distanceSquared(at);
            if (d < bestD) {
                bestD = d;
                best = living;
            }
        }
        return best;
    }

    private void impactGuidedStrike(GuidedStrike m) {
        Location at = m.pos.clone();
        Player shooter = Bukkit.getPlayer(m.shooter);
        if (plugin.strikeEffects() != null) {
            plugin.strikeEffects().detonate(at, shooter, m.gun, m.round, m.profile);
        } else {
            ImpactEffects.apply(m.gun, shooter, at, m.round, plugin);
            absorbExplosionSplash(at, m.profile.effectRadius(), shooter, m.gun, m.round);
        }
        if (shooter != null && shooter.isOnline()) {
            shooter.sendMessage(Component.text(m.profile.id() + " impact", NamedTextColor.GRAY));
            session(shooter).ifPresent(s -> {
                s.guideState = GUIDE_NONE;
                s.trackQuality = 0;
            });
        }
    }

    private void tickR9xMissiles() {
        if (r9xMissiles.isEmpty()) {
            return;
        }
        Iterator<HellfireR9x> it = r9xMissiles.iterator();
        while (it.hasNext()) {
            HellfireR9x m = it.next();
            m.ticks++;
            if (m.ticks > R9X_MAX_TICKS || m.pos.getWorld() == null) {
                it.remove();
                continue;
            }
            Player shooter = Bukkit.getPlayer(m.shooter);
            // Semi-active laser: track the sensor-camera aim (same as IR designator / crosshair).
            if (shooter != null && shooter.isOnline() && isPiloting(shooter)) {
                DroneSession sess = sessions.get(shooter.getUniqueId());
                Location sensor = sensorCameraOrigin(shooter, sess);
                Location aim = LaserBeams.aimPoint(shooter, sensor, R9X_RANGE, true, true);
                if (aim != null && aim.getWorld() != null && aim.getWorld().equals(m.pos.getWorld())) {
                    m.aim = aim.clone();
                }
            }
            Vector toAim = m.aim.toVector().subtract(m.pos.toVector());
            double dist = toAim.length();
            boolean nearImpact = dist <= R9X_BLADE_DEPLOY_DIST || m.bladesOut;
            if (nearImpact && !m.bladesOut) {
                m.bladesOut = true;
                spawnR9xBladeDeployFx(m.pos);
            }
            if (m.ticks >= R9X_MIN_FLIGHT) {
                // Entity intercept before ground — blades shred whoever is on the laser spot.
                if (tryR9xEntityHit(m)) {
                    it.remove();
                    continue;
                }
                Block block = m.pos.getBlock();
                if (isCrashSolid(block) || dist <= R9X_HIT_RADIUS) {
                    impactHellfireR9x(m);
                    it.remove();
                    continue;
                }
                // Airborne / parked airframe OBB
                Location prev = m.pos.clone().subtract(m.vel);
                var air = traceAirframeSegment(prev, m.pos, m.shooter);
                if (air.isPresent()) {
                    impactHellfireR9x(m);
                    it.remove();
                    continue;
                }
                var parked = traceParkedSegment(prev, m.pos, m.shooter);
                if (parked.isPresent()) {
                    impactHellfireR9x(m);
                    it.remove();
                    continue;
                }
            }
            if (dist > 1.0e-4) {
                Vector desired = toAim.normalize().multiply(R9X_SPEED);
                // Tight laser guidance
                m.vel = m.vel.multiply(0.35).add(desired.multiply(0.65));
                if (m.vel.lengthSquared() > 1.0e-6) {
                    m.vel.normalize().multiply(R9X_SPEED);
                }
            }
            m.pos.add(m.vel);
            World w = m.pos.getWorld();
            if (w != null) {
                w.spawnParticle(Particle.SMOKE, m.pos, 1, 0.02, 0.02, 0.02, 0.0);
                w.spawnParticle(Particle.CRIT, m.pos, m.bladesOut ? 4 : 1, 0.05, 0.05, 0.05, 0.01);
                if (m.bladesOut) {
                    drawR9xBladeRing(m.pos, 0.55 + (m.ticks % 3) * 0.08);
                }
                if (m.ticks % 5 == 0) {
                    w.playSound(m.pos, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.22f, 1.35f);
                }
            }
        }
    }

    private boolean tryR9xEntityHit(HellfireR9x m) {
        World w = m.pos.getWorld();
        if (w == null) {
            return false;
        }
        for (Entity e : w.getNearbyEntities(m.pos, R9X_HIT_RADIUS, R9X_HIT_RADIUS, R9X_HIT_RADIUS)) {
            if (!(e instanceof LivingEntity living)) {
                continue;
            }
            if (e instanceof Player p) {
                if (p.getUniqueId().equals(m.shooter)) {
                    continue;
                }
                if (isPiloting(p)) {
                    // Airframe hit handled via structure absorb at impact
                    impactHellfireR9x(m);
                    return true;
                }
            }
            impactHellfireR9x(m);
            return true;
        }
        return false;
    }

    private void spawnR9xBladeDeployFx(Location at) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        World w = at.getWorld();
        w.playSound(at, Sound.ITEM_TRIDENT_RETURN, 1.1f, 0.55f);
        w.playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.7f);
        drawR9xBladeRing(at, 0.9);
    }

    private void drawR9xBladeRing(Location center, double radius) {
        if (center == null || center.getWorld() == null) {
            return;
        }
        World w = center.getWorld();
        // Six blades — AGM-114R9X pop-outs
        for (int i = 0; i < 6; i++) {
            double ang = (Math.PI * 2.0 * i) / 6.0 + center.getYaw() * 0.01;
            double x = Math.cos(ang) * radius;
            double z = Math.sin(ang) * radius;
            Location tip = center.clone().add(x, 0.05, z);
            w.spawnParticle(Particle.SWEEP_ATTACK, tip, 1, 0, 0, 0, 0);
            w.spawnParticle(Particle.CRIT, tip, 2, 0.05, 0.02, 0.05, 0.01);
            // Trace blade edge toward center
            for (double t = 0.2; t <= 1.0; t += 0.2) {
                Location edge = center.clone().add(x * t, 0.02, z * t);
                w.spawnParticle(Particle.END_ROD, edge, 1, 0, 0, 0, 0);
            }
        }
    }

    private void impactHellfireR9x(HellfireR9x m) {
        Location at = m.pos.clone();
        Player shooter = Bukkit.getPlayer(m.shooter);
        World w = at.getWorld();
        if (w == null) {
            return;
        }
        m.bladesOut = true;
        drawR9xBladeRing(at, 1.35);
        drawR9xBladeRing(at, 0.7);
        w.spawnParticle(Particle.CRIT, at, 40, 0.55, 0.35, 0.55, 0.15);
        w.spawnParticle(Particle.SWEEP_ATTACK, at, 8, 0.4, 0.2, 0.4, 0);
        w.spawnParticle(Particle.ITEM, at, 28, 0.45, 0.25, 0.45, 0.08, new ItemStack(Material.IRON_SWORD));
        w.playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.35f, 0.55f);
        w.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.85f, 1.45f);
        w.playSound(at, Sound.ITEM_TRIDENT_HIT, 1.1f, 0.75f);
        w.playSound(at, Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.7f, 1.3f);
        // No createExplosion / blastShock / fire — kinetic only.

        double r2 = R9X_KILL_RADIUS * R9X_KILL_RADIUS;
        for (Entity e : w.getNearbyEntities(at, R9X_KILL_RADIUS, R9X_KILL_RADIUS, R9X_KILL_RADIUS)) {
            if (!(e instanceof LivingEntity living) || !living.isValid()) {
                continue;
            }
            if (living instanceof Player p) {
                if (p.getUniqueId().equals(m.shooter)) {
                    continue;
                }
                if (isPiloting(p)) {
                    absorbBulletHit(p, shooter, at, m.gun, m.round);
                    continue;
                }
            }
            Location body = living.getLocation().add(0, living.getHeight() * 0.5, 0);
            if (body.distanceSquared(at) > r2) {
                continue;
            }
            living.setNoDamageTicks(0);
            if (plugin.medical() != null && living instanceof Player hurt) {
                plugin.medical().flagBulletWound(hurt.getUniqueId());
            }
            // Speeding anvil + blades — lethal in the few-foot danger-close.
            Bullet.applyAttributedDamage(living, 10_000.0, shooter);
            if (living instanceof Player p && !p.isDead() && p.getHealth() > 0) {
                p.setHealth(0.0);
            }
            announceWeaponKill(shooter, living, "shredded", "AGM-114R9X");
        }
        // Parked hull under the laser spot
        if (plugin.dronePads() != null) {
            for (DronePadService.ParkedPad pad : plugin.dronePads().allPads()) {
                if (pad == null || isParkedPadInUse(pad.id)) {
                    continue;
                }
                Location c = plugin.dronePads().airframeCenter(pad);
                if (c == null || c.getWorld() == null || !c.getWorld().equals(w)) {
                    continue;
                }
                if (c.distanceSquared(at) <= r2) {
                    absorbParkedHit(pad, shooter, m.gun, at, m.round);
                }
            }
        }
        if (shooter != null && shooter.isOnline()) {
            shooter.sendMessage(Component.text("R9X impact — kinetic blades", NamedTextColor.GRAY));
        }
    }

    private void fireHeatSeeker(Player player, DroneSession session, GunDefinition law, RoundDefinition round) {
        MunitionProfile profile = MunitionProfile.ofRound(round != null ? round.fileName() : null)
                .orElse(MunitionProfile.ROCKET_AA);
        Location start = player.getEyeLocation().clone()
                .add(player.getEyeLocation().getDirection().multiply(1.2));
        double speed = Math.max(0.8, profile.speed());
        Vector vel = player.getEyeLocation().getDirection().normalize().multiply(speed);
        AaMissile m = new AaMissile(player.getUniqueId(), start, vel, law, round, profile);
        m.targetPilot = acquireAaTarget(player, start, vel.clone().normalize(), null);
        aaMissiles.add(m);
        session.guidanceHud = profile.hudMode();
        session.guideState = m.targetPilot != null ? GUIDE_LOCK : GUIDE_TRACK;
        session.trackQuality = m.targetPilot != null ? 85 : 35;
        boolean sidewinder = profile == MunitionProfile.AIM9X;
        if (m.targetPilot != null) {
            Player target = Bukkit.getPlayer(m.targetPilot);
            if (target != null) {
                warnMissileInbound(target);
            }
            player.sendMessage(Component.text(
                    sidewinder ? "AIM-9X — IR LOCK" : "HEAT SEEK — tracking airframe",
                    NamedTextColor.GOLD));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.85f);
        } else {
            player.sendMessage(Component.text(
                    sidewinder ? "AIM-9X — searching…" : "HEAT SEEK — searching…",
                    NamedTextColor.YELLOW));
        }
    }

    /** Nearest enemy airborne UAV in the seeker cone (null = keep searching). */
    private UUID acquireAaTarget(Player shooter, Location from, Vector forward, UUID current) {
        if (from == null || from.getWorld() == null || forward == null || forward.lengthSquared() < 1.0e-6) {
            return current;
        }
        Vector dir = forward.clone().normalize();
        UUID best = current;
        double bestScore = current != null ? -1.0 : Double.MAX_VALUE;
        // Re-validate current lock
        if (current != null) {
            Player cur = Bukkit.getPlayer(current);
            if (cur == null || !isPiloting(cur) || (shooter != null && cur.getUniqueId().equals(shooter.getUniqueId()))) {
                best = null;
                bestScore = Double.MAX_VALUE;
            } else {
                Location at = droneWorldLocation(cur);
                if (at == null || at.getWorld() == null || !at.getWorld().equals(from.getWorld())) {
                    best = null;
                    bestScore = Double.MAX_VALUE;
                } else {
                    bestScore = at.distanceSquared(from);
                }
            }
        }
        for (UUID id : sessions.keySet()) {
            if (shooter != null && id.equals(shooter.getUniqueId())) {
                continue;
            }
            Player pilot = Bukkit.getPlayer(id);
            if (pilot == null || !isPiloting(pilot)) {
                continue;
            }
            Location at = droneWorldLocation(pilot);
            if (at == null || at.getWorld() == null || !at.getWorld().equals(from.getWorld())) {
                continue;
            }
            Vector to = at.toVector().subtract(from.toVector());
            double dist = to.length();
            if (dist < 6.0 || dist > AA_ACQUIRE_RANGE) {
                continue;
            }
            double dot = to.normalize().dot(dir);
            if (dot < AA_ACQUIRE_DOT) {
                continue;
            }
            if (irSpoofed(at)) {
                continue;
            }
            // Prefer closer targets still in the cone
            double score = dist / Math.max(0.2, dot);
            if (score < bestScore) {
                bestScore = score;
                best = id;
            }
        }
        return best;
    }

    private void tickAaMissiles() {
        if (aaMissiles.isEmpty()) {
            return;
        }
        Iterator<AaMissile> it = aaMissiles.iterator();
        while (it.hasNext()) {
            AaMissile m = it.next();
            m.ticks++;
            if (m.ticks > AA_MAX_TICKS || m.pos.getWorld() == null) {
                it.remove();
                continue;
            }
            // Terrain impact
            Block block = m.pos.getBlock();
            if (m.ticks >= AA_MIN_FLIGHT && isCrashSolid(block)) {
                detonateAaMissile(m, null);
                it.remove();
                continue;
            }
            Vector fwd = m.vel.clone();
            if (fwd.lengthSquared() < 1.0e-6) {
                fwd = new Vector(0, 0, 1);
            } else {
                fwd.normalize();
            }
            Player shooter = Bukkit.getPlayer(m.shooter);
            UUID next = acquireAaTarget(shooter, m.pos, fwd, m.targetPilot);
            if (next != null && (m.targetPilot == null || !m.targetPilot.equals(next))) {
                m.targetPilot = next;
                Player t = Bukkit.getPlayer(next);
                if (t != null) {
                    warnMissileInbound(t);
                }
            }
            double speed = m.profile != null ? Math.max(0.8, m.profile.speed()) : AA_SPEED;
            if (m.targetPilot != null) {
                Player pilot = Bukkit.getPlayer(m.targetPilot);
                if (pilot == null || !isPiloting(pilot)) {
                    m.targetPilot = null;
                } else if (m.profile != null && m.profile.flareDecoy() && isFlareActive(pilot)) {
                    Location at = droneWorldLocation(pilot);
                    if (at != null && plugin.javelin() != null) {
                        plugin.javelin().spawnFlareBurst(at);
                    }
                    if (pilot.isOnline()) {
                        pilot.sendMessage(Component.text("FLARES decoyed the AA missile!", NamedTextColor.GREEN));
                    }
                    if (shooter != null) {
                        shooter.sendMessage(Component.text("AA missile decoyed by flares", NamedTextColor.GRAY));
                    }
                    it.remove();
                    continue;
                } else {
                    Location aim = droneWorldLocation(pilot);
                    if (aim != null && aim.getWorld() != null && aim.getWorld().equals(m.pos.getWorld())) {
                        Vector to = aim.toVector().subtract(m.pos.toVector());
                        double dist = to.length();
                        if (dist < AA_HIT_RADIUS && m.ticks >= AA_MIN_FLIGHT) {
                            detonateAaMissile(m, pilot);
                            it.remove();
                            continue;
                        }
                        if (dist > 1.0e-4) {
                            Vector desired = to.normalize().multiply(speed);
                            m.vel = m.vel.multiply(0.55).add(desired.multiply(0.45));
                            if (m.vel.lengthSquared() > 1.0e-6) {
                                m.vel.normalize().multiply(speed);
                            }
                        }
                    }
                }
            }
            if (shooter != null) {
                session(shooter).ifPresent(s -> {
                    s.guidanceHud = m.profile != null ? m.profile.hudMode() : MunitionProfile.HudMode.AA_LOCK;
                    s.guideState = m.targetPilot != null ? GUIDE_LOCK : GUIDE_TRACK;
                    s.trackQuality = m.targetPilot != null ? 90 : 40;
                });
            }
            m.pos.add(m.vel);
            World w = m.pos.getWorld();
            if (w != null) {
                w.spawnParticle(Particle.FLAME, m.pos, 3, 0.04, 0.04, 0.04, 0.01);
                w.spawnParticle(Particle.END_ROD, m.pos, 1, 0.02, 0.02, 0.02, 0.0);
                if (m.ticks % 4 == 0) {
                    w.playSound(m.pos, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.3f, 1.85f);
                }
            }
        }
    }

    private void detonateAaMissile(AaMissile m, Player targetPilot) {
        Location at = m.pos.clone();
        Player shooter = Bukkit.getPlayer(m.shooter);
        if (targetPilot != null && isPiloting(targetPilot)) {
            absorbBulletHit(targetPilot, shooter, at, m.gun, m.round);
        }
        MunitionProfile profile = m.profile != null ? m.profile : MunitionProfile.ROCKET_AA;
        if (plugin.strikeEffects() != null) {
            plugin.strikeEffects().detonate(at, shooter, m.gun, m.round, profile);
        } else {
            World w = at.getWorld();
            if (w != null) {
                w.spawnParticle(Particle.EXPLOSION, at, 3, 0.35, 0.35, 0.35, 0.02);
                w.spawnParticle(Particle.FLAME, at, 18, 0.5, 0.4, 0.5, 0.04);
                w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 1.15f);
                w.createExplosion(at, 1.6f, false, false, null);
            }
            absorbExplosionSplash(at, profile.effectRadius(), shooter, m.gun, m.round);
        }
    }

    private GunDefinition resolveDroneLaw() {
        Optional<GunDefinition> drone = plugin.registry().get("law_drone");
        if (drone.isPresent()) {
            return drone.get();
        }
        return plugin.registry().get("law").orElse(null);
    }

    public void tick() {
        Iterator<Map.Entry<UUID, DroneSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, DroneSession> entry = it.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            DroneSession session = entry.getValue();
            if (player == null || !player.isOnline()) {
                clearDisplays(session);
                removeBody(session);
                it.remove();
                continue;
            }
            if (player.isDead()) {
                // Don't keep a dead pilot cloaked across respawn — full exit now.
                exit(player, "death");
                continue;
            }
            if (!player.getAllowFlight()) {
                player.setAllowFlight(true);
            }
            if (!player.isFlying()) {
                player.setFlying(true);
            }
            applyDroneFlySpeed(player, session);
            applyPilotInvisibility(player);
            // Keep armor empty every tick — inventory glitches / kits can re-equip mid-flight.
            // NVG/thermal are driven by drone_hud optic on the companion (no helmet — that
            // would float on the invisible pilot). clearPilotArmor must stay helmet-free.
            clearPilotArmor(player);
            player.setCollidable(false);
            if (!player.getScoreboardTags().contains(PILOT_TAG)) {
                player.addScoreboardTag(PILOT_TAG);
            }

            if (session.datalinkFrozen) {
                // Hold position conceptually — no stick flight.
            } else if (session.orbit && !session.manualControl) {
                tickOrbit(player, session);
            } else if (!session.manualControl) {
                tickAutopilot(player, session);
            } else {
                tickManual(player, session);
            }

            if (tickTerrainCollision(player, session)) {
                continue;
            }

            // Prop spins whenever an operator is online in the airframe.
            tickPropellerHazard(player, session);

            if (session.parkedPadId != null && plugin.dronePads() != null) {
                plugin.dronePads().consumeFuelTick(session.parkedPadId);
                double fuelPct = plugin.dronePads().fuelPercent(session.parkedPadId);
                Location airframe = droneWorldLocation(player);
                session.datalinkSignal = datalinkSignal(airframe, session.seatKey, session.padStandLocation);
                session.datalinkFrozen = session.datalinkSignal < 0.15;
                if (session.datalinkFrozen) {
                    if (session.datalinkLostSinceMs <= 0L) {
                        session.datalinkLostSinceMs = System.currentTimeMillis();
                    }
                    player.sendActionBar(Component.text("DATALINK LOST", NamedTextColor.RED));
                    if (session.lastFault != FAULT_DATALINK
                            || System.currentTimeMillis() > session.faultUntilMs) {
                        session.lastFault = FAULT_DATALINK;
                        session.faultUntilMs = System.currentTimeMillis() + 4500L;
                        sendHitCue(player, HIT_EDGE_BOTTOM, FAULT_DATALINK, (byte) 80);
                    }
                    if (System.currentTimeMillis() - session.datalinkLostSinceMs >= 8000L) {
                        announceOutOfRangeCrash(player, airframeLabel(session));
                        beginCrashFromPilot(player);
                        exit(player, "datalink lost");
                        continue;
                    }
                } else {
                    session.datalinkLostSinceMs = 0L;
                    if (session.datalinkSignal < 0.4) {
                        if (session.lastFault != FAULT_DATALINK
                                || System.currentTimeMillis() > session.faultUntilMs) {
                            session.lastFault = FAULT_DATALINK;
                            session.faultUntilMs = System.currentTimeMillis() + 4500L;
                            sendHitCue(player, HIT_EDGE_BOTTOM, FAULT_DATALINK, (byte) 35);
                        }
                    }
                }
                if (fuelPct <= 20.0 && fuelPct > 0.0) {
                    if (session.lastFault != FAULT_FUEL
                            || System.currentTimeMillis() > session.faultUntilMs) {
                        session.lastFault = FAULT_FUEL;
                        session.faultUntilMs = System.currentTimeMillis() + 4500L;
                        sendHitCue(player, HIT_EDGE_BOTTOM, FAULT_FUEL, (byte) 40);
                    }
                }
                if (fuelPct <= 0.0) {
                    announceFuelCrash(player, airframeLabel(session));
                    beginCrashFromPilot(player);
                    exit(player, "fuel starvation");
                    continue;
                }
            }

            tickIrLaser(player, session);
            if (session.fromSeat) {
                tickSeatBody(player, session);
            }
            if (session.ticks % 10 == 0) {
                forceFarEntities(player, session);
            }

            if (session.ticks % 2 == 0) {
                tickSeekerPreview(player, session);
                sendHudTelemetry(player, session);
            }
            if (session.ticks % 10 == 0) {
                // Companion draws the full OSD — keep the action-bar slot empty so it
                // doesn't stack in the center above the hotbar. Vanilla clients still get the strip.
                if (plugin.companions() != null && plugin.companions().hasCompanion(player)) {
                    player.sendActionBar(Component.empty());
                } else {
                    player.sendActionBar(Component.text(formatActionBar(player, session), NamedTextColor.GREEN));
                }
            }
            session.ticks++;
        }
        tickOrphans();
        // Engine hum is client-side (companion BigDroneEngineSound) — continuous loop, 500-block range.
        tickCrashes();
        if (plugin.dronePads() != null && plugin.getServer().getCurrentTick() % 40 == 0) {
            plugin.dronePads().tickInteractEntities();
        }
        tickAaMissiles();
        tickR9xMissiles();
        tickGuidedStrikes();

        // Remotes cannot see scoreboard tags — push airframe list every other tick.
        boolean hasParked = plugin.dronePads() != null && plugin.dronePads().hasPads();
        boolean editing = plugin.droneMeshPose() != null && plugin.droneMeshPose().previewForBroadcast() != null;
        boolean empty = sessions.isEmpty() && crashes.isEmpty() && orphans.isEmpty() && !hasParked && !editing;
        if (empty) {
            if (!lastVisWasEmpty) {
                broadcastDroneVis();
                lastVisWasEmpty = true;
            }
        } else if (!sessions.isEmpty() || !crashes.isEmpty() || !orphans.isEmpty() || editing) {
            if (plugin.getServer().getCurrentTick() % 2 == 0) {
                lastVisWasEmpty = false;
                broadcastDroneVis();
            }
        } else if (hasParked && plugin.getServer().getCurrentTick() % 20 == 0) {
            lastVisWasEmpty = false;
            broadcastDroneVis();
        }
    }

    /** Push active BigDrone poses to one companion (on hello). */
    public void syncViewer(Player viewer) {
        if (viewer == null || plugin.companions() == null || !plugin.companions().hasCompanion(viewer)) {
            return;
        }
        byte[] payload = encodeDroneVis();
        if (payload != null) {
            viewer.sendPluginMessage(plugin, CHANNEL_DRONE_VIS, payload);
        }
    }

    /** Broadcast every active airframe (or empty clear) to all companions. */
    public void broadcastDroneVis() {
        if (plugin.companions() == null) {
            return;
        }
        byte[] payload = encodeDroneVis();
        if (payload == null) {
            return;
        }
        boolean hasParked = plugin.dronePads() != null && plugin.dronePads().hasPads();
        boolean editing = plugin.droneMeshPose() != null && plugin.droneMeshPose().previewForBroadcast() != null;
        lastVisWasEmpty = sessions.isEmpty() && crashes.isEmpty() && orphans.isEmpty() && !hasParked && !editing;
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (plugin.companions().hasCompanion(viewer)) {
                viewer.sendPluginMessage(plugin, CHANNEL_DRONE_VIS, payload);
            }
        }
    }

    /** Immediate vis push (mesh-pose editor / pad changes). */
    public void forceVisBroadcast() {
        broadcastDroneVis();
    }

    private byte[] encodeDroneVis() {
        try {
            record VisRow(UUID id, double x, double y, double z, float yaw, float pitch, boolean spin,
                          float scale, int typeOrdinal, int rockets, float airspeed, boolean grounded) {
            }
            List<VisRow> rows = new ArrayList<>();
            for (Player pilot : onlinePilots()) {
                if (rows.size() >= 32) {
                    break;
                }
                DroneSession session = sessions.get(pilot.getUniqueId());
                Location loc = pilot.getLocation();
                BigDroneType type = session != null && session.type != null ? session.type : BigDroneType.MQ9;
                float scale = type.meshScale();
                int rockets = session != null ? Math.max(0, session.ammo) : 0;
                boolean grounded = session == null || !isAirframeAirborne(pilot, session);
                float airspeed = 0f;
                if (session != null) {
                    if (session.manualControl) {
                        airspeed = (float) Math.max(0.0, session.airspeed);
                    } else {
                        // Orbit / loiter keep a steady cruise — session.airspeed may be stale.
                        airspeed = (float) (cruiseSpeed(session) * Math.max(0.25, session.orbitSpeed));
                    }
                }
                rows.add(new VisRow(
                        pilot.getUniqueId(),
                        loc.getX(), loc.getY(), loc.getZ(),
                        session != null ? bodyYawDegrees(session, pilot) : loc.getYaw(),
                        0f,
                        true,
                        scale,
                        type.ordinal(),
                        rockets,
                        airspeed,
                        grounded));
            }
            for (CrashWreck wreck : crashes.values()) {
                if (rows.size() >= 32) {
                    break;
                }
                BigDroneType type = wreck.airframeName != null
                        ? BigDroneType.fromId(wreck.airframeName) : BigDroneType.MQ9;
                rows.add(new VisRow(wreck.id, wreck.pos.getX(), wreck.pos.getY(), wreck.pos.getZ(),
                        wreck.yaw, wreck.pitch, false, type.meshScale(), type.ordinal(), 0,
                        0.55f, false));
            }
            for (OrphanFlight orphan : orphans.values()) {
                if (rows.size() >= 32) {
                    break;
                }
                BigDroneType type = BigDroneType.MQ9;
                int rockets = 0;
                if (plugin.dronePads() != null) {
                    type = plugin.dronePads().typeOf(orphan.padId);
                    rockets = plugin.dronePads().rocketCount(orphan.padId);
                }
                float ospd = (float) Math.max(0.0, orphan.airspeed);
                rows.add(new VisRow(orphan.padId, orphan.pos.getX(), orphan.pos.getY(), orphan.pos.getZ(),
                        orphan.yaw, 0f, true, type.meshScale(), type.ordinal(), rockets,
                        ospd, false));
            }
            if (plugin.dronePads() != null) {
                for (DronePadService.ParkedPad pad : plugin.dronePads().parkedForVis()) {
                    if (rows.size() >= 32) {
                        break;
                    }
                    Location loc = plugin.dronePads().airframeLocation(pad);
                    if (loc == null) {
                        continue;
                    }
                    BigDroneType type = plugin.dronePads().typeOf(pad);
                    // Parked on the pad with no operator — prop idle / on deck.
                    rows.add(new VisRow(pad.id, loc.getX(), loc.getY(), loc.getZ(), pad.yaw, 0f, false,
                            type.meshScale(), type.ordinal(), plugin.dronePads().rocketCount(pad.id),
                            0f, true));
                }
            }
            if (plugin.droneMeshPose() != null && rows.size() < 32) {
                DroneMeshPoseService.PreviewAirframe prev = plugin.droneMeshPose().previewForBroadcast();
                if (prev != null) {
                    boolean already = false;
                    for (VisRow r : rows) {
                        if (r.id.equals(prev.id())
                                || (Math.abs(r.x - prev.x()) < 0.2 && Math.abs(r.z - prev.z()) < 0.2
                                && r.typeOrdinal == prev.type().ordinal())) {
                            already = true;
                            break;
                        }
                    }
                    if (!already) {
                        // Full racks while posing so hardpoints are visible for framing.
                        int previewRockets = Math.max(0, prev.type().missileSlots());
                        rows.add(new VisRow(prev.id(), prev.x(), prev.y(), prev.z(), prev.yaw(), 0f, false,
                                prev.type().meshScale(), prev.type().ordinal(), previewRockets,
                                0f, true));
                    }
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(7); // protocol — + airspeed + grounded
            out.writeByte(rows.size());
            for (VisRow row : rows) {
                out.writeLong(row.id.getMostSignificantBits());
                out.writeLong(row.id.getLeastSignificantBits());
                out.writeDouble(row.x);
                out.writeDouble(row.y);
                out.writeDouble(row.z);
                out.writeFloat(row.yaw);
                out.writeFloat(row.pitch);
                out.writeByte(row.spin ? 1 : 0);
                out.writeFloat(row.scale);
                out.writeByte(row.typeOrdinal & 0xFF);
                out.writeByte(Math.max(0, Math.min(255, row.rockets)));
                out.writeFloat(row.airspeed);
                out.writeByte(row.grounded ? 1 : 0);
            }
            return bytes.toByteArray();
        } catch (IOException ex) {
            return null;
        }
    }

    private String formatActionBar(Player player, DroneSession session) {
        Location loc = player.getLocation();
        Vector vel = player.getVelocity();
        double gs = Math.hypot(vel.getX(), vel.getZ()) * 20.0;
        double ias = gs * 1.05;
        int hdg = Math.floorMod(Math.round(loc.getYaw()), 360);
        int msl = (int) Math.round(loc.getY());
        int agl = estimateAgl(player);
        String mode = flightModeLabel(session);
        String sensor = switch (session.optic) {
            case NORMAL -> "EO";
            case NVG -> "NV";
            case THERMAL -> "IR";
        };
        boolean laserArm = plugin.items().droneControlId(player.getInventory().getItemInMainHand())
                .filter("fire"::equals).isPresent();
        int fuelPct = batteryPercent(player, session);
        BigDroneType type = session.type != null ? session.type : BigDroneType.MQ9;
        double gal = fuelPct / 100.0 * type.fuelGal();
        StringBuilder sb = new StringBuilder(160);
        sb.append(String.format(java.util.Locale.ROOT,
                "%s  HDG %03d  IAS %.0f  GS %.0f  ALT %d/%dAGL  Z%d  %s  %s  %s %d  FUEL %.0f/%d GAL  LSR %s",
                type.displayName(), hdg, ias, gs, msl, agl, session.zoomLevel, mode, sensor,
                type.cargoBay() ? "CARGO" : "LAW",
                session.ammo, gal, type.fuelGal(),
                laserArm ? "ARM" : "SAFE"));
        if (session.orbit && session.orbitRadius > 0) {
            sb.append(String.format(java.util.Locale.ROOT, "  ORB %.0fm", session.orbitRadius));
        }
        if (session.orbitTarget != null) {
            Location t = session.orbitTarget;
            double rng = loc.distance(t);
            sb.append(String.format(java.util.Locale.ROOT, "  TGT %.0f %.0f %.0f  RNG %.0fm",
                    t.getX(), t.getY(), t.getZ(), rng));
        }
        sb.append(String.format(java.util.Locale.ROOT, "  GPS %.0f/%.0f", loc.getX(), loc.getZ()));
        return sb.toString();
    }

    private static String flightModeLabel(DroneSession session) {
        if (session.flightPhase == FlightPhase.TAKEOFF) {
            return String.format(java.util.Locale.ROOT, "TAKEOFF %.2fx", session.orbitSpeed);
        }
        if (session.flightPhase == FlightPhase.LANDING) {
            return String.format(java.util.Locale.ROOT, "LANDING %.2fx", session.orbitSpeed);
        }
        if (session.flightPhase == FlightPhase.LANDED) {
            return String.format(java.util.Locale.ROOT, "LANDED %.2fx", session.orbitSpeed);
        }
        if (session.manualControl) {
            return String.format(java.util.Locale.ROOT, "MANUAL %.2fx%s",
                    session.orbitSpeed, session.stallWarn ? " STALL" : "");
        }
        if (session.orbit) {
            return String.format(java.util.Locale.ROOT, "ORBIT %.2fx", session.orbitSpeed);
        }
        return String.format(java.util.Locale.ROOT, "LOITER %.2fx", session.orbitSpeed);
    }

    private int batteryPercent(Player player, DroneSession session) {
        if (session.parkedPadId != null && plugin.dronePads() != null) {
            return (int) Math.round(plugin.dronePads().fuelPercent(session.parkedPadId));
        }
        return 100;
    }

    private static int estimateAgl(Player player) {
        Location eye = player.getLocation();
        Block below = player.getWorld().getHighestBlockAt(eye.getBlockX(), eye.getBlockZ());
        int groundY = below.getY();
        if (below.getType().isAir()) {
            groundY = player.getWorld().getMinHeight();
        }
        return Math.max(0, (int) Math.round(eye.getY() - groundY));
    }

    /**
     * Pre-launch seeker preview when LAW is selected and next bay round is AA / AIM-9X / JAGM.
     * Drives lock box + rising tone via trackQuality / guideState.
     */
    private void tickSeekerPreview(Player player, DroneSession session) {
        // Prefer in-flight seeker box already written by tickers.
        boolean inFlight = false;
        for (AaMissile m : aaMissiles) {
            if (m.shooter.equals(player.getUniqueId()) && m.targetPilot != null) {
                Player t = Bukkit.getPlayer(m.targetPilot);
                Location at = t != null ? droneWorldLocation(t) : null;
                if (at != null) {
                    session.seekerX = (float) at.getX();
                    session.seekerY = (float) at.getY();
                    session.seekerZ = (float) at.getZ();
                    session.guidanceHud = m.profile != null ? m.profile.hudMode() : MunitionProfile.HudMode.AA_LOCK;
                    session.guideState = GUIDE_LOCK;
                    session.trackQuality = 95;
                    inFlight = true;
                }
            }
        }
        if (inFlight) {
            return;
        }
        Optional<String> ctrl = plugin.items().droneControlId(player.getInventory().getItemInMainHand());
        if (ctrl.isEmpty() || !"fire".equals(ctrl.get())) {
            // Clear soft preview when not on LAW (keep post-fire HUD briefly via armed cue / missiles).
            if (System.currentTimeMillis() > session.armedCueUntilMs
                    && session.guidanceHud == MunitionProfile.HudMode.NONE) {
                session.seekerX = Float.NaN;
                session.seekerY = Float.NaN;
                session.seekerZ = Float.NaN;
            }
            return;
        }
        String nextId = null;
        if (session.parkedPadId != null && plugin.dronePads() != null) {
            nextId = plugin.dronePads().peekRocket(session.parkedPadId).orElse(null);
        }
        MunitionProfile profile = MunitionProfile.ofRound(nextId).orElse(null);
        if (profile == null) {
            session.seekerX = Float.NaN;
            session.seekerY = Float.NaN;
            session.seekerZ = Float.NaN;
            return;
        }
        session.guidanceHud = profile.hudMode();
        Location sensor = sensorCameraOrigin(player, session);
        if (profile.guidance() == MunitionProfile.Guidance.IR_AA) {
            Vector fwd = sensor.getDirection().normalize();
            UUID tgt = acquireAaTarget(player, sensor, fwd, null);
            if (tgt != null) {
                Player t = Bukkit.getPlayer(tgt);
                Location at = t != null ? droneWorldLocation(t) : null;
                if (at != null && !irSpoofed(at)) {
                    session.seekerX = (float) at.getX();
                    session.seekerY = (float) at.getY();
                    session.seekerZ = (float) at.getZ();
                    session.guideState = GUIDE_LOCK;
                    session.trackQuality = Math.min(100, session.trackQuality + 8);
                    if (session.trackQuality < 40) {
                        session.trackQuality = 40;
                    }
                    return;
                }
            }
            session.seekerX = Float.NaN;
            session.seekerY = Float.NaN;
            session.seekerZ = Float.NaN;
            session.guideState = GUIDE_TRACK;
            session.trackQuality = Math.max(0, session.trackQuality - 6);
            return;
        }
        if (profile.hudMode() == MunitionProfile.HudMode.JAGM
                || profile.hudMode() == MunitionProfile.HudMode.STORM
                || profile.hudMode() == MunitionProfile.HudMode.LASER) {
            Location aim = LaserBeams.aimPoint(player, sensor, profile.range(), true, true);
            if (aim != null) {
                session.seekerX = (float) aim.getX();
                session.seekerY = (float) aim.getY();
                session.seekerZ = (float) aim.getZ();
                session.guideState = profile.hudMode() == MunitionProfile.HudMode.STORM
                        ? GUIDE_SAL : GUIDE_LASER;
                session.trackQuality = 85;
            } else {
                session.seekerX = Float.NaN;
                session.seekerY = Float.NaN;
                session.seekerZ = Float.NaN;
                session.guideState = GUIDE_LOST;
                session.trackQuality = Math.max(0, session.trackQuality - 5);
            }
        }
    }

    private void sendHudTelemetry(Player player, DroneSession session) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1); // protocol
            byte mode;
            if (session.manualControl) {
                mode = MODE_MANUAL;
            } else if (session.orbit) {
                mode = MODE_ORBIT;
            } else {
                mode = MODE_LOITER;
            }
            out.writeByte(mode);
            byte optic = switch (session.optic) {
                case NORMAL -> 0;
                case NVG -> 1;
                case THERMAL -> 2;
            };
            out.writeByte(optic);
            byte flags = FLAG_RECORDING;
            boolean hasTarget = session.orbitTarget != null && session.orbitTarget.getWorld() != null;
            if (hasTarget) {
                flags |= FLAG_HAS_TARGET;
            }
            if (plugin.items().droneControlId(player.getInventory().getItemInMainHand())
                    .filter("fire"::equals).isPresent()) {
                flags |= FLAG_LASER_ARM;
            }
            if (session.irLaser) {
                flags |= FLAG_IR_ON;
            }
            if (System.currentTimeMillis() < session.missileWarnUntilMs) {
                flags |= FLAG_MISSILE_WARN;
            }
            if (System.currentTimeMillis() < session.lockWarnUntilMs) {
                flags |= FLAG_LOCK_WARN;
                if (session.lockHard) {
                    flags |= FLAG_LOCK_HARD;
                }
            }
            if (session.flareCharges > 0) {
                flags |= FLAG_FLARES_READY;
            }
            out.writeByte(flags);
            out.writeByte(Math.max(0, Math.min(255, session.ammo)));
            BigDroneType type = session.type != null ? session.type : BigDroneType.MQ9;
            int maxZ = type.maxZoom();
            out.writeByte(Math.max(0, Math.min(maxZ, session.zoomLevel)));
            out.writeByte(batteryPercent(player, session));
            out.writeFloat((float) (session.orbitRadius > 0 ? session.orbitRadius : 0.0));
            // Effective cruise multiplier includes airframe speed rating.
            out.writeFloat(session.orbitSpeed * type.speedMult());
            if (hasTarget) {
                Location t = session.orbitTarget;
                out.writeFloat((float) t.getX());
                out.writeFloat((float) t.getY());
                out.writeFloat((float) t.getZ());
            } else {
                out.writeFloat(Float.NaN);
                out.writeFloat(Float.NaN);
                out.writeFloat(Float.NaN);
            }
            // Wind from weather (m/s-ish) + heading degrees.
            World world = player.getWorld();
            float windSpd = world.hasStorm() ? (world.isThundering() ? 14f : 7f) : 2.5f;
            float windHdg = (float) Math.floorMod(
                    (int) ((world.getSeed() ^ world.getFullTime() / 200) % 360), 360);
            out.writeFloat(windSpd);
            out.writeFloat(windHdg);
            // Airframe heading = travel direction (cruiseDir), not camera look / orbit aim
            out.writeFloat(bodyYawDegrees(session, player));
            // Protocol trailing caps (optional on older clients): water / stealth / wide / small + tank gal
            byte caps = 0;
            if (type.waterVision()) {
                caps |= 1;
            }
            if (type.stealth()) {
                caps |= 2;
            }
            if (type.wideArea()) {
                caps |= 4;
            }
            if (type.meshScale() < 0.85f) {
                caps |= 8;
            }
            out.writeByte(caps);
            out.writeShort(Math.max(0, Math.min(32767, type.fuelGal())));
            out.writeByte(Math.max(0, Math.min(255, maxZ)));
            // Protocol trailing: guidance HUD mode + seeker state + track quality (0–100)
            byte hudMode = switch (session.guidanceHud != null ? session.guidanceHud : MunitionProfile.HudMode.NONE) {
                case LASER -> 1;
                case AA_LOCK -> 2;
                case JAGM -> 3;
                case STORM -> 4;
                default -> 0;
            };
            out.writeByte(hudMode);
            out.writeByte(session.guideState);
            out.writeByte(Math.max(0, Math.min(100, session.trackQuality)));
            // Next bay round + orbit lock / armed / seeker box
            String nextId = "";
            String nextName = "";
            String nextRole = "";
            if (session.parkedPadId != null && plugin.dronePads() != null) {
                Optional<String> peek = plugin.dronePads().peekRocket(session.parkedPadId);
                if (peek.isPresent()) {
                    nextId = peek.get();
                    nextName = plugin.rounds().get(nextId)
                            .map(r -> PlainTextComponentSerializer.plainText().serialize(
                                    ItemFactory.colorize(r.displayName())))
                            .orElse(nextId);
                    if (nextName.length() > 40) {
                        nextName = nextName.substring(0, 40);
                    }
                    nextRole = plugin.rounds().get(nextId).map(RoundBlurbs::describe).orElse("");
                    if (nextRole.length() > 56) {
                        nextRole = nextRole.substring(0, 56);
                    }
                }
            }
            writeHudString(out, nextName, 40);
            writeHudString(out, nextRole, 56);
            byte extra = 0;
            if (session.orbitLocked) {
                extra |= 1;
            }
            if (System.currentTimeMillis() < session.armedCueUntilMs) {
                extra |= 2;
            }
            boolean seeker = Float.isFinite(session.seekerX) && Float.isFinite(session.seekerY)
                    && Float.isFinite(session.seekerZ);
            if (seeker) {
                extra |= 4;
            }
            // HUD phase bits: TAKEOFF=8, LANDING=16, LANDED=8|16 (sentinel — bit 64 is cam-edit).
            if (session.flightPhase == FlightPhase.LANDED) {
                extra |= 8 | 16;
            } else if (session.flightPhase == FlightPhase.TAKEOFF) {
                extra |= 8;
            } else if (session.flightPhase == FlightPhase.LANDING) {
                extra |= 16;
            }
            if (session.stallWarn) {
                extra |= 32;
            }
            out.writeByte(extra);
            if (seeker) {
                out.writeFloat(session.seekerX);
                out.writeFloat(session.seekerY);
                out.writeFloat(session.seekerZ);
            }
            player.sendPluginMessage(plugin, CHANNEL_HUD, bytes.toByteArray());
        } catch (IOException ignored) {
        }
    }

    /** Length-prefixed UTF-8 (byte length) — matches companion {@code readHudString}. */
    private static void writeHudString(DataOutputStream out, String raw, int maxChars) throws IOException {
        String s = raw == null ? "" : raw;
        if (s.length() > maxChars) {
            s = s.substring(0, maxChars);
        }
        byte[] utf = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = Math.min(255, utf.length);
        out.writeByte(n);
        out.write(utf, 0, n);
    }

    /** Yaw the MQ-9 should face: horizontal cruise / velocity, else last look. */
    private static float bodyYawDegrees(DroneSession session, Player player) {
        Vector dir = session.cruiseDir;
        if (dir != null) {
            double x = dir.getX();
            double z = dir.getZ();
            if (x * x + z * z > 1.0e-6) {
                return (float) Math.toDegrees(Math.atan2(-x, z));
            }
        }
        Vector vel = player.getVelocity();
        double vx = vel.getX();
        double vz = vel.getZ();
        if (vx * vx + vz * vz > 1.0e-4) {
            return (float) Math.toDegrees(Math.atan2(-vx, vz));
        }
        return player.getLocation().getYaw();
    }

    private void applyDroneFlySpeed(Player player, DroneSession session) {
        // Fixed-wing manual: kill creative-fly strafe; we drive velocity ourselves.
        if (session.manualControl) {
            if (Math.abs(player.getFlySpeed() - FW_FLY_SPEED) > 0.001f) {
                player.setFlySpeed(FW_FLY_SPEED);
            }
            return;
        }
        // Autopilot / orbit: mild fly speed (glidePilot owns motion).
        float air = session.type != null ? session.type.speedMult() : 1f;
        float fly = Math.max(0.05f, Math.min(1.0f, 0.08f * session.orbitSpeed * air));
        if (Math.abs(player.getFlySpeed() - fly) > 0.001f) {
            player.setFlySpeed(fly);
        }
    }

    private static double cruiseSpeed(DroneSession session) {
        float air = session != null && session.type != null ? session.type.speedMult() : 1f;
        return CRUISE_SPEED * air;
    }

    /** Enter from pad: stick-controlled ground roll → rotate → climb. */
    private void beginTakeoff(Player player, DroneSession session) {
        session.flightPhase = FlightPhase.TAKEOFF;
        session.takeoffTicks = 0;
        session.landingTicks = 0;
        session.manualControl = true;
        session.orbit = false;
        session.orbitLocked = false;
        session.stallWarn = false;
        session.airspeed = 0.0;
        session.throttle = 0.0;
        ensureHorizontalCruise(player, session);
        session.headingDeg = (float) Math.toDegrees(
                Math.atan2(-session.cruiseDir.getX(), session.cruiseDir.getZ()));
        applyDroneFlySpeed(player, session);
        player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.45f, 0.55f);
        player.sendActionBar(Component.text(
                "TAKEOFF — HOLD SHORT · W throttle · rotate ~"
                        + (int) (FW_ROTATE_SPEED_FRAC * 100) + "% IAS",
                NamedTextColor.GOLD));
    }

    private void beginManualFixedWing(Player player, DroneSession session) {
        session.manualControl = true;
        if (session.flightPhase != FlightPhase.TAKEOFF
                && session.flightPhase != FlightPhase.LANDING
                && session.flightPhase != FlightPhase.LANDED) {
            session.flightPhase = FlightPhase.CRUISE;
        }
        session.orbit = false;
        session.orbitLocked = false;
        session.orbitCenter = null;
        session.orbitTarget = null;
        session.orbitTargetBlock = null;
        session.orbitRadius = 0;
        session.orbitLookYaw = Float.NaN;
        session.orbitLookPitch = Float.NaN;
        clearGimbalState(session);
        ensureHorizontalCruise(player, session);
        session.headingDeg = (float) Math.toDegrees(
                Math.atan2(-session.cruiseDir.getX(), session.cruiseDir.getZ()));
        double cruise = cruiseSpeed(session) * session.orbitSpeed;
        boolean grounded = !isAirframeAirborne(player, session);
        if (grounded) {
            // Keep whatever roll speed we have; do not force cruise IAS on the runway.
            session.throttle = Math.max(0.0, Math.min(1.0, session.airspeed / Math.max(0.05, cruise)));
        } else if (session.airspeed < cruise * FW_MIN_SPEED_FRAC) {
            session.airspeed = cruise * 0.85;
            session.throttle = Math.max(0.15, Math.min(1.0,
                    (session.airspeed - cruise * FW_MIN_SPEED_FRAC)
                            / Math.max(0.05, cruise * (FW_MAX_SPEED_FRAC - FW_MIN_SPEED_FRAC))));
        } else {
            session.throttle = Math.max(0.15, Math.min(1.0,
                    (session.airspeed - cruise * FW_MIN_SPEED_FRAC)
                            / Math.max(0.05, cruise * (FW_MAX_SPEED_FRAC - FW_MIN_SPEED_FRAC))));
        }
        applyDroneFlySpeed(player, session);
    }

    private Location homePadApproachPoint(DroneSession session) {
        if (session == null || session.parkedPadId == null || plugin.dronePads() == null) {
            return null;
        }
        return plugin.dronePads().padById(session.parkedPadId)
                .map(pad -> {
                    Location air = plugin.dronePads().airframeLocation(pad);
                    return air != null ? air.clone().add(0, 1.1, 0) : null;
                })
                .orElse(null);
    }

    private static double horizDistance(Location a, Location b) {
        if (a == null || b == null) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.hypot(dx, dz);
    }

    /** Ray down for ground clearance; large value if nothing nearby. */
    private static double groundClearance(Player player) {
        Location air = player.getLocation();
        World world = air.getWorld();
        if (world == null) {
            return 999.0;
        }
        RayTraceResult hit = world.rayTraceBlocks(
                air.clone().add(0, 0.2, 0), new Vector(0, -1, 0), 64.0,
                FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitPosition() == null) {
            return 999.0;
        }
        return air.getY() - hit.getHitPosition().getY();
    }

    /**
     * Fixed-wing manual: W/S throttle, A/D bank (no strafe), look-pitch / jump / sneak climb.
     * Ground roll until rotate IAS; touchdown clamps to terrain. Below min airspeed aloft → stall mush.
     */
    private void tickManual(Player player, DroneSession session) {
        applyDroneFlySpeed(player, session);
        org.bukkit.Input in = player.getCurrentInput();
        double cruise = cruiseSpeed(session) * session.orbitSpeed;
        double maxSpd = cruise * FW_MAX_SPEED_FRAC;
        double rotateSpd = cruise * FW_ROTATE_SPEED_FRAC;
        double clearance = groundClearance(player);
        boolean grounded = clearance <= FW_GROUND_CLEARANCE;

        if (in != null) {
            if (in.isForward()) {
                session.throttle = Math.min(1.0, session.throttle + 0.045);
            } else if (in.isBackward()) {
                session.throttle = Math.max(0.0, session.throttle - 0.045);
            }
            double bank = FW_BANK_RATE_DEG * (0.45 + 0.55 * Math.max(0.25, session.throttle));
            if (grounded) {
                // Taxi / roll: slower nose-wheel steering at low speed.
                bank *= 0.35 + 0.65 * Math.min(1.0, session.airspeed / Math.max(0.15, rotateSpd));
            }
            if (in.isLeft()) {
                session.headingDeg -= (float) bank;
            }
            if (in.isRight()) {
                session.headingDeg += (float) bank;
            }
        }
        session.headingDeg = wrapYaw(session.headingDeg);

        double minSpd = grounded ? 0.0 : cruise * FW_MIN_SPEED_FRAC;
        double targetSpd = grounded
                ? maxSpd * session.throttle
                : minSpd + (maxSpd - minSpd) * session.throttle;
        double accel = grounded ? FW_ACCEL * 1.15 : FW_ACCEL;
        if (session.airspeed < targetSpd) {
            session.airspeed = Math.min(targetSpd, session.airspeed + accel);
        } else {
            session.airspeed = Math.max(targetSpd, session.airspeed - accel * (grounded ? 1.1 : 0.85));
        }

        float pitch = player.getLocation().getPitch();
        double climbCmd = 0.0;
        if (pitch < -6f) {
            climbCmd = (-pitch / 48.0) * FW_PITCH_RATE * (session.airspeed / Math.max(0.2, cruise));
        } else if (pitch > 6f) {
            climbCmd = (-pitch / 58.0) * FW_PITCH_RATE * (session.airspeed / Math.max(0.2, cruise));
        }
        if (in != null) {
            if (in.isJump()) {
                climbCmd += FW_PITCH_RATE * 0.75;
            }
            if (in.isSneak()) {
                climbCmd -= FW_PITCH_RATE * 0.75;
            }
        }

        if (grounded) {
            session.stallWarn = false;
            // Touchdown / taxi: LANDING → LANDED; any cruise touchdown → LANDED.
            // TAKEOFF stays until rotate so the HUD keeps showing TAKEOFF on the roll.
            if (session.flightPhase == FlightPhase.LANDING
                    || session.flightPhase == FlightPhase.CRUISE
                    || session.flightPhase == FlightPhase.LANDED) {
                session.flightPhase = FlightPhase.LANDED;
            }
            if (session.airspeed < rotateSpd) {
                // Hold short / roll — no lift until rotate speed.
                climbCmd = Math.min(0.0, climbCmd);
                if (climbCmd < -0.01 || (in != null && in.isSneak())) {
                    // Allow settling onto the deck; ignore nose-up until rotate.
                    climbCmd = Math.min(climbCmd, 0.0);
                } else {
                    climbCmd = 0.0;
                }
                if (session.flightPhase == FlightPhase.LANDED) {
                    if (session.landingTicks % 40 == 0 && session.airspeed < 0.08) {
                        player.sendActionBar(Component.text(
                                "LANDED — Exit when ready · throttle up to taxi", NamedTextColor.GREEN));
                    }
                    session.landingTicks++;
                } else if (session.takeoffTicks % 25 == 0 && session.throttle > 0.05) {
                    player.sendActionBar(Component.text(String.format(java.util.Locale.ROOT,
                                    "ROLL  IAS %.0f / ROT %.0f  THR %.0f%%",
                                    session.airspeed * 100.0, rotateSpd * 100.0,
                                    session.throttle * 100.0),
                            NamedTextColor.GOLD));
                }
            } else if (climbCmd > 0.01) {
                // Rotate — leave the deck.
                session.flightPhase = FlightPhase.CRUISE;
                if (session.takeoffTicks < 2 || session.takeoffTicks % 40 == 0) {
                    player.sendActionBar(Component.text("ROTATE — climb out", NamedTextColor.GREEN));
                }
            } else {
                // Fast taxi on deck — stay LANDED (not LANDING).
                climbCmd = Math.min(0.0, climbCmd);
                if (session.flightPhase != FlightPhase.TAKEOFF) {
                    session.flightPhase = FlightPhase.LANDED;
                }
                if (session.landingTicks % 30 == 0) {
                    Location pad = homePadApproachPoint(session);
                    if (pad != null && horizDistance(player.getLocation(), pad) <= LANDING_PARK_DIST * 2.5) {
                        player.sendActionBar(Component.text(String.format(java.util.Locale.ROOT,
                                        "LANDED · taxi  %.0fm to pad · Exit when settled",
                                        horizDistance(player.getLocation(), pad)),
                                NamedTextColor.GREEN));
                    }
                }
                session.landingTicks++;
            }
            session.takeoffTicks++;
        } else {
            if (session.flightPhase == FlightPhase.TAKEOFF
                    || session.flightPhase == FlightPhase.LANDED) {
                session.flightPhase = FlightPhase.CRUISE;
            }
            boolean stall = session.airspeed < minSpd * 0.92;
            session.stallWarn = stall;
            if (stall) {
                climbCmd = Math.min(climbCmd, -FW_STALL_SINK);
                session.airspeed = Math.max(session.airspeed * 0.994, minSpd * 0.45);
            }
            // Soft flare cue near the home pad (airborne only → LANDING).
            Location pad = homePadApproachPoint(session);
            if (pad != null && horizDistance(player.getLocation(), pad) <= LANDING_PARK_DIST * 3.0
                    && clearance < 18.0) {
                session.flightPhase = FlightPhase.LANDING;
                if (session.landingTicks % 25 == 0) {
                    player.sendActionBar(Component.text(String.format(java.util.Locale.ROOT,
                                    "APPROACH  %.0fm  AGL %.0f — flare & settle · then Exit",
                                    horizDistance(player.getLocation(), pad), clearance),
                            NamedTextColor.AQUA));
                }
                session.landingTicks++;
            } else if (session.flightPhase == FlightPhase.LANDING && clearance > 24.0) {
                session.flightPhase = FlightPhase.CRUISE;
            }
        }

        Vector dir = yawToForward(session.headingDeg);
        session.cruiseDir = dir.clone();
        Location cur = player.getLocation();
        Location next = cur.clone().add(dir.clone().multiply(session.airspeed));
        double nextY = cur.getY() + climbCmd;
        // Match parked pad feet (airframeLocation ≈ hitY + 0.05). Mesh draws at pilot Y.
        final double deckClear = 0.05;
        if (grounded) {
            double deck = cur.getY() - Math.max(0.0, clearance - deckClear);
            if (climbCmd <= 0.01) {
                nextY = deck;
            } else {
                nextY = Math.max(deck, nextY);
            }
        } else {
            // Touchdown: do not tunnel into terrain; settle onto the deck.
            double wantClear = nextY - (cur.getY() - clearance);
            if (wantClear < deckClear) {
                nextY = cur.getY() - clearance + deckClear;
                if (session.airspeed > rotateSpd * 0.85 && climbCmd < -0.02) {
                    // Firm arrival — scrub speed a bit.
                    session.airspeed *= 0.92;
                }
            }
        }
        next.setY(nextY);
        if (!grounded || climbCmd > 0.01) {
            liftAboveTerrain(next);
        }
        glidePilot(player, next);
        session.cruiseAltitude = next.getY();
        session.lastLoc = player.getLocation().clone();
    }

    /** Autopilot: hold horizontal course + altitude; look never steers. */
    private void tickAutopilot(Player player, DroneSession session) {
        ensureHorizontalCruise(player, session);
        if (Double.isNaN(session.cruiseAltitude)) {
            session.cruiseAltitude = player.getLocation().getY();
        }
        double step = cruiseSpeed(session) * session.orbitSpeed;
        Location cur = player.getLocation();
        double alt = session.cruiseAltitude;
        Location next = cur.clone();
        next.setX(cur.getX() + session.cruiseDir.getX() * step);
        next.setY(alt);
        next.setZ(cur.getZ() + session.cruiseDir.getZ() * step);
        // Glide, not teleport: a teleport would carry the server's stale rotation and
        // jerk the pilot's view every tick while they look around on autopilot.
        glidePilot(player, next);
        session.lastLoc = player.getLocation().clone();
    }

    private void refreshCruiseFromMotion(Player player, DroneSession session) {
        Location cur = player.getLocation();
        Vector fromVel = player.getVelocity().clone();
        fromVel.setY(0);
        if (fromVel.lengthSquared() > 0.0025) {
            session.cruiseDir = fromVel.normalize();
            return;
        }
        if (session.lastLoc != null && session.lastLoc.getWorld() != null
                && session.lastLoc.getWorld().equals(cur.getWorld())) {
            Vector delta = cur.toVector().subtract(session.lastLoc.toVector());
            delta.setY(0);
            if (delta.lengthSquared() > 0.01) {
                session.cruiseDir = delta.normalize();
            }
        }
    }

    /**
     * Free-look orbit. The camera is NEVER touched — the operator owns it at all times.
     * Every tick, whatever the crosshair is on becomes the point the drone circles, so
     * looking somewhere else walks the orbit over to there.
     *
     * <p>The centre is rate-limited rather than snapped: the ray's origin is the moving
     * drone, so the raw hit point jitters and slides on its own. Limiting how fast the
     * centre may travel turns that into a smooth, steerable drift instead of a runaway.
     */
    private void tickOrbit(Player player, DroneSession session) {
        Location cur = player.getLocation();
        float yaw = cur.getYaw();
        float pitch = cur.getPitch();

        // Locked orbit: free look — never retarget POI or gimbal the camera onto the circle.
        if (!session.orbitLocked) {
            // Only YOUR mouse retargets. The drone is the ray's origin, so re-casting while
            // you hold still would sweep the ray across the world and walk the orbit away.
            if (operatorMovedLook(session, yaw, pitch)) {
                session.orbitLookIdle = 0;
                session.orbitLookYaw = yaw;
                session.orbitLookPitch = pitch;
                session.orbitCmdYaw = Float.NaN;
                session.orbitCmdPitch = Float.NaN;
                session.orbitPrevCmdYaw = Float.NaN;
                session.orbitPrevCmdPitch = Float.NaN;
                retargetOrbitFromLook(player, session);
            } else {
                session.orbitLookIdle++;
            }
        } else {
            session.orbitLookIdle = 0;
            session.orbitLookYaw = yaw;
            session.orbitLookPitch = pitch;
        }

        if (session.orbitTarget == null || session.orbitTarget.getWorld() == null) {
            stopOrbit(player);
            return;
        }
        // Aim point and flight centre are the same point — a lagging centre would leave
        // the crosshair pinned somewhere the target no longer is.
        session.orbitCenter = session.orbitTarget.clone();

        double radius = clampRadius(session.orbitRadius > 0 ? session.orbitRadius : ORBIT_MIN_RADIUS, session);
        session.orbitRadius = radius;

        // Advance from where the drone ACTUALLY is rather than from a free-running angle:
        // when the POI jumps, the circle slides under the drone instead of yanking it
        // across to the far side.
        double dxc = cur.getX() - session.orbitCenter.getX();
        double dzc = cur.getZ() - session.orbitCenter.getZ();
        double bearing = (dxc * dxc + dzc * dzc) > 1.0e-4
                ? Math.atan2(dzc, dxc)
                : session.orbitAngle;
        // Constant ground speed regardless of width (0.028 rad/tick at r=30 — the old feel).
        double linear = cruiseSpeed(session) * session.orbitSpeed;
        double omega = Math.min(ORBIT_MAX_ANGLE_STEP, linear / Math.max(radius, 1.0));
        session.orbitAngle = bearing + omega;

        double x = session.orbitCenter.getX() + Math.cos(session.orbitAngle) * radius;
        double z = session.orbitCenter.getZ() + Math.sin(session.orbitAngle) * radius;

        // Cap per-tick travel so a distant retarget FLIES to the new circle instead of
        // teleporting there. Never throttles the orbit itself (radius * omega is exempt).
        double sx = (x - cur.getX()) * ORBIT_POS_LERP;
        double sz = (z - cur.getZ()) * ORBIT_POS_LERP;
        double stepLen = Math.hypot(sx, sz);
        double maxStep = Math.max(radius * omega, ORBIT_TRANSIT_SPEED * session.orbitSpeed);
        if (stepLen > maxStep && stepLen > 1.0e-6) {
            double k = maxStep / stepLen;
            sx *= k;
            sz *= k;
        }

        double wantY = session.orbitCenter.getY()
                + Math.max(ORBIT_MIN_HEIGHT_OVER_POI, session.orbitHeight);
        double sy = (wantY - cur.getY()) * ORBIT_POS_LERP;
        double maxVert = ORBIT_VERT_SPEED * session.orbitSpeed;
        sy = Math.max(-maxVert, Math.min(maxVert, sy));

        Location next = new Location(cur.getWorld(), cur.getX() + sx, cur.getY() + sy, cur.getZ() + sz);
        liftAboveTerrain(next);

        if (!session.orbitLocked && session.orbitLookIdle >= ORBIT_GIMBAL_IDLE_TICKS) {
            // Hands off the mouse: hold the crosshair on the target. Tracking is exact —
            // the per-tick cap only bites while catching up after a retarget, so once
            // locked the aim error goes to zero and the crosshair stops sliding.
            float newYaw = slew(yaw, yawDelta(yaw, aimYaw(next, session.orbitCenter)));
            float newPitch = slew(pitch, aimPitch(next, session.orbitCenter) - pitch);
            session.orbitPrevCmdYaw = Float.isNaN(session.orbitCmdYaw) ? yaw : session.orbitCmdYaw;
            session.orbitPrevCmdPitch = Float.isNaN(session.orbitCmdPitch) ? pitch : session.orbitCmdPitch;
            session.orbitCmdYaw = newYaw;
            session.orbitCmdPitch = newPitch;
            session.orbitLookYaw = newYaw;
            session.orbitLookPitch = newPitch;
            movePilotLooking(player, next, newYaw, newPitch);
        } else {
            // Free look (locked orbit or actively aiming): velocity move, no rotation fight.
            glidePilot(player, next);
        }

        double tx = -Math.sin(session.orbitAngle);
        double tz = Math.cos(session.orbitAngle);
        session.cruiseDir = new Vector(tx, 0, tz).normalize();
        session.cruiseAltitude = next.getY();
        session.lastLoc = player.getLocation().clone();
    }

    /**
     * True only if the OPERATOR moved the mouse. The gimbal moves the camera too and the
     * client echoes that rotation back a tick later, so a reading that matches either of
     * our last two commanded rotations is our own tracking, not input.
     */
    private static boolean operatorMovedLook(DroneSession session, float yaw, float pitch) {
        if (Float.isNaN(session.orbitLookYaw) || Float.isNaN(session.orbitLookPitch)) {
            return true;
        }
        float dYaw = Math.abs(yawDelta(session.orbitLookYaw, yaw));
        float dPitch = Math.abs(pitch - session.orbitLookPitch);
        if (!Float.isNaN(session.orbitPrevCmdYaw)) {
            dYaw = Math.min(dYaw, Math.abs(yawDelta(session.orbitPrevCmdYaw, yaw)));
            dPitch = Math.min(dPitch, Math.abs(pitch - session.orbitPrevCmdPitch));
        }
        return dYaw >= ORBIT_LOOK_DEADZONE_DEG || dPitch >= ORBIT_LOOK_DEADZONE_DEG;
    }

    private static void clearGimbalState(DroneSession session) {
        session.orbitCmdYaw = Float.NaN;
        session.orbitCmdPitch = Float.NaN;
        session.orbitPrevCmdYaw = Float.NaN;
        session.orbitPrevCmdPitch = Float.NaN;
        session.orbitLookIdle = 0;
    }

    /**
     * Close the whole aim error, capped per tick. No fractional easing: a lerp would
     * leave a permanent offset while the drone is turning, and the crosshair would sit
     * beside the target instead of on it.
     */
    private static float slew(float current, float error) {
        float step = error;
        if (step > ORBIT_GIMBAL_MAX_STEP_DEG) {
            step = ORBIT_GIMBAL_MAX_STEP_DEG;
        } else if (step < -ORBIT_GIMBAL_MAX_STEP_DEG) {
            step = -ORBIT_GIMBAL_MAX_STEP_DEG;
        }
        return current + step;
    }

    private static float aimYaw(Location from, Location to) {
        return (float) Math.toDegrees(Math.atan2(-(to.getX() - from.getX()), to.getZ() - from.getZ()));
    }

    private static float aimPitch(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        float p = (float) Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz)));
        return Math.max(-90f, Math.min(90f, p));
    }

    private static float yawDelta(float from, float to) {
        float d = to - from;
        while (d < -180f) {
            d += 360f;
        }
        while (d >= 180f) {
            d -= 360f;
        }
        return d;
    }

    /**
     * Move by velocity instead of teleporting. Sends no rotation at all, so the pilot's
     * mouse is never fought — this is what makes looking around feel smooth. Position is
     * approximate (flight drag eats some of it), but the orbit controller re-derives its
     * bearing from the drone's ACTUAL position every tick, so the error self-corrects.
     */
    private static void glidePilot(Player player, Location next) {
        Location cur = player.getLocation();
        Vector delta = next.toVector().subtract(cur.toVector());
        if (delta.lengthSquared() > GLIDE_MAX_STEP * GLIDE_MAX_STEP) {
            // Too big a jump to hand to the physics engine — snap and accept the stomp.
            movePilot(player, next);
            return;
        }
        player.setVelocity(delta.multiply(GLIDE_DRAG_COMP));
    }

    /** Position + absolute rotation — gimbal ticks only, never while the operator aims. */
    private static void movePilotLooking(Player player, Location next, float yaw, float pitch) {
        Location pos = new Location(player.getWorld(), next.getX(), next.getY(), next.getZ(), yaw, pitch);
        player.teleport(pos, PlayerTeleportEvent.TeleportCause.PLUGIN);
        clearVelocityIfNeeded(player);
    }

    /** Slide POI to whatever solid block is under the crosshair. Sky = keep last. */
    private void retargetOrbitFromLook(Player player, DroneSession session) {
        Block looked = raycastSolidBlock(player);
        if (looked == null) {
            return;
        }
        Location blockLoc = looked.getLocation();
        if (session.orbitTargetBlock != null && sameBlock(session.orbitTargetBlock, blockLoc)) {
            return;
        }
        session.orbitTargetBlock = blockLoc;
        session.orbitTarget = blockCenter(looked);
    }

    /**
     * Per-tick terrain clearance only. Deliberately does NOT write back to orbitHeight
     * or orbitRadius — doing so ratchets the drone permanently higher/wider every time it
     * clips a hill, and it never comes back down.
     */
    private static void liftAboveTerrain(Location next) {
        if (next.getWorld() == null) {
            return;
        }
        for (int i = 0; i < ORBIT_TERRAIN_MAX_LIFT; i++) {
            Block feet = next.getBlock();
            Block head = next.clone().add(0, 1, 0).getBlock();
            if (!feet.getType().isSolid() && !head.getType().isSolid()) {
                return;
            }
            next.add(0, 1, 0);
        }
    }

    /**
     * Position-only move: carries the pilot's CURRENT yaw/pitch through so the teleport
     * leaves the camera where they put it.
     *
     * <p>Do not "optimise" this back to {@code TeleportFlag.Relative.YAW/PITCH} with a 0
     * rotation. Those flags are deprecated for removal and do not preserve rotation here —
     * the literal 0/0 lands as absolute and locks the pilot due south at the horizon.
     */
    private static void movePilot(Player player, Location next) {
        Location cur = player.getLocation();
        Location pos = new Location(player.getWorld(), next.getX(), next.getY(), next.getZ(),
                cur.getYaw(), cur.getPitch());
        player.teleport(pos, PlayerTeleportEvent.TeleportCause.PLUGIN);
        clearVelocityIfNeeded(player);
    }

    private static void clearVelocityIfNeeded(Player player) {
        Vector v = player.getVelocity();
        if (v.lengthSquared() > 0.01) {
            player.setVelocity(new Vector(0, 0, 0));
        }
    }

    private static void applyPilotInvisibility(Player player) {
        player.setInvisible(true);
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY, 40, 0, false, false, false));
    }

    /** Strip BigDrone cloak (entity flag + potion). Safe to call anytime. */
    public void clearPilotInvisibility(Player player) {
        if (player == null) {
            return;
        }
        forceVisible(player);
        // Potion / teleport races can re-stick invis for a few ticks — hammer it.
        UUID id = player.getUniqueId();
        for (long delay : new long[]{1L, 5L, 20L, 40L}) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                Player p = plugin.getServer().getPlayer(id);
                if (p == null || !p.isOnline() || isPiloting(p)) {
                    return;
                }
                if (plugin.droneMeshPose() != null && plugin.droneMeshPose().isCameraPreviewMounted(p)) {
                    return;
                }
                forceVisible(p);
            }, delay);
        }
    }

    private static void forceVisible(Player player) {
        player.setInvisible(false);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.removeScoreboardTag(PILOT_TAG);
    }

    /** Fix leftover cloak after death / bad exits for every online player. */
    public void clearOrphanPilotCloaks() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (isPiloting(player)) {
                continue;
            }
            // Operator-camera editor mounts a live sensor preview (not a flight session).
            if (plugin.droneMeshPose() != null && plugin.droneMeshPose().isCameraPreviewMounted(player)) {
                continue;
            }
            clearPilotInvisibility(player);
            player.removeScoreboardTag(PILOT_TAG);
            stripOrphanDroneControls(player);
        }
        purgeOrphanSeatBodies();
    }

    /** HUD extraFlags bit: operator-camera editor is driving live sensor XYZ. */
    public static final byte HUD_EXTRA_CAM_EDIT = 64;
    /** HUD extraFlags bit: wipe companion OSD (sent on exit). */
    public static final byte HUD_EXTRA_CLEAR = (byte) 128;

    /** Tell the Fabric companion to drop OSD immediately (beats late telem). */
    public void sendHudClear(Player player) {
        sendHudClear(player, null);
    }

    /**
     * Drop OSD and optionally show a 1s static disconnect banner
     * ({@code DISCONNECTED}, {@code RADIOLINK LOST}, {@code SAT LINK LOST}, …).
     */
    public void sendHudClear(Player player, String banner) {
        if (player == null) {
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1); // protocol
            out.writeByte(0); // mode
            out.writeByte(0); // optic
            out.writeByte(0); // flags
            out.writeByte(0); // ammo
            out.writeByte(0); // zoom
            out.writeByte(0); // battery
            out.writeFloat(0f);
            out.writeFloat(1f);
            out.writeFloat(Float.NaN);
            out.writeFloat(Float.NaN);
            out.writeFloat(Float.NaN);
            out.writeFloat(0f);
            out.writeFloat(0f);
            out.writeFloat(0f); // bodyYaw
            out.writeByte(0); // caps
            out.writeShort(0); // fuel
            out.writeByte(0); // maxZoom
            out.writeByte(0); // guideHud
            out.writeByte(0); // guideState
            out.writeByte(0); // trackQuality
            writeHudString(out, "", 40);
            writeHudString(out, "", 56);
            out.writeByte(HUD_EXTRA_CLEAR);
            writeHudString(out, banner == null ? "" : banner, 48);
            player.sendPluginMessage(plugin, CHANNEL_HUD, bytes.toByteArray());
        } catch (IOException ignored) {
        }
    }

    /** Center-screen static banner for companion after an operator leave. */
    private String disconnectBanner(DroneSession session, String reason) {
        String r = reason == null ? "" : reason.toLowerCase(java.util.Locale.ROOT);
        boolean sat = session != null && session.seatKey != null
                && plugin.datalink() != null
                && plugin.datalink().seatUsesSatellite(session.seatKey);
        if (r.contains("datalink") || r.contains("radio") || r.contains("sat")) {
            return sat || r.contains("sat") ? "SAT LINK LOST" : "RADIOLINK LOST";
        }
        if (r.contains("shot") || r.contains("javelin") || r.contains("heat")
                || r.contains("crash") || r.contains("fuel") || r.contains("impact")
                || r.contains("pad broken") || r.contains("abandoned — crashing")) {
            return sat ? "SAT LINK LOST" : "RADIOLINK LOST";
        }
        if (r.contains("seat destroyed") || r.equals("death")) {
            return sat ? "SAT LINK LOST" : "RADIOLINK LOST";
        }
        // Clean park / manual disconnect / mid-air abandon / command
        return "DISCONNECTED";
    }

    /**
     * Lightweight drone_hud so the companion stays in pilot-camera mode while
     * {@code /warz dronecam} is open. Trailing camX/Y/Z update every nudge.
     */
    public void sendCameraEditHud(Player player, BigDroneType type, float bodyYaw,
                                  float camX, float camY, float camZ) {
        if (player == null || plugin.companions() == null || !plugin.companions().hasCompanion(player)) {
            return;
        }
        if (type == null) {
            type = BigDroneType.MQ9;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(80);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1); // protocol
            out.writeByte(MODE_LOITER);
            out.writeByte(0); // optic NORMAL
            out.writeByte(FLAG_RECORDING);
            out.writeByte(0); // ammo
            out.writeByte(0); // zoom
            out.writeByte(100); // battery
            out.writeFloat(0f); // orbit radius
            out.writeFloat(type.speedMult());
            out.writeFloat(Float.NaN);
            out.writeFloat(Float.NaN);
            out.writeFloat(Float.NaN);
            out.writeFloat(0f); // wind
            out.writeFloat(0f);
            out.writeFloat(bodyYaw);
            byte caps = 0;
            if (type.waterVision()) {
                caps |= 1;
            }
            if (type.stealth()) {
                caps |= 2;
            }
            if (type.wideArea()) {
                caps |= 4;
            }
            if (type.meshScale() < 0.85f) {
                caps |= 8;
            }
            out.writeByte(caps);
            out.writeShort(Math.max(0, Math.min(32767, type.fuelGal())));
            out.writeByte(Math.max(0, Math.min(255, type.maxZoom())));
            out.writeByte(0); // guide hud
            out.writeByte(0); // guide state
            out.writeByte(0); // track quality
            writeHudString(out, "CAM EDIT", 40);
            writeHudString(out, String.format(java.util.Locale.ROOT,
                    "cam %.2f %.2f %.2f", camX, camY, camZ), 56);
            out.writeByte(HUD_EXTRA_CAM_EDIT); // extra flags — live sensor XYZ follows
            out.writeFloat(camX);
            out.writeFloat(camY);
            out.writeFloat(camZ);
            out.writeFloat(type.meshScale());
            out.flush();
            player.sendPluginMessage(plugin, CHANNEL_HUD, bytes.toByteArray());
        } catch (IOException ignored) {
        }
    }

    /**
     * Remove leftover UAV hotbar controls after unclean restart / disconnect.
     * Safe when not piloting — never strips mid-flight sessions.
     */
    public void stripOrphanDroneControls(Player player) {
        if (player == null || isPiloting(player) || plugin.items() == null) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        boolean changed = false;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (plugin.items().isDroneControl(stack)) {
                inv.setItem(i, null);
                changed = true;
            }
        }
        if (changed) {
            player.updateInventory();
        }
    }

    /** Remove leftover seat mannequins after crash / unclean shutdown. */
    public void purgeOrphanSeatBodies() {
        NamespacedKey bodyKey = WarzKeys.of("drone_seat_body");
        Set<UUID> liveBodies = new HashSet<>();
        for (DroneSession session : sessions.values()) {
            if (session.bodyId != null) {
                liveBodies.add(session.bodyId);
            }
        }
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Mannequin m : world.getEntitiesByClass(Mannequin.class)) {
                if (!m.getPersistentDataContainer().has(bodyKey, PersistentDataType.STRING)) {
                    continue;
                }
                if (liveBodies.contains(m.getUniqueId())) {
                    continue;
                }
                m.remove();
                removed++;
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Purged " + removed + " orphan drone-seat mannequin(s).");
        }
    }

    /** Solid block under the crosshair, or null if open sky / no hit within 256. */
    private Block raycastSolidBlock(Player player) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        RayTraceResult blockHit = player.getWorld().rayTraceBlocks(
                eye, dir, 256, FluidCollisionMode.NEVER, true);
        if (blockHit != null && blockHit.getHitBlock() != null
                && !blockHit.getHitBlock().getType().isAir()) {
            return blockHit.getHitBlock();
        }
        RayTraceResult entityHit = player.getWorld().rayTraceEntities(
                eye, dir, 256, 0.35, ent -> ent != player && !(ent instanceof BlockDisplay));
        if (entityHit != null && entityHit.getHitPosition() != null) {
            Block b = entityHit.getHitPosition().toLocation(player.getWorld()).getBlock();
            if (!b.getType().isAir()) {
                return b;
            }
        }
        return null;
    }

    private static Location blockCenter(Block block) {
        return block.getLocation().add(0.5, 0.5, 0.5);
    }

    private void applyOptic(Player player, DroneSession session) {
        // Companion reads optic from drone_hud. Do not equip helmets here — clearPilotArmor
        // strips them every tick, and a pumpkin/FLIR on an invisible pilot floats in mid-air.
        if (player != null) {
            player.getInventory().setHelmet(null);
        }
    }

    private void applyZoom(Player player, DroneSession session) {
        // Client companion owns FOV levels — no potion FOV fighting.
        sendZoomLevel(player, session.zoomLevel);
    }

    private void sendZoomLevel(Player player, int level) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1);
            out.writeByte(Math.max(0, Math.min(MAX_ZOOM, level)));
            player.sendPluginMessage(plugin, CHANNEL_ZOOM, bytes.toByteArray());
        } catch (IOException ignored) {
        }
    }

    /** Operator hotbar slots (0–8). Exit stays last so you don't fat-finger leave. */
    public static final int HOTBAR_FIRE = 0;
    public static final int HOTBAR_ORBIT = 1;
    public static final int HOTBAR_CONTROL = 2;
    public static final int HOTBAR_OPTIC = 3;
    public static final int HOTBAR_SPEED = 4;
    public static final int HOTBAR_IR = 5;
    public static final int HOTBAR_FLARES = 6;
    public static final int HOTBAR_EXIT = 8;

    private void applyHotbarControls(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemFactory items = plugin.items();
        inv.setItem(HOTBAR_FIRE, items.createDroneFireControl());
        inv.setItem(HOTBAR_ORBIT, items.createDroneOrbitControl());
        inv.setItem(HOTBAR_CONTROL, items.createDroneModeControl());
        inv.setItem(HOTBAR_OPTIC, items.createDroneOpticControl());
        inv.setItem(HOTBAR_SPEED, items.createDroneSpeedControl());
        inv.setItem(HOTBAR_IR, items.createDroneIrControl());
        inv.setItem(HOTBAR_FLARES, items.createDroneFlareControl(
                FLARE_CHARGES_MAX, FLARE_CHARGES_MAX, false));
        inv.setItem(7, null);
        inv.setItem(HOTBAR_EXIT, items.createDroneExitControl());
    }

    private void tickIrLaser(Player player, DroneSession session) {
        LaserCompanionBridge bridge = plugin.laserBridge();
        if (bridge == null) {
            return;
        }
        if (!session.irLaser) {
            return;
        }
        Location sensor = sensorCameraOrigin(player, session);
        // Ray from the same point the companion camera looks from → tip under crosshair.
        Location aim = LaserBeams.aimPoint(player, sensor, DRONE_IR_RANGE, true, true);
        // Tiny look-forward nudge only (no lateral/vertical) so the beam isn't clipped.
        Location muzzle = LaserBeams.muzzleOrigin(sensor, 0.0, 0.0, 0.08);
        LaserOptics.BeamPath path = LaserOptics.traceFromTo(muzzle, aim, DRONE_IR_WIDTH, 0.35, true);
        bridge.broadcastBeam(player, path, NvgGear.IR_PHOSPHOR, DRONE_IR_WIDTH, true, true);
    }

    /**
     * Companion first-person camera origin (nose / free-view). Offsets scale with mesh
     * (smaller airframes pull aft). Preserves look yaw/pitch so
     * {@link LaserBeams#aimPoint} matches the on-screen crosshair.
     */
    private Location sensorCameraOrigin(Player player, DroneSession session) {
        Location eye = player.getEyeLocation();
        float bodyYaw = session != null ? bodyYawDegrees(session, player) : eye.getYaw();
        float scale = 1f;
        BigDroneType type = session != null ? session.type : null;
        if (type != null) {
            scale = Math.max(0.35f, Math.min(1.35f, type.meshScale()));
        }
        double forward = SENSOR_FORWARD * scale;
        if (scale < 0.95f) {
            forward -= (0.95f - scale) * SENSOR_SMALL_AFT_PULL;
        }
        forward = Math.max(0.55, Math.min(SENSOR_FORWARD * 1.15, forward));
        double down = SENSOR_DOWN * Math.max(0.5, scale);
        double camX = 0;
        double camY = 0;
        double camZ = 0;
        if (type != null && plugin.droneMeshPose() != null) {
            DroneMeshPose pose = plugin.droneMeshPose().effective(type);
            if (pose != null) {
                camX = pose.camX;
                camY = pose.camY;
                camZ = pose.camZ;
            }
        }
        forward += camZ;
        down -= camY;
        double rad = Math.toRadians(bodyYaw);
        double fx = -Math.sin(rad) * forward;
        double fz = Math.cos(rad) * forward;
        double rx = Math.cos(rad) * camX;
        double rz = Math.sin(rad) * camX;
        Location sensor = eye.clone().add(fx + rx, -down, fz + rz);
        sensor.setYaw(eye.getYaw());
        sensor.setPitch(eye.getPitch());
        return sensor;
    }

    /**
     * Force-track living mobs out past vanilla entity range so zoomed thermal/EO can paint them.
     */
    private void forceFarEntities(Player player, DroneSession session) {
        Set<UUID> keep = new HashSet<>();
        double radius = session.type != null ? session.type.farEntityRadius() : FAR_ENTITY_RADIUS;
        for (Entity ent : player.getNearbyEntities(radius, radius, radius)) {
            if (!(ent instanceof LivingEntity living) || living.equals(player) || !living.isValid() || living.isDead()) {
                continue;
            }
            if (living instanceof ArmorStand || living instanceof BlockDisplay) {
                continue;
            }
            keep.add(living.getUniqueId());
            if (!session.forcedEntities.contains(living.getUniqueId())) {
                try {
                    player.showEntity(plugin, living);
                    session.forcedEntities.add(living.getUniqueId());
                } catch (Throwable ignored) {
                    // Older API / vanished entity
                }
            }
        }
        // Drop out-of-range IDs only — never hideEntity here. Hiding would make those
        // mobs/players stay invisible to the pilot after they leave the force-track bubble
        // (and clearForcedEntities used to compound that on exit).
        session.forcedEntities.retainAll(keep);
    }

    private void clearForcedEntities(Player player, DroneSession session) {
        // Reverse any prior hideEntity mistakes + ensure force-shown entities stay visible.
        for (UUID id : new HashSet<>(session.forcedEntities)) {
            Entity ent = plugin.getServer().getEntity(id);
            if (ent != null) {
                try {
                    player.showEntity(plugin, ent);
                } catch (Throwable ignored) {
                }
            }
        }
        session.forcedEntities.clear();
        // Belt-and-suspenders: nearby players can stick hidden after a bad exit path.
        if (player != null && player.getWorld() != null) {
            for (Player other : player.getWorld().getPlayers()) {
                if (other == null || other.equals(player)) {
                    continue;
                }
                try {
                    player.showEntity(plugin, other);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static ItemStack[] snapshotHotbar(Player player) {
        ItemStack[] hot = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            hot[i] = cloneOrNull(player.getInventory().getItem(i));
        }
        return hot;
    }

    /** Helmet, chest, legs, boots. */
    private static ItemStack[] snapshotArmor(Player player) {
        PlayerInventory inv = player.getInventory();
        return new ItemStack[]{
                cloneOrNull(inv.getHelmet()),
                cloneOrNull(inv.getChestplate()),
                cloneOrNull(inv.getLeggings()),
                cloneOrNull(inv.getBoots())
        };
    }

    private static void clearPilotEquipment(Player player) {
        clearPilotArmor(player);
        player.getInventory().setItemInOffHand(null);
    }

    private static void clearPilotArmor(Player player) {
        PlayerInventory inv = player.getInventory();
        inv.setHelmet(null);
        inv.setChestplate(null);
        inv.setLeggings(null);
        inv.setBoots(null);
    }

    private static void restorePilotEquipment(Player player, DroneSession session) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] armor = session.savedArmor;
        if (armor != null && armor.length >= 4) {
            inv.setHelmet(armor[0]);
            inv.setChestplate(armor[1]);
            inv.setLeggings(armor[2]);
            inv.setBoots(armor[3]);
        }
        inv.setItemInOffHand(session.savedOffhand);
    }

    private static void restoreHotbar(Player player, DroneSession session) {
        if (session.savedHotbar == null) {
            return;
        }
        for (int i = 0; i < 9; i++) {
            player.getInventory().setItem(i, session.savedHotbar[i]);
        }
    }

    private void clearDisplays(DroneSession session) {
        if (session.displays == null) {
            return;
        }
        for (BlockDisplay d : session.displays) {
            if (d != null && !d.isDead()) {
                d.remove();
            }
        }
    }

    private void removeBody(DroneSession session) {
        if (session.bodyId == null) {
            return;
        }
        Entity tracked = Bukkit.getEntity(session.bodyId);
        if (tracked != null) {
            tracked.remove();
            return;
        }
        World world = session.padStandLocation != null ? session.padStandLocation.getWorld() : null;
        if (world == null) {
            return;
        }
        for (Entity ent : world.getEntities()) {
            if (ent.getUniqueId().equals(session.bodyId)) {
                ent.remove();
                return;
            }
        }
    }

    /** Brief seat invuln after the airframe fires — blocks self-LAW splash, not enemy attacks later. */
    private void shieldSeatFromOwnOrdnance(DroneSession session) {
        if (session == null || session.bodyId == null) {
            return;
        }
        Entity ent = Bukkit.getEntity(session.bodyId);
        if (!(ent instanceof Mannequin body) || !body.isValid()) {
            return;
        }
        body.setInvulnerable(true);
        final UUID bodyId = session.bodyId;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Entity again = Bukkit.getEntity(bodyId);
            if (again instanceof Mannequin m && m.isValid()) {
                m.setInvulnerable(false);
            }
        }, 45L);
    }

    /** Keep the seated operator facing the stairs forward; heal so they stay a stable hitbox. */
    private void tickSeatBody(Player pilot, DroneSession session) {
        if (session.bodyId == null) {
            return;
        }
        Entity ent = Bukkit.getEntity(session.bodyId);
        if (!(ent instanceof Mannequin body) || !body.isValid()) {
            return;
        }
        if (body.isDead()) {
            if (System.currentTimeMillis() < session.seatArmedAtMs) {
                respawnSeatBodyAfterDisarm(pilot, session, body);
                return;
            }
            // Chair body destroyed (bomb / lethal hit) — crash airframe and kill the operator.
            if (pilot != null && pilot.isOnline() && !pilot.isDead() && isPiloting(pilot)) {
                killPilotFromSeat(pilot, null);
            }
            return;
        }
        float yaw = Float.isFinite(session.seatYaw) ? session.seatYaw : body.getLocation().getYaw();
        // Lock head + body forward (no look-at nearby players).
        body.setAI(false);
        body.setRotation(yaw, 0f);
        try {
            body.setBodyYaw(yaw);
        } catch (Throwable ignored) {
        }
        if (Mannequin.validPoses().contains(Pose.SITTING) && body.getPose() != Pose.SITTING) {
            body.setPose(Pose.SITTING);
        }
        // Stay on the chair tread
        Location sit = session.padStandLocation;
        if (sit != null && sit.getWorld() != null && body.getWorld().equals(sit.getWorld())) {
            Location cur = body.getLocation();
            if (cur.distanceSquared(sit) > 0.04 || Math.abs(cur.getYaw() - yaw) > 1.5f) {
                Location locked = sit.clone();
                locked.setYaw(yaw);
                locked.setPitch(0f);
                body.teleport(locked);
                body.setRotation(yaw, 0f);
                try {
                    body.setBodyYaw(yaw);
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            double max = body.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                    ? body.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()
                    : 20.0;
            if (body.getHealth() < max) {
                body.setHealth(max);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Mirror seat-body hits onto the invisible pilot. Returns true if this was a seat body.
     */
    public boolean handleSeatBodyDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        Entity victim = event.getEntity();
        if (!(victim instanceof Mannequin body)) {
            return false;
        }
        NamespacedKey bodyKey = WarzKeys.of("drone_seat_body");
        String raw = body.getPersistentDataContainer().get(bodyKey, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        UUID pilotId;
        try {
            pilotId = UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        Player pilot = Bukkit.getPlayer(pilotId);
        if (pilot == null || !pilot.isOnline() || !isPiloting(pilot) || pilot.isDead()) {
            return true; // still a seat body — let vanilla damage/kill the leftover mannequin
        }
        DroneSession session = sessions.get(pilotId);
        if (session != null && System.currentTimeMillis() < session.seatArmedAtMs) {
            event.setCancelled(true);
            healSeatBody(body);
            return true;
        }
        double amount = event.getFinalDamage();
        if (amount <= 0) {
            return true;
        }
        Entity damager = null;
        if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent by) {
            damager = by.getDamager();
            if (damager instanceof org.bukkit.entity.Projectile proj && proj.getShooter() instanceof Entity shooter) {
                damager = shooter;
            }
        }
        SEAT_PROXY_DAMAGE.set(true);
        try {
            // Guns / melee / explosives on the chair all hurt the real operator.
            // Mannequin is healed next tick unless this hit was lethal (death handler crashes drone).
            if (damager instanceof Player shooter) {
                Bullet.applyAttributedDamage(pilot, amount, shooter);
            } else if (damager instanceof LivingEntity livingDamager) {
                pilot.damage(amount, livingDamager);
            } else {
                pilot.damage(amount);
            }
        } catch (Throwable t) {
            try {
                pilot.damage(amount);
            } catch (Throwable ignored) {
            }
        } finally {
            SEAT_PROXY_DAMAGE.set(false);
        }
        return true;
    }

    /** Seat mannequin died (bomb, gun, etc.) — crash the MQ-9 and kill the operator. */
    public boolean handleSeatBodyDeath(Mannequin body, Entity killer) {
        if (body == null) {
            return false;
        }
        NamespacedKey bodyKey = WarzKeys.of("drone_seat_body");
        String raw = body.getPersistentDataContainer().get(bodyKey, PersistentDataType.STRING);
        if (raw == null) {
            return false;
        }
        UUID pilotId;
        try {
            pilotId = UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        Player pilot = Bukkit.getPlayer(pilotId);
        if (pilot == null || !pilot.isOnline()) {
            return true;
        }
        DroneSession session = sessions.get(pilotId);
        if (session != null && System.currentTimeMillis() < session.seatArmedAtMs) {
            respawnSeatBodyAfterDisarm(pilot, session, body);
            return true;
        }
        killPilotFromSeat(pilot, killer);
        return true;
    }

    private void respawnSeatBodyAfterDisarm(Player pilot, DroneSession session, Mannequin dead) {
        Location sit = session.padStandLocation;
        if (sit == null || sit.getWorld() == null) {
            return;
        }
        dead.remove();
        UUID newId = spawnSeatMannequin(pilot, sit,
                session.savedArmor, session.savedOffhand,
                session.savedHotbar != null && session.savedHotbar.length > 0
                        ? session.savedHotbar[session.savedHeldSlot] : null);
        if (newId != null) {
            session.bodyId = newId;
        }
    }

    private static void healSeatBody(Mannequin body) {
        if (body == null || !body.isValid() || body.isDead()) {
            return;
        }
        try {
            double max = body.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                    ? body.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()
                    : 20.0;
            body.setHealth(max);
        } catch (Throwable ignored) {
        }
    }

    private void killPilotFromSeat(Player pilot, Entity killer) {
        if (pilot == null || !pilot.isOnline()) {
            return;
        }
        if (isPiloting(pilot)) {
            DroneSession session = sessions.get(pilot.getUniqueId());
            if (session != null && session.fromSeat && session.parkedPadId != null) {
                if (!orphans.containsKey(session.parkedPadId)) {
                    startOrphanFromPilot(pilot, session, killer);
                }
                exitAirborneForTakeover(pilot, false);
            } else {
                beginCrashFromPilot(pilot);
                exit(pilot, "seat destroyed");
            }
        }
        forceWorldSpawnOnRespawn.add(pilot.getUniqueId());
        if (pilot.isDead()) {
            return;
        }
        // Next tick: real death after gear restore / teleport so respawn is clean.
        final UUID pilotId = pilot.getUniqueId();
        final Entity killerRef = killer;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(pilotId);
            if (p == null || !p.isOnline() || p.isDead()) {
                return;
            }
            clearPilotInvisibility(p);
            p.setNoDamageTicks(0);
            p.setFireTicks(0);
            SEAT_PROXY_DAMAGE.set(true);
            try {
                if (killerRef instanceof LivingEntity living) {
                    p.damage(Math.max(p.getHealth() + 40.0, 1000.0), living);
                }
                if (!p.isDead()) {
                    p.setHealth(0.0);
                }
            } catch (Throwable t) {
                try {
                    if (!p.isDead()) {
                        p.setHealth(0.0);
                    }
                } catch (Throwable ignored) {
                }
            } finally {
                SEAT_PROXY_DAMAGE.set(false);
            }
        });
    }

    private OrphanFlight orphanForSeat(String seatKey) {
        if (seatKey == null || seatKey.isEmpty()) {
            return null;
        }
        for (OrphanFlight o : orphans.values()) {
            if (seatKey.equals(o.seatKey)) {
                return o;
            }
        }
        return null;
    }

    private void startOrphanFromPilot(Player pilot, DroneSession session, Entity killer) {
        OrphanFlight o = newOrphanFlight(pilot, session);
        o.abandoned = false;
        o.ticksLeft = ORPHAN_TICKS;
        String killerName = null;
        if (killer instanceof Player kp) {
            o.killerId = kp.getUniqueId();
            killerName = kp.getName();
        } else if (killer instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof Player sp) {
            o.killerId = sp.getUniqueId();
            killerName = sp.getName();
        }
        orphans.put(o.padId, o);
        // Keep radiolink so the killer can sit the same seat during the orphan window.
        if (plugin.dronePads() != null && o.seatKey != null) {
            plugin.dronePads().ensureSeatLink(o.padId, o.seatKey);
        }
        String air = airframeLabel(session);
        if (killerName != null) {
            broadcastFeed(Component.empty()
                    .append(feedPlayer(o.killerId, killerName))
                    .append(feedLegacy(" &7&okilled "))
                    .append(feedPlayer(o.previousPilotId, o.previousPilotName))
                    .append(feedLegacy(" &7&oin the pilot seat — &6" + air + " &7&ocontrollable")));
        } else {
            broadcastFeed(Component.empty()
                    .append(feedPlayer(o.previousPilotId, o.previousPilotName))
                    .append(feedLegacy(" &7&owas killed in the pilot seat — &6" + air + " &7&ocontrollable")));
        }
        broadcastDroneVis();
    }

    /** Operator Exit while airborne — unmanned AUTOPILOT until fuel starvation or takeover. */
    private void startAbandonedOrphan(Player pilot, DroneSession session) {
        OrphanFlight o = newOrphanFlight(pilot, session);
        o.abandoned = true;
        o.ticksLeft = Integer.MAX_VALUE;
        o.killerId = null;
        double cruise = cruiseSpeed(session) * Math.max(0.35, session.orbitSpeed);
        o.airspeed = session.airspeed > 0.05 ? session.airspeed : cruise;
        if (!Double.isNaN(session.cruiseAltitude)) {
            o.cruiseAltitude = session.cruiseAltitude;
        } else {
            o.cruiseAltitude = o.pos.getY();
        }
        // Keep circling the locked POI when abandoned mid-orbit.
        if (session.orbit && session.orbitCenter != null && session.orbitCenter.getWorld() != null) {
            o.orbit = true;
            o.orbitCenter = session.orbitCenter.clone();
            o.orbitRadius = clampRadius(
                    session.orbitRadius > 0 ? session.orbitRadius : ORBIT_MIN_RADIUS, session);
            o.orbitAngle = session.orbitAngle;
            o.orbitHeight = Math.max(ORBIT_MIN_HEIGHT_OVER_POI, session.orbitHeight);
            o.orbitSpeed = Math.max(0.35f, session.orbitSpeed);
            o.linearSpeed = cruiseSpeed(session) * o.orbitSpeed;
        }
        orphans.put(o.padId, o);
        if (plugin.dronePads() != null && o.seatKey != null) {
            plugin.dronePads().ensureSeatLink(o.padId, o.seatKey);
        }
        broadcastDroneVis();
    }

    private OrphanFlight newOrphanFlight(Player pilot, DroneSession session) {
        OrphanFlight o = new OrphanFlight();
        o.padId = session.parkedPadId;
        o.pos = pilot.getLocation().clone();
        o.vel = pilot.getVelocity().clone();
        if (session.cruiseDir != null && session.cruiseDir.lengthSquared() > 1.0e-6) {
            o.cruiseDir = session.cruiseDir.clone();
            o.cruiseDir.setY(0);
            if (o.cruiseDir.lengthSquared() > 1.0e-6) {
                o.cruiseDir.normalize();
            } else {
                o.cruiseDir = new Vector(0, 0, 1);
            }
        } else {
            o.cruiseDir = o.pos.getDirection().clone();
            o.cruiseDir.setY(0);
            if (o.cruiseDir.lengthSquared() < 1.0e-6) {
                o.cruiseDir = new Vector(0, 0, 1);
            } else {
                o.cruiseDir.normalize();
            }
        }
        o.yaw = bodyYawDegrees(session, pilot);
        o.structureHp = session.structureHp;
        o.seatKey = session.seatKey;
        o.previousPilotName = pilot.getName();
        o.previousPilotId = pilot.getUniqueId();
        o.airspeed = ORPHAN_CRUISE_SPEED;
        o.cruiseAltitude = o.pos.getY();
        o.type = session.type != null ? session.type : typeOfPad(o.padId);
        return o;
    }

    /** Unmanned orbit — same circle math as piloted orbit, no gimbal / look. */
    private void tickOrphanOrbit(OrphanFlight o) {
        Location cur = o.pos;
        Location center = o.orbitCenter;
        if (cur.getWorld() == null || center.getWorld() == null || !cur.getWorld().equals(center.getWorld())) {
            o.orbit = false;
            return;
        }
        double radius = o.orbitRadius > 0 ? o.orbitRadius : ORBIT_MIN_RADIUS;
        if (radius < ORBIT_MIN_RADIUS) {
            radius = ORBIT_MIN_RADIUS;
        }
        double dxc = cur.getX() - center.getX();
        double dzc = cur.getZ() - center.getZ();
        double bearing = (dxc * dxc + dzc * dzc) > 1.0e-4
                ? Math.atan2(dzc, dxc)
                : o.orbitAngle;
        double linear = o.linearSpeed > 0.05 ? o.linearSpeed : ORPHAN_CRUISE_SPEED * o.orbitSpeed;
        double omega = Math.min(ORBIT_MAX_ANGLE_STEP, linear / Math.max(radius, 1.0));
        o.orbitAngle = bearing + omega;
        double x = center.getX() + Math.cos(o.orbitAngle) * radius;
        double z = center.getZ() + Math.sin(o.orbitAngle) * radius;
        double sx = (x - cur.getX()) * ORBIT_POS_LERP;
        double sz = (z - cur.getZ()) * ORBIT_POS_LERP;
        double stepLen = Math.hypot(sx, sz);
        double maxStep = Math.max(radius * omega, ORBIT_TRANSIT_SPEED * o.orbitSpeed);
        if (stepLen > maxStep && stepLen > 1.0e-6) {
            double k = maxStep / stepLen;
            sx *= k;
            sz *= k;
        }
        double wantY = center.getY() + Math.max(ORBIT_MIN_HEIGHT_OVER_POI, o.orbitHeight);
        double sy = (wantY - cur.getY()) * ORBIT_POS_LERP;
        double maxVert = ORBIT_VERT_SPEED * o.orbitSpeed;
        sy = Math.max(-maxVert, Math.min(maxVert, sy));
        Location next = new Location(cur.getWorld(), cur.getX() + sx, cur.getY() + sy, cur.getZ() + sz);
        liftAboveTerrain(next);
        o.vel = new Vector(sx, sy, sz);
        o.pos = next;
        double tx = -Math.sin(o.orbitAngle);
        double tz = Math.cos(o.orbitAngle);
        o.cruiseDir = new Vector(tx, 0, tz).normalize();
        o.yaw = (float) Math.toDegrees(Math.atan2(-tx, tz));
        o.cruiseAltitude = next.getY();
        o.airspeed = Math.hypot(sx, sz);
    }

    private void tickOrphans() {
        if (orphans.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, OrphanFlight>> it = orphans.entrySet().iterator();
        while (it.hasNext()) {
            OrphanFlight o = it.next().getValue();
            World world = o.pos.getWorld();
            if (world == null) {
                it.remove();
                continue;
            }
            if (o.abandoned && o.orbit && o.orbitCenter != null && o.orbitCenter.getWorld() != null) {
                tickOrphanOrbit(o);
            } else {
                double spd = o.abandoned
                        ? Math.max(ORPHAN_CRUISE_SPEED, o.airspeed)
                        : ORPHAN_CRUISE_SPEED;
                Vector step = o.cruiseDir.clone().multiply(spd);
                o.pos.add(step);
                if (o.abandoned && !Double.isNaN(o.cruiseAltitude)) {
                    o.pos.setY(o.cruiseAltitude);
                }
                o.vel = step.clone();
            }
            int fuelLeft = 1;
            if (plugin.dronePads() != null) {
                fuelLeft = plugin.dronePads().consumeFuelTick(o.padId);
                if (plugin.dronePads().fuelPercent(o.padId) <= 0.0) {
                    fuelLeft = 0;
                }
            }
            if (o.abandoned) {
                if (fuelLeft <= 0) {
                    String orphanAir = airframeLabel(typeOfPad(o.padId));
                    startCrash(o.pos.clone(), o.cruiseDir, o.yaw, o.previousPilotId, o.previousPilotName, orphanAir);
                    if (plugin.dronePads() != null) {
                        plugin.dronePads().destroyPad(o.padId);
                    }
                    it.remove();
                    broadcastDroneVis();
                }
                continue;
            }
            o.ticksLeft--;
            if (o.killerId != null && o.ticksLeft % 20 == 0) {
                Player killer = Bukkit.getPlayer(o.killerId);
                if (killer != null && killer.isOnline()) {
                    int secs = Math.max(0, (o.ticksLeft + 19) / 20);
                    String air = airframeLabel(typeOfPad(o.padId)).toUpperCase(java.util.Locale.ROOT);
                    Location seatLoc = seatLocationForOrphan(o);
                    String coord = seatLoc != null
                            ? String.format(java.util.Locale.ROOT, " @ %.0f %.0f %.0f",
                            seatLoc.getX(), seatLoc.getY(), seatLoc.getZ())
                            : "";
                    boolean nearSeat = seatLoc != null && killer.getWorld().equals(seatLoc.getWorld())
                            && killer.getLocation().distance(seatLoc) < 8.0;
                    if (nearSeat) {
                        killer.sendActionBar(Component.text("SIT NOW — " + air + " CONTROLLABLE (" + secs + "s)",
                                NamedTextColor.RED, TextDecoration.BOLD));
                    } else {
                        killer.sendActionBar(Component.text(
                                air + " CONTROLLABLE — sit linked seat (" + secs + "s)" + coord,
                                NamedTextColor.GOLD));
                    }
                    if (seatLoc != null && seatLoc.getWorld() != null) {
                        killer.playSound(seatLoc, Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.6f);
                        seatLoc.getWorld().spawnParticle(Particle.END_ROD, seatLoc.clone().add(0, 0.5, 0),
                                4, 0.15, 0.25, 0.15, 0.01);
                        seatLoc.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, seatLoc.clone().add(0, 0.8, 0),
                                6, 0.2, 0.15, 0.2, 0.02);
                    }
                }
            }
            if (o.ticksLeft <= 0 || fuelLeft <= 0) {
                String orphanAir = airframeLabel(typeOfPad(o.padId));
                startCrash(o.pos.clone(), o.cruiseDir, o.yaw, o.previousPilotId, o.previousPilotName, orphanAir);
                if (plugin.dronePads() != null) {
                    plugin.dronePads().destroyPad(o.padId);
                }
                it.remove();
                broadcastDroneVis();
            }
        }
    }

    private Location seatLocationForOrphan(OrphanFlight o) {
        if (o == null || o.seatKey == null || plugin.droneSeats() == null) {
            return null;
        }
        for (DroneSeatService.Seat seat : plugin.droneSeats().all()) {
            if (o.seatKey.equals(seat.key())) {
                return plugin.droneSeats().sitLocation(seat);
            }
        }
        return null;
    }

    public Optional<UUID> seatBodyPilot(Entity entity) {
        if (!(entity instanceof Mannequin body)) {
            return Optional.empty();
        }
        String raw = body.getPersistentDataContainer().get(WarzKeys.of("drone_seat_body"), PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static ItemStack cloneOrNull(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return null;
        }
        return stack.clone();
    }

    private static boolean sameBlock(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            return false;
        }
        return a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private static Vector createShotVector(Player player, double accuracy, double speed) {
        int acc = (int) (accuracy * 1000);
        if (acc <= 0) {
            acc = 1;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double dir = -player.getLocation().getYaw() - 90.0F;
        double pitch = -player.getLocation().getPitch();
        double xwep = (random.nextInt(acc) - random.nextInt(acc) + 0.5D) / 1000.0D;
        double ywep = (random.nextInt(acc) - random.nextInt(acc) + 0.5D) / 1000.0D;
        double zwep = (random.nextInt(acc) - random.nextInt(acc) + 0.5D) / 1000.0D;
        double xd = Math.cos(Math.toRadians(dir)) * Math.cos(Math.toRadians(pitch)) + xwep;
        double yd = Math.sin(Math.toRadians(pitch)) + ywep;
        double zd = -Math.sin(Math.toRadians(dir)) * Math.cos(Math.toRadians(pitch)) + zwep;
        return new Vector(xd, yd, zd).normalize().multiply(speed);
    }

    public static final class DroneSession {
        final UUID playerId;
        final Location padStandLocation;
        final Location padBlockLocation;
        UUID bodyId;
        final BlockDisplay[] displays;
        /** Helmet, chest, legs, boots — restored on exit. */
        final ItemStack[] savedArmor;
        final ItemStack savedOffhand;
        final ItemStack[] savedHotbar;
        final int savedHeldSlot;
        final boolean savedAllowFlight;
        final boolean savedFlying;
        final float savedFlySpeed;
        final GameMode savedMode;
        int ammo;
        /** true = fixed-wing stick; false = autopilot holds cruiseDir. */
        boolean manualControl = false;
        /** Soft HUD label (TAKEOFF roll / LANDING approach); stick always owns flight. */
        FlightPhase flightPhase = FlightPhase.CRUISE;
        int takeoffTicks;
        int landingTicks;
        /** Airspeed blocks/tick for fixed-wing integrator. */
        double airspeed;
        /** 0…1 throttle between min and max airspeed. */
        double throttle = 0.75;
        /** Body heading degrees (turn); camera is free-look. */
        float headingDeg;
        boolean stallWarn;
        boolean orbit;
        /** Persistent world point the flight loop circles (slides toward orbitTarget). */
        Location orbitCenter;
        /** Center of the locked target block. */
        Location orbitTarget;
        /** Block coords of the current look-driven POI. */
        Location orbitTargetBlock;
        /** Horizontal circle radius. 0 = seed from standoff on next lock. */
        double orbitRadius;
        double orbitAngle;
        /** Height held ABOVE the POI — absolute altitude follows the ground under it. */
        double orbitHeight;
        /** Last look the retarget raycast ran at — NaN = retarget on the next tick. */
        float orbitLookYaw = Float.NaN;
        float orbitLookPitch = Float.NaN;
        /** Rotation the gimbal commanded this tick / last tick, for echo rejection. */
        float orbitCmdYaw = Float.NaN;
        float orbitCmdPitch = Float.NaN;
        float orbitPrevCmdYaw = Float.NaN;
        float orbitPrevCmdPitch = Float.NaN;
        /** Ticks since the operator last moved the mouse. */
        int orbitLookIdle;
        float orbitSpeed = 1.0f;
        /** Autopilot / post-orbit heading (not steered by look). */
        Vector cruiseDir;
        /** Locked Y while on autopilot. NaN = unset. */
        double cruiseAltitude = Double.NaN;
        Location lastLoc;
        OpticMode optic = OpticMode.NORMAL;
        int zoomLevel;
        long lastFireMs;
        int ticks;
        /** IR designator from drone camera toward crosshair. */
        boolean irLaser;
        int flareCharges = FLARE_CHARGES_MAX;
        long flareActiveUntilMs;
        long flareCooldownUntilMs;
        long missileWarnUntilMs;
        long lockWarnUntilMs;
        boolean lockHard;
        /** Entities force-shown past vanilla tracking for long-zoom targeting. */
        final Set<UUID> forcedEntities = new HashSet<>();
        int savedViewDistance = -1;
        /** True when entered via {@link #tryEnterSeat} (mannequin in stairs). */
        boolean fromSeat;
        String seatKey;
        /** Linked parked pad airframe (rockets + nose yaw). */
        UUID parkedPadId;
        /** Airframe variant (MQ-9 / RQ-4 / …). */
        BigDroneType type = BigDroneType.MQ9;
        /** Locked forward yaw for the seated operator mannequin. */
        float seatYaw = Float.NaN;
        /** Airframe structure (small-arms HP). */
        int structureHp = STRUCTURE_MAX;
        /** Datalink quality 0–1. */
        double datalinkSignal = 1.0;
        boolean datalinkFrozen;
        long datalinkLostSinceMs;
        long seatArmedAtMs;
        byte lastFault = FAULT_NONE;
        long faultUntilMs;
        /** Active munition HUD family for OSD (laser / JAGM / storm / AA). */
        MunitionProfile.HudMode guidanceHud = MunitionProfile.HudMode.NONE;
        /** Seeker state byte — see GUIDE_* constants. */
        byte guideState = GUIDE_NONE;
        /** Track confidence 0–100 for OSD bars. */
        int trackQuality;
        /** Orbit circle fixed — camera free, look does not retarget POI. */
        boolean orbitLocked;
        /** Brief ARMED cue after bay cycle. */
        long armedCueUntilMs;
        /** Debounce LMB / arm-swing bay cycle (interact + swing both fire). */
        long bayCycleUntilMs;
        /** Seeker box aim (AA / JAGM preview or in-flight). NaN = none. */
        float seekerX = Float.NaN;
        float seekerY = Float.NaN;
        float seekerZ = Float.NaN;

        DroneSession(UUID playerId, Location padStandLocation, Location padBlockLocation, UUID bodyId,
                     BlockDisplay[] displays, ItemStack[] savedArmor, ItemStack savedOffhand,
                     ItemStack[] savedHotbar, int savedHeldSlot,
                     boolean savedAllowFlight, boolean savedFlying, float savedFlySpeed, GameMode savedMode,
                     int ammo) {
            this.playerId = playerId;
            this.padStandLocation = padStandLocation;
            this.padBlockLocation = padBlockLocation;
            this.bodyId = bodyId;
            this.displays = displays;
            this.savedArmor = savedArmor != null ? savedArmor : new ItemStack[4];
            this.savedOffhand = savedOffhand;
            this.savedHotbar = savedHotbar;
            this.savedHeldSlot = savedHeldSlot;
            this.savedAllowFlight = savedAllowFlight;
            this.savedFlying = savedFlying;
            this.savedFlySpeed = savedFlySpeed <= 0 ? DEFAULT_FLY_SPEED : savedFlySpeed;
            this.savedMode = savedMode;
            this.ammo = Math.max(0, ammo);
        }
    }

    private static final class OrphanFlight {
        UUID padId;
        Location pos;
        Vector vel;
        Vector cruiseDir;
        float yaw;
        int structureHp;
        int ticksLeft;
        UUID killerId;
        String seatKey;
        /** Operator who died / left — for takeover kill-feed credit. */
        String previousPilotName;
        UUID previousPilotId;
        /** True when Exit abandoned mid-air — flies until fuel out (no 10s timeout). */
        boolean abandoned;
        double airspeed = ORPHAN_CRUISE_SPEED;
        double cruiseAltitude = Double.NaN;
        BigDroneType type;
        /** Continue circling after abandon-from-orbit. */
        boolean orbit;
        Location orbitCenter;
        double orbitRadius;
        double orbitAngle;
        double orbitHeight = ORBIT_MIN_HEIGHT_OVER_POI;
        float orbitSpeed = 1f;
        double linearSpeed;
    }

    private static final class AaMissile {
        final UUID shooter;
        final Location pos;
        Vector vel;
        final GunDefinition gun;
        final RoundDefinition round;
        final MunitionProfile profile;
        UUID targetPilot;
        int ticks;

        AaMissile(UUID shooter, Location pos, Vector vel, GunDefinition gun, RoundDefinition round,
                  MunitionProfile profile) {
            this.shooter = shooter;
            this.pos = pos.clone();
            this.vel = vel.clone();
            this.gun = gun;
            this.round = round;
            this.profile = profile;
        }
    }

    /** Laser-guided AGM-114R9X kinetic Hellfire. */
    private static final class HellfireR9x {
        final UUID shooter;
        final Location pos;
        Vector vel;
        Location aim;
        final GunDefinition gun;
        final RoundDefinition round;
        int ticks;
        boolean bladesOut;

        HellfireR9x(UUID shooter, Location pos, Vector vel, Location aim,
                    GunDefinition gun, RoundDefinition round) {
            this.shooter = shooter;
            this.pos = pos.clone();
            this.vel = vel.clone();
            this.aim = aim.clone();
            this.gun = gun;
            this.round = round;
        }
    }

    /** Profile-driven laser / glide / dual / multi guided UAV munition. */
    private static final class GuidedStrike {
        final UUID shooter;
        final Location pos;
        Vector vel;
        Location aim;
        Location lastKnown;
        final GunDefinition gun;
        final RoundDefinition round;
        final MunitionProfile profile;
        UUID entityTarget;
        int ticks;
        int lostTicks;
        byte guideState = GUIDE_LASER;
        int trackQuality = 50;
        boolean wingsOut;

        GuidedStrike(UUID shooter, Location pos, Vector vel, Location aim,
                     GunDefinition gun, RoundDefinition round, MunitionProfile profile) {
            this.shooter = shooter;
            this.pos = pos.clone();
            this.vel = vel.clone();
            this.aim = aim.clone();
            this.gun = gun;
            this.round = round;
            this.profile = profile;
        }
    }
}
