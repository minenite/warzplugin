package net.minenite.warzplugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;

/**
 * Features that belong only on warz, not on the rest of the network.
 *
 * <p>ServerPlugin still runs here for friends, ranks and travel. This plugin
 * owns fly speed, spawns, loot restock, clans, and the world overview map.
 */
public final class WarzPlugin extends JavaPlugin implements Listener {

    private RankStore ranks;
    private Rank spawnRank;
    private Rank speedRank;
    private Rank lootRank;
    private Rank clanAdminRank;
    private Location firstJoinSpawn;
    private LootRestockService lootRestock;
    private ClanService clans;
    private ClanGuiService clanGui;
    private ProfileGuiService profileGui;
    private ScoreboardService scoreboard;
    private HumanityService humanity;
    private WorldMapService worldMap;
    private WorldFreezeListener worldFreeze;
    private TransientBlocksService transientBlocks;
    private ZombieAmbientSpawnService zombieSpawns;
    private FacingBossBarService facingBossBar;
    private SaplingItemCleanupService saplingItems;
    private GunEngine guns;
    private final Random random = ThreadLocalRandom.current();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        Path shared = Path.of(getConfig().getString("shared-directory",
                Path.of("").toAbsolutePath().getParent().resolve("shared").toString()));
        try {
            this.ranks = new RankStore(shared);
        } catch (IOException failed) {
            getLogger().severe("Could not open shared ranks at " + shared + ": " + failed.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.spawnRank = Rank.parse(getConfig().getString("spawn-min-rank", "DEV"));
        this.speedRank = Rank.parse(getConfig().getString("speed-min-rank", "SMOD"));
        this.lootRank = Rank.parse(getConfig().getString("loot-min-rank", "DEV"));
        this.clanAdminRank = Rank.parse(getConfig().getString("clan-admin-min-rank", "DEV"));
        this.firstJoinSpawn = readLocation("spawn");

        this.lootRestock = new LootRestockService(this);
        this.clans = new ClanService(this);
        this.clans.load();
        this.clanGui = new ClanGuiService(this);
        this.profileGui = new ProfileGuiService(this);
        this.humanity = new HumanityService(this);
        this.scoreboard = new ScoreboardService(this);
        if (getConfig().getBoolean("world-map.enabled", true)) {
            this.worldMap = new WorldMapService(this);
            if (!this.worldMap.load()) {
                this.worldMap = null;
            }
        }

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(this.lootRestock, this);
        getServer().getPluginManager().registerEvents(new ClanGuiListener(this), this);
        getServer().getPluginManager().registerEvents(new ProfileGuiListener(this), this);
        getServer().getPluginManager().registerEvents(this.humanity, this);
        getServer().getPluginManager().registerEvents(this.scoreboard, this);
        if (this.worldMap != null) {
            getServer().getPluginManager().registerEvents(this.worldMap, this);
            this.worldMap.startPushTask();
        }
        this.worldFreeze = new WorldFreezeListener(this);
        if (this.worldFreeze.isEnabled()) {
            getServer().getPluginManager().registerEvents(this.worldFreeze, this);
        }
        this.transientBlocks = new TransientBlocksService(this);
        if (this.transientBlocks.isEnabled()) {
            getServer().getPluginManager().registerEvents(this.transientBlocks, this);
            // After worlds are fully available: undo leftover session edits.
            getServer().getScheduler().runTask(this, this.transientBlocks::restoreFromDisk);
            this.transientBlocks.start();
        }
        SpawnRestrictListener spawnRestrict = new SpawnRestrictListener(this);
        getServer().getPluginManager().registerEvents(spawnRestrict, this);
        this.guns = new GunEngine(this);
        this.guns.start(this);
        getServer().getScheduler().runTask(this, spawnRestrict::purgeWorlds);
        this.zombieSpawns = new ZombieAmbientSpawnService(this);
        if (this.zombieSpawns.isEnabled()) {
            this.zombieSpawns.start();
        }
        this.facingBossBar = new FacingBossBarService(this);
        if (this.facingBossBar.isEnabled()) {
            getServer().getPluginManager().registerEvents(this.facingBossBar, this);
            this.facingBossBar.start();
        }
        applyInstantRespawnToLoadedWorlds();
        this.saplingItems = new SaplingItemCleanupService(this);
        if (this.saplingItems.isEnabled()) {
            getServer().getPluginManager().registerEvents(this.saplingItems, this);
            this.saplingItems.start();
        }
        this.lootRestock.start();
        this.humanity.start();
        this.scoreboard.start();

        getServer().getScheduler().runTaskTimerAsynchronously(this, this.ranks::reload, 100L, 100L);

        getLogger().info("Warz features loaded"
                + (this.firstJoinSpawn != null ? " (first-join spawn set)" : " (no first-join spawn yet)")
                + ", " + deathSpawnIds().size() + " death spawn(s)"
                + ", " + this.clans.allClans().size() + " clan(s)"
                + (this.worldMap != null ? ", world map on" : "")
                + (this.worldFreeze != null && this.worldFreeze.isEnabled()
                        ? ", world freeze on" : "")
                + (this.transientBlocks != null && this.transientBlocks.isEnabled()
                        ? ", transient blocks on" : "")
                + (this.zombieSpawns != null && this.zombieSpawns.isEnabled()
                        ? ", ambient zombies on" : "")
                + (this.facingBossBar != null && this.facingBossBar.isEnabled()
                        ? ", compass boss bar on" : "")
                + (isInstantRespawnEnabled() ? ", instant respawn on" : "")
                + (this.saplingItems != null && this.saplingItems.isEnabled()
                        ? ", sapling item despawn on" : "")
                + ", zombie-only spawns"
                + (this.guns != null ? ", guns/workbench/lasers on" : ""));
    }

    @Override
    public void onDisable() {
        if (this.guns != null) {
            this.guns.stop();
        }
        if (this.saplingItems != null) {
            this.saplingItems.stop();
        }
        if (this.facingBossBar != null) {
            this.facingBossBar.stop();
        }
        if (this.zombieSpawns != null) {
            this.zombieSpawns.stop();
        }
        if (this.transientBlocks != null) {
            this.transientBlocks.stop();
        }
        if (this.scoreboard != null) {
            this.scoreboard.stop();
        }
        if (this.humanity != null) {
            this.humanity.stop();
        }
        if (this.lootRestock != null) {
            this.lootRestock.stop();
        }
        if (this.clans != null) {
            this.clans.save();
        }
    }

    public LootRestockService lootRestock() {
        return this.lootRestock;
    }

    public void reloadGuns() {
        reloadConfig();
        if (guns == null) {
            return;
        }
        if (guns.rounds != null) {
            guns.rounds.reload();
        }
        guns.registry.reload();
        guns.sessions.rebuildAll();
        if (guns.explosionRegen != null) {
            guns.explosionRegen.reloadFromConfig();
        }
        if (guns.killFeed != null) {
            guns.killFeed.reload();
        }
        if (guns.companions != null) {
            guns.companions.unregister();
            guns.companions.register();
        }
    }

    public com.local.warz.config.GunRegistry registry() {
        return guns == null ? null : guns.registry;
    }

    public com.local.warz.config.RoundRegistry rounds() {
        return guns == null ? null : guns.rounds;
    }

    public com.local.warz.runtime.SessionManager sessions() {
        return guns == null ? null : guns.sessions;
    }

    public com.local.warz.projectile.BulletManager bullets() {
        return guns == null ? null : guns.bullets;
    }

    public com.local.warz.runtime.ItemFactory items() {
        return guns == null ? null : guns.items;
    }

    public com.local.warz.gui.GunEditorService editor() {
        return guns == null ? null : guns.editor;
    }

    public com.local.warz.gui.GiveGunMenuService giveMenu() {
        return guns == null ? null : guns.giveMenu;
    }

    public com.local.warz.runtime.CompanionClients companions() {
        return guns == null ? null : guns.companions;
    }

    public com.local.warz.runtime.LaserCompanionBridge laserBridge() {
        return guns == null ? null : guns.laserBridge;
    }

    public com.local.warz.runtime.FlashlightService flashlight() {
        return guns == null ? null : guns.flashlight;
    }

    public com.local.warz.runtime.PeqService peq() {
        return guns == null ? null : guns.peq;
    }

    public com.local.warz.runtime.BigDroneService bigDrone() {
        return guns == null ? null : guns.bigDrone;
    }

    public com.local.warz.runtime.DroneStrikeEffects strikeEffects() {
        return guns == null ? null : guns.strikeEffects;
    }

    public com.local.warz.runtime.DroneSeatService droneSeats() {
        return guns == null ? null : guns.droneSeats;
    }

    public com.local.warz.runtime.DroneDatalinkService datalink() {
        return guns == null ? null : guns.datalink;
    }

    public com.local.warz.gui.WarzCreateMenuService createMenu() {
        return guns == null ? null : guns.createMenu;
    }

    public com.local.warz.runtime.DronePadService dronePads() {
        return guns == null ? null : guns.dronePads;
    }

    public com.local.warz.runtime.DroneMeshPoseService droneMeshPose() {
        return guns == null ? null : guns.droneMeshPose;
    }

    public com.local.warz.runtime.JavelinService javelin() {
        return guns == null ? null : guns.javelin;
    }

    public com.local.warz.runtime.WeatherService weather() {
        return guns == null ? null : guns.weather;
    }

    public com.local.warz.runtime.ProneService prone() {
        return guns == null ? null : guns.prone;
    }

    public com.local.warz.runtime.GunPoseSync gunPoses() {
        return guns == null ? null : guns.gunPoses;
    }

    public com.local.warz.runtime.ScopeSync scopeSync() {
        return guns == null ? null : guns.scopeSync;
    }

    public com.local.warz.runtime.SmokeService smoke() {
        return guns == null ? null : guns.smoke;
    }

    public com.local.warz.runtime.FlareService flares() {
        return guns == null ? null : guns.flares;
    }

    public com.local.warz.runtime.GunWorkbenchService workbenches() {
        return guns == null ? null : guns.workbenches;
    }

    public com.local.warz.runtime.GlassService glass() {
        return guns == null ? null : guns.glass;
    }

    public com.local.warz.gui.GunWorkbenchGui workbenchGui() {
        return guns == null ? null : guns.workbenchGui;
    }

    public com.local.warz.runtime.MedicalService medical() {
        return guns == null ? null : guns.medical;
    }

    public com.local.warz.runtime.ScubaService scuba() {
        return guns == null ? null : guns.scuba;
    }

    public com.local.warz.runtime.KillFeedService killFeed() {
        return guns == null ? null : guns.killFeed;
    }

    public com.local.warz.runtime.RazorWireService razorWire() {
        return guns == null ? null : guns.razorWire;
    }

    public com.local.warz.runtime.ChainlinkService chainlink() {
        return guns == null ? null : guns.chainlink;
    }

    public com.local.warz.runtime.CorpseService corpses() {
        return guns == null ? null : guns.corpses;
    }

    public com.local.warz.runtime.ThirstService thirst() {
        return guns == null ? null : guns.thirst;
    }

    public com.local.warz.runtime.InfectionService infection() {
        return guns == null ? null : guns.infection;
    }

    public com.local.warz.runtime.HydrazineService hydrazine() {
        return guns == null ? null : guns.hydrazine;
    }

    public com.local.warz.runtime.WaterService water() {
        return guns == null ? null : guns.water;
    }

    public com.local.warz.runtime.ProfileStatsService profileStats() {
        return guns == null ? null : guns.profileStats;
    }

    public com.local.warz.runtime.GrappleService grapple() {
        return guns == null ? null : guns.grapple;
    }

    public com.local.warz.runtime.ExplosionRegenService explosionRegen() {
        return guns == null ? null : guns.explosionRegen;
    }

    public com.local.warz.runtime.CrashSiteService crashSites() {
        return guns == null ? null : guns.crashSites;
    }

    public com.local.warz.runtime.BlastShockService blastShock() {
        return guns == null ? null : guns.blastShock;
    }

    public com.local.warz.runtime.GroundEmergeListener groundEmerge() {
        return guns == null ? null : guns.groundEmerge;
    }

    public com.local.warz.runtime.anomaly.AnomalyService anomalies() {
        return guns == null ? null : guns.anomalies;
    }

    public com.local.warz.runtime.NvgListener nvgListener() {
        return guns == null ? null : guns.nvgListener;
    }

    public com.local.warz.runtime.LavaHeatService lavaHeat() {
        return guns == null ? null : guns.lavaHeat;
    }

    public com.local.warz.runtime.LongProngsService longProngs() {
        return guns == null ? null : guns.longProngs;
    }

    public com.local.warz.runtime.RestraintService restraints() {
        return guns == null ? null : guns.restraints;
    }

    public RankStore ranks() {
        return this.ranks;
    }

    public ClanService clans() {
        return this.clans;
    }

    public ClanGuiService clanGui() {
        return this.clanGui;
    }

    public ProfileGuiService profileGui() {
        return this.profileGui;
    }

    public ScoreboardService scoreboard() {
        return this.scoreboard;
    }

    public HumanityService humanity() {
        return this.humanity;
    }

    /** DEV+ (or op) — create/delete loot chests, zones, and force reloot. */
    public boolean mayManageLoot(Player player) {
        if (player.isOp()) {
            return true;
        }
        if (this.lootRank == null) {
            return false;
        }
        this.ranks.reload();
        return this.ranks.rankOf(player.getUniqueId()).atLeast(this.lootRank);
    }

    /** DEV+ (or op) — /clan delete, kick, setowner. */
    public boolean mayAdminClans(Player player) {
        if (player.isOp()) {
            return true;
        }
        if (this.clanAdminRank == null) {
            return false;
        }
        this.ranks.reload();
        return this.ranks.rankOf(player.getUniqueId()).atLeast(this.clanAdminRank);
    }

    // --------------------------------------------------------------- commands

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().split("\\s+");
        String label = parts[0].toLowerCase(Locale.ROOT);

        if (label.equals("/speed")) {
            event.setCancelled(true);
            if (parts.length < 2) {
                event.getPlayer().sendMessage(ChatColor.GRAY + "Usage: /speed <1-10>");
                return;
            }
            setFlySpeed(event.getPlayer(), parts[1]);
            return;
        }

        if (label.equals("/setspawn")) {
            event.setCancelled(true);
            if (parts.length >= 2) {
                setDeathSpawn(event.getPlayer(), parts[1]);
            } else {
                setFirstJoinSpawn(event.getPlayer());
            }
            return;
        }

        if (label.equals("/delspawn")) {
            event.setCancelled(true);
            if (parts.length < 2) {
                event.getPlayer().sendMessage(ChatColor.GRAY + "Usage: /delspawn <number>");
                return;
            }
            deleteDeathSpawn(event.getPlayer(), parts[1]);
            return;
        }

        if (label.equals("/reloot")) {
            event.setCancelled(true);
            handleReloot(event.getPlayer());
            return;
        }

        if (label.equals("/map") || label.equals("/warzmap")) {
            event.setCancelled(true);
            giveWorldMap(event.getPlayer());
            return;
        }

        // /tag is owned by ServerPlugin (display rank). Do not intercept it here.

        if (label.equals("/warz") || label.equals("/wz")) {
            event.setCancelled(true);
            handleWarz(event.getPlayer(), parts);
            return;
        }

        if (label.equals("/humanity") || label.equals("/hum")) {
            event.setCancelled(true);
            handleHumanity(event.getPlayer(), parts);
            return;
        }

        if (label.equals("/clan") || label.equals("/c")) {
            event.setCancelled(true);
            handleClan(event.getPlayer(), parts);
            return;
        }

        if (label.equals("/profile") || label.equals("/stats")) {
            event.setCancelled(true);
            handleProfile(event.getPlayer(), parts);
        }
    }

    private void handleHumanity(Player player, String[] parts) {
        if (parts.length >= 2 && mayAdminClans(player)) {
            // /humanity set <player> <amount>  or  /humanity <player>
            if (parts[1].equalsIgnoreCase("set") && parts.length >= 4) {
                OfflinePlayer target = resolvePlayer(parts[2]);
                if (target == null || target.getUniqueId() == null) {
                    player.sendMessage(ChatColor.RED + "Unknown player.");
                    return;
                }
                try {
                    int value = Integer.parseInt(parts[3]);
                    this.humanity.set(target.getUniqueId(), value);
                    this.humanity.save();
                    String name = target.getName() != null ? target.getName() : parts[2];
                    player.sendMessage(ChatColor.GRAY + "Set " + name + "'s humanity to "
                            + ChatColor.WHITE + String.format("%,d", value));
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Usage: /humanity set <player> <amount>");
                }
                return;
            }
            if (!parts[1].equalsIgnoreCase("set")) {
                OfflinePlayer target = resolvePlayer(parts[1]);
                if (target != null && target.getUniqueId() != null) {
                    showHumanity(player, target.getUniqueId(),
                            target.getName() != null ? target.getName() : parts[1]);
                    return;
                }
            }
        }
        showHumanity(player, player.getUniqueId(), player.getName());
    }

    private void showHumanity(Player viewer, java.util.UUID id, String name) {
        int h = this.humanity.get(id);
        String tag = ClanService.color(this.humanity.chatTagFragment(id).trim());
        viewer.sendMessage(ChatColor.GOLD + name + ChatColor.GRAY + ": " + tag
                + ChatColor.DARK_GRAY + " (" + ChatColor.WHITE + String.format("%,d", h)
                + ChatColor.DARK_GRAY + ")");
    }

    /**
     * Puts humanity standing then clan tag in front of ServerPlugin's chat line.
     * Runs at the same priority after ServerPlugin (we softdepend it).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        StringBuilder prefix = new StringBuilder();
        if (this.humanity != null) {
            prefix.append(ClanService.color(
                    this.humanity.chatTagFragment(event.getPlayer().getUniqueId())));
        }
        if (this.clans != null) {
            String frag = this.clans.chatTagFragment(event.getPlayer().getUniqueId());
            if (!frag.isEmpty()) {
                prefix.append(ClanService.color(frag));
            }
        }
        if (prefix.length() == 0) {
            return;
        }
        event.setFormat(prefix + event.getFormat());
    }

    private void handleClan(Player player, String[] parts) {
        // parts[0] is /clan; args start at 1
        if (parts.length == 1) {
            ClanService.Clan own = this.clans.clanOf(player.getUniqueId());
            if (own == null) {
                player.sendMessage(ClanService.color("&cYou're not in a clan."));
                List<String> pending = this.clans.pendingInvites(player.getUniqueId());
                if (!pending.isEmpty()) {
                    player.sendMessage(ClanService.color(
                            "&ePending invites: &f" + String.join("&7, &f", pending)));
                }
                player.sendMessage(ClanService.color(
                        "&7/clan create <name> &8· &7/clan join [name] &8· &7/clan <name>"));
                return;
            }
            this.clanGui.open(player, own);
            return;
        }

        String sub = parts[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                if (parts.length < 3) {
                    player.sendMessage(ClanService.color("&cUsage: /clan create <name>"));
                    return;
                }
                player.sendMessage(ClanService.color(this.clans.create(player, parts[2])));
            }
            case "join" -> {
                String tag = parts.length >= 3 ? parts[2] : null;
                player.sendMessage(ClanService.color(this.clans.join(player, tag)));
            }
            case "leave" -> player.sendMessage(ClanService.color(this.clans.leave(player)));
            case "decline" -> {
                if (parts.length < 3) {
                    player.sendMessage(ClanService.color("&cUsage: /clan decline <clan>"));
                    return;
                }
                player.sendMessage(ClanService.color(this.clans.declineInvite(player, parts[2])));
            }
            case "invite" -> {
                if (parts.length < 3) {
                    player.sendMessage(ClanService.color("&cUsage: /clan invite <player>"));
                    return;
                }
                OfflinePlayer target = resolvePlayer(parts[2]);
                if (target == null) {
                    player.sendMessage(ClanService.color("&cUnknown player."));
                    return;
                }
                player.sendMessage(ClanService.color(this.clans.invite(player, target)));
            }
            case "promote" -> {
                if (parts.length < 3) {
                    player.sendMessage(ClanService.color("&cUsage: /clan promote <player>"));
                    return;
                }
                OfflinePlayer target = resolvePlayer(parts[2]);
                if (target == null) {
                    player.sendMessage(ClanService.color("&cUnknown player."));
                    return;
                }
                player.sendMessage(ClanService.color(this.clans.promote(player, target)));
            }
            case "demote" -> {
                if (parts.length < 3) {
                    player.sendMessage(ClanService.color("&cUsage: /clan demote <player>"));
                    return;
                }
                OfflinePlayer target = resolvePlayer(parts[2]);
                if (target == null) {
                    player.sendMessage(ClanService.color("&cUnknown player."));
                    return;
                }
                player.sendMessage(ClanService.color(this.clans.demote(player, target)));
            }
            case "delete" -> {
                if (!mayAdminClans(player)) {
                    player.sendMessage(ClanService.color("&cNo permission."));
                    return;
                }
                if (parts.length < 3) {
                    player.sendMessage(ClanService.color("&cUsage: /clan delete <clan>"));
                    return;
                }
                player.sendMessage(ClanService.color(this.clans.adminDelete(player, parts[2])));
            }
            case "kick" -> {
                if (!mayAdminClans(player)) {
                    player.sendMessage(ClanService.color("&cNo permission."));
                    return;
                }
                if (parts.length < 3) {
                    player.sendMessage(ClanService.color("&cUsage: /clan kick <player>"));
                    return;
                }
                OfflinePlayer target = resolvePlayer(parts[2]);
                if (target == null) {
                    player.sendMessage(ClanService.color("&cUnknown player."));
                    return;
                }
                player.sendMessage(ClanService.color(this.clans.adminKick(player, target)));
            }
            case "setowner" -> {
                if (!mayAdminClans(player)) {
                    player.sendMessage(ClanService.color("&cNo permission."));
                    return;
                }
                if (parts.length < 4) {
                    player.sendMessage(ClanService.color("&cUsage: /clan setowner <clan> <player>"));
                    return;
                }
                OfflinePlayer target = resolvePlayer(parts[3]);
                if (target == null) {
                    player.sendMessage(ClanService.color("&cUnknown player."));
                    return;
                }
                player.sendMessage(ClanService.color(this.clans.adminSetOwner(player, parts[2], target)));
            }
            case "help" -> sendClanHelp(player);
            default -> {
                ClanService.Clan clan = this.clans.get(parts[1]);
                if (clan == null) {
                    player.sendMessage(ClanService.color("&cUnknown clan or subcommand."));
                    sendClanHelp(player);
                    return;
                }
                this.clanGui.open(player, clan);
            }
        }
    }

    private void handleProfile(Player player, String[] parts) {
        if (this.profileGui == null || profileStats() == null) {
            player.sendMessage(ChatColor.RED + "Profile system is not ready.");
            return;
        }
        OfflinePlayer subject;
        if (parts.length < 2) {
            subject = player;
        } else {
            subject = profileStats().resolvePlayer(parts[1]);
            if (subject == null) {
                player.sendMessage(ChatColor.RED + "Unknown player: " + ChatColor.WHITE + parts[1]);
                return;
            }
        }
        this.profileGui.open(player, subject);
    }

    private void sendClanHelp(Player player) {
        player.sendMessage(ClanService.color("&5Clan commands:"));
        player.sendMessage(ClanService.color("&7/clan &8— &fyour clan GUI"));
        player.sendMessage(ClanService.color("&7/clan <name> &8— &fview a clan"));
        player.sendMessage(ClanService.color("&7/clan create <name> &8— &f1–5 letters/numbers"));
        player.sendMessage(ClanService.color("&7/clan invite <player>"));
        player.sendMessage(ClanService.color("&7/clan join [name] &8— &faccept invite"));
        player.sendMessage(ClanService.color("&7/clan decline <name>"));
        player.sendMessage(ClanService.color("&7/clan leave"));
        player.sendMessage(ClanService.color("&7/clan promote|demote <player> &8— &fowner only"));
        if (mayAdminClans(player)) {
            player.sendMessage(ClanService.color("&cAdmin: &7/clan delete <clan>"));
            player.sendMessage(ClanService.color("&cAdmin: &7/clan kick <player>"));
            player.sendMessage(ClanService.color("&cAdmin: &7/clan setowner <clan> <player>"));
        }
    }

    private static OfflinePlayer resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        if (off.hasPlayedBefore() || off.isOnline()) {
            return off;
        }
        return null;
    }

    private void handleReloot(Player player) {
        if (!mayManageLoot(player)) {
            player.sendMessage(ChatColor.RED + "You are not allowed to reloot.");
            return;
        }
        int n = this.lootRestock.forceReloot(true);
        player.sendMessage(ChatColor.GRAY + "Relooted " + ChatColor.WHITE + n
                + ChatColor.GRAY + " chests. Timer reset to 600s.");
    }

    /**
     * Console dispatch for /warz. Players reach these through
     * PlayerCommandPreprocessEvent, but the console has no such event, so every
     * /warz typed at the server prompt fell through to Bukkit's default handler
     * and printed the usage line - the subcommands were unreachable from there.
     */
    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender,
                             org.bukkit.command.Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("warz")) {
            return false;
        }
        if (sender instanceof Player player) {
            String[] parts = new String[args.length + 1];
            parts[0] = "/warz";
            System.arraycopy(args, 0, parts, 1, args.length);
            handleWarz(player, parts);
            return true;
        }
        if (this.guns == null) {
            sender.sendMessage("WarZ gun engine is not loaded.");
            return true;
        }
        return this.guns.handleWarz(sender, args);
    }

    private void handleWarz(Player player, String[] parts) {
        if (parts.length < 2) {
            sendWarzHelp(player);
            if (this.guns != null) {
                this.guns.handleWarz(player, new String[0]);
            }
            return;
        }
        String sub = parts[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "createchest", "lootchest", "addchest" -> {
                if (!mayManageLoot(player)) {
                    player.sendMessage(ChatColor.RED + "No permission.");
                    return;
                }
                this.lootRestock.beginCreateChest(player);
            }
            case "createzone", "zone" -> {
                if (!mayManageLoot(player)) {
                    player.sendMessage(ChatColor.RED + "No permission.");
                    return;
                }
                if (parts.length < 3) {
                    player.sendMessage(ChatColor.RED + "Usage: /warz createzone <1-7>");
                    player.sendMessage(ChatColor.GRAY + "Select a 2D area with the WorldEdit wand first.");
                    return;
                }
                try {
                    this.lootRestock.createZone(player, Integer.parseInt(parts[2]));
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Zone must be a number 1–7.");
                }
            }
            case "delchest", "removechest" -> {
                if (!mayManageLoot(player)) {
                    player.sendMessage(ChatColor.RED + "No permission.");
                    return;
                }
                this.lootRestock.deleteLookingChest(player);
            }
            case "loot", "restock", "lootstatus" -> this.lootRestock.sendStatus(player);
            case "reloot" -> handleReloot(player);
            case "listchests" -> {
                if (!mayManageLoot(player)) {
                    player.sendMessage(ChatColor.RED + "No permission.");
                    return;
                }
                List<String> rows = this.lootRestock.listChests();
                player.sendMessage(ChatColor.GOLD + "---- Loot chests (" + rows.size() + ") ----");
                if (rows.isEmpty()) {
                    player.sendMessage(ChatColor.GRAY + "None yet. /warz createchest");
                } else {
                    for (String row : rows) {
                        player.sendMessage(ChatColor.WHITE + row);
                    }
                }
            }
            case "scuba", "givescuba", "vitalsgear" -> {
                if (!mayManageLoot(player)) {
                    player.sendMessage(ChatColor.RED + "No permission.");
                    return;
                }
                this.scoreboard.giveScubaSet(player);
            }
            case "map" -> giveWorldMap(player);
            default -> {
                if (this.guns != null) {
                    String[] args = java.util.Arrays.copyOfRange(parts, 1, parts.length);
                    this.guns.handleWarz(player, args);
                } else {
                    player.sendMessage(ChatColor.RED + "Unknown subcommand. Try /warz");
                    sendWarzHelp(player);
                }
            }
        }
    }

    private void giveWorldMap(Player player) {
        if (this.worldMap == null || !this.worldMap.isLoaded()) {
            player.sendMessage(ChatColor.RED + "World map is not available.");
            return;
        }
        if (this.worldMap.giveMap(player)) {
            player.sendMessage(ChatColor.GRAY + "Here's the "
                    + ChatColor.GOLD + "Warz Map" + ChatColor.GRAY + ". Hold it to view.");
        } else {
            player.sendMessage(ChatColor.RED + "Could not create the map.");
        }
    }

    private void sendWarzHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "---- Warz loot ----");
        player.sendMessage(ChatColor.GREEN + "/warz createchest"
                + ChatColor.WHITE + " register looked-at chest loot template");
        player.sendMessage(ChatColor.GREEN + "/warz createzone <1-7>"
                + ChatColor.WHITE + " WorldEdit 2D zone label");
        player.sendMessage(ChatColor.GREEN + "/warz delchest"
                + ChatColor.WHITE + " unregister looked-at loot chest");
        player.sendMessage(ChatColor.GREEN + "/warz loot"
                + ChatColor.WHITE + " restock timer / zone status");
        player.sendMessage(ChatColor.GREEN + "/warz listchests"
                + ChatColor.WHITE + " list registered loot chests");
        player.sendMessage(ChatColor.GREEN + "/warz scuba"
                + ChatColor.WHITE + " give scuba + wetsuit + bandages");
        player.sendMessage(ChatColor.GREEN + "/map"
                + ChatColor.WHITE + " get the full-server overview map");
        player.sendMessage(ChatColor.GREEN + "/reloot"
                + ChatColor.WHITE + " restock all chests and reset the 600s timer");
        player.sendMessage(ChatColor.GOLD + "---- Guns ----");
        player.sendMessage(ChatColor.GREEN + "/warz give <gun>"
                + ChatColor.WHITE + " give a stick-gun");
        player.sendMessage(ChatColor.GREEN + "/warz menu"
                + ChatColor.WHITE + " guns / ammo / attachments GUI");
        player.sendMessage(ChatColor.GREEN + "/warz list"
                + ChatColor.WHITE + " list loaded guns");
    }

    // ----------------------------------------------------------------- speed

    private void setFlySpeed(Player player, String raw) {
        if (!maySpeed(player)) {
            player.sendMessage(ChatColor.RED + "You are not allowed to change fly speed.");
            return;
        }
        if (!player.isFlying()) {
            player.sendMessage(ChatColor.RED + "You have to be flying to set fly speed.");
            return;
        }
        int level;
        try {
            level = Integer.parseInt(raw.trim());
        } catch (NumberFormatException bad) {
            player.sendMessage(ChatColor.GRAY + "Usage: /speed <1-10>");
            return;
        }
        if (level < 1 || level > 10) {
            player.sendMessage(ChatColor.RED + "Fly speed must be between 1 and 10.");
            return;
        }
        // Bukkit's fly speed is -1..1; default is 0.1. Map 1 -> 0.1, 10 -> 1.0.
        float speed = level / 10.0f;
        player.setFlySpeed(speed);
        player.sendMessage(ChatColor.GRAY + "Fly speed set to " + ChatColor.WHITE + level
                + ChatColor.GRAY + ".");
    }

    private boolean maySpeed(Player player) {
        if (player.isOp()) {
            return true;
        }
        if (this.speedRank == null) {
            return false;
        }
        this.ranks.reload();
        return this.ranks.rankOf(player.getUniqueId()).atLeast(this.speedRank);
    }

    // ----------------------------------------------------------------- spawn

    /**
     * First time on this server only - later visits keep wherever they arrived
     * or left. Death uses the numbered pool instead.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean firstJoin = !player.hasPlayedBefore();
        if (firstJoin && this.firstJoinSpawn != null) {
            Location dest = this.firstJoinSpawn.clone();
            getServer().getScheduler().runTaskLater(this, () -> {
                if (player.isOnline()) {
                    player.teleport(dest);
                }
            }, 1L);
        }
        if (firstJoin) {
            java.util.UUID id = player.getUniqueId();
            getServer().getScheduler().runTaskLater(this, () -> {
                Player p = getServer().getPlayer(id);
                if (p != null) {
                    ensureStarterKit(p);
                }
            }, 2L);
            getServer().getScheduler().runTaskLater(this, () -> {
                Player p = getServer().getPlayer(id);
                if (p != null) {
                    ensureStarterKit(p);
                }
            }, 5L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Location death = pickDeathSpawn(event.getPlayer());
        if (death != null) {
            event.setRespawnLocation(death);
        } else if (this.firstJoinSpawn != null) {
            event.setRespawnLocation(this.firstJoinSpawn.clone());
        }
        // Kit is NOT given here: CardForge fires this before creating the new
        // ServerPlayer. Giving items would hit the soon-to-be-discarded inventory.
    }

    /**
     * Starter knife + USP + medical + water + map + compass after the new player
     * entity exists (CardForge respawn).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPostRespawnGiveKit(PlayerPostRespawnEvent event) {
        Player player = event.getPlayer();
        java.util.UUID id = player.getUniqueId();
        ensureStarterKit(player);
        getServer().getScheduler().runTaskLater(this, () -> {
            Player p = getServer().getPlayer(id);
            if (p != null) {
                ensureStarterKit(p);
            }
        }, 2L);
        getServer().getScheduler().runTaskLater(this, () -> {
            Player p = getServer().getPlayer(id);
            if (p != null) {
                ensureStarterKit(p);
            }
        }, 5L);
    }

    /**
     * Hotbar: 0 knife, 1 USP-45, 2 bandages×4, 3 beans×4, 4 map, 5 splints×2,
     * 6 water×2, 7 pasta×2, 8 compass.
     * Inv: slots above USP (10, 11) = two full 9mm mags; top-right (35) = blood bag.
     * Idempotent — only fills missing / wrong slots.
     */
    private void ensureStarterKit(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        var inv = player.getInventory();
        ItemStack slot0 = inv.getItem(0);
        if (slot0 == null || slot0.getType() != Material.WOODEN_SWORD) {
            giveWoodenKnife(player, 0);
        }

        var items = items();
        var registry = registry();
        if (items != null && registry != null) {
            ItemStack slot1 = inv.getItem(1);
            if (!isStarterUsp(slot1)) {
                registry.get("usp45").ifPresent(def -> inv.setItem(1, items.create(def, 1)));
            }

            // Two loaded 9mm mags, in the slots directly above the pistol. One
            // magazine is fifteen rounds; a spare is the difference between
            // finishing a fight and reloading loose ammo in the middle of it.
            for (int magSlot : new int[]{1 + 9, 2 + 9}) {
                ItemStack aboveGun = inv.getItem(magSlot);
                if (!isStarterLoaded9mmMag(aboveGun)) {
                    inv.setItem(magSlot, items.createMagazine(
                            com.local.warz.runtime.MagazineType.PISTOL_15,
                            com.local.warz.runtime.MagazineType.PISTOL_15.capacity(),
                            "pistol_fmj",
                            1));
                }
            }

            ItemStack slot2 = inv.getItem(2);
            if (!items.isBandage(slot2) || slot2.getAmount() < 4) {
                inv.setItem(2, items.createBandage(4));
            }

            ItemStack slot3 = inv.getItem(3);
            if (items.foodType(slot3) != com.local.warz.runtime.WarzFoodType.CANNED_BEANS
                    || slot3.getAmount() < 4) {
                inv.setItem(3, items.createFood(com.local.warz.runtime.WarzFoodType.CANNED_BEANS, 4));
            }

            ItemStack slot5 = inv.getItem(5);
            if (!items.isSplint(slot5) || slot5.getAmount() < 2) {
                inv.setItem(5, items.createSplint(2));
            }

            ItemStack slot6 = inv.getItem(6);
            if (items.drinkType(slot6) != com.local.warz.runtime.DrinkType.WATER
                    || slot6.getAmount() < 2) {
                inv.setItem(6, items.createDrink(com.local.warz.runtime.DrinkType.WATER, 2));
            }

            ItemStack slot7 = inv.getItem(7);
            if (items.foodType(slot7) != com.local.warz.runtime.WarzFoodType.CANNED_PASTA
                    || slot7.getAmount() < 2) {
                inv.setItem(7, items.createFood(com.local.warz.runtime.WarzFoodType.CANNED_PASTA, 2));
            }

            ItemStack topRight = inv.getItem(35);
            if (!items.isBloodBag(topRight)) {
                inv.setItem(35, items.createBloodBag(1));
            }
        }

        ItemStack slot4 = inv.getItem(4);
        boolean needMap = slot4 == null || slot4.getType() != Material.FILLED_MAP;
        if (needMap && this.worldMap != null && this.worldMap.isLoaded()) {
            this.worldMap.giveMap(player, 4);
        }
        ItemStack slot8 = inv.getItem(8);
        if (slot8 == null || slot8.getType() != Material.COMPASS) {
            giveStarterCompass(player, 8);
        } else if (FacingBossBarService.pointTrueNorth(slot8, player.getWorld())) {
            inv.setItem(8, slot8);
        }
        player.updateInventory();
    }

    private boolean isStarterUsp(ItemStack stack) {
        if (items() == null || stack == null) {
            return false;
        }
        return items().gunId(stack).filter(id -> "usp45".equalsIgnoreCase(id)).isPresent();
    }

    private boolean isStarterLoaded9mmMag(ItemStack stack) {
        if (items() == null || stack == null || !items().isMagazine(stack)) {
            return false;
        }
        if (items().magazineType(stack) != com.local.warz.runtime.MagazineType.PISTOL_15) {
            return false;
        }
        return items().magazineCount(stack) > 0;
    }

    /** Puts a north-seeking compass in a hotbar slot (replaces that slot only). */
    private void giveStarterCompass(Player player, int hotbarSlot) {
        player.getInventory().setItem(hotbarSlot, FacingBossBarService.northCompass(player.getWorld()));
    }

    /** Puts a styled wooden sword in a hotbar slot (replaces that slot only). */
    private void giveWoodenKnife(Player player, int hotbarSlot) {
        ItemStack knife = new ItemStack(Material.WOODEN_SWORD);
        ItemMeta meta = knife.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&7&oWooden Knife"));
            knife.setItemMeta(meta);
        }
        player.getInventory().setItem(hotbarSlot, knife);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        // Never spawn XP orbs, and do not strip the player's levels (loot timer
        // uses the XP bar). Applies to /kill, PvP, mobs, void — every death.
        event.setDroppedExp(0);
        event.setShouldDropExperience(false);
        event.setKeepLevel(true);

        // Belt-and-suspenders with doImmediateRespawn: if the client still sits
        // on the death screen (lag / missed game-event), force a server respawn.
        // Delay past the starter-kit grants so a late force-respawn cannot wipe them.
        if (!isInstantRespawnEnabled()) {
            return;
        }
        Player player = event.getEntity();
        java.util.UUID id = player.getUniqueId();
        getServer().getScheduler().runTaskLater(this, () -> {
            Player p = getServer().getPlayer(id);
            if (p != null && p.isOnline() && p.getHealth() <= 0.0) {
                p.spigot().respawn();
            }
        }, 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        applyInstantRespawn(event.getWorld());
    }

    private boolean isInstantRespawnEnabled() {
        return getConfig().getBoolean("instant-respawn", true);
    }

    private void applyInstantRespawnToLoadedWorlds() {
        if (!isInstantRespawnEnabled()) {
            return;
        }
        for (World world : getServer().getWorlds()) {
            applyInstantRespawn(world);
        }
    }

    private void applyInstantRespawn(World world) {
        if (!isInstantRespawnEnabled()) {
            return;
        }
        Boolean current = world.getGameRuleValue(GameRule.DO_IMMEDIATE_RESPAWN);
        if (Boolean.TRUE.equals(current)) {
            return;
        }
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
    }

    private void setFirstJoinSpawn(Player actor) {
        if (!maySetSpawn(actor)) {
            actor.sendMessage(ChatColor.RED + "You are not allowed to set the spawn.");
            return;
        }
        Location at = actor.getLocation();
        writeLocation("spawn", at);
        this.firstJoinSpawn = at.clone();
        // Keep the world spawn in agreement for compasses and anything that asks
        // the game rather than this plugin.
        at.getWorld().setSpawnLocation(at);
        saveConfig();
        actor.sendMessage(ChatColor.GRAY + String.format(
                "First-join spawn set to %.1f, %.1f, %.1f. New players land here once.",
                at.getX(), at.getY(), at.getZ()));
    }

    private void setDeathSpawn(Player actor, String rawNumber) {
        if (!maySetSpawn(actor)) {
            actor.sendMessage(ChatColor.RED + "You are not allowed to set death spawns.");
            return;
        }
        Integer number = parseSpawnNumber(rawNumber);
        if (number == null) {
            actor.sendMessage(ChatColor.GRAY + "Usage: /setspawn <number>  "
                    + ChatColor.DARK_GRAY + "(or /setspawn alone for first-join)");
            return;
        }
        Location at = actor.getLocation();
        writeLocation("death-spawns." + number, at);
        saveConfig();
        actor.sendMessage(ChatColor.GRAY + "Death spawn " + ChatColor.WHITE + number
                + ChatColor.GRAY + String.format(" set to %.1f, %.1f, %.1f.",
                at.getX(), at.getY(), at.getZ()));
    }

    private void deleteDeathSpawn(Player actor, String rawNumber) {
        if (!maySetSpawn(actor)) {
            actor.sendMessage(ChatColor.RED + "You are not allowed to delete death spawns.");
            return;
        }
        Integer number = parseSpawnNumber(rawNumber);
        if (number == null) {
            actor.sendMessage(ChatColor.GRAY + "Usage: /delspawn <number>");
            return;
        }
        String path = "death-spawns." + number;
        if (!getConfig().isSet(path)) {
            actor.sendMessage(ChatColor.RED + "No death spawn " + number + ".");
            return;
        }
        getConfig().set(path, null);
        saveConfig();
        actor.sendMessage(ChatColor.GRAY + "Deleted death spawn " + ChatColor.WHITE + number
                + ChatColor.GRAY + ".");
    }

    private boolean maySetSpawn(Player player) {
        if (player.isOp()) {
            return true;
        }
        if (this.spawnRank == null) {
            return false;
        }
        this.ranks.reload();
        return this.ranks.rankOf(player.getUniqueId()).atLeast(this.spawnRank);
    }

    private Integer parseSpawnNumber(String raw) {
        try {
            int n = Integer.parseInt(raw.trim());
            return n >= 1 ? n : null;
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    /**
     * Picks the death spawn with the fewest players nearby.
     *
     * <p>A player counts against a spawn when they are in the same world and
     * within {@code death-spawn-clearance} blocks, or when they are a bit farther
     * but looking roughly toward it (so camping a sightline still pushes the
     * next respawn elsewhere). Ties break toward the spawn whose nearest player
     * is farthest away, then at random.
     */
    private Location pickDeathSpawn(Player respawning) {
        List<Location> options = new ArrayList<>();
        for (String id : deathSpawnIds()) {
            Location at = readLocation("death-spawns." + id);
            if (at != null) {
                options.add(at);
            }
        }
        if (options.isEmpty()) {
            return null;
        }
        if (options.size() == 1) {
            return options.get(0).clone();
        }

        double clearance = getConfig().getDouble("death-spawn-clearance", 64.0);
        double lookRange = Math.max(clearance, getConfig().getDouble("death-spawn-look-range", 96.0));

        int bestPressure = Integer.MAX_VALUE;
        double bestNearest = -1.0;
        List<Location> best = new ArrayList<>();

        for (Location spawn : options) {
            int pressure = 0;
            double nearest = Double.POSITIVE_INFINITY;
            World world = spawn.getWorld();
            if (world == null) {
                continue;
            }
            for (Player other : getServer().getOnlinePlayers()) {
                if (other.equals(respawning) || !other.getWorld().equals(world)) {
                    continue;
                }
                Location at = other.getLocation();
                double dist = at.distance(spawn);
                if (dist < nearest) {
                    nearest = dist;
                }
                if (dist <= clearance || (dist <= lookRange && lookingToward(other, spawn))) {
                    pressure++;
                }
            }
            double nearestScore = nearest == Double.POSITIVE_INFINITY ? Double.MAX_VALUE : nearest;
            if (pressure < bestPressure
                    || (pressure == bestPressure && nearestScore > bestNearest)) {
                bestPressure = pressure;
                bestNearest = nearestScore;
                best.clear();
                best.add(spawn);
            } else if (pressure == bestPressure
                    && Math.abs(nearestScore - bestNearest) < 0.01) {
                best.add(spawn);
            }
        }

        if (best.isEmpty()) {
            return options.get(this.random.nextInt(options.size())).clone();
        }
        return best.get(this.random.nextInt(best.size())).clone();
    }

    /** True when the player's look direction points roughly at the spawn. */
    private static boolean lookingToward(Player player, Location spawn) {
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector toSpawn = spawn.toVector().subtract(eye.toVector());
        if (toSpawn.lengthSquared() < 1.0e-6) {
            return true;
        }
        toSpawn.normalize();
        // Cosine of ~45° — wide enough that someone scanning the area still counts.
        return eye.getDirection().normalize().dot(toSpawn) >= 0.7;
    }

    private Set<String> deathSpawnIds() {
        if (getConfig().getConfigurationSection("death-spawns") == null) {
            return Collections.emptySet();
        }
        return getConfig().getConfigurationSection("death-spawns").getKeys(false);
    }

    private Location readLocation(String path) {
        if (!getConfig().isSet(path + ".world")) {
            return null;
        }
        World world = getServer().getWorld(getConfig().getString(path + ".world"));
        if (world == null) {
            getLogger().warning("Spawn at " + path + " names a missing world: "
                    + getConfig().getString(path + ".world"));
            return null;
        }
        return new Location(world,
                getConfig().getDouble(path + ".x"),
                getConfig().getDouble(path + ".y"),
                getConfig().getDouble(path + ".z"),
                (float) getConfig().getDouble(path + ".yaw"),
                (float) getConfig().getDouble(path + ".pitch"));
    }

    private void writeLocation(String path, Location at) {
        getConfig().set(path + ".world", at.getWorld().getName());
        getConfig().set(path + ".x", at.getX());
        getConfig().set(path + ".y", at.getY());
        getConfig().set(path + ".z", at.getZ());
        getConfig().set(path + ".yaw", at.getYaw());
        getConfig().set(path + ".pitch", at.getPitch());
    }
}
