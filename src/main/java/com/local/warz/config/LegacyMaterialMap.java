package com.local.warz.config;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps pre-1.13 numeric item IDs (and a few modern names) to Materials.
 */
public final class LegacyMaterialMap {
    private static final Map<Integer, Material> BY_ID = new HashMap<>();

    static {
        put(256, Material.IRON_SHOVEL);
        put(257, Material.IRON_PICKAXE);
        put(258, Material.IRON_AXE);
        put(259, Material.FLINT_AND_STEEL);
        put(260, Material.APPLE);
        put(261, Material.BOW);
        put(262, Material.ARROW);
        put(263, Material.COAL);
        put(264, Material.DIAMOND);
        put(265, Material.IRON_INGOT);
        put(266, Material.GOLD_INGOT);
        put(267, Material.IRON_SWORD);
        put(268, Material.WOODEN_SWORD);
        put(269, Material.WOODEN_SHOVEL);
        put(270, Material.WOODEN_PICKAXE);
        put(271, Material.WOODEN_AXE);
        put(272, Material.STONE_SWORD);
        put(273, Material.STONE_SHOVEL);
        put(274, Material.STONE_PICKAXE);
        put(275, Material.STONE_AXE);
        put(276, Material.DIAMOND_SWORD);
        put(277, Material.DIAMOND_SHOVEL);
        put(278, Material.DIAMOND_PICKAXE);
        put(279, Material.DIAMOND_AXE);
        put(280, Material.STICK);
        put(281, Material.BOWL);
        put(282, Material.MUSHROOM_STEW);
        put(283, Material.GOLDEN_SWORD);
        put(284, Material.GOLDEN_SHOVEL);
        put(285, Material.GOLDEN_PICKAXE);
        put(286, Material.GOLDEN_AXE);
        put(287, Material.STRING);
        put(288, Material.FEATHER);
        put(289, Material.GUNPOWDER);
        put(290, Material.WOODEN_HOE);
        put(291, Material.STONE_HOE);
        put(292, Material.IRON_HOE);
        put(293, Material.DIAMOND_HOE);
        put(294, Material.GOLDEN_HOE);
        put(295, Material.WHEAT_SEEDS);
        put(318, Material.FLINT);
        put(332, Material.SNOWBALL);
        put(334, Material.LEATHER);
        put(336, Material.BRICK);
        put(337, Material.CLAY_BALL);
        put(339, Material.PAPER);
        put(341, Material.SLIME_BALL);
        put(348, Material.GLOWSTONE_DUST);
        put(352, Material.BONE);
        put(368, Material.ENDER_PEARL);
        put(369, Material.BLAZE_ROD);
        put(371, Material.GOLD_NUGGET);
        put(372, Material.NETHER_WART);
        put(377, Material.BLAZE_POWDER);
        put(378, Material.MAGMA_CREAM);
    }

    private LegacyMaterialMap() {
    }

    private static void put(int id, Material material) {
        BY_ID.put(id, material);
    }

    public static Material fromLegacy(String raw) {
        if (raw == null || raw.isBlank()) {
            return Material.STICK;
        }
        String value = raw.trim();
        if (value.contains(":")) {
            value = value.substring(0, value.indexOf(':'));
        }
        try {
            int id = Integer.parseInt(value);
            Material mapped = BY_ID.get(id);
            if (mapped != null) {
                return mapped;
            }
        } catch (NumberFormatException ignored) {
            // fall through to Material match
        }
        Material named = Material.matchMaterial(value.toUpperCase(Locale.ROOT));
        return named != null ? named : Material.STICK;
    }
}
