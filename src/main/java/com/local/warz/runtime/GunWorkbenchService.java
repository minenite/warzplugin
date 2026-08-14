package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tracks placed Gun Workbench blocks (stored as fletching tables) and syncs positions
 * to Fabric companions for custom block rendering.
 */
public final class GunWorkbenchService {
    public static final String CHANNEL = "pvpgunminus:workbench";
    public static final Material BLOCK_TYPE = Material.FLETCHING_TABLE;

    private final WarzPlugin plugin;
    private final Set<String> benches = new HashSet<>();
    private final File file;

    public GunWorkbenchService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "gun-workbenches.yml");
        load();
    }

    public void registerChannel() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    public void unregisterChannel() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    public boolean isWorkbench(Block block) {
        if (block == null || block.getType() != BLOCK_TYPE) {
            return false;
        }
        return benches.contains(key(block.getLocation()));
    }

    public void mark(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        benches.add(key(loc));
        save();
        broadcastFull();
    }

    public void unmark(Location loc) {
        if (loc == null) {
            return;
        }
        if (benches.remove(key(loc))) {
            save();
            broadcastFull();
        }
    }

    public void syncViewer(Player viewer) {
        if (viewer == null || plugin.companions() == null || !plugin.companions().hasCompanion(viewer)) {
            return;
        }
        byte[] payload = encodeFull();
        if (payload != null) {
            viewer.sendPluginMessage(plugin, CHANNEL, payload);
        }
    }

    public void broadcastFull() {
        byte[] payload = encodeFull();
        if (payload == null || plugin.companions() == null) {
            return;
        }
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (plugin.companions().hasCompanion(viewer)) {
                viewer.sendPluginMessage(plugin, CHANNEL, payload);
            }
        }
    }

    public ItemStack craft(ItemStack gunIn, ItemStack suppressorIn, ItemStack laserIn,
                           ItemStack flashlightIn, Player feedback) {
        return craft(gunIn, suppressorIn, laserIn, flashlightIn, null, null, null, feedback);
    }

    public ItemStack craft(ItemStack gunIn, ItemStack suppressorIn, ItemStack laserIn,
                           ItemStack flashlightIn, ItemStack adapterIn, Player feedback) {
        return craft(gunIn, suppressorIn, laserIn, flashlightIn, adapterIn, null, null, feedback);
    }

    public ItemStack craft(ItemStack gunIn, ItemStack suppressorIn, ItemStack laserIn,
                           ItemStack flashlightIn, ItemStack adapterIn,
                           ItemStack opticIn, ItemStack gripIn, Player feedback) {
        if (gunIn == null || !plugin.items().isGunItem(gunIn)) {
            if (feedback != null) {
                playerMsg(feedback, "Need a gun in the gun slot", NamedTextColor.RED);
            }
            return null;
        }
        SuppressorType sup = plugin.items().suppressorPartType(suppressorIn);
        LaserModColor laser = plugin.items().laserPartColor(laserIn);
        boolean flash = plugin.items().isFlashlightModulePart(flashlightIn);
        boolean peq = plugin.items().isPeq15Part(flashlightIn);
        boolean adapter = plugin.items().isMagAdapterPart(adapterIn);
        OpticType optic = plugin.items().opticPartType(opticIn);
        GripType grip = plugin.items().gripPartType(gripIn);
        boolean hasInstalled = plugin.items().hasSuppressor(gunIn)
                || plugin.items().hasLaserMod(gunIn)
                || plugin.items().hasFlashlightMod(gunIn)
                || plugin.items().hasPeq(gunIn)
                || plugin.items().hasMagAdapter(gunIn)
                || plugin.items().hasOpticPart(gunIn)
                || plugin.items().hasGrip(gunIn);

        if (sup == null && laser == null && !flash && !peq && !adapter && optic == null && grip == null) {
            if (!hasInstalled) {
                if (feedback != null) {
                    playerMsg(feedback, "Add parts, or use a gun with attachments to strip",
                            NamedTextColor.RED);
                }
                return null;
            }
            ItemStack out = gunIn.clone();
            out.setAmount(1);
            plugin.items().setSuppressor(out, (SuppressorType) null);
            plugin.items().setLaserMod(out, LaserModColor.NONE);
            plugin.items().setFlashlightMod(out, false);
            plugin.items().setPeq(out, false);
            plugin.items().setMagAdapter(out, false);
            plugin.items().clearOptic(out);
            plugin.items().setGrip(out, GripType.NONE);
            return out;
        }

        ItemStack out = gunIn.clone();
        out.setAmount(1);
        var gunDef = plugin.items().gunId(out).flatMap(id -> plugin.registry().get(id)).orElse(null);

        if (sup != null) {
            if (!plugin.items().suppressorFitsGun(out, sup)) {
                if (feedback != null) {
                    playerMsg(feedback, sup.displayName().replace("&7", "")
                            + " does not fit this gun", NamedTextColor.RED);
                }
                return null;
            }
            plugin.items().setSuppressor(out, sup);
        }
        if (laser != null) {
            plugin.items().setLaserMod(out, laser);
        }
        if (peq) {
            plugin.items().setPeq(out, true);
            plugin.items().setOpticMode(out, PeqMode.OFF);
        } else if (flash) {
            plugin.items().setFlashlightMod(out, true);
        }
        if (adapter) {
            MagPlatform plat = MagPlatform.forGun(gunDef);
            if (plat != MagPlatform.AR && plat != MagPlatform.AK) {
                if (feedback != null) {
                    playerMsg(feedback, "AK↔AR adapter only fits AR / AK rifles (not .50 / SMG / shotgun)",
                            NamedTextColor.RED);
                }
                return null;
            }
            plugin.items().setMagAdapter(out, true);
        }
        if (optic != null) {
            if (!optic.fits(gunDef)) {
                if (feedback != null) {
                    playerMsg(feedback, "This gun cannot take rail optics", NamedTextColor.RED);
                }
                return null;
            }
            plugin.items().setOptic(out, optic);
        }
        if (grip != null && grip.isInstalled()) {
            if (!grip.fits(gunDef)) {
                if (feedback != null) {
                    playerMsg(feedback, "This gun cannot take a grip", NamedTextColor.RED);
                }
                return null;
            }
            plugin.items().setGrip(out, grip);
        }
        return out;
    }

    public boolean isStripMode(ItemStack gunIn, ItemStack suppressorIn, ItemStack laserIn,
                               ItemStack flashlightIn) {
        return isStripMode(gunIn, suppressorIn, laserIn, flashlightIn, null, null, null);
    }

    public boolean isStripMode(ItemStack gunIn, ItemStack suppressorIn, ItemStack laserIn,
                               ItemStack flashlightIn, ItemStack adapterIn) {
        return isStripMode(gunIn, suppressorIn, laserIn, flashlightIn, adapterIn, null, null);
    }

    public boolean isStripMode(ItemStack gunIn, ItemStack suppressorIn, ItemStack laserIn,
                               ItemStack flashlightIn, ItemStack adapterIn,
                               ItemStack opticIn, ItemStack gripIn) {
        if (gunIn == null || !plugin.items().isGunItem(gunIn)) {
            return false;
        }
        if (plugin.items().suppressorPartType(suppressorIn) != null
                || plugin.items().laserPartColor(laserIn) != null
                || plugin.items().isLightDevicePart(flashlightIn)
                || plugin.items().isMagAdapterPart(adapterIn)
                || plugin.items().opticPartType(opticIn) != null
                || plugin.items().gripPartType(gripIn) != null) {
            return false;
        }
        return plugin.items().hasSuppressor(gunIn)
                || plugin.items().hasLaserMod(gunIn)
                || plugin.items().hasFlashlightMod(gunIn)
                || plugin.items().hasPeq(gunIn)
                || plugin.items().hasMagAdapter(gunIn)
                || plugin.items().hasOpticPart(gunIn)
                || plugin.items().hasGrip(gunIn);
    }

    private static void playerMsg(Player player, String msg, NamedTextColor color) {
        String plain = msg.replace("&7", "").replace("&f", "").replace("&c", "");
        player.sendMessage(Component.text(plain, color));
    }

    private void load() {
        benches.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> list = yaml.getStringList("benches");
        benches.addAll(list);
        plugin.getLogger().info("Gun workbenches loaded: " + benches.size());
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("benches", new ArrayList<>(benches));
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save gun-workbenches.yml: " + e.getMessage());
        }
    }

    private byte[] encodeFull() {
        try {
            var bos = new java.io.ByteArrayOutputStream();
            var out = new java.io.DataOutputStream(bos);
            out.writeByte(CompanionClients.PROTOCOL);
            out.writeByte(5); // FULL
            List<BenchPos> coords = new ArrayList<>();
            for (String k : benches) {
                BenchPos c = parseKey(k);
                if (c != null) {
                    coords.add(c);
                }
            }
            out.writeShort(coords.size());
            for (BenchPos c : coords) {
                out.writeInt(c.x);
                out.writeInt(c.y);
                out.writeInt(c.z);
                byte[] name = c.worldKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.writeShort(name.length);
                out.write(name);
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static String key(Location loc) {
        return loc.getWorld().getUID() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private static BenchPos parseKey(String k) {
        String[] p = k.split(":");
        if (p.length < 4) {
            return null;
        }
        try {
            // UUID has colons — last 3 are xyz, rest is world uid
            int z = Integer.parseInt(p[p.length - 1]);
            int y = Integer.parseInt(p[p.length - 2]);
            int x = Integer.parseInt(p[p.length - 3]);
            StringBuilder wb = new StringBuilder(p[0]);
            for (int i = 1; i < p.length - 3; i++) {
                wb.append(':').append(p[i]);
            }
            World world = null;
            try {
                world = Bukkit.getWorld(java.util.UUID.fromString(wb.toString()));
            } catch (IllegalArgumentException ignored) {
            }
            String worldKey = world != null ? world.getKey().asString() : wb.toString();
            return new BenchPos(x, y, z, worldKey);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record BenchPos(int x, int y, int z, String worldKey) {
    }
}
