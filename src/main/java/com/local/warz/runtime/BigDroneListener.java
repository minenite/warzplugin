package com.local.warz.runtime;

import com.local.warz.WarzKeys;
import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Optional;
import java.util.UUID;

public final class BigDroneListener implements Listener, PluginMessageListener {
    private final WarzPlugin plugin;

    public BigDroneListener(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerChannel() {
        var messenger = plugin.getServer().getMessenger();
        messenger.registerIncomingPluginChannel(plugin, BigDroneService.CHANNEL_OPTIC, this);
        messenger.registerIncomingPluginChannel(plugin, BigDroneService.CHANNEL_ADJUST, this);
        messenger.registerOutgoingPluginChannel(plugin, BigDroneService.CHANNEL_ZOOM);
        messenger.registerOutgoingPluginChannel(plugin, BigDroneService.CHANNEL_HUD);
        messenger.registerOutgoingPluginChannel(plugin, BigDroneService.CHANNEL_DRONE_VIS);
        messenger.registerOutgoingPluginChannel(plugin, BigDroneService.CHANNEL_DRONE_HIT);
    }

    public void unregisterChannel() {
        var messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin, BigDroneService.CHANNEL_OPTIC, this);
        messenger.unregisterIncomingPluginChannel(plugin, BigDroneService.CHANNEL_ADJUST, this);
        messenger.unregisterOutgoingPluginChannel(plugin, BigDroneService.CHANNEL_ZOOM);
        messenger.unregisterOutgoingPluginChannel(plugin, BigDroneService.CHANNEL_HUD);
        messenger.unregisterOutgoingPluginChannel(plugin, BigDroneService.CHANNEL_DRONE_VIS);
        messenger.unregisterOutgoingPluginChannel(plugin, BigDroneService.CHANNEL_DRONE_HIT);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (BigDroneService.CHANNEL_OPTIC.equals(channel)) {
            if (plugin.bigDrone().isPiloting(player)) {
                plugin.bigDrone().cycleOptics(player);
            }
            return;
        }
        if (!BigDroneService.CHANNEL_ADJUST.equals(channel) || message == null || message.length < 3) {
            return;
        }
        // protocol, kind, signed delta
        byte kind = message[1];
        int delta = message[2];
        plugin.bigDrone().handleAdjust(player, kind, delta);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        Action action = event.getAction();
        BigDroneService drones = plugin.bigDrone();

        if (drones.isPiloting(player)) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            Optional<String> ctrl = plugin.items().droneControlId(hand);
            boolean left = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
            boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
            // Always eat clicks while piloting — otherwise Interaction catchers / blocks can open the bay.
            if (left || right) {
                event.setCancelled(true);
            }
            if (ctrl.isPresent()) {
                if (left || right) {
                    drones.handleControlUse(player, ctrl.get(), left);
                }
                return;
            }
            if (right && player.isSneaking()) {
                drones.exit(player, "manual");
            }
            return;
        }

        boolean left = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;

        ItemStack hand = player.getInventory().getItemInMainHand();
        Block clicked = event.getClickedBlock();

        // LMB on parked hull does nothing (pack via payload bay item). Mesh OBB only.
        if (left && plugin.dronePads() != null) {
            if (drones.rayTraceParkedPad(player, BigDroneService.PARKED_USE_RANGE).isPresent()) {
                event.setCancelled(true);
                return;
            }
        }

        if (!right) {
            return;
        }

        if (action == Action.RIGHT_CLICK_BLOCK && clicked != null
                && plugin.items().isBigDroneItem(hand)) {
            event.setCancelled(true);
            drones.tryPlace(player, clicked, event.getBlockFace());
            return;
        }

        // Radiolink → seat completion wins when clicking the chair.
        if (plugin.items().isRadiolink(hand)
                && clicked != null
                && plugin.droneSeats() != null
                && plugin.droneSeats().isSeat(clicked)
                && plugin.dronePads() != null) {
            event.setCancelled(true);
            if (!plugin.dronePads().tryCompleteRadiolink(player, clicked)) {
                player.sendMessage(net.kyori.adventure.text.Component.text(
                        "Right-click a drone with Radiolink first, then the seat.",
                        net.kyori.adventure.text.format.NamedTextColor.YELLOW));
            }
            return;
        }

        // Radiolink pending: pad selected, now pick a seat.
        if (plugin.dronePads() != null && clicked != null
                && plugin.dronePads().tryCompleteRadiolink(player, clicked)) {
            event.setCancelled(true);
            return;
        }

        // Datalink tower → seat / satellite → tower
        if (plugin.datalink() != null && clicked != null) {
            if (plugin.datalink().hasPendingSeatLink(player.getUniqueId())
                    && plugin.droneSeats() != null && plugin.droneSeats().isSeat(clicked)) {
                event.setCancelled(true);
                plugin.datalink().tryCompleteSeatLink(player, clicked);
                return;
            }
            if (plugin.datalink().hasPendingSatLink(player.getUniqueId())
                    && plugin.datalink().towerAt(clicked).isPresent()) {
                event.setCancelled(true);
                plugin.datalink().tryCompleteSatelliteLink(player, clicked);
                return;
            }
        }

        // Mesh OBB + arm's-reach only — not long-range / empty air around the catcher.
        Optional<DronePadService.ParkedPad> frame =
                drones.rayTraceParkedPad(player, BigDroneService.PARKED_USE_RANGE);
        if (frame.isPresent() && plugin.dronePads() != null
                && drones.canUseParkedHull(player, frame.get())) {
            handleParkedFrameUse(player, hand, frame.get());
            event.setCancelled(true);
            return;
        }

        // Radiolink in hand, not on pad/seat/frame — link manager.
        if (plugin.items().isRadiolink(hand) && plugin.dronePads() != null) {
            boolean onSeat = clicked != null && plugin.droneSeats() != null && plugin.droneSeats().isSeat(clicked);
            if (!onSeat) {
                event.setCancelled(true);
                plugin.dronePads().openLinkManager(player);
                return;
            }
        }

        if (action == Action.RIGHT_CLICK_BLOCK && clicked != null
                && plugin.droneSeats() != null && plugin.droneSeats().isSeat(clicked)) {
            event.setCancelled(true);
            drones.tryEnterSeat(player, clicked);
        }
    }

    /** Survival left-click air is unreliable — arm swing while holding LAW cycles the bay. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.bigDrone().isPiloting(player)) {
            return;
        }
        Optional<String> ctrl = plugin.items().droneControlId(player.getInventory().getItemInMainHand());
        if (ctrl.isPresent() && "fire".equals(ctrl.get())) {
            plugin.bigDrone().cycleBay(player, 1);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!plugin.bigDrone().isPiloting(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        plugin.bigDrone().toggleOrbit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.bigDrone().isPiloting(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInvOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        boolean blockBay = plugin.bigDrone().isPiloting(player)
                || (plugin.droneMeshPose() != null && plugin.droneMeshPose().isCameraEditing(player));
        if (!blockBay) {
            return;
        }
        if (event.getInventory().getHolder() instanceof DronePadService.BayHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInvClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (plugin.bigDrone().isPiloting(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInvDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (plugin.bigDrone().isPiloting(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (plugin.droneSeats() != null && plugin.droneSeats().isSeat(block)) {
            // Explosions do NOT fire BlockBreakEvent — seat registry stays so regen restores the chair.
            // Only intentional admin breaks (or /warz deldroneseat) unregister the seat.
            if (!event.getPlayer().hasPermission("warz.admin")) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(net.kyori.adventure.text.Component.text(
                        "Only admins can break drone seats (or /warz deldroneseat)",
                        net.kyori.adventure.text.format.NamedTextColor.RED));
                return;
            }
            plugin.droneSeats().onSeatBroken(block);
            return;
        }
        Optional<Block> padOpt = plugin.bigDrone().findPadFromClick(block);
        if (padOpt.isEmpty()) {
            return;
        }
        Block pad = padOpt.get();
        Player player = event.getPlayer();
        var owner = plugin.bigDrone().padOwner(pad);
        if (owner.isEmpty() || !owner.get().equals(player.getUniqueId())) {
            event.setCancelled(true);
            String air = "drone";
            if (plugin.dronePads() != null) {
                air = plugin.dronePads().padAt(pad)
                        .map(p -> plugin.dronePads().typeOf(p).displayName())
                        .orElse("drone");
            }
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "Only the owner can remove this " + air,
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        // Breaking the ground under a virtual pad (or legacy lodestone) removes the airframe.
        plugin.bigDrone().forceExitAtPad(pad);
        plugin.bigDrone().removePadData(pad);
        if (pad.getType() == Material.LODESTONE) {
            event.setCancelled(true);
            pad.setType(Material.AIR, false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.bigDrone().isPiloting(event.getPlayer())) {
            // Server stop / plugin disable: do not treat as clean park (avoids scrap-on-restart).
            boolean stopping = !plugin.isEnabled()
                    || !plugin.getServer().getPluginManager().isPluginEnabled(plugin);
            plugin.bigDrone().exit(event.getPlayer(), stopping ? "shutdown" : "disconnect");
        } else {
            plugin.bigDrone().clearPilotInvisibility(event.getPlayer());
            plugin.bigDrone().stripOrphanDroneControls(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // Death while piloting can persist Invisible into the next login.
        if (!plugin.bigDrone().isPiloting(event.getPlayer())) {
            plugin.bigDrone().clearPilotInvisibility(event.getPlayer());
            plugin.bigDrone().stripOrphanDroneControls(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.bigDrone().applySeatKillRespawn(event);
        if (!plugin.bigDrone().isPiloting(event.getPlayer())) {
            plugin.bigDrone().clearPilotInvisibility(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        // Seat operators: orphan MQ-9 + keep radiolink for 10s takeover (do not scrap pad).
        plugin.bigDrone().handlePilotDeath(event);
        // Prop / UAV munition / crash casualty lines beat generic gun kill feed.
        if (plugin.bigDrone().applyPropDeathMessage(event)) {
            return;
        }
        if (plugin.bigDrone().applyWeaponKillDeathMessage(event)) {
            return;
        }
        if (plugin.bigDrone().applyCrashCasualtyDeathMessage(event)) {
            return;
        }
        if (plugin.killFeed() != null) {
            plugin.killFeed().apply(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrashVictimDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        // Pilots are protected from blast; only ground victims get the casualty line.
        if (plugin.bigDrone().isPiloting(player)) {
            return;
        }
        plugin.bigDrone().markCrashDamage(player, event.getCause());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!plugin.bigDrone().isPiloting(player)) {
            return;
        }
        // Seat-proxy hits (someone attacking the chair body) must reach the operator.
        if (BigDroneService.isSeatProxyDamage()) {
            return;
        }
        // Invisible pilot is the airframe — never die to LAW/crash boom while flying.
        // Structure damage is handled separately via absorbBulletHit.
        event.setCancelled(true);
    }

    /**
     * Seat chair is a real killbox — guns, melee, and explosives on the operator body
     * hurt/kill the pilot; MQ-9 goes orphan for takeover. (Airframe LAW/crash boom still
     * cannot hurt the invisible pilot mid-flight.)
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSeatBodyDamaged(EntityDamageEvent event) {
        plugin.bigDrone().handleSeatBodyDamage(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSeatBodyDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mannequin body)) {
            return;
        }
        if (plugin.bigDrone().seatBodyPilot(body).isEmpty()) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        Entity killer = body.getKiller();
        if (killer == null && event.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent by) {
            killer = by.getDamager();
            if (killer instanceof org.bukkit.entity.Projectile proj
                    && proj.getShooter() instanceof Entity shooter) {
                killer = shooter;
            }
        }
        plugin.bigDrone().handleSeatBodyDeath(body, killer);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSeatBodyInteract(PlayerInteractEntityEvent event) {
        if (isSeatBody(event.getRightClicked())) {
            event.setCancelled(true);
            return;
        }
        // Pilots / camera-editor preview must never open payload via Interaction catchers.
        if (plugin.bigDrone().isPiloting(event.getPlayer())
                || (plugin.droneMeshPose() != null && plugin.droneMeshPose().isCameraEditing(event.getPlayer()))) {
            event.setCancelled(true);
            return;
        }
        // Interaction catcher (square AABB) — only open bay if look ray hits the mesh OBB.
        if (plugin.dronePads() == null) {
            return;
        }
        Optional<DronePadService.ParkedPad> pad = plugin.dronePads().padFromInteractEntity(event.getRightClicked());
        if (pad.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        if (!plugin.bigDrone().canUseParkedHull(event.getPlayer(), pad.get())) {
            return;
        }
        handleParkedFrameUse(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand(), pad.get());
    }

    private void handleParkedFrameUse(Player player, ItemStack hand, DronePadService.ParkedPad pad) {
        if (player == null || pad == null || plugin.dronePads() == null) {
            return;
        }
        if (plugin.bigDrone().isPiloting(player)
                || (plugin.droneMeshPose() != null && plugin.droneMeshPose().isCameraEditing(player))) {
            return;
        }
        Block padBlock = player.getWorld().getBlockAt(pad.x, pad.y, pad.z);
        if (padBlock.getType() == Material.LODESTONE) {
            padBlock.setType(Material.AIR, false);
        }
        if (plugin.items().isRadiolink(hand)) {
            plugin.dronePads().beginRadiolink(player, pad);
        } else {
            // Empty hand, blocks, guns — anything except Radiolink opens the bay.
            plugin.dronePads().openBay(player, pad);
        }
    }

    private static boolean isSeatBody(Entity entity) {
        return entity instanceof Mannequin
                && entity.getPersistentDataContainer().has(WarzKeys.of("drone_seat_body"), PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (plugin.bigDrone().isPiloting(event.getPlayer()) && !event.isFlying()) {
            event.setCancelled(true);
            event.getPlayer().setFlying(true);
        }
    }
}
