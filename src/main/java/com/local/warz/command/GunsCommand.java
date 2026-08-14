package com.local.warz.command;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.model.GunDefinition;
import com.local.warz.runtime.BigDroneType;
import com.local.warz.runtime.DroneSeatService;
import com.local.warz.runtime.GunPlayerSession;
import com.local.warz.runtime.ItemFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class GunsCommand implements CommandExecutor, TabCompleter {
    private final WarzPlugin plugin;

    public GunsCommand(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("----[", NamedTextColor.DARK_GRAY)
                    .append(Component.text("WarZ", NamedTextColor.YELLOW))
                    .append(Component.text("]----", NamedTextColor.DARK_GRAY)));
            sender.sendMessage(Component.text("/warz reload", NamedTextColor.GREEN)
                    .append(Component.text(" reload gun configs", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz list", NamedTextColor.GREEN)
                    .append(Component.text(" list loaded guns", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz toggle", NamedTextColor.GREEN)
                    .append(Component.text(" toggle your guns", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz give <gun> [player] [amount]", NamedTextColor.GREEN)
                    .append(Component.text(" give a gun item", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz giveammo <round> [amount] [player]", NamedTextColor.GREEN)
                    .append(Component.text(" give tagged ammo rounds", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz listammo", NamedTextColor.GREEN)
                    .append(Component.text(" list loaded ammo rounds", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz editor [gun]", NamedTextColor.GREEN)
                    .append(Component.text(" open gun editor GUI", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz create", NamedTextColor.GREEN)
                    .append(Component.text(" create hub: guns, seats, towers, satellites", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz creategun", NamedTextColor.GREEN)
                    .append(Component.text(" create a new gun in the GUI", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz menu", NamedTextColor.GREEN)
                    .append(Component.text(" give guns/ammo/grenades GUI", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz drone exit", NamedTextColor.GREEN)
                    .append(Component.text(" leave UAV / drone", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz setdroneseat [drone]", NamedTextColor.GREEN)
                    .append(Component.text(" register looked-at stairs as drone seat", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz deldroneseat", NamedTextColor.GREEN)
                    .append(Component.text(" remove looked-at drone seat", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz listdroneseats", NamedTextColor.GREEN)
                    .append(Component.text(" list registered drone seats", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz laserdebug", NamedTextColor.GREEN)
                    .append(Component.text(" companion / laser status", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz weather <sandstorm|clear|status> [secs] [intensity]", NamedTextColor.GREEN)
                    .append(Component.text(" tactical weather", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz prone", NamedTextColor.GREEN)
                    .append(Component.text(" toggle prone / crawl", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz createchest", NamedTextColor.GREEN)
                    .append(Component.text(" register looked-at chest loot template", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz createzone <1-7>", NamedTextColor.GREEN)
                    .append(Component.text(" WorldEdit 2D zone label", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz delchest", NamedTextColor.GREEN)
                    .append(Component.text(" unregister looked-at loot chest", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz loot", NamedTextColor.GREEN)
                    .append(Component.text(" restock timer / zone status", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz regen [on|off|toggle|status|clear|now]", NamedTextColor.GREEN)
                    .append(Component.text(" explosion block regen", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz crashsites [list|clear]", NamedTextColor.GREEN)
                    .append(Component.text(" UAV crash site admin", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz dronepads [list|clear]", NamedTextColor.GREEN)
                    .append(Component.text(" UAV parked pad admin", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz dronepose <type|list|copy|exit|save>", NamedTextColor.GREEN)
                    .append(Component.text(" tune airframe mesh (hotbar)", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz dronecam <type|list|copy|exit|save>", NamedTextColor.GREEN)
                    .append(Component.text(" tune operator camera (hotbar)", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz clearcorpses", NamedTextColor.GREEN)
                    .append(Component.text(" remove all death corpses", NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("/warz anomaly [list|clear|spawn <type>]", NamedTextColor.GREEN)
                    .append(Component.text(" Red NV paranormal contacts", NamedTextColor.WHITE)));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "reload" -> reload(sender);
            case "list" -> list(sender);
            case "listammo", "ammo", "rounds" -> listAmmo(sender);
            case "toggle" -> toggle(sender);
            case "give" -> give(sender, args);
            case "giveammo" -> giveAmmo(sender, args);
            case "editor", "edit", "gui" -> editor(sender, args);
            case "create", "new" -> create(sender);
            case "creategun" -> createGun(sender);
            case "menu", "givemenu", "givegunmenu", "ggm" -> giveMenu(sender);
            case "drone" -> drone(sender, args);
            case "setdroneseat", "droneseat", "setseat" -> setDroneSeat(sender, args);
            case "deldroneseat", "rmdroneseat", "removedroneseat" -> delDroneSeat(sender);
            case "listdroneseats", "droneseats" -> listDroneSeats(sender);
            case "laserdebug", "laserd", "companion" -> laserDebug(sender);
            case "weather", "wx" -> weather(sender, args);
            case "prone", "crawl" -> prone(sender);
            case "createchest", "lootchest", "addchest" -> createChest(sender);
            case "createzone", "zone" -> createZone(sender, args);
            case "delchest", "removechest" -> delChest(sender);
            case "loot", "restock", "lootstatus" -> lootStatus(sender);
            case "reloot" -> reloot(sender);
            case "listchests" -> listChests(sender);
            case "regen", "explosionregen", "xpregen", "blockregen" -> explosionRegen(sender, args);
            case "crashsites", "crashsite", "crashes" -> crashSites(sender, args);
            case "dronepads", "dronepad", "pads" -> dronePads(sender, args);
            case "dronepose", "meshpose", "droneedit" -> dronePose(sender, args);
            case "dronecam", "dronecamera", "droneoptic", "sensorcam", "opcam" -> droneCam(sender, args);
            case "clearcorpses", "clearcorpse", "corpsesclear", "rmcorpses" -> clearCorpses(sender);
            case "anomaly", "anomalies", "paranormal" -> anomaly(sender, args);
            default -> {
                sender.sendMessage(Component.text("Unknown subcommand. Try /warz", NamedTextColor.RED));
                yield true;
            }
        };
    }

    private boolean anomaly(CommandSender sender, String[] args) {
        if (!sender.hasPermission("warz.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (plugin.anomalies() == null) {
            sender.sendMessage(Component.text("Anomaly service not loaded.", NamedTextColor.RED));
            return true;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        return switch (action) {
            case "clear", "purge" -> {
                int n = plugin.anomalies().clearAll();
                sender.sendMessage(Component.text("Cleared " + n + " anomal" + (n == 1 ? "y" : "ies") + ".",
                        NamedTextColor.GREEN));
                yield true;
            }
            case "spawn" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
                    yield true;
                }
                if (args.length < 3) {
                    sender.sendMessage(Component.text(
                            "Usage: /warz anomaly spawn <shadow|echo|watcher|crawler|static_man|residual|mimic|stalker|void|burn_in|distortion|false_contact|glitch|observer|imposter>",
                            NamedTextColor.RED));
                    yield true;
                }
                var type = com.local.warz.runtime.anomaly.AnomalyType.byName(args[2]);
                if (type == null) {
                    sender.sendMessage(Component.text("Unknown type: " + args[2], NamedTextColor.RED));
                    yield true;
                }
                var a = plugin.anomalies().spawnNear(player, type);
                if (a == null) {
                    sender.sendMessage(Component.text("Failed to spawn.", NamedTextColor.RED));
                } else {
                    sender.sendMessage(Component.text("Spawned " + type.name() + " — wear Red NV to see it.",
                            NamedTextColor.GREEN));
                }
                yield true;
            }
            case "list", "status", "ls" -> {
                var all = plugin.anomalies().all();
                sender.sendMessage(Component.text("---- Anomalies (" + all.size() + ") ----", NamedTextColor.DARK_RED));
                if (all.isEmpty()) {
                    sender.sendMessage(Component.text("None active. Ambient spawn near Red NV wearers at night.",
                            NamedTextColor.GRAY));
                } else {
                    for (var a : all) {
                        sender.sendMessage(Component.text(plugin.anomalies().describe(a), NamedTextColor.RED));
                    }
                }
                yield true;
            }
            default -> {
                sender.sendMessage(Component.text("Usage: /warz anomaly [list|clear|spawn <type>]", NamedTextColor.RED));
                yield true;
            }
        };
    }

    private boolean clearCorpses(CommandSender sender) {
        if (!sender.hasPermission("warz.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (plugin.corpses() == null) {
            sender.sendMessage(Component.text("Corpse service not loaded.", NamedTextColor.RED));
            return true;
        }
        int removed = plugin.corpses().clearAll();
        sender.sendMessage(Component.text("Cleared corpses (" + removed + " entities removed).", NamedTextColor.GREEN));
        return true;
    }

    private boolean crashSites(CommandSender sender, String[] args) {
        if (!sender.hasPermission("warz.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (plugin.crashSites() == null) {
            sender.sendMessage(Component.text("Crash site service not loaded.", NamedTextColor.RED));
            return true;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        return switch (action) {
            case "clear", "purge", "all" -> {
                int n = plugin.crashSites().clearAll();
                sender.sendMessage(Component.text("Cleared " + n + " UAV crash site(s) — craters regenerating.",
                        NamedTextColor.GREEN));
                yield true;
            }
            case "list", "status", "ls" -> {
                var sites = plugin.crashSites().listSites();
                sender.sendMessage(Component.text("---- UAV crash sites (" + sites.size() + ") ----",
                        NamedTextColor.GOLD));
                if (sites.isEmpty()) {
                    sender.sendMessage(Component.text("None active.", NamedTextColor.GRAY));
                    yield true;
                }
                for (var s : sites) {
                    String coords = String.format(Locale.ROOT, "%d %d %d",
                            s.center().getBlockX(), s.center().getBlockY(), s.center().getBlockZ());
                    sender.sendMessage(ItemFactory.colorize(
                            "&e" + s.shortId() + " &7@ &f" + s.worldName() + " " + coords
                                    + " &8| &7hold &f" + s.holdBlocks()
                                    + " &8| &7props &f" + s.props()));
                }
                sender.sendMessage(Component.text("/warz crashsites clear", NamedTextColor.DARK_GRAY)
                        .append(Component.text(" — force regen all", NamedTextColor.GRAY)));
                yield true;
            }
            default -> {
                sender.sendMessage(Component.text("Usage: /warz crashsites [list|clear]", NamedTextColor.RED));
                yield true;
            }
        };
    }

    private boolean dronePads(CommandSender sender, String[] args) {
        if (!sender.hasPermission("warz.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (plugin.dronePads() == null) {
            sender.sendMessage(Component.text("Drone pad service not loaded.", NamedTextColor.RED));
            return true;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        return switch (action) {
            case "clear", "purge", "all" -> {
                int n = plugin.dronePads().clearAllPads();
                sender.sendMessage(Component.text("Cleared " + n + " UAV pad(s).", NamedTextColor.GREEN));
                yield true;
            }
            case "list", "status", "ls" -> {
                var pads = plugin.dronePads().allPads();
                sender.sendMessage(Component.text("---- UAV pads (" + pads.size() + ") ----",
                        NamedTextColor.GOLD));
                if (pads.isEmpty()) {
                    sender.sendMessage(Component.text("None placed.", NamedTextColor.GRAY));
                    yield true;
                }
                for (var pad : pads) {
                    String coords = String.format(Locale.ROOT, "%d %d %d", pad.x, pad.y, pad.z);
                    String typeName = plugin.dronePads().typeOf(pad).displayName();
                    sender.sendMessage(ItemFactory.colorize(
                            "&e" + pad.id.toString().substring(0, 8) + " &7@ &f" + pad.world + " " + coords
                                    + " &8| &b" + typeName
                                    + " &8| &7rockets &f" + pad.rockets.size()
                                    + " &8| &7fuel &f" + pad.fuelCans
                                    + " &8| &7hp &f" + pad.structureHp));
                }
                sender.sendMessage(Component.text("/warz dronepads clear", NamedTextColor.DARK_GRAY)
                        .append(Component.text(" — remove all pads", NamedTextColor.GRAY)));
                yield true;
            }
            default -> {
                sender.sendMessage(Component.text("Usage: /warz dronepads [list|clear]", NamedTextColor.RED));
                yield true;
            }
        };
    }

    private boolean dronePose(CommandSender sender, String[] args) {
        return droneTune(sender, args, false);
    }

    private boolean droneCam(CommandSender sender, String[] args) {
        return droneTune(sender, args, true);
    }

    /** Shared mesh / operator-camera hotbar editors. */
    private boolean droneTune(CommandSender sender, String[] args, boolean camera) {
        if (!sender.hasPermission("warz.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (plugin.droneMeshPose() == null) {
            sender.sendMessage(Component.text("Mesh pose service not loaded.", NamedTextColor.RED));
            return true;
        }
        String cmd = camera ? "dronecam" : "dronepose";
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "Usage: /warz " + cmd + " <mq9|rq4|mq4c|mq1c|rq170|x47b|x37b|list|copy|exit|save>",
                    NamedTextColor.RED));
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        String notEditing = camera ? "Not in operator camera edit mode." : "Not in mesh pose edit mode.";
        return switch (action) {
            case "list", "ls", "status" -> {
                if (camera) {
                    plugin.droneMeshPose().listCamera(sender);
                } else {
                    plugin.droneMeshPose().list(sender);
                }
                yield true;
            }
            case "exit", "cancel", "abort" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
                    yield true;
                }
                if (camera && !plugin.droneMeshPose().isCameraEditing(player)) {
                    player.sendMessage(Component.text(notEditing, NamedTextColor.GRAY));
                    yield true;
                }
                if (!camera && !plugin.droneMeshPose().isMeshEditing(player)) {
                    // Allow cancel from either mode if they used the wrong exit command.
                    if (!plugin.droneMeshPose().isEditing(player)) {
                        player.sendMessage(Component.text(notEditing, NamedTextColor.GRAY));
                        yield true;
                    }
                }
                if (!plugin.droneMeshPose().cancel(player, true)) {
                    player.sendMessage(Component.text(notEditing, NamedTextColor.GRAY));
                }
                yield true;
            }
            case "save", "done" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
                    yield true;
                }
                if (camera && !plugin.droneMeshPose().isCameraEditing(player)) {
                    player.sendMessage(Component.text(notEditing, NamedTextColor.GRAY));
                    yield true;
                }
                if (!camera && !plugin.droneMeshPose().isMeshEditing(player)) {
                    player.sendMessage(Component.text(notEditing, NamedTextColor.GRAY));
                    yield true;
                }
                if (!plugin.droneMeshPose().save(player)) {
                    player.sendMessage(Component.text(notEditing, NamedTextColor.GRAY));
                }
                yield true;
            }
            case "copy", "clone" -> {
                if (args.length < 4) {
                    sender.sendMessage(Component.text("Usage: /warz " + cmd + " copy <from> <to>", NamedTextColor.RED));
                    yield true;
                }
                BigDroneType from = resolveDroneType(args[2]);
                BigDroneType to = resolveDroneType(args[3]);
                if (from == null) {
                    sender.sendMessage(Component.text("Unknown from-type: " + args[2], NamedTextColor.RED));
                    yield true;
                }
                if (to == null) {
                    sender.sendMessage(Component.text("Unknown to-type: " + args[3], NamedTextColor.RED));
                    yield true;
                }
                if (camera) {
                    plugin.droneMeshPose().copyCamera(sender, from, to);
                } else {
                    plugin.droneMeshPose().copy(sender, from, to);
                }
                yield true;
            }
            case "reload" -> {
                plugin.droneMeshPose().load();
                sender.sendMessage(Component.text("Reloaded drone-mesh-pose.yml", NamedTextColor.GREEN));
                yield true;
            }
            default -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Players only to enter editor.", NamedTextColor.RED));
                    yield true;
                }
                BigDroneType type = resolveDroneType(action);
                if (type == null) {
                    sender.sendMessage(Component.text("Unknown type. Try: mq9 rq4 mq4c mq1c rq170 x47b x37b",
                            NamedTextColor.RED));
                    yield true;
                }
                if (camera) {
                    plugin.droneMeshPose().beginCamera(player, type);
                } else {
                    plugin.droneMeshPose().begin(player, type);
                }
                yield true;
            }
        };
    }

    /** Null if the token is not a known airframe id. */
    private static BigDroneType resolveDroneType(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String action = token.toLowerCase(Locale.ROOT);
        String key = action.replace('-', '_');
        for (BigDroneType t : BigDroneType.values()) {
            if (t.id().equals(key) || t.name().equalsIgnoreCase(action)
                    || t.loreId().equalsIgnoreCase(action)) {
                return t;
            }
        }
        if (action.contains("mq9") || action.equals("reaper") || action.equals("bigdrone")) {
            return BigDroneType.MQ9;
        }
        return null;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("warz.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        plugin.reloadGuns();
        if (plugin.droneMeshPose() != null) {
            plugin.droneMeshPose().load();
        }
        sender.sendMessage(Component.text("Reloaded "
                + plugin.registry().all().size() + " guns, "
                + plugin.rounds().all().size() + " rounds"
                + (plugin.droneMeshPose() != null ? ", drone mesh poses" : "")
                + ".", NamedTextColor.GREEN));
        return true;
    }

    private boolean list(CommandSender sender) {
        sender.sendMessage(Component.text("-------WarZ-------", NamedTextColor.GRAY));
        for (GunDefinition gun : plugin.registry().all()) {
            sender.sendMessage(ItemFactory.colorize("&7 - " + gun.displayName()
                    + " &e(" + gun.fileName() + "/" + gun.customModelData() + ")"
                    + (gun.consumable()
                    ? " &7AMMO: &cSELF"
                    : " &7CAL: &c" + gun.ammoCaliber()
                    + " &7(" + gun.ammoMaterial() + " x" + gun.ammoAmtNeeded() + ")")));
        }
        sender.sendMessage(Component.text("---------------------", NamedTextColor.GRAY));
        return true;
    }

    private boolean listAmmo(CommandSender sender) {
        sender.sendMessage(Component.text("-------ROUNDS-------", NamedTextColor.GOLD));
        for (var round : plugin.rounds().all().stream()
                .sorted(java.util.Comparator.comparing(r -> r.fileName()))
                .toList()) {
            sender.sendMessage(ItemFactory.colorize("&7 - " + round.displayName()
                    + " &e(" + round.fileName() + ")"
                    + " &7cal:&c" + round.caliber()
                    + " &7dmg x&c" + String.format("%.2f", round.damageMult())
                    + (round.tracer() ? " &aTRACER" : "")));
        }
        sender.sendMessage(Component.text("---------------------", NamedTextColor.GOLD));
        return true;
    }

    private boolean giveAmmo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("warz.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /warz giveammo <round> [amount] [player]", NamedTextColor.RED));
            return true;
        }
        var roundOpt = plugin.rounds().get(args[1]);
        if (roundOpt.isEmpty()) {
            sender.sendMessage(Component.text("Unknown round: " + args[1], NamedTextColor.RED));
            return true;
        }
        int amount = 64;
        Player target = null;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Integer.parseInt(args[2]));
                if (args.length >= 4) {
                    target = Bukkit.getPlayerExact(args[3]);
                }
            } catch (NumberFormatException e) {
                target = Bukkit.getPlayerExact(args[2]);
                if (args.length >= 4) {
                    try {
                        amount = Math.max(1, Integer.parseInt(args[3]));
                    } catch (NumberFormatException ignored) {
                        amount = 64;
                    }
                }
            }
        }
        if (target == null) {
            if (sender instanceof Player player) {
                target = player;
            } else {
                sender.sendMessage(Component.text("Specify a player.", NamedTextColor.RED));
                return true;
            }
        }
        final Player recipient = target;
        ItemStack ammo = plugin.items().createRound(roundOpt.get(), amount);
        recipient.getInventory().addItem(ammo).values().forEach(left ->
                recipient.getWorld().dropItemNaturally(recipient.getLocation(), left));
        sender.sendMessage(Component.text("Gave " + amount + "x " + roundOpt.get().fileName()
                + " to " + recipient.getName(), NamedTextColor.GREEN));
        return true;
    }

    private boolean toggle(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("warz.user")) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        GunPlayerSession session = plugin.sessions().get(player);
        session.setEnabled(!session.enabled());
        player.sendMessage(Component.text("Guns turned ", NamedTextColor.GRAY)
                .append(Component.text(session.enabled() ? "ON" : "OFF",
                        session.enabled() ? NamedTextColor.GREEN : NamedTextColor.RED)));
        return true;
    }

    private boolean give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("warz.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /warz give <gun> [player] [amount]", NamedTextColor.RED));
            return true;
        }
        var defOpt = plugin.registry().get(args[1]);
        if (defOpt.isEmpty()) {
            sender.sendMessage(Component.text("Unknown gun: " + args[1], NamedTextColor.RED));
            return true;
        }
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(Component.text("Specify a player.", NamedTextColor.RED));
            return true;
        }
        if (target == null) {
            sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
            return true;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.max(1, Integer.parseInt(args[3]));
            } catch (NumberFormatException ignored) {
                amount = 1;
            }
        }
        ItemStack gun = plugin.items().create(defOpt.get(), amount);
        target.getInventory().addItem(gun).values().forEach(left ->
                target.getWorld().dropItemNaturally(target.getLocation(), left));
        sender.sendMessage(Component.text("Gave " + amount + "x " + defOpt.get().fileName() + " to " + target.getName(), NamedTextColor.GREEN));
        return true;
    }

    private boolean editor(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("warz.admin")) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length >= 2) {
            var defOpt = plugin.registry().get(args[1]);
            if (defOpt.isEmpty()) {
                player.sendMessage(Component.text("Unknown gun: " + args[1], NamedTextColor.RED));
                return true;
            }
            plugin.editor().openEdit(player, defOpt.get());
            return true;
        }
        plugin.editor().openBrowser(player);
        return true;
    }

    private boolean create(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("warz.admin")) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        plugin.createMenu().open(player);
        return true;
    }

    private boolean createGun(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("warz.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        plugin.editor().openCreate(player);
        return true;
    }

    private boolean giveMenu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("warz.admin")) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        try {
            plugin.giveMenu().open(player);
        } catch (RuntimeException ex) {
            player.sendMessage(Component.text("Could not open give menu.", NamedTextColor.RED));
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Give menu failed", ex);
        }
        return true;
    }

    private boolean laserDebug(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        boolean linked = plugin.companions() != null && plugin.companions().hasCompanion(player);
        var bridge = plugin.laserBridge();
        sender.sendMessage(Component.text("---- Laser / Companion ----", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Companion linked: ", NamedTextColor.GRAY)
                .append(Component.text(linked ? "YES" : "NO", linked ? NamedTextColor.GREEN : NamedTextColor.RED)));
        if (bridge != null) {
            sender.sendMessage(Component.text("Last beam segments: " + bridge.lastSegmentCount()
                    + "  viewers: " + bridge.lastViewerCount(), NamedTextColor.GRAY));
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        var held = plugin.sessions().get(player).heldGun(hand);
        if (held.isPresent()) {
            GunDefinition def = held.get();
            sender.sendMessage(Component.text("Held: " + def.fileName()
                    + " laser=" + def.laserSight()
                    + " aimOnly=" + def.laserSightAimOnly()
                    + " color=" + (def.laserSightColor() == null ? "?" : Integer.toHexString(def.laserSightColor().asRGB())),
                    NamedTextColor.AQUA));
        } else {
            sender.sendMessage(Component.text("Held: (not a gun)", NamedTextColor.DARK_GRAY));
        }
        if (!linked) {
            sender.sendMessage(Component.text("Install pvpgunminus-client on Fabric and rejoin.", NamedTextColor.YELLOW));
        }
        return true;
    }

    private boolean weather(CommandSender sender, String[] args) {
        if (!sender.hasPermission("warz.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        var wx = plugin.weather();
        if (wx == null) {
            sender.sendMessage(Component.text("Weather service unavailable.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2 || args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(Component.text("Weather: ", NamedTextColor.GOLD)
                    .append(Component.text(wx.eventName(), NamedTextColor.YELLOW))
                    .append(Component.text(String.format(Locale.ROOT, "  intensity=%.0f%%  wind=%.0f%%",
                            wx.intensity() * 100f, wx.wind() * 100f), NamedTextColor.GRAY)));
            return true;
        }
        String mode = args[1].toLowerCase(Locale.ROOT);
        if (mode.equals("clear") || mode.equals("off") || mode.equals("stop")) {
            wx.clear(true);
            sender.sendMessage(Component.text("Clearing weather.", NamedTextColor.AQUA));
            return true;
        }
        if (mode.equals("sandstorm") || mode.equals("sand") || mode.equals("dust") || mode.equals("duststorm")) {
            int secs = 180;
            float intensity = 0.85f;
            if (args.length >= 3) {
                try {
                    secs = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {
                }
            }
            if (args.length >= 4) {
                try {
                    intensity = Float.parseFloat(args[3]);
                    if (intensity > 1.5f) {
                        intensity = intensity / 100f;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            wx.startSandstorm(secs, intensity, true);
            sender.sendMessage(Component.text(String.format(Locale.ROOT,
                    "Sandstorm started (%.0f%%, %s).", intensity * 100f,
                    secs <= 0 ? "indefinite" : secs + "s"), NamedTextColor.GOLD));
            return true;
        }
        sender.sendMessage(Component.text("Usage: /warz weather <sandstorm|clear|status> [seconds] [intensity]", NamedTextColor.RED));
        return true;
    }

    private boolean drone(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("exit")) {
            player.sendMessage(Component.text("Usage: /warz drone exit", NamedTextColor.GRAY));
            return true;
        }
        if (!plugin.bigDrone().isPiloting(player)) {
            plugin.bigDrone().clearPilotInvisibility(player);
            player.sendMessage(Component.text("Not piloting a drone (cleared invis)", NamedTextColor.YELLOW));
            return true;
        }
        plugin.bigDrone().exit(player, "command");
        return true;
    }

    private boolean setDroneSeat(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("warz.admin")) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (plugin.droneSeats() == null) {
            player.sendMessage(Component.text("Drone seats unavailable.", NamedTextColor.RED));
            return true;
        }
        String vehicle = args.length >= 2 ? args[1] : DroneSeatService.VEHICLE_MQ9;
        plugin.droneSeats().setLooking(player, vehicle);
        return true;
    }

    private boolean delDroneSeat(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("warz.admin")) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (plugin.droneSeats() != null) {
            plugin.droneSeats().deleteLooking(player);
        }
        return true;
    }

    private boolean listDroneSeats(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("warz.admin")) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (plugin.droneSeats() != null) {
            plugin.droneSeats().list(player);
        }
        return true;
    }

    private boolean prone(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        if (plugin.prone() == null) {
            sender.sendMessage(Component.text("Prone service unavailable.", NamedTextColor.RED));
            return true;
        }
        plugin.prone().toggle(player);
        return true;
    }

    private boolean createChest(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("warz.admin")) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (plugin.lootRestock() == null) {
            player.sendMessage(Component.text("Loot restock unavailable.", NamedTextColor.RED));
            return true;
        }
        plugin.lootRestock().beginCreateChest(player);
        return true;
    }

    private boolean createZone(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("warz.admin")) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /warz createzone <1-7>", NamedTextColor.RED));
            player.sendMessage(Component.text("Select a 2D area with the WorldEdit wand first.", NamedTextColor.GRAY));
            return true;
        }
        int zone;
        try {
            zone = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Zone must be a number 1–7.", NamedTextColor.RED));
            return true;
        }
        if (plugin.lootRestock() == null) {
            player.sendMessage(Component.text("Loot restock unavailable.", NamedTextColor.RED));
            return true;
        }
        plugin.lootRestock().createZone(player, zone);
        return true;
    }

    private boolean delChest(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("warz.admin")) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (plugin.lootRestock() == null) {
            return true;
        }
        plugin.lootRestock().deleteLookingChest(player);
        return true;
    }

    private boolean lootStatus(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        if (plugin.lootRestock() == null) {
            return true;
        }
        plugin.lootRestock().sendStatus(player);
        return true;
    }

    private boolean reloot(CommandSender sender) {
        if (!sender.hasPermission("warz.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (plugin.lootRestock() == null) {
            sender.sendMessage(Component.text("Loot restock unavailable.", NamedTextColor.RED));
            return true;
        }
        int n = plugin.lootRestock().forceReloot(true);
        sender.sendMessage(ItemFactory.colorize("&7Relooted &f" + n + " &7chests. Timer reset to 600s."));
        return true;
    }

    private boolean listChests(CommandSender sender) {
        if (!sender.hasPermission("warz.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (plugin.lootRestock() == null) {
            return true;
        }
        List<String> rows = plugin.lootRestock().listChests();
        sender.sendMessage(Component.text("---- Loot chests (" + rows.size() + ") ----", NamedTextColor.GOLD));
        if (rows.isEmpty()) {
            sender.sendMessage(Component.text("None yet. /warz createchest", NamedTextColor.GRAY));
            return true;
        }
        for (String row : rows) {
            sender.sendMessage(Component.text(" - " + row, NamedTextColor.GRAY));
        }
        return true;
    }

    private boolean explosionRegen(CommandSender sender, String[] args) {
        if (!sender.hasPermission("warz.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        var regen = plugin.explosionRegen();
        if (regen == null) {
            sender.sendMessage(Component.text("Explosion regen unavailable.", NamedTextColor.RED));
            return true;
        }
        String mode = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (mode) {
            case "on", "enable", "true", "1" -> {
                regen.setEnabled(true);
                sender.sendMessage(Component.text("Explosion regen ON — broken blocks will grow back.", NamedTextColor.GREEN));
            }
            case "off", "disable", "false", "0" -> {
                regen.setEnabled(false);
                sender.sendMessage(Component.text("Explosion regen OFF.", NamedTextColor.YELLOW));
            }
            case "toggle" -> {
                boolean on = regen.toggle();
                sender.sendMessage(Component.text("Explosion regen " + (on ? "ON" : "OFF") + ".",
                        on ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
            case "clear" -> {
                int n = regen.queueSize();
                regen.clearQueue();
                sender.sendMessage(Component.text("Cleared " + n + " pending regen block(s).", NamedTextColor.AQUA));
            }
            case "now", "flush", "instant", "all" -> {
                int n = regen.flushNow();
                sender.sendMessage(Component.text("Regenerated " + n + " block(s) instantly.", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text(
                    "Regen: " + (regen.enabled() ? "ON" : "OFF")
                            + " · queued " + regen.queueSize()
                            + " · /warz regen <on|off|toggle|status|clear|now>",
                    NamedTextColor.GRAY));
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("reload", "list", "listammo", "toggle", "give", "giveammo", "editor", "create", "menu", "drone",
                    "setdroneseat", "deldroneseat", "listdroneseats", "laserdebug", "weather", "prone",
                    "createchest", "createzone", "delchest", "loot", "reloot", "listchests", "regen",
                    "crashsites", "dronepads", "dronepose", "dronecam", "clearcorpses", "anomaly"), args[0]);
        }
        if (args.length == 2 && isDroneTuneCommand(args[0])) {
            List<String> types = new ArrayList<>();
            for (BigDroneType t : BigDroneType.values()) {
                types.add(t.id());
            }
            types.addAll(List.of("list", "copy", "exit", "save", "reload"));
            return filter(types, args[1]);
        }
        if (args.length >= 3 && isDroneTuneCommand(args[0]) && args[1].equalsIgnoreCase("copy")) {
            List<String> types = new ArrayList<>();
            for (BigDroneType t : BigDroneType.values()) {
                types.add(t.id());
            }
            return filter(types, args[args.length - 1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("anomaly") || args[0].equalsIgnoreCase("anomalies")
                || args[0].equalsIgnoreCase("paranormal"))) {
            return filter(List.of("list", "clear", "spawn"), args[1]);
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("anomaly") || args[0].equalsIgnoreCase("anomalies")
                || args[0].equalsIgnoreCase("paranormal")) && args[1].equalsIgnoreCase("spawn")) {
            return filter(List.of("shadow", "echo", "watcher", "crawler", "static_man", "residual", "mimic",
                    "stalker", "void", "burn_in", "distortion", "false_contact", "glitch", "observer", "imposter"),
                    args[2]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("crashsites") || args[0].equalsIgnoreCase("crashsite")
                || args[0].equalsIgnoreCase("crashes"))) {
            return filter(List.of("list", "clear"), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("dronepads") || args[0].equalsIgnoreCase("dronepad")
                || args[0].equalsIgnoreCase("pads"))) {
            return filter(List.of("list", "clear"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("drone")) {
            return filter(List.of("exit"), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("setdroneseat") || args[0].equalsIgnoreCase("droneseat")
                || args[0].equalsIgnoreCase("setseat"))) {
            return filter(List.of("drone", "mq9reaper"), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("createzone") || args[0].equalsIgnoreCase("zone"))) {
            return filter(List.of("1", "2", "3", "4", "5", "6", "7"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("weather")) {
            return filter(List.of("sandstorm", "clear", "status"), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("regen") || args[0].equalsIgnoreCase("explosionregen")
                || args[0].equalsIgnoreCase("xpregen") || args[0].equalsIgnoreCase("blockregen"))) {
            return filter(List.of("on", "off", "toggle", "status", "clear", "now"), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("editor") || args[0].equalsIgnoreCase("edit"))) {
            return filter(plugin.registry().all().stream().map(GunDefinition::fileName).collect(Collectors.toList()), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("giveammo")) {
            return filter(plugin.rounds().ids(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return null;
        }
        return List.of();
    }

    private static boolean isDroneTuneCommand(String sub) {
        if (sub == null) {
            return false;
        }
        String s = sub.toLowerCase(Locale.ROOT);
        return s.equals("dronepose") || s.equals("meshpose") || s.equals("droneedit")
                || s.equals("dronecam") || s.equals("dronecamera") || s.equals("droneoptic")
                || s.equals("sensorcam") || s.equals("opcam");
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(option);
            }
        }
        return out;
    }
}
