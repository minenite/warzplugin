package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Radio towers and satellites for MQ-9 datalink range extension.
 * Persisted in {@code drone_datalink.yml}.
 */
public final class DroneDatalinkService {
    private final WarzPlugin plugin;
    private final File file;
    private final Map<String, RadioTower> towers = new ConcurrentHashMap<>();
    private final Map<String, Satellite> satellites = new ConcurrentHashMap<>();
    /** seatKey → tower block key */
    private final Map<String, String> seatToTower = new ConcurrentHashMap<>();
    /** player → tower key awaiting seat link */
    private final Map<UUID, String> pendingSeatLink = new ConcurrentHashMap<>();
    /** player → satellite key awaiting tower uplink */
    private final Map<UUID, String> pendingSatLink = new ConcurrentHashMap<>();

    public DroneDatalinkService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "drone_datalink.yml");
        load();
    }

    public Collection<RadioTower> allTowers() {
        return List.copyOf(towers.values());
    }

    public Collection<Satellite> allSatellites() {
        return List.copyOf(satellites.values());
    }

    public Optional<RadioTower> towerAt(Block block) {
        if (block == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(towers.get(blockKey(block)));
    }

    public Optional<Satellite> satelliteAt(Block block) {
        if (block == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(satellites.get(blockKey(block)));
    }

    /** True when this seat's tower has a live satellite uplink. */
    public boolean seatUsesSatellite(String seatKey) {
        return towerForSeat(seatKey)
                .map(t -> t.satelliteKey != null && !t.satelliteKey.isBlank()
                        && satellites.containsKey(t.satelliteKey))
                .orElse(false);
    }

    public Optional<RadioTower> towerForSeat(String seatKey) {
        if (seatKey == null || seatKey.isBlank()) {
            return Optional.empty();
        }
        String towerKey = seatToTower.get(seatKey);
        return towerKey == null ? Optional.empty() : Optional.ofNullable(towers.get(towerKey));
    }

    /**
     * Datalink quality 0–1 from airframe position and linked seat.
     * Uses tower/satellite uplink when configured on the seat's tower.
     */
    public double computeSignal(Location airframe, String seatKey, Location seatLoc) {
        if (airframe == null || airframe.getWorld() == null) {
            return 1.0;
        }
        Optional<RadioTower> towerOpt = towerForSeat(seatKey);
        if (towerOpt.isEmpty()) {
            if (seatLoc == null || seatLoc.getWorld() == null
                    || !seatLoc.getWorld().equals(airframe.getWorld())) {
                return 0.0;
            }
            double dist = airframe.distance(seatLoc);
            return clamp(1.0 - dist / 180.0, 0.0, 1.0);
        }
        RadioTower tower = towerOpt.get();
        Location towerLoc = tower.location();
        if (towerLoc == null || towerLoc.getWorld() == null
                || !towerLoc.getWorld().equals(airframe.getWorld())) {
            return 0.0;
        }
        if (tower.satelliteKey != null && !tower.satelliteKey.isBlank()) {
            Satellite sat = satellites.get(tower.satelliteKey);
            if (sat != null) {
                Location satLoc = sat.location();
                if (satLoc != null && satLoc.getWorld() != null
                        && satLoc.getWorld().equals(airframe.getWorld())) {
                    double dist = airframe.distance(satLoc);
                    return clamp(0.75 + 0.25 * (1.0 - dist / 2000.0), 0.75, 1.0);
                }
            }
        }
        double dist = airframe.distance(towerLoc);
        return clamp(1.0 - dist / 400.0, 0.0, 1.0);
    }

    /** Admin: register looked-at block as a radio tower. */
    public boolean registerTowerLooking(Player player) {
        Block block = lookingBlock(player);
        if (block == null) {
            player.sendMessage(Component.text("Look at a block first.", NamedTextColor.RED));
            return false;
        }
        return registerTower(player, block);
    }

    public boolean registerTower(Player player, Block block) {
        String key = blockKey(block);
        if (towers.containsKey(key)) {
            player.sendMessage(Component.text("That block is already a radio tower.", NamedTextColor.YELLOW));
            return false;
        }
        RadioTower tower = new RadioTower(key, block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        towers.put(key, tower);
        save();
        player.sendMessage(Component.text("Radio tower registered @ "
                        + block.getX() + "," + block.getY() + "," + block.getZ()
                        + " — right-click a drone seat to link.",
                NamedTextColor.GREEN));
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 1.2f);
        pendingSeatLink.put(player.getUniqueId(), key);
        return true;
    }

    /** Admin: register looked-at block as a satellite. */
    public boolean registerSatelliteLooking(Player player) {
        Block block = lookingBlock(player);
        if (block == null) {
            player.sendMessage(Component.text("Look at a block first.", NamedTextColor.RED));
            return false;
        }
        return registerSatellite(player, block);
    }

    public boolean registerSatellite(Player player, Block block) {
        String key = blockKey(block);
        if (satellites.containsKey(key)) {
            player.sendMessage(Component.text("That block is already a satellite.", NamedTextColor.YELLOW));
            return false;
        }
        Satellite sat = new Satellite(key, block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        satellites.put(key, sat);
        save();
        player.sendMessage(Component.text("Satellite registered @ "
                        + block.getX() + "," + block.getY() + "," + block.getZ()
                        + " — right-click a radio tower to uplink.",
                NamedTextColor.AQUA));
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.8f);
        pendingSatLink.put(player.getUniqueId(), key);
        return true;
    }

    public void beginSeatLink(Player player, Block towerBlock) {
        towerAt(towerBlock).ifPresentOrElse(tower -> {
            pendingSeatLink.put(player.getUniqueId(), tower.key);
            player.sendMessage(Component.text("Tower selected — right-click a drone seat to link.",
                    NamedTextColor.AQUA));
        }, () -> player.sendMessage(Component.text("Not a registered radio tower.", NamedTextColor.RED)));
    }

    public void beginSatelliteUplink(Player player, Block towerBlock) {
        towerAt(towerBlock).ifPresentOrElse(tower -> {
            pendingSatLink.put(player.getUniqueId(), tower.key);
            player.sendMessage(Component.text("Select a satellite, then right-click this tower to uplink.",
                    NamedTextColor.AQUA));
        }, () -> player.sendMessage(Component.text("Not a registered radio tower.", NamedTextColor.RED)));
    }

    /** @return true if the interact was consumed */
    public boolean tryCompleteSeatLink(Player player, Block seatBlock) {
        String towerKey = pendingSeatLink.remove(player.getUniqueId());
        if (towerKey == null) {
            return false;
        }
        if (plugin.droneSeats() == null || !plugin.droneSeats().isSeat(seatBlock)) {
            pendingSeatLink.put(player.getUniqueId(), towerKey);
            return false;
        }
        RadioTower tower = towers.get(towerKey);
        if (tower == null) {
            player.sendMessage(Component.text("Tower no longer exists.", NamedTextColor.RED));
            return true;
        }
        String seatKey = DroneSeatService.key(seatBlock);
        if (tower.seatKey != null) {
            seatToTower.remove(tower.seatKey, towerKey);
        }
        String oldTower = seatToTower.put(seatKey, towerKey);
        if (oldTower != null && !oldTower.equals(towerKey)) {
            RadioTower prev = towers.get(oldTower);
            if (prev != null && seatKey.equals(prev.seatKey)) {
                prev.seatKey = null;
            }
        }
        tower.seatKey = seatKey;
        save();
        player.sendMessage(Component.text("Tower linked to drone seat.", NamedTextColor.GREEN));
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.5f);
        return true;
    }

    /** @return true if the interact was consumed */
    public boolean tryCompleteSatelliteLink(Player player, Block towerBlock) {
        String satKey = pendingSatLink.remove(player.getUniqueId());
        if (satKey == null) {
            return false;
        }
        RadioTower tower = towers.get(blockKey(towerBlock));
        if (tower == null) {
            pendingSatLink.put(player.getUniqueId(), satKey);
            return false;
        }
        if (!satellites.containsKey(satKey)) {
            player.sendMessage(Component.text("Satellite no longer exists.", NamedTextColor.RED));
            return true;
        }
        tower.satelliteKey = satKey;
        save();
        player.sendMessage(Component.text("Satellite uplinked to radio tower.", NamedTextColor.GREEN));
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.7f, 1.4f);
        return true;
    }

    public boolean hasPendingSeatLink(UUID playerId) {
        return pendingSeatLink.containsKey(playerId);
    }

    public boolean hasPendingSatLink(UUID playerId) {
        return pendingSatLink.containsKey(playerId);
    }

    public void clearPending(UUID playerId) {
        if (playerId != null) {
            pendingSeatLink.remove(playerId);
            pendingSatLink.remove(playerId);
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private Block lookingBlock(Player player) {
        RayTraceResult hit = player.rayTraceBlocks(6.0);
        if (hit == null || hit.getHitBlock() == null) {
            return null;
        }
        return hit.getHitBlock();
    }

    public static String blockKey(Block block) {
        return blockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private static String blockKey(String world, int x, int y, int z) {
        return world.toLowerCase(Locale.ROOT) + ";" + x + ";" + y + ";" + z;
    }

    private void load() {
        towers.clear();
        satellites.clear();
        seatToTower.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection towerSec = yaml.getConfigurationSection("towers");
        if (towerSec != null) {
            for (String key : towerSec.getKeys(false)) {
                ConfigurationSection s = towerSec.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                String world = s.getString("world");
                if (world == null) {
                    continue;
                }
                RadioTower tower = new RadioTower(key, world, s.getInt("x"), s.getInt("y"), s.getInt("z"));
                tower.seatKey = s.getString("seat");
                tower.satelliteKey = s.getString("satellite");
                towers.put(key, tower);
                if (tower.seatKey != null && !tower.seatKey.isBlank()) {
                    seatToTower.put(tower.seatKey, key);
                }
            }
        }
        ConfigurationSection satSec = yaml.getConfigurationSection("satellites");
        if (satSec != null) {
            for (String key : satSec.getKeys(false)) {
                ConfigurationSection s = satSec.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                String world = s.getString("world");
                if (world == null) {
                    continue;
                }
                satellites.put(key, new Satellite(key, world, s.getInt("x"), s.getInt("y"), s.getInt("z")));
            }
        }
        plugin.getLogger().info("Loaded " + towers.size() + " datalink tower(s), "
                + satellites.size() + " satellite(s).");
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (RadioTower tower : towers.values()) {
            String path = "towers." + tower.key;
            yaml.set(path + ".world", tower.world);
            yaml.set(path + ".x", tower.x);
            yaml.set(path + ".y", tower.y);
            yaml.set(path + ".z", tower.z);
            yaml.set(path + ".seat", tower.seatKey);
            yaml.set(path + ".satellite", tower.satelliteKey);
        }
        for (Satellite sat : satellites.values()) {
            String path = "satellites." + sat.key;
            yaml.set(path + ".world", sat.world);
            yaml.set(path + ".x", sat.x);
            yaml.set(path + ".y", sat.y);
            yaml.set(path + ".z", sat.z);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save drone_datalink.yml: " + e.getMessage());
        }
    }

    public static final class RadioTower {
        public final String key;
        public final String world;
        public final int x;
        public final int y;
        public final int z;
        public String seatKey;
        public String satelliteKey;

        RadioTower(String key, String world, int x, int y, int z) {
            this.key = key;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x + 0.5, y + 0.5, z + 0.5);
        }
    }

    public static final class Satellite {
        public final String key;
        public final String world;
        public final int x;
        public final int y;
        public final int z;

        Satellite(String key, String world, int x, int y, int z) {
            this.key = key;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x + 0.5, y + 0.5, z + 0.5);
        }
    }
}
