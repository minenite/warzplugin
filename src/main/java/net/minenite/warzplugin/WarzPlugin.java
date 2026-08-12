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

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Features that belong only on warz, not on the rest of the network.
 *
 * <p>ServerPlugin still runs here for friends, ranks and travel. This plugin
 * owns fly speed, the first-join spawn, and the pool of death spawns.
 */
public final class WarzPlugin extends JavaPlugin implements Listener {

    private RankStore ranks;
    private Rank spawnRank;
    private Rank speedRank;
    private Location firstJoinSpawn;
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
        this.firstJoinSpawn = readLocation("spawn");

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimerAsynchronously(this, this.ranks::reload, 100L, 100L);

        getLogger().info("Warz features loaded"
                + (this.firstJoinSpawn != null ? " (first-join spawn set)" : " (no first-join spawn yet)")
                + ", " + deathSpawnIds().size() + " death spawn(s)");
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
        }
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
        Location death = pickDeathSpawn();
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

    private Location pickDeathSpawn() {
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
        return options.get(this.random.nextInt(options.size())).clone();
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
