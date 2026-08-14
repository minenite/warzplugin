package net.minenite.warzplugin;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import com.local.warz.command.GunsCommand;
import com.local.warz.config.GunRegistry;
import com.local.warz.config.RoundRegistry;
import com.local.warz.gui.GiveGunMenuListener;
import com.local.warz.gui.GiveGunMenuService;
import com.local.warz.gui.GunEditorListener;
import com.local.warz.gui.GunEditorService;
import com.local.warz.gui.GunWorkbenchGui;
import com.local.warz.gui.GunWorkbenchListener;
import com.local.warz.gui.WarzCreateMenuService;
import com.local.warz.projectile.BulletManager;
import com.local.warz.runtime.BigDroneListener;
import com.local.warz.runtime.BigDroneService;
import com.local.warz.runtime.BlastShockService;
import com.local.warz.runtime.ChainlinkService;
import com.local.warz.runtime.CompanionClients;
import com.local.warz.runtime.CorpseService;
import com.local.warz.runtime.CrashSiteService;
import com.local.warz.runtime.CreativeMaterializeListener;
import com.local.warz.runtime.DroneDatalinkService;
import com.local.warz.runtime.DroneMeshPoseService;
import com.local.warz.runtime.DronePadService;
import com.local.warz.runtime.DroneSeatService;
import com.local.warz.runtime.DroneStrikeEffects;
import com.local.warz.runtime.ExplosionRegenService;
import com.local.warz.runtime.FlareListener;
import com.local.warz.runtime.FlareService;
import com.local.warz.runtime.FlashlightService;
import com.local.warz.runtime.GlassListener;
import com.local.warz.runtime.GlassService;
import com.local.warz.runtime.GrappleService;
import com.local.warz.runtime.GroundEmergeListener;
import com.local.warz.runtime.GunListener;
import com.local.warz.runtime.GunPoseSync;
import com.local.warz.runtime.GunTooltipListener;
import com.local.warz.runtime.GunWorkbenchService;
import com.local.warz.runtime.HydrazineService;
import com.local.warz.runtime.InfectionService;
import com.local.warz.runtime.ItemFactory;
import com.local.warz.runtime.JavelinService;
import com.local.warz.runtime.KillFeedService;
import com.local.warz.runtime.LaserCompanionBridge;
import com.local.warz.runtime.LavaHeatService;
import com.local.warz.runtime.LongProngsService;
import com.local.warz.runtime.MagazineListener;
import com.local.warz.runtime.MedicalService;
import com.local.warz.runtime.NvgListener;
import com.local.warz.runtime.PeqService;
import com.local.warz.runtime.ProfileStatsService;
import com.local.warz.runtime.ProneService;
import com.local.warz.runtime.RestraintService;
import com.local.warz.runtime.RazorWireService;
import com.local.warz.runtime.ScopeSync;
import com.local.warz.runtime.ScubaService;
import com.local.warz.runtime.SessionManager;
import com.local.warz.runtime.SmokeListener;
import com.local.warz.runtime.SmokeService;
import com.local.warz.runtime.ThirstService;
import com.local.warz.runtime.WaterService;
import com.local.warz.runtime.WeatherService;
import com.local.warz.runtime.anomaly.AnomalyService;

/**
 * Paper WarZ gun engine (sticks + workbench + lasers) hosted inside Minenite WarzPlugin.
 */
final class GunEngine {
    final GunRegistry registry;
    final RoundRegistry rounds;
    final SessionManager sessions;
    final BulletManager bullets;
    final ItemFactory items;
    final GunEditorService editor;
    final GiveGunMenuService giveMenu;
    final CompanionClients companions;
    final LaserCompanionBridge laserBridge;
    final GunsCommand gunsCommand;
    NvgListener nvgListener;
    BigDroneService bigDrone;
    DroneStrikeEffects strikeEffects;
    BigDroneListener bigDroneListener;
    DroneSeatService droneSeats;
    DroneDatalinkService datalink;
    DronePadService dronePads;
    DroneMeshPoseService droneMeshPose;
    WarzCreateMenuService createMenu;
    FlashlightService flashlight;
    PeqService peq;
    JavelinService javelin;
    WeatherService weather;
    ProneService prone;
    GunPoseSync gunPoses;
    ScopeSync scopeSync;
    SmokeService smoke;
    SmokeListener smokeListener;
    FlareService flares;
    FlareListener flareListener;
    GunWorkbenchService workbenches;
    GunWorkbenchGui workbenchGui;
    GlassService glass;
    MedicalService medical;
    KillFeedService killFeed;
    RazorWireService razorWire;
    ChainlinkService chainlink;
    CorpseService corpses;
    ScubaService scuba;
    ThirstService thirst;
    InfectionService infection;
    HydrazineService hydrazine;
    LavaHeatService lavaHeat;
    LongProngsService longProngs;
    RestraintService restraints;
    WaterService water;
    ProfileStatsService profileStats;
    GrappleService grapple;
    ExplosionRegenService explosionRegen;
    CrashSiteService crashSites;
    BlastShockService blastShock;
    GroundEmergeListener groundEmerge;
    AnomalyService anomalies;
    BukkitTask tickTask;
    private WarzPlugin plugin;

    GunEngine(WarzPlugin plugin) {
        this.registry = new GunRegistry(plugin);
        this.rounds = new RoundRegistry(plugin);
        this.sessions = new SessionManager(plugin);
        this.bullets = new BulletManager();
        this.items = new ItemFactory(plugin);
        this.editor = new GunEditorService(plugin);
        this.giveMenu = new GiveGunMenuService(plugin);
        this.companions = new CompanionClients(plugin);
        this.laserBridge = new LaserCompanionBridge(plugin);
        this.gunsCommand = new GunsCommand(plugin);
    }

    void start(WarzPlugin plugin) {
        this.plugin = plugin;
        this.bigDrone = new BigDroneService(plugin);
        this.strikeEffects = new DroneStrikeEffects(plugin);
        this.droneSeats = new DroneSeatService(plugin);
        this.datalink = new DroneDatalinkService(plugin);
        this.dronePads = new DronePadService(plugin);
        this.droneMeshPose = new DroneMeshPoseService(plugin);
        this.createMenu = new WarzCreateMenuService(plugin);
        companions.register();
        droneMeshPose.register();

        rounds.reload();
        registry.reload();
        Bukkit.getOnlinePlayers().forEach(sessions::join);

        Bukkit.getPluginManager().registerEvents(new GunListener(plugin), plugin);
        Bukkit.getPluginManager().registerEvents(new com.local.warz.runtime.GunshotNoiseService(plugin), plugin);
        Bukkit.getPluginManager().registerEvents(new GunEditorListener(plugin), plugin);
        Bukkit.getPluginManager().registerEvents(new GiveGunMenuListener(plugin), plugin);
        this.nvgListener = new NvgListener(plugin);
        Bukkit.getPluginManager().registerEvents(nvgListener, plugin);
        nvgListener.start();
        this.bigDroneListener = new BigDroneListener(plugin);
        Bukkit.getPluginManager().registerEvents(bigDroneListener, plugin);
        Bukkit.getPluginManager().registerEvents(dronePads, plugin);
        Bukkit.getPluginManager().registerEvents(createMenu, plugin);
        bigDroneListener.registerChannel();
        strikeEffects.registerChannel();
        this.flashlight = new FlashlightService(plugin);
        Bukkit.getPluginManager().registerEvents(flashlight, plugin);
        flashlight.start();
        this.peq = new PeqService(plugin);
        peq.registerChannel();
        this.javelin = new JavelinService(plugin);
        javelin.registerChannel();
        this.weather = new WeatherService(plugin);
        weather.registerChannel();
        this.prone = new ProneService(plugin);
        Bukkit.getPluginManager().registerEvents(prone, plugin);
        prone.registerChannel();
        this.groundEmerge = new GroundEmergeListener(plugin);
        Bukkit.getPluginManager().registerEvents(groundEmerge, plugin);
        this.gunPoses = new GunPoseSync(plugin);
        gunPoses.registerChannel();
        this.scopeSync = new ScopeSync(plugin);
        scopeSync.registerChannel();
        this.smoke = new SmokeService(plugin);
        smoke.registerChannel();
        this.smokeListener = new SmokeListener(plugin);
        Bukkit.getPluginManager().registerEvents(smokeListener, plugin);
        this.flares = new FlareService(plugin);
        flares.registerChannel();
        this.flareListener = new FlareListener(plugin);
        Bukkit.getPluginManager().registerEvents(flareListener, plugin);
        this.workbenches = new GunWorkbenchService(plugin);
        workbenches.registerChannel();
        this.workbenchGui = new GunWorkbenchGui(plugin);
        Bukkit.getPluginManager().registerEvents(new GunWorkbenchListener(plugin), plugin);
        this.glass = new GlassService(plugin);
        glass.registerChannel();
        Bukkit.getPluginManager().registerEvents(new GlassListener(plugin), plugin);
        this.medical = new MedicalService(plugin);
        Bukkit.getPluginManager().registerEvents(medical, plugin);
        medical.start();
        this.killFeed = new KillFeedService(plugin);
        Bukkit.getPluginManager().registerEvents(killFeed, plugin);
        Bukkit.getPluginManager().registerEvents(new GunTooltipListener(plugin), plugin);
        Bukkit.getPluginManager().registerEvents(new CreativeMaterializeListener(plugin), plugin);
        Bukkit.getPluginManager().registerEvents(new MagazineListener(plugin), plugin);
        this.razorWire = new RazorWireService(plugin);
        Bukkit.getPluginManager().registerEvents(razorWire, plugin);
        razorWire.start();
        this.chainlink = new ChainlinkService(plugin);
        Bukkit.getPluginManager().registerEvents(chainlink, plugin);
        chainlink.start();
        this.corpses = new CorpseService(plugin);
        Bukkit.getPluginManager().registerEvents(corpses, plugin);
        corpses.start();
        this.scuba = new ScubaService(plugin);
        Bukkit.getPluginManager().registerEvents(scuba, plugin);
        scuba.start();
        this.thirst = new ThirstService(plugin);
        Bukkit.getPluginManager().registerEvents(thirst, plugin);
        thirst.start();
        this.infection = new InfectionService(plugin);
        Bukkit.getPluginManager().registerEvents(infection, plugin);
        infection.start();
        this.hydrazine = new HydrazineService(plugin);
        Bukkit.getPluginManager().registerEvents(hydrazine, plugin);
        hydrazine.start();
        this.lavaHeat = new LavaHeatService(plugin);
        lavaHeat.start();
        this.longProngs = new LongProngsService(plugin);
        Bukkit.getPluginManager().registerEvents(longProngs, plugin);
        longProngs.start();
        this.restraints = new RestraintService(plugin);
        Bukkit.getPluginManager().registerEvents(restraints, plugin);
        restraints.start();
        this.water = new WaterService(plugin);
        Bukkit.getPluginManager().registerEvents(water, plugin);
        water.start();
        this.profileStats = new ProfileStatsService(plugin);
        profileStats.load();
        Bukkit.getPluginManager().registerEvents(profileStats, plugin);
        profileStats.start();
        this.grapple = new GrappleService(plugin);
        Bukkit.getPluginManager().registerEvents(grapple, plugin);
        grapple.start();
        this.explosionRegen = new ExplosionRegenService(plugin);
        Bukkit.getPluginManager().registerEvents(explosionRegen, plugin);
        explosionRegen.loadHolds();
        if (groundEmerge != null) {
            groundEmerge.setRegenAreaCheck(explosionRegen::isInRegenArea);
        }
        this.crashSites = new CrashSiteService(plugin);
        Bukkit.getPluginManager().registerEvents(crashSites, plugin);
        this.blastShock = new BlastShockService(plugin);
        Bukkit.getPluginManager().registerEvents(blastShock, plugin);
        this.anomalies = new AnomalyService(plugin);
        anomalies.start();

        this.tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            tickSafe("sessions", sessions::tick);
            tickSafe("bullets", bullets::tick);
            if (bigDrone != null) {
                tickSafe("bigDrone", bigDrone::tick);
            }
            if (javelin != null) {
                tickSafe("javelin", javelin::tick);
            }
            if (weather != null) {
                tickSafe("weather", weather::tick);
            }
            if (prone != null) {
                tickSafe("prone", prone::tick);
            }
            if (smoke != null) {
                tickSafe("smoke", smoke::tick);
            }
            if (flares != null) {
                tickSafe("flares", flares::tick);
            }
            if (smokeListener != null) {
                tickSafe("smokeListener", smokeListener::tick);
            }
            if (gunPoses != null) {
                tickSafe("gunPoses", () -> {
                    for (var p : Bukkit.getOnlinePlayers()) {
                        gunPoses.tickPlayer(p);
                    }
                });
            }
            if (scopeSync != null) {
                tickSafe("scopeSync", () -> {
                    for (var p : Bukkit.getOnlinePlayers()) {
                        scopeSync.tickPlayer(p);
                    }
                });
            }
            if (explosionRegen != null) {
                tickSafe("explosionRegen", explosionRegen::tick);
            }
            if (anomalies != null) {
                tickSafe("anomalies", anomalies::tick);
            }
        }, 20L, 1L);

        plugin.getLogger().info("Guns loaded: " + registry.all().size()
                + " weapons / " + rounds.all().size() + " rounds (all sticks).");
    }

    private void tickSafe(String name, Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            if (plugin != null) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "GunEngine tick '" + name + "' failed", t);
            }
        }
    }

    void stop() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (anomalies != null) {
            anomalies.stop();
        }
        if (nvgListener != null) {
            nvgListener.stop();
        }
        if (droneMeshPose != null) {
            droneMeshPose.unregister();
        }
        if (bigDrone != null) {
            bigDrone.exitAll();
        }
        if (crashSites != null) {
            crashSites.shutdown();
        }
        if (bigDroneListener != null) {
            bigDroneListener.unregisterChannel();
        }
        if (strikeEffects != null) {
            strikeEffects.unregisterChannel();
        }
        if (flashlight != null) {
            flashlight.stop();
        }
        if (peq != null) {
            peq.unregisterChannel();
        }
        if (javelin != null) {
            javelin.unregisterChannel();
        }
        if (weather != null) {
            weather.unregisterChannel();
        }
        if (prone != null) {
            prone.clearAll();
            prone.unregisterChannel();
        }
        if (gunPoses != null) {
            gunPoses.clearAll();
            gunPoses.unregisterChannel();
        }
        if (scopeSync != null) {
            scopeSync.unregisterChannel();
        }
        if (smoke != null) {
            smoke.clearAll();
            smoke.unregisterChannel();
        }
        if (flares != null) {
            flares.clearAll();
            flares.unregisterChannel();
        }
        if (workbenches != null) {
            workbenches.unregisterChannel();
        }
        if (glass != null) {
            glass.flush();
            glass.unregisterChannel();
        }
        if (medical != null) {
            medical.stop();
        }
        if (razorWire != null) {
            razorWire.stop();
        }
        if (chainlink != null) {
            chainlink.stop();
        }
        if (corpses != null) {
            corpses.stop();
        }
        if (companions != null) {
            companions.unregister();
        }
    }

    boolean handleWarz(org.bukkit.command.CommandSender sender, String[] args) {
        return gunsCommand.onCommand(sender, null, "warz", args);
    }
}
