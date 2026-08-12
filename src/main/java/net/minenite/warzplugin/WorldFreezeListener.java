package net.minenite.warzplugin;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntitySpawnEvent;

/**
 * Halts vanity world physics on warz: gravity sand/gravel, unsupported plants
 * popping, leaf decay, fluid flow, crop growth, etc. Players can still break
 * and place blocks; the terrain just does not settle or grow on its own.
 */
public final class WorldFreezeListener implements Listener {

    private final WarzPlugin plugin;
    private boolean enabled;
    private boolean gravity;
    private boolean physics;
    private boolean fluids;
    private boolean growth;
    private boolean fadeDecay;

    public WorldFreezeListener(WarzPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("world-freeze.enabled", true);
        this.gravity = plugin.getConfig().getBoolean("world-freeze.gravity", true);
        this.physics = plugin.getConfig().getBoolean("world-freeze.physics", true);
        this.fluids = plugin.getConfig().getBoolean("world-freeze.fluids", true);
        this.growth = plugin.getConfig().getBoolean("world-freeze.growth", true);
        this.fadeDecay = plugin.getConfig().getBoolean("world-freeze.fade-decay", true);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Sand/gravel (and concrete powder) never turn into falling entities. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFallingSpawn(EntitySpawnEvent event) {
        if (!enabled || !gravity) {
            return;
        }
        if (event.getEntityType() == EntityType.FALLING_BLOCK
                || event.getEntity() instanceof FallingBlock) {
            event.setCancelled(true);
        }
    }

    /** Falling blocks neither leave their block nor place when landing. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!enabled || !gravity) {
            return;
        }
        if (event.getEntity() instanceof FallingBlock) {
            event.setCancelled(true);
        }
    }

    /**
     * Neighbor updates that would pop saplings, torches, carpets, etc. when
     * their support is removed.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (!enabled || !physics) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) {
        if (!enabled || !fluids) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (!enabled || !growth) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (!enabled || !growth) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (!enabled || !growth) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (!enabled || !fadeDecay) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeaves(LeavesDecayEvent event) {
        if (!enabled || !fadeDecay) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMoisture(MoistureChangeEvent event) {
        if (!enabled || !fadeDecay) {
            return;
        }
        event.setCancelled(true);
    }
}
