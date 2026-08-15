package net.minenite.warzplugin;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * Halts vanity world physics on warz: gravity sand/gravel, unsupported plants
 * popping, leaf decay, fluid flow, crop/tree growth, etc. Players can still
 * break and place blocks; the terrain just does not settle or grow on its own.
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
     * their support is removed. Always cancel (even if another plugin already
     * touched the event) so floating saplings stay put and do not drop.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPhysics(BlockPhysicsEvent event) {
        if (!enabled || !physics) {
            return;
        }
        if (isFire(event.getBlock().getType()) || isFire(event.getChangedType())) {
            return;
        }
        event.setCancelled(true);
    }

    /**
     * Extra belt-and-suspenders: nothing may replace a sapling / fungus /
     * mangrove propagule via entity block changes while freeze is on (except
     * we still allow player breaks through BlockBreakEvent).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTouchPlant(EntityChangeBlockEvent event) {
        if (!enabled || !physics) {
            return;
        }
        if (event.getEntity() instanceof FallingBlock) {
            // handled in onEntityChangeBlock for gravity
            return;
        }
        if (event.getEntity() instanceof org.bukkit.entity.Player) {
            return;
        }
        if (isFrozenPlant(event.getBlock().getType())) {
            event.setCancelled(true);
        }
    }

    private static boolean isFrozenPlant(Material type) {
        if (type == null) {
            return false;
        }
        String name = type.name();
        return name.endsWith("_SAPLING")
                || name.equals("MANGROVE_PROPAGULE")
                || name.equals("BAMBOO_SAPLING")
                || name.equals("AZALEA")
                || name.equals("FLOWERING_AZALEA")
                || name.endsWith("_FUNGUS");
    }

    /** Bone meal must never grow a sapling / azalea / fungus / propagule. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBoneMealClick(PlayerInteractEvent event) {
        if (!enabled || !growth) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (event.getItem() == null || event.getItem().getType() != Material.BONE_MEAL) {
            return;
        }
        if (isFrozenPlant(event.getClickedBlock().getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (!enabled || !growth) {
            return;
        }
        if (isFrozenPlant(event.getBlock().getType())) {
            event.setCancelled(true);
        }
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

    /** Saplings (and bone meal) never turn into trees / huge fungi. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
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
        // Fire burning out is a fade. Freezing it meant every flame this server
        // lit - dragon's breath, molotovs, flares, crash sites - burned for ever,
        // because nothing else ever removes them. The freeze is meant to stop the
        // terrain rotting, not to make fire permanent.
        if (isFire(event.getBlock().getType())) {
            return;
        }
        event.setCancelled(true);
    }

    private static boolean isFire(org.bukkit.Material type) {
        return type == org.bukkit.Material.FIRE || type == org.bukkit.Material.SOUL_FIRE;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeaves(LeavesDecayEvent event) {
        // Always keep leaves, even if fade-decay is toggled off.
        if (!enabled) {
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
