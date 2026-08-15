package com.local.warz.gui;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.model.GunDefinition;
import com.local.warz.model.RoundDefinition;
import com.local.warz.runtime.BigDroneType;
import com.local.warz.runtime.FlareColor;
import com.local.warz.runtime.GlassType;
import com.local.warz.runtime.GripType;
import com.local.warz.runtime.ItemFactory;
import com.local.warz.runtime.LaserModColor;
import com.local.warz.runtime.MagazineType;
import com.local.warz.runtime.NvgGear;
import com.local.warz.runtime.DrinkType;
import com.local.warz.runtime.WarzFoodType;
import com.local.warz.runtime.DronePadService;
import com.local.warz.runtime.OpticType;
import com.local.warz.runtime.SmokeType;
import com.local.warz.runtime.SuppressorType;
import com.local.warz.runtime.ThermalGear;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Simple give-yourself menu for guns, ammo rounds, and grenades/throwables. */
public final class GiveGunMenuService {
    public enum Page {
        HOME, GUNS, WARZ_GUNS, AMMO, MAGS, GRENADES, SMOKES, FLARES, GEAR, UAV_MUNITIONS,
        NVG_COLORS, THERMAL_MODES, HAZMAT_SUIT, FIRE_PROXIMITY_SUIT,
        ATTACHMENTS, GLASS, DRINKS
    }

    public static final class Session {
        public Page page = Page.HOME;
        public int browserPage;
        public Inventory openInventory;
    }

    public static final class Holder implements InventoryHolder {
        private final UUID playerId;
        private Inventory inventory;

        public Holder(UUID playerId) {
            this.playerId = playerId;
        }

        public UUID playerId() {
            return playerId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    private final WarzPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public GiveGunMenuService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        Session session = sessions.computeIfAbsent(player.getUniqueId(), id -> new Session());
        session.page = Page.HOME;
        session.browserPage = 0;
        render(player);
    }

    public void clear(Player player) {
        sessions.remove(player.getUniqueId());
    }

    public Session session(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), id -> new Session());
    }

    public void render(Player player) {
        Session session = session(player);
        Holder holder = new Holder(player.getUniqueId());
        Inventory inv = switch (session.page) {
            case HOME -> {
                Inventory home = ChestInventories.create(holder, 27,
                        Component.text("Give Menu", NamedTextColor.GOLD));
                holder.setInventory(home);
                fillHome(home);
                yield home;
            }
            case GUNS -> {
                Inventory guns = ChestInventories.create(holder, 54,
                        Component.text("Give Guns", NamedTextColor.DARK_GREEN));
                holder.setInventory(guns);
                fillGuns(guns, session);
                yield guns;
            }
            case WARZ_GUNS -> {
                Inventory warz = ChestInventories.create(holder, 54,
                        Component.text("WARZ GUNS!", NamedTextColor.GOLD));
                holder.setInventory(warz);
                fillWarzGuns(warz, session);
                yield warz;
            }
            case AMMO -> {
                Inventory ammo = ChestInventories.create(holder, 54,
                        Component.text("Give Ammo", NamedTextColor.YELLOW));
                holder.setInventory(ammo);
                fillAmmo(ammo, session);
                yield ammo;
            }
            case MAGS -> {
                Inventory mags = ChestInventories.create(holder, 54,
                        Component.text("Give Magazines / Clips", NamedTextColor.GOLD));
                holder.setInventory(mags);
                fillMags(mags, session);
                yield mags;
            }
            case GRENADES -> {
                Inventory nades = ChestInventories.create(holder, 54,
                        Component.text("Give Grenades / Throwables", NamedTextColor.RED));
                holder.setInventory(nades);
                fillGrenades(nades, session);
                yield nades;
            }
            case SMOKES -> {
                Inventory smokes = ChestInventories.create(holder, 54,
                        Component.text("Smoke Grenades", NamedTextColor.GRAY));
                holder.setInventory(smokes);
                fillSmokes(smokes, session);
                yield smokes;
            }
            case FLARES -> {
                Inventory flares = ChestInventories.create(holder, 27,
                        Component.text("Road Flares", NamedTextColor.RED));
                holder.setInventory(flares);
                fillFlares(flares);
                yield flares;
            }
            case GEAR -> {
                Inventory gear = ChestInventories.create(holder, 54,
                        Component.text("Give Gear — Optics", NamedTextColor.GREEN));
                holder.setInventory(gear);
                fillGear(gear);
                yield gear;
            }
            case UAV_MUNITIONS -> {
                Inventory uav = ChestInventories.create(holder, 54,
                        Component.text("UAV Munitions", NamedTextColor.GOLD));
                holder.setInventory(uav);
                fillUavMunitions(uav);
                yield uav;
            }
            case NVG_COLORS -> {
                Inventory nvg = ChestInventories.create(holder, 27,
                        Component.text("NODS — Phosphor Colors", NamedTextColor.GREEN));
                holder.setInventory(nvg);
                fillNvgColors(nvg);
                yield nvg;
            }
            case THERMAL_MODES -> {
                Inventory th = ChestInventories.create(holder, 27,
                        Component.text("FLIR — Thermal Modes", NamedTextColor.GOLD));
                holder.setInventory(th);
                fillThermalModes(th);
                yield th;
            }
            case HAZMAT_SUIT -> {
                Inventory haz = ChestInventories.create(holder, 27,
                        Component.text("Hazmat Suit", NamedTextColor.GOLD));
                holder.setInventory(haz);
                fillHazmatSuit(haz);
                yield haz;
            }
            case FIRE_PROXIMITY_SUIT -> {
                Inventory fire = ChestInventories.create(holder, 27,
                        Component.text("Fire Proximity Suit", NamedTextColor.GRAY));
                holder.setInventory(fire);
                fillFireProximitySuit(fire);
                yield fire;
            }
            case ATTACHMENTS -> {
                Inventory attachments = ChestInventories.create(holder, 54,
                        Component.text("Attachments & Workbench", NamedTextColor.DARK_AQUA));
                holder.setInventory(attachments);
                fillAttachments(attachments);
                yield attachments;
            }
            case GLASS -> {
                Inventory glass = ChestInventories.create(holder, 54,
                        Component.text("Tactical Glass", NamedTextColor.AQUA));
                holder.setInventory(glass);
                fillGlass(glass);
                yield glass;
            }
            case DRINKS -> {
                Inventory drinks = ChestInventories.create(holder, 54,
                        Component.text("Food / Drinks", NamedTextColor.AQUA));
                holder.setInventory(drinks);
                fillDrinks(drinks);
                yield drinks;
            }
        };
        session.openInventory = inv;
        player.openInventory(inv);
    }

    private void fillHome(Inventory inv) {
        set(inv, 10, Material.CROSSBOW, "&aGuns",
                "&7Assault rifles, pistols, snipers…",
                "&eClick to browse");
        set(inv, 12, Material.IRON_SWORD, "&6WARZ GUNS!",
                "&7Classic WarZ loadout",
                "&7AK / M4 / MP5 / Barrett / AA-12…",
                "&7Mags, calibers, and sounds like live guns",
                "&eClick to browse");
        set(inv, 11, Material.FIREWORK_STAR, "&eAmmo Rounds",
                "&7Tagged FMJ / Tracer / AP / etc.",
                "&eClick to browse");
        set(inv, 21, Material.IRON_NUGGET, "&6Magazines & Clips",
                "&7Empty / loaded mags by caliber",
                "&7Click ammo onto a mag to load",
                "&eClick to browse");
        set(inv, 13, Material.TNT, "&cGrenades & Throwables",
                "&7Grenades, flashbangs, molotovs…",
                "&7Smokes & road flares inside",
                "&eClick to browse");
        set(inv, 14, Material.CARVED_PUMPKIN, "&aGear / Optics",
                "&7NODs + FLIR + UAVs + Flashlight",
                "&7Night vision, heat vision, deployable drones, light",
                "&eClick to open");
        set(inv, 15, Material.FLETCHING_TABLE, "&bAttachments",
                "&7Suppressors · lasers · flashlight · AN/PEQ-15",
                "&7Craft outfits at a placed workbench",
                "&eClick to browse");
        set(inv, 16, Material.GLASS, "&bTactical Glass",
                "&7Window · tempered · laminated · ballistic…",
                "&7Blocks + panes · caliber-aware impacts",
                "&eClick to browse");
        set(inv, 19, Material.POTION, "&bFood / Drinks",
                "&7Canned food · Dew · golden apple",
                "&7Water · Gatorade · sodas · alcohol",
                "&7Thirst + empty cans",
                "&eClick to browse");
        set(inv, 22, Material.BARRIER, "&cClose", "&7Exit menu");
    }

    private void fillDrinks(Inventory inv) {
        DrinkType[] types = DrinkType.values();
        int slot = 0;
        for (DrinkType type : types) {
            if (slot >= 45) {
                break;
            }
            ItemStack drink = plugin.items().createDrink(type, 8);
            ItemMeta meta = drink.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(text("&8────────"));
            lore.add(text("&eLeft &7→ give &fx8"));
            lore.add(text("&eRight &7→ give &fx1"));
            meta.lore(lore);
            drink.setItemMeta(meta);
            inv.setItem(slot++, drink);
        }
        for (WarzFoodType food : WarzFoodType.values()) {
            if (slot >= 45) {
                break;
            }
            ItemStack stack = plugin.items().createFood(food, 8);
            ItemMeta meta = stack.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(text("&8────────"));
            lore.add(text("&eLeft &7→ give &fx8"));
            lore.add(text("&eRight &7→ give &fx1"));
            meta.lore(lore);
            stack.setItemMeta(meta);
            inv.setItem(slot++, stack);
        }
        inv.setItem(45, withGiveHint(plugin.items().createEmptyCan(16),
                "&eLeft &7→ give &fx16", "&eRight &7→ give &fx1"));
        inv.setItem(46, withGiveHint(plugin.items().createPlasticBottle(16),
                "&eLeft &7→ give &fx16", "&eRight &7→ give &fx1"));
        inv.setItem(47, withGiveHint(plugin.items().createEmptyGlassBottle(16),
                "&eLeft &7→ give &fx16", "&eRight &7→ give &fx1"));
        inv.setItem(48, withGiveHint(plugin.items().createLifeStraw(ItemFactory.LIFE_STRAW_MAX_USES),
                "&eLeft/Right &7→ give &fx1"));
        inv.setItem(49, button(Material.ARROW, "&eHome", "&7Back to categories"));
    }

    private void fillGlass(Inventory inv) {
        int slot = 0;
        for (GlassType type : GlassType.values()) {
            if (slot >= 45) {
                break;
            }
            inv.setItem(slot++, withGiveHint(plugin.items().createGlassBlock(type),
                    "&eLeft &7→ give block x1",
                    "&eShift+Left &7→ give block x8"));
            if (slot >= 45) {
                break;
            }
            inv.setItem(slot++, withGiveHint(plugin.items().createGlassPane(type),
                    "&eLeft &7→ give pane x1",
                    "&eShift+Left &7→ give pane x8"));
        }
        inv.setItem(49, button(Material.BOOK, "&eHome", "&7Back to categories"));
    }

    private void fillAttachments(Inventory inv) {
        inv.setItem(4, withGiveHint(plugin.items().createGunWorkbenchItem(),
                "&eLeft &7→ give workbench",
                "&7Place in world, right-click to craft"));
        inv.setItem(10, withGiveHint(plugin.items().createFlashlightModulePart(),
                "&eLeft &7→ give x1",
                "&eShift+Left &7→ give x8",
                "&7Craft onto a gun at the workbench"));
        inv.setItem(12, withGiveHint(plugin.items().createPeq15Part(),
                "&eLeft &7→ give x1",
                "&eShift+Left &7→ give x8",
                "&7IR · green · flashlight · strobe",
                "&eZ &7to cycle on a fitted gun"));
        inv.setItem(14, withGiveHint(plugin.items().createMagAdapterAkAr(),
                "&eLeft &7→ give x1",
                "&eShift+Left &7→ give x8",
                "&7AK↔AR mag well adapter",
                "&7Not for .50 / sniper / shotgun"));

        int slot = 19;
        for (SuppressorType type : SuppressorType.values()) {
            inv.setItem(slot++, withGiveHint(plugin.items().createSuppressorPart(type),
                    "&eLeft &7→ give x1",
                    "&eShift+Left &7→ give x8"));
        }
        slot = 28;
        for (LaserModColor color : LaserModColor.installable()) {
            if (slot >= 37) {
                break;
            }
            inv.setItem(slot++, withGiveHint(plugin.items().createLaserModulePart(color),
                    "&eLeft &7→ give x1",
                    "&eShift+Left &7→ give x8"));
        }
        slot = 37;
        for (OpticType optic : OpticType.installable()) {
            if (slot >= 45) {
                break;
            }
            inv.setItem(slot++, withGiveHint(plugin.items().createOpticPart(optic),
                    "&eLeft &7→ give x1",
                    "&eShift+Left &7→ give x8",
                    "&7Rail optic — 3D model on fitted gun",
                    "&7Workbench → Optic slot"));
        }
        slot = 45;
        for (GripType grip : GripType.installable()) {
            if (slot >= 49) {
                break;
            }
            inv.setItem(slot++, withGiveHint(plugin.items().createGripPart(grip),
                    "&eLeft &7→ give x1",
                    "&eShift+Left &7→ give x8",
                    "&7Foregrip / bipod — workbench Grip slot"));
        }
        inv.setItem(49, button(Material.BOOK, "&eHome", "&7Back to categories"));
    }

    private static ItemStack withGiveHint(ItemStack stack, String... extra) {
        ItemMeta meta = stack.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(text("&8────────"));
        for (String line : extra) {
            lore.add(text(line));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private void placeRoundGive(Inventory inv, int slot, String roundId) {
        plugin.rounds().get(roundId).ifPresentOrElse(round -> {
            ItemStack stack = plugin.items().createRound(round, 1);
            inv.setItem(slot, withGiveHint(stack,
                    "&eLeft &7→ give &fx1",
                    "&eShift+Left &7→ give &fx8",
                    "&7Load in UAV payload bay"));
        }, () -> inv.setItem(slot, button(Material.BARRIER, "&cMissing " + roundId,
                "&7Round file not loaded")));
    }

    private static final String[] UAV_ROUND_IDS = {
            "rocket_he", "rocket_ap", "rocket_hp", "rocket_aa", "rocket_r9x",
            "rocket_mac", "rocket_romeo", "rocket_jagm",
            "gbu_viper", "gbu_sgm", "gbu_sdb", "gbu_storm", "gbu_paveway",
            "gbu_sonar", "aim9x"
    };

    private void fillUavMunitions(Inventory inv) {
        int[] slots = {
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29
        };
        for (int i = 0; i < UAV_ROUND_IDS.length && i < slots.length; i++) {
            placeRoundGive(inv, slots[i], UAV_ROUND_IDS[i]);
        }
        inv.setItem(49, button(Material.ARROW, "&eBack", "&7Return to Gear"));
    }

    /**
     * Where the airframes sit on the Gear page. Shared with the click handler:
     * these were two separate lists, and the click side only covered 28..33 with
     * a slot-minus-28 index, so the seventh airframe - the X-37B on slot 35 -
     * was drawn but could not be taken.
     */
    private static final int[] DRONE_SLOTS = {28, 29, 30, 31, 32, 33, 35};

    private static int droneIndexForSlot(int slot) {
        for (int i = 0; i < DRONE_SLOTS.length; i++) {
            if (DRONE_SLOTS[i] == slot) return i;
        }
        return -1;
    }

    private void fillGear(Inventory inv) {
        inv.setItem(10, helmetIcon(plugin.items().createNvgHelmet(NvgGear.Variant.MULTI)));
        inv.setItem(11, button(Material.LIME_DYE, "&aNODS Colors",
                "&7Green / White / Amber / Blue / Red / True",
                "&eClick &7— fixed phosphor (H = on/off)"));

        // Airframes (mesh / systems vary — tanks, sensors, weapons, cargo)
        int[] droneSlots = DRONE_SLOTS;
        BigDroneType[] drones = BigDroneType.values();
        for (int i = 0; i < drones.length && i < droneSlots.length; i++) {
            ItemStack drone = plugin.items().createBigDrone(drones[i]);
            ItemMeta droneMeta = drone.getItemMeta();
            List<Component> droneLore = droneMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(droneMeta.lore());
            droneLore.add(text("&8────────"));
            droneLore.add(text("&eLeft &7→ give &fx1"));
            droneMeta.lore(droneLore);
            drone.setItemMeta(droneMeta);
            inv.setItem(droneSlots[i], drone);
        }

        ItemStack link = plugin.items().createRadiolink();
        ItemMeta linkMeta = link.getItemMeta();
        List<Component> linkLore = linkMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(linkMeta.lore());
        linkLore.add(text("&8────────"));
        linkLore.add(text("&eLeft &7→ give &fx1"));
        linkMeta.lore(linkLore);
        link.setItemMeta(linkMeta);
        // Slot 5 — kept free of scuba/medical overwrites (slot 12 is scuba helm).
        inv.setItem(5, link);

        ItemStack jetFuel = plugin.items().createJetFuelCan(1);
        ItemMeta fuelMeta = jetFuel.getItemMeta();
        List<Component> fuelLore = fuelMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(fuelMeta.lore());
        fuelLore.add(text("&8────────"));
        fuelLore.add(text("&eLeft &7→ give &fx1"));
        fuelLore.add(text("&eShift+Left &7→ give &fx8"));
        fuelMeta.lore(fuelLore);
        jetFuel.setItemMeta(fuelMeta);
        inv.setItem(6, jetFuel);

        ItemStack hydrazine = plugin.items().createHydrazineFuelCan(1);
        ItemMeta hydMeta = hydrazine.getItemMeta();
        List<Component> hydLore = hydMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(hydMeta.lore());
        hydLore.add(text("&8────────"));
        hydLore.add(text("&eLeft &7→ give &fx1"));
        hydLore.add(text("&eShift+Left &7→ give &fx8"));
        hydMeta.lore(hydLore);
        hydrazine.setItemMeta(hydMeta);
        inv.setItem(37, hydrazine);

        // UAV munitions live on a dedicated page (avoid medical / scuba slot collisions).
        inv.setItem(34, button(Material.FIREWORK_ROCKET, "&6UAV Munitions",
                "&7Hellfires · JAGM · GBUs · AIM-9X",
                "&7Load in UAV payload bay",
                "&eClick to browse"));

        ItemStack metal = plugin.items().createMetal(64);
        ItemMeta metalMeta = metal.getItemMeta();
        List<Component> metalLore = metalMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(metalMeta.lore());
        metalLore.add(text("&8────────"));
        metalLore.add(text("&eLeft &7→ give &fx64"));
        metalLore.add(text("&eShift+Left &7→ give &fx99"));
        metalMeta.lore(metalLore);
        metal.setItemMeta(metalMeta);
        inv.setItem(7, metal);

        inv.setItem(15, helmetIcon(plugin.items().createThermalHelmet(ThermalGear.Variant.MULTI)));
        inv.setItem(16, button(Material.ORANGE_DYE, "&6Thermal Modes",
                "&7White Hot / Black Hot / Ironbow / Rainbow / Fusion",
                "&eClick &7— fixed palette (H = on/off)"));

        ItemStack light = plugin.items().createFlashlight();
        ItemMeta lightMeta = light.getItemMeta();
        List<Component> lightLore = lightMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(lightMeta.lore());
        lightLore.add(text("&8────────"));
        lightLore.add(text("&eLeft &7→ give &fx1"));
        lightMeta.lore(lightLore);
        light.setItemMeta(lightMeta);
        inv.setItem(17, light);

        ItemStack grapple = plugin.items().createGrapplingHook();
        ItemMeta grappleMeta = grapple.getItemMeta();
        List<Component> grappleLore = grappleMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(grappleMeta.lore());
        grappleLore.add(text("&8────────"));
        grappleLore.add(text("&eLeft/Right &7→ give &fx1"));
        grappleMeta.lore(grappleLore);
        grapple.setItemMeta(grappleMeta);
        inv.setItem(4, grapple);

        ItemStack splint = plugin.items().createSplint(8);
        ItemMeta splintMeta = splint.getItemMeta();
        List<Component> splintLore = splintMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(splintMeta.lore());
        splintLore.add(text("&8────────"));
        splintLore.add(text("&eLeft &7→ give &fx8"));
        splintLore.add(text("&eRight &7→ give &fx1"));
        splintMeta.lore(splintLore);
        splint.setItemMeta(splintMeta);
        inv.setItem(20, splint);

        ItemStack bandage = plugin.items().createBandage(8);
        ItemMeta bandageMeta = bandage.getItemMeta();
        List<Component> bandageLore = bandageMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(bandageMeta.lore());
        bandageLore.add(text("&8────────"));
        bandageLore.add(text("&eLeft &7→ give &fx8"));
        bandageLore.add(text("&eRight &7→ give &fx1"));
        bandageMeta.lore(bandageLore);
        bandage.setItemMeta(bandageMeta);
        inv.setItem(21, bandage);

        ItemStack tourniquet = plugin.items().createTourniquet(4);
        ItemMeta tqMeta = tourniquet.getItemMeta();
        List<Component> tqLore = tqMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(tqMeta.lore());
        tqLore.add(text("&8────────"));
        tqLore.add(text("&eLeft &7→ give &fx4"));
        tqLore.add(text("&eRight &7→ give &fx1"));
        tqMeta.lore(tqLore);
        tourniquet.setItemMeta(tqMeta);
        inv.setItem(18, tourniquet);

        ItemStack bloodBag = plugin.items().createBloodBag(4);
        ItemMeta bagMeta = bloodBag.getItemMeta();
        List<Component> bagLore = bagMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(bagMeta.lore());
        bagLore.add(text("&8────────"));
        bagLore.add(text("&eLeft &7→ give &fx4"));
        bagLore.add(text("&eRight &7→ give &fx1"));
        bagMeta.lore(bagLore);
        bloodBag.setItemMeta(bagMeta);
        inv.setItem(19, bloodBag);

        ItemStack chainlink = plugin.items().createChainlink(16);
        ItemMeta chainMeta = chainlink.getItemMeta();
        List<Component> chainLore = chainMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(chainMeta.lore());
        chainLore.add(text("&8────────"));
        chainLore.add(text("&eLeft &7→ give &fx16"));
        chainLore.add(text("&eRight &7→ give &fx1"));
        chainMeta.lore(chainLore);
        chainlink.setItemMeta(chainMeta);
        inv.setItem(9, chainlink);

        ItemStack razor = plugin.items().createRazorWire(16);
        ItemMeta razorMeta = razor.getItemMeta();
        List<Component> razorLore = razorMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(razorMeta.lore());
        razorLore.add(text("&8────────"));
        razorLore.add(text("&eLeft &7→ give &fx16"));
        razorLore.add(text("&eRight &7→ give &fx1"));
        razorMeta.lore(razorLore);
        razor.setItemMeta(razorMeta);
        inv.setItem(23, razor);

        ItemStack cutters = plugin.items().createWireCutters(1);
        ItemMeta cuttersMeta = cutters.getItemMeta();
        List<Component> cuttersLore = cuttersMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(cuttersMeta.lore());
        cuttersLore.add(text("&8────────"));
        cuttersLore.add(text("&eLeft/Right &7→ give &fx1"));
        cuttersMeta.lore(cuttersLore);
        cutters.setItemMeta(cuttersMeta);
        inv.setItem(24, cutters);

        ItemStack prongs = plugin.items().createLongProngs(false);
        ItemMeta prongsMeta = prongs.getItemMeta();
        List<Component> prongsLore = prongsMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(prongsMeta.lore());
        prongsLore.add(text("&8────────"));
        prongsLore.add(text("&eLeft/Right &7→ give &fx1"));
        prongsMeta.lore(prongsLore);
        prongs.setItemMeta(prongsMeta);
        inv.setItem(3, prongs);

        placeGiveIcon(inv, 0, plugin.items().createHandcuffs(1), 1, 4);
        placeGiveIcon(inv, 1, plugin.items().createHandcuffKey(1), 1, 4);
        placeGiveIcon(inv, 2, plugin.items().createLockpick(1), 1, 8);
        placeGiveIcon(inv, 27, plugin.items().createZipTies(1), 1, 8);
        placeGiveIcon(inv, 36, plugin.items().createPocketKnife(1), 1, 1);

        ItemStack shards = plugin.items().createObsidianShards(16);
        ItemMeta shardsMeta = shards.getItemMeta();
        List<Component> shardsLore = shardsMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(shardsMeta.lore());
        shardsLore.add(text("&8────────"));
        shardsLore.add(text("&eLeft &7→ give &fx16"));
        shardsLore.add(text("&eRight &7→ give &fx4"));
        shardsMeta.lore(shardsLore);
        shards.setItemMeta(shardsMeta);
        inv.setItem(8, shards);

        ItemStack scubaHelm = plugin.items().createScubaHelmet();
        ItemMeta scubaHelmMeta = scubaHelm.getItemMeta();
        List<Component> scubaHelmLore = scubaHelmMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(scubaHelmMeta.lore());
        scubaHelmLore.add(text("&8────────"));
        scubaHelmLore.add(text("&eLeft &7→ give  &eRight &7→ equip"));
        scubaHelmMeta.lore(scubaHelmLore);
        scubaHelm.setItemMeta(scubaHelmMeta);
        inv.setItem(12, scubaHelm);

        ItemStack scubaTank = plugin.items().createScubaTank();
        ItemMeta scubaTankMeta = scubaTank.getItemMeta();
        List<Component> scubaTankLore = scubaTankMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(scubaTankMeta.lore());
        scubaTankLore.add(text("&8────────"));
        scubaTankLore.add(text("&eLeft &7→ give  &eRight &7→ equip"));
        scubaTankMeta.lore(scubaTankLore);
        scubaTank.setItemMeta(scubaTankMeta);
        inv.setItem(14, scubaTank);

        ItemStack wetsuitLegs = plugin.items().createWetsuitLeggings();
        ItemMeta wetsuitLegsMeta = wetsuitLegs.getItemMeta();
        List<Component> wetsuitLegsLore = wetsuitLegsMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(wetsuitLegsMeta.lore());
        wetsuitLegsLore.add(text("&8────────"));
        wetsuitLegsLore.add(text("&eLeft &7→ give  &eRight &7→ equip"));
        wetsuitLegsMeta.lore(wetsuitLegsLore);
        wetsuitLegs.setItemMeta(wetsuitLegsMeta);
        inv.setItem(25, wetsuitLegs);

        ItemStack wetsuitBoots = plugin.items().createWetsuitBoots();
        ItemMeta wetsuitBootsMeta = wetsuitBoots.getItemMeta();
        List<Component> wetsuitBootsLore = wetsuitBootsMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(wetsuitBootsMeta.lore());
        wetsuitBootsLore.add(text("&8────────"));
        wetsuitBootsLore.add(text("&eLeft &7→ give  &eRight &7→ equip"));
        wetsuitBootsMeta.lore(wetsuitBootsLore);
        wetsuitBoots.setItemMeta(wetsuitBootsMeta);
        inv.setItem(26, wetsuitBoots);

        // Slot 38, not 10: slot 10 is the NVG helmet. This button used to be drawn
        // over it, which hid the helmet and - because the click handler checks
        // slot 10 for the helmet first - made this button hand out an NVG helmet
        // and never open the hazmat page at all.
        inv.setItem(38, button(Material.ORANGE_DYE, "&6Hazmat Suit",
                "&7Orange chemical oversuit",
                "&aBlocks X-37B hydrazine vapor",
                "&eClick to browse / equip"));
        inv.setItem(13, button(Material.IRON_INGOT, "&7Fire Proximity Suit",
                "&7Silver aluminized turnout gear",
                "&eLava heat safe beyond &f1 &eblock",
                "&eClick to browse / equip"));

        inv.setItem(22, button(Material.BOOK, "&eHome", "&7Back to categories"));
    }

    private void fillHazmatSuit(Inventory inv) {
        placeSuitIcon(inv, 10, plugin.items().createHazmatHelmet(), ArmorSlot.HELMET);
        placeSuitIcon(inv, 12, plugin.items().createHazmatChestplate(), ArmorSlot.CHEST);
        placeSuitIcon(inv, 14, plugin.items().createHazmatLeggings(), ArmorSlot.LEGS);
        placeSuitIcon(inv, 16, plugin.items().createHazmatBoots(), ArmorSlot.BOOTS);
        inv.setItem(22, button(Material.ARROW, "&eBack", "&7Return to Gear"));
    }

    private void fillFireProximitySuit(Inventory inv) {
        placeSuitIcon(inv, 10, plugin.items().createFireProximityHelmet(), ArmorSlot.HELMET);
        placeSuitIcon(inv, 12, plugin.items().createFireProximityChestplate(), ArmorSlot.CHEST);
        placeSuitIcon(inv, 14, plugin.items().createFireProximityLeggings(), ArmorSlot.LEGS);
        placeSuitIcon(inv, 16, plugin.items().createFireProximityBoots(), ArmorSlot.BOOTS);
        inv.setItem(22, button(Material.ARROW, "&eBack", "&7Return to Gear"));
    }

    private void placeSuitIcon(Inventory inv, int slot, ItemStack piece, ArmorSlot ignored) {
        ItemMeta meta = piece.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(text("&8────────"));
        lore.add(text("&eLeft &7→ give  &eRight &7→ equip"));
        meta.lore(lore);
        piece.setItemMeta(meta);
        inv.setItem(slot, piece);
    }

    private void placeGiveIcon(Inventory inv, int slot, ItemStack stack, int rightAmt, int leftAmt) {
        ItemMeta meta = stack.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(text("&8────────"));
        if (leftAmt == rightAmt) {
            lore.add(text("&eLeft/Right &7→ give &fx" + leftAmt));
        } else {
            lore.add(text("&eLeft &7→ give &fx" + leftAmt));
            lore.add(text("&eRight &7→ give &fx" + rightAmt));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        inv.setItem(slot, stack);
    }

    private void fillNvgColors(Inventory inv) {
        NvgGear.Variant[] fixed = {
                NvgGear.Variant.GREEN, NvgGear.Variant.WHITE, NvgGear.Variant.AMBER,
                NvgGear.Variant.BLUE, NvgGear.Variant.RED, NvgGear.Variant.TRUE_COLOR
        };
        int[] slots = {10, 11, 12, 13, 14, 15};
        for (int i = 0; i < fixed.length; i++) {
            inv.setItem(slots[i], helmetIcon(plugin.items().createNvgHelmet(fixed[i])));
        }
        inv.setItem(22, button(Material.ARROW, "&eBack", "&7Return to Optics"));
    }

    private void fillThermalModes(Inventory inv) {
        ThermalGear.Variant[] fixed = {
                ThermalGear.Variant.WHITE_HOT, ThermalGear.Variant.BLACK_HOT, ThermalGear.Variant.IRONBOW,
                ThermalGear.Variant.RAINBOW, ThermalGear.Variant.FUSION
        };
        int[] slots = {11, 12, 13, 14, 15};
        for (int i = 0; i < fixed.length; i++) {
            inv.setItem(slots[i], helmetIcon(plugin.items().createThermalHelmet(fixed[i])));
        }
        inv.setItem(22, button(Material.ARROW, "&eBack", "&7Return to Optics"));
    }

    private static ItemStack helmetIcon(ItemStack helm) {
        ItemMeta meta = helm.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(text("&8────────"));
        lore.add(text("&eLeft &7→ give helmet"));
        lore.add(text("&eRight &7→ equip on head now"));
        meta.lore(lore);
        helm.setItemMeta(meta);
        return helm;
    }

    private void giveOrEquipHelmet(Player player, ItemStack helm, boolean left, boolean right) {
        giveOrEquipArmor(player, helm, left, right, ArmorSlot.HELMET);
    }

    private enum ArmorSlot { HELMET, CHEST, LEGS, BOOTS }

    private void giveOrEquipArmor(Player player, ItemStack piece, boolean left, boolean right, ArmorSlot slot) {
        if (piece == null || (!left && !right)) {
            return;
        }
        Component name = piece.hasItemMeta() && piece.getItemMeta().hasDisplayName()
                ? piece.getItemMeta().displayName()
                : Component.text("armor");
        if (right) {
            ItemStack old = switch (slot) {
                case HELMET -> player.getInventory().getHelmet();
                case CHEST -> player.getInventory().getChestplate();
                case LEGS -> player.getInventory().getLeggings();
                case BOOTS -> player.getInventory().getBoots();
            };
            switch (slot) {
                case HELMET -> player.getInventory().setHelmet(piece);
                case CHEST -> player.getInventory().setChestplate(piece);
                case LEGS -> player.getInventory().setLeggings(piece);
                case BOOTS -> player.getInventory().setBoots(piece);
            }
            if (old != null && old.getType() != Material.AIR) {
                give(player, old);
            }
            player.sendMessage(Component.text("Equipped ", NamedTextColor.GREEN).append(name));
        } else {
            give(player, piece);
            player.sendMessage(Component.text("Gave ", NamedTextColor.GREEN).append(name));
        }
    }

    private void fillFlares(Inventory inv) {
        int[] slots = {11, 13, 15};
        FlareColor[] colors = FlareColor.values();
        for (int i = 0; i < colors.length; i++) {
            ItemStack flare = plugin.items().createRoadFlare(colors[i], 1);
            ItemMeta meta = flare.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(text("&8────────"));
            lore.add(text("&eLeft &7→ give &fx1"));
            lore.add(text("&eShift+Left &7→ give &fx8"));
            lore.add(text("&eRight &7→ give &fx4"));
            meta.lore(lore);
            flare.setItemMeta(meta);
            inv.setItem(slots[i], flare);
        }
        inv.setItem(22, button(Material.TNT, "&cGrenades", "&7Back to grenades & throwables"));
    }

    private void fillGuns(Inventory inv, Session session) {
        List<GunDefinition> guns = weapons().stream()
                .sorted(Comparator.comparing(GunDefinition::fileName))
                .toList();
        fillPagedItems(inv, session, guns.size(), (slot, index) -> {
            GunDefinition gun = guns.get(index);
            ItemStack icon = plugin.items().create(gun, 1);
            ItemMeta meta = icon.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(text("&8────────"));
            lore.add(text("&eLeft &7→ give &fx1"));
            lore.add(text("&eShift+Left &7→ give &fx16"));
            lore.add(text("&eRight &7→ give gun + &f64 &7primary ammo"));
            meta.lore(lore);
            icon.setItemMeta(meta);
            inv.setItem(slot, icon);
        });
        fillNav(inv, session, "&aGuns");
    }

    private void fillWarzGuns(Inventory inv, Session session) {
        List<GunDefinition> guns = warzCatalog().stream()
                .sorted(Comparator.comparing(GunDefinition::fileName))
                .toList();
        fillPagedItems(inv, session, guns.size(), (slot, index) -> {
            GunDefinition gun = guns.get(index);
            ItemStack icon = plugin.items().create(gun, 1);
            ItemMeta meta = icon.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(text("&8────────"));
            lore.add(text("&6WarZ catalog"));
            lore.add(text("&eLeft &7→ give &fx1"));
            lore.add(text("&eShift+Left &7→ give &fx16"));
            lore.add(text("&eRight &7→ give gun + &f64 &7ammo + mag"));
            meta.lore(lore);
            icon.setItemMeta(meta);
            inv.setItem(slot, icon);
        });
        fillNav(inv, session, "&6WARZ GUNS!");
    }

    private void fillAmmo(Inventory inv, Session session) {
        List<RoundDefinition> rounds = plugin.rounds().all().stream()
                .sorted(Comparator.comparing(RoundDefinition::fileName))
                .toList();
        fillPagedItems(inv, session, rounds.size(), (slot, index) -> {
            RoundDefinition round = rounds.get(index);
            ItemStack icon = plugin.items().createRound(round, 1);
            ItemMeta meta = icon.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(text("&8────────"));
            lore.add(text("&eLeft &7→ give &fx64"));
            lore.add(text("&eShift+Left &7→ give &fx16"));
            lore.add(text("&eRight &7→ give &fx1"));
            meta.lore(lore);
            icon.setItemMeta(meta);
            inv.setItem(slot, icon);
        });
        fillNav(inv, session, "&eAmmo");
    }

    private void fillMags(Inventory inv, Session session) {
        MagazineType[] types = MagazineType.values();
        fillPagedItems(inv, session, types.length, (slot, index) -> {
            MagazineType type = types[index];
            ItemStack icon = plugin.items().createMagazine(type, 1);
            ItemMeta meta = icon.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(text("&8────────"));
            lore.add(text(ItemFactory.adapterHintLine(type)));
            lore.add(text("&7Models: &fempty &7/ &floaded &7(auto-swaps)"));
            lore.add(text("&eLeft &7→ empty mag &fx1"));
            lore.add(text("&eShift+Left &7→ empty &fx8"));
            lore.add(text("&eRight &7→ &ffull loaded &7with primary ammo"));
            meta.lore(lore);
            icon.setItemMeta(meta);
            inv.setItem(slot, icon);
        });
        fillNav(inv, session, "&6Mags");
    }

    private void fillGrenades(Inventory inv, Session session) {
        List<GunDefinition> items = throwables().stream()
                .sorted(Comparator.comparing(GunDefinition::fileName))
                .toList();
        // Throwables, then Smoke / Flares submenu entries in the same grid
        int total = items.size() + 2;
        fillPagedItems(inv, session, total, (slot, index) -> {
            if (index < items.size()) {
                GunDefinition gun = items.get(index);
                ItemStack icon = plugin.items().create(gun, 1);
                ItemMeta meta = icon.getItemMeta();
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.add(text("&8────────"));
                lore.add(text("&eLeft &7→ give &fx1"));
                lore.add(text("&eShift+Left &7→ give &fx16"));
                lore.add(text("&eRight &7→ give &fx8"));
                meta.lore(lore);
                icon.setItemMeta(meta);
                inv.setItem(slot, icon);
                return;
            }
            if (index == items.size()) {
                inv.setItem(slot, button(Material.CAMPFIRE, "&7Smoke Grenades",
                        "&7White / colored / IR / thermal / multi…",
                        "&7Affects NVG wash & thermal differently",
                        "&eClick to open submenu"));
                return;
            }
            inv.setItem(slot, button(Material.REDSTONE_TORCH, "&cRoad Flares",
                    "&7Red / green / blue marker flares",
                    "&7Step on → catch fire · step off → out",
                    "&7Burns ~20 min each",
                    "&eClick to open submenu"));
        });
        fillNav(inv, session, "&cGrenades");
    }

    private void fillSmokes(Inventory inv, Session session) {
        SmokeType[] types = SmokeType.values();
        fillPagedItems(inv, session, types.length, (slot, index) -> {
            SmokeType type = types[index];
            ItemStack icon = plugin.items().createSmokeGrenade(type, 1);
            ItemMeta meta = icon.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(text("&8────────"));
            lore.add(text("&eLeft &7→ give &fx1"));
            lore.add(text("&eShift+Left &7→ give &fx8"));
            lore.add(text("&eRight &7→ give &fx4"));
            meta.lore(lore);
            // Show type icon material while keeping smoke PDC from factory snowball…
            // Replace visual with dye/icon but keep meta from snowball stack.
            ItemStack show = new ItemStack(type.icon(), 1);
            ItemMeta showMeta = show.getItemMeta();
            showMeta.displayName(meta.displayName());
            showMeta.lore(meta.lore());
            showMeta.getPersistentDataContainer().set(
                    plugin.items().smokeKey(),
                    org.bukkit.persistence.PersistentDataType.STRING,
                    type.fileKey());
            show.setItemMeta(showMeta);
            inv.setItem(slot, show);
        });
        fillNavBackToGrenades(inv, session, "&7Smokes");
    }

    private interface SlotFiller {
        void fill(int slot, int index);
    }

    private void fillPagedItems(Inventory inv, Session session, int total, SlotFiller filler) {
        int perPage = 45;
        int maxPage = Math.max(0, (total - 1) / perPage);
        session.browserPage = Math.min(Math.max(0, session.browserPage), maxPage);
        int start = session.browserPage * perPage;
        for (int i = 0; i < perPage && start + i < total; i++) {
            filler.fill(i, start + i);
        }
    }

    private void fillNav(Inventory inv, Session session, String title) {
        inv.setItem(45, button(Material.ARROW, "&ePrevious",
                "&7Page " + (session.browserPage + 1), "&8" + title));
        inv.setItem(49, button(Material.BOOK, "&eHome", "&7Back to categories"));
        inv.setItem(53, button(Material.ARROW, "&eNext",
                "&7Page " + (session.browserPage + 1), "&8" + title));
    }

    private void fillNavBackToGrenades(Inventory inv, Session session, String title) {
        inv.setItem(45, button(Material.ARROW, "&ePrevious",
                "&7Page " + (session.browserPage + 1), "&8" + title));
        inv.setItem(49, button(Material.TNT, "&cGrenades", "&7Back to grenades & throwables"));
        inv.setItem(53, button(Material.ARROW, "&eNext",
                "&7Page " + (session.browserPage + 1), "&8" + title));
    }

    public void handleClick(Player player, int slot, boolean left, boolean right, boolean shift) {
        Session session = session(player);
        if (session.page == Page.HOME) {
            if (slot == 10) {
                session.page = Page.GUNS;
                session.browserPage = 0;
                render(player);
            } else if (slot == 12) {
                session.page = Page.WARZ_GUNS;
                session.browserPage = 0;
                render(player);
            } else if (slot == 11) {
                session.page = Page.AMMO;
                session.browserPage = 0;
                render(player);
            } else if (slot == 21) {
                session.page = Page.MAGS;
                session.browserPage = 0;
                render(player);
            } else if (slot == 13) {
                session.page = Page.GRENADES;
                session.browserPage = 0;
                render(player);
            } else if (slot == 14) {
                session.page = Page.GEAR;
                session.browserPage = 0;
                render(player);
            } else if (slot == 15) {
                session.page = Page.ATTACHMENTS;
                session.browserPage = 0;
                render(player);
            } else if (slot == 16) {
                session.page = Page.GLASS;
                session.browserPage = 0;
                render(player);
            } else if (slot == 19) {
                session.page = Page.DRINKS;
                render(player);
            } else if (slot == 22) {
                player.closeInventory();
            }
            return;
        }

        if (session.page == Page.DRINKS) {
            if (slot == 49) {
                session.page = Page.HOME;
                render(player);
                return;
            }
            DrinkType[] types = DrinkType.values();
            if (slot >= 0 && slot < types.length && slot < 45 && (left || right)) {
                int amt = left ? 8 : 1;
                give(player, plugin.items().createDrink(types[slot], amt));
                player.sendMessage(Component.text("Gave " + types[slot].name() + " x" + amt, NamedTextColor.AQUA));
                return;
            }
            WarzFoodType[] foods = WarzFoodType.values();
            int foodSlot = slot - types.length;
            if (foodSlot >= 0 && foodSlot < foods.length && slot < 45 && (left || right)) {
                int amt = left ? 8 : 1;
                give(player, plugin.items().createFood(foods[foodSlot], amt));
                player.sendMessage(Component.text("Gave " + foods[foodSlot].name() + " x" + amt, NamedTextColor.GOLD));
                return;
            }
            if (slot == 45 && (left || right)) {
                int amt = left ? 16 : 1;
                give(player, plugin.items().createEmptyCan(amt));
                player.sendMessage(Component.text("Gave Empty Can x" + amt, NamedTextColor.GRAY));
                return;
            }
            if (slot == 46 && (left || right)) {
                int amt = left ? 16 : 1;
                give(player, plugin.items().createPlasticBottle(amt));
                player.sendMessage(Component.text("Gave Plastic Bottle x" + amt, NamedTextColor.GRAY));
                return;
            }
            if (slot == 47 && (left || right)) {
                int amt = left ? 16 : 1;
                give(player, plugin.items().createEmptyGlassBottle(amt));
                player.sendMessage(Component.text("Gave Glass Bottle x" + amt, NamedTextColor.GRAY));
                return;
            }
            if (slot == 48 && (left || right)) {
                give(player, plugin.items().createLifeStraw(ItemFactory.LIFE_STRAW_MAX_USES));
                player.sendMessage(Component.text("Gave Life Straw", NamedTextColor.GREEN));
            }
            return;
        }

        if (session.page == Page.ATTACHMENTS) {
            handleAttachmentsClick(player, session, slot, left, right, shift);
            return;
        }

        if (session.page == Page.GLASS) {
            handleGlassClick(player, session, slot, left, right, shift);
            return;
        }

        if (session.page == Page.FLARES) {
            if (slot == 22) {
                session.page = Page.GRENADES;
                session.browserPage = 0;
                render(player);
                return;
            }
            FlareColor color = switch (slot) {
                case 11 -> FlareColor.RED;
                case 13 -> FlareColor.GREEN;
                case 15 -> FlareColor.BLUE;
                default -> null;
            };
            if (color != null && (left || right)) {
                int amount = shift ? 8 : (right ? 4 : 1);
                give(player, plugin.items().createRoadFlare(color, amount));
                NamedTextColor chat = switch (color) {
                    case RED -> NamedTextColor.RED;
                    case GREEN -> NamedTextColor.GREEN;
                    case BLUE -> NamedTextColor.AQUA;
                };
                player.sendMessage(Component.text("Gave " + color.name().charAt(0)
                        + color.name().substring(1).toLowerCase()
                        + " Road Flare x" + amount, chat));
            }
            return;
        }

        if (session.page == Page.UAV_MUNITIONS) {
            if (slot == 49) {
                session.page = Page.GEAR;
                render(player);
                return;
            }
            if (left || right) {
                int[] uavSlots = {
                        10, 11, 12, 13, 14, 15, 16,
                        19, 20, 21, 22, 23, 24, 25,
                        28, 29
                };
                for (int i = 0; i < UAV_ROUND_IDS.length && i < uavSlots.length; i++) {
                    if (slot == uavSlots[i]) {
                        String rid = UAV_ROUND_IDS[i];
                        int amt = shift ? 8 : 1;
                        plugin.rounds().get(rid).ifPresent(round -> {
                            give(player, plugin.items().createRound(round, amt));
                            player.sendMessage(Component.text(
                                    "Gave " + rid + " x" + amt, NamedTextColor.GOLD));
                        });
                        return;
                    }
                }
            }
            return;
        }
        if (session.page == Page.GEAR) {
            if (slot == 22) {
                session.page = Page.HOME;
                render(player);
                return;
            }
            if (slot == 34) {
                session.page = Page.UAV_MUNITIONS;
                render(player);
                return;
            }
            if (slot == 11) {
                session.page = Page.NVG_COLORS;
                render(player);
                return;
            }
            if (slot == 16) {
                session.page = Page.THERMAL_MODES;
                render(player);
                return;
            }
            if (slot == 10) {
                giveOrEquipHelmet(player, plugin.items().createNvgHelmet(NvgGear.Variant.MULTI), left, right);
            } else if (slot == 5) {
                if (left || right) {
                    give(player, plugin.items().createRadiolink());
                    player.sendMessage(Component.text("Gave Radiolink", NamedTextColor.LIGHT_PURPLE));
                }
            } else if (droneIndexForSlot(slot) >= 0) {
                if (left || right) {
                    BigDroneType[] drones = BigDroneType.values();
                    int idx = droneIndexForSlot(slot);
                    if (idx < drones.length) {
                        BigDroneType t = drones[idx];
                        give(player, plugin.items().createBigDrone(t));
                        player.sendMessage(Component.text("Gave " + t.displayName(), NamedTextColor.AQUA));
                    }
                }
            } else if (slot == 6) {
                if (left || right) {
                    int amt = shift ? 8 : 1;
                    give(player, plugin.items().createJetFuelCan(amt));
                    player.sendMessage(Component.text("Gave Jet Fuel Can x" + amt, NamedTextColor.RED));
                }
            } else if (slot == 37) {
                if (left || right) {
                    int amt = shift ? 8 : 1;
                    give(player, plugin.items().createHydrazineFuelCan(amt));
                    player.sendMessage(Component.text("Gave Hydrazine Fuel Can x" + amt, NamedTextColor.WHITE));
                }
            } else if (slot == 7) {
                if (left || right) {
                    int amt = shift ? ItemFactory.METAL_STACK_MAX : 64;
                    give(player, plugin.items().createMetal(amt));
                    player.sendMessage(Component.text("Gave Metal x" + amt, NamedTextColor.WHITE));
                }
            } else if (slot == 15) {
                giveOrEquipHelmet(player, plugin.items().createThermalHelmet(ThermalGear.Variant.MULTI), left, right);
            } else if (slot == 17) {
                if (left || right) {
                    give(player, plugin.items().createFlashlight());
                    player.sendMessage(Component.text("Gave Tactical Flashlight", NamedTextColor.YELLOW));
                }
            } else if (slot == 4) {
                if (left || right) {
                    give(player, plugin.items().createGrapplingHook());
                    player.sendMessage(Component.text("Gave Grappling Hook", NamedTextColor.DARK_GREEN));
                }
            } else if (slot == 20) {
                if (left || right) {
                    int amt = left ? 8 : 1;
                    give(player, plugin.items().createSplint(amt));
                    player.sendMessage(Component.text("Gave Splint x" + amt, NamedTextColor.RED));
                }
            } else if (slot == 21) {
                if (left || right) {
                    int amt = left ? 8 : 1;
                    give(player, plugin.items().createBandage(amt));
                    player.sendMessage(Component.text("Gave Bandage x" + amt, NamedTextColor.WHITE));
                }
            } else if (slot == 18) {
                if (left || right) {
                    int amt = left ? 4 : 1;
                    give(player, plugin.items().createTourniquet(amt));
                    player.sendMessage(Component.text("Gave Tourniquet x" + amt, NamedTextColor.GOLD));
                }
            } else if (slot == 19) {
                if (left || right) {
                    int amt = left ? 4 : 1;
                    give(player, plugin.items().createBloodBag(amt));
                    player.sendMessage(Component.text("Gave Blood Bag x" + amt, NamedTextColor.DARK_RED));
                }
            } else if (slot == 9) {
                if (left || right) {
                    int amt = left ? 16 : 1;
                    give(player, plugin.items().createChainlink(amt));
                    player.sendMessage(Component.text("Gave Chainlink x" + amt, NamedTextColor.GRAY));
                }
            } else if (slot == 23) {
                if (left || right) {
                    int amt = left ? 16 : 1;
                    give(player, plugin.items().createRazorWire(amt));
                    player.sendMessage(Component.text("Gave Razor Wire x" + amt, NamedTextColor.DARK_GRAY));
                }
            } else if (slot == 24) {
                if (left || right) {
                    give(player, plugin.items().createWireCutters(1));
                    player.sendMessage(Component.text("Gave Wire Cutters", NamedTextColor.WHITE));
                }
            } else if (slot == 3) {
                if (left || right) {
                    give(player, plugin.items().createLongProngs(false));
                    player.sendMessage(Component.text("Gave Long Prongs", NamedTextColor.GOLD));
                }
            } else if (slot == 0) {
                if (left || right) {
                    int amt = left ? 4 : 1;
                    give(player, plugin.items().createHandcuffs(amt));
                    player.sendMessage(Component.text("Gave Handcuffs x" + amt, NamedTextColor.GRAY));
                }
            } else if (slot == 1) {
                if (left || right) {
                    int amt = left ? 4 : 1;
                    give(player, plugin.items().createHandcuffKey(amt));
                    player.sendMessage(Component.text("Gave Handcuff Key x" + amt, NamedTextColor.GOLD));
                }
            } else if (slot == 2) {
                if (left || right) {
                    int amt = left ? 8 : 1;
                    give(player, plugin.items().createLockpick(amt));
                    player.sendMessage(Component.text("Gave Lockpick x" + amt, NamedTextColor.GRAY));
                }
            } else if (slot == 27) {
                if (left || right) {
                    int amt = left ? 8 : 1;
                    give(player, plugin.items().createZipTies(amt));
                    player.sendMessage(Component.text("Gave Zip Ties x" + amt, NamedTextColor.DARK_GRAY));
                }
            } else if (slot == 36) {
                if (left || right) {
                    give(player, plugin.items().createPocketKnife(1));
                    player.sendMessage(Component.text("Gave Pocket Knife", NamedTextColor.WHITE));
                }
            } else if (slot == 8) {
                if (left || right) {
                    int amt = left ? 16 : 4;
                    give(player, plugin.items().createObsidianShards(amt));
                    player.sendMessage(Component.text("Gave Obsidian Shards x" + amt, NamedTextColor.DARK_GRAY));
                }
            } else if (slot == 12) {
                giveOrEquipArmor(player, plugin.items().createScubaHelmet(), left, right, ArmorSlot.HELMET);
            } else if (slot == 14) {
                giveOrEquipArmor(player, plugin.items().createScubaTank(), left, right, ArmorSlot.CHEST);
            } else if (slot == 25) {
                giveOrEquipArmor(player, plugin.items().createWetsuitLeggings(), left, right, ArmorSlot.LEGS);
            } else if (slot == 26) {
                giveOrEquipArmor(player, plugin.items().createWetsuitBoots(), left, right, ArmorSlot.BOOTS);
            } else if (slot == 38) {
                session.page = Page.HAZMAT_SUIT;
                render(player);
            } else if (slot == 13) {
                session.page = Page.FIRE_PROXIMITY_SUIT;
                render(player);
            }
            return;
        }

        if (session.page == Page.HAZMAT_SUIT) {
            if (slot == 22) {
                session.page = Page.GEAR;
                render(player);
                return;
            }
            if (slot == 10) {
                giveOrEquipArmor(player, plugin.items().createHazmatHelmet(), left, right, ArmorSlot.HELMET);
            } else if (slot == 12) {
                giveOrEquipArmor(player, plugin.items().createHazmatChestplate(), left, right, ArmorSlot.CHEST);
            } else if (slot == 14) {
                giveOrEquipArmor(player, plugin.items().createHazmatLeggings(), left, right, ArmorSlot.LEGS);
            } else if (slot == 16) {
                giveOrEquipArmor(player, plugin.items().createHazmatBoots(), left, right, ArmorSlot.BOOTS);
            }
            return;
        }

        if (session.page == Page.FIRE_PROXIMITY_SUIT) {
            if (slot == 22) {
                session.page = Page.GEAR;
                render(player);
                return;
            }
            if (slot == 10) {
                giveOrEquipArmor(player, plugin.items().createFireProximityHelmet(), left, right, ArmorSlot.HELMET);
            } else if (slot == 12) {
                giveOrEquipArmor(player, plugin.items().createFireProximityChestplate(), left, right, ArmorSlot.CHEST);
            } else if (slot == 14) {
                giveOrEquipArmor(player, plugin.items().createFireProximityLeggings(), left, right, ArmorSlot.LEGS);
            } else if (slot == 16) {
                giveOrEquipArmor(player, plugin.items().createFireProximityBoots(), left, right, ArmorSlot.BOOTS);
            }
            return;
        }

        if (session.page == Page.NVG_COLORS) {
            if (slot == 22) {
                session.page = Page.GEAR;
                render(player);
                return;
            }
            NvgGear.Variant[] fixed = {
                    NvgGear.Variant.GREEN, NvgGear.Variant.WHITE, NvgGear.Variant.AMBER,
                    NvgGear.Variant.BLUE, NvgGear.Variant.RED, NvgGear.Variant.TRUE_COLOR
            };
            int[] slots = {10, 11, 12, 13, 14, 15};
            for (int i = 0; i < fixed.length; i++) {
                if (slot == slots[i]) {
                    giveOrEquipHelmet(player, plugin.items().createNvgHelmet(fixed[i]), left, right);
                    return;
                }
            }
            return;
        }

        if (session.page == Page.THERMAL_MODES) {
            if (slot == 22) {
                session.page = Page.GEAR;
                render(player);
                return;
            }
            ThermalGear.Variant[] fixed = {
                    ThermalGear.Variant.WHITE_HOT, ThermalGear.Variant.BLACK_HOT, ThermalGear.Variant.IRONBOW,
                    ThermalGear.Variant.RAINBOW, ThermalGear.Variant.FUSION
            };
            int[] slots = {11, 12, 13, 14, 15};
            for (int i = 0; i < fixed.length; i++) {
                if (slot == slots[i]) {
                    giveOrEquipHelmet(player, plugin.items().createThermalHelmet(fixed[i]), left, right);
                    return;
                }
            }
            return;
        }

        if (slot == 45) {
            session.browserPage = Math.max(0, session.browserPage - 1);
            render(player);
            return;
        }
        if (slot == 49) {
            if (session.page == Page.SMOKES || session.page == Page.FLARES) {
                session.page = Page.GRENADES;
            } else {
                session.page = Page.HOME;
            }
            session.browserPage = 0;
            render(player);
            return;
        }
        if (slot == 53) {
            session.browserPage++;
            render(player);
            return;
        }
        if (slot < 0 || slot >= 45) {
            return;
        }

        switch (session.page) {
            case GUNS -> handleGunClick(player, session, slot, left, right, shift, weapons());
            case WARZ_GUNS -> handleGunClick(player, session, slot, left, right, shift, warzCatalog());
            case AMMO -> handleAmmoClick(player, session, slot, left, right, shift);
            case MAGS -> handleMagsClick(player, session, slot, left, right, shift);
            case GRENADES -> handleGrenadeClick(player, session, slot, left, right, shift);
            case SMOKES -> handleSmokeClick(player, session, slot, left, right, shift);
            default -> {
            }
        }
    }

    private void handleGlassClick(Player player, Session session, int slot,
                                  boolean left, boolean right, boolean shift) {
        if (slot == 49) {
            session.page = Page.HOME;
            render(player);
            return;
        }
        if (!(left || right)) {
            return;
        }
        GlassType[] types = GlassType.values();
        int pair = slot / 2;
        boolean pane = (slot % 2) == 1;
        if (pair < 0 || pair >= types.length || slot >= 45) {
            return;
        }
        GlassType type = types[pair];
        int amount = shift ? 8 : 1;
        for (int i = 0; i < amount; i++) {
            give(player, pane ? plugin.items().createGlassPane(type) : plugin.items().createGlassBlock(type));
        }
        player.sendMessage(Component.text(
                "Gave " + amount + "x " + type.displayName() + (pane ? " Pane" : " Block"),
                NamedTextColor.AQUA));
    }

    private void handleAttachmentsClick(Player player, Session session, int slot,
                                        boolean left, boolean right, boolean shift) {
        if (slot == 49) {
            session.page = Page.HOME;
            render(player);
            return;
        }
        if (!(left || right)) {
            return;
        }
        int amount = shift ? 8 : 1;
        if (slot == 4) {
            give(player, plugin.items().createGunWorkbenchItem());
            player.sendMessage(Component.text("Gave Gun Workbench", NamedTextColor.GOLD));
            return;
        }
        if (slot == 10) {
            for (int i = 0; i < amount; i++) {
                give(player, plugin.items().createFlashlightModulePart());
            }
            player.sendMessage(Component.text("Gave " + amount + "x flashlight module", NamedTextColor.YELLOW));
            return;
        }
        if (slot == 12) {
            for (int i = 0; i < amount; i++) {
                give(player, plugin.items().createPeq15Part());
            }
            player.sendMessage(Component.text("Gave " + amount + "x AN/PEQ-15", NamedTextColor.GOLD));
            return;
        }
        if (slot == 14) {
            for (int i = 0; i < amount; i++) {
                give(player, plugin.items().createMagAdapterAkAr());
            }
            player.sendMessage(Component.text("Gave " + amount + "x AK↔AR Mag Adapter", NamedTextColor.GOLD));
            return;
        }
        // Suppressors in 19..22
        if (slot >= 19 && slot <= 22) {
            SuppressorType[] types = SuppressorType.values();
            int idx = slot - 19;
            if (idx >= 0 && idx < types.length) {
                give(player, plugin.items().createSuppressorPart(types[idx]));
                // amount loop
                for (int i = 1; i < amount; i++) {
                    give(player, plugin.items().createSuppressorPart(types[idx]));
                }
                player.sendMessage(Component.text("Gave " + amount + "x suppressor (" + types[idx].id() + ")",
                        NamedTextColor.GRAY));
            }
            return;
        }
        // Lasers 28..36
        LaserModColor[] colors = LaserModColor.installable();
        int laserIdx = slot - 28;
        if (laserIdx >= 0 && laserIdx < colors.length && slot < 37) {
            for (int i = 0; i < amount; i++) {
                give(player, plugin.items().createLaserModulePart(colors[laserIdx]));
            }
            player.sendMessage(Component.text("Gave " + amount + "x laser (" + colors[laserIdx].id() + ")",
                    NamedTextColor.AQUA));
            return;
        }
        // Optics 37..44
        OpticType[] optics = OpticType.installable();
        int opticIdx = slot - 37;
        if (opticIdx >= 0 && opticIdx < optics.length && slot < 45) {
            for (int i = 0; i < amount; i++) {
                give(player, plugin.items().createOpticPart(optics[opticIdx]));
            }
            player.sendMessage(Component.text("Gave " + amount + "x optic (" + optics[opticIdx].id() + ")",
                    NamedTextColor.GREEN));
            return;
        }
        // Grips 45..48
        GripType[] grips = GripType.installable();
        int gripIdx = slot - 45;
        if (gripIdx >= 0 && gripIdx < grips.length && slot < 49) {
            for (int i = 0; i < amount; i++) {
                give(player, plugin.items().createGripPart(grips[gripIdx]));
            }
            player.sendMessage(Component.text("Gave " + amount + "x grip (" + grips[gripIdx].id() + ")",
                    NamedTextColor.YELLOW));
        }
    }

    private void handleGunClick(Player player, Session session, int slot,
                                boolean left, boolean right, boolean shift,
                                List<GunDefinition> source) {
        List<GunDefinition> guns = source.stream()
                .sorted(Comparator.comparing(GunDefinition::fileName))
                .toList();
        int index = session.browserPage * 45 + slot;
        if (index < 0 || index >= guns.size()) {
            return;
        }
        GunDefinition gun = guns.get(index);
        if (right) {
            give(player, plugin.items().create(gun, 1));
            String primary = gun.allowedRounds().isEmpty()
                    ? null
                    : gun.allowedRounds().get(0);
            if (primary == null) {
                primary = com.local.warz.config.AmmoCaliber.primaryRound(gun.ammoCaliber());
            }
            final String roundId = primary;
            plugin.rounds().get(roundId).ifPresent(round ->
                    give(player, plugin.items().createRound(round, 64)));
            MagazineType[] fit = MagazineType.forCaliber(gun.ammoCaliber());
            if (fit.length > 0) {
                give(player, plugin.items().createMagazine(fit[0], fit[0].capacity(), roundId, 1));
                give(player, plugin.items().createMagazine(fit[0], 1));
            }
            player.sendMessage(Component.text("Gave " + gun.fileName() + " + ammo + mags", NamedTextColor.GREEN));
            return;
        }
        int amount = shift && left ? 16 : 1;
        if (left) {
            give(player, plugin.items().create(gun, amount));
            player.sendMessage(Component.text("Gave " + amount + "x " + gun.fileName(), NamedTextColor.GREEN));
        }
    }

    private void handleAmmoClick(Player player, Session session, int slot,
                                 boolean left, boolean right, boolean shift) {
        List<RoundDefinition> rounds = plugin.rounds().all().stream()
                .sorted(Comparator.comparing(RoundDefinition::fileName))
                .toList();
        int index = session.browserPage * 45 + slot;
        if (index < 0 || index >= rounds.size()) {
            return;
        }
        RoundDefinition round = rounds.get(index);
        int amount;
        if (right) {
            amount = 1;
        } else if (shift && left) {
            amount = 16;
        } else if (left) {
            amount = 64;
        } else {
            return;
        }
        give(player, plugin.items().createRound(round, amount));
        player.sendMessage(Component.text("Gave " + amount + "x " + round.fileName(), NamedTextColor.GREEN));
    }

    private void handleMagsClick(Player player, Session session, int slot,
                                 boolean left, boolean right, boolean shift) {
        MagazineType[] types = MagazineType.values();
        int index = session.browserPage * 45 + slot;
        if (index < 0 || index >= types.length) {
            return;
        }
        MagazineType type = types[index];
        if (right) {
            String primary = com.local.warz.config.AmmoCaliber.primaryRound(type.caliber());
            give(player, plugin.items().createMagazine(type, type.capacity(), primary, 1));
            player.sendMessage(Component.text("Gave full " + type.id() + " (" + type.capacity() + ")",
                    NamedTextColor.GOLD));
            return;
        }
        if (!left) {
            return;
        }
        int amount = shift ? 8 : 1;
        give(player, plugin.items().createMagazine(type, amount));
        player.sendMessage(Component.text("Gave " + amount + "x empty " + type.id(), NamedTextColor.GOLD));
    }

    private void handleGrenadeClick(Player player, Session session, int slot,
                                    boolean left, boolean right, boolean shift) {
        List<GunDefinition> items = throwables().stream()
                .sorted(Comparator.comparing(GunDefinition::fileName))
                .toList();
        int index = session.browserPage * 45 + slot;
        int total = items.size() + 2;
        if (index < 0 || index >= total || !(left || right)) {
            return;
        }
        if (index == items.size()) {
            session.page = Page.SMOKES;
            session.browserPage = 0;
            render(player);
            return;
        }
        if (index == items.size() + 1) {
            session.page = Page.FLARES;
            session.browserPage = 0;
            render(player);
            return;
        }
        GunDefinition gun = items.get(index);
        int amount;
        if (right) {
            amount = 8;
        } else if (shift && left) {
            amount = 16;
        } else {
            amount = 1;
        }
        give(player, plugin.items().create(gun, amount));
        player.sendMessage(Component.text("Gave " + amount + "x " + gun.fileName(), NamedTextColor.GREEN));
    }

    private void handleSmokeClick(Player player, Session session, int slot,
                                  boolean left, boolean right, boolean shift) {
        SmokeType[] types = SmokeType.values();
        int index = session.browserPage * 45 + slot;
        if (index < 0 || index >= types.length) {
            return;
        }
        SmokeType type = types[index];
        int amount;
        if (right) {
            amount = 4;
        } else if (shift && left) {
            amount = 8;
        } else if (left) {
            amount = 1;
        } else {
            return;
        }
        give(player, plugin.items().createSmokeGrenade(type, amount));
        player.sendMessage(Component.text("Gave " + amount + "x " + type.plainName(), NamedTextColor.GRAY));
    }

    private static boolean isWarzCatalog(GunDefinition gun) {
        String id = gun == null || gun.fileName() == null ? "" : gun.fileName().toLowerCase(java.util.Locale.ROOT);
        return id.startsWith("warz_");
    }

    private List<GunDefinition> weapons() {
        return plugin.registry().all().stream()
                .filter(g -> !g.throwable() && !g.consumable())
                .filter(g -> !isWarzCatalog(g))
                .toList();
    }

    private List<GunDefinition> warzCatalog() {
        return plugin.registry().all().stream()
                .filter(g -> !g.throwable() && !g.consumable())
                .filter(GiveGunMenuService::isWarzCatalog)
                .toList();
    }

    private List<GunDefinition> throwables() {
        return plugin.registry().all().stream()
                .filter(g -> g.throwable() || g.consumable())
                .toList();
    }

    private void give(Player player, ItemStack stack) {
        // Every one of the fifty-odd menu gives comes through here, and it used
        // vanilla addItem - which merges on isSimilar. Ammo already in the pocket
        // whose lore or components had drifted opened a second stack instead of
        // topping up. giveOrDrop merges on what the item is.
        plugin.items().giveOrDrop(player, stack);
    }

    private static void set(Inventory inv, int slot, Material mat, String name, String... loreLines) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(text(name).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(text(line));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        inv.setItem(slot, stack);
    }

    private static ItemStack button(Material mat, String name, String... loreLines) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(text(name).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(text(line));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private static Component text(String legacy) {
        return ItemFactory.colorize(legacy).decoration(TextDecoration.ITALIC, false);
    }
}
