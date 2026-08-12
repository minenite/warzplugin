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

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Features that belong only on warz, not on the rest of the network.
 *
 * <p>ServerPlugin still runs here for friends, ranks and travel. This plugin
 * owns fly speed, spawns, loot restock, and clans.
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

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(this.lootRestock, this);
        getServer().getPluginManager().registerEvents(new ClanGuiListener(), this);
        this.lootRestock.start();

        getServer().getScheduler().runTaskTimerAsynchronously(this, this.ranks::reload, 100L, 100L);

        getLogger().info("Warz features loaded"
                + (this.firstJoinSpawn != null ? " (first-join spawn set)" : " (no first-join spawn yet)")
                + ", " + deathSpawnIds().size() + " death spawn(s)"
                + ", " + this.clans.allClans().size() + " clan(s)");
    }

    @Override
    public void onDisable() {
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

    public ClanService clans() {
        return this.clans;
    }

    public ClanGuiService clanGui() {
        return this.clanGui;
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

        if (label.equals("/warz") || label.equals("/wz")) {
            event.setCancelled(true);
            handleWarz(event.getPlayer(), parts);
            return;
        }

        if (label.equals("/clan") || label.equals("/c")) {
            event.setCancelled(true);
            handleClan(event.getPlayer(), parts);
        }
    }

    /**
     * Puts the clan tag in front of ServerPlugin's rank-coloured chat line.
     * Runs at the same priority after ServerPlugin (we softdepend it).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        if (this.clans == null) {
            return;
        }
        String frag = this.clans.chatTagFragment(event.getPlayer().getUniqueId());
        if (frag.isEmpty()) {
            return;
        }
        event.setFormat(ClanService.color(frag) + event.getFormat());
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

    private void handleWarz(Player player, String[] parts) {
        if (parts.length < 2) {
            sendWarzHelp(player);
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
            default -> {
                player.sendMessage(ChatColor.RED + "Unknown subcommand. Try /warz");
                sendWarzHelp(player);
            }
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
        player.sendMessage(ChatColor.GREEN + "/reloot"
                + ChatColor.WHITE + " restock all chests and reset the 600s timer");
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
        if (player.hasPlayedBefore() || this.firstJoinSpawn == null) {
            return;
        }
        Location dest = this.firstJoinSpawn.clone();
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                player.teleport(dest);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Location death = pickDeathSpawn(event.getPlayer());
        if (death != null) {
            event.setRespawnLocation(death);
            return;
        }
        if (this.firstJoinSpawn != null) {
            event.setRespawnLocation(this.firstJoinSpawn.clone());
        }
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
