package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.util.LaserBeams;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MainHand;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Long Prongs — scoop a dab of lava (incl. flowing), hold it dripping on the tip,
 * quench in a cauldron for {@link ItemFactory#createObsidianShards}.
 */
public final class LongProngsService implements Listener {
    private static final double REACH = 5.5;
    private static final int SHARDS_PER_QUENCH = 2;

    private final WarzPlugin plugin;
    private BukkitTask dripTask;

    public LongProngsService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (dripTask != null) {
            dripTask.cancel();
        }
        dripTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickDrips, 4L, 4L);
    }

    public void stop() {
        if (dripTask != null) {
            dripTask.cancel();
            dripTask = null;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!plugin.items().isLongProngs(hand)) {
            return;
        }

        boolean loaded = plugin.items().isLongProngsLoaded(hand);

        // Loaded → quench in cauldron first (block click).
        if (loaded && action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Block cauldron = resolveCauldron(event.getClickedBlock(), event.getBlockFace());
            if (cauldron != null) {
                event.setCancelled(true);
                quench(player, hand, cauldron);
                return;
            }
        }

        // Empty → scoop lava (source or flowing).
        if (!loaded) {
            Block lava = resolveLava(player, event);
            if (lava != null) {
                event.setCancelled(true);
                scoop(player, hand, lava);
            }
        }
    }

    private void scoop(Player player, ItemStack hand, Block lava) {
        ItemStack loaded = plugin.items().createLongProngs(true);
        loaded.setAmount(hand.getAmount());
        player.getInventory().setItemInMainHand(loaded);
        Location at = lava.getLocation().add(0.5, 0.6, 0.5);
        player.getWorld().playSound(at, Sound.ITEM_BUCKET_FILL_LAVA, 0.85f, 1.35f);
        player.getWorld().playSound(at, Sound.BLOCK_FIRE_AMBIENT, 0.5f, 1.6f);
        player.getWorld().spawnParticle(Particle.LAVA, at, 8, 0.15, 0.2, 0.15, 0.02);
        player.getWorld().spawnParticle(Particle.FLAME, at, 12, 0.12, 0.15, 0.12, 0.01);
        player.sendActionBar(ItemFactory.colorize("&6Long Prongs &7— lava on the tip"));
        player.sendMessage(ItemFactory.colorize(
                "&7Scooped a dab of lava. Quench it in a &fcauldron &7for &8Obsidian Shards&7."));
    }

    private void quench(Player player, ItemStack hand, Block cauldron) {
        // Prefer water cauldrons — consume one level when present.
        if (cauldron.getType() == Material.WATER_CAULDRON
                && cauldron.getBlockData() instanceof Levelled levelled) {
            int level = levelled.getLevel();
            if (level <= 1) {
                cauldron.setType(Material.CAULDRON);
            } else {
                levelled.setLevel(level - 1);
                cauldron.setBlockData(levelled);
            }
        }

        ItemStack empty = plugin.items().createLongProngs(false);
        empty.setAmount(1);
        if (hand.getAmount() > 1) {
            hand.setAmount(hand.getAmount() - 1);
            player.getInventory().setItemInMainHand(hand);
            ItemStack left = plugin.items().addItemMerging(player.getInventory(), empty);
            if (left != null && left.getAmount() > 0) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        } else {
            player.getInventory().setItemInMainHand(empty);
        }

        ItemStack shards = plugin.items().createObsidianShards(SHARDS_PER_QUENCH);
        ItemStack leftover = plugin.items().addItemMerging(player.getInventory(), shards);
        if (leftover != null && leftover.getAmount() > 0) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }

        Location at = cauldron.getLocation().add(0.5, 0.9, 0.5);
        player.getWorld().playSound(at, Sound.BLOCK_LAVA_EXTINGUISH, 1f, 1.1f);
        player.getWorld().playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.7f, 0.8f);
        player.getWorld().spawnParticle(Particle.CLOUD, at, 10, 0.2, 0.25, 0.2, 0.02);
        player.getWorld().spawnParticle(Particle.SMOKE, at, 8, 0.15, 0.2, 0.15, 0.01);
        player.sendMessage(ItemFactory.colorize(
                "&8Obsidian Shards &7×" + SHARDS_PER_QUENCH + " — lava quenched."));
        player.sendActionBar(ItemFactory.colorize("&8+" + SHARDS_PER_QUENCH + " Obsidian Shards"));
    }

    private void tickDrips() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.isOnline() || player.isDead()) {
                continue;
            }
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (!plugin.items().isLongProngsLoaded(hand)) {
                continue;
            }
            Location[] tips = tipLocations(player);
            int tick = player.getTicksLived();
            // Fire + lava drips from each of the three prong tips (main-hand pose).
            for (int i = 0; i < tips.length; i++) {
                Location tip = tips[i];
                player.getWorld().spawnParticle(Particle.FLAME, tip, 1, 0.008, 0.008, 0.008, 0.002);
                if (tick % 3 == i) {
                    player.getWorld().spawnParticle(Particle.SMALL_FLAME, tip, 1, 0.006, 0.006, 0.006, 0.001);
                }
                if (tick % 6 == i) {
                    player.getWorld().spawnParticle(Particle.DRIPPING_LAVA, tip, 1, 0.0, 0.0, 0.0, 0);
                }
                if (tick % 12 == i) {
                    player.getWorld().spawnParticle(Particle.LAVA, tip, 1, 0.0, 0.0, 0.0, 0);
                }
            }
            if (tick % 40 == 0) {
                player.getWorld().playSound(tips[1], Sound.BLOCK_LAVA_POP, 0.25f, 1.4f);
            }
        }
    }

    /**
     * World positions of the three lava-hot prong tips on a handheld Long Prongs
     * (texture tip = top of item; held like a tool in the main hand).
     */
    private static Location[] tipLocations(Player player) {
        Location eye = player.getEyeLocation();
        double side = player.getMainHand() == MainHand.LEFT ? -1.0 : 1.0;

        // Grip in the main-hand pocket (below / beside the eye), then shaft to tip.
        double gripRight = 0.36 * side;
        double gripUp = player.isSneaking() ? -0.72 : -0.52;
        double gripForward = 0.42;
        Location grip = LaserBeams.muzzleOrigin(eye, gripRight, gripUp, gripForward);

        Vector forward = eye.getDirection().clone();
        if (forward.lengthSquared() < 1.0E-6) {
            forward = new Vector(0, 0, 1);
        } else {
            forward.normalize();
        }
        Vector right = forward.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6) {
            right = new Vector(1, 0, 0);
        } else {
            right.normalize();
        }
        Vector up = right.clone().crossProduct(forward).normalize();

        // Handheld display tilts the item ~55° — tip (texture +Y) runs forward+up from the grip.
        Vector shaft = forward.clone().multiply(0.78).add(up.clone().multiply(0.58)).normalize();
        Location center = grip.clone().add(shaft.multiply(0.98));

        // Outer tines sit across the fork head.
        Vector across = right.clone().multiply(0.085);
        return new Location[] {
                center.clone().subtract(across),
                center.clone(),
                center.clone().add(across)
        };
    }

    private static Block resolveCauldron(Block clicked, BlockFace face) {
        if (isCauldron(clicked)) {
            return clicked;
        }
        if (face != null) {
            Block rel = clicked.getRelative(face);
            if (isCauldron(rel)) {
                return rel;
            }
        }
        return null;
    }

    private static boolean isCauldron(Block block) {
        if (block == null) {
            return false;
        }
        Material t = block.getType();
        return t == Material.CAULDRON || t == Material.WATER_CAULDRON || t == Material.LAVA_CAULDRON;
    }

    private Block resolveLava(Player player, PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Block clicked = event.getClickedBlock();
            if (isLava(clicked)) {
                return clicked;
            }
            Block relative = clicked.getRelative(event.getBlockFace());
            if (isLava(relative)) {
                return relative;
            }
        }
        RayTraceResult hit = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                REACH,
                FluidCollisionMode.ALWAYS,
                true);
        if (hit != null && hit.getHitBlock() != null && isLava(hit.getHitBlock())) {
            return hit.getHitBlock();
        }
        return null;
    }

    private static boolean isLava(Block block) {
        return block != null && block.getType() == Material.LAVA;
    }
}
