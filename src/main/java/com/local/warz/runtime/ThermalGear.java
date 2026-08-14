package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

/** FLIR / thermal optic helmet helpers (multi + fixed palette variants). */
public final class ThermalGear {
    public static final int CMD_MULTI = 2002;
    public static final String PDC_MULTI = "thermal_flir";

    public enum Variant {
        MULTI(CMD_MULTI, PDC_MULTI, "Multi", "&6Shadow Company FLIR Thermal",
                "&7H &fcycles &7White Hot / Black Hot / Ironbow / Rainbow / Fusion"),
        WHITE_HOT(2020, "thermal_flir_white_hot", "White Hot", "&fFLIR &7(White Hot)",
                "&7H &ftoggles &7white-hot ON/OFF"),
        BLACK_HOT(2021, "thermal_flir_black_hot", "Black Hot", "&8FLIR &7(Black Hot)",
                "&7H &ftoggles &7black-hot ON/OFF"),
        IRONBOW(2022, "thermal_flir_ironbow", "Ironbow", "&6FLIR &e(Ironbow)",
                "&7H &ftoggles &7ironbow ON/OFF"),
        RAINBOW(2023, "thermal_flir_rainbow", "Rainbow", "&dFLIR &5(Rainbow)",
                "&7H &ftoggles &7rainbow ON/OFF"),
        FUSION(2024, "thermal_flir_fusion", "Fusion", "&bFLIR &3(Fusion)",
                "&7H &ftoggles &7fusion ON/OFF");

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

    private ThermalGear() {
    }

    public static boolean isThermalHelmet(WarzPlugin plugin, ItemStack stack) {
        return variantOf(plugin, stack) != null;
    }

    public static Variant variantOf(WarzPlugin plugin, ItemStack stack) {
        if (stack == null || stack.getType() != Material.CARVED_PUMPKIN || !stack.hasItemMeta()) {
            return null;
        }
        String id = stack.getItemMeta().getPersistentDataContainer()
                .get(plugin.items().thermalKey(), PersistentDataType.STRING);
        return Variant.byPdc(id);
    }

    public static boolean isWearingThermal(WarzPlugin plugin, Player player) {
        if (player == null) {
            return false;
        }
        return isThermalHelmet(plugin, player.getInventory().getHelmet());
    }

    public static void tickWearer(WarzPlugin plugin, Player player) {
        if (!isWearingThermal(plugin, player)) {
            return;
        }
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
