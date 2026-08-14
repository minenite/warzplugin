package net.minenite.warzplugin;

import java.util.EnumSet;
import java.util.Set;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityTransformEvent;

/**
 * Apocalypse roster from the Paper WarZ server: only zombie-family (and husks)
 * may spawn. Everything else is cancelled and purged on enable. Daylight does
 * not ignite zombies; lava / fire sources still do.
 */
public final class SpawnRestrictListener implements Listener {

    private static final Set<EntityType> ALLOWED = EnumSet.of(
            EntityType.ZOMBIE,
            EntityType.HUSK,
            EntityType.ZOMBIE_VILLAGER,
            EntityType.ZOMBIFIED_PIGLIN,
            EntityType.PARCHED,
            EntityType.VILLAGER,
            EntityType.MANNEQUIN
    );

    private static final Set<EntityType> SUN_FIRE_IMMUNE = EnumSet.of(
            EntityType.ZOMBIE,
            EntityType.ZOMBIE_VILLAGER,
            EntityType.HUSK,
            EntityType.PARCHED
    );

    private final WarzPlugin plugin;

    public SpawnRestrictListener(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public static boolean isAllowed(EntityType type) {
        return type != null && ALLOWED.contains(type);
    }

    /** Remove already-loaded living mobs that are not on the allowlist. */
    public void purgeWorlds() {
        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player) {
                    continue;
                }
                if (!(entity instanceof LivingEntity)) {
                    continue;
                }
                if (!isAllowed(entity.getType())) {
                    entity.remove();
                    removed++;
                }
            }
        }
        plugin.getLogger().info("Spawn restrict: purged " + removed + " disallowed living entities.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!isAllowed(event.getEntityType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        Entity transformed = event.getTransformedEntity();
        if (transformed != null && !isAllowed(transformed.getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (!SUN_FIRE_IMMUNE.contains(event.getEntityType())) {
            return;
        }
        if (event instanceof EntityCombustByBlockEvent || event instanceof EntityCombustByEntityEvent) {
            return;
        }
        event.setCancelled(true);
    }
}
