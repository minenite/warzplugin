package com.local.warz.runtime;

import com.local.warz.WarzKeys;
import net.minenite.warzplugin.WarzPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin editors for per-{@link BigDroneType} mesh pose and operator camera.
 * Mesh: {@code /warz dronepose} — yaw/pitch/roll/scale/offset.
 * Camera: {@code /warz dronecam} — sensor view XYZ (separate hotbar).
 */
public final class DroneMeshPoseService implements Listener {
    public static final String CHANNEL = "pvpgunminus:drone_mesh_pose";
    public static final String TOOL_KEY = "drone_pose_tool";

    private enum EditMode { MESH, CAMERA }

    private static final float[] ANGLE_STEPS = {0.5f, 2f, 10f};
    private static final float[] SCALE_STEPS = {0.01f, 0.05f, 0.15f};
    private static final float[] OFFSET_STEPS = {0.05f, 0.25f, 1f};

    private final WarzPlugin plugin;
    private final File file;
    private final Map<BigDroneType, DroneMeshPose> saved = new EnumMap<>(BigDroneType.class);
    private final Map<UUID, EditSession> editing = new ConcurrentHashMap<>();
    private BukkitTask cameraPreviewTask;

    public DroneMeshPoseService(WarzPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "drone-mesh-pose.yml");
        for (BigDroneType t : BigDroneType.values()) {
            saved.put(t, DroneMeshPose.identity());
        }
    }

    public void register() {
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        load();
        cameraPreviewTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickCameraPreviews, 1L, 1L);
    }

    public void unregister() {
        if (cameraPreviewTask != null) {
            cameraPreviewTask.cancel();
            cameraPreviewTask = null;
        }
        // Commit any in-progress camera/mesh drafts so a stop mid-edit doesn't discard them.
        for (UUID id : editing.keySet().toArray(new UUID[0])) {
            EditSession session = editing.get(id);
            if (session != null) {
                saved.put(session.type, new DroneMeshPose(session.draft));
            }
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                cancel(p, false);
            }
        }
        editing.clear();
        saveFile();
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    public void load() {
        for (BigDroneType t : BigDroneType.values()) {
            saved.put(t, DroneMeshPose.identity());
        }
        if (!file.exists()) {
            saveFile();
            broadcastAll();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int loaded = 0;
        for (BigDroneType t : BigDroneType.values()) {
            DroneMeshPose pose = DroneMeshPose.identity();
            pose.load(yaml.getConfigurationSection(t.id()));
            saved.put(t, pose);
            if (Math.abs(pose.camX) + Math.abs(pose.camY) + Math.abs(pose.camZ) > 1e-4f
                    || Math.abs(pose.yaw) + Math.abs(pose.pitch) + Math.abs(pose.roll) > 1e-4f
                    || Math.abs(pose.scaleMul - 1f) > 1e-4f
                    || Math.abs(pose.offX) + Math.abs(pose.offY) + Math.abs(pose.offZ) > 1e-4f) {
                loaded++;
            }
        }
        plugin.getLogger().info("Loaded drone mesh/camera poses from " + file.getName()
                + " (" + loaded + "/" + BigDroneType.values().length + " tuned).");
        broadcastAll();
    }

    public void saveFile() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (BigDroneType t : BigDroneType.values()) {
            saved.get(t).save(yaml.createSection(t.id()));
        }
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            yaml.save(tmp);
            if (file.exists() && !file.delete() && file.exists()) {
                plugin.getLogger().warning("Could not replace " + file.getName() + " (delete failed).");
            }
            if (!tmp.renameTo(file)) {
                // Fallback: copy via YamlConfiguration save directly
                yaml.save(file);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save drone-mesh-pose.yml: " + ex.getMessage());
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Effective pose for rendering (draft while someone edits that type). */
    public DroneMeshPose effective(BigDroneType type) {
        if (type == null) {
            type = BigDroneType.MQ9;
        }
        for (EditSession s : editing.values()) {
            if (s.type == type) {
                return s.draft;
            }
        }
        return saved.getOrDefault(type, DroneMeshPose.identity());
    }

    public DroneMeshPose saved(BigDroneType type) {
        return new DroneMeshPose(saved.getOrDefault(type, DroneMeshPose.identity()));
    }

    public boolean isEditing(Player player) {
        return player != null && editing.containsKey(player.getUniqueId());
    }

    public void begin(Player player, BigDroneType type) {
        begin(player, type, EditMode.MESH);
    }

    /** Operator sensor-camera editor ({@code /warz dronecam}). */
    public void beginCamera(Player player, BigDroneType type) {
        begin(player, type, EditMode.CAMERA);
    }

    private void begin(Player player, BigDroneType type, EditMode mode) {
        if (player == null || type == null || mode == null) {
            return;
        }
        if (editing.containsKey(player.getUniqueId())) {
            cancel(player, false);
        }
        // Only one editor per type (keeps draft unambiguous).
        for (Map.Entry<UUID, EditSession> e : editing.entrySet()) {
            if (e.getValue().type == type) {
                Player other = Bukkit.getPlayer(e.getKey());
                player.sendMessage(Component.text(
                        (other != null ? other.getName() : "Someone") + " is already editing "
                                + type.displayName() + ".", NamedTextColor.RED));
                return;
            }
        }
        ItemStack[] stash = player.getInventory().getContents();
        ItemStack[] stashCopy = new ItemStack[stash.length];
        for (int i = 0; i < stash.length; i++) {
            stashCopy[i] = stash[i] == null ? null : stash[i].clone();
        }
        GameMode prevMode = player.getGameMode();
        Location anchor = resolveAnchor(player, type);
        EditSession session = new EditSession(mode, type, new DroneMeshPose(saved.get(type)),
                stashCopy, prevMode, anchor);
        editing.put(player.getUniqueId(), session);
        giveTools(player, session);
        if (mode == EditMode.CAMERA) {
            enterCameraPreview(player, session);
            player.sendMessage(Component.text("Operator camera editor: ", NamedTextColor.GOLD)
                    .append(Component.text(type.displayName(), NamedTextColor.YELLOW)));
            player.sendMessage(Component.text(
                    "Hotbar: forward / height / left-right  |  LMB − / RMB +  |  sneak = fine",
                    NamedTextColor.GRAY));
            if (session.livePilot) {
                player.sendMessage(Component.text(
                        "Live flight — sensor camera updates as you nudge.",
                        NamedTextColor.DARK_AQUA));
            } else {
                player.sendMessage(Component.text(
                        "Mounted in sensor preview — look around; tools nudge the view live.",
                        NamedTextColor.DARK_AQUA));
            }
        } else {
            player.sendMessage(Component.text("Mesh pose editor: ", NamedTextColor.GOLD)
                    .append(Component.text(type.displayName(), NamedTextColor.YELLOW)));
            player.sendMessage(Component.text(
                    "Hotbar tools: LMB − / RMB +  |  sneak = fine  |  Step tool cycles size",
                    NamedTextColor.GRAY));
            player.sendMessage(Component.text(
                    "Camera is separate: /warz dronecam <type>",
                    NamedTextColor.DARK_GRAY));
        }
        player.sendMessage(Component.text(
                "Lime = SAVE   |   Barrier = CANCEL",
                NamedTextColor.GREEN));
        tip(player, session);
        broadcastAll();
        if (plugin.bigDrone() != null) {
            plugin.bigDrone().forceVisBroadcast();
        }
    }

    public void copy(org.bukkit.command.CommandSender sender, BigDroneType from, BigDroneType to) {
        if (from == null || to == null || from == to) {
            sender.sendMessage(Component.text("Usage: /warz dronepose copy <from> <to>", NamedTextColor.RED));
            return;
        }
        DroneMeshPose src = saved.get(from);
        DroneMeshPose dst = new DroneMeshPose(src);
        // Keep destination camera when copying mesh-only.
        DroneMeshPose old = saved.get(to);
        if (old != null) {
            dst.camX = old.camX;
            dst.camY = old.camY;
            dst.camZ = old.camZ;
        }
        saved.put(to, dst);
        for (EditSession s : editing.values()) {
            if (s.type == to && s.mode == EditMode.MESH) {
                float cx = s.draft.camX;
                float cy = s.draft.camY;
                float cz = s.draft.camZ;
                s.draft = new DroneMeshPose(dst);
                s.draft.camX = cx;
                s.draft.camY = cy;
                s.draft.camZ = cz;
            }
        }
        saveFile();
        broadcastAll();
        sender.sendMessage(Component.text("Copied mesh pose ", NamedTextColor.GREEN)
                .append(Component.text(from.id() + " → " + to.id(), NamedTextColor.YELLOW))
                .append(Component.text(" (camera unchanged)", NamedTextColor.DARK_GRAY)));
        if (plugin.bigDrone() != null) {
            plugin.bigDrone().forceVisBroadcast();
        }
    }

    /** Copy only operator-camera offsets between airframes. */
    public void copyCamera(org.bukkit.command.CommandSender sender, BigDroneType from, BigDroneType to) {
        if (from == null || to == null || from == to) {
            sender.sendMessage(Component.text("Usage: /warz dronecam copy <from> <to>", NamedTextColor.RED));
            return;
        }
        DroneMeshPose src = saved.get(from);
        DroneMeshPose dst = saved.get(to);
        if (dst == null) {
            dst = DroneMeshPose.identity();
        } else {
            dst = new DroneMeshPose(dst);
        }
        dst.camX = src.camX;
        dst.camY = src.camY;
        dst.camZ = src.camZ;
        saved.put(to, dst);
        for (EditSession s : editing.values()) {
            if (s.type == to) {
                s.draft.camX = src.camX;
                s.draft.camY = src.camY;
                s.draft.camZ = src.camZ;
            }
        }
        saveFile();
        broadcastAll();
        sender.sendMessage(Component.text("Copied operator camera ", NamedTextColor.GREEN)
                .append(Component.text(from.id() + " → " + to.id(), NamedTextColor.YELLOW)));
        if (plugin.bigDrone() != null) {
            plugin.bigDrone().forceVisBroadcast();
        }
    }

    public void list(org.bukkit.command.CommandSender sender) {
        sender.sendMessage(Component.text("---- drone mesh poses ----", NamedTextColor.GOLD));
        for (BigDroneType t : BigDroneType.values()) {
            DroneMeshPose p = effective(t);
            boolean draft = false;
            for (EditSession s : editing.values()) {
                if (s.type == t && s.mode == EditMode.MESH) {
                    draft = true;
                    break;
                }
            }
            sender.sendMessage(Component.text(t.id(), NamedTextColor.YELLOW)
                    .append(Component.text((draft ? " [editing] " : " ") + meshSummary(p), NamedTextColor.GRAY)));
        }
    }

    public void listCamera(org.bukkit.command.CommandSender sender) {
        sender.sendMessage(Component.text("---- drone operator cameras ----", NamedTextColor.GOLD));
        for (BigDroneType t : BigDroneType.values()) {
            DroneMeshPose p = effective(t);
            boolean draft = false;
            for (EditSession s : editing.values()) {
                if (s.type == t && s.mode == EditMode.CAMERA) {
                    draft = true;
                    break;
                }
            }
            sender.sendMessage(Component.text(t.id(), NamedTextColor.YELLOW)
                    .append(Component.text((draft ? " [editing] " : " ") + camSummary(p), NamedTextColor.GRAY)));
        }
    }

    public boolean save(Player player) {
        EditSession session = editing.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        saved.put(session.type, new DroneMeshPose(session.draft));
        saveFile();
        leaveCameraPreview(player, session);
        restoreInventory(player, session);
        editing.remove(player.getUniqueId());
        if (session.mode == EditMode.CAMERA) {
            player.sendMessage(Component.text("Saved operator camera for ", NamedTextColor.GREEN)
                    .append(Component.text(session.type.displayName(), NamedTextColor.YELLOW)));
            player.sendMessage(Component.text(camSummary(session.draft), NamedTextColor.GRAY));
        } else {
            player.sendMessage(Component.text("Saved mesh pose for ", NamedTextColor.GREEN)
                    .append(Component.text(session.type.displayName(), NamedTextColor.YELLOW)));
            player.sendMessage(Component.text(meshSummary(session.draft), NamedTextColor.GRAY));
        }
        broadcastAll();
        if (plugin.bigDrone() != null) {
            plugin.bigDrone().forceVisBroadcast();
        }
        return true;
    }

    public boolean cancel(Player player, boolean message) {
        EditSession session = editing.remove(player.getUniqueId());
        if (session == null) {
            return false;
        }
        leaveCameraPreview(player, session);
        restoreInventory(player, session);
        if (message) {
            String kind = session.mode == EditMode.CAMERA ? "operator camera" : "mesh pose";
            player.sendMessage(Component.text("Cancelled " + kind + " edit for ", NamedTextColor.RED)
                    .append(Component.text(session.type.displayName(), NamedTextColor.YELLOW)));
        }
        broadcastAll();
        if (plugin.bigDrone() != null) {
            plugin.bigDrone().forceVisBroadcast();
        }
        return true;
    }

    public boolean isCameraEditing(Player player) {
        EditSession s = player == null ? null : editing.get(player.getUniqueId());
        return s != null && s.mode == EditMode.CAMERA;
    }

    /** True while {@code /warz dronecam} has the player cloaked in the airframe sensor seat. */
    public boolean isCameraPreviewMounted(Player player) {
        EditSession s = player == null ? null : editing.get(player.getUniqueId());
        return s != null && s.mode == EditMode.CAMERA && s.mountedPreview;
    }

    /** True when a camera-editor preview is seated on this parked pad's airframe. */
    public boolean isCameraPreviewNearPad(DronePadService.ParkedPad pad) {
        if (pad == null || plugin.dronePads() == null) {
            return false;
        }
        Location padLoc = plugin.dronePads().airframeLocation(pad);
        if (padLoc == null || padLoc.getWorld() == null) {
            return false;
        }
        BigDroneType padType = plugin.dronePads().typeOf(pad);
        for (Map.Entry<UUID, EditSession> e : editing.entrySet()) {
            EditSession s = e.getValue();
            if (s.mode != EditMode.CAMERA || !s.mountedPreview || s.type != padType) {
                continue;
            }
            Player editor = Bukkit.getPlayer(e.getKey());
            Location seat = editor != null ? editor.getLocation() : s.anchor;
            if (seat == null || seat.getWorld() != padLoc.getWorld()) {
                continue;
            }
            if (seat.distanceSquared(padLoc) < 36.0) { // within 6 blocks
                return true;
            }
        }
        return false;
    }

    public boolean isMeshEditing(Player player) {
        EditSession s = player == null ? null : editing.get(player.getUniqueId());
        return s != null && s.mode == EditMode.MESH;
    }

    public void syncViewer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        // Always push on hello/join — do not gate on companion TTL (race during handshake).
        byte[] payload = encode();
        if (payload != null) {
            player.sendPluginMessage(plugin, CHANNEL, payload);
        }
    }

    public void broadcastAll() {
        byte[] payload = encode();
        if (payload == null || plugin.companions() == null) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.companions().hasCompanion(p)) {
                p.sendPluginMessage(plugin, CHANNEL, payload);
            }
        }
    }

    /** Preview row for drone_vis while editing (null if none for this player). */
    public PreviewAirframe previewForBroadcast() {
        // One preview per editor (first is enough; rare concurrent).
        for (Map.Entry<UUID, EditSession> e : editing.entrySet()) {
            EditSession s = e.getValue();
            Player editor = Bukkit.getPlayer(e.getKey());
            // Live pilots already appear in drone_vis via their flight session.
            if (s.livePilot) {
                continue;
            }
            Location a = s.anchor;
            if (editor != null && s.mountedPreview) {
                a = editor.getLocation();
            }
            if (a == null || a.getWorld() == null) {
                continue;
            }
            float yaw = s.bodyYaw;
            return new PreviewAirframe(e.getKey(), a.getX(), a.getY(), a.getZ(), yaw, s.type);
        }
        return null;
    }

    public record PreviewAirframe(UUID id, double x, double y, double z, float yaw, BigDroneType type) {
    }

    private byte[] encode() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(2); // protocol — + cam X/Y/Z
            BigDroneType[] types = BigDroneType.values();
            out.writeByte(types.length);
            for (BigDroneType t : types) {
                out.writeByte(t.ordinal());
                DroneMeshPose p = effective(t);
                out.writeFloat(p.yaw);
                out.writeFloat(p.pitch);
                out.writeFloat(p.roll);
                out.writeFloat(p.scaleMul);
                out.writeFloat(p.offX);
                out.writeFloat(p.offY);
                out.writeFloat(p.offZ);
                out.writeFloat(p.camX);
                out.writeFloat(p.camY);
                out.writeFloat(p.camZ);
            }
            PreviewAirframe prev = previewForBroadcast();
            if (prev != null) {
                out.writeByte(1);
                out.writeLong(prev.id.getMostSignificantBits());
                out.writeLong(prev.id.getLeastSignificantBits());
                out.writeByte(prev.type.ordinal());
                out.writeDouble(prev.x);
                out.writeDouble(prev.y);
                out.writeDouble(prev.z);
                out.writeFloat(prev.yaw);
            } else {
                out.writeByte(0);
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException ex) {
            return null;
        }
    }

    private Location resolveAnchor(Player player, BigDroneType type) {
        Location best = null;
        double bestD = 48 * 48;
        if (plugin.dronePads() != null) {
            for (DronePadService.ParkedPad pad : plugin.dronePads().parkedForVis()) {
                if (plugin.dronePads().typeOf(pad) != type) {
                    continue;
                }
                Location loc = plugin.dronePads().airframeLocation(pad);
                if (loc == null || loc.getWorld() != player.getWorld()) {
                    continue;
                }
                double d = loc.distanceSquared(player.getLocation());
                if (d < bestD) {
                    bestD = d;
                    best = loc.clone();
                }
            }
        }
        if (best != null) {
            return best;
        }
        Location feet = player.getLocation().clone();
        Vector look = feet.getDirection().setY(0);
        if (look.lengthSquared() < 1e-4) {
            look = new Vector(0, 0, 1);
        } else {
            look.normalize();
        }
        feet.add(look.multiply(6));
        feet.setY(Math.floor(feet.getY()) + 0.05);
        return feet;
    }

    private void giveTools(Player player, EditSession session) {
        PlayerInventory inv = player.getInventory();
        inv.clear();
        if (session.mode == EditMode.CAMERA) {
            inv.setItem(0, tool(Material.TARGET, "Cam forward", "camz", NamedTextColor.DARK_AQUA));
            inv.setItem(1, tool(Material.SCAFFOLDING, "Cam height", "camy", NamedTextColor.BLUE));
            inv.setItem(2, tool(Material.POWERED_RAIL, "Cam left/right", "camx", NamedTextColor.WHITE));
            inv.setItem(3, tool(Material.REPEATER, "Step size", "step", NamedTextColor.GRAY));
            inv.setItem(4, tool(Material.STRUCTURE_VOID, "Reset camera", "reset", NamedTextColor.DARK_RED));
            inv.setItem(7, named(Material.LIME_CONCRETE, "SAVE camera", NamedTextColor.GREEN));
            inv.setItem(8, named(Material.BARRIER, "CANCEL edit", NamedTextColor.RED));
        } else {
            inv.setItem(0, tool(Material.COMPASS, "Yaw", "yaw", NamedTextColor.YELLOW));
            inv.setItem(1, tool(Material.FEATHER, "Pitch", "pitch", NamedTextColor.AQUA));
            inv.setItem(2, tool(Material.BLAZE_ROD, "Roll", "roll", NamedTextColor.GOLD));
            inv.setItem(3, tool(Material.SLIME_BALL, "Scale", "scale", NamedTextColor.GREEN));
            inv.setItem(4, tool(Material.GOLD_NUGGET, "Height (Y)", "offy", NamedTextColor.LIGHT_PURPLE));
            inv.setItem(5, tool(Material.IRON_NUGGET, "Offset X", "offx", NamedTextColor.WHITE));
            inv.setItem(6, tool(Material.COPPER_INGOT, "Offset Z", "offz", NamedTextColor.RED));
            inv.setItem(7, tool(Material.REPEATER, "Step size", "step", NamedTextColor.GRAY));
            inv.setItem(8, tool(Material.STRUCTURE_VOID, "Reset mesh", "reset", NamedTextColor.DARK_RED));
            inv.setItem(9, named(Material.LIME_CONCRETE, "SAVE pose", NamedTextColor.GREEN));
            inv.setItem(10, named(Material.BARRIER, "CANCEL edit", NamedTextColor.RED));
        }
        inv.setHeldItemSlot(0);
        tip(player, session);
    }

    private static ItemStack tool(Material mat, String title, String id, NamedTextColor color) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(title, color).decoration(TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                Component.text("LMB −  /  RMB +", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Sneak = fine step", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(WarzKeys.of(TOOL_KEY), PersistentDataType.STRING, id);
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack named(Material mat, String title, NamedTextColor color) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(title, color).decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(WarzKeys.of(TOOL_KEY), PersistentDataType.STRING, "block");
        stack.setItemMeta(meta);
        return stack;
    }

    private void restoreInventory(Player player, EditSession session) {
        player.getInventory().setContents(session.stash);
        player.updateInventory();
    }

    private void tip(Player player, EditSession session) {
        String body = session.mode == EditMode.CAMERA
                ? camSummary(session.draft)
                : meshSummary(session.draft);
        player.sendActionBar(Component.text(session.type.displayName() + "  " + body
                + "  step[" + session.stepIndex + "]", NamedTextColor.AQUA));
    }

    private static String meshSummary(DroneMeshPose p) {
        return String.format(Locale.ROOT,
                "yaw %.1f° pitch %.1f° roll %.1f° scale %.3f off(%.2f,%.2f,%.2f)",
                p.yaw, p.pitch, p.roll, p.scaleMul, p.offX, p.offY, p.offZ);
    }

    private static String camSummary(DroneMeshPose p) {
        return String.format(Locale.ROOT, "cam(%.2f, %.2f, %.2f)  [right, up, forward]",
                p.camX, p.camY, p.camZ);
    }

    private String toolId(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(WarzKeys.of(TOOL_KEY), PersistentDataType.STRING);
    }

    private void nudge(Player player, EditSession session, String tool, int sign) {
        float fine = player.isSneaking() ? 0.25f : 1f;
        DroneMeshPose d = session.draft;
        boolean camera = session.mode == EditMode.CAMERA;
        switch (tool) {
            case "yaw" -> {
                if (camera) {
                    return;
                }
                d.yaw += sign * ANGLE_STEPS[session.stepIndex] * fine;
            }
            case "pitch" -> {
                if (camera) {
                    return;
                }
                d.pitch += sign * ANGLE_STEPS[session.stepIndex] * fine;
            }
            case "roll" -> {
                if (camera) {
                    return;
                }
                d.roll += sign * ANGLE_STEPS[session.stepIndex] * fine;
            }
            case "scale" -> {
                if (camera) {
                    return;
                }
                d.scaleMul += sign * SCALE_STEPS[session.stepIndex] * fine;
                d.scaleMul = Math.max(0.15f, Math.min(4f, d.scaleMul));
            }
            case "offx" -> {
                if (camera) {
                    return;
                }
                d.offX += sign * OFFSET_STEPS[session.stepIndex] * fine;
            }
            case "offy" -> {
                if (camera) {
                    return;
                }
                d.offY += sign * OFFSET_STEPS[session.stepIndex] * fine;
            }
            case "offz" -> {
                if (camera) {
                    return;
                }
                d.offZ += sign * OFFSET_STEPS[session.stepIndex] * fine;
            }
            case "camx" -> {
                if (!camera) {
                    return;
                }
                d.camX += sign * OFFSET_STEPS[session.stepIndex] * fine;
            }
            case "camy" -> {
                if (!camera) {
                    return;
                }
                d.camY += sign * OFFSET_STEPS[session.stepIndex] * fine;
            }
            case "camz" -> {
                if (!camera) {
                    return;
                }
                d.camZ += sign * OFFSET_STEPS[session.stepIndex] * fine;
            }
            case "step" -> {
                session.stepIndex = (session.stepIndex + (sign > 0 ? 1 : ANGLE_STEPS.length - 1)) % ANGLE_STEPS.length;
                if (camera) {
                    player.sendMessage(Component.text(String.format(Locale.ROOT,
                            "Step: camera offset %.2f", OFFSET_STEPS[session.stepIndex]),
                            NamedTextColor.GRAY));
                } else {
                    player.sendMessage(Component.text(String.format(Locale.ROOT,
                            "Step: angle %.1f°  scale %.2f  offset %.2f",
                            ANGLE_STEPS[session.stepIndex], SCALE_STEPS[session.stepIndex],
                            OFFSET_STEPS[session.stepIndex]),
                            NamedTextColor.GRAY));
                }
            }
            case "reset" -> {
                if (camera) {
                    d.camX = 0;
                    d.camY = 0;
                    d.camZ = 0;
                    player.sendMessage(Component.text("Camera draft reset to default sensor point.", NamedTextColor.YELLOW));
                } else {
                    float cx = d.camX;
                    float cy = d.camY;
                    float cz = d.camZ;
                    session.draft = DroneMeshPose.identity();
                    session.draft.camX = cx;
                    session.draft.camY = cy;
                    session.draft.camZ = cz;
                    player.sendMessage(Component.text("Mesh draft reset (camera kept).", NamedTextColor.YELLOW));
                }
            }
            default -> {
                return;
            }
        }
        tip(player, session);
        broadcastAll();
        if (session.mode == EditMode.CAMERA) {
            pushCameraEditHud(player, session);
        }
        if (plugin.bigDrone() != null) {
            plugin.bigDrone().forceVisBroadcast();
        }
    }

    private void pushCameraEditHud(Player player, EditSession session) {
        if (player == null || session == null || session.mode != EditMode.CAMERA || plugin.bigDrone() == null) {
            return;
        }
        DroneMeshPose d = session.draft;
        plugin.bigDrone().sendCameraEditHud(player, session.type, session.bodyYaw,
                d.camX, d.camY, d.camZ);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        EditSession session = editing.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        boolean left = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        if (!left && !right) {
            return;
        }

        if (right && event.getClickedBlock() != null) {
            Material mat = event.getClickedBlock().getType();
            if (mat == Material.LIME_CONCRETE || mat == Material.LIME_WOOL || mat == Material.EMERALD_BLOCK) {
                event.setCancelled(true);
                save(player);
                return;
            }
            if (mat == Material.BARRIER) {
                event.setCancelled(true);
                cancel(player, true);
                return;
            }
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        String tool = toolId(hand);
        if (tool == null) {
            return;
        }
        // Hotbar SAVE / CANCEL items (lime / barrier) — click anywhere to commit.
        if ("block".equals(tool)) {
            Material handMat = hand.getType();
            if (handMat == Material.LIME_CONCRETE || handMat == Material.LIME_WOOL) {
                event.setCancelled(true);
                save(player);
            } else if (handMat == Material.BARRIER) {
                event.setCancelled(true);
                cancel(player, true);
            }
            return;
        }
        event.setCancelled(true);
        nudge(player, session, tool, left ? -1 : 1);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isEditing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p) || !isEditing(p)) {
            return;
        }
        // Keep lime/barrier reachable for placing; block dumping tools into chests.
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(p.getInventory())) {
            return;
        }
        if (event.getClick().isKeyboardClick()) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player p) || !isEditing(p)) {
            return;
        }
        if (event.getInventory().equals(p.getInventory())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer(), false);
    }

    private void enterCameraPreview(Player player, EditSession session) {
        if (player == null || session == null || session.mode != EditMode.CAMERA) {
            return;
        }
        session.bodyYaw = session.anchor != null ? session.anchor.getYaw() : player.getLocation().getYaw();
        // Already flying this airframe — stay in the real operator seat.
        if (plugin.bigDrone() != null && plugin.bigDrone().isPiloting(player)
                && plugin.bigDrone().typeOf(player) == session.type) {
            session.livePilot = true;
            session.mountedPreview = false;
            return;
        }
        session.returnLoc = player.getLocation().clone();
        session.savedAllowFlight = player.getAllowFlight();
        session.savedFlying = player.isFlying();
        session.savedFlySpeed = player.getFlySpeed();
        session.mountedPreview = true;
        session.livePilot = false;

        Location seat = session.anchor.clone();
        // Parked pad feet — same Y as operator enter / drone_vis parked mesh.
        seat.setYaw(session.bodyYaw);
        seat.setPitch(8f);
        session.anchor = seat.clone();
        player.teleport(seat);
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setFlySpeed(0.01f);
        player.setCollidable(false);
        player.setInvisible(true);
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY, 80, 0, false, false, false));
        player.addScoreboardTag(BigDroneService.PILOT_TAG);
        // Drop hull Interaction so RMB can't open the payload bay mid-edit.
        if (plugin.dronePads() != null) {
            for (DronePadService.ParkedPad pad : plugin.dronePads().parkedForVis()) {
                if (isCameraPreviewNearPad(pad)) {
                    plugin.dronePads().detachInteract(pad);
                }
            }
        }
        pushCameraEditHud(player, session);
    }

    private void leaveCameraPreview(Player player, EditSession session) {
        if (player == null || session == null || session.mode != EditMode.CAMERA) {
            return;
        }
        if (session.livePilot) {
            // Flight session owns cloak / HUD — do not strip.
            session.livePilot = false;
            return;
        }
        if (!session.mountedPreview) {
            return;
        }
        session.mountedPreview = false;
        player.removeScoreboardTag(BigDroneService.PILOT_TAG);
        player.setInvisible(false);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.setCollidable(true);
        player.setFlySpeed(session.savedFlySpeed > 0 ? session.savedFlySpeed : 0.1f);
        player.setAllowFlight(session.savedAllowFlight);
        player.setFlying(session.savedAllowFlight && session.savedFlying);
        if (session.prevMode != null) {
            player.setGameMode(session.prevMode);
        }
        if (session.returnLoc != null && session.returnLoc.getWorld() != null) {
            player.teleport(session.returnLoc);
        }
        if (plugin.bigDrone() != null) {
            plugin.bigDrone().clearPilotInvisibility(player);
            plugin.bigDrone().sendHudClear(player);
        }
    }

    private void tickCameraPreviews() {
        boolean anyMounted = false;
        for (Map.Entry<UUID, EditSession> e : editing.entrySet()) {
            EditSession s = e.getValue();
            if (s.mode != EditMode.CAMERA) {
                continue;
            }
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) {
                continue;
            }
            // Keep draft cam XYZ on the HUD every tick so the companion view moves live.
            if (plugin.getServer().getCurrentTick() % 2 == 0) {
                pushCameraEditHud(p, s);
            }
            if (s.livePilot) {
                continue;
            }
            if (!s.mountedPreview || s.anchor == null) {
                continue;
            }
            anyMounted = true;
            Location want = s.anchor.clone();
            want.setYaw(p.getLocation().getYaw());
            want.setPitch(p.getLocation().getPitch());
            Location cur = p.getLocation();
            if (cur.getWorld() != want.getWorld()
                    || cur.distanceSquared(want) > 0.04
                    || Math.abs(cur.getY() - want.getY()) > 0.05) {
                p.teleport(want);
            }
            p.setAllowFlight(true);
            p.setFlying(true);
            p.setInvisible(true);
            p.addPotionEffect(new PotionEffect(
                    PotionEffectType.INVISIBILITY, 40, 0, false, false, false));
            if (!p.getScoreboardTags().contains(BigDroneService.PILOT_TAG)) {
                p.addScoreboardTag(BigDroneService.PILOT_TAG);
            }
        }
        if (anyMounted && plugin.getServer().getCurrentTick() % 2 == 0 && plugin.bigDrone() != null) {
            plugin.bigDrone().forceVisBroadcast();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        EditSession session = editing.get(event.getPlayer().getUniqueId());
        if (session == null || session.mode != EditMode.CAMERA || !session.mountedPreview) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        // Lock body to the airframe; allow look yaw/pitch only.
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            to.setX(session.anchor.getX());
            to.setY(session.anchor.getY());
            to.setZ(session.anchor.getZ());
            event.setTo(to);
        }
    }

    private static final class EditSession {
        final EditMode mode;
        final BigDroneType type;
        DroneMeshPose draft;
        final ItemStack[] stash;
        final GameMode prevMode;
        Location anchor;
        int stepIndex = 1;
        /** Teleported into airframe sensor seat for live preview. */
        boolean mountedPreview;
        /** Already piloting this type — use real flight camera. */
        boolean livePilot;
        Location returnLoc;
        boolean savedAllowFlight;
        boolean savedFlying;
        float savedFlySpeed = 0.1f;
        float bodyYaw;

        EditSession(EditMode mode, BigDroneType type, DroneMeshPose draft, ItemStack[] stash,
                    GameMode prevMode, Location anchor) {
            this.mode = mode;
            this.type = type;
            this.draft = draft;
            this.stash = stash;
            this.prevMode = prevMode;
            this.anchor = anchor;
            this.bodyYaw = anchor != null ? anchor.getYaw() : 0f;
        }
    }
}
