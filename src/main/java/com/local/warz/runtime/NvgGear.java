package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

/** Shadow Company Quad NOD / NVG helmet helpers (multi + fixed phosphor variants). */
public final class NvgGear {
    /** Multi-palette NODS — H cycles phosphors on the client. */
    public static final int CMD_MULTI = 2001;
    public static final String PDC_MULTI = "quad_nods";
    /** Phosphor green IR appears as through Gen3+/quad tubes. */
    public static final Color IR_PHOSPHOR = Color.fromRGB(0x2A, 0xFF, 0x4A);

    public enum Variant {
        MULTI(CMD_MULTI, PDC_MULTI, "Multi", "&aShadow Company Quad NODs",
                "&7H &fcycles &7phosphor: green / white / amber / blue / red / true"),
        GREEN(2010, "quad_nods_green", "Green", "&aQuad NODs &2(Green)",
                "&7H &ftoggles &7green phosphor ON/OFF"),
        WHITE(2011, "quad_nods_white", "White", "&fQuad NODs &7(White)",
                "&7H &ftoggles &7white phosphor ON/OFF"),
        AMBER(2012, "quad_nods_amber", "Amber", "&6Quad NODs &e(Amber)",
                "&7H &ftoggles &7amber phosphor ON/OFF"),
        BLUE(2013, "quad_nods_blue", "Blue", "&bQuad NODs &9(Blue)",
                "&7H &ftoggles &7blue phosphor ON/OFF"),
        RED(2014, "quad_nods_red", "Red", "&cQuad NODs &4(Red)",
                "&7H &ftoggles &7red phosphor ON/OFF — &cparanormal sensor"),
        TRUE_COLOR(2015, "quad_nods_true", "True Color", "&dQuad NODs &5(True Color)",
                "&7H &ftoggles &7true-color tubes ON/OFF");

        public final int cmd;
        public final String pdc;
        public final String shortLabel;
        public final String displayName;
        public final String hHint;

        Variant(int cmd, String pdc, String shortLabel, String displayName, String hHint) {
            this.cmd = cmd;
            this.pdc = pdc;
            this.shortLabel = shortLabel;
            this.displayName = displayName;
            this.hHint = hHint;
        }

        public boolean multi() {
            return this == MULTI;
        }

        public static Variant byPdc(String id) {
            if (id == null || id.isBlank()) {
                return null;
            }
            for (Variant v : values()) {
                if (v.pdc.equalsIgnoreCase(id)) {
                    return v;
                }
            }
            return null;
        }

        public static Variant byCmd(int cmd) {
            for (Variant v : values()) {
                if (v.cmd == cmd) {
                    return v;
                }
            }
            return null;
        }
    }

    /** @deprecated use {@link #CMD_MULTI} */
    public static final int CMD = CMD_MULTI;
    /** @deprecated use {@link #PDC_MULTI} */
    public static final String PDC_VALUE = PDC_MULTI;

    private NvgGear() {
    }

    public static boolean isNvgHelmet(WarzPlugin plugin, ItemStack stack) {
        return variantOf(plugin, stack) != null;
    }

    public static Variant variantOf(WarzPlugin plugin, ItemStack stack) {
        if (stack == null || stack.getType() != Material.CARVED_PUMPKIN || !stack.hasItemMeta()) {
            return null;
        }
        String id = stack.getItemMeta().getPersistentDataContainer()
                .get(plugin.items().nvgKey(), PersistentDataType.STRING);
        return Variant.byPdc(id);
    }

    public static boolean isWearingNvg(WarzPlugin plugin, Player player) {
        if (player == null) {
            return false;
        }
        return isNvgHelmet(plugin, player.getInventory().getHelmet());
    }

    /**
     * NVG look is driven by the Fabric companion lightmap (realistic crush + bloom).
     * Do not apply vanilla Night Vision — it washes the world flat.
     */
    public static void tickWearer(WarzPlugin plugin, Player player) {
        if (!isWearingNvg(plugin, player)) {
            return;
        }
        // Flashbang / critical blood-loss vision must not be stripped every tick.
        if (com.local.warz.combat.ImpactEffects.isFlashProtected(player)) {
            return;
        }
        if (plugin.medical() != null && plugin.medical().isBloodVisionProtected(player)) {
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
            return;
        }
        player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        player.removePotionEffect(PotionEffectType.DARKNESS);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
    }
}
