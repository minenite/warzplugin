package com.local.warz.runtime;

import com.destroystokyo.paper.ClientOption;
import com.destroystokyo.paper.SkinParts;
import com.local.warz.WarzKeys;
import com.local.warz.gui.ChestInventories;
import net.minenite.warzplugin.WarzPlugin;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Death corpses inspired by unldenis/Corpse: a real player-skinned body (Paper {@link Mannequin}
 * in {@link Pose#SLEEPING}), lootable double-chest, instant respawn, timed despawn.
 * <p>
 * Worn gear is shown on the body and mirrored in reserved GUI slots — taking an item
 * off the GUI strips it from the corpse model.
 */
public final class CorpseService implements Listener {
    private static final int CHEST_SIZE = 54;
    /** Reserved equipment row in the corpse chest (synced ↔ mannequin). */
    private static final int SLOT_HELMET = 45;
    private static final int SLOT_CHEST = 46;
    private static final int SLOT_LEGS = 47;
    private static final int SLOT_BOOTS = 48;
    private static final int SLOT_OFF = 49;
    private static final int SLOT_MAIN = 50;
    private static final int LOOT_END = 45; // storage loot fills 0..44

    private final WarzPlugin plugin;
    private final NamespacedKey corpseIdKey;
    private final Map<UUID, Corpse> corpses = new ConcurrentHashMap<>();
    private BukkitTask tickTask;

    public CorpseService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.corpseIdKey = WarzKeys.of("corpse_id");
    }

    public void start() {
        applyImmediateRespawnRules();
        if (tickTask != null) {
            tickTask.cancel();
        }
        // Drop any leftover corpse entities from a previous session (were setPersistent).
        clearAll();
        Bukkit.getScheduler().runTaskLater(plugin, this::purgeMarkedEntities, 40L);
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        clearAll();
    }

    /**
     * Remove every tracked corpse and any world entities tagged with our corpse PDC.
     * Loot is discarded (no ground dumps).
     *
     * @return number of world entities removed
     */
    public int clearAll() {
        for (Corpse corpse : new ArrayList<>(corpses.values())) {
            removeCorpse(corpse, false);
        }
        corpses.clear();
        return purgeMarkedEntities();
    }

    /** Sweep loaded worlds for orphan Mannequin / Interaction / TextDisplay corpse parts. */
    public int purgeMarkedEntities() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : List.copyOf(world.getEntities())) {
                if (!entity.getPersistentDataContainer().has(corpseIdKey, PersistentDataType.STRING)) {
                    continue;
                }
                entity.remove();
                removed++;
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Purged " + removed + " leftover corpse entity(ies).");
        }
        return removed;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("corpses.enabled", true);
    }

    private long lifetimeTicks() {
        int seconds = plugin.getConfig().getInt("corpses.lifetime-seconds", 180);
        return Math.max(20L, seconds * 20L);
    }

    private boolean instantRespawn() {
        return plugin.getConfig().getBoolean("corpses.instant-respawn", true);
    }

    private String poseName() {
        return plugin.getConfig().getString("corpses.pose", "SLEEPING");
    }

    private double lootRange() {
        return Math.max(0.5, plugin.getConfig().getDouble("corpses.loot-range", 3.0));
    }

    private double lootRangeSq() {
        double r = lootRange();
        return r * r;
    }

    @SuppressWarnings("deprecation")
    private void applyImmediateRespawnRules() {
        if (!instantRespawn()) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        }
    }

    @EventHandler
    @SuppressWarnings("deprecation")
    public void onWorldLoad(WorldLoadEvent event) {
        if (instantRespawn()) {
            event.getWorld().setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        }
        // Late-loaded worlds / chunks may still hold old persistent corpses
        Bukkit.getScheduler().runTask(plugin, this::purgeMarkedEntities);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (event.isNewChunk()) {
            return;
        }
        for (Entity entity : event.getChunk().getEntities()) {
            if (!entity.getPersistentDataContainer().has(corpseIdKey, PersistentDataType.STRING)) {
                continue;
            }
            String id = entity.getPersistentDataContainer().get(corpseIdKey, PersistentDataType.STRING);
            try {
                if (id != null && corpses.containsKey(UUID.fromString(id))) {
                    continue; // still an active session corpse
                }
            } catch (IllegalArgumentException ignored) {
            }
            entity.remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!enabled()) {
            return;
        }
        Player player = event.getEntity();
        Location at = player.getLocation().clone();

        PlayerInventory inv = player.getInventory();
        // Snapshot worn gear for the body BEFORE clearing
        ItemStack helmet = cloneOrNull(inv.getHelmet());
        ItemStack chest = cloneOrNull(inv.getChestplate());
        ItemStack legs = cloneOrNull(inv.getLeggings());
        ItemStack boots = cloneOrNull(inv.getBoots());
        ItemStack main = cloneOrNull(inv.getItemInMainHand());
        ItemStack off = cloneOrNull(inv.getItemInOffHand());

        // Storage only for loose loot (skip held slot — that goes into MAIN equipment slot).
        List<ItemStack> loot = new ArrayList<>();
        ItemStack[] storage = inv.getStorageContents();
        int held = inv.getHeldItemSlot();
        for (int i = 0; i < storage.length; i++) {
            if (i == held) {
                continue;
            }
            pushLoot(loot, storage[i]);
        }

        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        inv.clear();
        inv.setArmorContents(null);
        inv.setItemInOffHand(null);

        try {
            spawnCorpse(player, at, loot, helmet, chest, legs, boots, main, off);
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to spawn corpse for " + player.getName() + ": " + t.getMessage());
            t.printStackTrace();
            for (ItemStack stack : loot) {
                if (at.getWorld() != null) {
                    at.getWorld().dropItemNaturally(at, stack);
                }
            }
        }

        if (instantRespawn()) {
            UUID id = player.getUniqueId();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline() && p.isDead()) {
                    p.spigot().respawn();
                }
            });
        }
    }

    /** Belt-and-suspenders — other plugins may re-fill drops after us. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeathClearDrops(PlayerDeathEvent event) {
        if (!enabled()) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    private static void pushLoot(List<ItemStack> loot, ItemStack stack) {
        if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
            loot.add(stack.clone());
        }
    }

    private void spawnCorpse(Player victim, Location at, List<ItemStack> loot,
                             ItemStack helmet, ItemStack chest, ItemStack legs, ItemStack boots,
                             ItemStack main, ItemStack off) {
        World world = at.getWorld();
        if (world == null) {
            return;
        }

        Location spawnAt = at.clone();
        // Lie on the block surface (same idea as Corpse plugin's sleeping fake-player)
        spawnAt.setY(Math.floor(spawnAt.getY()) + 0.01);
        spawnAt.setPitch(0f);

        UUID corpseId = UUID.randomUUID();
        Component title = Component.text(victim.getName() + "'s Corpse", NamedTextColor.DARK_RED)
                .decoration(TextDecoration.ITALIC, false);

        Pose pose = resolvePose();

        Mannequin body = world.spawn(spawnAt, Mannequin.class, m -> {
            // Persist while the server is up (chunk unload); start/stop/clear purge leftovers.
            m.setPersistent(true);
            m.setRemoveWhenFarAway(false);
            m.setInvulnerable(true);
            m.setSilent(true);
            m.setGravity(false);
            m.setAI(false);
            m.setCollidable(false);
            m.setImmovable(true);
            // Nameplate is a TextDisplay (see-through=false) — Mannequin nametags don't show on look
            m.customName(null);
            m.setCustomNameVisible(false);
            m.setDescription(null);
            try {
                m.setProfile(ResolvableProfile.resolvableProfile(victim.getPlayerProfile()));
            } catch (Throwable ignored) {
            }
            try {
                SkinParts skin = victim.getClientOption(ClientOption.SKIN_PARTS);
                if (skin != null) {
                    m.setSkinParts(skin);
                }
            } catch (Throwable ignored) {
            }
            // Equipment only — never call set*DropChance (Mannequin is not a Mob; that crashed us)
            if (plugin.getConfig().getBoolean("corpses.render-armor", true)) {
                applyBodyEquipment(m, helmet, chest, legs, boots, main, off);
            }
            m.getPersistentDataContainer().set(corpseIdKey, PersistentDataType.STRING, corpseId.toString());
        });

        body.setRotation(spawnAt.getYaw(), 0f);
        body.setVelocity(new Vector(0, 0, 0));
        if (Mannequin.validPoses().contains(pose)) {
            body.setPose(pose);
        } else if (Mannequin.validPoses().contains(Pose.SLEEPING)) {
            body.setPose(Pose.SLEEPING);
        } else if (Mannequin.validPoses().contains(Pose.SWIMMING)) {
            body.setPose(Pose.SWIMMING);
        }

        // Entity origin sits near the feet; sleeping model stretches toward look yaw → torso.
        final float bodyYaw = spawnAt.getYaw();
        Location torso = torsoOf(spawnAt, bodyYaw);
        Interaction click = world.spawn(torso.clone().add(0, 0.15, 0), Interaction.class, i -> {
            i.setPersistent(true);
            i.setResponsive(true);
            i.setInteractionWidth(2.6f);
            i.setInteractionHeight(0.95f);
            i.getPersistentDataContainer().set(corpseIdKey, PersistentDataType.STRING, corpseId.toString());
        });

        // Name label above mid-body (not through walls)
        TextDisplay label = world.spawn(torso.clone().add(0, 0.45, 0), TextDisplay.class, t -> {
            t.text(title);
            t.setBillboard(Display.Billboard.CENTER);
            t.setSeeThrough(false);
            t.setShadowed(true);
            t.setDefaultBackground(true);
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setViewRange(0.35f);
            t.setPersistent(true);
            t.setGravity(false);
            t.getPersistentDataContainer().set(corpseIdKey, PersistentDataType.STRING, corpseId.toString());
        });

        Holder holder = new Holder(corpseId);
        Inventory chestInv = ChestInventories.create(holder, CHEST_SIZE, title);
        holder.bind(chestInv);

        // Worn gear: bottom row — mirrored on the body; taking from GUI strips the model
        chestInv.setItem(SLOT_HELMET, cloneOrNull(helmet));
        chestInv.setItem(SLOT_CHEST, cloneOrNull(chest));
        chestInv.setItem(SLOT_LEGS, cloneOrNull(legs));
        chestInv.setItem(SLOT_BOOTS, cloneOrNull(boots));
        chestInv.setItem(SLOT_OFF, cloneOrNull(off));
        chestInv.setItem(SLOT_MAIN, cloneOrNull(main));

        int slot = 0;
        for (ItemStack stack : loot) {
            while (slot < LOOT_END && chestInv.getItem(slot) != null) {
                slot++;
            }
            if (slot >= LOOT_END) {
                world.dropItemNaturally(spawnAt, stack);
                continue;
            }
            chestInv.setItem(slot++, stack);
        }

        corpses.put(corpseId, new Corpse(
                corpseId,
                victim.getUniqueId(),
                victim.getName(),
                body.getUniqueId(),
                click.getUniqueId(),
                label.getUniqueId(),
                chestInv,
                System.currentTimeMillis() + lifetimeTicks() * 50L,
                pose,
                bodyYaw
        ));

        victim.sendMessage(Component.text("Corpse left behind (" + (lifetimeTicks() / 20) + "s).",
                NamedTextColor.GRAY));
        plugin.getLogger().info("Corpse spawned for " + victim.getName()
                + " at " + spawnAt.getBlockX() + "," + spawnAt.getBlockY() + "," + spawnAt.getBlockZ()
                + " (" + loot.size() + " bag + worn gear)");
    }

    private static void applyBodyEquipment(Mannequin body,
                                           ItemStack helmet, ItemStack chest, ItemStack legs,
                                           ItemStack boots, ItemStack main, ItemStack off) {
        EntityEquipment eq = body.getEquipment();
        if (eq == null) {
            return;
        }
        eq.setHelmet(cloneOrNull(helmet));
        eq.setChestplate(cloneOrNull(chest));
        eq.setLeggings(cloneOrNull(legs));
        eq.setBoots(cloneOrNull(boots));
        eq.setItemInMainHand(cloneOrNull(main));
        eq.setItemInOffHand(cloneOrNull(off));
    }

    /** GUI equipment slots → mannequin model. */
    private void syncBodyFromGui(Corpse corpse) {
        Entity entity = Bukkit.getEntity(corpse.bodyEntityId);
        if (!(entity instanceof Mannequin body) || !body.isValid()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("corpses.render-armor", true)) {
            return;
        }
        Inventory inv = corpse.inventory;
        applyBodyEquipment(body,
                inv.getItem(SLOT_HELMET),
                inv.getItem(SLOT_CHEST),
                inv.getItem(SLOT_LEGS),
                inv.getItem(SLOT_BOOTS),
                inv.getItem(SLOT_MAIN),
                inv.getItem(SLOT_OFF));
    }

    private void scheduleEquipSync(UUID corpseId) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Corpse corpse = corpses.get(corpseId);
            if (corpse != null) {
                syncBodyFromGui(corpse);
            }
        });
    }

    private Pose resolvePose() {
        try {
            return Pose.valueOf(poseName().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Pose.SLEEPING;
        }
    }

    /**
     * Mid-torso of a SLEEPING mannequin.
     * <p>
     * Client {@code LivingEntityRenderer} lays the model with ZP(90)×YP(270) then
     * scale(-1,-1,1), so the body stretches <em>perpendicular</em> to look yaw
     * (along {@code (look.z, 0, -look.x)}), not along look direction.
     */
    private static Location torsoOf(Location feet, float yaw) {
        Location base = feet.clone();
        base.setYaw(yaw);
        base.setPitch(0f);
        Vector look = base.getDirection().clone().setY(0);
        if (look.lengthSquared() < 1.0e-4) {
            look = new Vector(0, 0, 1);
        } else {
            look.normalize();
        }
        // Head lies ~1.6 along this axis from feet; mid-chest ≈ 0.95
        Vector alongBody = new Vector(look.getZ(), 0, -look.getX());
        return base.add(alongBody.multiply(0.95));
    }

    private boolean withinLootRange(Player player, Corpse corpse) {
        if (player == null || corpse == null) {
            return false;
        }
        Entity body = Bukkit.getEntity(corpse.bodyEntityId);
        if (body == null || !body.isValid()) {
            return false;
        }
        if (player.getWorld() != body.getWorld()) {
            return false;
        }
        Location torso = torsoOf(body.getLocation(), corpse.bodyYaw);
        return player.getLocation().distanceSquared(torso) <= lootRangeSq();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Corpse corpse = corpseOf(event.getRightClicked());
        if (corpse == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!withinLootRange(player, corpse)) {
            player.sendMessage(Component.text("Too far to loot.", NamedTextColor.GRAY));
            return;
        }
        player.openInventory(corpse.inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.7f, 1.0f);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (corpseOf(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        Corpse corpse = corpses.get(holder.corpseId);
        if (corpse == null) {
            return;
        }
        if (event.getWhoClicked() instanceof Player player && !withinLootRange(player, corpse)) {
            event.setCancelled(true);
            player.closeInventory();
            player.sendMessage(Component.text("Too far to loot.", NamedTextColor.GRAY));
            return;
        }
        scheduleEquipSync(holder.corpseId);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        Corpse corpse = corpses.get(holder.corpseId);
        if (corpse == null) {
            return;
        }
        if (event.getWhoClicked() instanceof Player player && !withinLootRange(player, corpse)) {
            event.setCancelled(true);
            player.closeInventory();
            player.sendMessage(Component.text("Too far to loot.", NamedTextColor.GRAY));
            return;
        }
        scheduleEquipSync(holder.corpseId);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof Holder holder)) {
            return;
        }
        Corpse corpse = corpses.get(holder.corpseId);
        if (corpse == null) {
            return;
        }
        syncBodyFromGui(corpse);
        if (event.getPlayer() instanceof Player player) {
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.55f, 1.0f);
        }
        if (isEmpty(corpse.inventory)) {
            removeCorpse(corpse, false);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Corpse>> it = corpses.entrySet().iterator();
        while (it.hasNext()) {
            Corpse corpse = it.next().getValue();
            Entity body = Bukkit.getEntity(corpse.bodyEntityId);
            Entity click = Bukkit.getEntity(corpse.clickEntityId);
            Entity label = Bukkit.getEntity(corpse.labelEntityId);
            if (body == null || !body.isValid()) {
                clearLoot(corpse);
                if (click != null) {
                    click.remove();
                }
                if (label != null) {
                    label.remove();
                }
                it.remove();
                continue;
            }
            if (now >= corpse.despawnAtMs) {
                Location at = body.getLocation().clone();
                clearLoot(corpse);
                closeViewers(corpse);
                body.remove();
                if (click != null) {
                    click.remove();
                }
                if (label != null) {
                    label.remove();
                }
                it.remove();
                spawnDespawnZombie(at);
                continue;
            }
            // Keep pose locked (clients sometimes reset mannequins)
            if (body instanceof Mannequin mannequin) {
                if (!mannequin.isInvulnerable()) {
                    mannequin.setInvulnerable(true);
                }
                if (Mannequin.validPoses().contains(corpse.pose)
                        && mannequin.getPose() != corpse.pose) {
                    mannequin.setPose(corpse.pose);
                }
            }
            Location torso = torsoOf(body.getLocation(), corpse.bodyYaw);
            if (click != null && click.isValid()) {
                Location want = torso.clone().add(0, 0.15, 0);
                if (click.getLocation().distanceSquared(want) > 0.04) {
                    click.teleport(want);
                }
            }
            if (label != null && label.isValid()) {
                Location want = torso.clone().add(0, 0.45, 0);
                if (label.getLocation().distanceSquared(want) > 0.04) {
                    label.teleport(want);
                }
            }
            // Kick looters who walk away
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof Holder h
                        && h.corpseId.equals(corpse.id)
                        && !withinLootRange(viewer, corpse)) {
                    viewer.closeInventory();
                    viewer.sendMessage(Component.text("Too far to loot.", NamedTextColor.GRAY));
                }
            }
        }
    }

    private void removeCorpse(Corpse corpse, boolean dropLoot) {
        if (corpse == null) {
            return;
        }
        corpses.remove(corpse.id);
        Entity body = Bukkit.getEntity(corpse.bodyEntityId);
        Entity click = Bukkit.getEntity(corpse.clickEntityId);
        Entity label = Bukkit.getEntity(corpse.labelEntityId);
        if (dropLoot) {
            dropRemaining(corpse, body != null ? body.getLocation() : null);
        }
        if (body != null) {
            body.remove();
        }
        if (click != null) {
            click.remove();
        }
        if (label != null) {
            label.remove();
        }
        closeViewers(corpse);
    }

    private void closeViewers(Corpse corpse) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof Holder h
                    && h.corpseId.equals(corpse.id)) {
                viewer.closeInventory();
            }
        }
    }

    /** Despawn: loot vanishes with the body (no ground dumps). */
    private void clearLoot(Corpse corpse) {
        if (corpse != null && corpse.inventory != null) {
            corpse.inventory.clear();
        }
    }

    /**
     * Timed corpse despawn: spawn a zombie that digs up via {@link GroundEmergeListener}
     * (CUSTOM spawn reason triggers the same emerge animation as natural zombies).
     */
    private void spawnDespawnZombie(Location at) {
        if (!plugin.getConfig().getBoolean("corpses.despawn-zombie", true)) {
            return;
        }
        if (at == null || at.getWorld() == null) {
            return;
        }
        World world = at.getWorld();
        Location spawnAt = at.clone();
        // Sleeping mannequin is low; nudge to block feet so emerge finds a surface.
        spawnAt.setY(Math.floor(spawnAt.getY()) + 0.1);
        try {
            world.spawn(spawnAt, Zombie.class, CreatureSpawnEvent.SpawnReason.CUSTOM, zombie -> {
                zombie.setShouldBurnInDay(false);
                zombie.setCanPickupItems(false);
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("Corpse reanimate spawn failed: " + t.getMessage());
        }
    }

    private void dropRemaining(Corpse corpse, Location at) {
        if (at == null || at.getWorld() == null) {
            corpse.inventory.clear();
            return;
        }
        for (ItemStack stack : corpse.inventory.getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                at.getWorld().dropItemNaturally(at, stack);
            }
        }
        corpse.inventory.clear();
    }

    private Corpse corpseOf(Entity entity) {
        if (entity == null) {
            return null;
        }
        String id = entity.getPersistentDataContainer().get(corpseIdKey, PersistentDataType.STRING);
        if (id == null) {
            return null;
        }
        try {
            return corpses.get(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isEmpty(Inventory inv) {
        for (ItemStack stack : inv.getContents()) {
            if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
                return false;
            }
        }
        return true;
    }

    private static ItemStack cloneOrNull(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        return stack.clone();
    }

    private static final class Corpse {
        final UUID id;
        final UUID ownerId;
        final String ownerName;
        final UUID bodyEntityId;
        final UUID clickEntityId;
        final UUID labelEntityId;
        final Inventory inventory;
        final long despawnAtMs;
        final Pose pose;
        /** Death yaw — sleeping body stretches this way from the feet origin. */
        final float bodyYaw;

        Corpse(UUID id, UUID ownerId, String ownerName, UUID bodyEntityId, UUID clickEntityId,
               UUID labelEntityId, Inventory inventory, long despawnAtMs, Pose pose, float bodyYaw) {
            this.id = id;
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.bodyEntityId = bodyEntityId;
            this.clickEntityId = clickEntityId;
            this.labelEntityId = labelEntityId;
            this.inventory = inventory;
            this.despawnAtMs = despawnAtMs;
            this.pose = pose;
            this.bodyYaw = bodyYaw;
        }
    }

    public static final class Holder implements InventoryHolder {
        private final UUID corpseId;
        private Inventory inventory;

        Holder(UUID corpseId) {
            this.corpseId = corpseId;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
