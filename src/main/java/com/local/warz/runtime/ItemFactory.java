package com.local.warz.runtime;

import com.local.warz.WarzKeys;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.config.AmmoCaliber;
import com.local.warz.model.GunDefinition;
import com.local.warz.model.RoundDefinition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ItemFactory {
    private final WarzPlugin plugin;
    private final NamespacedKey gunKey;
    private final NamespacedKey roundKey;
    private final NamespacedKey nvgKey;
    private final NamespacedKey thermalKey;
    private final NamespacedKey modelKey;
    private final NamespacedKey bigDroneItemKey;
    private final NamespacedKey bigDronePadKey;
    private final NamespacedKey bigDroneCtrlKey;
    private final NamespacedKey radiolinkKey;
    private final NamespacedKey jetFuelKey;
    private final NamespacedKey hydrazineFuelKey;
    private final NamespacedKey metalKey;
    private final NamespacedKey droneCargoRocketsKey;
    private final NamespacedKey droneCargoFuelKey;
    private final NamespacedKey droneCargoStructureHpKey;
    private final NamespacedKey droneCargoFlaresKey;
    private final NamespacedKey droneCargoBayKey;
    private final NamespacedKey droneTypeKey;
    private final NamespacedKey flashlightKey;
    private final NamespacedKey flashlightOnKey;
    private final NamespacedKey smokeKey;
    private final NamespacedKey flareKey;
    private final NamespacedKey flareColorKey;
    private final NamespacedKey suppressorKey;
    private final NamespacedKey laserModKey;
    private final NamespacedKey laserColorKey;
    private final NamespacedKey flashlightModKey;
    private final NamespacedKey peqKey;
    private final NamespacedKey opticModeKey;
    private final NamespacedKey attachmentPartKey;
    private final NamespacedKey medicalKey;
    private final NamespacedKey trapKey;
    private final NamespacedKey toolKey;
    private final NamespacedKey prongsLavaKey;
    private final NamespacedKey scubaKey;
    private final NamespacedKey suitKey;
    private final NamespacedKey drinkKey;
    private final NamespacedKey foodKey;
    private final NamespacedKey emptyCanKey;
    private final NamespacedKey emptyBottleKey;
    private final NamespacedKey plasticBottleKey;
    private final NamespacedKey brokenGlassKey;
    private final NamespacedKey lifeStrawKey;
    private final NamespacedKey lifeStrawUsesKey;
    private final NamespacedKey grappleKey;
    private final NamespacedKey grappleUsesKey;
    /** Newline-joined legacy lines for companion Shift-expanded inventory tooltips. */
    private final NamespacedKey tooltipDetailKey;
    /** Companion creative-tab marker — server replaces these with full create* stacks. */
    private final NamespacedKey creativeStubKey;
    /** Compact give-spec from companion creative tabs, e.g. {@code gun:ak47}. */
    private final NamespacedKey warzGiveKey;
    private final NamespacedKey magKey;
    private final NamespacedKey magCountKey;
    private final NamespacedKey magRoundKey;
    /** Comma-separated round ids; last entry is next to fire (LIFO). */
    private final NamespacedKey magLoadKey;
    private final NamespacedKey chamberRoundKey;
    private final NamespacedKey magAdapterKey;
    private final NamespacedKey opticKey;
    private final NamespacedKey gripKey;
    private final NamespacedKey zeroYardsKey;
    private final NamespacedKey gunConditionKey;
    private final NamespacedKey warzMapKey;
    private final Material baseMaterial;

    /** Separator for {@code tooltip_detail} PDC (avoids raw newlines in NBT). */
    public static final String TOOLTIP_DETAIL_SEP = "\u001e";

    public static final String MEDICAL_SPLINT = "splint";
    public static final String MEDICAL_BANDAGE = "bandage";
    public static final String MEDICAL_TOURNIQUET = "tourniquet";
    public static final String MEDICAL_BLOOD_BAG = "blood_bag";
    private static final Color BLOOD_BAG_COLOR = Color.fromRGB(0x8B, 0x00, 0x00);
    public static final String TRAP_RAZOR_WIRE = "razor_wire";
    public static final String TRAP_CHAINLINK = "chainlink";
    public static final String TOOL_WIRE_CUTTERS = "wire_cutters";
    public static final String TOOL_LONG_PRONGS = "long_prongs";
    public static final String TOOL_OBSIDIAN_SHARDS = "obsidian_shards";
    public static final String TOOL_HANDCUFFS = "handcuffs";
    public static final String TOOL_HANDCUFF_KEY = "handcuff_key";
    public static final String TOOL_LOCKPICK = "lockpick";
    public static final String TOOL_ZIP_TIES = "zip_ties";
    public static final String TOOL_POCKET_KNIFE = "pocket_knife";
    public static final int CMD_LONG_PRONGS = 4320;
    public static final int CMD_LONG_PRONGS_LAVA = 4321;
    public static final int CMD_OBSIDIAN_SHARDS = 4322;
    public static final int CMD_HANDCUFFS = 4330;
    public static final int CMD_HANDCUFF_KEY = 4331;
    public static final int CMD_LOCKPICK = 4332;
    public static final int CMD_ZIP_TIES = 4333;
    public static final int CMD_POCKET_KNIFE = 4334;
    public static final int CMD_JET_FUEL_CAN = 4340;
    public static final int CMD_HYDRAZINE_FUEL_CAN = 4341;
    public static final int CMD_BANDAGE = 4224;
    public static final int CMD_FOOD_BEANS = 4225;
    public static final int CMD_FOOD_PASTA = 4226;
    public static final int CMD_FOOD_FISH = 4227;
    public static final int CMD_FOOD_DEW = 4228;
    public static final int CMD_FOOD_GOLDEN_APPLE = 4229;
    public static final String SCUBA_HELMET = "scuba_helmet";
    public static final String SCUBA_TANK = "scuba_tank";
    public static final String WETSUIT_LEGS = "wetsuit_legs";
    public static final String WETSUIT_BOOTS = "wetsuit_boots";
    public static final String SUIT_HAZMAT_HELMET = "hazmat_helmet";
    public static final String SUIT_HAZMAT_CHEST = "hazmat_chest";
    public static final String SUIT_HAZMAT_LEGS = "hazmat_legs";
    public static final String SUIT_HAZMAT_BOOTS = "hazmat_boots";
    public static final String SUIT_FIRE_HELMET = "fire_proximity_helmet";
    public static final String SUIT_FIRE_CHEST = "fire_proximity_chest";
    public static final String SUIT_FIRE_LEGS = "fire_proximity_legs";
    public static final String SUIT_FIRE_BOOTS = "fire_proximity_boots";
    private static final Color SCUBA_MASK_COLOR = Color.fromRGB(0x4A, 0xC0, 0xD4);
    private static final Color SCUBA_TANK_COLOR = Color.fromRGB(0x2A, 0x2A, 0x2E);
    private static final Color WETSUIT_COLOR = Color.fromRGB(0x0A, 0x2F, 0x38);
    /** Inventory dye — matches hazmat skin ochre (worn look uses equipment texture). */
    private static final Color HAZMAT_COLOR = Color.fromRGB(0xD9, 0x9A, 0x28);
    private static final Color FIRE_SUIT_COLOR = Color.fromRGB(0xBE, 0xC3, 0xCD);
    /**
     * Hazmat inventory icons. These were dyed leather with no CMD, so the icon was
     * an orange leather helmet rather than the hazmat model the pack ships.
     */
    public static final int CMD_HAZMAT_HELMET = 4300;
    public static final int CMD_HAZMAT_CHEST = 4301;
    public static final int CMD_HAZMAT_LEGS = 4302;
    public static final int CMD_HAZMAT_BOOTS = 4303;
    /** NiftyBlocks fire suit inventory icons (leather CMD → companion models). */
    public static final int CMD_FIRE_HELMET = 4310;
    public static final int CMD_FIRE_CHEST = 4311;
    public static final int CMD_FIRE_LEGS = 4312;
    public static final int CMD_FIRE_BOOTS = 4313;

    public ItemFactory(WarzPlugin plugin) {
        this.plugin = plugin;
        this.gunKey = WarzKeys.of("gun_id");
        this.roundKey = WarzKeys.of("round_id");
        this.nvgKey = WarzKeys.of("nvg_id");
        this.thermalKey = WarzKeys.of("thermal_id");
        this.modelKey = WarzKeys.of("cmd");
        this.bigDroneItemKey = WarzKeys.of("bigdrone_item");
        this.bigDronePadKey = WarzKeys.of("bigdrone_pad");
        this.bigDroneCtrlKey = WarzKeys.of("bigdrone_ctrl");
        this.radiolinkKey = WarzKeys.of("radiolink");
        this.jetFuelKey = WarzKeys.of("jet_fuel_can");
        this.hydrazineFuelKey = WarzKeys.of("hydrazine_fuel_can");
        this.metalKey = WarzKeys.of("drone_metal");
        this.droneCargoRocketsKey = WarzKeys.of("drone_cargo_rockets");
        this.droneCargoFuelKey = WarzKeys.of("drone_cargo_fuel");
        this.droneCargoStructureHpKey = WarzKeys.of("drone_cargo_structure_hp");
        this.droneCargoFlaresKey = WarzKeys.of("drone_cargo_flares");
        this.droneCargoBayKey = WarzKeys.of("drone_cargo_bay");
        this.droneTypeKey = WarzKeys.of("drone_type");
        this.flashlightKey = WarzKeys.of("flashlight");
        this.flashlightOnKey = WarzKeys.of("flashlight_on");
        this.smokeKey = WarzKeys.of("smoke_type");
        this.flareKey = WarzKeys.of("road_flare");
        this.flareColorKey = WarzKeys.of("flare_color");
        this.suppressorKey = WarzKeys.of("suppressor");
        this.laserModKey = WarzKeys.of("laser_mod");
        this.laserColorKey = WarzKeys.of("laser_color");
        this.flashlightModKey = WarzKeys.of("flashlight_mod");
        this.peqKey = WarzKeys.of("peq15");
        this.opticModeKey = WarzKeys.of("optic_mode");
        this.attachmentPartKey = WarzKeys.of("attachment_part");
        this.medicalKey = WarzKeys.of("medical_id");
        this.trapKey = WarzKeys.of("trap_id");
        this.toolKey = WarzKeys.of("tool_id");
        this.prongsLavaKey = WarzKeys.of("prongs_lava");
        this.scubaKey = WarzKeys.of("scuba_id");
        this.suitKey = WarzKeys.of("suit_id");
        this.drinkKey = WarzKeys.of("drink_id");
        this.foodKey = WarzKeys.of("food_id");
        this.emptyCanKey = WarzKeys.of("empty_can");
        this.emptyBottleKey = WarzKeys.of("empty_bottle");
        this.plasticBottleKey = WarzKeys.of("plastic_bottle");
        this.brokenGlassKey = WarzKeys.of("broken_glass");
        this.lifeStrawKey = WarzKeys.of("life_straw");
        this.lifeStrawUsesKey = WarzKeys.of("life_straw_uses");
        this.grappleKey = WarzKeys.of("grapple_hook");
        this.grappleUsesKey = WarzKeys.of("grapple_uses");
        this.tooltipDetailKey = WarzKeys.of("tooltip_detail");
        this.creativeStubKey = WarzKeys.of("creative_stub");
        this.warzGiveKey = WarzKeys.of("warz_give");
        this.magKey = WarzKeys.of("mag_id");
        this.magCountKey = WarzKeys.of("mag_count");
        this.magRoundKey = WarzKeys.of("mag_round");
        this.magLoadKey = WarzKeys.of("mag_load");
        this.chamberRoundKey = WarzKeys.of("chamber_round");
        this.magAdapterKey = WarzKeys.of("mag_adapter");
        this.opticKey = WarzKeys.of("optic");
        this.gripKey = WarzKeys.of("grip");
        this.zeroYardsKey = WarzKeys.of("zero_yards");
        this.gunConditionKey = WarzKeys.of("gun_condition");
        this.warzMapKey = WarzKeys.of("warz_map");
        Material configured = Material.matchMaterial(
                plugin.getConfig().getString("gun-base-material", "STICK")
        );
        this.baseMaterial = configured != null ? configured : Material.BONE;
    }

    public NamespacedKey gunKey() {
        return gunKey;
    }

    public NamespacedKey roundKey() {
        return roundKey;
    }

    public NamespacedKey nvgKey() {
        return nvgKey;
    }

    public NamespacedKey thermalKey() {
        return thermalKey;
    }

    public NamespacedKey bigDroneItemKey() {
        return bigDroneItemKey;
    }

    public NamespacedKey bigDronePadKey() {
        return bigDronePadKey;
    }

    public NamespacedKey bigDroneCtrlKey() {
        return bigDroneCtrlKey;
    }

    public NamespacedKey flashlightKey() {
        return flashlightKey;
    }

    public NamespacedKey smokeKey() {
        return smokeKey;
    }

    public NamespacedKey flareKey() {
        return flareKey;
    }

    public NamespacedKey flareColorKey() {
        return flareColorKey;
    }

    public ItemStack createRoadFlare(int amount) {
        return createRoadFlare(FlareColor.RED, amount);
    }

    public ItemStack createRoadFlare(FlareColor color, int amount) {
        FlareColor c = color != null ? color : FlareColor.RED;
        ItemStack stack = new ItemStack(c.material(), Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(flareKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(flareColorKey, PersistentDataType.STRING, c.id());
        meta.displayName(colorize(c.displayName()));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Throwable roadside marker flare"));
        lore.add(colorize("&7Burns &f" + c.name().toLowerCase(Locale.ROOT)
                + " &7— step on it to catch fire"));
        lore.add(colorize("&7Walk off &7→ fire goes out"));
        lore.add(colorize("&7Duration &f" + (FlareService.BURN_TICKS / 20 / 60) + " min"
                + " &8(" + (FlareService.BURN_TICKS / 20) + "s)"));
        lore.add(colorize("&8────────"));
        lore.add(colorize("&eRight-click &7→ throw / strike"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isRoadFlare(ItemStack stack) {
        return readFlag(stack, flareKey);
    }

    public FlareColor flareColor(ItemStack stack) {
        if (!isRoadFlare(stack)) {
            return FlareColor.RED;
        }
        String id = stack.getItemMeta().getPersistentDataContainer().get(flareColorKey, PersistentDataType.STRING);
        return FlareColor.fromId(id);
    }

    public ItemStack createSmokeGrenade(SmokeType type, int amount) {
        // Clay ball: not a vanilla throwable — avoids snowball use-cooldown blocking multi-throws
        ItemStack stack = new ItemStack(Material.CLAY_BALL, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(smokeKey, PersistentDataType.STRING, type.fileKey());
        meta.displayName(colorize(type.displayName()));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize(type.blurb()));
        lore.add(colorize("&8────────"));
        lore.add(colorize("&7NVG wash &fx" + String.format(Locale.ROOT, "%.0f%%", type.nvgWash() * 100)));
        lore.add(colorize("&7Thermal block &fx" + String.format(Locale.ROOT, "%.0f%%", type.thermalBlock() * 100)));
        lore.add(colorize("&7Duration &f" + (type.lifeTicks() / 20) + "s &8· &7Radius &f"
                + String.format(Locale.ROOT, "%.0f", type.maxRadius()) + "m"));
        if (type.irPrimary()) {
            lore.add(colorize("&bPrimarily visible under NVG / IR"));
        }
        if (type == SmokeType.WIND) {
            lore.add(colorize("&3Drifts with server wind"));
        }
        lore.add(colorize("&eRight-click &7→ throw"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public SmokeType smokeType(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return null;
        }
        String key = stack.getItemMeta().getPersistentDataContainer().get(smokeKey, PersistentDataType.STRING);
        return SmokeType.byKey(key);
    }

    public boolean isSmokeGrenade(ItemStack stack) {
        return smokeType(stack) != null;
    }

    public ItemStack create(GunDefinition def, int amount) {
        // Guns never stack: each one has its own attachments, chamber and wear.
        ItemStack stack = new ItemStack(baseMaterial, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(gunKey, PersistentDataType.STRING, def.fileName());
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, def.customModelData());
        applyCmd(meta, def.customModelData());
        meta.displayName(colorize(def.displayName()));
        stack.setItemMeta(meta);
        applyGunInventoryLore(stack, false);
        applyMaxStack(stack, 1);
        stack.setAmount(1);
        return stack;
    }

    /**
     * Compact inventory lore: caliber + gun type + Shift hint.
     * Full stats are stored in PDC {@code tooltip_detail} for the companion while Shift is held.
     */
    public void applyGunInventoryLore(ItemStack stack, boolean ignoredExpanded) {
        if (stack == null || !isGunItem(stack) || !stack.hasItemMeta()) {
            return;
        }
        GunDefinition def = gunId(stack).flatMap(id -> plugin.registry().get(id)).orElse(null);
        ItemMeta meta = stack.getItemMeta();
        // Persist expanded lines for the Fabric companion (GLFW Shift).
        if (def != null) {
            writeTooltipDetail(meta, killFeedHoverLines(def, stack, -1.0));
        }
        // Compact: type + caliber + fitted attachments; Shift expands the rest.
        List<Component> lore = new ArrayList<>();
        if (def != null) {
            lore.add(colorize("&7(" + gunClassPlain(def) + ")")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            lore.add(colorize(killFeedAmmoCaliberLine(def))
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
        lore.addAll(compactAttachmentLore(stack, def));
        lore.add(colorize("&8Hold &eShift")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(lore);
        stack.setItemMeta(meta);
    }

    /** Always-visible attachment summary on the gun item (not Shift-only). */
    private List<Component> compactAttachmentLore(ItemStack stack, GunDefinition def) {
        List<Component> lines = new ArrayList<>();
        OpticType optic = opticTypeStored(stack);
        if (optic != null) {
            lines.add(colorize("&7Optic: " + optic.displayName())
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        } else if (OpticType.acceptsRail(def)) {
            lines.add(colorize("&7Optic: &8Ironsights")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
        GripType grip = gripType(stack);
        if (grip.isInstalled()) {
            lines.add(colorize("&7Grip: " + grip.displayName())
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
        if (hasMagAdapter(stack)) {
            lines.add(colorize("&6AK↔AR Adapter")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
        SuppressorType sup = suppressorType(stack);
        if (sup != null) {
            lines.add(colorize("&7" + sup.displayName().replace("&7", "").replace("&f", ""))
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
        return lines;
    }

    private void writeTooltipDetail(ItemMeta meta, List<Component> detail) {
        if (meta == null || detail == null || detail.isEmpty()) {
            return;
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < detail.size(); i++) {
            if (i > 0) {
                joined.append(TOOLTIP_DETAIL_SEP);
            }
            joined.append(LegacyComponentSerializer.legacyAmpersand().serialize(detail.get(i)));
        }
        meta.getPersistentDataContainer().set(tooltipDetailKey, PersistentDataType.STRING, joined.toString());
    }

    /** Compact lore + PDC detail expanded client-side while Shift is held. */
    private void applyShiftLore(ItemMeta meta, List<Component> compact, List<Component> detail) {
        if (meta == null) {
            return;
        }
        writeTooltipDetail(meta, detail);
        List<Component> lore = new ArrayList<>();
        if (compact != null) {
            for (Component line : compact) {
                lore.add(line.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            }
        }
        lore.add(colorize("&8Hold &eShift")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(lore);
    }

    /** Format a multiplier as a signed percent (0.92 → {@code -8%}). */
    private static String deltaPct(double mult) {
        int d = (int) Math.round((1.0 - mult) * 100.0);
        if (d == 0) {
            return "0%";
        }
        return (d > 0 ? "-" : "+") + Math.abs(d) + "%";
    }

    public NamespacedKey suppressorKey() {
        return suppressorKey;
    }

    public NamespacedKey laserModKey() {
        return laserModKey;
    }

    public NamespacedKey laserColorKey() {
        return laserColorKey;
    }

    public boolean isGunItem(ItemStack stack) {
        return gunId(stack).isPresent();
    }

    public boolean supportsSuppressor(GunDefinition def) {
        if (def == null || def.throwable() || def.consumable() || def.isLaser()) {
            return false;
        }
        String id = def.fileName() == null ? "" : def.fileName().toLowerCase(Locale.ROOT);
        if (id.equals("law") || id.equals("law_drone") || id.equals("javelin")) {
            return false;
        }
        String cal = AmmoCaliber.normalize(def.ammoCaliber());
        return !cal.equals("rocket") && !cal.equals("energy") && !cal.equals("arrow") && !cal.equals("melee");
    }

    public boolean supportsSuppressor(ItemStack stack) {
        return gunId(stack).flatMap(id -> plugin.registry().get(id)).map(this::supportsSuppressor).orElse(false);
    }

    public boolean hasSuppressor(ItemStack stack) {
        return suppressorType(stack) != null;
    }

    public SuppressorType suppressorType(ItemStack stack) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return null;
        }
        var pdc = stack.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(suppressorKey, PersistentDataType.STRING);
        if (id != null) {
            return SuppressorType.fromId(id);
        }
        // Legacy BYTE flag
        Byte legacy = pdc.get(suppressorKey, PersistentDataType.BYTE);
        if (legacy != null && legacy == (byte) 1) {
            return SuppressorType.RIFLE;
        }
        return null;
    }

    public void setSuppressor(ItemStack stack, boolean on) {
        if (on) {
            gunId(stack).flatMap(id -> plugin.registry().get(id)).ifPresent(def -> {
                SuppressorType t = SuppressorType.forGun(def);
                if (t != null) {
                    setSuppressor(stack, t);
                }
            });
        } else {
            setSuppressor(stack, (SuppressorType) null);
        }
    }

    public void setSuppressor(ItemStack stack, SuppressorType type) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return;
        }
        if (type != null) {
            GunDefinition def = gunId(stack).flatMap(id -> plugin.registry().get(id)).orElse(null);
            if (!supportsSuppressor(def) || !type.fits(def)) {
                return;
            }
        }
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().remove(suppressorKey);
        if (type != null) {
            meta.getPersistentDataContainer().set(suppressorKey, PersistentDataType.STRING, type.id());
        }
        stack.setItemMeta(meta);
        refreshAttachmentLore(stack);
    }

    public boolean suppressorFitsGun(ItemStack gun, SuppressorType type) {
        if (type == null || !isGunItem(gun) || !supportsSuppressor(gun)) {
            return false;
        }
        return gunId(gun).flatMap(id -> plugin.registry().get(id)).map(type::fits).orElse(false);
    }

    public boolean hasLaserMod(ItemStack stack) {
        if (!isGunItem(stack)) {
            return false;
        }
        Byte v = stack.getItemMeta().getPersistentDataContainer().get(laserModKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    public LaserModColor laserColor(ItemStack stack) {
        if (!isGunItem(stack)) {
            return LaserModColor.NONE;
        }
        if (!hasLaserMod(stack)) {
            return LaserModColor.NONE;
        }
        String id = stack.getItemMeta().getPersistentDataContainer().get(laserColorKey, PersistentDataType.STRING);
        return LaserModColor.fromId(id);
    }

    public void setLaserMod(ItemStack stack, LaserModColor color) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return;
        }
        LaserModColor c = color != null ? color : LaserModColor.NONE;
        ItemMeta meta = stack.getItemMeta();
        if (!c.isInstalled()) {
            meta.getPersistentDataContainer().remove(laserModKey);
            meta.getPersistentDataContainer().remove(laserColorKey);
        } else {
            meta.getPersistentDataContainer().set(laserModKey, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(laserColorKey, PersistentDataType.STRING, c.id());
        }
        stack.setItemMeta(meta);
        clearOpticModeIfBare(stack);
        refreshAttachmentLore(stack);
    }

    public boolean hasFlashlightMod(ItemStack stack) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return false;
        }
        Byte v = stack.getItemMeta().getPersistentDataContainer().get(flashlightModKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    public void setFlashlightMod(ItemStack stack, boolean on) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (on) {
            meta.getPersistentDataContainer().set(flashlightModKey, PersistentDataType.BYTE, (byte) 1);
            if (meta.getPersistentDataContainer().get(flashlightOnKey, PersistentDataType.BYTE) == null) {
                meta.getPersistentDataContainer().set(flashlightOnKey, PersistentDataType.BYTE, (byte) 0);
            }
        } else {
            meta.getPersistentDataContainer().remove(flashlightModKey);
            meta.getPersistentDataContainer().remove(flashlightOnKey);
        }
        stack.setItemMeta(meta);
        clearOpticModeIfBare(stack);
        refreshAttachmentLore(stack);
    }

    public boolean hasPeq(ItemStack stack) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return false;
        }
        Byte v = stack.getItemMeta().getPersistentDataContainer().get(peqKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    public void setPeq(ItemStack stack, boolean on) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (on) {
            meta.getPersistentDataContainer().set(peqKey, PersistentDataType.BYTE, (byte) 1);
            if (meta.getPersistentDataContainer().get(opticModeKey, PersistentDataType.STRING) == null) {
                meta.getPersistentDataContainer().set(opticModeKey, PersistentDataType.STRING, PeqMode.OFF.id());
            }
        } else {
            meta.getPersistentDataContainer().remove(peqKey);
            meta.getPersistentDataContainer().remove(opticModeKey);
            meta.getPersistentDataContainer().remove(flashlightOnKey);
        }
        stack.setItemMeta(meta);
        clearOpticModeIfBare(stack);
        refreshAttachmentLore(stack);
    }

    /** Drop stored optic mode when the gun no longer has any light/laser device. */
    private void clearOpticModeIfBare(ItemStack stack) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return;
        }
        if (hasPeq(stack) || hasLaserMod(stack) || hasFlashlightMod(stack)) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().remove(opticModeKey);
        meta.getPersistentDataContainer().remove(flashlightOnKey);
        stack.setItemMeta(meta);
    }

    public boolean canToggleOptic(ItemStack stack) {
        return hasPeq(stack) || hasLaserMod(stack) || hasFlashlightMod(stack);
    }

    public PeqMode opticMode(ItemStack stack) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return PeqMode.OFF;
        }
        var pdc = stack.getItemMeta().getPersistentDataContainer();
        String stored = pdc.get(opticModeKey, PersistentDataType.STRING);
        if (stored != null) {
            return PeqMode.fromId(stored);
        }
        // Legacy: laser always on, flashlight uses its own ON flag
        if (hasPeq(stack)) {
            return PeqMode.OFF;
        }
        if (hasLaserMod(stack)) {
            LaserModColor c = laserColor(stack);
            return c.infrared() ? PeqMode.IR : PeqMode.GREEN;
        }
        if (hasFlashlightMod(stack) && isFlashlightOn(stack)) {
            return PeqMode.FLASH;
        }
        return PeqMode.OFF;
    }

    public void setOpticMode(ItemStack stack, PeqMode mode) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return;
        }
        PeqMode m = mode != null ? mode : PeqMode.OFF;
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(opticModeKey, PersistentDataType.STRING, m.id());
        // Keep flashlight_on in sync for white-light modes
        if (hasFlashlightMod(stack) || hasPeq(stack)) {
            meta.getPersistentDataContainer().set(flashlightOnKey, PersistentDataType.BYTE,
                    m.whiteLight() ? (byte) 1 : (byte) 0);
        }
        stack.setItemMeta(meta);
        refreshAttachmentLore(stack);
    }

    public PeqMode cycleOpticMode(ItemStack stack) {
        List<PeqMode> cycle;
        if (hasPeq(stack)) {
            cycle = PeqMode.peqCycle();
        } else {
            LaserModColor laser = laserColor(stack);
            cycle = PeqMode.kitCycle(hasLaserMod(stack), laser.infrared(), hasFlashlightMod(stack));
        }
        PeqMode next = opticMode(stack).nextIn(cycle);
        setOpticMode(stack, next);
        return next;
    }

    /**
     * Human label for the current optic mode. Kit lasers use the module color
     * (e.g. "Pink Laser") instead of the internal {@link PeqMode#GREEN} name.
     */
    public String opticDeviceLabel(ItemStack stack, PeqMode mode) {
        if (mode == null || mode == PeqMode.OFF) {
            return "OFF";
        }
        if (mode == PeqMode.GREEN && hasLaserMod(stack)) {
            LaserModColor c = laserColor(stack);
            if (c.isInstalled() && !c.infrared()) {
                String id = c.id();
                return Character.toUpperCase(id.charAt(0)) + id.substring(1) + " Laser";
            }
        }
        if (hasPeq(stack)) {
            return mode.label();
        }
        return mode.label();
    }

    /** Suppressor / laser / optic / grip spread (&lt;1 = tighter). ADS vs hip use different parts. */
    public static final double SUPPRESSOR_SPREAD_MULT = 0.92;
    public static final double SUPPRESSOR_RECOIL_MULT = 0.88;
    public static final int SUPPRESSOR_STEALTH_ADD = 62;
    public static final double LASER_ON_SPREAD_MULT = 0.86;
    public static final double LASER_OFF_SPREAD_MULT = 0.97;

    public double accuracySpreadMultiplier(ItemStack stack) {
        return accuracySpreadMultiplier(stack, true);
    }

    /**
     * Spread multiplier (&lt;1 = tighter).
     * ADS: suppressor + laser/PEQ + optic. Hipfire: suppressor + laser/PEQ + grip hip spread.
     */
    public double accuracySpreadMultiplier(ItemStack stack, boolean aiming) {
        double m = 1.0;
        if (hasSuppressor(stack)) {
            m *= SUPPRESSOR_SPREAD_MULT;
        }
        if (hasPeq(stack) || hasLaserMod(stack)) {
            PeqMode mode = opticMode(stack);
            if (mode.laserActive()) {
                m *= LASER_ON_SPREAD_MULT;
            } else {
                m *= LASER_OFF_SPREAD_MULT;
            }
        }
        if (aiming) {
            OpticType optic = resolvedOptic(stack);
            if (optic != null) {
                m *= optic.adsSpreadMult();
            }
        } else {
            GripType grip = gripType(stack);
            if (grip.isInstalled()) {
                m *= grip.hipfireSpreadMult();
            }
        }
        return m;
    }

    /** Recoil / kick multiplier (&lt;1 = softer). */
    public double recoilMultiplier(ItemStack stack) {
        double m = 1.0;
        if (hasSuppressor(stack)) {
            m *= SUPPRESSOR_RECOIL_MULT;
        }
        GripType grip = gripType(stack);
        if (grip.isInstalled()) {
            m *= grip.recoilMult();
        }
        return m;
    }

    /** 0–100 stealth score for kill-feed bars. */
    public int stealthScore(ItemStack stack) {
        int s = 18;
        if (hasSuppressor(stack)) {
            s += SUPPRESSOR_STEALTH_ADD;
        }
        if (hasPeq(stack) || hasLaserMod(stack)) {
            PeqMode mode = opticMode(stack);
            if (mode.laserActive() && !mode.infrared()) {
                s -= 12; // visible beam is less stealthy
            } else if (mode == PeqMode.IR) {
                s += 8;
            }
        }
        if (hasFlashlightMod(stack) || hasPeq(stack)) {
            PeqMode mode = opticMode(stack);
            if (mode.whiteLight()) {
                s -= 20;
            }
        }
        return Math.max(0, Math.min(100, s));
    }

    /** Plain weapon class for kill-feed / UI (no color codes). */
    public String gunClassPlain(GunDefinition def) {
        if (def == null) {
            return "Firearm";
        }
        if (def.throwable()) {
            String id = def.fileName() == null ? "" : def.fileName().toLowerCase(Locale.ROOT);
            if (id.contains("molotov")) {
                return "Incendiary";
            }
            if (id.contains("flash")) {
                return "Tactical";
            }
            return "Throwable";
        }
        String cal = AmmoCaliber.normalize(def.ammoCaliber());
        return switch (cal) {
            case "sniper", "heavy" -> "Sniper Rifle";
            case "rifle", "auto" -> "Assault Rifle";
            case "pistol", "handgun" -> "Pistol";
            case "shotgun", "shot" -> "Shotgun";
            case "rocket", "launcher" -> "Launcher";
            case "energy", "plasma", "laser" -> "Energy Weapon";
            case "arrow", "bolt" -> "Bow";
            case "flare", "flares" -> "Flare Gun";
            case "melee" -> "Melee";
            default -> "Firearm";
        };
    }

    public String gunClassLabel(GunDefinition def) {
        String plain = gunClassPlain(def);
        if (def != null && def.throwable()) {
            String id = def.fileName() == null ? "" : def.fileName().toLowerCase(Locale.ROOT);
            if (id.contains("molotov")) {
                return "&6&l" + plain;
            }
            if (id.contains("flash")) {
                return "&e&l" + plain;
            }
            return "&c&l" + plain;
        }
        String cal = def == null ? "rifle" : AmmoCaliber.normalize(def.ammoCaliber());
        return switch (cal) {
            case "rocket", "launcher", "flare", "flares" -> "&6&l" + plain;
            case "energy", "plasma", "laser" -> "&b&l" + plain;
            default -> "&a&l" + plain;
        };
    }

    /**
     * Kill-feed hover: name, caliber, class, ASCII stat bars (attachment-adjusted), attachments.
     */
    public List<Component> killFeedHoverLines(GunDefinition gun, ItemStack stack) {
        return killFeedHoverLines(gun, stack, -1.0, null);
    }

    public List<Component> killFeedHoverLines(GunDefinition gun, ItemStack stack, double engagementBlocks) {
        return killFeedHoverLines(gun, stack, engagementBlocks, null);
    }

    /**
     * @param engagementBlocks kill distance in blocks; {@code < 0} omits the engagement line
     * @param round              bullet used for the kill (title); null falls back to gun name
     */
    public List<Component> killFeedHoverLines(GunDefinition gun, ItemStack stack,
                                              double engagementBlocks, RoundDefinition round) {
        List<Component> lines = new ArrayList<>();
        if (round != null) {
            lines.add(hoverLine(killFeedRoundTitle(round)));
            lines.add(hoverLine("&7Weapon: &f" + plainGunNameForHover(gun)));
            lines.add(hoverLine("&7(" + gunClassPlain(gun) + ")"));
            lines.add(hoverLine("&7Caliber: &f" + AmmoCaliber.displayLabel(round.caliber())));
        } else {
            lines.add(hoverLine(killFeedGunTitle(gun)));
            lines.add(hoverLine("&7(" + gunClassPlain(gun) + ")"));
            lines.add(hoverLine(killFeedAmmoCaliberLine(gun)));
        }
        if (engagementBlocks >= 0.0) {
            lines.add(hoverLine("&7Engagement: &f" + Math.round(engagementBlocks) + "m"));
        }
        lines.add(hoverLine("&8────────────"));
        if (gun != null) {
            double hipSpread = Math.max(0.001, gun.accuracy()) * accuracySpreadMultiplier(stack, false);
            double adsSpread = (gun.accuracyAimed() >= 0 ? gun.accuracyAimed() : gun.accuracy())
                    * accuracySpreadMultiplier(stack, true);
            adsSpread = Math.max(0.001, adsSpread);
            // Primary Accuracy = how the gun is meant to shoot (ADS when configured).
            // Soft curve — old linear cut at 0.22 floored snipers with loose hipfire to 0%.
            int accPct = accuracyPctFromSpread(adsSpread);
            int hipPct = accuracyPctFromSpread(hipSpread);
            double recoil = Math.max(0.0, gun.recoil()) * recoilMultiplier(stack);
            int dmgPct = clampPct(gun.gunDamage() / 42.0 * 100.0);
            int rangePct = clampPct(gun.maxDistance() / 140.0 * 100.0);
            int controlPct = clampPct(100.0 / (1.0 + recoil * 6.0));
            int ratePct = clampPct(100.0 / (1.0 + gun.bulletDelayTime() / 12.0));
            int stealthPct = stealthScore(stack);
            lines.add(statBarLine("Damage", dmgPct));
            lines.add(statBarLine("Accuracy", accPct));
            if (gun.accuracyAimed() >= 0 && hipSpread > adsSpread + 0.015) {
                lines.add(statBarLine("Hipfire", hipPct));
            }
            lines.add(statBarLine("Range", rangePct));
            lines.add(statBarLine("Control", controlPct));
            lines.add(statBarLine("Fire Rate", ratePct));
            lines.add(statBarLine("Stealth", stealthPct));
            OpticType zo = resolvedOptic(stack);
            if (zo != null && zo.allowsZeroing()) {
                lines.add(hoverLine("&7Zero: &f" + zeroYards(stack) + " yd &8(sneak+F)"));
            }
            int cond = gunCondition(stack);
            if (cond < 100) {
                String tone = cond >= 70 ? "&a" : (cond >= 40 ? "&e" : "&c");
                lines.add(hoverLine("&7Condition: " + tone + cond + "%"));
            }
        }
        lines.add(hoverLine("&8────────────"));
        lines.addAll(attachmentHoverLines(stack));
        return lines;
    }

    private static Component hoverLine(String legacy) {
        return colorize(legacy).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }

    /** Gold title from the gun's configured display name (not bold). */
    private String killFeedGunTitle(GunDefinition gun) {
        return "&6" + plainGunNameForHover(gun);
    }

    private String killFeedRoundTitle(RoundDefinition round) {
        if (round == null) {
            return "&6Round";
        }
        String raw = round.displayName() == null || round.displayName().isBlank()
                ? round.fileName()
                : round.displayName();
        String plain = PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(raw == null ? "" : raw));
        plain = plain.replaceAll("§.", "").trim();
        if (plain.isEmpty()) {
            plain = round.fileName() != null ? round.fileName() : "Round";
        }
        return "&6" + plain;
    }

    private String plainGunNameForHover(GunDefinition gun) {
        String raw = gun == null || gun.displayName() == null || gun.displayName().isBlank()
                ? (gun != null && gun.fileName() != null ? gun.fileName() : "Gun")
                : gun.displayName();
        String plain = PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(raw));
        plain = plain.replaceAll("§.", "").trim();
        if (plain.isEmpty()) {
            plain = gun != null && gun.fileName() != null ? gun.fileName() : "Gun";
        }
        return plain;
    }

    /** Ammo family the weapon feeds — from gun caliber (resolved for every gun). */
    private String killFeedAmmoCaliberLine(GunDefinition gun) {
        if (gun == null) {
            return "&7Caliber: &fUnknown";
        }
        return "&7Caliber: &f" + AmmoCaliber.displayLabel(gun.ammoCaliber());
    }

    private static int clampPct(double value) {
        return (int) Math.round(Math.max(0, Math.min(100, value)));
    }

    /**
     * Convert WarZ spread (lower = tighter) to a 0–100 score.
     * Examples: 0.01 → ~91%, 0.05 → ~67%, 0.15 → ~40%, 0.4 → ~20%.
     */
    private static int accuracyPctFromSpread(double spread) {
        double s = Math.max(0.0, spread);
        return clampPct(100.0 / (1.0 + s * 10.0));
    }

    private Component statBarLine(String name, int pct) {
        int filled = Math.max(0, Math.min(10, (int) Math.round(pct / 10.0)));
        StringBuilder bar = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            bar.append(i < filled ? '|' : '.');
        }
        String color = pct >= 70 ? "&a" : (pct >= 40 ? "&e" : "&c");
        String line = String.format(Locale.ROOT, "&7%-9s %s%s %s%d%%", name, color, bar, color, pct);
        return hoverLine(line);
    }

    /**
     * Hover lines for kill-feed weapon names — suppressor / laser / light / PEQ.
     */
    public List<Component> attachmentHoverLines(ItemStack stack) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("Attachments", NamedTextColor.GOLD)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        if (stack == null || !isGunItem(stack)) {
            lines.add(Component.text("None fitted", NamedTextColor.DARK_GRAY)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            return lines;
        }
        boolean any = false;
        SuppressorType sup = suppressorType(stack);
        if (sup != null) {
            any = true;
            lines.add(colorize(sup.displayName() + " &8(quieter)")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            lines.addAll(suppressorMechanicLines());
        }
        if (hasPeq(stack)) {
            any = true;
            PeqMode mode = opticMode(stack);
            lines.add(colorize("&6AN/PEQ-15 &8(" + opticDeviceLabel(stack, mode) + ")")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            lines.addAll(peqMechanicLines(stack, mode));
        } else {
            LaserModColor laser = laserColor(stack);
            if (laser.isInstalled()) {
                any = true;
                lines.add(colorize(laser.loreLine())
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                lines.addAll(laserMechanicLines(laser, opticMode(stack)));
            }
            if (hasFlashlightMod(stack)) {
                any = true;
                lines.add(colorize("&7Flashlight module")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                lines.addAll(lightMechanicLines(opticMode(stack)));
            }
        }
        if (hasMagAdapter(stack)) {
            any = true;
            lines.add(colorize("&6AK↔AR Mag Adapter")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            lines.add(hoverLine("&8  AK and AR mags of the same caliber"));
        }
        OpticType optic = opticTypeStored(stack);
        if (optic != null) {
            any = true;
            lines.add(colorize(optic.displayName() + " &8(optic)")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            lines.addAll(opticMechanicLines(optic));
        } else if (OpticType.acceptsRail(gunId(stack).flatMap(id -> plugin.registry().get(id)).orElse(null))) {
            lines.add(colorize("&7Ironsights &8(default)")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            lines.addAll(opticMechanicLines(OpticType.IRONS));
            any = true;
        }
        GripType grip = gripType(stack);
        if (grip.isInstalled()) {
            any = true;
            lines.add(colorize(grip.displayName() + " &8(grip)")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            lines.addAll(gripMechanicLines(grip));
        }
        if (!any) {
            lines.add(Component.text("None fitted", NamedTextColor.DARK_GRAY)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
        return lines;
    }

    private List<Component> suppressorMechanicLines() {
        List<Component> lines = new ArrayList<>();
        lines.add(hoverLine("&8  spread " + deltaPct(SUPPRESSOR_SPREAD_MULT)
                + " · recoil " + deltaPct(SUPPRESSOR_RECOIL_MULT)
                + " · stealth +" + SUPPRESSOR_STEALTH_ADD));
        lines.add(hoverLine("&8  quieter report · cuts sonic crack"));
        return lines;
    }

    private List<Component> laserMechanicLines(LaserModColor laser, PeqMode mode) {
        List<Component> lines = new ArrayList<>();
        lines.add(hoverLine("&8  hip/ADS spread " + deltaPct(LASER_ON_SPREAD_MULT) + " while on"));
        if (laser != null && laser.infrared()) {
            lines.add(hoverLine("&8  IR beam · stealth +8 · NVG only"));
        } else if (mode != null && mode.laserActive() && !mode.infrared()) {
            lines.add(hoverLine("&8  visible beam · stealth -12"));
        } else {
            lines.add(hoverLine("&8  visible beam · stealth -12 while on"));
        }
        return lines;
    }

    private List<Component> lightMechanicLines(PeqMode mode) {
        List<Component> lines = new ArrayList<>();
        lines.add(hoverLine("&8  white light · stealth -20 while on"));
        if (mode != null && mode.whiteLight()) {
            lines.add(hoverLine("&8  currently lighting the muzzle"));
        }
        return lines;
    }

    private List<Component> peqMechanicLines(ItemStack stack, PeqMode mode) {
        List<Component> lines = new ArrayList<>();
        if (mode != null && mode.laserActive()) {
            lines.add(hoverLine("&8  laser on · spread " + deltaPct(LASER_ON_SPREAD_MULT)));
            if (mode.infrared()) {
                lines.add(hoverLine("&8  IR · stealth +8 · NVG only"));
            } else {
                lines.add(hoverLine("&8  visible laser · stealth -12"));
            }
        } else if (mode != null && mode.whiteLight()) {
            lines.addAll(lightMechanicLines(mode));
        } else {
            lines.add(hoverLine("&8  Z cycles IR / green / light / strobe"));
            lines.add(hoverLine("&8  laser spread " + deltaPct(LASER_ON_SPREAD_MULT)
                    + " · light stealth -20"));
        }
        return lines;
    }

    private List<Component> opticMechanicLines(OpticType optic) {
        List<Component> lines = new ArrayList<>();
        if (optic == null) {
            return lines;
        }
        String extra = "";
        if (optic.allowsZeroing()) {
            extra = " · Shift+F zero";
        }
        lines.add(hoverLine("&8  ADS spread " + deltaPct(optic.adsSpreadMult())
                + " · sway " + deltaPct(optic.swayMult()) + extra));
        return lines;
    }

    private List<Component> gripMechanicLines(GripType grip) {
        List<Component> lines = new ArrayList<>();
        if (grip == null || !grip.isInstalled()) {
            return lines;
        }
        String line = "&8  recoil " + deltaPct(grip.recoilMult())
                + " · hip spread " + deltaPct(grip.hipfireSpreadMult())
                + " · ADS sway " + deltaPct(grip.adsSwayMult());
        lines.add(hoverLine(line));
        if (grip.isBipod()) {
            lines.add(hoverLine("&8  rest sway " + deltaPct(grip.restSwayMult())
                    + " when prone or sneaking"));
        }
        return lines;
    }

    public void refreshAttachmentLore(ItemStack stack) {
        // Rebuild compact inventory lore (attachments + Shift hint). Expanded tooltips
        // are re-applied by GunTooltipListener while the viewer is sneaking.
        applyGunInventoryLore(stack, false);
    }

    /** True for lore lines this factory owns for gun attachments / optic hints. */
    private static boolean isManagedAttachmentLore(Component line) {
        if (line == null) {
            return false;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(line)
                .toLowerCase(Locale.ROOT)
                .replace('\u00a0', ' ')
                .trim();
        if (plain.isEmpty()) {
            return false;
        }
        return plain.contains("suppressor")
                || (plain.contains("fitted") && plain.contains("quieter"))
                || plain.startsWith("laser:")
                || plain.contains("laser: ")
                || plain.contains("flashlight")
                || plain.contains("an/peq")
                || plain.contains("peq-15")
                || plain.startsWith("device:")
                || plain.startsWith("optic:")
                || plain.startsWith("grip:")
                || plain.contains("ak↔ar")
                || plain.contains("ak↔ar adapter")
                || plain.contains("needs adapter")
                || plain.contains("ironsight")
                || plain.contains("(optic)")
                || plain.contains("(grip)")
                || plain.contains("eotech")
                || plain.contains("acog")
                || plain.contains("bipod")
                || plain.contains("handstop")
                || plain.contains("vertical grip")
                || plain.contains("angled grip")
                || plain.contains("z to toggle")
                || (plain.contains("to toggle") && (plain.startsWith("z") || plain.contains(" sneak")));
    }

    /** Compact / detail hint for AR↔AK magazine bridging. */
    public static String adapterHintLine(MagazineType type) {
        if (type == null) {
            return "&8—";
        }
        return switch (type.platform()) {
            case AR -> "&eAK rifles need &6AK↔AR Adapter";
            case AK -> "&eAR rifles need &6AK↔AR Adapter";
            case SNIPER, SHOTGUN -> "&cNo adapter — platform-locked";
            default -> "&7Native fit only (no adapter bridge)";
        };
    }

    public ItemStack createSuppressorPart() {
        return createSuppressorPart(SuppressorType.RIFLE);
    }

    public ItemStack createSuppressorPart(SuppressorType type) {
        SuppressorType t = type != null ? type : SuppressorType.RIFLE;
        ItemStack stack = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(attachmentPartKey, PersistentDataType.STRING, "suppressor:" + t.id());
        applyCmd(meta, t.customModelData());
        meta.displayName(colorize(t.displayName()));
        List<Component> compact = new ArrayList<>();
        compact.add(colorize("&7Muzzle device — quieter report"));
        compact.add(colorize(t.fitLore()));
        List<Component> detail = new ArrayList<>(compact);
        detail.addAll(suppressorMechanicLines());
        detail.add(colorize("&7Shows as a &f3D model &7on the fitted gun"));
        detail.add(colorize("&8Craft at a &fGun Workbench"));
        applyShiftLore(meta, compact, detail);
        stack.setItemMeta(meta);
        return stack;
    }

    public SuppressorType suppressorPartType(ItemStack stack) {
        String part = attachmentPartId(stack);
        if (part == null || !part.startsWith("suppressor")) {
            return null;
        }
        if (part.equals("suppressor")) {
            return SuppressorType.RIFLE;
        }
        if (part.startsWith("suppressor:")) {
            return SuppressorType.fromId(part.substring("suppressor:".length()));
        }
        return null;
    }

    public ItemStack createLaserModulePart(LaserModColor color) {
        LaserModColor c = color != null && color.isInstalled() ? color : LaserModColor.RED;
        ItemStack stack = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(attachmentPartKey, PersistentDataType.STRING, "laser:" + c.id());
        applyCmd(meta, c.customModelData());
        meta.displayName(colorize("&bLaser Module &8(" + c.id() + ")"));
        List<Component> compact = new ArrayList<>();
        compact.add(colorize(c.loreLine()));
        compact.add(colorize("&eZ &7to toggle on a fitted gun"));
        List<Component> detail = new ArrayList<>(compact);
        detail.addAll(laserMechanicLines(c, c.infrared() ? PeqMode.IR : PeqMode.GREEN));
        detail.add(colorize("&7Shows as a &f3D model &7on the fitted gun"));
        detail.add(colorize("&8Craft at a &fGun Workbench"));
        applyShiftLore(meta, compact, detail);
        stack.setItemMeta(meta);
        return stack;
    }

    public LaserModColor laserPartColor(ItemStack stack) {
        String part = attachmentPartId(stack);
        if (part == null || !part.startsWith("laser:")) {
            return null;
        }
        LaserModColor c = LaserModColor.fromId(part.substring("laser:".length()));
        return c.isInstalled() ? c : null;
    }

    public static final int CMD_FLASHLIGHT = 3140;
    public static final int CMD_PEQ15 = 3141;
    public static final int CMD_MAG_ADAPTER = 3142;
    /**
     * Handheld gear on the stick base. Both of these shipped with no CMD at all,
     * so the tactical flashlight and the radiolink were literally sticks in the
     * hand and in the gear menu.
     */
    public static final int CMD_FLASHLIGHT_HANDHELD = 3143;
    public static final int CMD_RADIOLINK = 3144;

    /** Workbench craft part — weapon light module. */
    public ItemStack createFlashlightModulePart() {
        ItemStack stack = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(attachmentPartKey, PersistentDataType.STRING, "flashlight");
        applyCmd(meta, CMD_FLASHLIGHT);
        meta.displayName(colorize("&eFlashlight Module"));
        List<Component> compact = new ArrayList<>();
        compact.add(colorize("&7Weapon light — look-direction beam"));
        compact.add(colorize("&eZ &7to toggle on a fitted gun"));
        List<Component> detail = new ArrayList<>(compact);
        detail.addAll(lightMechanicLines(PeqMode.FLASH));
        detail.add(colorize("&7Works alongside a laser module"));
        detail.add(colorize("&7Shows as a &f3D model &7on the fitted gun"));
        detail.add(colorize("&8Craft at a &fGun Workbench"));
        applyShiftLore(meta, compact, detail);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isFlashlightModulePart(ItemStack stack) {
        String part = attachmentPartId(stack);
        return part != null && part.equals("flashlight");
    }

    /** AN/PEQ-15 multi-function laser / illuminator unit. */
    public ItemStack createPeq15Part() {
        ItemStack stack = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(attachmentPartKey, PersistentDataType.STRING, "peq15");
        applyCmd(meta, CMD_PEQ15);
        meta.displayName(colorize("&6AN/PEQ-15"));
        List<Component> compact = new ArrayList<>();
        compact.add(colorize("&7IR laser · green laser · white light · strobe"));
        compact.add(colorize("&eZ &7to cycle modes on a fitted gun"));
        List<Component> detail = new ArrayList<>(compact);
        detail.addAll(peqMechanicLines(null, PeqMode.OFF));
        detail.add(colorize("&7Shows as a &f3D model &7on the fitted gun"));
        detail.add(colorize("&8Craft at a &fGun Workbench"));
        applyShiftLore(meta, compact, detail);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isPeq15Part(ItemStack stack) {
        String part = attachmentPartId(stack);
        return part != null && part.equals("peq15");
    }

    /** Flashlight slot accepts a flashlight module or an AN/PEQ-15. */
    public boolean isLightDevicePart(ItemStack stack) {
        return isFlashlightModulePart(stack) || isPeq15Part(stack);
    }

    /** AK ↔ AR magazine well adapter — rifle calibers only; never bridges .50. */
    public static final String MAG_ADAPTER_AK_AR = "mag_adapter_ak_ar";

    public ItemStack createMagAdapterAkAr() {
        ItemStack stack = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(attachmentPartKey, PersistentDataType.STRING, MAG_ADAPTER_AK_AR);
        applyCmd(meta, CMD_MAG_ADAPTER);
        meta.displayName(colorize("&6AK↔AR Mag Adapter"));
        List<Component> compact = new ArrayList<>();
        compact.add(colorize("&7Lets &fAK &7mags seat in &fAR &7guns and vice versa"));
        compact.add(colorize("&7Same caliber only"));
        List<Component> detail = new ArrayList<>(compact);
        detail.add(colorize("&c.50 / sniper &7never fits"));
        detail.add(colorize("&7Shows as a &f3D model &7on the fitted gun"));
        detail.add(colorize("&8Craft at a &fGun Workbench"));
        applyShiftLore(meta, compact, detail);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isMagAdapterPart(ItemStack stack) {
        String part = attachmentPartId(stack);
        return part != null && part.equals(MAG_ADAPTER_AK_AR);
    }

    public boolean hasMagAdapter(ItemStack stack) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return false;
        }
        Byte v = stack.getItemMeta().getPersistentDataContainer().get(magAdapterKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    public void setMagAdapter(ItemStack stack, boolean on) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (on) {
            meta.getPersistentDataContainer().set(magAdapterKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            meta.getPersistentDataContainer().remove(magAdapterKey);
        }
        stack.setItemMeta(meta);
        refreshAttachmentLore(stack);
    }

    /** Stored optic only — null means bare rail (default irons). */
    public OpticType opticTypeStored(ItemStack stack) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return null;
        }
        String id = stack.getItemMeta().getPersistentDataContainer().get(opticKey, PersistentDataType.STRING);
        if (id == null || id.isBlank()) {
            return null;
        }
        return OpticType.fromId(id);
    }

    /** Resolved optic for HUD/stats — bare rail → irons on eligible guns. */
    public OpticType resolvedOptic(ItemStack stack) {
        OpticType stored = opticTypeStored(stack);
        if (stored != null) {
            return stored;
        }
        return gunId(stack).flatMap(id -> plugin.registry().get(id))
                .filter(OpticType::acceptsRail)
                .map(g -> OpticType.IRONS)
                .orElse(null);
    }

    public boolean hasOpticPart(ItemStack stack) {
        return opticTypeStored(stack) != null;
    }

    public void setOptic(ItemStack stack, OpticType optic) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (optic == null || optic == OpticType.IRONS) {
            // Explicit irons OR clear → bare default irons (remove PDC)
            if (optic == OpticType.IRONS) {
                meta.getPersistentDataContainer().set(opticKey, PersistentDataType.STRING, OpticType.IRONS.id());
            } else {
                meta.getPersistentDataContainer().remove(opticKey);
            }
        } else {
            meta.getPersistentDataContainer().set(opticKey, PersistentDataType.STRING, optic.id());
        }
        stack.setItemMeta(meta);
        refreshAttachmentLore(stack);
    }

    public void clearOptic(ItemStack stack) {
        setOptic(stack, null);
    }

    public GripType gripType(ItemStack stack) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return GripType.NONE;
        }
        String id = stack.getItemMeta().getPersistentDataContainer().get(gripKey, PersistentDataType.STRING);
        return GripType.fromId(id);
    }

    public boolean hasGrip(ItemStack stack) {
        return gripType(stack).isInstalled();
    }

    public void setGrip(ItemStack stack, GripType grip) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (grip == null || !grip.isInstalled()) {
            meta.getPersistentDataContainer().remove(gripKey);
        } else {
            meta.getPersistentDataContainer().set(gripKey, PersistentDataType.STRING, grip.id());
        }
        stack.setItemMeta(meta);
        refreshAttachmentLore(stack);
    }

    public ItemStack createOpticPart(OpticType type) {
        OpticType t = type != null ? type : OpticType.RDS;
        // Iron nugget + CMD so resource-pack / companion 3D optic models apply.
        ItemStack stack = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(attachmentPartKey, PersistentDataType.STRING, "optic:" + t.id());
        applyCmd(meta, t.customModelData());
        meta.displayName(colorize(t.displayName()));
        List<Component> compact = new ArrayList<>();
        compact.add(colorize("&7Rail optic — craft at a &fGun Workbench"));
        compact.add(colorize("&7Fits &fAR / AK / SMG / pistol / shotgun / sniper"));
        List<Component> detail = new ArrayList<>(compact);
        detail.addAll(opticMechanicLines(t));
        detail.add(colorize("&7Shows as a &f3D model &7on the fitted gun"));
        if (t.allowsZeroing()) {
            detail.add(colorize("&7Zero: &fShift+F &7while ADS"));
        }
        if (t.laserTintsReticle()) {
            detail.add(colorize("&7Holo reticle tinted by fitted &claser"));
        }
        applyShiftLore(meta, compact, detail);
        stack.setItemMeta(meta);
        return stack;
    }

    public OpticType opticPartType(ItemStack stack) {
        String part = attachmentPartId(stack);
        if (part == null || !part.startsWith("optic:")) {
            return null;
        }
        return OpticType.fromId(part.substring("optic:".length()));
    }

    public ItemStack createGripPart(GripType type) {
        GripType t = type != null && type.isInstalled() ? type : GripType.VERTICAL;
        ItemStack stack = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(attachmentPartKey, PersistentDataType.STRING, "grip:" + t.id());
        applyCmd(meta, t.customModelData());
        meta.displayName(colorize(t.displayName()));
        List<Component> compact = new ArrayList<>();
        compact.add(colorize("&7Foregrip — craft at a &fGun Workbench"));
        compact.add(colorize("&7Fits combat firearms"));
        List<Component> detail = new ArrayList<>(compact);
        detail.addAll(gripMechanicLines(t));
        detail.add(colorize("&7Shows as a &f3D model &7on the fitted gun"));
        applyShiftLore(meta, compact, detail);
        stack.setItemMeta(meta);
        return stack;
    }

    public GripType gripPartType(ItemStack stack) {
        String part = attachmentPartId(stack);
        if (part == null || !part.startsWith("grip:")) {
            return null;
        }
        return GripType.fromId(part.substring("grip:".length()));
    }

    /** Reticle RGB for holo optics from laser module; default red. */
    public int reticleRgb(ItemStack stack, OpticType optic) {
        if (optic != null && optic.laserTintsReticle()) {
            LaserModColor laser = laserColor(stack);
            if (laser.isInstalled() && laser.color() != null && !laser.infrared()) {
                Color c = laser.color();
                return (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
            }
        }
        return 0xFF2828;
    }

    /** Zero distance in yards for long guns (100 / 200 / 300). */
    public int zeroYards(ItemStack stack) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return 100;
        }
        Integer z = stack.getItemMeta().getPersistentDataContainer().get(zeroYardsKey, PersistentDataType.INTEGER);
        if (z == null) {
            return 100;
        }
        if (z == 200 || z == 300) {
            return z;
        }
        return 100;
    }

    public int cycleZeroYards(ItemStack stack) {
        int next = switch (zeroYards(stack)) {
            case 100 -> 200;
            case 200 -> 300;
            default -> 100;
        };
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return next;
        }
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(zeroYardsKey, PersistentDataType.INTEGER, next);
        stack.setItemMeta(meta);
        return next;
    }

    /** 0–100 condition; lower = more jam chance. */
    public int gunCondition(ItemStack stack) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return 100;
        }
        Integer c = stack.getItemMeta().getPersistentDataContainer().get(gunConditionKey, PersistentDataType.INTEGER);
        return c == null ? 100 : Math.max(0, Math.min(100, c));
    }

    public void setGunCondition(ItemStack stack, int condition) {
        if (!isGunItem(stack) || !stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(gunConditionKey, PersistentDataType.INTEGER,
                Math.max(0, Math.min(100, condition)));
        stack.setItemMeta(meta);
    }

    public void wearGun(ItemStack stack, int amount) {
        if (amount <= 0) {
            return;
        }
        setGunCondition(stack, gunCondition(stack) - amount);
    }

    public ItemStack createGlassBlock(GlassType type) {
        return createGlassItem(type, false);
    }

    public ItemStack createGlassPane(GlassType type) {
        return createGlassItem(type, true);
    }

    private ItemStack createGlassItem(GlassType type, boolean pane) {
        GlassType t = type != null ? type : GlassType.STANDARD;
        ItemStack stack = new ItemStack(pane ? t.paneMaterial() : t.blockMaterial(), 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(attachmentPartKey, PersistentDataType.STRING,
                "glass:" + t.id() + (pane ? ":pane" : ":block"));
        meta.displayName(colorize("&b" + t.displayName() + (pane ? " Pane" : " Block")));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Tactical glass — ballistic interactions"));
        lore.add(colorize("&8Pen resist &f" + String.format(Locale.ROOT, "%.0f%%", t.penResist() * 100)
                + " &8· Frag &f" + String.format(Locale.ROOT, "%.0f%%", t.fragmentation() * 100)));
        lore.add(colorize("&8Mode &f" + t.shatter().name().toLowerCase(Locale.ROOT).replace('_', ' ')));
        lore.add(colorize("&ePlace &7→ registers as this glass type"));
        lore.add(colorize("&7AP / sniper punch through easier; HP frags more"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public GlassType glassType(ItemStack stack) {
        String part = attachmentPartId(stack);
        if (part == null || !part.startsWith("glass:")) {
            return null;
        }
        String[] bits = part.split(":");
        if (bits.length < 2) {
            return null;
        }
        return GlassType.fromId(bits[1]);
    }

    public boolean isGlassPaneItem(ItemStack stack) {
        String part = attachmentPartId(stack);
        return part != null && part.startsWith("glass:") && part.endsWith(":pane");
    }

    public boolean isGlassBlockItem(ItemStack stack) {
        String part = attachmentPartId(stack);
        return part != null && part.startsWith("glass:") && part.endsWith(":block");
    }

    public String attachmentPartId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return null;
        }
        String id = stack.getItemMeta().getPersistentDataContainer()
                .get(attachmentPartKey, PersistentDataType.STRING);
        if (id != null && !id.isBlank()) {
            return id;
        }
        return attachmentPartIdFromCmd(stack);
    }

    /**
     * Shift-click / vanilla clones on CardForge can drop PDC while keeping CMD.
     * Identify loose workbench parts from the iron-nugget/stick CMD ranges.
     */
    private static String attachmentPartIdFromCmd(ItemStack stack) {
        int cmd = cmdFloat(stack);
        if (cmd < 0) {
            return null;
        }
        for (OpticType t : OpticType.values()) {
            if (t.customModelData() == cmd) {
                return "optic:" + t.id();
            }
        }
        for (SuppressorType t : SuppressorType.values()) {
            if (t.customModelData() == cmd) {
                return "suppressor:" + t.id();
            }
        }
        for (LaserModColor t : LaserModColor.values()) {
            if (t.isInstalled() && t.customModelData() == cmd) {
                return "laser:" + t.id();
            }
        }
        for (GripType t : GripType.values()) {
            if (t.isInstalled() && t.customModelData() == cmd) {
                return "grip:" + t.id();
            }
        }
        if (cmd == CMD_FLASHLIGHT) {
            return "flashlight";
        }
        if (cmd == CMD_PEQ15) {
            return "peq15";
        }
        if (cmd == CMD_MAG_ADAPTER) {
            return MAG_ADAPTER_AK_AR;
        }
        return null;
    }

    private static int cmdFloat(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return -1;
        }
        try {
            var cmd = stack.getItemMeta().getCustomModelDataComponent();
            List<Float> floats = cmd.getFloats();
            if (floats == null || floats.isEmpty() || floats.get(0) == null) {
                return -1;
            }
            return Math.round(floats.get(0));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public boolean isAttachmentPart(ItemStack stack) {
        return attachmentPartId(stack) != null;
    }

    /** Rebuild compact lore + Shift detail on existing attachment parts (join / inv open). */
    public void refreshAttachmentPartLore(ItemStack stack) {
        if (stack == null || !isAttachmentPart(stack) || !stack.hasItemMeta()) {
            return;
        }
        String part = attachmentPartId(stack);
        if (part == null || "gun_workbench".equals(part) || part.startsWith("glass:")) {
            return;
        }
        ItemStack fresh = createFromAttachmentPart(part);
        if (fresh == null || !fresh.hasItemMeta()) {
            return;
        }
        ItemMeta src = fresh.getItemMeta();
        ItemMeta dst = stack.getItemMeta();
        dst.lore(src.lore());
        String detail = src.getPersistentDataContainer().get(tooltipDetailKey, PersistentDataType.STRING);
        if (detail != null && !detail.isBlank()) {
            dst.getPersistentDataContainer().set(tooltipDetailKey, PersistentDataType.STRING, detail);
        }
        stack.setItemMeta(dst);
    }

    public static final int WORKBENCH_CMD = 2100;

    public ItemStack createGunWorkbenchItem() {
        ItemStack stack = new ItemStack(Material.FLETCHING_TABLE, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(attachmentPartKey, PersistentDataType.STRING, "gun_workbench");
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, WORKBENCH_CMD);
        applyCmd(meta, WORKBENCH_CMD);
        meta.displayName(colorize("&6Gun Workbench"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Place to install a gunsmith bench"));
        lore.add(colorize("&7Craft suppressor / laser / flashlight"));
        lore.add(colorize("&eRight-click placed bench &7→ open"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isGunWorkbenchItem(ItemStack stack) {
        String id = attachmentPartId(stack);
        return id != null && id.equals("gun_workbench");
    }

    /** Max loose stack for explosive / HE rounds (pocket cook risk). */
    public static final int HE_LOOSE_STACK_MAX = 8;

    /**
     * Build one or more round stacks for {@code amount} (HE splits into stacks of
     * {@link #HE_LOOSE_STACK_MAX} so unload never silently drops rounds).
     */
    public List<ItemStack> createRounds(RoundDefinition def, int amount) {
        List<ItemStack> out = new ArrayList<>();
        if (def == null || amount <= 0) {
            return out;
        }
        int left = amount;
        int maxStack = def.explodeRadiusAdd() > 0 ? HE_LOOSE_STACK_MAX : 64;
        while (left > 0) {
            int n = Math.min(maxStack, left);
            out.add(createRound(def, n));
            left -= n;
        }
        return out;
    }

    public ItemStack createRound(RoundDefinition def, int amount) {
        if (def == null || amount <= 0) {
            return new ItemStack(Material.AIR);
        }
        // HE / explosive loose rounds stay small in the pocket (cook / mishandle risk).
        int capped = amount;
        if (def.explodeRadiusAdd() > 0) {
            capped = Math.min(capped, HE_LOOSE_STACK_MAX);
        }
        capped = Math.max(1, Math.min(64, capped));
        ItemStack stack = new ItemStack(Material.STICK, capped);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(roundKey, PersistentDataType.STRING, def.fileName());
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, def.customModelData());
        applyCmd(meta, def.customModelData());
        meta.displayName(colorize(def.displayName()));
        String calLine = "&7Caliber: &f" + AmmoCaliber.displayLabel(def.caliber());
        List<Component> detail = new ArrayList<>();
        detail.add(colorize(calLine));
        detail.add(colorize("&7" + RoundBlurbs.describe(def)));
        detail.add(colorize("&7Damage x&f" + String.format(Locale.ROOT, "%.2f", def.damageMult())
                + " &8| &7AP &f" + (def.armorPenAdd() >= 0 ? "+" : "") + def.armorPenAdd()));
        if (def.tracer()) {
            detail.add(colorize("&aTracer round"));
        }
        if (def.subsonic()) {
            detail.add(colorize("&8Subsonic &7— no sonic crack"));
            detail.add(colorize("&8+ suppressor &7= whisper report"));
        }
        if (def.muzzleFlash()) {
            detail.add(colorize("&7Muzzle flash &fx" + String.format(Locale.ROOT, "%.2f", def.muzzleScale())));
        }
        if (def.explodeRadiusAdd() > 0) {
            detail.add(colorize("&6Explosive &7radius &f+"
                    + String.format(Locale.ROOT, "%.1f", def.explodeRadiusAdd())));
            detail.add(colorize("&cPocket stacks max 8 &7· &cchamber cooks if on fire"));
        }
        detail.add(colorize("&8Tagged ammo — plain " + def.material().name().toLowerCase(Locale.ROOT) + " will not work"));
        writeTooltipDetail(meta, detail);
        List<Component> lore = new ArrayList<>();
        lore.add(colorize(calLine)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        // UAV ordnance: show the short identity line without requiring Shift.
        String cal = def.caliber() == null ? "" : def.caliber().toLowerCase(Locale.ROOT);
        String rid = def.fileName() == null ? "" : def.fileName().toLowerCase(Locale.ROOT);
        boolean uavOrdnance = "rocket".equals(cal) || rid.startsWith("rocket_")
                || rid.startsWith("gbu_") || rid.equals("aim9x");
        if (uavOrdnance) {
            lore.add(colorize("&7" + RoundBlurbs.describe(def))
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
        lore.add(colorize("&8Hold &eShift")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(lore);
        stack.setItemMeta(meta);
        // Rounds of the same kind are interchangeable, so they stack. Explosive
        // ones stay in small piles - the same reason they always did.
        applyMaxStack(stack, def.explodeRadiusAdd() > 0 ? HE_LOOSE_STACK_MAX : 64);
        return stack;
    }

    /** Multi-palette Quad NODS (H cycles phosphors). */
    public ItemStack createNvgHelmet() {
        return createNvgHelmet(NvgGear.Variant.MULTI);
    }

    public ItemStack createNvgHelmet(NvgGear.Variant variant) {
        NvgGear.Variant v = variant == null ? NvgGear.Variant.MULTI : variant;
        ItemStack stack = new ItemStack(Material.CARVED_PUMPKIN, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(nvgKey, PersistentDataType.STRING, v.pdc);
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, v.cmd);
        applyCmd(meta, v.cmd);
        meta.displayName(colorize(v.displayName));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Gen-III+ quad-tube night vision"));
        lore.add(colorize(v.multi() ? "&7Multi-phosphor tubes" : "&7Fixed &f" + v.shortLabel + " &7phosphor"));
        lore.add(colorize("&7Wear on head for NVG"));
        lore.add(colorize("&aIR lasers &7visible only while worn"));
        if (v == NvgGear.Variant.RED || v == NvgGear.Variant.MULTI) {
            lore.add(colorize("&cRed phosphor &7reveals anomalous contacts"));
        }
        lore.add(colorize(v.hHint));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    /** Multi-palette FLIR (H cycles thermal modes). */
    public ItemStack createThermalHelmet() {
        return createThermalHelmet(ThermalGear.Variant.MULTI);
    }

    public ItemStack createThermalHelmet(ThermalGear.Variant variant) {
        ThermalGear.Variant v = variant == null ? ThermalGear.Variant.MULTI : variant;
        ItemStack stack = new ItemStack(Material.CARVED_PUMPKIN, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(thermalKey, PersistentDataType.STRING, v.pdc);
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, v.cmd);
        applyCmd(meta, v.cmd);
        meta.displayName(colorize(v.displayName));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Uncooled LWIR thermal imager"));
        lore.add(colorize(v.multi() ? "&7Multi-mode FLIR" : "&7Fixed &f" + v.shortLabel + " &7palette"));
        lore.add(colorize("&7Wear on head for heat vision"));
        lore.add(colorize("&ePlayers / mobs / lava / engines &7glow by temp"));
        lore.add(colorize(v.hHint));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public static final int CMD_BLOOD_BAG = 4201;
    public static final int CMD_TOURNIQUET = 4202;
    public static final int CMD_SPLINT = 4203;
    public static final int CMD_EMPTY_CAN = 4204;
    public static final int CMD_DRINK_COKE = 4205;
    public static final int CMD_DRINK_PEPSI = 4206;
    public static final int CMD_DRINK_SELTZER = 4207;
    public static final int CMD_DRINK_BEER = 4208;
    public static final int CMD_DRINK_WINE = 4209;
    public static final int CMD_BROKEN_GLASS = 4210;
    public static final int CMD_EMPTY_BOTTLE = 4211;
    public static final int CMD_DRINK_LIQUOR = 4212;
    public static final int CMD_DRINK_CIDER = 4213;
    public static final int CMD_DRINK_WATER = 4214;
    public static final int CMD_DRINK_GATORADE = 4215;
    public static final int CMD_PLASTIC_BOTTLE = 4216;
    public static final int CMD_UNFILTERED_WATER_BOTTLE = 4217;
    public static final int CMD_FILTERED_WATER_BOTTLE = 4218;
    public static final int CMD_UNFILTERED_WATER_CAN = 4219;
    public static final int CMD_FILTERED_WATER_CAN = 4220;
    public static final int CMD_UNFILTERED_WATER_GLASS = 4221;
    public static final int CMD_FILTERED_WATER_GLASS = 4222;
    public static final int CMD_LIFE_STRAW = 4223;
    public static final int MEDICAL_STACK_MAX = 64;
    public static final int EMPTY_CAN_STACK_MAX = 64;

    /**
     * Plain sticks/paper/string often never send UseItem on CardForge. Mark medical
     * stacks as always-edible so right-click always reaches the server; MedicalService
     * cancels the eat and applies the heal instantly.
     */
    private static void markMedicalUsable(ItemStack stack, boolean drinkAnim) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        try {
            var food = io.papermc.paper.datacomponent.item.FoodProperties.food()
                    .nutrition(0)
                    .saturation(0f)
                    .canAlwaysEat(true)
                    .build();
            var consumable = io.papermc.paper.datacomponent.item.Consumable.consumable()
                    .consumeSeconds(0.35f)
                    .animation(drinkAnim
                            ? io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.DRINK
                            : io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.EAT)
                    .hasConsumeParticles(false)
                    .build();
            stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.FOOD, food);
            stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.CONSUMABLE, consumable);
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[WarzPlugin] markMedicalUsable failed for "
                    + stack.getType() + ": " + t);
        }
    }

    /**
     * Sets how high an item stacks.
     *
     * <p>Must be called after the meta is written: setItemMeta replaces the item's
     * components, so a stack size set before it is thrown away. That is why the
     * medical items set it twice and the magazines - which only ever set it
     * through the meta - never actually stacked.
     *
     * @param max 1 to 99, the range the component allows
     */
    public static void applyMaxStack(ItemStack stack, int max) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        int wanted = Math.max(1, Math.min(99, max));
        try {
            if (wanted == stack.getType().getMaxStackSize()) {
                // Carrying the component is not the same as not carrying it, even
                // when the number matches: components are part of an item's
                // identity, so a round stamped "max 64" will not merge with an
                // identical round that simply inherits 64 from its material. That
                // is how stacking broke - two piles of the same ammo sitting side
                // by side, and shift-click refusing to combine them.
                stack.unsetData(io.papermc.paper.datacomponent.DataComponentTypes.MAX_STACK_SIZE);
                return;
            }
            stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.MAX_STACK_SIZE, wanted);
        } catch (Throwable ignored) {
        }
    }

    /** Blood bags are potions (vanilla max 1). Force every medical stack to 64. */
    private static void applyMedicalMaxStack(ItemMeta meta) {
        try {
            meta.setMaxStackSize(MEDICAL_STACK_MAX);
        } catch (Throwable ignored) {
        }
    }

    private static void applyMedicalMaxStack(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        try {
            stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.MAX_STACK_SIZE, MEDICAL_STACK_MAX);
        } catch (Throwable ignored) {
        }
    }

    public ItemStack createMedical(String id, int amount) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return switch (id) {
            case MEDICAL_SPLINT -> createSplint(amount);
            case MEDICAL_BANDAGE -> createBandage(amount);
            case MEDICAL_TOURNIQUET -> createTourniquet(amount);
            case MEDICAL_BLOOD_BAG -> createBloodBag(amount);
            default -> null;
        };
    }

    /** Splint — heals broken bones from fall damage. */
    public ItemStack createSplint(int amount) {
        ItemStack stack = new ItemStack(Material.BONE, Math.max(1, Math.min(MEDICAL_STACK_MAX, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(medicalKey, PersistentDataType.STRING, MEDICAL_SPLINT);
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, CMD_SPLINT);
        applyCmd(meta, CMD_SPLINT, "splint");
        meta.displayName(colorize("&cSplint").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Sets broken bones from fall damage"));
        lore.add(colorize("&eRight-click &7→ heal yourself"));
        lore.add(colorize("&eRight-click player &7→ heal them"));
        meta.lore(lore);
        applyMedicalMaxStack(meta);
        stack.setItemMeta(meta);
        markMedicalUsable(stack, false);
        applyMedicalMaxStack(stack);
        return stack;
    }

    /** Bandage — stops normal bleeding. */
    public ItemStack createBandage(int amount) {
        ItemStack stack = new ItemStack(Material.PAPER, Math.max(1, Math.min(MEDICAL_STACK_MAX, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(medicalKey, PersistentDataType.STRING, MEDICAL_BANDAGE);
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, CMD_BANDAGE);
        applyCmd(meta, CMD_BANDAGE, "bandage");
        meta.displayName(colorize("&fBandage").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Stops normal bleeding"));
        lore.add(colorize("&cArterial bleeds &7need a &6Tourniquet"));
        lore.add(colorize("&eRight-click &7→ bandage yourself"));
        lore.add(colorize("&eRight-click player &7→ bandage them"));
        meta.lore(lore);
        applyMedicalMaxStack(meta);
        stack.setItemMeta(meta);
        markMedicalUsable(stack, false);
        applyMedicalMaxStack(stack);
        return stack;
    }

    /** Tourniquet — stops fast / arterial bleeding (also works on normal). */
    public ItemStack createTourniquet(int amount) {
        ItemStack stack = new ItemStack(Material.STRING, Math.max(1, Math.min(MEDICAL_STACK_MAX, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(medicalKey, PersistentDataType.STRING, MEDICAL_TOURNIQUET);
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, CMD_TOURNIQUET);
        applyCmd(meta, CMD_TOURNIQUET, "tourniquet");
        meta.displayName(colorize("&6Tourniquet").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Stops severe arterial bleeding"));
        lore.add(colorize("&7Also works on normal bleeds"));
        lore.add(colorize("&eRight-click &7→ apply to yourself"));
        lore.add(colorize("&eRight-click player &7→ apply to them"));
        meta.lore(lore);
        applyMedicalMaxStack(meta);
        stack.setItemMeta(meta);
        markMedicalUsable(stack, false);
        applyMedicalMaxStack(stack);
        return stack;
    }

    /** Blood bag — instantly restores blood volume (does not stop bleeding). */
    public ItemStack createBloodBag(int amount) {
        ItemStack stack = new ItemStack(Material.POTION, Math.max(1, Math.min(MEDICAL_STACK_MAX, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(medicalKey, PersistentDataType.STRING, MEDICAL_BLOOD_BAG);
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, CMD_BLOOD_BAG);
        applyCmd(meta, CMD_BLOOD_BAG, "blood_bag");
        if (meta instanceof PotionMeta potionMeta) {
            try {
                potionMeta.setBasePotionType(PotionType.WATER);
            } catch (Throwable ignored) {
            }
            try {
                potionMeta.setColor(BLOOD_BAG_COLOR);
            } catch (Throwable ignored) {
            }
            potionMeta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ATTRIBUTES);
        }
        meta.displayName(colorize("&4Blood Bag &fO-").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Type &fO- &7— universal donor"));
        lore.add(colorize("&7Transfusion — restores blood volume"));
        lore.add(colorize("&7Works even while still bleeding"));
        lore.add(colorize("&cDoes not &7stop bleeding"));
        lore.add(colorize("&eRight-click &7→ use on yourself"));
        lore.add(colorize("&eRight-click player &7→ transfuse them"));
        meta.lore(lore);
        applyMedicalMaxStack(meta);
        stack.setItemMeta(meta);
        markMedicalUsable(stack, true);
        applyMedicalMaxStack(stack);
        return stack;
    }

    public boolean isSplint(ItemStack stack) {
        return MEDICAL_SPLINT.equals(medicalId(stack));
    }

    public boolean isBandage(ItemStack stack) {
        return MEDICAL_BANDAGE.equals(medicalId(stack));
    }

    public boolean isTourniquet(ItemStack stack) {
        return MEDICAL_TOURNIQUET.equals(medicalId(stack));
    }

    public boolean isBloodBag(ItemStack stack) {
        return MEDICAL_BLOOD_BAG.equals(medicalId(stack));
    }

    public String medicalId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        String pdc = meta.getPersistentDataContainer().get(medicalKey, PersistentDataType.STRING);
        if (pdc != null && !pdc.isBlank()) {
            return pdc;
        }
        // Legacy ScoreboardService paper bandages
        String gear = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "gear"), PersistentDataType.STRING);
        if ("bandage".equalsIgnoreCase(gear)) {
            return MEDICAL_BANDAGE;
        }
        try {
            var cmd = meta.getCustomModelDataComponent();
            for (String s : cmd.getStrings()) {
                if (s == null) {
                    continue;
                }
                String n = s.toLowerCase(Locale.ROOT);
                if (n.contains("splint")) {
                    return MEDICAL_SPLINT;
                }
                if (n.contains("bandage")) {
                    return MEDICAL_BANDAGE;
                }
                if (n.contains("tourniquet")) {
                    return MEDICAL_TOURNIQUET;
                }
                if (n.contains("blood_bag") || n.contains("bloodbag")) {
                    return MEDICAL_BLOOD_BAG;
                }
            }
            for (Float f : cmd.getFloats()) {
                if (f == null) {
                    continue;
                }
                int v = Math.round(f);
                if (v == CMD_SPLINT) {
                    return MEDICAL_SPLINT;
                }
                if (v == CMD_BANDAGE) {
                    return MEDICAL_BANDAGE;
                }
                if (v == CMD_TOURNIQUET) {
                    return MEDICAL_TOURNIQUET;
                }
                if (v == CMD_BLOOD_BAG) {
                    return MEDICAL_BLOOD_BAG;
                }
            }
        } catch (Throwable ignored) {
        }
        if (meta.hasItemModel() && meta.getItemModel() != null) {
            String path = meta.getItemModel().getKey();
            if (path.contains("splint")) {
                return MEDICAL_SPLINT;
            }
            if (path.contains("bandage")) {
                return MEDICAL_BANDAGE;
            }
            if (path.contains("tourniquet")) {
                return MEDICAL_TOURNIQUET;
            }
            if (path.contains("blood_bag") || path.contains("bloodbag")) {
                return MEDICAL_BLOOD_BAG;
            }
        }
        return null;
    }

    public ItemStack createDrink(DrinkType type, int amount) {
        DrinkType t = type == null ? DrinkType.WATER : type;
        int amt = Math.max(1, Math.min(64, amount));
        int canCmd = drinkCanCmd(t);
        int bottleCmd = drinkBottleCmd(t);
        int cmd = canCmd > 0 ? canCmd : bottleCmd;
        ItemStack stack = new ItemStack(Material.STICK, amt);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(drinkKey, PersistentDataType.STRING, t.id);
        if (cmd > 0) {
            applyCmd(meta, cmd);
        }
        meta.displayName(colorize(t.displayName));
        List<Component> lore = new ArrayList<>();
        for (String line : t.loreLines()) {
            lore.add(colorize(line));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    /** Custom model data for canned drinks (iron_nugget); 0 = not a can. */
    public static int drinkCanCmd(DrinkType type) {
        if (type == null) {
            return 0;
        }
        return switch (type) {
            case COKE -> CMD_DRINK_COKE;
            case PEPSI -> CMD_DRINK_PEPSI;
            case SELTZER -> CMD_DRINK_SELTZER;
            case UNFILTERED_WATER_CAN -> CMD_UNFILTERED_WATER_CAN;
            case FILTERED_WATER_CAN -> CMD_FILTERED_WATER_CAN;
            default -> 0;
        };
    }

    /** Custom model data for bottled drinks (glass_bottle); 0 = not a bottle. */
    public static int drinkBottleCmd(DrinkType type) {
        if (type == null) {
            return 0;
        }
        return switch (type) {
            case BEER -> CMD_DRINK_BEER;
            case WINE -> CMD_DRINK_WINE;
            case LIQUOR -> CMD_DRINK_LIQUOR;
            case CIDER -> CMD_DRINK_CIDER;
            case WATER -> CMD_DRINK_WATER;
            case GATORADE -> CMD_DRINK_GATORADE;
            case UNFILTERED_WATER_BOTTLE -> CMD_UNFILTERED_WATER_BOTTLE;
            case FILTERED_WATER_BOTTLE -> CMD_FILTERED_WATER_BOTTLE;
            case UNFILTERED_WATER_GLASS -> CMD_UNFILTERED_WATER_GLASS;
            case FILTERED_WATER_GLASS -> CMD_FILTERED_WATER_GLASS;
            default -> 0;
        };
    }

    /** Water / Gatorade / plastic field bottles leave plastic bottles instead of glass. */
    public static boolean leavesPlasticBottle(DrinkType type) {
        return type == DrinkType.WATER || type == DrinkType.GATORADE
                || type == DrinkType.UNFILTERED_WATER_BOTTLE
                || type == DrinkType.FILTERED_WATER_BOTTLE;
    }

    /** Glass alcohol / glass field bottles leave throwable glass that can shatter. */
    public static boolean leavesGlassBottle(DrinkType type) {
        if (type == null || leavesPlasticBottle(type)) {
            return false;
        }
        return type == DrinkType.UNFILTERED_WATER_GLASS
                || type == DrinkType.FILTERED_WATER_GLASS
                || drinkBottleCmd(type) > 0;
    }

    /** Trash left after drinking — empty can, glass bottle, or plastic bottle. */
    public ItemStack createDrinkRemnant(DrinkType type) {
        if (type == null) {
            return createEmptyCan(1);
        }
        if (leavesPlasticBottle(type)) {
            return createPlasticBottle(1);
        }
        if (leavesGlassBottle(type)) {
            // Intact empty bottle — throw it to shatter into a broken bottle on the ground
            return createEmptyGlassBottle(1);
        }
        if (drinkCanCmd(type) > 0) {
            return createEmptyCan(1);
        }
        return createEmptyCan(1);
    }

    /** Empty plastic bottle from water / Gatorade — throwable trash (pop sound). */
    public ItemStack createPlasticBottle(int amount) {
        ItemStack stack = new ItemStack(Material.STICK, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(plasticBottleKey, PersistentDataType.BYTE, (byte) 1);
        applyCmd(meta, CMD_PLASTIC_BOTTLE);
        meta.displayName(colorize("&fPlastic Bottle"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Empty plastic bottle"));
        lore.add(colorize("&eRight-click water &7→ fill (unfiltered)"));
        lore.add(colorize("&eRight-click &7→ throw"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isPlasticBottle(ItemStack stack) {
        return readFlag(stack, plasticBottleKey);
    }

    public NamespacedKey plasticBottleKey() {
        return plasticBottleKey;
    }

    /** Empty glass bottle from beer/wine — throwable; breaks on impact. */
    public ItemStack createEmptyGlassBottle(int amount) {
        ItemStack stack = new ItemStack(Material.STICK, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(emptyBottleKey, PersistentDataType.BYTE, (byte) 1);
        applyCmd(meta, CMD_EMPTY_BOTTLE);
        meta.displayName(colorize("&7Glass Bottle"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Empty bottle from a drink"));
        lore.add(colorize("&eRight-click water &7→ fill (unfiltered)"));
        lore.add(colorize("&eRight-click &7→ throw"));
        lore.add(colorize("&8Breaks on impact"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isEmptyGlassBottle(ItemStack stack) {
        return readFlag(stack, emptyBottleKey);
    }

    public NamespacedKey emptyBottleKey() {
        return emptyBottleKey;
    }

    public DrinkType drinkType(ItemStack stack) {
        return DrinkType.byId(drinkId(stack));
    }

    public boolean isDrink(ItemStack stack) {
        return drinkType(stack) != null;
    }

    public String drinkId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(drinkKey, PersistentDataType.STRING);
    }

    public ItemStack createFood(WarzFoodType type, int amount) {
        WarzFoodType t = type == null ? WarzFoodType.CANNED_BEANS : type;
        int amt = Math.max(1, Math.min(EMPTY_CAN_STACK_MAX, amount));
        ItemStack stack = new ItemStack(Material.STICK, amt);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(foodKey, PersistentDataType.STRING, t.id);
        applyCmd(meta, t.cmd, t.id);
        meta.displayName(colorize(t.displayName)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : t.loreLines()) {
            lore.add(colorize(line));
        }
        meta.lore(lore);
        applyEmptyCanMaxStack(meta);
        stack.setItemMeta(meta);
        markMedicalUsable(stack, t.drinkAnimation);
        applyEmptyCanMaxStack(stack);
        return stack;
    }

    public WarzFoodType foodType(ItemStack stack) {
        return WarzFoodType.byId(foodId(stack));
    }

    public boolean isFood(ItemStack stack) {
        return foodType(stack) != null;
    }

    public String foodId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(foodKey, PersistentDataType.STRING);
    }

    public ItemStack createEmptyCan(int amount) {
        ItemStack stack = new ItemStack(Material.STICK, Math.max(1, Math.min(EMPTY_CAN_STACK_MAX, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(emptyCanKey, PersistentDataType.BYTE, (byte) 1);
        applyCmd(meta, CMD_EMPTY_CAN);
        meta.displayName(colorize("&7Empty Can")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Trash from a drink"));
        lore.add(colorize("&eRight-click water &7→ fill (unfiltered)"));
        lore.add(colorize("&eRight-click &7→ throw"));
        meta.lore(lore);
        applyEmptyCanMaxStack(meta);
        stack.setItemMeta(meta);
        applyEmptyCanMaxStack(stack);
        return stack;
    }

    private static void applyEmptyCanMaxStack(ItemMeta meta) {
        try {
            meta.setMaxStackSize(EMPTY_CAN_STACK_MAX);
        } catch (Throwable ignored) {
        }
    }

    private static void applyEmptyCanMaxStack(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        try {
            stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.MAX_STACK_SIZE, EMPTY_CAN_STACK_MAX);
        } catch (Throwable ignored) {
        }
    }

    public boolean isEmptyCan(ItemStack stack) {
        return readFlag(stack, emptyCanKey);
    }

    public NamespacedKey emptyCanKey() {
        return emptyCanKey;
    }

    public static final int LIFE_STRAW_MAX_USES = 5;

    /** Life Straw — drink from water sources safely for a limited number of uses. */
    public ItemStack createLifeStraw(int uses) {
        uses = Math.max(0, Math.min(LIFE_STRAW_MAX_USES, uses));
        ItemStack stack = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(lifeStrawKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(lifeStrawUsesKey, PersistentDataType.INTEGER, uses);
        applyCmd(meta, CMD_LIFE_STRAW);
        meta.displayName(colorize("&aLife Straw"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Drink from rivers / cauldrons safely"));
        lore.add(colorize("&7Uses: &f" + uses + "&7/" + LIFE_STRAW_MAX_USES));
        lore.add(colorize("&8When spent, water may &2Infect &8you"));
        lore.add(colorize("&eRight-click water &7→ drink"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isLifeStraw(ItemStack stack) {
        return readFlag(stack, lifeStrawKey);
    }

    public int lifeStrawUses(ItemStack stack) {
        if (!isLifeStraw(stack) || !stack.hasItemMeta()) {
            return 0;
        }
        Integer uses = stack.getItemMeta().getPersistentDataContainer()
                .get(lifeStrawUsesKey, PersistentDataType.INTEGER);
        return uses == null ? 0 : Math.max(0, uses);
    }

    public void setLifeStrawUses(ItemStack stack, int uses) {
        if (!isLifeStraw(stack) || !stack.hasItemMeta()) {
            return;
        }
        uses = Math.max(0, Math.min(LIFE_STRAW_MAX_USES, uses));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(lifeStrawUsesKey, PersistentDataType.INTEGER, uses);
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Drink from rivers / cauldrons safely"));
        lore.add(colorize("&7Uses: &f" + uses + "&7/" + LIFE_STRAW_MAX_USES));
        lore.add(colorize("&8When spent, water may &2Infect &8you"));
        lore.add(colorize("&eRight-click water &7→ drink"));
        meta.lore(lore);
        if (uses <= 0) {
            meta.displayName(colorize("&8Life Straw &7(spent)"));
        } else {
            meta.displayName(colorize("&aLife Straw"));
        }
        stack.setItemMeta(meta);
    }

    public ItemStack createBrokenGlassBottle(int amount) {
        ItemStack stack = new ItemStack(Material.STICK, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(brokenGlassKey, PersistentDataType.BYTE, (byte) 1);
        applyCmd(meta, CMD_BROKEN_GLASS);
        meta.displayName(colorize("&7Broken Glass Bottle"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Shattered bottle — sharp trash"));
        lore.add(colorize("&8From thrown bottles / molotovs"));
        lore.add(colorize("&eRight-click &7→ throw"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isBrokenGlassBottle(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return false;
        }
        Byte v = stack.getItemMeta().getPersistentDataContainer().get(brokenGlassKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    public boolean isThrowableTrash(ItemStack stack) {
        return isEmptyCan(stack) || isEmptyGlassBottle(stack)
                || isPlasticBottle(stack) || isBrokenGlassBottle(stack);
    }

    public NamespacedKey brokenGlassKey() {
        return brokenGlassKey;
    }

    public ItemStack createGrapplingHook() {
        return withGrappleUses(baseGrappleStack(), GrappleService.MAX_USES);
    }

    private ItemStack baseGrappleStack() {
        ItemStack stack = new ItemStack(Material.FISHING_ROD, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(grappleKey, PersistentDataType.BYTE, (byte) 1);
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }

    public ItemStack withGrappleUses(ItemStack stack, int uses) {
        uses = Math.max(0, Math.min(GrappleService.MAX_USES, uses));
        ItemStack copy = stack.clone();
        ItemMeta meta = copy.getItemMeta();
        meta.getPersistentDataContainer().set(grappleKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(grappleUsesKey, PersistentDataType.INTEGER, uses);
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
        meta.displayName(colorize("&2Grappling Hook &7(" + uses + "/" + GrappleService.MAX_USES + ")"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Cast at a surface to swing"));
        lore.add(colorize("&eRight-click &7→ cast / reel"));
        lore.add(colorize("&7Uses left: &e" + uses + "&7/" + GrappleService.MAX_USES));
        meta.lore(lore);
        copy.setItemMeta(meta);
        return copy;
    }

    public boolean isGrapplingHook(ItemStack stack) {
        if (stack == null || stack.getType() != Material.FISHING_ROD) {
            return false;
        }
        return readFlag(stack, grappleKey);
    }

    public int grappleUses(ItemStack stack) {
        if (!isGrapplingHook(stack)) {
            return 0;
        }
        Integer uses = stack.getItemMeta().getPersistentDataContainer().get(grappleUsesKey, PersistentDataType.INTEGER);
        return uses == null ? GrappleService.MAX_USES : Math.max(0, uses);
    }

    /** Chain-link fence — iron bars you can climb (vanilla iron bars stay non-climbable). */
    public ItemStack createChainlink(int amount) {
        ItemStack stack = new ItemStack(Material.IRON_BARS, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(trapKey, PersistentDataType.STRING, TRAP_CHAINLINK);
        meta.displayName(colorize("&7Chainlink"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Climbable fence panels."));
        lore.add(colorize("&8Regular iron bars are &cnot &8climbable."));
        lore.add(colorize("&ePlace &7to deploy"));
        meta.lore(lore);
        meta.setMaxStackSize(64);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isChainlink(ItemStack stack) {
        return TRAP_CHAINLINK.equals(trapId(stack));
    }

    /** Razor wire — placeable cobweb that damages moving players and slows zombies. */
    public ItemStack createRazorWire(int amount) {
        ItemStack stack = new ItemStack(Material.COBWEB, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(trapKey, PersistentDataType.STRING, TRAP_RAZOR_WIRE);
        meta.displayName(colorize("&8Razor Wire"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Slows and damages players."));
        lore.add(colorize("&7Slows zombies."));
        lore.add(colorize("&ePlace &7to deploy"));
        lore.add(colorize("&eShears / Wire Cutters &7→ reclaim"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isRazorWire(ItemStack stack) {
        return TRAP_RAZOR_WIRE.equals(trapId(stack));
    }

    public String trapId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(trapKey, PersistentDataType.STRING);
    }

    /** Wire Cutters — shears used to reclaim razor wire. */
    public ItemStack createWireCutters(int amount) {
        ItemStack stack = new ItemStack(Material.SHEARS, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, TOOL_WIRE_CUTTERS);
        meta.displayName(colorize("&fWire Cutters"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Cut and reclaim razor wire"));
        lore.add(colorize("&eRight-click razor wire &7→ pick up"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isWireCutters(ItemStack stack) {
        return TOOL_WIRE_CUTTERS.equals(toolId(stack));
    }

    public String toolId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
    }

    /** Empty or lava-loaded long prongs (blaze rod + CMD). */
    public ItemStack createLongProngs() {
        return createLongProngs(false);
    }

    public ItemStack createLongProngs(boolean lavaLoaded) {
        ItemStack stack = new ItemStack(Material.BLAZE_ROD, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, TOOL_LONG_PRONGS);
        meta.getPersistentDataContainer().set(prongsLavaKey, PersistentDataType.BYTE,
                (byte) (lavaLoaded ? 1 : 0));
        int cmd = lavaLoaded ? CMD_LONG_PRONGS_LAVA : CMD_LONG_PRONGS;
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, cmd);
        applyCmd(meta, cmd);
        if (lavaLoaded) {
            meta.displayName(colorize("&6Long Prongs &c(Lava)"));
            meta.lore(List.of(
                    colorize("&7A dab of lava drips from the tip"),
                    colorize("&eRight-click a &fcauldron &e→ &8Obsidian Shards"),
                    colorize("&8Don't drip it on yourself")));
        } else {
            meta.displayName(colorize("&fLong Prongs"));
            meta.lore(List.of(
                    colorize("&7Reach into lava without a bucket"),
                    colorize("&eRight-click lava &7(even flowing) → scoop"),
                    colorize("&eQuench in a cauldron &7→ &8Obsidian Shards")));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isLongProngs(ItemStack stack) {
        return TOOL_LONG_PRONGS.equals(toolId(stack));
    }

    public boolean isLongProngsLoaded(ItemStack stack) {
        if (!isLongProngs(stack) || !stack.hasItemMeta()) {
            return false;
        }
        Byte v = stack.getItemMeta().getPersistentDataContainer()
                .get(prongsLavaKey, PersistentDataType.BYTE);
        return v != null && v != 0;
    }

    /** Quenched lava residue — dark glassy chips. */
    public ItemStack createObsidianShards(int amount) {
        ItemStack stack = new ItemStack(Material.ECHO_SHARD, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, TOOL_OBSIDIAN_SHARDS);
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, CMD_OBSIDIAN_SHARDS);
        applyCmd(meta, CMD_OBSIDIAN_SHARDS);
        meta.displayName(colorize("&8Obsidian Shards"));
        meta.lore(List.of(
                colorize("&7Lava quenched on Long Prongs"),
                colorize("&7Sharp volcanic glass chips"),
                colorize("&8Crafting material")));
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isObsidianShards(ItemStack stack) {
        return TOOL_OBSIDIAN_SHARDS.equals(toolId(stack));
    }

    public ItemStack createHandcuffs(int amount) {
        // Brick base — dedicated CMD range (iron_nugget is crowded with mags/drinks).
        ItemStack stack = new ItemStack(Material.BRICK, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, TOOL_HANDCUFFS);
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, CMD_HANDCUFFS);
        applyCmd(meta, CMD_HANDCUFFS);
        meta.displayName(colorize("&fHandcuffs"));
        meta.lore(List.of(
                colorize("&7Steel arm restraint"),
                colorize("&eRight-click player &7→ cuff"),
                colorize("&7Blocks items, mining, inventory"),
                colorize("&8Unlock with Handcuff Key / Lockpick")));
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isHandcuffs(ItemStack stack) {
        return TOOL_HANDCUFFS.equals(toolId(stack));
    }

    public ItemStack createHandcuffKey(int amount) {
        ItemStack stack = new ItemStack(Material.GOLD_NUGGET, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, TOOL_HANDCUFF_KEY);
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, CMD_HANDCUFF_KEY);
        applyCmd(meta, CMD_HANDCUFF_KEY);
        meta.displayName(colorize("&eHandcuff Key"));
        meta.lore(List.of(
                colorize("&7Unlocks steel handcuffs"),
                colorize("&eRight-click cuffed player"),
                colorize("&cDoes not open zip ties")));
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isHandcuffKey(ItemStack stack) {
        return TOOL_HANDCUFF_KEY.equals(toolId(stack));
    }

    public ItemStack createLockpick(int amount) {
        ItemStack stack = new ItemStack(Material.BRICK, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, TOOL_LOCKPICK);
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, CMD_LOCKPICK);
        applyCmd(meta, CMD_LOCKPICK);
        meta.displayName(colorize("&7Lockpick"));
        meta.lore(List.of(
                colorize("&7Crack locks and restraints"),
                colorize("&eRight-click iron door / trapdoor &7→ open"),
                colorize("&eRight-click handcuffed player &7→ pick"),
                colorize("&cCan't cut zip ties")));
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isLockpick(ItemStack stack) {
        return TOOL_LOCKPICK.equals(toolId(stack));
    }

    public ItemStack createZipTies(int amount) {
        // Bone alias — not placeable. item_model bypasses bone CMD / gun / splint routing.
        ItemStack stack = new ItemStack(Material.BONE, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, TOOL_ZIP_TIES);
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, CMD_ZIP_TIES);
        meta.setItemModel(new NamespacedKey("pvpgunminus", "zip_ties"));
        meta.displayName(colorize("&8Zip Ties"));
        meta.lore(List.of(
                colorize("&7Disposable plastic restraints"),
                colorize("&eRight-click player &7→ bind"),
                colorize("&7Same limits as handcuffs"),
                colorize("&cKeys don't work — cut with Pocket Knife")));
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isZipTies(ItemStack stack) {
        return TOOL_ZIP_TIES.equals(toolId(stack));
    }

    public ItemStack createPocketKnife(int amount) {
        ItemStack stack = new ItemStack(Material.BRICK, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, TOOL_POCKET_KNIFE);
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, CMD_POCKET_KNIFE);
        applyCmd(meta, CMD_POCKET_KNIFE);
        meta.displayName(colorize("&fPocket Knife"));
        meta.lore(List.of(
                colorize("&7Folding utility blade"),
                colorize("&eRight-click zip-tied player &7→ cut free"),
                colorize("&8Does not unlock handcuffs")));
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isPocketKnife(ItemStack stack) {
        return TOOL_POCKET_KNIFE.equals(toolId(stack));
    }

    public ItemStack createScubaHelmet() {
        ItemStack stack = new ItemStack(Material.LEATHER_HELMET, 1);
        LeatherArmorMeta meta = (LeatherArmorMeta) stack.getItemMeta();
        meta.setColor(SCUBA_MASK_COLOR);
        meta.getPersistentDataContainer().set(scubaKey, PersistentDataType.STRING, SCUBA_HELMET);
        meta.displayName(colorize("&bScuba Helmet"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Dive mask — wear on head"));
        lore.add(colorize("&eWith Scuba Tank &7→ breathe underwater"));
        meta.lore(lore);
        applySuitEquippable(meta, org.bukkit.inventory.EquipmentSlot.HEAD, "scuba");
        stack.setItemMeta(meta);
        return stack;
    }

    public ItemStack createScubaTank() {
        ItemStack stack = new ItemStack(Material.LEATHER_CHESTPLATE, 1);
        LeatherArmorMeta meta = (LeatherArmorMeta) stack.getItemMeta();
        meta.setColor(SCUBA_TANK_COLOR);
        meta.getPersistentDataContainer().set(scubaKey, PersistentDataType.STRING, SCUBA_TANK);
        meta.displayName(colorize("&8Scuba Tank"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Air tank — wear on torso"));
        lore.add(colorize("&eWith Scuba Helmet &7→ breathe underwater"));
        meta.lore(lore);
        applySuitEquippable(meta, org.bukkit.inventory.EquipmentSlot.CHEST, "scuba");
        stack.setItemMeta(meta);
        return stack;
    }

    public ItemStack createWetsuitLeggings() {
        ItemStack stack = new ItemStack(Material.LEATHER_LEGGINGS, 1);
        LeatherArmorMeta meta = (LeatherArmorMeta) stack.getItemMeta();
        meta.setColor(WETSUIT_COLOR);
        meta.getPersistentDataContainer().set(scubaKey, PersistentDataType.STRING, WETSUIT_LEGS);
        meta.displayName(colorize("&3Wetsuit Leggings"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Insulated wetsuit bottoms"));
        lore.add(colorize("&7Wear with boots to hold body heat in water"));
        meta.lore(lore);
        applySuitEquippable(meta, org.bukkit.inventory.EquipmentSlot.LEGS, "scuba");
        stack.setItemMeta(meta);
        return stack;
    }

    public ItemStack createWetsuitBoots() {
        ItemStack stack = new ItemStack(Material.LEATHER_BOOTS, 1);
        LeatherArmorMeta meta = (LeatherArmorMeta) stack.getItemMeta();
        meta.setColor(WETSUIT_COLOR);
        meta.getPersistentDataContainer().set(scubaKey, PersistentDataType.STRING, WETSUIT_BOOTS);
        meta.displayName(colorize("&3Wetsuit Boots"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Insulated wetsuit footwear"));
        lore.add(colorize("&7Wear with leggings to hold body heat in water"));
        meta.lore(lore);
        applySuitEquippable(meta, org.bukkit.inventory.EquipmentSlot.FEET, "scuba");
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isScubaHelmet(ItemStack stack) {
        return SCUBA_HELMET.equals(scubaId(stack));
    }

    public boolean isScubaTank(ItemStack stack) {
        return SCUBA_TANK.equals(scubaId(stack));
    }

    public boolean isWetsuitLeggings(ItemStack stack) {
        return WETSUIT_LEGS.equals(scubaId(stack));
    }

    public boolean isWetsuitBoots(ItemStack stack) {
        return WETSUIT_BOOTS.equals(scubaId(stack));
    }

    public String scubaId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(scubaKey, PersistentDataType.STRING);
    }

    public ItemStack createHazmatHelmet() {
        return createSuitPiece(Material.LEATHER_HELMET, HAZMAT_COLOR, SUIT_HAZMAT_HELMET,
                "hazmat", org.bukkit.inventory.EquipmentSlot.HEAD, CMD_HAZMAT_HELMET,
                "&6Hazmat Helmet",
                "&7Sealed orange respirator hood",
                "&aBlocks hydrazine vapor (with suit)");
    }

    public ItemStack createHazmatChestplate() {
        return createSuitPiece(Material.LEATHER_CHESTPLATE, HAZMAT_COLOR, SUIT_HAZMAT_CHEST,
                "hazmat", org.bukkit.inventory.EquipmentSlot.CHEST, CMD_HAZMAT_CHEST,
                "&6Hazmat Suit",
                "&7Level-A orange chemical oversuit",
                "&aImmune to X-37B hydrazine leak while worn");
    }

    public ItemStack createHazmatLeggings() {
        return createSuitPiece(Material.LEATHER_LEGGINGS, HAZMAT_COLOR, SUIT_HAZMAT_LEGS,
                "hazmat", org.bukkit.inventory.EquipmentSlot.LEGS, CMD_HAZMAT_LEGS,
                "&6Hazmat Leggings",
                "&7Chemical-resistant trousers",
                "&7Part of the hazmat ensemble");
    }

    public ItemStack createHazmatBoots() {
        return createSuitPiece(Material.LEATHER_BOOTS, HAZMAT_COLOR, SUIT_HAZMAT_BOOTS,
                "hazmat", org.bukkit.inventory.EquipmentSlot.FEET, CMD_HAZMAT_BOOTS,
                "&6Hazmat Boots",
                "&7Sealed overboots",
                "&7Part of the hazmat ensemble");
    }

    public ItemStack createFireProximityHelmet() {
        return createSuitPiece(Material.LEATHER_HELMET, FIRE_SUIT_COLOR, SUIT_FIRE_HELMET,
                "fire_proximity", org.bukkit.inventory.EquipmentSlot.HEAD, CMD_FIRE_HELMET,
                "&7Fire Proximity Helmet",
                "&7Aluminized hood · &6orange faceplate",
                "&eWalk near lava — stay &f>1 &eblock clear");
    }

    public ItemStack createFireProximityChestplate() {
        return createSuitPiece(Material.LEATHER_CHESTPLATE, FIRE_SUIT_COLOR, SUIT_FIRE_CHEST,
                "fire_proximity", org.bukkit.inventory.EquipmentSlot.CHEST, CMD_FIRE_CHEST,
                "&7Fire Proximity Suit",
                "&7Silver aluminized turnout coat",
                "&aNo lava heat damage beyond &f1 &ablock");
    }

    public ItemStack createFireProximityLeggings() {
        return createSuitPiece(Material.LEATHER_LEGGINGS, FIRE_SUIT_COLOR, SUIT_FIRE_LEGS,
                "fire_proximity", org.bukkit.inventory.EquipmentSlot.LEGS, CMD_FIRE_LEGS,
                "&7Fire Proximity Leggings",
                "&7Reflective silver trousers",
                "&7Part of the fire proximity ensemble");
    }

    public ItemStack createFireProximityBoots() {
        return createSuitPiece(Material.LEATHER_BOOTS, FIRE_SUIT_COLOR, SUIT_FIRE_BOOTS,
                "fire_proximity", org.bukkit.inventory.EquipmentSlot.FEET, CMD_FIRE_BOOTS,
                "&7Fire Proximity Boots",
                "&7Insulated silver boots",
                "&7Part of the fire proximity ensemble");
    }

    /**
     * Hazmat: dyed leather inventory icon + custom worn equipment.
     * Fire proximity: custom CMD inventory icons + custom worn equipment.
     */
    private ItemStack createSuitPiece(Material mat, Color dye, String suitId,
                                     String equipmentModel, org.bukkit.inventory.EquipmentSlot slot,
                                     String name, String lore1, String lore2) {
        return createSuitPiece(mat, dye, suitId, equipmentModel, slot, 0, name, lore1, lore2);
    }

    private ItemStack createSuitPiece(Material mat, Color dye, String suitId,
                                     String equipmentModel, org.bukkit.inventory.EquipmentSlot slot,
                                     int cmd, String name, String lore1, String lore2) {
        ItemStack stack = new ItemStack(mat, 1);
        LeatherArmorMeta meta = (LeatherArmorMeta) stack.getItemMeta();
        meta.setColor(dye);
        meta.getPersistentDataContainer().set(suitKey, PersistentDataType.STRING, suitId);
        if (cmd > 0) {
            meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, cmd);
            applyCmd(meta, cmd);
        }
        applySuitEquippable(meta, slot, equipmentModel);
        meta.displayName(colorize(name));
        meta.lore(List.of(colorize(lore1), colorize(lore2)));
        stack.setItemMeta(meta);
        return stack;
    }

    private static void applySuitEquippable(ItemMeta meta, org.bukkit.inventory.EquipmentSlot slot,
                                           String equipmentModel) {
        var eq = meta.getEquippable();
        eq.setSlot(slot);
        eq.setModel(org.bukkit.NamespacedKey.fromString("pvpgunminus:" + equipmentModel));
        meta.setEquippable(eq);
    }

    public String suitId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(suitKey, PersistentDataType.STRING);
    }

    public boolean isHazmatPiece(ItemStack stack) {
        String id = suitId(stack);
        return id != null && id.startsWith("hazmat_");
    }

    public boolean isFireProximityPiece(ItemStack stack) {
        String id = suitId(stack);
        return id != null && id.startsWith("fire_proximity_");
    }

    /** Hazmat body worn — immune to parked X-37B hydrazine zones. */
    public boolean isWearingHazmatSuit(org.bukkit.entity.Player player) {
        if (player == null) {
            return false;
        }
        return SUIT_HAZMAT_CHEST.equals(suitId(player.getInventory().getChestplate()));
    }

    /** Fire proximity coat worn — lava heat safe beyond 1 block. */
    public boolean isWearingFireProximitySuit(org.bukkit.entity.Player player) {
        if (player == null) {
            return false;
        }
        return SUIT_FIRE_CHEST.equals(suitId(player.getInventory().getChestplate()));
    }

    /** Handheld tactical flashlight — client look-cone, not vanilla LIGHT blocks. */
    public ItemStack createFlashlight() {
        // Stick: no block-place fight on right-click (same idea as the old blaze rod).
        ItemStack stack = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(flashlightKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(flashlightOnKey, PersistentDataType.BYTE, (byte) 0);
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, CMD_FLASHLIGHT_HANDHELD);
        applyCmd(meta, CMD_FLASHLIGHT_HANDHELD, "flashlight_handheld");
        meta.displayName(colorize("&eTactical Flashlight &7[OFF]"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&7Look-direction spotlight"));
        lore.add(colorize("&eRight-click &7→ toggle ON / OFF"));
        lore.add(colorize("&7Hold in main hand or offhand while ON"));
        lore.add(colorize("&8Lights whatever you aim at"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isFlashlight(ItemStack stack) {
        return readFlag(stack, flashlightKey);
    }

    public boolean isWarzMap(ItemStack stack) {
        if (stack == null || stack.getType() != Material.FILLED_MAP) {
            return false;
        }
        if (readFlag(stack, warzMapKey)) {
            return true;
        }
        if (!stack.hasItemMeta()) {
            return false;
        }
        var name = stack.getItemMeta().displayName();
        if (name == null) {
            return false;
        }
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(name);
        return plain.toLowerCase(Locale.ROOT).contains("warz map");
    }

    public void markWarzMap(ItemStack stack) {
        if (stack == null || stack.getType() != Material.FILLED_MAP || !stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(warzMapKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        applyMaxStack(stack, 64);
    }

    /** Empty mags and WarZ maps — shift-click should merge these everywhere. */
    public boolean isShiftMergeCandidate(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (isWarzMap(stack)) {
            return true;
        }
        return isMagazine(stack) && magazineCount(stack) <= 0;
    }

    public boolean isFlashlightOn(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return false;
        }
        if (!isFlashlight(stack) && !hasFlashlightMod(stack)) {
            return false;
        }
        Byte v = stack.getItemMeta().getPersistentDataContainer().get(flashlightOnKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    public void setFlashlightOn(ItemStack stack, boolean on) {
        if (stack == null || !stack.hasItemMeta()) {
            return;
        }
        if (!isFlashlight(stack) && !hasFlashlightMod(stack)) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(flashlightOnKey, PersistentDataType.BYTE, on ? (byte) 1 : (byte) 0);
        if (isFlashlight(stack)) {
            meta.displayName(colorize(on ? "&eTactical Flashlight &a[ON]" : "&eTactical Flashlight &7[OFF]"));
            stack.setItemMeta(meta);
        } else {
            stack.setItemMeta(meta);
            refreshAttachmentLore(stack);
        }
    }

    /** Deployable UAV (default MQ-9 cargo). */
    public ItemStack createBigDrone() {
        return createBigDrone(BigDroneType.MQ9);
    }

    public ItemStack createBigDrone(BigDroneType type) {
        BigDroneType t = type != null ? type : BigDroneType.MQ9;
        return createBigDrone(t, t.defaultRockets(), t.defaultFuelCans(),
                t.structureMax(), DronePadService.FLARE_MAX);
    }

    public ItemStack createBigDrone(List<String> rockets, int fuelCans) {
        return createBigDrone(BigDroneType.MQ9, rockets, fuelCans,
                BigDroneType.MQ9.structureMax(), DronePadService.FLARE_MAX);
    }

    public ItemStack createBigDrone(List<String> rockets, int fuelCans, int structureHp) {
        return createBigDrone(BigDroneType.MQ9, rockets, fuelCans, structureHp, DronePadService.FLARE_MAX);
    }

    public ItemStack createBigDrone(List<String> rockets, int fuelCans, int structureHp, int flareCharges) {
        return createBigDrone(BigDroneType.MQ9, rockets, fuelCans, structureHp, flareCharges);
    }

    public ItemStack createBigDrone(BigDroneType type, List<String> rockets, int fuelCans,
                                    int structureHp, int flareCharges) {
        BigDroneType t = type != null ? type : BigDroneType.MQ9;
        ItemStack stack = new ItemStack(Material.RECOVERY_COMPASS, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(bigDroneItemKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(droneTypeKey, PersistentDataType.STRING, t.id());
        List<String> capped = capRockets(rockets, t.missileSlots());
        String rocketCsv = capped.isEmpty() ? "" : String.join(",", capped);
        meta.getPersistentDataContainer().set(droneCargoRocketsKey, PersistentDataType.STRING, rocketCsv);
        int cans = Math.max(0, Math.min(t.maxFuelCans(), fuelCans));
        meta.getPersistentDataContainer().set(droneCargoFuelKey, PersistentDataType.INTEGER, cans);
        int hp = Math.max(0, Math.min(t.structureMax(), structureHp));
        meta.getPersistentDataContainer().set(droneCargoStructureHpKey, PersistentDataType.INTEGER, hp);
        int flares = Math.max(0, Math.min(DronePadService.FLARE_MAX, flareCharges));
        meta.getPersistentDataContainer().set(droneCargoFlaresKey, PersistentDataType.INTEGER, flares);
        meta.displayName(colorize("&b" + t.displayName()));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&8" + t.loreId()));
        lore.add(colorize("&7Place — nose faces you · RMB payload bay"));
        lore.add(colorize("&7Take airframe from bay to pack up"));
        lore.add(colorize("&7Radiolink → seat to fly"));
        lore.add(colorize("&8Tank: &f" + t.fuelGal() + " &8gal"
                + (t.cargoBay() ? " · &aCargo bay" : " · Missiles: &f" + t.missileSlots())));
        if (t.cargoBay()) {
            lore.add(colorize("&8Unarmed · LAW drops cargo to look point"));
            lore.add(colorize("&cHydrazine leak &7after landing (10 blk / 10 min)"));
        }
        if (t.stealth()) {
            lore.add(colorize("&8Stealth: &aJavelin LOAL denied &7· LAW still kills"));
        }
        lore.add(colorize("&8Hull: &f" + t.structureMax() + " HP &8· bullet armor &f"
                + String.format(java.util.Locale.ROOT, "%.0f%%", (2f - t.bulletDamageTaken()) * 50f)));
        if (t.waterVision()) {
            lore.add(colorize("&8Sensors: &bWater-penetrating NV/IR"));
        }
        if (t.wideArea()) {
            lore.add(colorize("&8Sensors: &bWide-area surveillance"));
        }
        String optics = t.hasThermal() ? (t.hasNvg() ? "NV + Thermal" : "Thermal")
                : (t.hasNvg() ? "NV only" : "EO only");
        lore.add(colorize("&8Optics: &f" + optics));
        int rCount = capped.size();
        int metal = DronePadService.metalFromStructureHp(hp);
        if (t.cargoBay()) {
            lore.add(colorize("&8Loadout: &fcargo bay · &f" + cans + " &8Jet Fuel"));
        } else {
            lore.add(colorize("&8Cargo: &f" + rCount + " &8rockets · &f" + cans + " &8Jet Fuel"));
        }
        lore.add(colorize("&8Flares: &f" + flares + "&8/&f" + DronePadService.FLARE_MAX));
        lore.add(colorize("&8Metal: &f" + metal + "&8/&f" + DronePadService.METAL_MAX));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private static List<String> capRockets(List<String> rockets, int maxSlots) {
        if (rockets == null || rockets.isEmpty() || maxSlots <= 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String r : rockets) {
            if (out.size() >= maxSlots) {
                break;
            }
            if (r != null && !r.isBlank()) {
                out.add(r.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    public boolean isBigDroneItem(ItemStack stack) {
        return readFlag(stack, bigDroneItemKey);
    }

    public BigDroneType droneType(ItemStack stack) {
        if (!isBigDroneItem(stack)) {
            return BigDroneType.MQ9;
        }
        String id = stack.getItemMeta().getPersistentDataContainer()
                .get(droneTypeKey, PersistentDataType.STRING);
        return BigDroneType.fromId(id);
    }

    public NamespacedKey droneTypeKey() {
        return droneTypeKey;
    }

    public List<String> droneCargoRockets(ItemStack stack) {
        BigDroneType t = droneType(stack);
        if (!isBigDroneItem(stack)) {
            return t.defaultRockets();
        }
        String raw = stack.getItemMeta().getPersistentDataContainer()
                .get(droneCargoRocketsKey, PersistentDataType.STRING);
        if (raw == null) {
            return t.defaultRockets();
        }
        if (raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (part != null && !part.isBlank()) {
                out.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        return capRockets(out, t.missileSlots());
    }

    public int droneCargoFuelCans(ItemStack stack) {
        BigDroneType t = droneType(stack);
        if (!isBigDroneItem(stack)) {
            return t.defaultFuelCans();
        }
        Integer v = stack.getItemMeta().getPersistentDataContainer()
                .get(droneCargoFuelKey, PersistentDataType.INTEGER);
        return v == null ? t.defaultFuelCans() : Math.max(0, Math.min(t.maxFuelCans(), v));
    }

    public int droneCargoStructureHp(ItemStack stack) {
        BigDroneType t = droneType(stack);
        if (!isBigDroneItem(stack)) {
            return t.structureMax();
        }
        Integer v = stack.getItemMeta().getPersistentDataContainer()
                .get(droneCargoStructureHpKey, PersistentDataType.INTEGER);
        if (v == null) {
            return t.structureMax();
        }
        return Math.max(0, Math.min(t.structureMax(), v));
    }

    public int droneCargoFlares(ItemStack stack) {
        if (!isBigDroneItem(stack)) {
            return DronePadService.FLARE_MAX;
        }
        Integer v = stack.getItemMeta().getPersistentDataContainer()
                .get(droneCargoFlaresKey, PersistentDataType.INTEGER);
        if (v == null) {
            return DronePadService.FLARE_MAX;
        }
        return Math.max(0, Math.min(DronePadService.FLARE_MAX, v));
    }

    /** Persist general cargo bay contents onto a drone item (X-37B). */
    public void writeDroneBayCargo(ItemStack stack, List<ItemStack> cargo) {
        if (!isBigDroneItem(stack) || !stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (cargo == null || cargo.isEmpty()) {
            meta.getPersistentDataContainer().remove(droneCargoBayKey);
            stack.setItemMeta(meta);
            return;
        }
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (org.bukkit.util.io.BukkitObjectOutputStream oos =
                         new org.bukkit.util.io.BukkitObjectOutputStream(baos)) {
                List<ItemStack> clean = new ArrayList<>();
                for (ItemStack it : cargo) {
                    if (it != null && !it.getType().isAir()) {
                        clean.add(it.clone());
                    }
                }
                oos.writeInt(Math.min(DronePadService.CARGO_SLOTS, clean.size()));
                int n = 0;
                for (ItemStack it : clean) {
                    if (n >= DronePadService.CARGO_SLOTS) {
                        break;
                    }
                    oos.writeObject(it);
                    n++;
                }
            }
            meta.getPersistentDataContainer().set(droneCargoBayKey, PersistentDataType.BYTE_ARRAY, baos.toByteArray());
            stack.setItemMeta(meta);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to write drone cargo bay: " + e.getMessage());
        }
    }

    public List<ItemStack> droneBayCargo(ItemStack stack) {
        if (!isBigDroneItem(stack) || !stack.hasItemMeta()) {
            return List.of();
        }
        byte[] raw = stack.getItemMeta().getPersistentDataContainer()
                .get(droneCargoBayKey, PersistentDataType.BYTE_ARRAY);
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        try (org.bukkit.util.io.BukkitObjectInputStream ois =
                     new org.bukkit.util.io.BukkitObjectInputStream(new java.io.ByteArrayInputStream(raw))) {
            int n = Math.max(0, Math.min(DronePadService.CARGO_SLOTS, ois.readInt()));
            List<ItemStack> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Object o = ois.readObject();
                if (o instanceof ItemStack it && !it.getType().isAir()) {
                    out.add(it);
                }
            }
            return out;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to read drone cargo bay: " + e.getMessage());
            return List.of();
        }
    }

    /** UAV / Flare Gun ammunition. */
    public ItemStack createFlareCartridge(int amount) {
        int n = Math.max(1, Math.min(64, amount));
        Optional<RoundDefinition> def = plugin.rounds().get("flare_cartridge");
        if (def.isPresent()) {
            return createRound(def.get(), n);
        }
        ItemStack stack = new ItemStack(Material.FIREWORK_STAR, n);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(roundKey, PersistentDataType.STRING, "flare_cartridge");
        applyCmd(meta, CMD_FLARE_CARTRIDGE);
        meta.displayName(colorize("&6Flare Cartridge"));
        meta.lore(List.of(
                colorize("&8flare_cartridge"),
                colorize("&7UAV countermeasure / Flare Gun"),
                colorize("&7Load in the drone payload bay")));
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isFlareCartridge(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return false;
        }
        Optional<RoundDefinition> round = roundOf(stack);
        if (round.isPresent()) {
            String id = round.get().fileName() == null ? "" : round.get().fileName().toLowerCase(Locale.ROOT);
            return id.equals("flare_cartridge")
                    || AmmoCaliber.normalize(round.get().caliber()).equals("flare");
        }
        return false;
    }

    /**
     * Hull Metal — iron ingot. Integrity cap is {@link DronePadService#METAL_MAX} (100);
     * Paper only allows item max-stack 1–99, so physical stacks clamp to {@link #METAL_STACK_MAX}.
     */
    public static final int METAL_STACK_MAX = 99;

    public ItemStack createMetal(int amount) {
        int n = Math.max(1, Math.min(METAL_STACK_MAX, amount));
        ItemStack stack = new ItemStack(Material.IRON_INGOT, n);
        ItemMeta meta = stack.getItemMeta();
        meta.setMaxStackSize(METAL_STACK_MAX);
        meta.getPersistentDataContainer().set(metalKey, PersistentDataType.BYTE, (byte) 1);
        applyCmd(meta, CMD_METAL, "metal");
        meta.displayName(colorize("&fMetal"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&8metal"));
        lore.add(colorize("&7UAV airframe plate"));
        lore.add(colorize("&7Add in the payload bay to repair"));
        lore.add(colorize("&8Bay integrity max &f" + DronePadService.METAL_MAX));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isMetal(ItemStack stack) {
        return readFlag(stack, metalKey);
    }

    /** Tagged Metal or plain iron ingot — both repair the bay Metal slot. Magazines never count. */
    public boolean isMetalDeposit(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || isMagazine(stack)) {
            return false;
        }
        return isMetal(stack) || stack.getType() == Material.IRON_INGOT;
    }

    public ItemStack createJetFuelCan(int amount) {
        ItemStack stack = new ItemStack(Material.BRICK, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(jetFuelKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, CMD_JET_FUEL_CAN);
        applyCmd(meta, CMD_JET_FUEL_CAN);
        meta.displayName(colorize("&cJet Fuel Can"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&8jet_fuel_can"));
        lore.add(colorize("&7Red aviation fuel — MQ-9 / RQ-4 / X-47B…"));
        lore.add(colorize("&eAdd at the bay fuel gauge"));
        lore.add(colorize("&cNot for X-37B (needs Hydrazine)"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isJetFuelCan(ItemStack stack) {
        return readFlag(stack, jetFuelKey);
    }

    /** White hydrazine canisters — X-37B spaceplane only. */
    public ItemStack createHydrazineFuelCan(int amount) {
        ItemStack stack = new ItemStack(Material.BRICK, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(hydrazineFuelKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, CMD_HYDRAZINE_FUEL_CAN);
        applyCmd(meta, CMD_HYDRAZINE_FUEL_CAN);
        meta.displayName(colorize("&fHydrazine Fuel Can"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&8hydrazine_fuel_can"));
        lore.add(colorize("&7White N₂H₄ — &fX-37B &7OMS / RCS only"));
        lore.add(colorize("&eAdd at the X-37B bay fuel gauge"));
        lore.add(colorize("&cToxic — hazmat recommended near leaks"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isHydrazineFuelCan(ItemStack stack) {
        return readFlag(stack, hydrazineFuelKey);
    }

    /** True if this can refuels the given airframe (jet vs hydrazine). */
    public boolean isFuelCanFor(ItemStack stack, BigDroneType type) {
        if (type == null) {
            return isJetFuelCan(stack);
        }
        if (type == BigDroneType.X37B) {
            return isHydrazineFuelCan(stack);
        }
        return isJetFuelCan(stack);
    }

    public boolean isAnyFuelCan(ItemStack stack) {
        return isJetFuelCan(stack) || isHydrazineFuelCan(stack);
    }

    /** Links a placed UAV to a drone seat (RMB drone, then RMB seat). */
    public ItemStack createRadiolink() {
        ItemStack stack = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(radiolinkKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER, CMD_RADIOLINK);
        applyCmd(meta, CMD_RADIOLINK, "radiolink");
        meta.displayName(colorize("&dRadiolink"));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize("&8radiolink"));
        lore.add(colorize("&7RMB drone → seat to link"));
        lore.add(colorize("&7RMB air = manage your links"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isRadiolink(ItemStack stack) {
        return readFlag(stack, radiolinkKey);
    }

    /** Custom model data range for operator UAV hotbar icons (companion item models). */
    /** Flare cartridge. The art has been in the pack all along, unreferenced. */
    public static final int CMD_FLARE_CARTRIDGE = 2601;
    /** Scrap metal. Had no model at all - it showed as a plain iron ingot. */
    public static final int CMD_METAL = 4350;
    public static final int CMD_DRONE_FIRE = 4101;
    public static final int CMD_DRONE_ORBIT = 4102;
    public static final int CMD_DRONE_CONTROL = 4103;
    public static final int CMD_DRONE_OPTIC = 4104;
    public static final int CMD_DRONE_SPEED = 4105;
    public static final int CMD_DRONE_IR = 4106;
    public static final int CMD_DRONE_FLARES = 4107;
    public static final int CMD_DRONE_EXIT = 4108;

    public ItemStack createDroneControl(String id, Material material, String name, String... loreLines) {
        return createDroneControl(id, material, 0, name, loreLines);
    }

    public ItemStack createDroneControl(String id, Material material, int cmd, String name, String... loreLines) {
        ItemStack stack = new ItemStack(material, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(bigDroneCtrlKey, PersistentDataType.STRING, id);
        meta.displayName(colorize(name));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(colorize(line));
        }
        lore.add(colorize("&8UAV control"));
        meta.lore(lore);
        meta.setUnbreakable(true);
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_UNBREAKABLE,
                ItemFlag.HIDE_ADDITIONAL_TOOLTIP,
                ItemFlag.HIDE_ENCHANTS,
                ItemFlag.HIDE_DESTROYS,
                ItemFlag.HIDE_PLACED_ON);
        if (cmd > 0) {
            applyCmd(meta, cmd);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /** Shared base for UAV hotbar icons (companion CMD 4101–4108 on paper). */
    private static final Material DRONE_CTRL_BASE = Material.PAPER;

    public ItemStack createDroneFireControl() {
        return createDroneControl("fire", DRONE_CTRL_BASE, CMD_DRONE_FIRE,
                "&cLAW Fire",
                "&eRMB &7fire · &eLMB &7cycle bay",
                "&7Scroll = zoom · next round on OSD",
                "&8Drone-tuned accuracy + blast");
    }

    public ItemStack createDroneOrbitControl() {
        return createDroneControl("orbit", DRONE_CTRL_BASE, CMD_DRONE_ORBIT,
                "&eOrbit",
                "&7RMB start · RMB again LOCK free-look · LMB stop",
                "&7Scroll = orbit width only (no zoom)",
                "&88–96m");
    }

    public ItemStack createDroneModeControl() {
        return createDroneControl("control", DRONE_CTRL_BASE, CMD_DRONE_CONTROL,
                "&dControl",
                "&7MANUAL fixed-wing ↔ AUTOPILOT",
                "&7W/S throttle · A/D bank · look pitch",
                "&7Autopilot after climb-out only");
    }

    public ItemStack createDroneOpticControl() {
        return createDroneControl("optic", DRONE_CTRL_BASE, CMD_DRONE_OPTIC,
                "&aOptics",
                "&7Click: Normal → NVG → Thermal",
                "&7Scroll to zoom",
                "&7Then H cycles palettes");
    }

    public ItemStack createDroneSpeedControl() {
        return createDroneControl("speed", DRONE_CTRL_BASE, CMD_DRONE_SPEED,
                "&eDrone Speed",
                "&7Scroll or RMB+/LMB−",
                "&7Manual / auto / orbit rate");
    }

    public ItemStack createDroneIrControl() {
        return createDroneControl("ir", DRONE_CTRL_BASE, CMD_DRONE_IR,
                "&aIR Laser",
                "&7Toggle IR designator",
                "&7Visible on EO / NV / Thermal",
                "&7Ground NODs / FLIR can see it too");
    }

    public ItemStack createDroneFlareControl(int charges, int maxCharges, boolean active) {
        return createDroneControl("flares", DRONE_CTRL_BASE, CMD_DRONE_FLARES,
                "&6Flares &7[" + Math.max(0, charges) + "/" + Math.max(0, maxCharges) + "]"
                        + (active ? " &a● ACTIVE" : ""),
                "&7Deploy IR decoy flares",
                "&7Active flares always decoy one Javelin",
                charges > 0 ? "&eClick to deploy" : "&cEmpty");
    }

    public ItemStack createDroneExitControl() {
        return createDroneControl("exit", DRONE_CTRL_BASE, CMD_DRONE_EXIT,
                "&cExit Drone",
                "&7Land anywhere to park",
                "&7Airborne = abandon on AUTOPILOT");
    }

    public Optional<String> droneControlId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        String id = stack.getItemMeta().getPersistentDataContainer().get(bigDroneCtrlKey, PersistentDataType.STRING);
        return Optional.ofNullable(id);
    }

    public boolean isDroneControl(ItemStack stack) {
        return droneControlId(stack).isPresent();
    }

    private static void applyCmd(ItemMeta meta, int cmd) {
        applyCmd(meta, cmd, null);
    }

    /**
     * Apply numeric CMD (range_dispatch) and optional string id (select).
     * Strings avoid threshold collisions when many models share one base item.
     */
    private static void applyCmd(ItemMeta meta, int cmd, String stringId) {
        var cmdComponent = meta.getCustomModelDataComponent();
        cmdComponent.setFloats(List.of((float) cmd));
        if (stringId != null && !stringId.isBlank()) {
            cmdComponent.setStrings(List.of(stringId));
        }
        // Component last — deprecated setCustomModelData(int) can wipe strings if applied after.
        meta.setCustomModelDataComponent(cmdComponent);
    }

    public Optional<String> gunId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        String id = stack.getItemMeta().getPersistentDataContainer().get(gunKey, PersistentDataType.STRING);
        return Optional.ofNullable(id);
    }

    public Optional<String> roundId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        String id = stack.getItemMeta().getPersistentDataContainer().get(roundKey, PersistentDataType.STRING);
        return Optional.ofNullable(id);
    }

    public Optional<RoundDefinition> roundOf(ItemStack stack) {
        // Magazines share materials with ammo (e.g. .50 clay ball) — never treat a mag as rounds.
        if (isMagazine(stack)) {
            return Optional.empty();
        }
        return roundId(stack).flatMap(id -> plugin.rounds().get(id));
    }

    public boolean isAllowedRound(ItemStack stack, GunDefinition gun) {
        if (gun == null || gun.consumable() || gun.throwable()) {
            return false;
        }
        Optional<RoundDefinition> round = roundOf(stack);
        if (round.isEmpty()) {
            return false;
        }
        RoundDefinition def = round.get();
        if (!gun.allowsRound(def.fileName())) {
            return false;
        }
        // Also require matching caliber when gun lists empty allowed (all of caliber).
        return AmmoCaliber.normalize(def.caliber()).equals(AmmoCaliber.normalize(gun.ammoCaliber()))
                || gun.allowedRounds().contains(def.fileName().toLowerCase(Locale.ROOT));
    }

    public void applyDisplay(ItemStack stack, String display) {
        if (stack == null || !stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(colorize(display).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        stack.setItemMeta(meta);
    }

    // ---- Magazines / chamber -------------------------------------------------

    public ItemStack createMagazine(MagazineType type, int amount) {
        return createMagazine(type, 0, null, amount);
    }

    public ItemStack createMagazine(MagazineType type, int count, String roundId, int amount) {
        if (type == null) {
            return new ItemStack(Material.AIR);
        }
        // Loaded mags never stack (amount always 1). Empty mags may stack.
        int rounds = Math.max(0, Math.min(type.capacity(), count));
        int stackAmt = rounds > 0 ? 1 : Math.max(1, Math.min(64, amount));
        if (rounds > 0) {
            rounds = Math.min(type.capacity(), rounds);
        } else {
            rounds = 0;
        }
        ItemStack stack = new ItemStack(Material.STICK, stackAmt);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(magKey, PersistentDataType.STRING, type.id());
        try {
            meta.setMaxStackSize(rounds > 0 ? 1 : 64);
        } catch (Throwable ignored) {
        }
        applyCmd(meta, type.customModelData(rounds > 0));
        stack.setItemMeta(meta);
        if (rounds > 0 && roundId != null && !roundId.isBlank()) {
            List<String> load = new ArrayList<>(rounds);
            String rid = roundId.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < rounds; i++) {
                load.add(rid);
            }
            writeMagazineLoad(stack, load);
        } else {
            writeMagazineLoad(stack, List.of());
        }
        // Last, because writing the load rewrites the meta and takes the component
        // with it. An empty magazine is interchangeable with any other, so they
        // stack; a loaded one holds its own rounds and cannot.
        applyMaxStack(stack, rounds > 0 ? 1 : 64);
        return stack;
    }

    /**
     * Enforce: empty stacks OK; loaded = amount 1.
     * If a loaded stack somehow has amount&gt;1, peel without duplicating rounds.
     *
     * @return extras the caller must give back to the player
     */
    public List<ItemStack> applyMagazineStackRules(ItemStack stack) {
        List<ItemStack> extras = new ArrayList<>();
        if (stack == null || !isMagazine(stack) || !stack.hasItemMeta()) {
            return extras;
        }
        MagazineType type = magazineType(stack);
        if (type == null) {
            return extras;
        }
        List<String> load = magazineLoadList(stack);
        if (load.isEmpty()) {
            writeMagazineLoad(stack, List.of());
            return extras;
        }
        int amount = Math.max(1, stack.getAmount());
        int cap = type.capacity();
        if (amount == 1) {
            if (load.size() <= cap) {
                writeMagazineLoad(stack, load);
                return extras;
            }
            // Keep top (next-to-fire) rounds on this mag; overflow bottom into extras.
            List<String> keep = new ArrayList<>(load.subList(load.size() - cap, load.size()));
            List<String> overflow = new ArrayList<>(load.subList(0, load.size() - cap));
            writeMagazineLoad(stack, keep);
            while (!overflow.isEmpty()) {
                int put = Math.min(cap, overflow.size());
                List<String> chunk = new ArrayList<>(overflow.subList(overflow.size() - put, overflow.size()));
                overflow.subList(overflow.size() - put, overflow.size()).clear();
                extras.add(createMagazineWithLoad(type, chunk));
            }
            return extras;
        }
        // Illegal loaded stack: amount>1 — distribute load into singles + leftover empties.
        List<String> remaining = new ArrayList<>(load);
        int remainingSlots = amount;
        stack.setAmount(1);
        remainingSlots--;
        int first = Math.min(cap, remaining.size());
        List<String> firstLoad = new ArrayList<>(remaining.subList(remaining.size() - first, remaining.size()));
        remaining.subList(remaining.size() - first, remaining.size()).clear();
        writeMagazineLoad(stack, firstLoad);
        while (!remaining.isEmpty() && remainingSlots > 0) {
            int put = Math.min(cap, remaining.size());
            List<String> chunk = new ArrayList<>(remaining.subList(remaining.size() - put, remaining.size()));
            remaining.subList(remaining.size() - put, remaining.size()).clear();
            remainingSlots--;
            extras.add(createMagazineWithLoad(type, chunk));
        }
        while (!remaining.isEmpty()) {
            int put = Math.min(cap, remaining.size());
            List<String> chunk = new ArrayList<>(remaining.subList(remaining.size() - put, remaining.size()));
            remaining.subList(remaining.size() - put, remaining.size()).clear();
            extras.add(createMagazineWithLoad(type, chunk));
        }
        if (remainingSlots > 0) {
            extras.add(createMagazine(type, 0, null, remainingSlots));
        }
        return extras;
    }

    private ItemStack createMagazineWithLoad(MagazineType type, List<String> load) {
        ItemStack mag = createMagazine(type, 0, null, 1);
        writeMagazineLoad(mag, load == null ? List.of() : load);
        return mag;
    }

    public boolean isMagazine(ItemStack stack) {
        return magazineType(stack) != null;
    }

    public MagazineType magazineType(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        String id = stack.getItemMeta().getPersistentDataContainer().get(magKey, PersistentDataType.STRING);
        return MagazineType.fromId(id);
    }

    /** Rounds in this mag item (loaded mags are always a single item). */
    public int magazineCount(ItemStack stack) {
        return magazineLoadList(stack).size();
    }

    public int magazineTotalCapacity(ItemStack stack) {
        MagazineType type = magazineType(stack);
        if (type == null || stack == null) {
            return 0;
        }
        // Only empty stacks use amount×capacity for auto-fill preview; loaded is per-mag.
        if (magazineCount(stack) > 0) {
            return type.capacity();
        }
        return type.capacity() * Math.max(1, stack.getAmount());
    }

    /** Next round to fire (top of mag), or null if empty. */
    public String magazineRoundId(ItemStack stack) {
        List<String> load = magazineLoadList(stack);
        if (load.isEmpty()) {
            return null;
        }
        return load.get(load.size() - 1);
    }

    public String magazinePeekNext(ItemStack stack) {
        return magazineRoundId(stack);
    }

    /** Pop next-to-fire round from the mag. */
    public String magazinePopNext(ItemStack stack) {
        List<String> load = magazineLoadList(stack);
        if (load.isEmpty()) {
            return null;
        }
        String id = load.remove(load.size() - 1);
        writeMagazineLoad(stack, load);
        return id;
    }

    /** Remove up to {@code amount} of {@code roundId} (prefer from the top). */
    public int magazineTakeRounds(ItemStack stack, String roundId, int amount) {
        if (roundId == null || amount <= 0) {
            return 0;
        }
        String want = roundId.trim().toLowerCase(Locale.ROOT);
        List<String> load = magazineLoadList(stack);
        int taken = 0;
        for (int i = load.size() - 1; i >= 0 && taken < amount; i--) {
            if (want.equals(load.get(i))) {
                load.remove(i);
                taken++;
            }
        }
        if (taken > 0) {
            writeMagazineLoad(stack, load);
        }
        return taken;
    }

    /** Count of a specific round id currently in the mag. */
    public int magazineCountOf(ItemStack stack, String roundId) {
        if (roundId == null) {
            return 0;
        }
        String want = roundId.trim().toLowerCase(Locale.ROOT);
        int n = 0;
        for (String id : magazineLoadList(stack)) {
            if (want.equals(id)) {
                n++;
            }
        }
        return n;
    }

    /** Ordered counts for display (insertion order = first seen in load from bottom). */
    public Map<String, Integer> magazineLoadCounts(ItemStack stack) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String id : magazineLoadList(stack)) {
            counts.merge(id, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Bottom→top load list. Last entry fires next.
     * Migrates legacy mag_count + mag_round when mag_load is missing.
     */
    public List<String> magazineLoadList(ItemStack stack) {
        List<String> out = new ArrayList<>();
        if (stack == null || !stack.hasItemMeta()) {
            return out;
        }
        ItemMeta meta = stack.getItemMeta();
        String raw = meta.getPersistentDataContainer().get(magLoadKey, PersistentDataType.STRING);
        if (raw != null) {
            if (!raw.isBlank()) {
                for (String part : raw.split(",")) {
                    if (part == null || part.isBlank()) {
                        continue;
                    }
                    out.add(part.trim().toLowerCase(Locale.ROOT));
                }
            }
            return out;
        }
        // Legacy: single type × count
        Integer c = meta.getPersistentDataContainer().get(magCountKey, PersistentDataType.INTEGER);
        String rid = meta.getPersistentDataContainer().get(magRoundKey, PersistentDataType.STRING);
        int count = c == null ? 0 : Math.max(0, c);
        if (count > 0 && rid != null && !rid.isBlank()) {
            String id = rid.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < count; i++) {
                out.add(id);
            }
        }
        return out;
    }

    public void writeMagazineLoad(ItemStack stack, List<String> load) {
        MagazineType type = magazineType(stack);
        if (type == null || !stack.hasItemMeta()) {
            return;
        }
        List<String> clean = new ArrayList<>();
        if (load != null) {
            for (String id : load) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                clean.add(id.trim().toLowerCase(Locale.ROOT));
                if (clean.size() >= type.capacity()) {
                    break;
                }
            }
        }
        if (!clean.isEmpty() && stack.getAmount() > 1) {
            stack.setAmount(1);
        }
        ItemMeta meta = stack.getItemMeta();
        if (clean.isEmpty()) {
            meta.getPersistentDataContainer().set(magCountKey, PersistentDataType.INTEGER, 0);
            meta.getPersistentDataContainer().remove(magRoundKey);
            meta.getPersistentDataContainer().remove(magLoadKey);
        } else {
            meta.getPersistentDataContainer().set(magCountKey, PersistentDataType.INTEGER, clean.size());
            meta.getPersistentDataContainer().set(magRoundKey, PersistentDataType.STRING,
                    clean.get(clean.size() - 1));
            meta.getPersistentDataContainer().set(magLoadKey, PersistentDataType.STRING,
                    String.join(",", clean));
        }
        // Swap empty ↔ loaded 3D model
        applyCmd(meta, type.customModelData(!clean.isEmpty()));
        meta.getPersistentDataContainer().set(modelKey, PersistentDataType.INTEGER,
                type.customModelData(!clean.isEmpty()));
        try {
            meta.setMaxStackSize(clean.isEmpty() ? 64 : 1);
        } catch (Throwable ignored) {
        }
        stack.setItemMeta(meta);
        refreshMagazineLore(stack);
        // Every path that loads or empties a magazine ends here, so the stack size
        // is settled here too. Anywhere else and a magazine that had just been
        // emptied would keep the loaded rule and refuse to stack, and - worse - a
        // magazine that had just been filled could still stack with an empty one
        // and take its rounds along.
        applyMaxStack(stack, magazineLoadList(stack).isEmpty() ? 64 : 1);
    }

    /** Uniform fill/clear helper (legacy API). */
    public void setMagazineContents(ItemStack stack, int count, String roundId) {
        MagazineType type = magazineType(stack);
        if (type == null) {
            return;
        }
        int clamped = Math.max(0, Math.min(type.capacity(), count));
        if (clamped <= 0 || roundId == null || roundId.isBlank()) {
            writeMagazineLoad(stack, List.of());
            return;
        }
        String rid = roundId.trim().toLowerCase(Locale.ROOT);
        List<String> load = new ArrayList<>(clamped);
        for (int i = 0; i < clamped; i++) {
            load.add(rid);
        }
        writeMagazineLoad(stack, load);
    }

    public void refreshMagazineLore(ItemStack stack) {
        MagazineType type = magazineType(stack);
        if (type == null || !stack.hasItemMeta()) {
            return;
        }
        List<String> load = magazineLoadList(stack);
        int count = load.size();
        // Deliberately not the stack amount. The name and lore used to include it -
        // "x2 (empty)" against "[0/30]" - which made a single empty magazine a
        // different item from a stack of them, so they refused to merge, a stack
        // showed the wrong thing until the window was touched, and a magazine
        // taken out of a corpse came back unable to stack with the ones already
        // held. The client already draws the number in the corner.
        ItemMeta meta = stack.getItemMeta();
        Map<String, Integer> counts = magazineLoadCounts(stack);
        String nextId = count > 0 ? load.get(load.size() - 1) : null;
        String nextLabel = nextId == null ? "Empty" : roundPlainName(nextId);
        String loadedLine;
        if (count <= 0) {
            loadedLine = "&7Loaded: &fEmpty";
        } else if (counts.size() == 1) {
            loadedLine = "&7Loaded: &f" + nextLabel;
        } else {
            loadedLine = "&7Loaded: &fMixed &8· next &f" + nextLabel;
        }
        Component name = colorize(type.displayName()
                + " &7[" + count + "/" + type.capacity() + "]");
        meta.displayName(name.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        try {
            meta.itemName(name.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        } catch (Throwable ignored) {
        }
        String calLine = "&7Caliber: &f" + AmmoCaliber.displayLabel(type.caliber());
        List<Component> detail = new ArrayList<>();
        detail.add(colorize(calLine));
        detail.add(colorize(loadedLine));
        if (count > 0) {
            if (counts.size() > 1) {
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    detail.add(colorize("&8· &f" + roundPlainName(e.getKey()) + " &7×" + e.getValue()));
                }
                detail.add(colorize("&7Next: &f" + nextLabel));
            }
            detail.add(colorize("&7Capacity: &f" + type.capacity() + " &7· open &f" + (type.capacity() - count)));
            detail.add(colorize("&cDoes not stack while loaded"));
        } else {
            detail.add(colorize("&7Capacity: &f" + type.capacity()));
        }
        detail.add(colorize(type.fitLore()));
        detail.add(colorize(adapterHintLine(type)));
        detail.add(colorize("&7Platform: &f" + type.platform().name().replace('_', ' ')));
        detail.add(colorize("&8────────"));
        detail.add(colorize("&eHold mag &7→ auto-loads matching ammo"));
        detail.add(colorize("&eClick ammo onto mag &7→ load (can mix types)"));
        detail.add(colorize("&eShift-Left &7→ unload all · &eShift-Right &7→ next type"));
        detail.add(colorize("&eRight-click &7→ unload 5 from top"));
        detail.add(colorize("&eCraft loaded mag &7→ dump rounds (mag returns)"));
        writeTooltipDetail(meta, detail);
        List<Component> lore = new ArrayList<>();
        lore.add(colorize(calLine)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        lore.add(colorize(loadedLine)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        lore.add(colorize(type.fitLore())
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        lore.add(colorize(adapterHintLine(type))
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        lore.add(colorize("&8Hold &eShift")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(lore);
        try {
            meta.setMaxStackSize(count > 0 ? 1 : 64);
        } catch (Throwable ignored) {
        }
        stack.setItemMeta(meta);
        // This is called on its own from several places, and setting the meta
        // above drops the component, so the rule is restated here as well.
        applyMaxStack(stack, count > 0 ? 1 : 64);
    }

    private String roundPlainName(String roundId) {
        if (roundId == null) {
            return "?";
        }
        return plugin.rounds().get(roundId)
                .map(r -> PlainTextComponentSerializer.plainText().serialize(colorize(r.displayName()))
                        .replaceAll("§.", "").trim())
                .orElse(roundId);
    }

    /** Empty mags of the same type may merge. Loaded mags never merge. */
    public boolean magazinesCanMerge(ItemStack a, ItemStack b) {
        MagazineType ta = magazineType(a);
        MagazineType tb = magazineType(b);
        if (ta == null || tb == null || ta != tb) {
            return false;
        }
        if (magazineCount(a) > 0 || magazineCount(b) > 0) {
            return false;
        }
        return true;
    }

    /** Merge empty mag stacks only. */
    public int mergeMagazines(ItemStack into, ItemStack from) {
        if (!magazinesCanMerge(into, from)) {
            return from == null ? 0 : from.getAmount();
        }
        int space = 64 - into.getAmount();
        int move = Math.min(space, from.getAmount());
        if (move <= 0) {
            return from.getAmount();
        }
        into.setAmount(into.getAmount() + move);
        setMagazineContents(into, 0, null);
        int left = from.getAmount() - move;
        from.setAmount(left);
        if (left > 0) {
            setMagazineContents(from, 0, null);
        }
        return left;
    }

    /** Split empty mag stacks only (loaded mags are never stacked). */
    public ItemStack splitMagazineStack(ItemStack stack, int takeAmount) {
        MagazineType type = magazineType(stack);
        if (type == null || takeAmount <= 0 || takeAmount >= stack.getAmount()) {
            return null;
        }
        if (magazineCount(stack) > 0) {
            return null;
        }
        ItemStack taken = createMagazine(type, 0, null, takeAmount);
        stack.setAmount(stack.getAmount() - takeAmount);
        refreshMagazineLore(stack);
        return taken;
    }

    /**
     * Load ammo into a single mag. Same caliber may mix types (last loaded fires first).
     * {@code mag} must already be amount 1 (split empty stacks before calling).
     */
    public int fillMagazineFrom(ItemStack mag, ItemStack ammoStack) {
        MagazineType type = magazineType(mag);
        Optional<RoundDefinition> round = roundOf(ammoStack);
        if (type == null || round.isEmpty() || ammoStack == null) {
            return 0;
        }
        if (mag.getAmount() != 1) {
            return 0;
        }
        if (!AmmoCaliber.sameFamily(round.get().caliber(), type.caliber())) {
            return 0;
        }
        // Same material as mag (clay .50 / seeds shells) — force single mag before load
        if (mag.getAmount() != 1) {
            return 0;
        }
        List<String> load = magazineLoadList(mag);
        int space = type.capacity() - load.size();
        if (space <= 0) {
            return 0;
        }
        int take = Math.min(space, ammoStack.getAmount());
        if (take <= 0) {
            return 0;
        }
        String rid = round.get().fileName().trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < take; i++) {
            load.add(rid);
        }
        writeMagazineLoad(mag, load);
        ammoStack.setAmount(ammoStack.getAmount() - take);
        return take;
    }

    /**
     * Whether two stacks should merge in inventory / corpse loot.
     * Vanilla {@code isSimilar} fails when one copy has a leftover max-stack
     * component or lore drift — the usual corpse shift-click miss.
     * Guns and loaded magazines never merge.
     */
    public boolean canStackTogether(ItemStack a, ItemStack b) {
        if (a == null || b == null || a.getType().isAir() || b.getType().isAir()) {
            return false;
        }
        if (isGunItem(a) || isGunItem(b)) {
            return false;
        }
        if (isMagazine(a) || isMagazine(b)) {
            return magazinesCanMerge(a, b);
        }
        if (isWarzMap(a) || isWarzMap(b)) {
            return isWarzMap(a) && isWarzMap(b);
        }
        if (isLifeStraw(a) || isLifeStraw(b) || isGrapplingHook(a) || isGrapplingHook(b)
                || isBigDroneItem(a) || isBigDroneItem(b)
                || NvgGear.isNvgHelmet(plugin, a) || NvgGear.isNvgHelmet(plugin, b)
                || ThermalGear.isThermalHelmet(plugin, a) || ThermalGear.isThermalHelmet(plugin, b)) {
            return false;
        }
        Optional<String> ra = roundId(a);
        Optional<String> rb = roundId(b);
        if (ra.isPresent() || rb.isPresent()) {
            return ra.isPresent() && ra.equals(rb);
        }
        String fa = foodId(a);
        String fb = foodId(b);
        if (fa != null || fb != null) {
            return fa != null && fa.equals(fb);
        }
        String da = drinkId(a);
        String db = drinkId(b);
        if (da != null || db != null) {
            return da != null && da.equals(db);
        }
        String ma = medicalId(a);
        String mb = medicalId(b);
        if (ma != null || mb != null) {
            return ma != null && ma.equals(mb);
        }
        String pa = attachmentPartId(a);
        String pb = attachmentPartId(b);
        if (pa != null || pb != null) {
            return pa != null && pa.equals(pb);
        }
        if (isEmptyCan(a) || isEmptyCan(b)) {
            return isEmptyCan(a) && isEmptyCan(b);
        }
        if (isEmptyGlassBottle(a) || isEmptyGlassBottle(b)) {
            return isEmptyGlassBottle(a) && isEmptyGlassBottle(b);
        }
        if (isPlasticBottle(a) || isPlasticBottle(b)) {
            return isPlasticBottle(a) && isPlasticBottle(b);
        }
        if (isBrokenGlassBottle(a) || isBrokenGlassBottle(b)) {
            return isBrokenGlassBottle(a) && isBrokenGlassBottle(b);
        }
        if (isChainlink(a) || isChainlink(b)) {
            return isChainlink(a) && isChainlink(b);
        }
        if (isRazorWire(a) || isRazorWire(b)) {
            return isRazorWire(a) && isRazorWire(b);
        }
        if (isMetal(a) || isMetal(b)) {
            return isMetal(a) && isMetal(b);
        }
        if (isJetFuelCan(a) || isJetFuelCan(b)) {
            return isJetFuelCan(a) && isJetFuelCan(b);
        }
        if (isHydrazineFuelCan(a) || isHydrazineFuelCan(b)) {
            return isHydrazineFuelCan(a) && isHydrazineFuelCan(b);
        }
        SmokeType sa = smokeType(a);
        SmokeType sb = smokeType(b);
        if (sa != null || sb != null) {
            return sa != null && sa == sb;
        }
        if (isRoadFlare(a) || isRoadFlare(b)) {
            return isRoadFlare(a) && isRoadFlare(b) && flareColor(a) != null
                    && flareColor(a).equals(flareColor(b));
        }
        return a.isSimilar(b);
    }

    /** Max amount this stack is allowed to hold. Guns and loaded mags are always 1. */
    public int stackLimit(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return 1;
        }
        if (isGunItem(stack)) {
            return 1;
        }
        if (isMagazine(stack) && magazineCount(stack) > 0) {
            return 1;
        }
        Optional<RoundDefinition> round = roundOf(stack);
        if (round.isPresent() && !isMagazine(stack) && round.get().explodeRadiusAdd() > 0) {
            return HE_LOOSE_STACK_MAX;
        }
        if (isMetal(stack)) {
            return METAL_STACK_MAX;
        }
        if (medicalId(stack) != null) {
            return MEDICAL_STACK_MAX;
        }
        if (isFood(stack) || isEmptyCan(stack) || isDrink(stack)) {
            return EMPTY_CAN_STACK_MAX;
        }
        if (isWarzMap(stack) || (isMagazine(stack) && magazineCount(stack) <= 0)) {
            return 64;
        }
        int marked = stack.getMaxStackSize();
        return marked > 0 ? marked : 64;
    }

    /**
     * Put {@code incoming} into {@code inv}, merging WarZ-identical stacks first.
     * @return leftover that did not fit, or null
     */
    public ItemStack addItemMerging(org.bukkit.inventory.Inventory inv, ItemStack incoming) {
        if (inv == null) {
            return incoming;
        }
        int end = inv.getSize();
        if (inv instanceof PlayerInventory) {
            end = 36;
        }
        return addItemMerging(inv, incoming, 0, end);
    }

    public ItemStack addItemMerging(org.bukkit.inventory.Inventory inv, ItemStack incoming,
                                    int fromSlot, int toSlotExclusive) {
        if (inv == null || incoming == null || incoming.getType().isAir() || incoming.getAmount() <= 0) {
            return null;
        }
        ItemStack moving = incoming.clone();
        int last = Math.min(toSlotExclusive, inv.getSize());
        int first = Math.max(0, fromSlot);
        for (int i = first; i < last && moving.getAmount() > 0; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot == null || slot.getType().isAir()) {
                continue;
            }
            if (!canStackTogether(slot, moving)) {
                continue;
            }
            int max = Math.min(stackLimit(slot), stackLimit(moving));
            int space = max - slot.getAmount();
            if (space <= 0) {
                continue;
            }
            int take = Math.min(space, moving.getAmount());
            slot.setAmount(slot.getAmount() + take);
            inv.setItem(i, slot);
            moving.setAmount(moving.getAmount() - take);
        }
        for (int i = first; i < last && moving.getAmount() > 0; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot != null && !slot.getType().isAir()) {
                continue;
            }
            int put = Math.min(stackLimit(moving), moving.getAmount());
            ItemStack place = moving.clone();
            place.setAmount(put);
            applyMaxStack(place, stackLimit(place));
            inv.setItem(i, place);
            moving.setAmount(moving.getAmount() - put);
        }
        return moving.getAmount() <= 0 ? null : moving;
    }

    /** Give leftover item stacks to a player (inventory, else drop). */
    public void giveOrDrop(org.bukkit.entity.Player player, List<ItemStack> items) {
        if (player == null || items == null) {
            return;
        }
        for (ItemStack it : items) {
            if (it == null || it.getType().isAir() || it.getAmount() <= 0) {
                continue;
            }
            ItemStack left = addItemMerging(player.getInventory(), it);
            if (left != null && left.getAmount() > 0) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
    }

    public void giveOrDrop(org.bukkit.entity.Player player, ItemStack item) {
        if (item == null) {
            return;
        }
        giveOrDrop(player, List.of(item));
    }

    /**
     * Pull matching-caliber loose ammo from {@code inv} into {@code mag}. Returns rounds loaded.
     * Mixed types allowed.
     */
    public int autoFillMagazine(PlayerInventory inv, ItemStack mag) {
        MagazineType type = magazineType(mag);
        if (type == null || inv == null) {
            return 0;
        }
        int loaded = 0;
        int guard = 0;
        while (magazineCount(mag) < magazineTotalCapacity(mag) && guard++ < 128) {
            ItemStack ammo = null;
            for (ItemStack it : inv.getContents()) {
                if (it == null) {
                    continue;
                }
                Optional<RoundDefinition> r = roundOf(it);
                if (r.isEmpty()) {
                    continue;
                }
                if (!AmmoCaliber.sameFamily(r.get().caliber(), type.caliber())) {
                    continue;
                }
                ammo = it;
                break;
            }
            if (ammo == null) {
                break;
            }
            int took = fillMagazineFrom(mag, ammo);
            if (took <= 0) {
                break;
            }
            loaded += took;
        }
        return loaded;
    }

    /**
     * Shift-click helper: dump this ammo stack into every matching mag the player
     * is carrying (partial first, then empties). Returns rounds loaded.
     */
    public int fillMatchingMagsFrom(org.bukkit.entity.Player player, ItemStack ammo) {
        if (player == null || ammo == null || ammo.getAmount() <= 0) {
            return 0;
        }
        Optional<RoundDefinition> round = roundOf(ammo);
        if (round.isEmpty()) {
            return 0;
        }
        int total = 0;
        int guard = 0;
        while (ammo.getAmount() > 0 && guard++ < 256) {
            ItemStack mag = findMagWithSpace(player, round.get());
            if (mag == null) {
                break;
            }
            int took = fillMagazineFrom(mag, ammo);
            if (took <= 0) {
                break;
            }
            total += took;
        }
        return total;
    }

    /** Live inventory mag (amount 1) that can take {@code round}. Prefers already holding that round. */
    private ItemStack findMagWithSpace(org.bukkit.entity.Player player, RoundDefinition round) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        int bestSlot = -1;
        int bestScore = 99;
        for (int i = 0; i < contents.length; i++) {
            if (i >= 36 && i <= 39) {
                continue;
            }
            ItemStack mag = contents[i];
            if (!isMagazine(mag)) {
                continue;
            }
            MagazineType type = magazineType(mag);
            if (type == null || !AmmoCaliber.sameFamily(round.caliber(), type.caliber())) {
                continue;
            }
            int count = magazineCount(mag);
            boolean stackedEmpty = mag.getAmount() > 1 && count <= 0;
            int space = stackedEmpty ? type.capacity() : type.capacity() - count;
            if (space <= 0 || (!stackedEmpty && mag.getAmount() != 1)) {
                continue;
            }
            int score;
            if (count > 0 && magazineCountOf(mag, round.fileName()) > 0) {
                score = 0;
            } else if (count > 0) {
                score = 1;
            } else {
                score = 2;
            }
            if (score < bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return null;
        }
        ItemStack mag = inv.getItem(bestSlot);
        if (mag == null) {
            return null;
        }
        if (mag.getAmount() > 1 && magazineCount(mag) <= 0) {
            // One magazine has to come off the stack before it can be loaded, and
            // the leftovers must not be handed back through the inventory: they are
            // identical empties, so they merge straight back into this slot and the
            // rounds then go into a stack of two - which is why both magazines
            // ended up reading zero.
            //
            // The remainder stays where it is and the single one goes to a free
            // slot. Once it has rounds in it, it can no longer merge with them.
            int free = inv.firstEmpty();
            if (free < 0) {
                return null;
            }
            ItemStack single = mag.clone();
            single.setAmount(1);
            setMagazineContents(single, 0, null);

            ItemStack rest = mag.clone();
            rest.setAmount(mag.getAmount() - 1);
            setMagazineContents(rest, 0, null);

            inv.setItem(bestSlot, rest);
            inv.setItem(free, single);
            return inv.getItem(free);
        }
        if (mag.getAmount() != 1) {
            return null;
        }
        return mag;
    }

    /** Extract all ammo from mag as stacks (mag emptied). Mixed types → multiple stacks. */
    public List<ItemStack> extractMagazineAmmo(ItemStack mag) {
        List<ItemStack> out = new ArrayList<>();
        Map<String, Integer> counts = magazineLoadCounts(mag);
        if (counts.isEmpty()) {
            return out;
        }
        writeMagazineLoad(mag, List.of());
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            Optional<RoundDefinition> round = plugin.rounds().get(e.getKey());
            if (round.isEmpty() || e.getValue() <= 0) {
                continue;
            }
            // Split HE / large dumps so pocket stack caps never delete rounds (sniper HE glitch).
            out.addAll(createRounds(round.get(), e.getValue()));
        }
        return out;
    }

    public int unloadMagazineTo(PlayerInventory inv, ItemStack mag) {
        if (magazineType(mag) == null || inv == null) {
            return 0;
        }
        if (magazineLoadList(mag).isEmpty()) {
            return 0;
        }
        List<ItemStack> stacks = extractMagazineAmmo(mag);
        int unloaded = 0;
        List<String> leftover = new ArrayList<>();
        for (ItemStack ammo : stacks) {
            if (ammo == null || ammo.getAmount() <= 0) {
                continue;
            }
            int before = ammo.getAmount();
            var left = inv.addItem(ammo);
            int failed = left.values().stream().mapToInt(ItemStack::getAmount).sum();
            unloaded += before - failed;
            for (ItemStack fail : left.values()) {
                Optional<RoundDefinition> fr = roundOf(fail);
                if (fr.isEmpty()) {
                    continue;
                }
                String id = fr.get().fileName().toLowerCase(Locale.ROOT);
                for (int i = 0; i < fail.getAmount(); i++) {
                    leftover.add(id);
                }
            }
        }
        if (!leftover.isEmpty()) {
            writeMagazineLoad(mag, leftover);
        }
        return unloaded;
    }

    public boolean magazineFitsGun(ItemStack mag, GunDefinition gun) {
        return magazineFitsGun(mag, gun, null);
    }

    public boolean magazineFitsGun(ItemStack mag, GunDefinition gun, ItemStack gunItem) {
        MagazineType type = magazineType(mag);
        if (type == null || gun == null) {
            return false;
        }
        if (!AmmoCaliber.sameFamily(type.caliber(), gun.ammoCaliber())) {
            return false;
        }
        MagPlatform gunPlat = MagPlatform.forGun(gun);
        MagPlatform magPlat = type.platform();
        if (gunPlat == magPlat) {
            return true;
        }
        // SMG mags can feed AR-platform PDWs that share rifle caliber
        if (gunPlat == MagPlatform.AR && magPlat == MagPlatform.SMG) {
            return true;
        }
        if (gunPlat == MagPlatform.SMG && magPlat == MagPlatform.AR) {
            return true;
        }
        boolean adapter = gunItem != null && hasMagAdapter(gunItem);
        return adapter && MagPlatform.adapterBridges(gunPlat, magPlat);
    }

    /** Unload up to {@code max} rounds from the top of the mag. */
    public int unloadMagazinePartial(PlayerInventory inv, ItemStack mag, int max) {
        if (magazineType(mag) == null || inv == null || max <= 0) {
            return 0;
        }
        List<String> load = magazineLoadList(mag);
        if (load.isEmpty()) {
            return 0;
        }
        int take = Math.min(max, load.size());
        List<String> removed = new ArrayList<>(load.subList(load.size() - take, load.size()));
        load.subList(load.size() - take, load.size()).clear();
        writeMagazineLoad(mag, load);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String id : removed) {
            counts.merge(id, 1, Integer::sum);
        }
        int unloaded = 0;
        List<String> leftover = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            Optional<RoundDefinition> round = plugin.rounds().get(e.getKey());
            if (round.isEmpty()) {
                continue;
            }
            ItemStack ammo = createRound(round.get(), e.getValue());
            int before = ammo.getAmount();
            var left = inv.addItem(ammo);
            int failed = left.values().stream().mapToInt(ItemStack::getAmount).sum();
            unloaded += before - failed;
            for (ItemStack fail : left.values()) {
                Optional<RoundDefinition> fr = roundOf(fail);
                if (fr.isEmpty()) {
                    continue;
                }
                String id = fr.get().fileName().toLowerCase(Locale.ROOT);
                for (int i = 0; i < fail.getAmount(); i++) {
                    leftover.add(id);
                }
            }
        }
        if (!leftover.isEmpty()) {
            List<String> merged = magazineLoadList(mag);
            merged.addAll(leftover);
            writeMagazineLoad(mag, merged);
        }
        return unloaded;
    }

    /** Unload every round of the next-to-fire type only. */
    public int unloadMagazineNextType(PlayerInventory inv, ItemStack mag) {
        String next = magazineRoundId(mag);
        if (next == null) {
            return 0;
        }
        int have = magazineCountOf(mag, next);
        if (have <= 0) {
            return 0;
        }
        List<String> load = magazineLoadList(mag);
        List<String> keep = new ArrayList<>();
        int removed = 0;
        for (String id : load) {
            if (next.equals(id)) {
                removed++;
            } else {
                keep.add(id);
            }
        }
        writeMagazineLoad(mag, keep);
        Optional<RoundDefinition> round = plugin.rounds().get(next);
        if (round.isEmpty()) {
            return 0;
        }
        ItemStack ammo = createRound(round.get(), removed);
        var left = inv.addItem(ammo);
        int failed = left.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (failed > 0) {
            List<String> back = magazineLoadList(mag);
            for (int i = 0; i < failed; i++) {
                back.add(next);
            }
            writeMagazineLoad(mag, back);
        }
        return removed - failed;
    }

    public String chamberRound(ItemStack gun) {
        if (gun == null || !isGunItem(gun) || !gun.hasItemMeta()) {
            return null;
        }
        String id = gun.getItemMeta().getPersistentDataContainer().get(chamberRoundKey, PersistentDataType.STRING);
        return id == null || id.isBlank() ? null : id;
    }

    public boolean hasChamberRound(ItemStack gun) {
        return chamberRound(gun) != null;
    }

    public void setChamberRound(ItemStack gun, String roundId) {
        if (gun == null || !isGunItem(gun) || !gun.hasItemMeta()) {
            return;
        }
        ItemMeta meta = gun.getItemMeta();
        if (roundId == null || roundId.isBlank()) {
            meta.getPersistentDataContainer().remove(chamberRoundKey);
        } else {
            meta.getPersistentDataContainer().set(chamberRoundKey, PersistentDataType.STRING,
                    roundId.trim().toLowerCase(Locale.ROOT));
        }
        gun.setItemMeta(meta);
    }

    public static Component colorize(String input) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(input == null ? "" : input);
    }

    // ---- Creative-tab stubs → full server items --------------------------------

    /**
     * Companion creative tabs ship thin stacks (PDC + name only). When those land
     * in a player's inventory, rebuild them through the normal create* paths so
     * lore, Shift tooltip detail, stack caps, and models match give-menu items.
     *
     * @return a replacement stack, or {@code null} if no change is needed
     */
    public ItemStack materializeIfNeeded(ItemStack source) {
        if (source == null || source.getType() == Material.AIR || !source.hasItemMeta()) {
            return null;
        }
        if (!looksLikeCreativeStub(source)) {
            return null;
        }
        return materializeOrNull(source);
    }

    /**
     * Always rebuild when the stack has WarZ PDC / {@code warz_give} / creative_stub
     * (used for creative-mode picks — client stubs often carry only the give-spec).
     */
    public ItemStack materializeWarZ(ItemStack source) {
        if (source == null || source.getType() == Material.AIR || !source.hasItemMeta()) {
            return null;
        }
        // warz_give alone is enough — do NOT require other identity keys (byte-flag
        // mismatch used to make flashlight/drone/etc. look like "no WarZ item").
        if (warzGiveId(source) == null && !hasCreativeStubFlag(source) && !hasWarZIdentity(source)) {
            return null;
        }
        return materializeOrNull(source);
    }

    private ItemStack materializeOrNull(ItemStack source) {
        ItemStack full = materialize(source);
        if (full == null || full.getType() == Material.AIR) {
            return null;
        }
        return full;
    }

    public boolean hasCreativeStubFlag(ItemStack stack) {
        return readFlag(stack, creativeStubKey);
    }

    /** Companion may write flags as BYTE or INT — accept either. */
    private boolean readFlag(ItemStack stack, NamespacedKey key) {
        if (stack == null || !stack.hasItemMeta() || key == null) {
            return false;
        }
        var pdc = stack.getItemMeta().getPersistentDataContainer();
        Byte b = pdc.get(key, PersistentDataType.BYTE);
        if (b != null) {
            return b == (byte) 1;
        }
        Integer i = pdc.get(key, PersistentDataType.INTEGER);
        return i != null && i == 1;
    }

    public String warzGiveId(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .get(warzGiveKey, PersistentDataType.STRING);
    }

    /**
     * True when the stack has WarZ identity but is still a thin creative / incomplete item.
     * Potions keep vanilla lore, so drinks cannot use "empty lore" alone.
     * Guns may get lore filled by {@link GunTooltipListener} without a full create().
     */
    public boolean looksLikeCreativeStub(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return false;
        }
        if (hasCreativeStubFlag(stack) || warzGiveId(stack) != null) {
            return true;
        }
        if (!hasWarZIdentity(stack)) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        List<Component> lore = meta.lore();
        boolean thinLore = lore == null || lore.isEmpty();
        String detail = meta.getPersistentDataContainer().get(tooltipDetailKey, PersistentDataType.STRING);
        boolean missingDetail = detail == null || detail.isBlank();

        if (isGunItem(stack) || (roundId(stack).isPresent() && !isMagazine(stack))) {
            // Compact lore from applyGunInventoryLore still lacks a full create() pass
            // when creative_stub was stripped — require tooltip_detail AND a Hold-Shift line.
            if (missingDetail) {
                return true;
            }
            return !loreContainsHoldShift(lore);
        }
        if (isMagazine(stack)) {
            return thinLore || !loreHasMagazineLines(lore);
        }
        if (isDrink(stack) || isFood(stack) || isSmokeGrenade(stack) || isRoadFlare(stack)
                || isAttachmentPart(stack) || NvgGear.isNvgHelmet(plugin, stack)
                || ThermalGear.isThermalHelmet(plugin, stack)) {
            // Vanilla potion lore / custom name only — need WarZ blurb lines.
            return thinLore || missingWarZPresentation(lore);
        }
        return thinLore;
    }

    private static boolean loreContainsHoldShift(List<Component> lore) {
        if (lore == null) {
            return false;
        }
        for (Component line : lore) {
            String plain = PlainTextComponentSerializer.plainText().serialize(line).toLowerCase(Locale.ROOT);
            if (plain.contains("hold") && plain.contains("shift")) {
                return true;
            }
        }
        return false;
    }

    private static boolean loreHasMagazineLines(List<Component> lore) {
        if (lore == null) {
            return false;
        }
        for (Component line : lore) {
            String plain = PlainTextComponentSerializer.plainText().serialize(line).toLowerCase(Locale.ROOT);
            if (plain.contains("capacity") || plain.contains("rounds") || plain.contains("empty")
                    || plain.contains("loaded") || plain.contains("caliber")) {
                return true;
            }
        }
        return false;
    }

    private static boolean missingWarZPresentation(List<Component> lore) {
        if (lore == null || lore.isEmpty()) {
            return true;
        }
        // Single vanilla potion line / plain name is not enough.
        int meaningful = 0;
        for (Component line : lore) {
            String plain = PlainTextComponentSerializer.plainText().serialize(line).trim();
            if (plain.isEmpty()) {
                continue;
            }
            // Skip vanilla "No Effects" / duration crumbs
            String lower = plain.toLowerCase(Locale.ROOT);
            if (lower.contains("no effects") || lower.matches(".*\\d+:\\d+.*")) {
                continue;
            }
            meaningful++;
        }
        return meaningful < 2;
    }

    public boolean hasWarZIdentity(ItemStack stack) {
        return isGunItem(stack)
                || (roundId(stack).isPresent() && !isMagazine(stack))
                || isMagazine(stack)
                || isSmokeGrenade(stack)
                || isRoadFlare(stack)
                || isDrink(stack)
                || isFood(stack)
                || isEmptyCan(stack)
                || isEmptyGlassBottle(stack)
                || isPlasticBottle(stack)
                || isBrokenGlassBottle(stack)
                || isLifeStraw(stack)
                || isAttachmentPart(stack)
                || NvgGear.isNvgHelmet(plugin, stack)
                || ThermalGear.isThermalHelmet(plugin, stack)
                || medicalId(stack) != null
                || isChainlink(stack)
                || isRazorWire(stack)
                || isWireCutters(stack)
                || isScubaHelmet(stack)
                || isScubaTank(stack)
                || isWetsuitLeggings(stack)
                || isWetsuitBoots(stack)
                || isFlashlight(stack)
                || isGrapplingHook(stack)
                || isMetal(stack)
                || isJetFuelCan(stack)
                || isRadiolink(stack)
                || isBigDroneItem(stack);
    }

    /**
     * Rebuild a WarZ stack from its PDC identity. Preserves amount and mag load.
     * Returns {@code null} if the identity cannot be resolved.
     */
    public ItemStack materialize(ItemStack source) {
        if (source == null || source.getType() == Material.AIR || !source.hasItemMeta()) {
            return null;
        }
        int amt = Math.max(1, source.getAmount());

        // Preferred path: compact give-spec from companion creative tabs.
        String give = warzGiveId(source);
        if (give != null && !give.isBlank()) {
            ItemStack fromGive = materializeGiveSpec(give.trim(), amt, source);
            if (fromGive != null && fromGive.getType() != Material.AIR) {
                return fromGive;
            }
        }

        Optional<String> gid = gunId(source);
        if (gid.isPresent()) {
            return plugin.registry().get(gid.get()).map(def -> create(def, amt)).orElse(null);
        }

        if (!isMagazine(source)) {
            Optional<String> rid = roundId(source);
            if (rid.isPresent()) {
                String id = rid.get();
                if ("flare_cartridge".equalsIgnoreCase(id)) {
                    return createFlareCartridge(amt);
                }
                return plugin.rounds().get(id).map(def -> createRound(def, amt)).orElse(null);
            }
        }

        MagazineType mag = magazineType(source);
        if (mag != null) {
            List<String> load = magazineLoadList(source);
            if (load.isEmpty()) {
                return createMagazine(mag, amt);
            }
            ItemStack full = createMagazine(mag, 0, null, 1);
            writeMagazineLoad(full, load);
            return full;
        }

        SmokeType smoke = smokeType(source);
        if (smoke != null) {
            return createSmokeGrenade(smoke, amt);
        }

        if (isRoadFlare(source)) {
            return createRoadFlare(flareColor(source), amt);
        }

        DrinkType drink = drinkType(source);
        if (drink != null) {
            return createDrink(drink, amt);
        }

        WarzFoodType food = foodType(source);
        if (food != null) {
            return createFood(food, amt);
        }

        if (isEmptyCan(source)) {
            return createEmptyCan(amt);
        }
        if (isEmptyGlassBottle(source)) {
            return createEmptyGlassBottle(amt);
        }
        if (isPlasticBottle(source)) {
            return createPlasticBottle(amt);
        }
        if (isBrokenGlassBottle(source)) {
            return createBrokenGlassBottle(amt);
        }
        if (isLifeStraw(source)) {
            return createLifeStraw(lifeStrawUses(source));
        }

        NvgGear.Variant nvg = NvgGear.variantOf(plugin, source);
        if (nvg != null) {
            return createNvgHelmet(nvg);
        }
        ThermalGear.Variant thermal = ThermalGear.variantOf(plugin, source);
        if (thermal != null) {
            return createThermalHelmet(thermal);
        }

        String med = medicalId(source);
        if (med != null) {
            return switch (med) {
                case MEDICAL_SPLINT -> createSplint(amt);
                case MEDICAL_BANDAGE -> createBandage(amt);
                case MEDICAL_TOURNIQUET -> createTourniquet(amt);
                case MEDICAL_BLOOD_BAG -> createBloodBag(amt);
                default -> null;
            };
        }

        if (isChainlink(source)) {
            return createChainlink(amt);
        }
        if (isRazorWire(source)) {
            return createRazorWire(amt);
        }
        if (isWireCutters(source)) {
            return createWireCutters(amt);
        }
        if (isScubaHelmet(source)) {
            return createScubaHelmet();
        }
        if (isScubaTank(source)) {
            return createScubaTank();
        }
        if (isWetsuitLeggings(source)) {
            return createWetsuitLeggings();
        }
        if (isWetsuitBoots(source)) {
            return createWetsuitBoots();
        }
        if (isFlashlight(source)) {
            return createFlashlight();
        }
        if (isGrapplingHook(source)) {
            return withGrappleUses(baseGrappleStack(), grappleUses(source));
        }
        if (isMetal(source)) {
            return createMetal(amt);
        }
        if (isJetFuelCan(source)) {
            return createJetFuelCan(amt);
        }
        if (isRadiolink(source)) {
            return createRadiolink();
        }
        if (isBigDroneItem(source)) {
            return createBigDrone(
                    droneType(source),
                    droneCargoRockets(source),
                    droneCargoFuelCans(source),
                    droneCargoStructureHp(source),
                    droneCargoFlares(source));
        }

        String part = attachmentPartId(source);
        if (part != null) {
            ItemStack full = createFromAttachmentPart(part);
            if (full != null && amt > 1 && full.getMaxStackSize() > 1) {
                full.setAmount(Math.min(amt, full.getMaxStackSize()));
            }
            return full;
        }

        return null;
    }

    /**
     * Resolve companion creative {@code warz_give} specs into full ItemFactory stacks.
     * Formats: gun:id, round:id, mag:id, mag:id:loaded, smoke:id, flare:color,
     * drink:id, food:id, part:…, nvg:id, thermal:id, medical:id, gear:id
     */
    public ItemStack materializeGiveSpec(String give, int amount, ItemStack source) {
        if (give == null || give.isBlank()) {
            return null;
        }
        int amt = Math.max(1, amount);
        String[] bits = give.toLowerCase(Locale.ROOT).split(":", 3);
        if (bits.length < 2) {
            return null;
        }
        String kind = bits[0];
        String id = bits[1];
        String extra = bits.length > 2 ? bits[2] : "";

        return switch (kind) {
            case "gun" -> plugin.registry().get(id).map(def -> create(def, amt)).orElse(null);
            case "round" -> {
                if ("flare_cartridge".equals(id)) {
                    yield createFlareCartridge(amt);
                }
                yield plugin.rounds().get(id).map(def -> createRound(def, amt)).orElse(null);
            }
            case "mag" -> {
                MagazineType mag = MagazineType.fromId(id);
                if (mag == null) {
                    yield null;
                }
                if ("loaded".equals(extra) || "full".equals(extra)) {
                    String round = defaultRoundForMag(mag);
                    if (source != null) {
                        String fromLoad = magazineRoundId(source);
                        if (fromLoad != null) {
                            round = fromLoad;
                        }
                    }
                    yield createMagazine(mag, mag.capacity(), round, 1);
                }
                yield createMagazine(mag, amt);
            }
            case "smoke" -> {
                SmokeType smoke = SmokeType.byKey(id);
                yield smoke != null ? createSmokeGrenade(smoke, amt) : null;
            }
            case "flare" -> createRoadFlare(FlareColor.fromId(id), amt);
            case "drink" -> {
                DrinkType drink = DrinkType.byId(id);
                yield drink != null ? createDrink(drink, amt) : null;
            }
            case "food" -> {
                WarzFoodType food = WarzFoodType.byId(id);
                yield food != null ? createFood(food, amt) : null;
            }
            case "part" -> {
                // part:optic:rds  / part:glass:standard:block  / part:flashlight
                String part = give.substring("part:".length());
                ItemStack full = createFromAttachmentPart(part);
                if (full != null && amt > 1 && full.getMaxStackSize() > 1) {
                    full.setAmount(Math.min(amt, full.getMaxStackSize()));
                }
                yield full;
            }
            case "nvg" -> {
                NvgGear.Variant v = NvgGear.Variant.byPdc(id);
                yield v != null ? createNvgHelmet(v) : null;
            }
            case "thermal" -> {
                ThermalGear.Variant v = ThermalGear.Variant.byPdc(id);
                yield v != null ? createThermalHelmet(v) : null;
            }
            case "medical" -> switch (id) {
                case MEDICAL_SPLINT -> createSplint(amt);
                case MEDICAL_BANDAGE -> createBandage(amt);
                case MEDICAL_TOURNIQUET -> createTourniquet(amt);
                case MEDICAL_BLOOD_BAG -> createBloodBag(amt);
                default -> null;
            };
            case "gear" -> switch (id) {
                case "flashlight" -> createFlashlight();
                case "grapple" -> createGrapplingHook();
                case "chainlink" -> createChainlink(amt);
                case "razor_wire" -> createRazorWire(amt);
                case "wire_cutters" -> createWireCutters(amt);
                case "long_prongs", "prongs" -> createLongProngs(false);
                case "long_prongs_lava", "prongs_lava" -> createLongProngs(true);
                case "obsidian_shards", "obsidian_shard" -> createObsidianShards(amt);
                case "handcuffs", "cuffs" -> createHandcuffs(amt);
                case "handcuff_key", "handcuffs_key", "cuff_key" -> createHandcuffKey(amt);
                case "lockpick", "lock_pick" -> createLockpick(amt);
                case "zip_ties", "zipties", "ziptie" -> createZipTies(amt);
                case "pocket_knife", "knife" -> createPocketKnife(amt);
                case "jet_fuel", "jet_fuel_can" -> createJetFuelCan(amt);
                case "hydrazine", "hydrazine_fuel", "hydrazine_fuel_can" -> createHydrazineFuelCan(amt);
                case "scuba_helmet" -> createScubaHelmet();
                case "scuba_tank" -> createScubaTank();
                case "wetsuit_legs" -> createWetsuitLeggings();
                case "wetsuit_boots" -> createWetsuitBoots();
                case "hazmat_helmet" -> createHazmatHelmet();
                case "hazmat", "hazmat_chest", "hazmat_suit" -> createHazmatChestplate();
                case "hazmat_legs" -> createHazmatLeggings();
                case "hazmat_boots" -> createHazmatBoots();
                case "fire_proximity_helmet", "firesuit_helmet" -> createFireProximityHelmet();
                case "fire_proximity", "fire_proximity_chest", "firesuit" -> createFireProximityChestplate();
                case "fire_proximity_legs", "firesuit_legs" -> createFireProximityLeggings();
                case "fire_proximity_boots", "firesuit_boots" -> createFireProximityBoots();
                case "bigdrone", "mq9", "reaper" -> createBigDrone(BigDroneType.MQ9);
                case "rq4", "globalhawk", "global_hawk" -> createBigDrone(BigDroneType.RQ4);
                case "mq4c", "triton" -> createBigDrone(BigDroneType.MQ4C);
                case "mq1c", "grayeagle", "gray_eagle" -> createBigDrone(BigDroneType.MQ1C);
                case "rq170", "sentinel" -> createBigDrone(BigDroneType.RQ170);
                case "x47b", "x47" -> createBigDrone(BigDroneType.X47B);
                case "x37b", "x37" -> createBigDrone(BigDroneType.X37B);
                case "radiolink" -> createRadiolink();
                case "metal" -> createMetal(amt);
                case "empty_can" -> createEmptyCan(amt);
                case "empty_bottle" -> createEmptyGlassBottle(amt);
                case "plastic_bottle" -> createPlasticBottle(amt);
                case "broken_glass" -> createBrokenGlassBottle(amt);
                case "life_straw", "lifestraw" -> createLifeStraw(LIFE_STRAW_MAX_USES);
                case "canned_beans", "beans" -> createFood(WarzFoodType.CANNED_BEANS, amt);
                case "canned_pasta", "pasta" -> createFood(WarzFoodType.CANNED_PASTA, amt);
                case "canned_fish", "fish" -> createFood(WarzFoodType.CANNED_FISH, amt);
                case "dew", "mountain_dew" -> createFood(WarzFoodType.DEW, amt);
                case "golden_apple", "gapple" -> createFood(WarzFoodType.GOLDEN_APPLE, amt);
                default -> null;
            };
            default -> null;
        };
    }

    private static String defaultRoundForMag(MagazineType mag) {
        if (mag == null) {
            return "rifle_fmj";
        }
        return switch (AmmoCaliber.normalize(mag.caliber())) {
            case "pistol" -> "pistol_fmj";
            case "sniper", "heavy" -> "sniper_fmj";
            case "shotgun", "shot" -> "shot_buck";
            default -> "rifle_fmj";
        };
    }

    private ItemStack createFromAttachmentPart(String part) {
        if (part == null || part.isBlank()) {
            return null;
        }
        if ("gun_workbench".equals(part)) {
            return createGunWorkbenchItem();
        }
        if ("flashlight".equals(part)) {
            return createFlashlightModulePart();
        }
        if ("peq15".equals(part)) {
            return createPeq15Part();
        }
        if (MAG_ADAPTER_AK_AR.equals(part)) {
            return createMagAdapterAkAr();
        }
        if (part.startsWith("suppressor")) {
            SuppressorType type = suppressorPartType(syntheticAttachment(part));
            return createSuppressorPart(type != null ? type : SuppressorType.RIFLE);
        }
        if (part.startsWith("laser:")) {
            LaserModColor c = LaserModColor.fromId(part.substring("laser:".length()));
            return createLaserModulePart(c);
        }
        if (part.startsWith("optic:")) {
            return createOpticPart(OpticType.fromId(part.substring("optic:".length())));
        }
        if (part.startsWith("grip:")) {
            GripType type = GripType.fromId(part.substring("grip:".length()));
            return type.isInstalled() ? createGripPart(type) : null;
        }
        if (part.startsWith("glass:")) {
            String[] bits = part.split(":");
            if (bits.length < 3) {
                return null;
            }
            GlassType type = GlassType.fromId(bits[1]);
            if (type == null) {
                return null;
            }
            return "pane".equals(bits[2]) ? createGlassPane(type) : createGlassBlock(type);
        }
        return null;
    }

    /** Tiny stack used only so suppressorPartType / similar can parse a part id. */
    private ItemStack syntheticAttachment(String part) {
        ItemStack stack = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(attachmentPartKey, PersistentDataType.STRING, part);
        stack.setItemMeta(meta);
        return stack;
    }
}
