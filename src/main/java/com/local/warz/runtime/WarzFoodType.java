package com.local.warz.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Old WarZ foods remade as stick + CMD + PDC items (sugar excluded).
 * HP / hunger match the 1.8 Food plugin; canned items leave an empty can.
 */
public enum WarzFoodType {
    CANNED_BEANS("canned_beans", "&6Canned Beans", ItemFactory.CMD_FOOD_BEANS,
            5.0, 5, false, true, 1_199L, false,
            "beans", "bean"),
    CANNED_PASTA("canned_pasta", "&eCanned Pasta", ItemFactory.CMD_FOOD_PASTA,
            7.0, 7, false, true, 1_199L, false,
            "pasta"),
    CANNED_FISH("canned_fish", "&bCanned Fish", ItemFactory.CMD_FOOD_FISH,
            7.0, 7, false, true, 1_199L, false,
            "fish"),
    DEW("dew", "&aDew", ItemFactory.CMD_FOOD_DEW,
            0.0, 0, true, true, 999L, true,
            "mountain_dew", "mtdew"),
    GOLDEN_APPLE("golden_apple", "&6Golden Apple", ItemFactory.CMD_FOOD_GOLDEN_APPLE,
            0.0, 0, false, false, 99_999L, false,
            "gapple", "gold_apple");

    public final String id;
    public final String displayName;
    public final int cmd;
    /** Flat HP added (ignored when {@link #fullRestore} or golden apple). */
    public final double healHp;
    /** Hunger points added (ignored when {@link #fullRestore}). */
    public final int hunger;
    /** Dew: set health 20 and food 20. */
    public final boolean fullRestore;
    public final boolean leavesEmptyCan;
    public final long cooldownMs;
    public final boolean drinkAnimation;
    private final String[] aliases;

    WarzFoodType(String id, String displayName, int cmd,
                 double healHp, int hunger, boolean fullRestore, boolean leavesEmptyCan,
                 long cooldownMs, boolean drinkAnimation, String... aliases) {
        this.id = id;
        this.displayName = displayName;
        this.cmd = cmd;
        this.healHp = healHp;
        this.hunger = hunger;
        this.fullRestore = fullRestore;
        this.leavesEmptyCan = leavesEmptyCan;
        this.cooldownMs = cooldownMs;
        this.drinkAnimation = drinkAnimation;
        this.aliases = aliases == null ? new String[0] : aliases;
    }

    /** Instant Health II — 8 HP. */
    public boolean instantHealthIi() {
        return this == GOLDEN_APPLE;
    }

    public List<String> loreLines() {
        List<String> lore = new ArrayList<>();
        switch (this) {
            case CANNED_BEANS -> {
                lore.add("&7Emergency ration");
                lore.add("&a+5 HP &7· &e+5 hunger");
            }
            case CANNED_PASTA -> {
                lore.add("&7Hearty canned meal");
                lore.add("&a+7 HP &7· &e+7 hunger");
            }
            case CANNED_FISH -> {
                lore.add("&7Oily canned fish");
                lore.add("&a+7 HP &7· &e+7 hunger");
            }
            case DEW -> {
                lore.add("&7Canned soda — fills you up");
                lore.add("&aHealth & hunger to full");
            }
            case GOLDEN_APPLE -> {
                lore.add("&7Enchanted fruit");
                lore.add("&aInstant Health II &7· strips Absorption");
                lore.add("&8~100s cooldown");
            }
        }
        lore.add(drinkAnimation ? "&eRight-click &7→ drink (instant)" : "&eRight-click &7→ eat (instant)");
        if (leavesEmptyCan) {
            lore.add("&8Leaves an empty can");
        }
        return lore;
    }

    public static WarzFoodType byId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (WarzFoodType t : values()) {
            if (t.id.equals(key)) {
                return t;
            }
            for (String alias : t.aliases) {
                if (alias.equals(key)) {
                    return t;
                }
            }
        }
        return null;
    }
}
