package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thirst vital (0–100): drains while sprinting; restored by drinks.
 * Sodas: speed 1 min then movement crash 30s (attribute — not SLOWNESS / gun ADS).
 * Water / Gatorade: gradual hydration. Empty cans stack to 64 on pickup; throw to drop.
 */
public final class ThirstService implements Listener {
    public static final double MAX_THIRST = 100.0;
    public static final double THIRST_ORANGE = 60.0;
    public static final double THIRST_CRITICAL = 30.0;

    private static final double PASSIVE_DRAIN_PER_SEC = 0.015;
    private static final double SPRINT_DRAIN_PER_SEC = 0.18;
    private static final int SODA_BOOST_TICKS = 20 * 60;   // 1 min
    private static final int SODA_CRASH_TICKS = 20 * 30;   // 30s
    private static final double SODA_CRASH_SCALAR = -0.12; // slight slow
    private static final NamespacedKey SODA_CRASH_KEY = new NamespacedKey("warz", "soda_crash");

    private final WarzPlugin plugin;
    private final Map<UUID, Double> thirst = new ConcurrentHashMap<>();
    private final Map<UUID, Double> pendingHydration = new ConcurrentHashMap<>();
    private final Map<UUID, Double> hydratePerSec = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> sodaBoostLeft = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> sodaCrashLeft = new ConcurrentHashMap<>();
    /** Debounce interact+consume double-fire. */
    private final Map<UUID, Long> lastFoodMs = new ConcurrentHashMap<>();
    /** Per-player per-food cooldown (golden apple ~100s). */
    private final Map<UUID, Map<String, Long>> foodCooldownUntil = new ConcurrentHashMap<>();
    private BukkitTask task;
    private int secCounter;

    public ThirstService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) {
            task.cancel();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            ensureThirst(player);
            rematerializeEmptyCans(player);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeCrash(player);
        }
        thirst.clear();
        pendingHydration.clear();
        hydratePerSec.clear();
        sodaBoostLeft.clear();
        sodaCrashLeft.clear();
    }

    public double thirst(Player player) {
        if (player == null) {
            return MAX_THIRST;
        }
        return ensureThirst(player);
    }

    /** Add thirst (clamped). Used by water sources / Life Straw. */
    public void addThirst(Player player, double amount) {
        if (player == null || amount == 0) {
            return;
        }
        double t = ensureThirst(player) + amount;
        thirst.put(player.getUniqueId(), clamp(t, 0.0, MAX_THIRST));
        applyExpBar(player);
    }

    private double ensureThirst(Player player) {
        return thirst.computeIfAbsent(player.getUniqueId(), id -> MAX_THIRST);
    }

    /** XP bar fill = thirst 0–100%. Level number stays the chest reloot timer. */
    public void applyExpBar(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        float frac = (float) (ensureThirst(player) / MAX_THIRST);
        player.setExp(Math.max(0f, Math.min(0.999f, frac)));
    }

    private void tick() {
        secCounter++;
        boolean second = secCounter % 20 == 0;

        for (Player player : Bukkit.getOnlinePlayers()) {
            tickSoda(player);
            if (!second) {
                continue;
            }
            tickThirstSecond(player);
            applyExpBar(player);
        }
    }

    private void tickSoda(Player player) {
        UUID id = player.getUniqueId();
        int boost = sodaBoostLeft.getOrDefault(id, 0);
        if (boost > 0) {
            sodaBoostLeft.put(id, boost - 1);
            if (boost % 40 == 0 || boost == SODA_BOOST_TICKS) {
                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.SPEED, 45, 0, false, true, true));
            }
            if (boost - 1 <= 0) {
                sodaBoostLeft.remove(id);
                sodaCrashLeft.put(id, SODA_CRASH_TICKS);
                applyCrash(player);
                player.sendActionBar(ItemFactory.colorize("&cSugar crash…"));
            }
            return;
        }
        int crash = sodaCrashLeft.getOrDefault(id, 0);
        if (crash > 0) {
            sodaCrashLeft.put(id, crash - 1);
            if (crash % 20 == 0) {
                applyCrash(player); // keep modifier present
            }
            if (crash - 1 <= 0) {
                sodaCrashLeft.remove(id);
                removeCrash(player);
            }
        }
    }

    private void tickThirstSecond(Player player) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            thirst.put(player.getUniqueId(), MAX_THIRST);
            pendingHydration.remove(player.getUniqueId());
            hydratePerSec.remove(player.getUniqueId());
            return;
        }
        if (player.isDead()) {
            return;
        }

        double t = ensureThirst(player);

        // Gradual hydration from water / gatorade
        Double pending = pendingHydration.get(player.getUniqueId());
        if (pending != null && pending > 0) {
            double rate = hydratePerSec.getOrDefault(player.getUniqueId(), 1.0);
            double step = Math.min(pending, rate);
            t += step;
            pending -= step;
            if (pending <= 0.01) {
                pendingHydration.remove(player.getUniqueId());
                hydratePerSec.remove(player.getUniqueId());
            } else {
                pendingHydration.put(player.getUniqueId(), pending);
            }
        }

        // Drain
        t -= PASSIVE_DRAIN_PER_SEC;
        if (player.isSprinting()) {
            t -= SPRINT_DRAIN_PER_SEC;
        }

        t = clamp(t, 0.0, MAX_THIRST);
        thirst.put(player.getUniqueId(), t);
    }

    /* -------------------- consume / throw -------------------- */

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
        ItemStack hand = player.getInventory().getItemInMainHand();

        // Unfiltered water → campfire boil (must beat drink / trash handlers).
        if (plugin.water() != null && plugin.water().tryPlaceOnCampfire(player, hand, event)) {
            return;
        }

        // Water fill / Life Straw / drink from source before trash-throw.
        if (plugin.water() != null && plugin.water().tryHandleWaterInteract(player, hand, event)) {
            return;
        }

        WarzFoodType food = plugin.items().foodType(hand);
        if (food != null) {
            event.setCancelled(true);
            try {
                player.clearActiveItem();
            } catch (Throwable ignored) {
            }
            eat(player, hand, food);
            return;
        }

        if (plugin.items().isThrowableTrash(hand)) {
            event.setCancelled(true);
            throwTrash(player, hand);
            return;
        }

        DrinkType drink = plugin.items().drinkType(hand);
        if (drink == null) {
            return;
        }
        event.setCancelled(true);
        drink(player, hand, drink);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (plugin.items().isDrink(item)) {
            event.setCancelled(true);
            return;
        }
        if (!plugin.items().isFood(item)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        try {
            player.clearActiveItem();
        } catch (Throwable ignored) {
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        WarzFoodType food = plugin.items().foodType(hand);
        if (food == null) {
            food = plugin.items().foodType(item);
            hand = item;
        }
        if (food != null) {
            eat(player, hand, food);
        }
    }

    private void drink(Player player, ItemStack hand, DrinkType drink) {
        consumeOne(player, hand);
        giveOrDrop(player, plugin.items().createDrinkRemnant(drink));

        double t = ensureThirst(player);
        if (drink.instantHydration > 0) {
            t = clamp(t + drink.instantHydration, 0.0, MAX_THIRST);
        }
        if (drink.dehydrateAmount > 0) {
            // Alcohol dries you out some, but never all the way
            t = Math.max(DrinkType.ALCOHOL_THIRST_FLOOR, t - drink.dehydrateAmount);
        }
        thirst.put(player.getUniqueId(), clamp(t, 0.0, MAX_THIRST));

        if (drink.pendingHydration > 0 && drink.hydrateSeconds > 0) {
            pendingHydration.merge(player.getUniqueId(), drink.pendingHydration, Double::sum);
            double rate = drink.pendingHydration / drink.hydrateSeconds;
            hydratePerSec.merge(player.getUniqueId(), rate, Math::max);
        }

        for (PotionEffect effect : drink.effects) {
            player.addPotionEffect(effect);
        }

        if (drink.dirtyWater() && plugin.infection() != null) {
            plugin.infection().infect(player, "That water wasn't clean.");
        }

        if (drink.sodaBoost) {
            sodaCrashLeft.remove(player.getUniqueId());
            removeCrash(player);
            sodaBoostLeft.put(player.getUniqueId(), SODA_BOOST_TICKS);
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.SPEED, SODA_BOOST_TICKS, 0, false, true, true));
            player.sendMessage(ItemFactory.colorize(drink.displayName + " &7— &asugar rush!"));
        } else if (drink.alcohol()) {
            player.sendMessage(ItemFactory.colorize(drink.displayName + " &7— &6cheers&7. &8(a bit dehydrating)"));
        } else if (drink.dirtyWater()) {
            player.sendMessage(ItemFactory.colorize("&eDrank " + drink.displayName + "&e… &8feels wrong."));
        } else {
            player.sendMessage(ItemFactory.colorize("&bDrank " + drink.displayName + "&b."));
        }
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1f, 1.1f);
    }

    private void eat(Player player, ItemStack hand, WarzFoodType food) {
        if (food == null || hand == null || hand.getType().isAir()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastFoodMs.get(player.getUniqueId());
        if (last != null && now - last < 250L) {
            return;
        }

        Map<String, Long> until = foodCooldownUntil.computeIfAbsent(
                player.getUniqueId(), k -> new ConcurrentHashMap<>());
        Long ready = until.get(food.id);
        if (ready != null && now < ready) {
            long leftSec = Math.max(1L, (ready - now + 999L) / 1000L);
            player.sendMessage(ItemFactory.colorize(
                    food.displayName + " &7cooling down &8(" + leftSec + "s)"));
            return;
        }

        lastFoodMs.put(player.getUniqueId(), now);
        if (food.cooldownMs > 250L) {
            until.put(food.id, now + food.cooldownMs);
        }

        consumeOne(player, hand);
        if (food.leavesEmptyCan) {
            giveOrDrop(player, plugin.items().createEmptyCan(1));
        }

        if (food.fullRestore) {
            setHealth(player, 20.0);
            player.setFoodLevel(20);
            player.setSaturation(20f);
            player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1f, 1.15f);
            return;
        }

        if (food.instantHealthIi()) {
            player.removePotionEffect(PotionEffectType.ABSORPTION);
            addHealth(player, 8.0);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.7f, 1.2f);
            return;
        }

        addHealth(player, food.healHp);
        player.setFoodLevel(Math.min(20, player.getFoodLevel() + food.hunger));
        player.setSaturation(Math.min(20f, player.getSaturation() + food.hunger));
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EAT, 1f, 1.05f);
    }

    private static void addHealth(Player player, double amount) {
        if (amount <= 0) {
            return;
        }
        setHealth(player, player.getHealth() + amount);
    }

    private static void setHealth(Player player, double health) {
        double max = 20.0;
        var attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            max = attr.getValue();
        }
        player.setHealth(Math.max(0.0, Math.min(max, health)));
    }

    private void throwTrash(Player player, ItemStack hand) {
        boolean broken = plugin.items().isBrokenGlassBottle(hand);
        boolean emptyBottle = plugin.items().isEmptyGlassBottle(hand);
        boolean plastic = plugin.items().isPlasticBottle(hand);
        ItemStack visual = hand.clone();
        visual.setAmount(1);
        consumeOne(player, hand);

        // Set the item before the spawn packet so clients see the can/bottle, not a snowball.
        org.bukkit.util.Vector vel = player.getLocation().getDirection().normalize().multiply(1.25);
        vel.setY(vel.getY() + 0.12);
        Snowball ball = player.launchProjectile(Snowball.class, vel, thrown -> {
            thrown.setItem(visual);
            thrown.setGravity(true);
            var pdc = thrown.getPersistentDataContainer();
            if (broken) {
                pdc.set(plugin.items().brokenGlassKey(), PersistentDataType.BYTE, (byte) 1);
            } else if (emptyBottle) {
                pdc.set(plugin.items().emptyBottleKey(), PersistentDataType.BYTE, (byte) 1);
            } else if (plastic) {
                pdc.set(plugin.items().plasticBottleKey(), PersistentDataType.BYTE, (byte) 1);
            } else {
                pdc.set(plugin.items().emptyCanKey(), PersistentDataType.BYTE, (byte) 1);
            }
        });
        if (broken) {
            player.playSound(player.getLocation(), Sound.BLOCK_GLASS_HIT, 0.75f, 1.35f);
        } else if (emptyBottle) {
            player.playSound(player.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 0.75f, 1.15f);
        } else if (plastic) {
            player.playSound(player.getLocation(), Sound.ENTITY_CHICKEN_EGG, 0.95f, 0.85f);
        } else {
            player.playSound(player.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 0.8f, 0.9f);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (ball != null && ball.isValid()) {
                ball.remove();
            }
        }, 20L * 8);
    }

    private boolean isTrashProjectile(org.bukkit.entity.Entity entity) {
        if (!(entity instanceof Snowball ball)) {
            return false;
        }
        var pdc = ball.getPersistentDataContainer();
        Byte can = pdc.get(plugin.items().emptyCanKey(), PersistentDataType.BYTE);
        if (can != null && can == (byte) 1) {
            return true;
        }
        Byte emptyBottle = pdc.get(plugin.items().emptyBottleKey(), PersistentDataType.BYTE);
        if (emptyBottle != null && emptyBottle == (byte) 1) {
            return true;
        }
        Byte plastic = pdc.get(plugin.items().plasticBottleKey(), PersistentDataType.BYTE);
        if (plastic != null && plastic == (byte) 1) {
            return true;
        }
        Byte glass = pdc.get(plugin.items().brokenGlassKey(), PersistentDataType.BYTE);
        return glass != null && glass == (byte) 1;
    }

    private static void playPlasticPopNearby(Location hit) {
        if (hit.getWorld() == null) {
            return;
        }
        double rangeSq = 32.0 * 32.0;
        for (Player p : hit.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(hit) <= rangeSq) {
                p.playSound(hit, Sound.ENTITY_CHICKEN_EGG, org.bukkit.SoundCategory.PLAYERS, 1.0f, 0.8f);
            }
        }
    }

    /** Glass smash heard by players within ~40 blocks. */
    private static void playGlassBreakNearby(Location hit) {
        if (hit.getWorld() == null) {
            return;
        }
        double rangeSq = 40.0 * 40.0;
        for (Player p : hit.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(hit) <= rangeSq) {
                p.playSound(hit, Sound.BLOCK_GLASS_BREAK, org.bukkit.SoundCategory.BLOCKS, 1.6f, 1.05f);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCanHit(ProjectileHitEvent event) {
        if (!isTrashProjectile(event.getEntity())) {
            return;
        }
        event.setCancelled(true);
        Location hit = event.getEntity().getLocation();
        var pdc = event.getEntity().getPersistentDataContainer();
        ItemStack brokenVisual = plugin.items().createBrokenGlassBottle(1);
        boolean hitPlayer = event.getHitEntity() instanceof Player;

        if (pdc.has(plugin.items().brokenGlassKey(), PersistentDataType.BYTE)) {
            // Already-shattered bottle: smash + despawn (bleed applied in onCanDamage)
            playGlassBreakNearby(hit);
            hit.getWorld().spawnParticle(org.bukkit.Particle.ITEM,
                    hit, 10, 0.18, 0.12, 0.18, 0.05, brokenVisual);
            if (event.getHitEntity() instanceof Player victim && plugin.medical() != null) {
                plugin.medical().inflictBleed(victim, MedicalService.BleedSeverity.NORMAL);
            }
        } else if (pdc.has(plugin.items().emptyBottleKey(), PersistentDataType.BYTE)) {
            // Intact empty bottle: smash into a broken bottle on the ground
            playGlassBreakNearby(hit);
            hit.getWorld().spawnParticle(org.bukkit.Particle.ITEM,
                    hit, 10, 0.18, 0.12, 0.18, 0.05, brokenVisual);
            // Don't leave a pickup on someone if it hit a player — just shatter
            if (!hitPlayer) {
                org.bukkit.entity.Item dropped = hit.getWorld().dropItem(hit, brokenVisual.clone());
                dropped.setPickupDelay(15);
                dropped.setVelocity(new org.bukkit.util.Vector(0, 0.08, 0));
            }
        } else if (pdc.has(plugin.items().plasticBottleKey(), PersistentDataType.BYTE)) {
            playPlasticPopNearby(hit);
            ItemStack plastic = plugin.items().createPlasticBottle(1);
            hit.getWorld().spawnParticle(org.bukkit.Particle.ITEM,
                    hit, 6, 0.12, 0.08, 0.12, 0.03, plastic);
        } else {
            hit.getWorld().playSound(hit, Sound.BLOCK_METAL_HIT, 0.45f, 1.4f);
            ItemStack splash = plugin.items().createEmptyCan(1);
            hit.getWorld().spawnParticle(org.bukkit.Particle.ITEM,
                    hit, 6, 0.12, 0.08, 0.12, 0.03, splash);
            if (!hitPlayer) {
                org.bukkit.entity.Item dropped = hit.getWorld().dropItem(hit, splash.clone());
                dropped.setPickupDelay(8);
                dropped.setVelocity(new org.bukkit.util.Vector(0, 0.08, 0));
            }
        }
        event.getEntity().remove();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCanDamage(EntityDamageByEntityEvent event) {
        if (!isTrashProjectile(event.getDamager())) {
            return;
        }
        event.setCancelled(true);
        if (event.getDamager().getPersistentDataContainer().has(
                plugin.items().brokenGlassKey(), PersistentDataType.BYTE)
                && event.getEntity() instanceof Player victim
                && plugin.medical() != null) {
            plugin.medical().inflictBleed(victim, MedicalService.BleedSeverity.NORMAL);
            playGlassBreakNearby(victim.getLocation());
        }
        event.getDamager().remove();
    }

    private static void applyCrash(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr == null) {
            return;
        }
        if (attr.getModifier(SODA_CRASH_KEY) != null) {
            return;
        }
        attr.addTransientModifier(new AttributeModifier(
                SODA_CRASH_KEY, SODA_CRASH_SCALAR, AttributeModifier.Operation.ADD_SCALAR));
    }

    private static void removeCrash(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr != null) {
            attr.removeModifier(SODA_CRASH_KEY);
        }
    }

    private static void consumeOne(Player player, ItemStack hand) {
        int amt = hand.getAmount();
        if (amt <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(amt - 1);
        }
    }

    private void giveOrDrop(Player player, ItemStack stack) {
        HashMap<Integer, ItemStack> left = player.getInventory().addItem(stack);
        for (ItemStack drop : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        if (plugin.items().isEmptyCan(stack)) {
            rematerializeEmptyCans(player);
        }
    }

    /**
     * Ground empty cans often fail vanilla {@code isSimilar} (CMD / max-stack
     * components differ), so merge by empty_can identity up to 64.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEmptyCanPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (plugin.restraints() != null && plugin.restraints().isRestrained(player)) {
            return;
        }
        ItemFactory items = plugin.items();
        if (items == null) {
            return;
        }
        Item entity = event.getItem();
        ItemStack incoming = entity.getItemStack();
        if (!items.isEmptyCan(incoming)) {
            return;
        }
        int amount = Math.max(1, incoming.getAmount());
        event.setCancelled(true);
        entity.remove();
        absorbEmptyCans(player, amount);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.2f, 1.0f);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEmptyCanSpawn(ItemSpawnEvent event) {
        ItemFactory items = plugin.items();
        if (items == null) {
            return;
        }
        ItemStack stack = event.getEntity().getItemStack();
        if (!items.isEmptyCan(stack)) {
            return;
        }
        int amt = Math.max(1, Math.min(ItemFactory.EMPTY_CAN_STACK_MAX, stack.getAmount()));
        event.getEntity().setItemStack(items.createEmptyCan(amt));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEmptyCanClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemFactory items = plugin.items();
        if (items == null) {
            return;
        }
        ClickType click = event.getClick();
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            if (items.isEmptyCan(current) || items.isEmptyCan(cursor)) {
                Bukkit.getScheduler().runTask(plugin, () -> rematerializeEmptyCans(player));
            }
            return;
        }
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        if (!items.isEmptyCan(cursor) || !items.isEmptyCan(current)) {
            return;
        }
        int cursorAmt = cursor.getAmount();
        int slotAmt = current.getAmount();
        int max = ItemFactory.EMPTY_CAN_STACK_MAX;
        if (click == ClickType.LEFT) {
            int space = max - slotAmt;
            if (space <= 0) {
                return;
            }
            int move = Math.min(space, cursorAmt);
            event.setCancelled(true);
            event.setCurrentItem(items.createEmptyCan(slotAmt + move));
            int left = cursorAmt - move;
            player.setItemOnCursor(left <= 0 ? null : items.createEmptyCan(left));
        } else {
            if (slotAmt >= max) {
                return;
            }
            event.setCancelled(true);
            event.setCurrentItem(items.createEmptyCan(slotAmt + 1));
            int left = cursorAmt - 1;
            player.setItemOnCursor(left <= 0 ? null : items.createEmptyCan(left));
        }
    }

    private void absorbEmptyCans(Player player, int amount) {
        ItemFactory items = plugin.items();
        if (items == null || amount <= 0) {
            return;
        }
        ItemStack extra = items.createEmptyCan(amount);
        for (var leftover : player.getInventory().addItem(extra).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        rematerializeEmptyCans(player);
    }

    /** Rewrite empty cans to canonical stacks and merge matching piles. */
    private void rematerializeEmptyCans(Player player) {
        ItemFactory items = plugin.items();
        if (items == null || player == null) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        int total = 0;
        List<Integer> slots = new ArrayList<>();
        boolean dirty = false;
        for (int i = 0; i < contents.length; i++) {
            if (i >= 36 && i <= 39) {
                continue;
            }
            ItemStack stack = contents[i];
            if (!items.isEmptyCan(stack)) {
                continue;
            }
            total += Math.max(1, stack.getAmount());
            slots.add(i);
            contents[i] = null;
            dirty = true;
        }
        if (!dirty) {
            return;
        }
        int left = total;
        int di = 0;
        while (left > 0) {
            int n = Math.min(ItemFactory.EMPTY_CAN_STACK_MAX, left);
            ItemStack fresh = items.createEmptyCan(n);
            int slot;
            if (di < slots.size()) {
                slot = slots.get(di++);
            } else {
                slot = firstEmptyContentSlot(contents);
                if (slot < 0) {
                    player.getWorld().dropItemNaturally(player.getLocation(), fresh);
                    left -= n;
                    continue;
                }
            }
            contents[slot] = fresh;
            left -= n;
        }
        inv.setContents(contents);
    }

    private static int firstEmptyContentSlot(ItemStack[] contents) {
        for (int i = 0; i < contents.length; i++) {
            if (i >= 36 && i <= 39) {
                continue;
            }
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir()) {
                return i;
            }
        }
        return -1;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    public void clear(Player player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        thirst.put(id, MAX_THIRST);
        pendingHydration.remove(id);
        hydratePerSec.remove(id);
        sodaBoostLeft.remove(id);
        sodaCrashLeft.remove(id);
        lastFoodMs.remove(id);
        foodCooldownUntil.remove(id);
        removeCrash(player);
        player.removePotionEffect(PotionEffectType.SPEED);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ensureThirst(player);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            rematerializeEmptyCans(player);
            applyExpBar(player);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
        thirst.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        clear(event.getPlayer());
        Bukkit.getScheduler().runTask(plugin, () -> applyExpBar(event.getPlayer()));
    }
}
