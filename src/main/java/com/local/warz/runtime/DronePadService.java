package com.local.warz.runtime;

import com.local.warz.WarzKeys;
import com.local.warz.gui.ChestInventories;
import net.minenite.warzplugin.WarzPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.Sound;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Placed MQ-9 pads: facing yaw, rocket bay, radiolink → seat pairing, parked airframe draw.
 */
public final class DronePadService implements Listener {
    public static final String ROUND_HP = "rocket_hp";
    public static final String ROUND_AP = "rocket_ap";
    public static final String ROUND_HE = "rocket_he";
    /** Heat-seeking air-to-air — guides onto other UAVs. */
    public static final String ROUND_AA = "rocket_aa";
    /** AGM-114R9X — kinetic “flying ginsu”, no explosive warhead. */
    public static final String ROUND_R9X = "rocket_r9x";
    public static final String ROUND_MAC = "rocket_mac";
    public static final String ROUND_ROMEO = "rocket_romeo";
    public static final String ROUND_JAGM = "rocket_jagm";
    public static final String ROUND_VIPER = "gbu_viper";
    public static final String ROUND_SGM = "gbu_sgm";
    public static final String ROUND_SDB = "gbu_sdb";
    public static final String ROUND_STORM = "gbu_storm";
    public static final String ROUND_PAVEWAY = "gbu_paveway";
    public static final String ROUND_SONAR = "gbu_sonar";
    public static final String ROUND_AIM9X = "aim9x";
    private static final int DEFAULT_HP_ROCKETS = 3;
    private static final int DEFAULT_FUEL_CANS = BigDroneType.MQ9.maxFuelCans();
    /** Fuel units per Jet Fuel Can (~20 min flight at 1 unit/tick for 10 cans / 600 gal). */
    public static final int FUEL_UNITS_PER_CAN = 2400;
    private static final int BAY_SIZE = 27;
    /** X-37B cargo bay GUI (27 cargo + service row). */
    private static final int CARGO_BAY_SIZE = 54;
    /** Free cargo slots for X-37B (indices 0..CARGO_SLOTS-1). */
    public static final int CARGO_SLOTS = 27;
    private static final int LINK_GUI_SIZE = 54;
    private static final int PRESET_SLOT_STRIKE = 18;
    private static final int PRESET_SLOT_RECON = 19;
    private static final int PRESET_SLOT_BALANCED = 20;
    /** Jet fuel tank gauge (add cans here — not loose stacks in the bay). */
    private static final int FUEL_SLOT = 23;
    /** Flare cartridge magazine (take / restock, max {@link #FLARE_MAX}). */
    private static final int FLARE_SLOT = 24;
    /** Hull Metal readout / repair (add-only). */
    private static final int METAL_SLOT = 25;
    /** Take this stack to pack the parked MQ-9 into inventory. */
    private static final int SALVAGE_SLOT = 26;
    private static final int CARGO_FUEL_SLOT = 48;
    private static final int CARGO_FLARE_SLOT = 49;
    private static final int CARGO_METAL_SLOT = 50;
    private static final int CARGO_SALVAGE_SLOT = 51;
    /** Visible airframe integrity stack in the bay (maps to {@link BigDroneService#STRUCTURE_MAX}). */
    public static final int METAL_MAX = 100;
    /** Countermeasure flare magazine size. */
    public static final int FLARE_MAX = 3;
    public static final NamespacedKey BAY_PRESET_KEY = WarzKeys.of("drone_bay_preset");
    public static final NamespacedKey BAY_SALVAGE_KEY = WarzKeys.of("drone_bay_salvage");
    public static final NamespacedKey BAY_METAL_KEY = WarzKeys.of("drone_bay_metal");
    public static final NamespacedKey BAY_FUEL_KEY = WarzKeys.of("drone_bay_fuel");
    /** Clickable hull for empty-hand / any-item RMB (air clicks are unreliable). */
    public static final NamespacedKey PAD_INTERACT_KEY = WarzKeys.of("drone_pad_interact");

    private final WarzPlugin plugin;
    private final File file;
    private final Map<String, ParkedPad> byBlock = new ConcurrentHashMap<>();
    private final Map<UUID, ParkedPad> byId = new ConcurrentHashMap<>();
    /** player → pad id awaiting seat link */
    private final Map<UUID, UUID> pendingLink = new ConcurrentHashMap<>();
    /** seatKey → pad id */
    private final Map<String, UUID> seatToPad = new ConcurrentHashMap<>();

    public DronePadService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "drone_pads.yml");
        load();
    }

    public Optional<ParkedPad> padAt(Block block) {
        if (block == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byBlock.get(blockKey(block)));
    }

    public Optional<ParkedPad> padById(UUID id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public Optional<ParkedPad> padForSeat(String seatKey) {
        if (seatKey == null) {
            return Optional.empty();
        }
        UUID id = seatToPad.get(seatKey);
        return padById(id);
    }

    /** Re-assert seat↔pad radiolink (e.g. after seat-kill orphan handoff). */
    public void ensureSeatLink(UUID padId, String seatKey) {
        if (padId == null || seatKey == null || seatKey.isEmpty()) {
            return;
        }
        ParkedPad pad = byId.get(padId);
        if (pad == null) {
            return;
        }
        if (pad.seatKey != null && !seatKey.equals(pad.seatKey)) {
            seatToPad.remove(pad.seatKey, pad.id);
        }
        UUID old = seatToPad.put(seatKey, pad.id);
        if (old != null && !old.equals(pad.id)) {
            ParkedPad other = byId.get(old);
            if (other != null && seatKey.equals(other.seatKey)) {
                other.seatKey = null;
            }
        }
        pad.seatKey = seatKey;
        persistPads();
    }

    public boolean isOccupied(UUID padId) {
        return padId != null && plugin.bigDrone() != null && plugin.bigDrone().isParkedPadInUse(padId);
    }

    public boolean hasPads() {
        return !byId.isEmpty();
    }

    public int padCount() {
        return byId.size();
    }

    /** Migrate legacy lodestone pads (chunk PDC only) into parked pad data. */
    public ParkedPad ensurePad(Block block, UUID owner, Player facingHint) {
        Optional<ParkedPad> existing = padAt(block);
        if (existing.isPresent()) {
            return existing.get();
        }
        float yaw = facingHint != null ? yawFacingPlayer(block, facingHint) : 0f;
        UUID own = owner != null ? owner : (facingHint != null ? facingHint.getUniqueId() : UUID.randomUUID());
        ParkedPad pad = new ParkedPad(UUID.randomUUID(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), yaw, own);
        initDefaultCargo(pad);
        index(pad);
        ensureInteractEntity(pad);
        save();
        return pad;
    }

    /** Parked pads to draw (skip if an operator is flying that airframe). */
    public List<ParkedPad> parkedForVis() {
        List<ParkedPad> out = new ArrayList<>();
        for (ParkedPad pad : byId.values()) {
            if (!isOccupied(pad.id)) {
                out.add(pad);
            }
        }
        return out;
    }

    public ParkedPad createPad(Player player, Block block) {
        return createPad(player, block, null);
    }

    public ParkedPad createPad(Player player, Block block, ItemStack droneItem) {
        float yaw = yawFacingPlayer(block, player);
        ParkedPad pad = new ParkedPad(UUID.randomUUID(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), yaw, player.getUniqueId());
        BigDroneType type = droneItem != null && plugin.items().isBigDroneItem(droneItem)
                ? plugin.items().droneType(droneItem) : BigDroneType.MQ9;
        pad.typeId = type.id();
        if (droneItem != null && plugin.items().isBigDroneItem(droneItem)) {
            if (type.cargoBay()) {
                pad.cargo.clear();
                for (ItemStack it : plugin.items().droneBayCargo(droneItem)) {
                    if (pad.cargo.size() >= CARGO_SLOTS) {
                        break;
                    }
                    if (it != null && !it.getType().isAir()) {
                        pad.cargo.add(it.clone());
                    }
                }
            } else {
                List<String> fromItem = plugin.items().droneCargoRockets(droneItem);
                if (fromItem.isEmpty()) {
                    initDefaultRockets(pad);
                } else {
                    pad.rockets.addAll(capRockets(fromItem, type.missileSlots()));
                }
            }
            pad.fuelCans = plugin.items().droneCargoFuelCans(droneItem);
            if (pad.fuelCans <= 0) {
                pad.fuelCans = type.defaultFuelCans();
            }
            pad.fuelCans = Math.min(pad.fuelCans, type.maxFuelCans());
            pad.structureHp = plugin.items().droneCargoStructureHp(droneItem);
            pad.flareCharges = plugin.items().droneCargoFlares(droneItem);
        } else {
            initDefaultCargo(pad);
        }
        pad.fuelCapacityCans = type.maxFuelCans();
        pad.fuelUnits = Math.min(pad.fuelCans, pad.fuelCapacityCans) * FUEL_UNITS_PER_CAN;
        syncFuelCansFromUnits(pad);
        index(pad);
        ensureInteractEntity(pad);
        save();
        return pad;
    }

    public BigDroneType typeOf(ParkedPad pad) {
        return pad == null ? BigDroneType.MQ9 : BigDroneType.fromId(pad.typeId);
    }

    public BigDroneType typeOf(UUID padId) {
        return typeOf(byId.get(padId));
    }

    private static List<String> capRockets(List<String> rockets, int max) {
        if (rockets == null || rockets.isEmpty() || max <= 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String r : rockets) {
            if (out.size() >= max) {
                break;
            }
            if (r != null && !r.isBlank()) {
                out.add(r.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    public static int metalFromStructureHp(int structureHp) {
        return metalFromStructureHp(structureHp, BigDroneService.STRUCTURE_MAX);
    }

    public static int metalFromStructureHp(int structureHp, int structureMax) {
        int max = Math.max(1, structureMax);
        if (structureHp <= 0) {
            return 0;
        }
        if (structureHp >= max) {
            return METAL_MAX;
        }
        return (int) Math.round(METAL_MAX * (double) structureHp / max);
    }

    public static int structureHpFromMetal(int metal) {
        return structureHpFromMetal(metal, BigDroneService.STRUCTURE_MAX);
    }

    public static int structureHpFromMetal(int metal, int structureMax) {
        int max = Math.max(1, structureMax);
        int m = Math.max(0, Math.min(METAL_MAX, metal));
        if (m <= 0) {
            return 0;
        }
        if (m >= METAL_MAX) {
            return max;
        }
        return (int) Math.round(max * (double) m / METAL_MAX);
    }

    private static void initDefaultRockets(ParkedPad pad) {
        BigDroneType t = BigDroneType.fromId(pad.typeId);
        pad.rockets.clear();
        pad.rockets.addAll(t.defaultRockets());
    }

    private static void initDefaultCargo(ParkedPad pad) {
        BigDroneType t = BigDroneType.fromId(pad.typeId);
        initDefaultRockets(pad);
        pad.fuelCans = t.defaultFuelCans();
        pad.fuelCapacityCans = t.maxFuelCans();
        pad.fuelUnits = pad.fuelCans * FUEL_UNITS_PER_CAN;
        pad.structureHp = t.structureMax();
        pad.flareCharges = FLARE_MAX;
    }

    public List<ParkedPad> allPads() {
        return new ArrayList<>(byId.values());
    }

    public int clearAllPads() {
        int n = byId.size();
        for (ParkedPad pad : new ArrayList<>(byId.values())) {
            destroyPad(pad);
        }
        return n;
    }

    public int fuelCans(UUID padId) {
        ParkedPad pad = byId.get(padId);
        return pad == null ? 0 : pad.fuelCans;
    }

    public void setFuelCans(UUID padId, int cans) {
        ParkedPad pad = byId.get(padId);
        if (pad == null) {
            return;
        }
        BigDroneType type = typeOf(pad);
        pad.fuelCapacityCans = type.maxFuelCans();
        pad.fuelCans = Math.max(0, Math.min(type.maxFuelCans(), cans));
        pad.fuelUnits = pad.fuelCans * FUEL_UNITS_PER_CAN;
        save();
    }

    /** Drain one fuel unit per flight tick; sync displayed cans. @return remaining cans */
    public int consumeFuelTick(UUID padId) {
        ParkedPad pad = byId.get(padId);
        if (pad == null) {
            return 0;
        }
        if (pad.fuelUnits > 0) {
            pad.fuelUnits--;
        }
        syncFuelCansFromUnits(pad);
        if (plugin.getServer().getCurrentTick() % 40 == 0) {
            save();
        }
        return pad.fuelCans;
    }

    private static void syncFuelCansFromUnits(ParkedPad pad) {
        pad.fuelCans = pad.fuelUnits <= 0 ? 0
                : (pad.fuelUnits + FUEL_UNITS_PER_CAN - 1) / FUEL_UNITS_PER_CAN;
    }

    public double fuelPercent(ParkedPad pad) {
        if (pad == null || pad.fuelCapacityCans <= 0) {
            return pad != null && pad.fuelUnits > 0 ? 100.0 : 0.0;
        }
        int max = pad.fuelCapacityCans * FUEL_UNITS_PER_CAN;
        return Math.max(0.0, Math.min(100.0, 100.0 * pad.fuelUnits / max));
    }

    public double fuelPercent(UUID padId) {
        return fuelPercent(byId.get(padId));
    }

    /** Persist after external pad mutation (gunfire on parked airframe). */
    public void persistPads() {
        save();
    }

    /** Pack pad cargo into a deployable item and remove the pad. */
    public ItemStack pickupToItem(ParkedPad pad) {
        if (pad == null) {
            return null;
        }
        BigDroneType type = typeOf(pad);
        ItemStack item = plugin.items().createBigDrone(
                type, new ArrayList<>(pad.rockets), pad.fuelCans, pad.structureHp, pad.flareCharges);
        if (type.cargoBay()) {
            plugin.items().writeDroneBayCargo(item, new ArrayList<>(pad.cargo));
        }
        destroyPad(pad);
        return item;
    }

    public int cargoCount(UUID padId) {
        ParkedPad pad = byId.get(padId);
        if (pad == null) {
            return 0;
        }
        int n = 0;
        for (ItemStack it : pad.cargo) {
            if (it != null && !it.getType().isAir()) {
                n += it.getAmount();
            }
        }
        return n;
    }

    /** Remove and return the first non-empty cargo stack (for LAW drop). */
    public Optional<ItemStack> consumeCargoFront(UUID padId) {
        ParkedPad pad = byId.get(padId);
        if (pad == null || pad.cargo.isEmpty()) {
            return Optional.empty();
        }
        Iterator<ItemStack> it = pad.cargo.iterator();
        while (it.hasNext()) {
            ItemStack stack = it.next();
            if (stack == null || stack.getType().isAir()) {
                it.remove();
                continue;
            }
            ItemStack one = stack.clone();
            one.setAmount(1);
            if (stack.getAmount() <= 1) {
                it.remove();
            } else {
                stack.setAmount(stack.getAmount() - 1);
            }
            save();
            return Optional.of(one);
        }
        return Optional.empty();
    }

    public boolean pickup(Player player, ParkedPad pad) {
        if (player == null || pad == null) {
            return false;
        }
        String airframe = typeOf(pad).displayName();
        if (!pad.owner.equals(player.getUniqueId()) && !player.hasPermission("warz.admin")) {
            player.sendMessage(Component.text("Only the owner can salvage this " + airframe + ".", NamedTextColor.RED));
            return true;
        }
        if (isOccupied(pad.id)) {
            player.sendMessage(Component.text(airframe + " is airborne — cannot salvage.", NamedTextColor.RED));
            return true;
        }
        ItemStack item = pickupToItem(pad);
        if (item == null) {
            return false;
        }
        if (plugin.bigDrone() != null) {
            Block block = player.getWorld().getBlockAt(pad.x, pad.y, pad.z);
            plugin.bigDrone().clearChunkPadOwner(block);
            if (block.getType() == Material.LODESTONE) {
                block.setType(Material.AIR, false);
            }
        }
        player.getInventory().addItem(item).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        player.sendMessage(Component.text(airframe + " packed up.", NamedTextColor.AQUA));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.0f);
        return true;
    }

    public void removePad(Block block) {
        padAt(block).ifPresent(this::destroyPad);
    }

    /** Permanently scrap a parked MQ-9 (shot down / crash) — no respawn at the pad. */
    public void destroyPad(UUID padId) {
        if (padId == null) {
            return;
        }
        ParkedPad pad = byId.get(padId);
        if (pad != null) {
            destroyPad(pad);
        }
    }

    public void destroyPad(ParkedPad pad) {
        if (pad == null) {
            return;
        }
        UUID padId = pad.id;
        removeInteractEntity(pad);
        if (pad.seatKey != null) {
            seatToPad.remove(pad.seatKey, pad.id);
        }
        byId.remove(pad.id);
        byBlock.remove(blockKey(pad.world, pad.x, pad.y, pad.z));
        pendingLink.entrySet().removeIf(e -> pad.id.equals(e.getValue()));
        World world = Bukkit.getWorld(pad.world);
        if (world != null) {
            Block block = world.getBlockAt(pad.x, pad.y, pad.z);
            if (plugin.bigDrone() != null) {
                plugin.bigDrone().clearChunkPadOwner(block);
            }
        }
        // After unregistering, sweep again in case Interaction was still loading.
        purgePadInteractEntities(padId, null);
        save();
        if (plugin.bigDrone() != null) {
            plugin.bigDrone().broadcastDroneVis();
        }
    }

    public Optional<ParkedPad> padFromInteractEntity(Entity entity) {
        if (!(entity instanceof Interaction)) {
            return Optional.empty();
        }
        String raw = entity.getPersistentDataContainer().get(PAD_INTERACT_KEY, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return padById(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * Interaction is always an axis-aligned square prism (can't rotate). Kept close to fuselage
     * size so far-away looks don't latch; bay open still requires mesh OBB + {@link BigDroneService#PARKED_USE_RANGE}.
     */
    private static final float INTERACT_HEIGHT = (float) (BigDroneService.MESH_CENTER_Y * 2.0);
    private static final float INTERACT_WIDTH = 9.2f; // ~fuselage length, not full wingspan

    /** Spawn / refresh Interaction catcher for empty-hand RMB (OBB validates the real hull). */
    public void ensureInteractEntity(ParkedPad pad) {
        if (pad == null) {
            return;
        }
        if (plugin.bigDrone() != null && plugin.bigDrone().isParkedPadInUse(pad.id)) {
            removeInteractEntity(pad);
            return;
        }
        Location c = airframeCenter(pad);
        if (c == null || c.getWorld() == null) {
            return;
        }
        // Bottom of Interaction is at entity Y — shift so the prism covers belly→top.
        Location spawnAt = c.clone().subtract(0, INTERACT_HEIGHT * 0.5, 0);
        if (pad.interactId != null) {
            Entity existing = Bukkit.getEntity(pad.interactId);
            if (existing instanceof Interaction inter && existing.isValid()) {
                inter.setInteractionWidth(INTERACT_WIDTH);
                inter.setInteractionHeight(INTERACT_HEIGHT);
                inter.setResponsive(true);
                if (inter.getLocation().distanceSquared(spawnAt) > 0.05) {
                    inter.teleport(spawnAt);
                }
                return;
            }
            pad.interactId = null;
        }
        Interaction inter = spawnAt.getWorld().spawn(spawnAt, Interaction.class, e -> {
            e.setPersistent(true);
            e.setInteractionWidth(INTERACT_WIDTH);
            e.setInteractionHeight(INTERACT_HEIGHT);
            e.setResponsive(true);
            e.getPersistentDataContainer().set(PAD_INTERACT_KEY, PersistentDataType.STRING, pad.id.toString());
        });
        pad.interactId = inter.getUniqueId();
    }

    /** Public so camera-editor mount can strip the hull catcher immediately. */
    public void detachInteract(ParkedPad pad) {
        removeInteractEntity(pad);
    }

    private void removeInteractEntity(ParkedPad pad) {
        if (pad == null) {
            return;
        }
        // Prefer tracked id (load chunk first — getEntity is null for unloaded chunks).
        if (pad.interactId != null) {
            Location c = airframeCenter(pad);
            if (c != null && c.getWorld() != null) {
                c.getWorld().getChunkAt(c);
            }
            Entity ent = Bukkit.getEntity(pad.interactId);
            if (ent != null) {
                ent.remove();
            }
            pad.interactId = null;
        }
        // Sweep any leftover Interaction tagged with this pad (orphans from failed removes).
        purgePadInteractEntities(pad.id, pad);
    }

    /**
     * Remove Interaction catchers for a pad id near its airframe (or world-wide if pad gone).
     */
    private void purgePadInteractEntities(UUID padId, ParkedPad pad) {
        if (padId == null) {
            return;
        }
        String idStr = padId.toString();
        if (pad != null) {
            Location c = airframeCenter(pad);
            if (c != null && c.getWorld() != null) {
                for (Entity ent : c.getWorld().getNearbyEntities(c, 24, 16, 24)) {
                    if (!(ent instanceof Interaction)) {
                        continue;
                    }
                    String raw = ent.getPersistentDataContainer().get(PAD_INTERACT_KEY, PersistentDataType.STRING);
                    if (idStr.equals(raw)) {
                        ent.remove();
                    }
                }
                return;
            }
        }
        // Pad already deleted — scan loaded worlds for matching tag.
        for (World world : Bukkit.getWorlds()) {
            for (Entity ent : world.getEntitiesByClass(Interaction.class)) {
                String raw = ent.getPersistentDataContainer().get(PAD_INTERACT_KEY, PersistentDataType.STRING);
                if (idStr.equals(raw)) {
                    ent.remove();
                }
            }
        }
    }

    /** Drop Interaction entities whose pad no longer exists. */
    public void purgeOrphanInteractEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity ent : world.getEntitiesByClass(Interaction.class)) {
                String raw = ent.getPersistentDataContainer().get(PAD_INTERACT_KEY, PersistentDataType.STRING);
                if (raw == null || raw.isEmpty()) {
                    continue;
                }
                try {
                    UUID id = UUID.fromString(raw);
                    if (!byId.containsKey(id)) {
                        ent.remove();
                    }
                } catch (IllegalArgumentException ignored) {
                    ent.remove();
                }
            }
        }
    }

    /** Keep interact hitboxes for parked airframes (call from drone tick occasionally). */
    public void tickInteractEntities() {
        for (ParkedPad pad : byId.values()) {
            if (plugin.bigDrone() != null && plugin.bigDrone().isParkedPadInUse(pad.id)) {
                removeInteractEntity(pad);
            } else if (plugin.droneMeshPose() != null && plugin.droneMeshPose().isCameraPreviewNearPad(pad)) {
                // Camera editor is seated in this airframe — no hull Interaction.
                removeInteractEntity(pad);
            } else {
                ensureInteractEntity(pad);
            }
        }
        if (plugin.getServer().getCurrentTick() % 100 == 0) {
            purgeOrphanInteractEntities();
        }
    }

    public void beginRadiolink(Player player, Block padBlock) {
        Optional<ParkedPad> pad = padAt(padBlock);
        if (pad.isEmpty()) {
            player.sendMessage(Component.text("Not a placed drone.", NamedTextColor.RED));
            return;
        }
        beginRadiolink(player, pad.get());
    }

    public void beginRadiolink(Player player, ParkedPad pad) {
        if (player == null || pad == null) {
            return;
        }
        String air = typeOf(pad).displayName();
        if (!pad.owner.equals(player.getUniqueId()) && !player.hasPermission("warz.admin")) {
            player.sendMessage(Component.text("Only the owner can radiolink this " + air + ".", NamedTextColor.RED));
            return;
        }
        pendingLink.put(player.getUniqueId(), pad.id);
        player.sendMessage(Component.text(air + " selected — right-click a drone seat to radiolink.", NamedTextColor.AQUA));
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.5f);
    }

    public boolean tryCompleteRadiolink(Player player, Block seatBlock) {
        UUID padId = pendingLink.get(player.getUniqueId());
        if (padId == null) {
            return false;
        }
        if (plugin.droneSeats() == null || !plugin.droneSeats().isSeat(seatBlock)) {
            return false;
        }
        ParkedPad pad = byId.get(padId);
        if (pad == null) {
            pendingLink.remove(player.getUniqueId());
            player.sendMessage(Component.text("Drone no longer exists.", NamedTextColor.RED));
            return true;
        }
        String seatKey = DroneSeatService.key(seatBlock);
        // Unlink previous seat on this pad / previous pad on this seat
        if (pad.seatKey != null) {
            seatToPad.remove(pad.seatKey, pad.id);
        }
        UUID oldPad = seatToPad.put(seatKey, pad.id);
        if (oldPad != null && !oldPad.equals(pad.id)) {
            ParkedPad other = byId.get(oldPad);
            if (other != null && seatKey.equals(other.seatKey)) {
                other.seatKey = null;
            }
        }
        pad.seatKey = seatKey;
        pad.linkedBy = player.getUniqueId();
        pendingLink.remove(player.getUniqueId());
        save();
        player.sendMessage(Component.text(
                "Radiolink OK — seat controls this " + typeOf(pad).displayName() + ".",
                NamedTextColor.GREEN));
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.6f);
        if (plugin.bigDrone() != null) {
            plugin.bigDrone().broadcastDroneVis();
        }
        return true;
    }

    public void openLinkManager(Player player) {
        if (player == null) {
            return;
        }
        List<ParkedPad> linked = new ArrayList<>();
        for (ParkedPad pad : byId.values()) {
            if (player.getUniqueId().equals(pad.linkedBy)) {
                linked.add(pad);
            }
        }
        LinkGuiHolder holder = new LinkGuiHolder(player.getUniqueId());
        Inventory inv = ChestInventories.create(holder, LINK_GUI_SIZE,
                Component.text("UAV Radiolinks", NamedTextColor.DARK_PURPLE)
                        .decoration(TextDecoration.ITALIC, false));
        holder.inventory = inv;
        int slot = 0;
        for (ParkedPad pad : linked) {
            if (slot >= LINK_GUI_SIZE) {
                break;
            }
            ItemStack icon = new ItemStack(Material.RECOVERY_COMPASS, 1);
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Component.text(
                    typeOf(pad).displayName() + " @ " + pad.x + " " + pad.y + " " + pad.z,
                    NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Seat: " + (pad.seatKey != null ? pad.seatKey : "none"),
                    NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Click to unlink", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(WarzKeys.of("drone_link_pad"), PersistentDataType.STRING,
                    pad.id.toString());
            icon.setItemMeta(meta);
            inv.setItem(slot++, icon);
        }
        if (linked.isEmpty()) {
            ItemStack none = new ItemStack(Material.BARRIER, 1);
            ItemMeta meta = none.getItemMeta();
            meta.displayName(Component.text("No radiolinks", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            none.setItemMeta(meta);
            inv.setItem(22, none);
        }
        player.openInventory(inv);
    }

    private void unlinkPad(ParkedPad pad) {
        if (pad == null) {
            return;
        }
        if (pad.seatKey != null) {
            seatToPad.remove(pad.seatKey, pad.id);
            pad.seatKey = null;
        }
        pad.linkedBy = null;
        save();
        if (plugin.bigDrone() != null) {
            plugin.bigDrone().broadcastDroneVis();
        }
    }

    public void openBay(Player player, Block padBlock) {
        padAt(padBlock).ifPresent(pad -> openBay(player, pad));
    }

    public void openBay(Player player, ParkedPad pad) {
        if (player == null || pad == null) {
            return;
        }
        if (plugin.bigDrone() != null && plugin.bigDrone().isPiloting(player)) {
            return;
        }
        if (plugin.droneMeshPose() != null && plugin.droneMeshPose().isCameraEditing(player)) {
            player.sendMessage(Component.text("Close the camera editor first (/warz dronecam exit).",
                    NamedTextColor.GRAY));
            return;
        }
        BigDroneType type = typeOf(pad);
        if (!pad.owner.equals(player.getUniqueId()) && !player.hasPermission("warz.admin")) {
            player.sendMessage(Component.text("Only the owner can load this " + type.displayName() + ".",
                    NamedTextColor.RED));
            return;
        }
        BayHolder holder = new BayHolder(pad.id);
        boolean cargo = type.cargoBay();
        Inventory inv = ChestInventories.create(holder, cargo ? CARGO_BAY_SIZE : BAY_SIZE,
                Component.text(type.displayName() + (cargo ? " Cargo" : " Payload"), NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false));
        holder.inventory = inv;
        if (cargo) {
            fillCargoBayContents(inv, pad, type);
        } else {
            fillBayContents(inv, pad, type);
        }
        player.openInventory(inv);
    }

    private void fillBayContents(Inventory inv, ParkedPad pad, BigDroneType type) {
        int slot = 0;
        for (String id : pad.rockets) {
            if (slot >= PRESET_SLOT_STRIKE) {
                break;
            }
            var def = plugin.rounds().get(id);
            if (def.isEmpty()) {
                continue;
            }
            inv.setItem(slot++, plugin.items().createRound(def.get(), 1));
        }
        int slots = type.missileSlots();
        inv.setItem(PRESET_SLOT_STRIKE, presetButton("strike", Material.FIRE_CHARGE,
                "Strike", NamedTextColor.RED,
                Math.min(6, slots) + "× AP · ~60% fuel (" + type.fuelGal() + " gal tank)"));
        inv.setItem(PRESET_SLOT_RECON, presetButton("recon", Material.SPYGLASS,
                "Recon", NamedTextColor.AQUA,
                (slots > 0 ? "1× HP · " : "Unarmed · ") + "full fuel"));
        inv.setItem(PRESET_SLOT_BALANCED, presetButton("balanced", Material.COMPASS,
                "Balanced", NamedTextColor.GOLD,
                Math.min(3, slots) + "× HP · ~80% fuel"));
        inv.setItem(FUEL_SLOT, bayFuelItem(pad, type));
        inv.setItem(FLARE_SLOT, bayFlareItem(pad));
        inv.setItem(METAL_SLOT, bayMetalItem(pad));
        inv.setItem(SALVAGE_SLOT, salvagePackItem(pad));
    }

    private void fillCargoBayContents(Inventory inv, ParkedPad pad, BigDroneType type) {
        int slot = 0;
        for (ItemStack it : pad.cargo) {
            if (slot >= CARGO_SLOTS) {
                break;
            }
            if (it != null && !it.getType().isAir()) {
                inv.setItem(slot++, it.clone());
            }
        }
        inv.setItem(CARGO_FUEL_SLOT, bayFuelItem(pad, type));
        inv.setItem(CARGO_FLARE_SLOT, bayFlareItem(pad));
        inv.setItem(CARGO_METAL_SLOT, bayMetalItem(pad));
        inv.setItem(CARGO_SALVAGE_SLOT, salvagePackItem(pad));
    }

    private ItemStack presetButton(String id, Material mat, String name,
                                   NamedTextColor color, String loreLine) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(loreLine, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(BAY_PRESET_KEY, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    private void applyPreset(ParkedPad pad, String presetId) {
        if (pad == null || presetId == null) {
            return;
        }
        BigDroneType type = typeOf(pad);
        int slots = type.missileSlots();
        int maxFuel = type.maxFuelCans();
        pad.rockets.clear();
        int fuelCans;
        switch (presetId.toLowerCase(Locale.ROOT)) {
            case "strike" -> {
                int n = Math.min(6, slots);
                for (int i = 0; i < n; i++) {
                    // Mix AP ground strike with heat-seek AA
                    pad.rockets.add(i % 3 == 2 ? ROUND_AA : ROUND_AP);
                }
                fuelCans = Math.max(1, Math.min(maxFuel, (int) Math.round(maxFuel * 0.6)));
            }
            case "recon" -> {
                if (slots > 0) {
                    pad.rockets.add(ROUND_HP);
                }
                fuelCans = maxFuel;
            }
            case "balanced" -> {
                for (int i = 0; i < Math.min(3, slots); i++) {
                    pad.rockets.add(ROUND_HP);
                }
                fuelCans = Math.max(1, Math.min(maxFuel, (int) Math.round(maxFuel * 0.8)));
            }
            default -> {
                return;
            }
        }
        pad.fuelCapacityCans = maxFuel;
        pad.fuelUnits = Math.min(fuelCans, maxFuel) * FUEL_UNITS_PER_CAN;
        syncFuelCansFromUnits(pad);
        save();
    }

    private void refreshBayInventory(Player player, ParkedPad pad, Inventory inv) {
        inv.clear();
        BigDroneType type = typeOf(pad);
        if (type.cargoBay()) {
            fillCargoBayContents(inv, pad, type);
        } else {
            fillBayContents(inv, pad, type);
        }
    }

    private ItemStack bayFlareItem(ParkedPad pad) {
        int n = Math.max(0, Math.min(FLARE_MAX, pad.flareCharges));
        if (n <= 0) {
            ItemStack empty = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta meta = empty.getItemMeta();
            meta.displayName(Component.text("Flare Cartridges", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("0 / " + FLARE_MAX + " — empty", NamedTextColor.RED)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Add Flare Cartridges to restock", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Used for IR decoys in flight", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
            empty.setItemMeta(meta);
            return empty;
        }
        ItemStack item = plugin.items().createFlareCartridge(n);
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.text(n + " / " + FLARE_MAX + " loaded", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Take out or add to restock", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Fuel tank gauge — add the airframe's fuel cans here (no loose can stacks in the bay). */
    private ItemStack bayFuelItem(ParkedPad pad, BigDroneType type) {
        int gal = Math.max(0, (int) Math.round(type.fuelGal() * (pad.fuelCapacityCans <= 0 ? 0.0
                : Math.min(1.0, (double) pad.fuelUnits / (pad.fuelCapacityCans * FUEL_UNITS_PER_CAN)))));
        int shown = Math.max(1, Math.min(64, Math.max(1, pad.fuelCans)));
        boolean hydrazine = type == BigDroneType.X37B;
        ItemStack item = hydrazine
                ? plugin.items().createHydrazineFuelCan(shown)
                : plugin.items().createJetFuelCan(shown);
        item.setAmount(shown);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(BAY_FUEL_KEY, PersistentDataType.BYTE, (byte) 1);
        String fuelName = hydrazine ? "Hydrazine" : "Jet Fuel";
        meta.displayName(Component.text(hydrazine ? "Hydrazine Tank" : "Fuel Tank",
                        hydrazine ? NamedTextColor.WHITE : NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(gal + " / " + type.fuelGal() + " gal",
                        gal > type.fuelGal() / 4 ? NamedTextColor.GRAY : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(pad.fuelCans + " / " + type.maxFuelCans() + " " + fuelName + " cans",
                        NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Add " + fuelName + " Cans to refuel", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        if (hydrazine) {
            lore.add(Component.text("X-37B only — jet fuel will not work", NamedTextColor.DARK_AQUA)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("Cannot be removed — gauge only", NamedTextColor.DARK_RED)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static boolean isBayFuelItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        Byte v = stack.getItemMeta().getPersistentDataContainer().get(BAY_FUEL_KEY, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    /** Add-only Metal readout — lore = remaining plate (0–{@link #METAL_MAX}); stack ≤ 99 (Paper limit). */
    private ItemStack bayMetalItem(ParkedPad pad) {
        BigDroneType type = typeOf(pad);
        int metal = metalFromStructureHp(pad.structureHp, type.structureMax());
        // Physical amount can't be 100 (Paper max_stack 1–99); lore carries the true count.
        int shown = metal <= 0 ? 1 : Math.min(ItemFactory.METAL_STACK_MAX, metal);
        ItemStack item = plugin.items().createMetal(shown);
        item.setAmount(shown);
        ItemMeta meta = item.getItemMeta();
        meta.setMaxStackSize(ItemFactory.METAL_STACK_MAX);
        meta.getPersistentDataContainer().set(BAY_METAL_KEY, PersistentDataType.BYTE, (byte) 1);
        meta.displayName(Component.text("Metal", NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(metal + " / " + METAL_MAX + " remaining",
                        metal > 25 ? NamedTextColor.GRAY : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Hull HP " + pad.structureHp + "/" + type.structureMax()
                        + " — damaged by gunfire", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Add Metal or iron ingots to repair", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Cannot be removed from the bay", NamedTextColor.DARK_RED)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static boolean isBayMetalItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        Byte v = stack.getItemMeta().getPersistentDataContainer().get(BAY_METAL_KEY, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    /** Take / deposit Flare Cartridges in the magazine slot (cap {@link #FLARE_MAX}). */
    private void handleFlareSlotClick(Player player, ParkedPad pad, InventoryClickEvent event) {
        event.setCancelled(true);
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        boolean cursorFlare = cursor != null && !cursor.getType().isAir()
                && plugin.items().isFlareCartridge(cursor);
        boolean slotFlare = current != null && plugin.items().isFlareCartridge(current);
        boolean slotEmpty = current == null || current.getType().isAir() || isFlarePlaceholder(current);

        // Pickup magazine stack into cursor
        if (!cursorFlare && (cursor == null || cursor.getType().isAir()) && slotFlare) {
            int take = Math.min(FLARE_MAX, current.getAmount());
            event.getView().setCursor(plugin.items().createFlareCartridge(take));
            pad.flareCharges = 0;
            persistPads();
            refreshBayInventory(player, pad, event.getInventory());
            return;
        }
        // Deposit / merge from cursor
        if (cursorFlare) {
            int have = Math.max(0, pad.flareCharges);
            int room = FLARE_MAX - have;
            if (room <= 0) {
                player.sendMessage(Component.text("Flare magazine full (" + FLARE_MAX + ").", NamedTextColor.GRAY));
                return;
            }
            int add = Math.min(room, cursor.getAmount());
            pad.flareCharges = have + add;
            int left = cursor.getAmount() - add;
            if (left <= 0) {
                event.getView().setCursor(null);
            } else {
                cursor.setAmount(left);
                event.getView().setCursor(cursor);
            }
            persistPads();
            refreshBayInventory(player, pad, event.getInventory());
            player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.4f, 1.4f);
            return;
        }
        if (!slotEmpty && !slotFlare) {
            player.sendMessage(Component.text("Only Flare Cartridges go here.", NamedTextColor.RED));
        }
    }

    /** Deposit Jet Fuel Cans onto the tank gauge (cannot take the gauge out). */
    private void handleFuelSlotClick(Player player, ParkedPad pad, InventoryClickEvent event) {
        event.setCancelled(true);
        ItemStack cursor = event.getCursor();
        BigDroneType type = typeOf(pad);
        boolean hydrazine = type == BigDroneType.X37B;
        String fuelName = hydrazine ? "Hydrazine Fuel Cans" : "Jet Fuel Cans";
        if (cursor == null || cursor.getType().isAir() || isBayFuelItem(cursor)) {
            player.sendMessage(Component.text("Fuel gauge stays in the bay — add " + fuelName + " to refuel.",
                    NamedTextColor.YELLOW));
            return;
        }
        if (!plugin.items().isFuelCanFor(cursor, type)) {
            if (hydrazine && plugin.items().isJetFuelCan(cursor)) {
                player.sendMessage(Component.text("X-37B needs Hydrazine Fuel Cans — not jet fuel.",
                        NamedTextColor.RED));
            } else if (!hydrazine && plugin.items().isHydrazineFuelCan(cursor)) {
                player.sendMessage(Component.text("Hydrazine is for the X-37B only — use Jet Fuel Cans.",
                        NamedTextColor.RED));
            } else {
                player.sendMessage(Component.text("Only " + fuelName + " can be added here.", NamedTextColor.RED));
            }
            return;
        }
        int max = type.maxFuelCans();
        int have = Math.max(0, pad.fuelCans);
        int room = max - have;
        if (room <= 0) {
            player.sendMessage(Component.text("Fuel tank full (" + type.fuelGal() + " gal).", NamedTextColor.GRAY));
            return;
        }
        int add = Math.min(room, cursor.getAmount());
        pad.fuelCapacityCans = max;
        pad.fuelUnits = Math.min(max * FUEL_UNITS_PER_CAN, pad.fuelUnits + add * FUEL_UNITS_PER_CAN);
        syncFuelCansFromUnits(pad);
        int left = cursor.getAmount() - add;
        if (left <= 0) {
            event.getView().setCursor(null);
        } else {
            cursor.setAmount(left);
            event.getView().setCursor(cursor);
        }
        persistPads();
        refreshBayInventory(player, pad, event.getInventory());
        player.playSound(player.getLocation(), Sound.ITEM_BUCKET_FILL_LAVA, 0.45f, 1.2f);
        int gal = (int) Math.round(type.fuelGal() * fuelPercent(pad) / 100.0);
        player.sendMessage(Component.text("Refueled +" + add + " can(s) → " + gal + "/" + type.fuelGal() + " gal",
                NamedTextColor.GOLD));
    }

    /**
     * Deposit Metal / iron onto the bay slot (cannot take Metal out).
     * @return true if the click was fully handled
     */
    private boolean handleMetalSlotClick(Player player, ParkedPad pad, InventoryClickEvent event) {
        event.setCancelled(true);
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType().isAir()) {
            player.sendMessage(Component.text("Metal stays in the bay — add Metal or iron ingots to repair.",
                    NamedTextColor.YELLOW));
            return true;
        }
        if (!plugin.items().isMetalDeposit(cursor) || isBayMetalItem(cursor)) {
            player.sendMessage(Component.text("Only Metal or iron ingots can be added here.",
                    NamedTextColor.RED));
            return true;
        }
        BigDroneType type = typeOf(pad);
        int current = metalFromStructureHp(pad.structureHp, type.structureMax());
        if (current >= METAL_MAX) {
            player.sendMessage(Component.text("Airframe Metal is already full.", NamedTextColor.GRAY));
            return true;
        }
        int room = METAL_MAX - current;
        int add = Math.min(room, cursor.getAmount());
        if (add <= 0) {
            return true;
        }
        pad.structureHp = structureHpFromMetal(current + add, type.structureMax());
        int left = cursor.getAmount() - add;
        if (left <= 0) {
            event.getView().setCursor(null);
        } else {
            cursor.setAmount(left);
            event.getView().setCursor(cursor);
        }
        persistPads();
        refreshBayInventory(player, pad, event.getInventory());
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.35f, 1.35f);
        player.sendMessage(Component.text("Repaired +" + add + " Metal ("
                        + metalFromStructureHp(pad.structureHp, type.structureMax()) + "/" + METAL_MAX + ")",
                NamedTextColor.GREEN));
        return true;
    }

    /** Deployable airframe shown in the bay — taking it packs the airframe. */
    private ItemStack salvagePackItem(ParkedPad pad) {
        BigDroneType type = typeOf(pad);
        ItemStack item = plugin.items().createBigDrone(
                type, new ArrayList<>(pad.rockets), pad.fuelCans, pad.structureHp, pad.flareCharges);
        if (type.cargoBay()) {
            plugin.items().writeDroneBayCargo(item, new ArrayList<>(pad.cargo));
        }
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(BAY_SALVAGE_KEY, PersistentDataType.BYTE, (byte) 1);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Take this to pack the " + type.displayName(), NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Removes it from the tarmac", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (type.cargoBay()) {
            lore.add(Component.text("Cargo: " + cargoCount(pad.id) + " items · "
                            + pad.fuelCans + "/" + type.maxFuelCans() + " Jet Fuel",
                    NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Cargo: " + pad.rockets.size() + "/" + type.missileSlots()
                            + " rockets · " + pad.fuelCans + "/" + type.maxFuelCans() + " Jet Fuel",
                    NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("Tank: " + type.fuelGal() + " gal", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Flares: " + pad.flareCharges + "/" + FLARE_MAX, NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Metal: " + metalFromStructureHp(pad.structureHp, type.structureMax())
                        + "/" + METAL_MAX + " · HP " + pad.structureHp + "/" + type.structureMax(),
                NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static boolean isBaySalvageItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        Byte v = stack.getItemMeta().getPersistentDataContainer().get(BAY_SALVAGE_KEY, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    public Optional<String> peekRocket(UUID padId) {
        ParkedPad pad = byId.get(padId);
        if (pad == null || pad.rockets.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(pad.rockets.get(0));
    }

    /**
     * Rotate the payload bay queue. Positive delta = next rocket becomes first (fire next).
     * Returns the new peek id, or empty if the bay has no rockets.
     */
    public Optional<String> rotateRockets(UUID padId, int delta) {
        ParkedPad pad = byId.get(padId);
        if (pad == null || typeOf(pad).cargoBay() || pad.rockets.isEmpty()) {
            return Optional.empty();
        }
        int n = pad.rockets.size();
        if (n <= 1 || delta == 0) {
            return Optional.of(pad.rockets.get(0));
        }
        int steps = ((delta % n) + n) % n;
        if (steps == 0) {
            return Optional.of(pad.rockets.get(0));
        }
        // Rotate left by `steps` so former index `steps` is now first.
        List<String> rotated = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rotated.add(pad.rockets.get((i + steps) % n));
        }
        pad.rockets.clear();
        pad.rockets.addAll(rotated);
        save();
        return Optional.of(pad.rockets.get(0));
    }

    public Optional<String> consumeRocket(UUID padId) {
        ParkedPad pad = byId.get(padId);
        if (pad == null || pad.rockets.isEmpty()) {
            return Optional.empty();
        }
        String id = pad.rockets.remove(0);
        save();
        return Optional.of(id);
    }

    public int rocketCount(UUID padId) {
        ParkedPad pad = byId.get(padId);
        return pad == null ? 0 : pad.rockets.size();
    }

    public Location airframeLocation(ParkedPad pad) {
        World world = Bukkit.getWorld(pad.world);
        if (world == null) {
            return null;
        }
        // Pad cell is already the air block above the ground — sit on that floor, not +1.
        return new Location(world, pad.x + 0.5, pad.y + 0.05, pad.z + 0.5, pad.yaw, 0f);
    }

    /** Mid-body aim point — mesh center above pad feet (matches client 3× MQ-9). */
    public Location airframeCenter(ParkedPad pad) {
        Location feet = airframeLocation(pad);
        if (feet == null) {
            return null;
        }
        return feet.clone().add(0, BigDroneService.MESH_CENTER_Y, 0);
    }

    /** Yaw so the MQ-9 nose faces the placing player. */
    public static float yawFacingPlayer(Block pad, Player player) {
        Location from = pad.getLocation().add(0.5, 0, 0.5);
        Location to = player.getLocation();
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (dx * dx + dz * dz < 1.0e-6) {
            // Fallback: opposite of look (drone faces you)
            return player.getLocation().getYaw() + 180f;
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    public static boolean isBayItem(WarzPlugin plugin, ItemStack stack) {
        // Rockets only in cargo slots — Jet Fuel uses the tank gauge slot.
        return isDroneRocket(plugin, stack);
    }

    private static boolean isFlarePlaceholder(ItemStack stack) {
        return stack != null && stack.getType() == Material.GRAY_STAINED_GLASS_PANE
                && stack.hasItemMeta() && stack.getItemMeta().hasDisplayName();
    }

    public static boolean isDroneRocket(WarzPlugin plugin, ItemStack stack) {
        if (plugin == null || stack == null || !stack.hasItemMeta()) {
            return false;
        }
        String id = stack.getItemMeta().getPersistentDataContainer()
                .get(plugin.items().roundKey(), org.bukkit.persistence.PersistentDataType.STRING);
        if (id == null) {
            return false;
        }
        String low = id.toLowerCase(Locale.ROOT);
        return MunitionProfile.isBayRocket(low);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLinkClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof LinkGuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!player.getUniqueId().equals(holder.playerId)) {
            return;
        }
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        String raw = clicked.getItemMeta().getPersistentDataContainer()
                .get(WarzKeys.of("drone_link_pad"), PersistentDataType.STRING);
        if (raw == null) {
            return;
        }
        UUID padId;
        try {
            padId = UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return;
        }
        ParkedPad pad = byId.get(padId);
        if (pad == null || !player.getUniqueId().equals(pad.linkedBy)) {
            player.sendMessage(Component.text("Link no longer valid.", NamedTextColor.RED));
            player.closeInventory();
            return;
        }
        unlinkPad(pad);
        player.sendMessage(Component.text("Radiolink removed.", NamedTextColor.YELLOW));
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.7f, 1.2f);
        player.closeInventory();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBayClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BayHolder holder)) {
            return;
        }
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        int rawSlot = event.getRawSlot();
        ParkedPad pad = byId.get(holder.padId);
        boolean cargoBay = pad != null && typeOf(pad).cargoBay();
        int fuelSlot = cargoBay ? CARGO_FUEL_SLOT : FUEL_SLOT;
        int flareSlot = cargoBay ? CARGO_FLARE_SLOT : FLARE_SLOT;
        int metalSlot = cargoBay ? CARGO_METAL_SLOT : METAL_SLOT;
        int salvageSlot = cargoBay ? CARGO_SALVAGE_SLOT : SALVAGE_SLOT;

        // Don't let double-click collect pull Metal out of the bay readout.
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                && (isBayMetalItem(current) || plugin.items().isMetalDeposit(cursor))) {
            event.setCancelled(true);
            return;
        }

        // Fuel tank gauge — add Jet Fuel Cans (max type tank).
        if (rawSlot == fuelSlot
                || (event.isShiftClick() && isBayFuelItem(current)
                && event.getClickedInventory() != null
                && event.getClickedInventory().getHolder() instanceof BayHolder)) {
            if (!(event.getWhoClicked() instanceof Player player) || pad == null) {
                event.setCancelled(true);
                return;
            }
            handleFuelSlotClick(player, pad, event);
            return;
        }
        // Shift-click matching fuel cans from player inventory → tank.
        if (event.isShiftClick() && current != null && !current.getType().isAir()
                && event.getClickedInventory() != null
                && !(event.getClickedInventory().getHolder() instanceof BayHolder)
                && plugin.items().isAnyFuelCan(current)
                && !isBayFuelItem(current)) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || pad == null) {
                return;
            }
            BigDroneType type = typeOf(pad);
            if (!plugin.items().isFuelCanFor(current, type)) {
                handleFuelSlotClick(player, pad, event);
                return;
            }
            int max = type.maxFuelCans();
            int room = max - Math.max(0, pad.fuelCans);
            if (room <= 0) {
                player.sendMessage(Component.text("Fuel tank full (" + type.fuelGal() + " gal).", NamedTextColor.GRAY));
                return;
            }
            int add = Math.min(room, current.getAmount());
            pad.fuelCapacityCans = max;
            pad.fuelUnits = Math.min(max * FUEL_UNITS_PER_CAN, pad.fuelUnits + add * FUEL_UNITS_PER_CAN);
            syncFuelCansFromUnits(pad);
            current.setAmount(current.getAmount() - add);
            if (current.getAmount() <= 0) {
                event.setCurrentItem(null);
            }
            persistPads();
            refreshBayInventory(player, pad, event.getInventory());
            player.playSound(player.getLocation(), Sound.ITEM_BUCKET_FILL_LAVA, 0.45f, 1.2f);
            return;
        }

        // Flare magazine slot — take / restock Flare Cartridges (max FLARE_MAX).
        if (rawSlot == flareSlot) {
            if (!(event.getWhoClicked() instanceof Player player) || pad == null) {
                event.setCancelled(true);
                return;
            }
            handleFlareSlotClick(player, pad, event);
            return;
        }
        if (event.isShiftClick() && current != null && !current.getType().isAir()
                && event.getClickedInventory() != null
                && !(event.getClickedInventory().getHolder() instanceof BayHolder)
                && plugin.items().isFlareCartridge(current)) {
            event.setCancelled(true);
            if (pad == null || !(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            int room = FLARE_MAX - Math.max(0, pad.flareCharges);
            if (room <= 0) {
                player.sendMessage(Component.text("Flare magazine full (" + FLARE_MAX + ").", NamedTextColor.GRAY));
                return;
            }
            int add = Math.min(room, current.getAmount());
            pad.flareCharges += add;
            current.setAmount(current.getAmount() - add);
            if (current.getAmount() <= 0) {
                event.setCurrentItem(null);
            }
            persistPads();
            refreshBayInventory(player, pad, event.getInventory());
            player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.4f, 1.4f);
            player.sendMessage(Component.text("Flare magazine " + pad.flareCharges + "/" + FLARE_MAX,
                    NamedTextColor.GOLD));
            return;
        }

        // Metal slot: add-only (never remove the readout stack).
        if (rawSlot == metalSlot
                || (event.isShiftClick() && isBayMetalItem(current)
                && event.getClickedInventory() != null
                && event.getClickedInventory().getHolder() instanceof BayHolder)) {
            if (!(event.getWhoClicked() instanceof Player player) || pad == null) {
                event.setCancelled(true);
                return;
            }
            handleMetalSlotClick(player, pad, event);
            return;
        }

        // Shift-click Metal / iron from player inventory → repair bay Metal.
        if (event.isShiftClick() && current != null && !current.getType().isAir()
                && event.getClickedInventory() != null
                && !(event.getClickedInventory().getHolder() instanceof BayHolder)
                && plugin.items().isMetalDeposit(current)
                && !isBayMetalItem(current)) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || pad == null) {
                return;
            }
            BigDroneType type = typeOf(pad);
            int metalNow = metalFromStructureHp(pad.structureHp, type.structureMax());
            if (metalNow >= METAL_MAX) {
                player.sendMessage(Component.text("Airframe Metal is already full.", NamedTextColor.GRAY));
                return;
            }
            int room = METAL_MAX - metalNow;
            int add = Math.min(room, current.getAmount());
            pad.structureHp = structureHpFromMetal(metalNow + add, type.structureMax());
            current.setAmount(current.getAmount() - add);
            if (current.getAmount() <= 0) {
                event.setCurrentItem(null);
            }
            persistPads();
            refreshBayInventory(player, pad, event.getInventory());
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.35f, 1.35f);
            player.sendMessage(Component.text("Repaired +" + add + " Metal ("
                            + metalFromStructureHp(pad.structureHp, type.structureMax()) + "/" + METAL_MAX + ")",
                    NamedTextColor.GREEN));
            return;
        }

        boolean salvageClick = rawSlot == salvageSlot
                || (event.isShiftClick() && isBaySalvageItem(current)
                && event.getClickedInventory() != null
                && event.getClickedInventory().getHolder() instanceof BayHolder);
        if (salvageClick) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (!isBaySalvageItem(current)) {
                if (cursor != null && !cursor.getType().isAir()) {
                    player.sendMessage(Component.text(
                            "Take the " + typeOf(pad).displayName() + " item to pack the airframe.",
                            NamedTextColor.YELLOW));
                }
                return;
            }
            if (pad == null) {
                player.closeInventory();
                return;
            }
            syncBayFromInventory(pad, event.getInventory());
            if (!pickup(player, pad)) {
                return;
            }
            player.closeInventory();
            return;
        }
        if (!cargoBay && (rawSlot == PRESET_SLOT_STRIKE || rawSlot == PRESET_SLOT_RECON
                || rawSlot == PRESET_SLOT_BALANCED)) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) {
                return;
            }
            String presetId = clicked.getItemMeta().getPersistentDataContainer()
                    .get(BAY_PRESET_KEY, PersistentDataType.STRING);
            if (presetId == null) {
                return;
            }
            if (pad == null) {
                return;
            }
            applyPreset(pad, presetId);
            refreshBayInventory(player, pad, event.getInventory());
            player.playSound(player.getLocation(), Sound.BLOCK_DISPENSER_DISPENSE, 0.6f, 1.2f);
            player.sendMessage(Component.text("Payload preset applied: " + presetId,
                    NamedTextColor.AQUA));
            return;
        }
        // Cargo bay: free slots accept any item; service row already handled above.
        if (cargoBay) {
            if (event.getClickedInventory() != null
                    && event.getClickedInventory().getHolder() instanceof BayHolder
                    && rawSlot >= CARGO_SLOTS && rawSlot != fuelSlot && rawSlot != flareSlot
                    && rawSlot != metalSlot && rawSlot != salvageSlot) {
                // Decorative empty slots between cargo and service row
                event.setCancelled(true);
                return;
            }
            if (cursor != null && !cursor.getType().isAir()
                    && event.getClickedInventory() != null
                    && event.getClickedInventory().getHolder() instanceof BayHolder
                    && rawSlot < CARGO_SLOTS) {
                if (plugin.items().isAnyFuelCan(cursor) && !isBayFuelItem(cursor)
                        && event.getWhoClicked() instanceof Player player) {
                    handleFuelSlotClick(player, pad, event);
                    return;
                }
                if (plugin.items().isFlareCartridge(cursor)
                        && event.getWhoClicked() instanceof Player player) {
                    handleFlareSlotClick(player, pad, event);
                    return;
                }
                if (plugin.items().isBigDroneItem(cursor)) {
                    event.setCancelled(true);
                    return;
                }
            }
            return;
        }
        // Allow take-out; block putting invalid items in rocket bay
        if (event.getClickedInventory() != null
                && event.getClickedInventory().getHolder() instanceof BayHolder) {
            if (cursor != null && !cursor.getType().isAir()
                    && !isBayItem(plugin, cursor) && !isBaySalvageItem(cursor)
                    && !isBayMetalItem(cursor) && !isBayFuelItem(cursor)) {
                // Metal / iron on cargo slots → redirect to Metal repair
                if (plugin.items().isMetalDeposit(cursor) && pad != null
                        && event.getWhoClicked() instanceof Player player) {
                    handleMetalSlotClick(player, pad, event);
                    return;
                }
                // Fuel cans → tank gauge
                if (plugin.items().isAnyFuelCan(cursor) && pad != null
                        && event.getWhoClicked() instanceof Player player) {
                    handleFuelSlotClick(player, pad, event);
                    return;
                }
                // Flare cartridges → magazine slot
                if (plugin.items().isFlareCartridge(cursor) && pad != null
                        && event.getWhoClicked() instanceof Player player) {
                    handleFlareSlotClick(player, pad, event);
                    return;
                }
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player p) {
                    p.sendMessage(Component.text(
                            "Rockets in cargo · Jet Fuel → fuel gauge · Flares → flare slot.",
                            NamedTextColor.RED));
                }
            }
        }
        if (event.isShiftClick() && current != null && !current.getType().isAir()
                && event.getClickedInventory() != null
                && !(event.getClickedInventory().getHolder() instanceof BayHolder)
                && !isBayItem(plugin, current)
                && !plugin.items().isMetalDeposit(current)
                && !plugin.items().isAnyFuelCan(current)
                && !plugin.items().isFlareCartridge(current)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBayDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof BayHolder holder)) {
            return;
        }
        ParkedPad pad = byId.get(holder.padId);
        boolean cargoBay = pad != null && typeOf(pad).cargoBay();
        if (event.getRawSlots().contains(METAL_SLOT) || event.getRawSlots().contains(FLARE_SLOT)
                || event.getRawSlots().contains(FUEL_SLOT)
                || event.getRawSlots().contains(SALVAGE_SLOT)
                || event.getRawSlots().contains(CARGO_METAL_SLOT)
                || event.getRawSlots().contains(CARGO_FLARE_SLOT)
                || event.getRawSlots().contains(CARGO_FUEL_SLOT)
                || event.getRawSlots().contains(CARGO_SALVAGE_SLOT)
                || event.getRawSlots().contains(PRESET_SLOT_STRIKE)
                || event.getRawSlots().contains(PRESET_SLOT_RECON)
                || event.getRawSlots().contains(PRESET_SLOT_BALANCED)) {
            event.setCancelled(true);
            return;
        }
        if (cargoBay) {
            for (int slot : event.getRawSlots()) {
                if (slot >= CARGO_SLOTS && slot < CARGO_BAY_SIZE) {
                    event.setCancelled(true);
                    return;
                }
            }
            for (ItemStack stack : event.getNewItems().values()) {
                if (stack != null && !stack.getType().isAir()
                        && (plugin.items().isFlareCartridge(stack)
                        || plugin.items().isAnyFuelCan(stack)
                        || plugin.items().isBigDroneItem(stack))) {
                    event.setCancelled(true);
                    return;
                }
            }
            return;
        }
        for (ItemStack stack : event.getNewItems().values()) {
            if (stack != null && !stack.getType().isAir()
                    && !isBayItem(plugin, stack) && !plugin.items().isFlareCartridge(stack)) {
                event.setCancelled(true);
                return;
            }
            // Flares belong in the flare slot, not cargo rows.
            if (stack != null && plugin.items().isFlareCartridge(stack)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBayClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof BayHolder holder)) {
            return;
        }
        ParkedPad pad = byId.get(holder.padId);
        if (pad == null) {
            return; // already packed / destroyed
        }
        syncBayFromInventory(pad, event.getInventory());
        save();
    }

    /** Write rockets/cargo + fuel + flares from the open bay GUI back onto the pad. */
    private void syncBayFromInventory(ParkedPad pad, Inventory inv) {
        if (pad == null || inv == null) {
            return;
        }
        BigDroneType type = typeOf(pad);
        int flareSlot = type.cargoBay() ? CARGO_FLARE_SLOT : FLARE_SLOT;
        ItemStack flareStack = inv.getItem(flareSlot);
        if (flareStack != null && plugin.items().isFlareCartridge(flareStack)) {
            pad.flareCharges = Math.min(FLARE_MAX, Math.max(0, flareStack.getAmount()));
        } else if (isFlarePlaceholder(flareStack)
                || flareStack == null || flareStack.getType().isAir()) {
            pad.flareCharges = 0;
        }
        int maxFuel = type.maxFuelCans();
        if (type.cargoBay()) {
            pad.cargo.clear();
            ItemStack[] contents = inv.getContents();
            for (int i = 0; i < CARGO_SLOTS && i < contents.length; i++) {
                ItemStack stack = contents[i];
                if (stack == null || stack.getType().isAir() || isBaySalvageItem(stack)
                        || isBayMetalItem(stack) || isBayFuelItem(stack) || isFlarePlaceholder(stack)
                        || plugin.items().isFlareCartridge(stack)
                        || plugin.items().isAnyFuelCan(stack)
                        || plugin.items().isBigDroneItem(stack)) {
                    continue;
                }
                pad.cargo.add(stack.clone());
            }
        } else {
            int maxSlots = type.missileSlots();
            pad.rockets.clear();
            ItemStack[] contents = inv.getContents();
            for (int i = 0; i < contents.length; i++) {
                if (i == PRESET_SLOT_STRIKE || i == PRESET_SLOT_RECON || i == PRESET_SLOT_BALANCED
                        || i == FUEL_SLOT || i == FLARE_SLOT || i == METAL_SLOT || i == SALVAGE_SLOT) {
                    continue;
                }
                ItemStack stack = contents[i];
                if (stack == null || stack.getType().isAir() || isBaySalvageItem(stack)
                        || isBayMetalItem(stack) || isBayFuelItem(stack) || isFlarePlaceholder(stack)
                        || plugin.items().isFlareCartridge(stack)
                        || plugin.items().isAnyFuelCan(stack)) {
                    continue;
                }
                if (!isDroneRocket(plugin, stack)) {
                    continue;
                }
                String id = stack.getItemMeta().getPersistentDataContainer()
                        .get(plugin.items().roundKey(), org.bukkit.persistence.PersistentDataType.STRING);
                if (id == null) {
                    continue;
                }
                for (int n = 0; n < stack.getAmount(); n++) {
                    if (pad.rockets.size() >= maxSlots) {
                        break;
                    }
                    pad.rockets.add(id.toLowerCase(Locale.ROOT));
                }
            }
        }
        pad.fuelCapacityCans = maxFuel;
        int maxUnits = maxFuel * FUEL_UNITS_PER_CAN;
        pad.fuelUnits = Math.min(pad.fuelUnits, maxUnits);
        syncFuelCansFromUnits(pad);
    }

    private void index(ParkedPad pad) {
        byId.put(pad.id, pad);
        byBlock.put(blockKey(pad.world, pad.x, pad.y, pad.z), pad);
        if (pad.seatKey != null) {
            seatToPad.put(pad.seatKey, pad.id);
        }
    }

    private void load() {
        byBlock.clear();
        byId.clear();
        seatToPad.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("pads");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            String world = s.getString("world");
            UUID owner;
            try {
                owner = UUID.fromString(s.getString("owner", ""));
            } catch (Exception e) {
                continue;
            }
            if (world == null) {
                continue;
            }
            ParkedPad pad = new ParkedPad(id, world, s.getInt("x"), s.getInt("y"), s.getInt("z"),
                    (float) s.getDouble("yaw"), owner);
            pad.seatKey = s.getString("seat");
            List<String> rockets = s.getStringList("rockets");
            if (rockets != null) {
                for (String r : rockets) {
                    if (r != null && !r.isBlank()) {
                        pad.rockets.add(r.toLowerCase(Locale.ROOT));
                    }
                }
            }
            pad.typeId = s.getString("typeId", BigDroneType.MQ9.id());
            BigDroneType type = BigDroneType.fromId(pad.typeId);
            pad.fuelCans = s.getInt("fuelCans", type.defaultFuelCans());
            pad.fuelCapacityCans = s.getInt("fuelCapacityCans", type.maxFuelCans());
            pad.fuelCapacityCans = Math.max(pad.fuelCapacityCans, type.maxFuelCans());
            pad.fuelUnits = s.getInt("fuelUnits", pad.fuelCans * FUEL_UNITS_PER_CAN);
            pad.structureHp = s.getInt("structureHp", BigDroneService.STRUCTURE_MAX);
            pad.flareCharges = Math.max(0, Math.min(FLARE_MAX, s.getInt("flareCharges", FLARE_MAX)));
            if (type.cargoBay()) {
                pad.rockets.clear();
                ConfigurationSection cargoSec = s.getConfigurationSection("cargo");
                if (cargoSec != null) {
                    for (String ck : cargoSec.getKeys(false)) {
                        if (pad.cargo.size() >= CARGO_SLOTS) {
                            break;
                        }
                        ItemStack stack = cargoSec.getItemStack(ck);
                        if (stack != null && !stack.getType().isAir()) {
                            pad.cargo.add(stack);
                        }
                    }
                } else {
                    List<?> rawCargo = s.getList("cargo");
                    if (rawCargo != null) {
                        for (Object o : rawCargo) {
                            if (pad.cargo.size() >= CARGO_SLOTS) {
                                break;
                            }
                            if (o instanceof ItemStack stack && !stack.getType().isAir()) {
                                pad.cargo.add(stack);
                            }
                        }
                    }
                }
            } else if (pad.rockets.size() > type.missileSlots()) {
                List<String> capped = new ArrayList<>(pad.rockets.subList(0, type.missileSlots()));
                pad.rockets.clear();
                pad.rockets.addAll(capped);
            }
            String linkedRaw = s.getString("linkedBy");
            if (linkedRaw != null && !linkedRaw.isBlank()) {
                try {
                    pad.linkedBy = UUID.fromString(linkedRaw);
                } catch (IllegalArgumentException ignored) {
                }
            }
            index(pad);
        }
        plugin.getLogger().info("Loaded " + byId.size() + " drone pad(s).");
        // Worlds may not be ready mid-constructor — spawn hull hitboxes next tick.
        Bukkit.getScheduler().runTask(plugin, this::tickInteractEntities);
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (ParkedPad pad : byId.values()) {
            String path = "pads." + pad.id;
            yaml.set(path + ".world", pad.world);
            yaml.set(path + ".x", pad.x);
            yaml.set(path + ".y", pad.y);
            yaml.set(path + ".z", pad.z);
            yaml.set(path + ".yaw", pad.yaw);
            yaml.set(path + ".owner", pad.owner.toString());
            yaml.set(path + ".seat", pad.seatKey);
            yaml.set(path + ".typeId", pad.typeId != null ? pad.typeId : BigDroneType.MQ9.id());
            yaml.set(path + ".rockets", new ArrayList<>(pad.rockets));
            List<ItemStack> cargoOut = new ArrayList<>();
            for (ItemStack it : pad.cargo) {
                if (it != null && !it.getType().isAir()) {
                    cargoOut.add(it.clone());
                }
            }
            yaml.set(path + ".cargo", cargoOut);
            yaml.set(path + ".fuelCans", pad.fuelCans);
            yaml.set(path + ".fuelCapacityCans", pad.fuelCapacityCans);
            yaml.set(path + ".fuelUnits", pad.fuelUnits);
            yaml.set(path + ".structureHp", pad.structureHp);
            yaml.set(path + ".flareCharges", pad.flareCharges);
            yaml.set(path + ".linkedBy", pad.linkedBy != null ? pad.linkedBy.toString() : null);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save drone_pads.yml: " + e.getMessage());
        }
    }

    private static String blockKey(Block block) {
        return blockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private static String blockKey(String world, int x, int y, int z) {
        return world.toLowerCase(Locale.ROOT) + ";" + x + ";" + y + ";" + z;
    }

    public static final class ParkedPad {
        public final UUID id;
        public final String world;
        public final int x;
        public final int y;
        public final int z;
        public final float yaw;
        public final UUID owner;
        public String seatKey;
        /** {@link BigDroneType#id()} — defaults to MQ-9. */
        public String typeId = BigDroneType.MQ9.id();
        public int fuelCans = DEFAULT_FUEL_CANS;
        public int fuelCapacityCans = DEFAULT_FUEL_CANS;
        public int fuelUnits = DEFAULT_FUEL_CANS * FUEL_UNITS_PER_CAN;
        public int structureHp = BigDroneService.STRUCTURE_MAX;
        public int flareCharges = FLARE_MAX;
        public UUID linkedBy;
        public final List<String> rockets = new ArrayList<>();
        /** Free-form cargo stacks (X-37B bay); serialized in pad YAML. */
        public final List<ItemStack> cargo = new ArrayList<>();
        /** Runtime-only Interaction entity for full-hull RMB. */
        public UUID interactId;

        ParkedPad(UUID id, String world, int x, int y, int z, float yaw, UUID owner) {
            this.id = id;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.owner = owner;
        }
    }

    private static final class LinkGuiHolder implements InventoryHolder {
        final UUID playerId;
        Inventory inventory;

        LinkGuiHolder(UUID playerId) {
            this.playerId = playerId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Package-visible so pilot interact guards can recognize the payload GUI. */
    static final class BayHolder implements InventoryHolder {
        final UUID padId;
        Inventory inventory;

        BayHolder(UUID padId) {
            this.padId = padId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
