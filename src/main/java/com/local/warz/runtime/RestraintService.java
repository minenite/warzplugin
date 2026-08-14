package com.local.warz.runtime;

import com.local.warz.WarzKeys;
import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Handcuffs / zip ties restraints, keys, lockpicks (iron doors), pocket knife.
 * Iron doors stay locked until picked (10s); unlocks are memory-only and clear on restart.
 */
public final class RestraintService implements Listener {
    public static final String TYPE_HANDCUFFS = "handcuffs";
    public static final String TYPE_ZIP_TIES = "zip_ties";

    private static final double APPLY_RANGE = 3.25;
    private static final double ESCORT_RANGE = 3.5;
    private static final double ESCORT_FOLLOW = 0.95;
    private static final int LOCKPICK_TICKS = 20 * 10; // 10 seconds
    private static final double LOCKPICK_MAX_DIST = 5.0;

    private final WarzPlugin plugin;
    private final NamespacedKey restraintKey;
    private final Map<UUID, UUID> escorting = new HashMap<>(); // target -> escort
    /** Bottom-half iron door/trapdoor keys unlocked this uptime (cleared on restart). */
    private final Set<String> unlockedIron = new HashSet<>();
    private final Map<UUID, DoorPick> picking = new HashMap<>();
    private BukkitTask tickTask;

    public RestraintService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.restraintKey = WarzKeys.of("restraint");
    }

    public void start() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        // Fresh uptime — all iron doors locked again.
        unlockedIron.clear();
        picking.clear();
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        // Slam picked doors shut so the next boot starts locked/closed.
        for (String key : new HashSet<>(unlockedIron)) {
            Block block = blockFromKey(key);
            if (block != null && isIronOpenable(block.getType())) {
                setOpen(block, false);
            }
        }
        unlockedIron.clear();
        picking.clear();
        escorting.clear();
    }

    public boolean isRestrained(Player player) {
        return restraintType(player) != null;
    }

    public String restraintType(Player player) {
        if (player == null) {
            return null;
        }
        return player.getPersistentDataContainer().get(restraintKey, PersistentDataType.STRING);
    }

    public boolean isHandcuffed(Player player) {
        return TYPE_HANDCUFFS.equals(restraintType(player));
    }

    public boolean isZipTied(Player player) {
        return TYPE_ZIP_TIES.equals(restraintType(player));
    }

    private void setRestraint(Player player, String type) {
        if (type == null || type.isBlank()) {
            player.getPersistentDataContainer().remove(restraintKey);
            player.removeScoreboardTag("warz_restrained");
            player.removeScoreboardTag("warz_handcuffed");
            player.removeScoreboardTag("warz_ziptied");
        } else {
            player.getPersistentDataContainer().set(restraintKey, PersistentDataType.STRING, type);
            player.addScoreboardTag("warz_restrained");
            if (TYPE_HANDCUFFS.equals(type)) {
                player.addScoreboardTag("warz_handcuffed");
                player.removeScoreboardTag("warz_ziptied");
            } else if (TYPE_ZIP_TIES.equals(type)) {
                player.addScoreboardTag("warz_ziptied");
                player.removeScoreboardTag("warz_handcuffed");
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String type = restraintType(player);
        if (type != null) {
            setRestraint(player, type);
            player.sendActionBar(ItemFactory.colorize("&cRestrained &7(" + label(type) + ")"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        escorting.remove(id);
        escorting.values().removeIf(id::equals);
        picking.remove(id);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player actor = event.getPlayer();
        if (!(event.getRightClicked() instanceof Player target)) {
            return;
        }
        if (actor.getUniqueId().equals(target.getUniqueId())) {
            return;
        }
        if (actor.getLocation().distanceSquared(target.getLocation()) > APPLY_RANGE * APPLY_RANGE) {
            return;
        }

        if (isRestrained(actor)) {
            event.setCancelled(true);
            deny(actor);
            return;
        }

        ItemStack hand = actor.getInventory().getItemInMainHand();
        ItemFactory items = plugin.items();

        if (items.isHandcuffs(hand)) {
            event.setCancelled(true);
            tryApply(actor, target, TYPE_HANDCUFFS, hand);
            return;
        }
        if (items.isZipTies(hand)) {
            event.setCancelled(true);
            tryApply(actor, target, TYPE_ZIP_TIES, hand);
            return;
        }
        if (items.isHandcuffKey(hand)) {
            event.setCancelled(true);
            tryKeyUnlock(actor, target);
            return;
        }
        if (items.isLockpick(hand)) {
            event.setCancelled(true);
            tryLockpickRestraint(actor, target, hand);
            return;
        }
        if (items.isPocketKnife(hand)) {
            event.setCancelled(true);
            tryCutZipTies(actor, target, hand);
            return;
        }

        if ((hand == null || hand.getType().isAir()) && isRestrained(target)) {
            event.setCancelled(true);
            toggleEscort(actor, target);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        Action action = event.getAction();

        if (isRestrained(player)) {
            if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK
                    || action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK
                    || action == Action.PHYSICAL) {
                event.setCancelled(true);
                deny(player);
            }
            return;
        }

        if (action != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (!isIronOpenable(clicked.getType())) {
            return;
        }

        Block door = normalizeIron(clicked);
        String key = blockKey(door);
        boolean unlocked = unlockedIron.contains(key);
        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean holdingPick = plugin.items().isLockpick(hand);

        // Always claim iron door clicks so vanilla redstone-only behavior doesn't confuse.
        event.setCancelled(true);

        if (unlocked) {
            // Freely open/close like a wooden door until restart.
            boolean nowOpen = !(door.getBlockData() instanceof Openable o && o.isOpen());
            setOpen(door, nowOpen);
            player.getWorld().playSound(door.getLocation(),
                    nowOpen ? Sound.BLOCK_IRON_DOOR_OPEN : Sound.BLOCK_IRON_DOOR_CLOSE, 1.0f, 1.0f);
            return;
        }

        if (holdingPick) {
            beginOrContinuePick(player, door);
            return;
        }

        player.sendActionBar(ItemFactory.colorize("&cLocked &7— use a &fLockpick"));
        player.getWorld().playSound(door.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 0.35f, 0.55f);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isRestrained(event.getPlayer())) {
            event.setCancelled(true);
            deny(event.getPlayer());
            return;
        }
        Block block = event.getBlock();
        if (isIronOpenable(block.getType())) {
            unlockedIron.remove(blockKey(normalizeIron(block)));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isRestrained(player)) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && isRestrained(player)) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isRestrained(event.getPlayer())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isRestrained(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isRestrained(event.getPlayer())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHotbar(PlayerItemHeldEvent event) {
        if (isRestrained(event.getPlayer())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    private void beginOrContinuePick(Player player, Block door) {
        UUID id = player.getUniqueId();
        DoorPick existing = picking.get(id);
        String key = blockKey(door);
        if (existing != null && existing.key.equals(key)) {
            // Already picking this door — tick() drives progress.
            return;
        }
        picking.put(id, new DoorPick(key, LOCKPICK_TICKS));
        player.sendActionBar(ItemFactory.colorize("&eLockpicking.."));
        player.getWorld().playSound(door.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.7f, 1.4f);
    }

    private void tick() {
        tickEscorts();
        tickLockpicks();
    }

    private void tickLockpicks() {
        if (picking.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, DoorPick>> it = picking.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, DoorPick> e = it.next();
            Player player = plugin.getServer().getPlayer(e.getKey());
            DoorPick pick = e.getValue();
            if (player == null || !player.isOnline() || player.isDead()) {
                it.remove();
                continue;
            }
            if (!plugin.items().isLockpick(player.getInventory().getItemInMainHand())) {
                it.remove();
                player.sendActionBar(ItemFactory.colorize("&cLockpicking cancelled"));
                continue;
            }
            Block door = blockFromKey(pick.key);
            if (door == null || !isIronOpenable(door.getType())) {
                it.remove();
                player.sendActionBar(ItemFactory.colorize("&cLockpicking cancelled"));
                continue;
            }
            if (player.getWorld() != door.getWorld()
                    || player.getLocation().distanceSquared(door.getLocation().add(0.5, 0.5, 0.5))
                    > LOCKPICK_MAX_DIST * LOCKPICK_MAX_DIST) {
                it.remove();
                player.sendActionBar(ItemFactory.colorize("&cLockpicking cancelled &7(too far)"));
                continue;
            }
            if (unlockedIron.contains(pick.key)) {
                it.remove();
                continue;
            }

            pick.ticksLeft--;
            int done = LOCKPICK_TICKS - pick.ticksLeft;
            int secsLeft = Math.max(1, (pick.ticksLeft + 19) / 20);
            player.sendActionBar(ItemFactory.colorize("&eLockpicking.. &7" + secsLeft + "s"));

            // Lockpick-ish rattles.
            if (done % 8 == 0) {
                float pitch = 1.1f + (done % 40) * 0.02f;
                player.getWorld().playSound(door.getLocation(), Sound.BLOCK_CHAIN_STEP, 0.45f, pitch);
            }
            if (done % 20 == 10) {
                player.getWorld().playSound(door.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.25f, 1.8f);
            }
            if (done % 25 == 0) {
                player.getWorld().playSound(door.getLocation(), Sound.UI_STONECUTTER_TAKE_RESULT, 0.2f, 1.6f);
            }

            if (pick.ticksLeft <= 0) {
                it.remove();
                finishPick(player, door, pick.key);
            }
        }
    }

    private void finishPick(Player player, Block door, String key) {
        unlockedIron.add(key);
        setOpen(door, true);
        player.sendActionBar(ItemFactory.colorize("&aDoor unlocked"));
        player.sendMessage(ItemFactory.colorize(
                "&aPicked the lock &7— you can open/close this iron door until the server restarts."));
        player.getWorld().playSound(door.getLocation(), Sound.BLOCK_CHAIN_BREAK, 1.0f, 1.2f);
        player.getWorld().playSound(door.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 1.0f, 1.05f);
        player.getWorld().playSound(door.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1.4f);
    }

    private void tryApply(Player actor, Player target, String type, ItemStack hand) {
        if (target.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (isRestrained(target)) {
            actor.sendActionBar(ItemFactory.colorize("&cAlready restrained"));
            return;
        }
        if (actor.getGameMode() != GameMode.CREATIVE) {
            hand.setAmount(hand.getAmount() - 1);
        }
        setRestraint(target, type);
        escorting.remove(target.getUniqueId());
        String nice = label(type);
        actor.sendMessage(ItemFactory.colorize("&aApplied &f" + nice + " &ato &f" + target.getName()));
        target.sendMessage(ItemFactory.colorize("&cYou were restrained with &f" + nice));
        target.sendActionBar(ItemFactory.colorize("&cRestrained &7(" + nice + ")"));
        if (TYPE_HANDCUFFS.equals(type)) {
            target.getWorld().playSound(target.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.85f);
            actor.getWorld().playSound(actor.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.1f);
        } else {
            target.getWorld().playSound(target.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 0.7f);
        }
    }

    private void tryKeyUnlock(Player actor, Player target) {
        String type = restraintType(target);
        if (type == null) {
            actor.sendActionBar(ItemFactory.colorize("&7Not restrained"));
            return;
        }
        if (!TYPE_HANDCUFFS.equals(type)) {
            actor.sendActionBar(ItemFactory.colorize("&cHandcuff keys don't work on zip ties"));
            actor.sendMessage(ItemFactory.colorize("&7Use a &fPocket Knife &7to cut zip ties."));
            return;
        }
        release(target, actor, true);
        actor.sendMessage(ItemFactory.colorize("&aUnlocked handcuffs on &f" + target.getName()));
        target.sendMessage(ItemFactory.colorize("&aYour handcuffs were unlocked"));
        target.getWorld().playSound(target.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.0f, 1.2f);
    }

    private void tryLockpickRestraint(Player actor, Player target, ItemStack hand) {
        String type = restraintType(target);
        if (type == null) {
            actor.sendActionBar(ItemFactory.colorize("&7Not restrained"));
            return;
        }
        if (TYPE_ZIP_TIES.equals(type)) {
            actor.sendActionBar(ItemFactory.colorize("&cLockpicks can't cut zip ties"));
            actor.sendMessage(ItemFactory.colorize("&7Use a &fPocket Knife&7."));
            return;
        }
        if (actor.getGameMode() != GameMode.CREATIVE) {
            hand.setAmount(hand.getAmount() - 1);
        }
        release(target, actor, true);
        actor.sendMessage(ItemFactory.colorize("&aPicked handcuffs on &f" + target.getName()));
        target.sendMessage(ItemFactory.colorize("&aYour handcuffs were picked open"));
        target.getWorld().playSound(target.getLocation(), Sound.BLOCK_CHAIN_BREAK, 1.0f, 1.15f);
    }

    private void tryCutZipTies(Player actor, Player target, ItemStack hand) {
        String type = restraintType(target);
        if (type == null) {
            actor.sendActionBar(ItemFactory.colorize("&7Not restrained"));
            return;
        }
        if (!TYPE_ZIP_TIES.equals(type)) {
            actor.sendActionBar(ItemFactory.colorize("&cPocket Knife only cuts zip ties"));
            return;
        }
        release(target, actor, false);
        actor.sendMessage(ItemFactory.colorize("&aCut zip ties on &f" + target.getName()));
        target.sendMessage(ItemFactory.colorize("&aYour zip ties were cut"));
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 1.0f, 1.35f);
        actor.getWorld().playSound(actor.getLocation(), Sound.ITEM_AXE_STRIP, 0.5f, 1.6f);
    }

    private void release(Player target, Player releaser, boolean returnRestraint) {
        String type = restraintType(target);
        setRestraint(target, null);
        escorting.remove(target.getUniqueId());
        target.sendActionBar(ItemFactory.colorize("&aFree"));
        if (returnRestraint && type != null && releaser != null) {
            ItemStack giveBack = TYPE_ZIP_TIES.equals(type)
                    ? plugin.items().createZipTies(1)
                    : plugin.items().createHandcuffs(1);
            Map<Integer, ItemStack> overflow = releaser.getInventory().addItem(giveBack);
            for (ItemStack left : overflow.values()) {
                releaser.getWorld().dropItemNaturally(releaser.getLocation(), left);
            }
        }
    }

    private void toggleEscort(Player actor, Player target) {
        UUID tid = target.getUniqueId();
        UUID current = escorting.get(tid);
        if (actor.getUniqueId().equals(current)) {
            escorting.remove(tid);
            actor.sendActionBar(ItemFactory.colorize("&7Stopped escorting"));
            return;
        }
        if (actor.isSneaking()) {
            escorting.remove(tid);
            actor.sendActionBar(ItemFactory.colorize("&7Escort cancelled"));
            return;
        }
        escorting.put(tid, actor.getUniqueId());
        actor.sendActionBar(ItemFactory.colorize("&eEscorting &f" + target.getName() + " &7(sneak to stop)"));
        target.sendActionBar(ItemFactory.colorize("&eBeing escorted by &f" + actor.getName()));
    }

    private void tickEscorts() {
        if (escorting.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, UUID>> it = escorting.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, UUID> e = it.next();
            Player target = plugin.getServer().getPlayer(e.getKey());
            Player escort = plugin.getServer().getPlayer(e.getValue());
            if (target == null || escort == null || !target.isOnline() || !escort.isOnline()
                    || !isRestrained(target) || escort.isDead() || target.isDead()
                    || escort.isSneaking()) {
                it.remove();
                continue;
            }
            if (target.getWorld() != escort.getWorld()
                    || target.getLocation().distanceSquared(escort.getLocation()) > ESCORT_RANGE * ESCORT_RANGE) {
                it.remove();
                escort.sendActionBar(ItemFactory.colorize("&cEscort broken (too far)"));
                continue;
            }
            Vector behind = escort.getLocation().getDirection().setY(0);
            if (behind.lengthSquared() < 1.0E-4) {
                behind = new Vector(0, 0, 1);
            } else {
                behind.normalize().multiply(-ESCORT_FOLLOW);
            }
            var dest = escort.getLocation().clone().add(behind);
            dest.setYaw(escort.getLocation().getYaw());
            dest.setPitch(0f);
            target.setVelocity(dest.toVector().subtract(target.getLocation().toVector()).multiply(0.35));
            if (target.getLocation().distanceSquared(dest) > 1.2) {
                target.teleport(dest);
            }
        }
    }

    private static void setOpen(Block block, boolean open) {
        Block base = normalizeIron(block);
        applyOpen(base, open);
        if (base.getBlockData() instanceof Door door) {
            Block other = door.getHalf() == Bisected.Half.TOP
                    ? base.getRelative(BlockFace.DOWN)
                    : base.getRelative(BlockFace.UP);
            if (other.getType() == base.getType() && other.getBlockData() instanceof Door) {
                applyOpen(other, open);
            }
        }
    }

    private static void applyOpen(Block block, boolean open) {
        if (!(block.getBlockData() instanceof Openable openable)) {
            return;
        }
        openable.setOpen(open);
        block.setBlockData(openable, false);
    }

    private static Block normalizeIron(Block block) {
        if (block.getBlockData() instanceof Door door && door.getHalf() == Bisected.Half.TOP) {
            return block.getRelative(BlockFace.DOWN);
        }
        return block;
    }

    private static boolean isIronOpenable(Material type) {
        return type == Material.IRON_DOOR || type == Material.IRON_TRAPDOOR;
    }

    private static String blockKey(Block block) {
        Block b = normalizeIron(block);
        return b.getWorld().getUID() + "|" + b.getX() + "|" + b.getY() + "|" + b.getZ();
    }

    private Block blockFromKey(String key) {
        try {
            String[] p = key.split("\\|");
            if (p.length != 4) {
                return null;
            }
            World world = plugin.getServer().getWorld(UUID.fromString(p[0]));
            if (world == null) {
                return null;
            }
            return world.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (Exception ex) {
            return null;
        }
    }

    private static void deny(Player player) {
        player.sendActionBar(ItemFactory.colorize("&cHands are bound"));
    }

    private static String label(String type) {
        if (TYPE_ZIP_TIES.equals(type)) {
            return "Zip Ties";
        }
        return "Handcuffs";
    }

    private static final class DoorPick {
        final String key;
        int ticksLeft;

        DoorPick(String key, int ticksLeft) {
            this.key = key;
            this.ticksLeft = ticksLeft;
        }
    }
}
