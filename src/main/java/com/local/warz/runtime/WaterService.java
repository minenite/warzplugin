package com.local.warz.runtime;

import com.local.warz.WarzKeys;
import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Campfire;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Dirty water sources, filling empty vessels, campfire boiling, Life Straw.
 */
public final class WaterService implements Listener {
    private static final int LIFE_STRAW_MAX = 5;
    private static final int BOIL_TICKS = 200; // 10s
    private static final int COOK_STEP = 10;
    private static final double FILL_REACH = 4.5;

    private final WarzPlugin plugin;
    private final NamespacedKey[] recipeKeys;
    private BukkitTask cookTask;

    /** Active boils we own (vanilla cook bar is display-only). */
    private final Map<BoilKey, BoilSlot[]> boils = new ConcurrentHashMap<>();
    /** Campfires that just finished — convert any raw ejects nearby. */
    private final Map<BoilKey, Integer> expectFilteredDrop = new ConcurrentHashMap<>();

    public WaterService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.recipeKeys = new NamespacedKey[]{
                WarzKeys.of("boil_water_plastic"),
                WarzKeys.of("boil_water_can"),
                WarzKeys.of("boil_water_glass")
        };
    }

    public void start() {
        registerRecipes();
        if (cookTask != null) {
            cookTask.cancel();
        }
        cookTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickCampfires, COOK_STEP, COOK_STEP);
    }

    public void stop() {
        if (cookTask != null) {
            cookTask.cancel();
            cookTask = null;
        }
        for (NamespacedKey key : recipeKeys) {
            Bukkit.removeRecipe(key);
        }
        boils.clear();
        expectFilteredDrop.clear();
    }

    private void registerRecipes() {
        // Backup path if vanilla ever cooks these; BlockCookEvent forces filtered result.
        ItemFactory items = plugin.items();
        addBoil(recipeKeys[0],
                items.createDrink(DrinkType.UNFILTERED_WATER_BOTTLE, 1),
                items.createDrink(DrinkType.FILTERED_WATER_BOTTLE, 1));
        addBoil(recipeKeys[1],
                items.createDrink(DrinkType.UNFILTERED_WATER_CAN, 1),
                items.createDrink(DrinkType.FILTERED_WATER_CAN, 1));
        addBoil(recipeKeys[2],
                items.createDrink(DrinkType.UNFILTERED_WATER_GLASS, 1),
                items.createDrink(DrinkType.FILTERED_WATER_GLASS, 1));
    }

    private void addBoil(NamespacedKey key, ItemStack input, ItemStack output) {
        Bukkit.removeRecipe(key);
        Bukkit.addRecipe(new CampfireRecipe(
                key,
                output.clone(),
                new RecipeChoice.ExactChoice(input.clone()),
                0.1f,
                BOIL_TICKS
        ));
    }

    /**
     * Place unfiltered water on a campfire to boil. Call before drink handling.
     *
     * @return true if the interact was handled (placed or campfire full).
     */
    public boolean tryPlaceOnCampfire(Player player, ItemStack hand, PlayerInteractEvent event) {
        if (player == null || hand == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return false;
        }
        Block block = event.getClickedBlock();
        if (!isCampfire(block) || !(block.getState() instanceof Campfire campfire)) {
            return false;
        }
        DrinkType type = resolveDirty(hand);
        if (type == null || filteredFor(type) == null) {
            return false;
        }

        int slot = firstEmptySlot(campfire);
        if (slot < 0) {
            event.setCancelled(true);
            player.sendActionBar(ItemFactory.colorize("&cCampfire is full"));
            return true;
        }

        event.setCancelled(true);
        consumeOne(player, hand);

        ItemStack placed = plugin.items().createDrink(type, 1);
        campfire.setItem(slot, placed);
        campfire.setCookTime(slot, 0);
        // Keep total ahead of progress so vanilla never auto-ejects the raw item.
        campfire.setCookTimeTotal(slot, BOIL_TICKS + 40);
        campfire.update(true, false);

        BoilKey key = BoilKey.of(block);
        BoilSlot[] slots = boils.computeIfAbsent(key, k -> new BoilSlot[4]);
        slots[slot] = new BoilSlot(type, 0);

        player.playSound(block.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.35f, 1.6f);
        player.sendActionBar(ItemFactory.colorize("&6Boiling… &7don't drink it raw"));
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockCook(BlockCookEvent event) {
        if (!isCampfire(event.getBlock())) {
            return;
        }
        DrinkType dirty = resolveDirty(event.getSource());
        if (dirty == null) {
            dirty = resolveDirty(event.getResult());
        }
        if (dirty == null) {
            return;
        }
        DrinkType filtered = filteredFor(dirty);
        if (filtered == null) {
            return;
        }
        event.setResult(plugin.items().createDrink(filtered, Math.max(1, event.getResult().getAmount())));
        BoilKey key = BoilKey.of(event.getBlock());
        expectFilteredDrop.put(key, 60);
        clearBoilMatching(key, dirty);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item entity = event.getEntity();
        ItemStack stack = entity.getItemStack();
        DrinkType dirty = resolveDirty(stack);
        if (dirty == null) {
            return;
        }
        DrinkType filtered = filteredFor(dirty);
        if (filtered == null) {
            return;
        }
        BoilKey near = nearestExpecting(entity.getLocation());
        if (near == null) {
            return;
        }
        entity.setItemStack(plugin.items().createDrink(filtered, stack.getAmount()));
    }

    private void tickCampfires() {
        decayExpectations();

        Iterator<Map.Entry<BoilKey, BoilSlot[]>> it = boils.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BoilKey, BoilSlot[]> e = it.next();
            BoilKey key = e.getKey();
            BoilSlot[] slots = e.getValue();
            Block block = key.block();
            if (block == null || !isCampfire(block)) {
                it.remove();
                continue;
            }
            if (!(block.getState() instanceof Campfire state)) {
                it.remove();
                continue;
            }
            Campfire campfire = state;

            boolean steaming = false;

            for (int slot = 0; slot < 4; slot++) {
                BoilSlot boil = slots[slot];
                ItemStack stack = campfire.getItem(slot);
                DrinkType onFire = resolveDirty(stack);

                if (boil == null) {
                    if (onFire != null) {
                        boil = new BoilSlot(onFire, Math.max(0, campfire.getCookTime(slot)));
                        slots[slot] = boil;
                    } else {
                        continue;
                    }
                }

                // Player took the bottle off early — cancel this boil, no free filtered.
                if (stack == null || stack.getType().isAir()) {
                    slots[slot] = null;
                    continue;
                }

                steaming = true;
                if (!isLitCampfire(block)) {
                    continue;
                }

                boil.progress += COOK_STEP;
                if (boil.progress >= BOIL_TICKS) {
                    DrinkType filtered = filteredFor(boil.type);
                    campfire.setItem(slot, null);
                    campfire.setCookTime(slot, 0);
                    campfire.setCookTimeTotal(slot, 0);
                    slots[slot] = null;
                    expectFilteredDrop.put(key, 60);
                    campfire.update(true, false);
                    if (block.getState() instanceof Campfire refreshed) {
                        campfire = refreshed;
                    }
                    if (filtered != null) {
                        popResult(block, plugin.items().createDrink(filtered, 1));
                    }
                } else {
                    campfire.setCookTimeTotal(slot, BOIL_TICKS + 40);
                    campfire.setCookTime(slot, Math.min(boil.progress, BOIL_TICKS - 1));
                    campfire.update(true, false);
                    if (block.getState() instanceof Campfire refreshed) {
                        campfire = refreshed;
                    }
                }
            }

            if (steaming && isLitCampfire(block)) {
                Location steam = block.getLocation().add(0.5, 0.65, 0.5);
                World world = block.getWorld();
                world.spawnParticle(Particle.CLOUD, steam, 3, 0.15, 0.2, 0.15, 0.01);
                world.spawnParticle(Particle.WHITE_SMOKE, steam, 2, 0.1, 0.25, 0.1, 0.005);
            }

            boolean empty = true;
            for (BoilSlot s : slots) {
                if (s != null) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
                it.remove();
            }
        }
    }

    private void decayExpectations() {
        Iterator<Map.Entry<BoilKey, Integer>> exp = expectFilteredDrop.entrySet().iterator();
        while (exp.hasNext()) {
            Map.Entry<BoilKey, Integer> e = exp.next();
            int left = e.getValue() - COOK_STEP;
            if (left <= 0) {
                exp.remove();
            } else {
                e.setValue(left);
            }
        }
    }

    private void clearBoilMatching(BoilKey key, DrinkType dirty) {
        BoilSlot[] slots = boils.get(key);
        if (slots == null) {
            return;
        }
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null && slots[i].type == dirty) {
                slots[i] = null;
            }
        }
    }

    private BoilKey nearestExpecting(Location loc) {
        if (loc.getWorld() == null) {
            return null;
        }
        for (BoilKey key : expectFilteredDrop.keySet()) {
            if (!key.worldId.equals(loc.getWorld().getUID())) {
                continue;
            }
            double dx = (key.x + 0.5) - loc.getX();
            double dy = (key.y + 0.5) - loc.getY();
            double dz = (key.z + 0.5) - loc.getZ();
            if (dx * dx + dy * dy + dz * dz <= 6.25) { // 2.5 blocks
                return key;
            }
        }
        return null;
    }

    private DrinkType resolveDirty(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        DrinkType type = plugin.items().drinkType(stack);
        if (type != null) {
            return type.dirtyWater() ? type : null;
        }
        int cmd = readCmd(stack);
        return switch (cmd) {
            case ItemFactory.CMD_UNFILTERED_WATER_BOTTLE -> DrinkType.UNFILTERED_WATER_BOTTLE;
            case ItemFactory.CMD_UNFILTERED_WATER_CAN -> DrinkType.UNFILTERED_WATER_CAN;
            case ItemFactory.CMD_UNFILTERED_WATER_GLASS -> DrinkType.UNFILTERED_WATER_GLASS;
            default -> null;
        };
    }

    private static int readCmd(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return 0;
        }
        var comp = stack.getItemMeta().getCustomModelDataComponent();
        if (comp == null || comp.getFloats().isEmpty()) {
            return 0;
        }
        return Math.round(comp.getFloats().getFirst());
    }

    private static void popResult(Block block, ItemStack result) {
        if (result == null) {
            return;
        }
        Location at = block.getLocation().add(0.5, 0.85, 0.5);
        Item dropped = block.getWorld().dropItem(at, result);
        dropped.setPickupDelay(10);
        dropped.setVelocity(new Vector(
                (Math.random() - 0.5) * 0.12,
                0.22,
                (Math.random() - 0.5) * 0.12));
        block.getWorld().playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.8f);
        block.getWorld().playSound(at, Sound.ENTITY_ITEM_PICKUP, 0.35f, 0.7f);
    }

    private static DrinkType filteredFor(DrinkType dirty) {
        if (dirty == null) {
            return null;
        }
        return switch (dirty) {
            case UNFILTERED_WATER_BOTTLE -> DrinkType.FILTERED_WATER_BOTTLE;
            case UNFILTERED_WATER_CAN -> DrinkType.FILTERED_WATER_CAN;
            case UNFILTERED_WATER_GLASS -> DrinkType.FILTERED_WATER_GLASS;
            default -> null;
        };
    }

    private static int firstEmptySlot(Campfire campfire) {
        for (int i = 0; i < 4; i++) {
            ItemStack stack = campfire.getItem(i);
            if (stack == null || stack.getType().isAir()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isCampfire(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        return type == Material.CAMPFIRE || type == Material.SOUL_CAMPFIRE;
    }

    private static boolean isLitCampfire(Block block) {
        if (!isCampfire(block)) {
            return false;
        }
        if (block.getBlockData() instanceof org.bukkit.block.data.type.Campfire data) {
            return data.isLit();
        }
        return true;
    }

    public boolean tryHandleWaterInteract(Player player, ItemStack hand, PlayerInteractEvent event) {
        if (player == null || hand == null) {
            return false;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return false;
        }
        Block water = resolveWater(player, event);
        if (water == null) {
            return false;
        }

        ItemFactory items = plugin.items();

        if (items.isLifeStraw(hand)) {
            event.setCancelled(true);
            drinkWithLifeStraw(player, hand);
            return true;
        }
        if (items.isPlasticBottle(hand)) {
            event.setCancelled(true);
            fillVessel(player, hand, DrinkType.UNFILTERED_WATER_BOTTLE);
            return true;
        }
        if (items.isEmptyCan(hand)) {
            event.setCancelled(true);
            fillVessel(player, hand, DrinkType.UNFILTERED_WATER_CAN);
            return true;
        }
        if (items.isEmptyGlassBottle(hand)) {
            event.setCancelled(true);
            fillVessel(player, hand, DrinkType.UNFILTERED_WATER_GLASS);
            return true;
        }
        if (hand.getType().isAir() || hand.getAmount() <= 0) {
            event.setCancelled(true);
            drinkFromSource(player, true);
            return true;
        }
        return false;
    }

    private void fillVessel(Player player, ItemStack hand, DrinkType filled) {
        consumeOne(player, hand);
        ItemStack out = plugin.items().createDrink(filled, 1);
        var left = player.getInventory().addItem(out);
        left.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
        player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_FILL, 1f, 1f);
        player.sendMessage(ItemFactory.colorize("&7Filled with &eunfiltered water&7. Boil on a &6campfire&7."));
    }

    private void drinkWithLifeStraw(Player player, ItemStack hand) {
        int uses = plugin.items().lifeStrawUses(hand);
        boolean safe = uses > 0;
        if (safe) {
            uses -= 1;
            plugin.items().setLifeStrawUses(hand, uses);
            player.getInventory().setItemInMainHand(hand);
            drinkFromSource(player, false);
            player.sendMessage(ItemFactory.colorize(
                    "&bLife Straw &7— clean sip. &f" + uses + "&7/" + LIFE_STRAW_MAX + " uses left."));
        } else {
            drinkFromSource(player, true);
            player.sendMessage(ItemFactory.colorize(
                    "&cLife Straw is spent — that water looks foul…"));
        }
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 0.9f, 1.25f);
    }

    private void drinkFromSource(Player player, boolean infect) {
        if (plugin.thirst() != null) {
            plugin.thirst().addThirst(player, infect ? 8.0 : 18.0);
        }
        if (infect && plugin.infection() != null) {
            plugin.infection().infect(player, "You drank untreated water.");
        } else if (!infect) {
            player.sendActionBar(ItemFactory.colorize("&bClean water"));
        }
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1f, 0.95f);
    }

    private Block resolveWater(Player player, PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Block clicked = event.getClickedBlock();
            if (isDrinkableWater(clicked)) {
                return clicked;
            }
            Block relative = clicked.getRelative(event.getBlockFace());
            if (isDrinkableWater(relative)) {
                return relative;
            }
        }
        RayTraceResult hit = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                FILL_REACH,
                FluidCollisionMode.ALWAYS,
                true);
        if (hit != null && hit.getHitBlock() != null && isDrinkableWater(hit.getHitBlock())) {
            return hit.getHitBlock();
        }
        return null;
    }

    public static boolean isDrinkableWater(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
            return true;
        }
        if (type == Material.WATER_CAULDRON) {
            if (block.getBlockData() instanceof Levelled levelled) {
                return levelled.getLevel() > 0;
            }
            return true;
        }
        return false;
    }

    private static void consumeOne(Player player, ItemStack hand) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        int amt = hand.getAmount();
        if (amt <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(amt - 1);
        }
    }

    private static final class BoilSlot {
        final DrinkType type;
        int progress;

        BoilSlot(DrinkType type, int progress) {
            this.type = type;
            this.progress = progress;
        }
    }

    private static final class BoilKey {
        final UUID worldId;
        final int x;
        final int y;
        final int z;

        BoilKey(UUID worldId, int x, int y, int z) {
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        static BoilKey of(Block block) {
            return new BoilKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        Block block() {
            World world = Bukkit.getWorld(worldId);
            return world == null ? null : world.getBlockAt(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BoilKey other)) {
                return false;
            }
            return x == other.x && y == other.y && z == other.z
                    && Objects.equals(worldId, other.worldId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(worldId, x, y, z);
        }
    }
}
