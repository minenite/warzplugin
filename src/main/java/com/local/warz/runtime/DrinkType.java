package com.local.warz.runtime;

import org.bukkit.Color;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;

/** Consumable drinks for the thirst system. */
public enum DrinkType {
    PEPSI("pepsi", "&9Pepsi", Color.fromRGB(0x1A, 0x33, 0x66),
            8.0, 0.0, 0, true, 0.0, List.of()),
    COKE("coke", "&4Coca-Cola", Color.fromRGB(0x3E, 0x1F, 0x14),
            8.0, 0.0, 0, true, 0.0, List.of()),
    WATER("water", "&bWater", Color.fromRGB(0x7E, 0xC8, 0xFF),
            0.0, 45.0, 25, false, 0.0, List.of()),
    GATORADE("gatorade", "&6Gatorade", Color.fromRGB(0xFF, 0x8C, 0x00),
            0.0, 50.0, 10, false, 0.0, List.of()),

    // Alcohol — dehydrates somewhat, short buffs (max ~2 min). Avoid SLOWNESS (gun ADS).
    BEER("beer", "&eBeer", Color.fromRGB(0xD4, 0xA0, 0x34),
            0.0, 0.0, 0, false, 12.0, List.of(
            effect(PotionEffectType.ABSORPTION, 90, 0),
            effect(PotionEffectType.NAUSEA, 8, 0)
    )),
    WINE("wine", "&5Wine", Color.fromRGB(0x72, 0x14, 0x2B),
            0.0, 0.0, 0, false, 18.0, List.of(
            effect(PotionEffectType.REGENERATION, 70, 0),
            effect(PotionEffectType.NAUSEA, 6, 0)
    )),
    LIQUOR("liquor", "&6Liquor", Color.fromRGB(0xC4, 0xA3, 0x5A),
            0.0, 0.0, 0, false, 28.0, List.of(
            effect(PotionEffectType.STRENGTH, 100, 0),
            effect(PotionEffectType.NAUSEA, 14, 0),
            effect(PotionEffectType.DARKNESS, 5, 0)
    )),
    CIDER("cider", "&6Cider", Color.fromRGB(0xE8, 0x9B, 0x3C),
            0.0, 0.0, 0, false, 14.0, List.of(
            effect(PotionEffectType.JUMP_BOOST, 80, 0),
            effect(PotionEffectType.SPEED, 50, 0),
            effect(PotionEffectType.NAUSEA, 5, 0)
    )),
    SELTZER("seltzer", "&fHard Seltzer", Color.fromRGB(0xB8, 0xE0, 0xD2),
            0.0, 0.0, 0, false, 8.0, List.of(
            effect(PotionEffectType.SPEED, 60, 0),
            effect(PotionEffectType.HASTE, 45, 0)
    )),

    // Field water — fill from sources; boil on campfire to purify.
    UNFILTERED_WATER_BOTTLE("unfiltered_water_bottle", "&eUnfiltered Water Bottle",
            Color.fromRGB(0x6B, 0x8E, 0x4E), 0.0, 30.0, 20, false, 0.0, List.of()),
    FILTERED_WATER_BOTTLE("filtered_water_bottle", "&bFiltered Water Bottle",
            Color.fromRGB(0x7E, 0xC8, 0xFF), 0.0, 45.0, 25, false, 0.0, List.of()),
    UNFILTERED_WATER_CAN("unfiltered_water_can", "&eUnfiltered Water Can",
            Color.fromRGB(0x6B, 0x8E, 0x4E), 0.0, 28.0, 18, false, 0.0, List.of()),
    FILTERED_WATER_CAN("filtered_water_can", "&bFiltered Water Can",
            Color.fromRGB(0x7E, 0xC8, 0xFF), 0.0, 40.0, 22, false, 0.0, List.of()),
    UNFILTERED_WATER_GLASS("unfiltered_water_glass", "&eUnfiltered Water Glass",
            Color.fromRGB(0x6B, 0x8E, 0x4E), 0.0, 30.0, 20, false, 0.0, List.of()),
    FILTERED_WATER_GLASS("filtered_water_glass", "&bFiltered Water Glass",
            Color.fromRGB(0x7E, 0xC8, 0xFF), 0.0, 45.0, 25, false, 0.0, List.of());

    /** Alcohol cannot push thirst below this. */
    public static final double ALCOHOL_THIRST_FLOOR = 20.0;

    public final String id;
    public final String displayName;
    public final Color color;
    /** Instant thirst restore (0–100 scale). */
    public final double instantHydration;
    /** Gradual thirst restore applied over {@link #hydrateSeconds}. */
    public final double pendingHydration;
    public final int hydrateSeconds;
    public final boolean sodaBoost;
    /** Instant thirst loss for alcohol (clamped to floor). */
    public final double dehydrateAmount;
    public final List<PotionEffect> effects;

    DrinkType(String id, String displayName, Color color,
              double instantHydration, double pendingHydration, int hydrateSeconds,
              boolean sodaBoost, double dehydrateAmount, List<PotionEffect> effects) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
        this.instantHydration = instantHydration;
        this.pendingHydration = pendingHydration;
        this.hydrateSeconds = hydrateSeconds;
        this.sodaBoost = sodaBoost;
        this.dehydrateAmount = dehydrateAmount;
        this.effects = List.copyOf(effects);
    }

    public boolean alcohol() {
        return dehydrateAmount > 0;
    }

    /** Untreated field water — drinking applies Infected. */
    public boolean dirtyWater() {
        return this == UNFILTERED_WATER_BOTTLE
                || this == UNFILTERED_WATER_CAN
                || this == UNFILTERED_WATER_GLASS;
    }

    public static DrinkType byId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (DrinkType t : values()) {
            if (t.id.equalsIgnoreCase(id)) {
                return t;
            }
        }
        return null;
    }

    private static PotionEffect effect(PotionEffectType type, int seconds, int amplifier) {
        int ticks = Math.min(20 * 120, Math.max(1, seconds) * 20); // cap 2 min
        return new PotionEffect(type, ticks, amplifier, false, true, true);
    }

    public List<String> loreLines() {
        List<String> lore = new ArrayList<>();
        switch (this) {
            case PEPSI, COKE -> {
                lore.add("&7Sugary soda — barely hydrates");
                lore.add("&aSpeed &7for &f1 min&7, then &ccrash &730s");
            }
            case WATER -> lore.add("&7Hydrates slowly over time");
            case GATORADE -> lore.add("&7Electrolytes — hydrates faster than water");
            case BEER -> {
                lore.add("&7Absorption + short nausea");
                lore.add("&cDehydrates &7a little");
            }
            case WINE -> {
                lore.add("&7Regen + short nausea");
                lore.add("&cDehydrates &7moderately");
            }
            case LIQUOR -> {
                lore.add("&7Strength + nausea / darkness");
                lore.add("&cDehydrates &7more");
            }
            case CIDER -> {
                lore.add("&7Jump + short speed");
                lore.add("&cDehydrates &7a little");
            }
            case SELTZER -> {
                lore.add("&7Light buzz — speed + haste");
                lore.add("&cSlightly dehydrates");
            }
            case UNFILTERED_WATER_BOTTLE, UNFILTERED_WATER_CAN, UNFILTERED_WATER_GLASS -> {
                lore.add("&eUnfiltered &7— will &2Infect &7you");
                lore.add("&7Boil on a &6campfire &7to purify");
            }
            case FILTERED_WATER_BOTTLE, FILTERED_WATER_CAN, FILTERED_WATER_GLASS -> {
                lore.add("&aBoiled / filtered — safe to drink");
                lore.add("&7Hydrates slowly over time");
            }
        }
        lore.add("&eRight-click &7→ drink (instant)");
        lore.add(switch (this) {
            case BEER, WINE, LIQUOR, CIDER, UNFILTERED_WATER_GLASS, FILTERED_WATER_GLASS
                    -> "&8Leaves a glass bottle (throw to break)";
            case WATER, GATORADE, UNFILTERED_WATER_BOTTLE, FILTERED_WATER_BOTTLE
                    -> "&8Leaves a plastic bottle";
            case COKE, PEPSI, SELTZER, UNFILTERED_WATER_CAN, FILTERED_WATER_CAN
                    -> "&8Leaves an empty can";
            default -> "&8Leaves an empty can";
        });
        return lore;
    }
}
