package net.minenite.warzplugin;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Replaces every filled map with a full-world overview: pre-baked PNG terrain,
 * red dot for the viewer, green dots for online friends on this server.
 */
public final class WorldMapService implements Listener {

    private static final int MAP_SIZE = 128;
    private static final byte COLOR_RED;
    private static final byte COLOR_GREEN;

    static {
        COLOR_RED = MapPalette.matchColor(new Color(220, 40, 40));
        COLOR_GREEN = MapPalette.matchColor(new Color(40, 200, 60));
    }

    private final WarzPlugin plugin;
    private final Path friendsFile;
    private final double minX;
    private final double maxX;
    private final double minZ;
    private final double maxZ;
    private final byte[][] baseLayer = new byte[MAP_SIZE][MAP_SIZE];
    private final WorldMapRenderer renderer = new WorldMapRenderer();
    private final Map<UUID, CachedFriends> friendCache = new ConcurrentHashMap<>();
    private final Set<Integer> attachedMapIds = ConcurrentHashMap.newKeySet();
    private boolean loaded;

    public WorldMapService(WarzPlugin plugin) {
        this.plugin = plugin;
        String shared = plugin.getConfig().getString("shared-directory",
                Path.of("").toAbsolutePath().getParent().resolve("shared").toString());
        this.friendsFile = Path.of(shared).resolve("friends.json");
        this.minX = plugin.getConfig().getDouble("world-map.min-x", -5000);
        this.maxX = plugin.getConfig().getDouble("world-map.max-x", 5000);
        this.minZ = plugin.getConfig().getDouble("world-map.min-z", -5000);
        this.maxZ = plugin.getConfig().getDouble("world-map.max-z", 5000);
    }

    public boolean load() {
        loaded = false;
        String imageName = plugin.getConfig().getString("world-map.image", "themap.png");
        File dataImage = new File(plugin.getDataFolder(), imageName);
        if (!dataImage.exists()) {
            plugin.saveResource(imageName, false);
        }
        BufferedImage src;
        try {
            if (dataImage.exists()) {
                src = ImageIO.read(dataImage);
            } else {
                try (InputStream in = plugin.getResource(imageName)) {
                    if (in == null) {
                        plugin.getLogger().warning("World map image missing: " + imageName);
                        return false;
                    }
                    src = ImageIO.read(in);
                }
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to load world map image: " + ex.getMessage());
            return false;
        }
        if (src == null) {
            plugin.getLogger().warning("World map image unreadable: " + imageName);
            return false;
        }
        scaleIntoBase(src);
        loaded = true;
        plugin.getLogger().info("World map ready (" + src.getWidth() + "x" + src.getHeight()
                + " → " + MAP_SIZE + "x" + MAP_SIZE + ", bounds "
                + (int) minX + ".." + (int) maxX + " / " + (int) minZ + ".." + (int) maxZ + ")");
        return true;
    }

    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Gives the player a filled map bound to the world overview.
     *
     * @return true if the item was created and added to inventory (or dropped)
     */
    public boolean giveMap(Player player) {
        if (!loaded || player == null) {
            return false;
        }
        World world = player.getWorld();
        if (world == null) {
            return false;
        }
        try {
            MapView view = Bukkit.createMap(world);
            attach(view);

            ItemStack stack = new ItemStack(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) stack.getItemMeta();
            if (meta == null) {
                return false;
            }
            meta.setMapView(view);
            meta.setDisplayName(ChatColor.GOLD + "Warz Map");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Full server overview",
                    ChatColor.RED + "●" + ChatColor.GRAY + " you  "
                            + ChatColor.GREEN + "●" + ChatColor.GRAY + " friends"));
            stack.setItemMeta(meta);

            java.util.HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            return true;
        } catch (Throwable t) {
            plugin.getLogger().severe("Failed to give world map: " + t.getMessage());
            t.printStackTrace();
            return false;
        }
    }

    public void attach(MapView view) {
        if (!loaded || view == null) {
            return;
        }
        int id = view.getId();
        if (!attachedMapIds.add(id)) {
            // Still ensure our renderer is present after reloads / other plugins.
            boolean hasOurs = false;
            for (MapRenderer r : view.getRenderers()) {
                if (r == renderer) {
                    hasOurs = true;
                    break;
                }
            }
            if (hasOurs) {
                return;
            }
        }
        List<MapRenderer> old = new ArrayList<>(view.getRenderers());
        for (MapRenderer r : old) {
            view.removeRenderer(r);
        }
        view.addRenderer(renderer);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setCenterX((int) ((minX + maxX) / 2));
        view.setCenterZ((int) ((minZ + maxZ) / 2));
    }

    public void attachItem(ItemStack stack) {
        if (!loaded || stack == null || stack.getType() != Material.FILLED_MAP) {
            return;
        }
        if (!(stack.getItemMeta() instanceof MapMeta meta)) {
            return;
        }
        MapView view = meta.getMapView();
        if (view != null) {
            attach(view);
        }
    }

    private void scaleIntoBase(BufferedImage src) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        for (int y = 0; y < MAP_SIZE; y++) {
            int y0 = y * sh / MAP_SIZE;
            int y1 = Math.max(y0 + 1, (y + 1) * sh / MAP_SIZE);
            for (int x = 0; x < MAP_SIZE; x++) {
                int x0 = x * sw / MAP_SIZE;
                int x1 = Math.max(x0 + 1, (x + 1) * sw / MAP_SIZE);
                long r = 0;
                long g = 0;
                long b = 0;
                int n = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int argb = src.getRGB(sx, sy);
                        int a = (argb >>> 24) & 0xFF;
                        if (a < 16) {
                            continue;
                        }
                        r += (argb >> 16) & 0xFF;
                        g += (argb >> 8) & 0xFF;
                        b += argb & 0xFF;
                        n++;
                    }
                }
                if (n == 0) {
                    baseLayer[y][x] = MapPalette.matchColor(new Color(20, 40, 80));
                } else {
                    baseLayer[y][x] = MapPalette.matchColor(new Color(
                            (int) (r / n), (int) (g / n), (int) (b / n)));
                }
            }
        }
    }

    private int worldToMapX(double worldX) {
        if (maxX <= minX) {
            return MAP_SIZE / 2;
        }
        double t = (worldX - minX) / (maxX - minX);
        return clamp((int) Math.round(t * (MAP_SIZE - 1)), 0, MAP_SIZE - 1);
    }

    private int worldToMapY(double worldZ) {
        // Image top = north = min Z (Minecraft -Z is north when minZ < maxZ).
        if (maxZ <= minZ) {
            return MAP_SIZE / 2;
        }
        double t = (worldZ - minZ) / (maxZ - minZ);
        return clamp((int) Math.round(t * (MAP_SIZE - 1)), 0, MAP_SIZE - 1);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private Set<UUID> friendsOf(UUID playerId) {
        long now = System.currentTimeMillis();
        CachedFriends cached = friendCache.get(playerId);
        if (cached != null && now - cached.atMs < 5_000L) {
            return cached.ids;
        }
        Set<UUID> ids = readFriends(playerId);
        friendCache.put(playerId, new CachedFriends(ids, now));
        return ids;
    }

    private Set<UUID> readFriends(UUID playerId) {
        if (playerId == null || !Files.isRegularFile(friendsFile)) {
            return Set.of();
        }
        try {
            String json = Files.readString(friendsFile, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject entry = root.getAsJsonObject(playerId.toString());
            if (entry == null) {
                return Set.of();
            }
            JsonObject list = entry.getAsJsonObject("friends");
            if (list == null || list.isEmpty()) {
                return Set.of();
            }
            Set<UUID> out = new HashSet<>();
            for (Map.Entry<String, JsonElement> e : list.entrySet()) {
                try {
                    out.add(UUID.fromString(e.getKey()));
                } catch (IllegalArgumentException ignored) {
                    // skip
                }
            }
            return Collections.unmodifiableSet(out);
        } catch (Exception ex) {
            return Set.of();
        }
    }

    private static void paintDot(MapCanvas canvas, int cx, int cy, byte color) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = cx + dx;
                int y = cy + dy;
                if (x >= 0 && x < MAP_SIZE && y >= 0 && y < MAP_SIZE) {
                    canvas.setPixel(x, y, color);
                }
            }
        }
    }

    @EventHandler
    public void onMapInitialize(MapInitializeEvent event) {
        attach(event.getMap());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            attachItem(player.getInventory().getItemInMainHand());
            attachItem(player.getInventory().getItemInOffHand());
            for (ItemStack stack : player.getInventory().getContents()) {
                attachItem(stack);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        ItemStack stack = event.getPlayer().getInventory().getItem(event.getNewSlot());
        attachItem(stack);
    }

    private final class WorldMapRenderer extends MapRenderer {
        WorldMapRenderer() {
            super(true);
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            if (!loaded || player == null) {
                return;
            }
            for (int y = 0; y < MAP_SIZE; y++) {
                for (int x = 0; x < MAP_SIZE; x++) {
                    canvas.setPixel(x, y, baseLayer[y][x]);
                }
            }

            Set<UUID> friends = friendsOf(player.getUniqueId());
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.getUniqueId().equals(player.getUniqueId())) {
                    continue;
                }
                if (!friends.contains(other.getUniqueId())) {
                    continue;
                }
                paintDot(canvas,
                        worldToMapX(other.getLocation().getX()),
                        worldToMapY(other.getLocation().getZ()),
                        COLOR_GREEN);
            }

            paintDot(canvas,
                    worldToMapX(player.getLocation().getX()),
                    worldToMapY(player.getLocation().getZ()),
                    COLOR_RED);
        }
    }

    private record CachedFriends(Set<UUID> ids, long atMs) {
    }
}
