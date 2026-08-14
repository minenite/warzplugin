package net.minenite.warzplugin;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Allowed zombies dig up from the ground when they spawn (same FX as Paper WarZ).
 */
public final class GroundEmergeListener implements Listener {

    private static final Set<EntityType> EMERGE_TYPES = EnumSet.of(
            EntityType.ZOMBIE,
            EntityType.HUSK,
            EntityType.ZOMBIE_VILLAGER,
            EntityType.ZOMBIFIED_PIGLIN,
            EntityType.PARCHED
    );

    private static final Set<CreatureSpawnEvent.SpawnReason> EMERGE_REASONS = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.NATURAL,
            CreatureSpawnEvent.SpawnReason.REINFORCEMENTS,
            CreatureSpawnEvent.SpawnReason.SPAWNER,
            CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER,
            CreatureSpawnEvent.SpawnReason.DEFAULT,
            CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION,
            CreatureSpawnEvent.SpawnReason.REANIMATE,
            CreatureSpawnEvent.SpawnReason.NETHER_PORTAL,
            CreatureSpawnEvent.SpawnReason.JOCKEY,
            CreatureSpawnEvent.SpawnReason.CUSTOM,
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
    );

    private static final int EMERGE_TICKS = 48;
    private static final String TAG = "warz_emerging";

    private final WarzPlugin plugin;
    private final Map<UUID, BukkitTask> active = new ConcurrentHashMap<>();

    public GroundEmergeListener(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!EMERGE_TYPES.contains(event.getEntityType())) {
            return;
        }
        if (!EMERGE_REASONS.contains(event.getSpawnReason())) {
            return;
        }
        LivingEntity entity = event.getEntity();
        Bukkit.getScheduler().runTask(plugin, () -> beginEmerge(entity));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        cancel(event.getEntity().getUniqueId());
    }

    private void beginEmerge(LivingEntity entity) {
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return;
        }
        if (active.containsKey(entity.getUniqueId())) {
            return;
        }
        if (!EMERGE_TYPES.contains(entity.getType())) {
            return;
        }

        Location feet = entity.getLocation();
        World world = feet.getWorld();
        if (world == null) {
            return;
        }

        Surface surface = findSurface(feet);
        if (surface == null) {
            return;
        }

        float yaw = feet.getYaw();
        double height = Math.max(0.9, entity.getHeight());
        final double startY = Math.max(world.getMinHeight() + 0.1, surface.feetY - height);
        final double endY = surface.feetY;

        Location buried = new Location(world, surface.x, startY, surface.z, yaw, feet.getPitch());
        entity.teleport(buried);

        entity.setAI(false);
        entity.setGravity(false);
        entity.setCollidable(false);
        entity.setInvulnerable(true);
        try {
            entity.setNoPhysics(true);
        } catch (Throwable ignored) {
            // CardForge may not support noPhysics on all living entities
        }
        entity.setVelocity(new Vector(0, 0, 0));
        try {
            entity.setPose(Pose.SWIMMING, true);
        } catch (Throwable ignored) {
            // ignore
        }
        entity.addScoreboardTag(TAG);

        BlockData dirt = surface.blockData;
        UUID id = entity.getUniqueId();
        final int[] tick = {0};

        burstDirt(world, surface.hole, dirt, 18);
        world.playSound(surface.hole, digSound(dirt.getMaterial()), SoundCategory.HOSTILE, 0.85f, 0.7f);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!entity.isValid() || entity.isDead()) {
                cancel(id);
                return;
            }

            tick[0]++;
            double t = Math.min(1.0, tick[0] / (double) EMERGE_TICKS);
            double eased = 1.0 - Math.pow(1.0 - t, 2.2);
            double y = startY + (endY - startY) * eased;
            Location next = new Location(world, surface.x, y, surface.z, entity.getLocation().getYaw(), 0f);
            entity.teleport(next);
            entity.setVelocity(new Vector(0, 0, 0));

            if (tick[0] % 2 == 0) {
                world.spawnParticle(org.bukkit.Particle.BLOCK, surface.hole, 6,
                        0.22, 0.08, 0.22, 0.02, dirt);
            }
            if (tick[0] % 6 == 0) {
                float pitchFx = 0.55f + (float) (eased * 0.45f)
                        + ThreadLocalRandom.current().nextFloat() * 0.08f;
                world.playSound(surface.hole, digSound(dirt.getMaterial()),
                        SoundCategory.HOSTILE, 0.45f, pitchFx);
            }

            if (tick[0] >= EMERGE_TICKS) {
                finishEmerge(entity, surface);
                cancel(id);
            }
        }, 1L, 1L);

        active.put(id, task);
    }

    private void finishEmerge(LivingEntity mob, Surface surface) {
        if (mob == null || !mob.isValid()) {
            return;
        }
        World world = mob.getWorld();
        Location stand = new Location(world, surface.x, surface.feetY, surface.z,
                mob.getLocation().getYaw(), 0f);
        mob.teleport(stand);
        try {
            mob.setNoPhysics(false);
        } catch (Throwable ignored) {
            // ignore
        }
        mob.setGravity(true);
        mob.setCollidable(true);
        mob.setInvulnerable(false);
        mob.setAI(true);
        try {
            mob.setPose(Pose.STANDING, false);
        } catch (Throwable ignored) {
            // ignore
        }
        mob.removeScoreboardTag(TAG);
        mob.setVelocity(new Vector(0, 0.12, 0));

        burstDirt(world, surface.hole, surface.blockData, 28);
        world.playSound(surface.hole, digSound(surface.blockData.getMaterial()),
                SoundCategory.HOSTILE, 1.0f, 0.85f);
        world.playSound(stand, Sound.ENTITY_ZOMBIE_AMBIENT, SoundCategory.HOSTILE, 0.7f, 0.85f);
    }

    private void cancel(UUID id) {
        BukkitTask task = active.remove(id);
        if (task != null) {
            task.cancel();
        }
        org.bukkit.entity.Entity ent = Bukkit.getEntity(id);
        if (ent instanceof LivingEntity living && living.isValid()) {
            try {
                living.setNoPhysics(false);
            } catch (Throwable ignored) {
                // ignore
            }
            living.setGravity(true);
            living.setCollidable(true);
            living.setInvulnerable(false);
            living.setAI(true);
            try {
                living.setPose(Pose.STANDING, false);
            } catch (Throwable ignored) {
                // ignore
            }
            living.removeScoreboardTag(TAG);
        }
    }

    private static void burstDirt(World world, Location hole, BlockData data, int count) {
        world.spawnParticle(org.bukkit.Particle.BLOCK, hole, count, 0.28, 0.15, 0.28, 0.08, data);
        world.spawnParticle(org.bukkit.Particle.CLOUD, hole, Math.max(4, count / 4), 0.2, 0.1, 0.2, 0.01);
    }

    private static Sound digSound(Material mat) {
        if (mat == null) {
            return Sound.BLOCK_GRAVEL_BREAK;
        }
        String key = mat.name();
        if (key.contains("SAND")) {
            return Sound.BLOCK_SAND_BREAK;
        }
        if (key.contains("GRAVEL") || key.contains("DIRT") || key.contains("MUD")
                || key.contains("PODZOL") || key.contains("MYCELIUM") || key.contains("SOUL")) {
            return Sound.BLOCK_GRAVEL_BREAK;
        }
        if (key.contains("STONE") || key.contains("DEEPSLATE") || key.contains("BLACKSTONE")) {
            return Sound.BLOCK_STONE_BREAK;
        }
        return Sound.BLOCK_GRAVEL_BREAK;
    }

    private static Surface findSurface(Location feet) {
        World world = feet.getWorld();
        if (world == null) {
            return null;
        }
        int x = feet.getBlockX();
        int z = feet.getBlockZ();
        int fromY = Math.min(world.getMaxHeight() - 2, feet.getBlockY() + 48);
        int minY = Math.max(world.getMinHeight() + 1, feet.getBlockY() - 16);

        for (int y = fromY; y >= minY; y--) {
            Block ground = world.getBlockAt(x, y, z);
            Block above = world.getBlockAt(x, y + 1, z);
            if (!isSupport(ground) || isOccluding(above) || above.isLiquid()) {
                continue;
            }
            double feetY = y + 1.0;
            double cx = x + 0.5;
            double cz = z + 0.5;
            Location hole = new Location(world, cx, feetY + 0.05, cz);
            return new Surface(cx, cz, feetY, hole, ground.getBlockData().clone());
        }
        return null;
    }

    private static boolean isSupport(Block block) {
        if (block == null || block.isEmpty() || block.isLiquid()) {
            return false;
        }
        Material type = block.getType();
        if (!type.isSolid()) {
            return false;
        }
        return type != Material.COBWEB && !type.name().contains("LEAVES");
    }

    private static boolean isOccluding(Block block) {
        if (block == null || block.isEmpty() || block.isPassable()) {
            return false;
        }
        return block.getType().isOccluding();
    }

    private record Surface(double x, double z, double feetY, Location hole, BlockData blockData) {
    }
}
