package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Stairs;
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
 * Persistent stairs → drone control seats. Right-click sits a mannequin body in the chair
 * and puts the player in the MQ-9 (see {@link BigDroneService#tryEnterSeat}).
 */
public final class DroneSeatService {
    public static final String VEHICLE_MQ9 = "mq9reaper";

    private final WarzPlugin plugin;
    private final File file;
    private final Map<String, Seat> seats = new ConcurrentHashMap<>();
    /** seatKey → pilot currently using it. */
    private final Map<String, UUID> occupied = new ConcurrentHashMap<>();
    private final Map<UUID, String> seatOfPilot = new ConcurrentHashMap<>();

    public DroneSeatService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "drone_seats.yml");
        load();
    }

    public Collection<Seat> all() {
        return List.copyOf(seats.values());
    }

    public Optional<Seat> seatAt(Block block) {
        if (block == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(seats.get(key(block)));
    }

    public boolean isSeat(Block block) {
        return seatAt(block).isPresent();
    }

    /**
     * Seat registration is by block coordinate — explosions may temporarily turn the stairs
     * to air; the entry stays so explosion-regen can restore the chair and right-click still works.
     */

    public Optional<UUID> occupant(Block block) {
        return seatAt(block).map(s -> occupied.get(s.key()));
    }

    public void markOccupied(String seatKey, UUID pilot) {
        if (seatKey == null || pilot == null) {
            return;
        }
        occupied.put(seatKey, pilot);
        seatOfPilot.put(pilot, seatKey);
    }

    public void clearOccupied(UUID pilot) {
        if (pilot == null) {
            return;
        }
        String key = seatOfPilot.remove(pilot);
        if (key != null) {
            occupied.remove(key, pilot);
        }
    }

    public Optional<String> seatKeyOf(UUID pilot) {
        return Optional.ofNullable(seatOfPilot.get(pilot));
    }

    /** Admin: look at stairs → register as a drone seat. */
    public boolean setLooking(Player player, String vehicleRaw) {
        Block block = lookingStairs(player);
        if (block == null) {
            player.sendMessage(Component.text("Look at a stairs block first.", NamedTextColor.RED));
            return false;
        }
        String vehicle = normalizeVehicle(vehicleRaw);
        if (vehicle == null) {
            player.sendMessage(Component.text("Unknown vehicle. Try: drone", NamedTextColor.RED));
            return false;
        }
        return registerSeat(player, block, vehicle);
    }

    /** Register a specific stairs block as a drone seat. */
    public boolean registerSeat(Player player, Block block, String vehicleRaw) {
        if (block == null || !(block.getBlockData() instanceof Stairs stairs)) {
            player.sendMessage(Component.text("Must be a stairs block.", NamedTextColor.RED));
            return false;
        }
        String vehicle = normalizeVehicle(vehicleRaw);
        if (vehicle == null) {
            player.sendMessage(Component.text("Unknown vehicle. Try: drone", NamedTextColor.RED));
            return false;
        }
        float yaw = yawFromFace(stairs.getFacing());
        Seat seat = new Seat(key(block), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), vehicle, yaw);
        seats.put(seat.key(), seat);
        save();
        player.sendMessage(Component.text("Drone seat set → ", NamedTextColor.GREEN)
                .append(Component.text(vehicle, NamedTextColor.AQUA))
                .append(Component.text(" @ " + block.getX() + "," + block.getY() + "," + block.getZ()
                        + " (right-click stairs to pilot)", NamedTextColor.GRAY)));
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.4f);
        return true;
    }

    public boolean deleteLooking(Player player) {
        Block block = lookingStairs(player);
        if (block == null) {
            // Also allow deleting a registered seat that is no longer stairs
            RayTraceResult hit = player.rayTraceBlocks(6.0);
            if (hit == null || hit.getHitBlock() == null) {
                player.sendMessage(Component.text("Look at a drone seat first.", NamedTextColor.RED));
                return false;
            }
            block = hit.getHitBlock();
        }
        Seat seat = seats.remove(key(block));
        if (seat == null) {
            player.sendMessage(Component.text("That block is not a drone seat.", NamedTextColor.YELLOW));
            return false;
        }
        UUID pilot = occupied.remove(seat.key());
        if (pilot != null) {
            seatOfPilot.remove(pilot);
            Player online = Bukkit.getPlayer(pilot);
            if (online != null && plugin.bigDrone() != null && plugin.bigDrone().isPiloting(online)) {
                plugin.bigDrone().exit(online, "seat removed");
            }
        }
        save();
        player.sendMessage(Component.text("Drone seat removed.", NamedTextColor.GRAY));
        return true;
    }

    public void list(Player player) {
        if (seats.isEmpty()) {
            player.sendMessage(Component.text("No drone seats registered.", NamedTextColor.GRAY));
            return;
        }
        player.sendMessage(Component.text("Drone seats (" + seats.size() + "):", NamedTextColor.AQUA));
        for (Seat seat : seats.values()) {
            Component line = Component.text(" · " + seat.vehicle() + " @ "
                    + seat.world() + " " + seat.x() + "," + seat.y() + "," + seat.z(), NamedTextColor.GRAY);
            if (occupied.containsKey(seat.key())) {
                line = line.append(Component.text(" [occupied]", NamedTextColor.YELLOW));
            }
            player.sendMessage(line);
        }
    }

    /** Sit pose location on the stairs (body stays here while piloting). */
    public Location sitLocation(Seat seat) {
        World world = Bukkit.getWorld(seat.world());
        if (world == null) {
            return null;
        }
        Block block = world.getBlockAt(seat.x(), seat.y(), seat.z());
        double y = seat.y();
        if (block.getBlockData() instanceof Stairs stairs && stairs.getHalf() == Bisected.Half.TOP) {
            y += 0.5;
        }
        // Nudge slightly toward the stair face so the mannequin sits on the tread.
        double x = seat.x() + 0.5;
        double z = seat.z() + 0.5;
        BlockFace face = yawToFace(seat.yaw());
        x -= face.getModX() * 0.12;
        z -= face.getModZ() * 0.12;
        Location loc = new Location(world, x, y, z, seat.yaw(), 0f);
        return loc;
    }

    public void onSeatBroken(Block block) {
        Seat seat = seats.remove(key(block));
        if (seat == null) {
            return;
        }
        UUID pilot = occupied.remove(seat.key());
        if (pilot != null) {
            seatOfPilot.remove(pilot);
            Player online = Bukkit.getPlayer(pilot);
            if (online != null && plugin.bigDrone() != null && plugin.bigDrone().isPiloting(online)) {
                plugin.bigDrone().exit(online, "seat broken");
            }
        }
        save();
    }

    private Block lookingStairs(Player player) {
        RayTraceResult hit = player.rayTraceBlocks(6.0);
        if (hit == null || hit.getHitBlock() == null) {
            return null;
        }
        Block block = hit.getHitBlock();
        if (!(block.getBlockData() instanceof Stairs)) {
            return null;
        }
        return block;
    }

    public static String normalizeVehicle(String raw) {
        if (raw == null || raw.isBlank()) {
            return VEHICLE_MQ9;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "mq9", "mq9reaper", "mq-9", "mq-9-reaper", "reaper", "bigdrone", "drone" -> VEHICLE_MQ9;
            default -> null;
        };
    }

    public static float yawFromFace(BlockFace face) {
        return switch (face) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> -90f;
            default -> 0f;
        };
    }

    private static BlockFace yawToFace(float yaw) {
        float y = yaw % 360f;
        if (y < 0) {
            y += 360f;
        }
        if (y >= 45 && y < 135) {
            return BlockFace.WEST;
        }
        if (y >= 135 && y < 225) {
            return BlockFace.NORTH;
        }
        if (y >= 225 && y < 315) {
            return BlockFace.EAST;
        }
        return BlockFace.SOUTH;
    }

    public static String key(Block block) {
        return key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private static String key(String world, int x, int y, int z) {
        return world.toLowerCase(Locale.ROOT) + ";" + x + ";" + y + ";" + z;
    }

    private void load() {
        seats.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yaml.getConfigurationSection("seats");
        if (sec == null) {
            return;
        }
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            String world = s.getString("world");
            String vehicle = normalizeVehicle(s.getString("vehicle", VEHICLE_MQ9));
            if (world == null || vehicle == null) {
                continue;
            }
            int x = s.getInt("x");
            int y = s.getInt("y");
            int z = s.getInt("z");
            float yaw = (float) s.getDouble("yaw", 0);
            Seat seat = new Seat(key(world, x, y, z), world, x, y, z, vehicle, yaw);
            seats.put(seat.key(), seat);
        }
        plugin.getLogger().info("Loaded " + seats.size() + " drone seat(s).");
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        int i = 0;
        for (Seat seat : seats.values()) {
            String path = "seats." + (i++);
            yaml.set(path + ".world", seat.world());
            yaml.set(path + ".x", seat.x());
            yaml.set(path + ".y", seat.y());
            yaml.set(path + ".z", seat.z());
            yaml.set(path + ".vehicle", seat.vehicle());
            yaml.set(path + ".yaw", seat.yaw());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save drone_seats.yml: " + e.getMessage());
        }
    }

    public record Seat(String key, String world, int x, int y, int z, String vehicle, float yaw) {
    }
}
