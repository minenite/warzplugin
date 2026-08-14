package com.local.warz.runtime;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.combat.ImpactEffects;
import com.local.warz.config.AmmoCaliber;
import com.local.warz.event.GunFireEvent;
import com.local.warz.event.GunReloadEvent;
import com.local.warz.model.GunDefinition;
import com.local.warz.model.GunInstance;
import com.local.warz.model.RoundDefinition;
import com.local.warz.projectile.Bullet;
import com.local.warz.projectile.SniperBallistics;
import com.local.warz.util.LaserBeams;
import com.local.warz.util.LaserOptics;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class GunPlayerSession {
    private final WarzPlugin plugin;
    private final UUID playerId;
    private final Map<String, GunInstance> guns = new HashMap<>();
    private final Random random = new Random();
    private final Set<Block> laserLights = new HashSet<>();
    private boolean laserActive;
    private boolean enabled = true;
    private int ticks;
    private int lastFiredTicks;
    private GunInstance currentlyFiring;
    private GunInstance lastFiredGun;
    private ItemStack lastHeldItem;
    private String lastUsedRoundId;
    /** Session tick when hipfire pose tag should expire (0 = inactive). */
    private int hipfireUntilTick;
    /** Armed grenade/flash/molotov cook — fuse runs in hand until throw or boom. */
    private CookState cook;
    /** True while the engage/throw button is still held (needs release before throw click). */
    private boolean cookButtonHeld;
    /**
     * Inventory slot of the magazine currently seated in each gun (by gun file name).
     * Drum / extended mags fire their full capacity before requiring a reload swap.
     */
    private final Map<String, Integer> activeMagSlot = new HashMap<>();
    /** Per-gun jam flags (cleared on reload). */
    private final Map<String, Boolean> jammed = new HashMap<>();
    /** Per-gun barrel heat 0–100; builds on fire, cools when idle. */
    private final Map<String, Integer> barrelHeat = new HashMap<>();
    private final Map<String, Boolean> overheatWarned = new HashMap<>();
    /**
     * Temporary gun stack for mag-fit checks during Q-drop reload.
     * Drop events remove the item from the hand before the handler runs, so
     * {@link #heldGunItem()} would miss adapters and reject bridged mags.
     */
    private ItemStack magFitGunOverride;
    /** 0..1 sniper breath stamina (Shift while ADS). */
    private float breathStamina = 1f;
    private boolean holdingBreath;
    /** Explicit ADS — not inferred from any Slowness (bleed/etc. used to false-trigger scope). */
    private boolean adsActive;
    /** Debounce overlapping left-click sources (interact + arm swing) so ADS does not toggle twice. */
    private long lastAimClickMs;
    /**
     * Ignore arm-swing ADS briefly after RMB fire. Looking at entities sends UseEntity + arm
     * swing; without this, RMB on a zombie scopes instead of (or as well as) shooting.
     */
    private long ignoreSwingAimUntilMs;
    /** Ignore aim toggles briefly after Q-drop (reload) so drop never scopes. */
    private int ignoreAimUntilTick;
    /** Subtitle reload bar is showing — clear it when the mag seats. */
    private boolean reloadHudShown;
    /** Sway offsets applied to laser + shots while sniper ADS (matches companion scope). */
    private float swayYaw;
    private float swayPitch;
    private float swayPhase;

    /** Default cook/fuse window when a cookable has no {@code timeUntilRelease} (3 seconds). */
    private static final int DEFAULT_COOK_FUSE_TICKS = 20 * 3;
    private static final int OVERHEAT_SMOKE_AT = 55;
    private static final int OVERHEAT_WARN_AT = 70;

    public GunPlayerSession(WarzPlugin plugin, Player player) {
        this.plugin = plugin;
        this.playerId = player.getUniqueId();
        for (GunDefinition def : plugin.registry().all()) {
            guns.put(def.fileName().toLowerCase(Locale.ROOT), new GunInstance(def));
        }
    }

    public Player player() {
        return Bukkit.getPlayer(playerId);
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            clearLaserLights();
        }
    }

    private void clearLaserLights() {
        LaserBeams.clearLights(laserLights);
        if (laserActive) {
            Player player = player();
            if (player != null && plugin.laserBridge() != null) {
                plugin.laserBridge().clearBeam(player);
            }
            laserActive = false;
        }
    }

    public void rebuildGuns() {
        guns.clear();
        for (GunDefinition def : plugin.registry().all()) {
            guns.put(def.fileName().toLowerCase(Locale.ROOT), new GunInstance(def));
        }
    }

    public void onClick(String clickType, Projectile alreadyFired) {
        if (!enabled) {
            return;
        }
        Player player = player();
        if (player == null) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        Optional<GunDefinition> held = heldGun(hand);
        if (held.isEmpty()) {
            return;
        }
        GunDefinition def = held.get();
        if (def.needsPermission() && !player.hasPermission(def.permissionNode()) && !player.hasPermission("warz.admin")) {
            if (def.permissionMessage() != null && !def.permissionMessage().isBlank()) {
                player.sendMessage(ItemFactory.colorize(def.permissionMessage()));
            }
            return;
        }
        GunInstance gun = guns.get(def.fileName().toLowerCase(Locale.ROOT));
        if (gun == null) {
            return;
        }

        // Firearms: RMB shoots, LMB always ADS (irons / optic). Throwables keep cook/throw mapping.
        if (!def.throwable()) {
            if (clickType.equals("right")) {
                gun.setHeldDownTicks(gun.heldDownTicks() + 1);
                gun.setLastFired(0);
                if (currentlyFiring == null) {
                    fireGun(gun, alreadyFired);
                }
            } else if (clickType.equals("left")) {
                checkAim();
            }
            return;
        }
        String shootAction = "right";
        if ((def.canClickRight() || def.canAimRight()) && clickType.equals(shootAction)) {
            if (!def.canAimRight()) {
                gun.setHeldDownTicks(gun.heldDownTicks() + 1);
                gun.setLastFired(0);
                if (currentlyFiring == null) {
                    fireGun(gun, alreadyFired);
                }
            } else {
                checkAim();
            }
        } else if ((def.canClickLeft() || def.canAimLeft()) && clickType.equals("left")) {
            if (!def.canAimLeft()) {
                gun.setHeldDownTicks(0);
                if (currentlyFiring == null) {
                    fireGun(gun, alreadyFired);
                }
            } else {
                checkAim();
            }
        }
    }

    private void fireGun(GunInstance gun, Projectile alreadyFired) {
        Player player = player();
        if (player == null || gun.timer() > 0) {
            return;
        }
        if (!canShoot(gun)) {
            return;
        }
        // Grenade / flash / molotov: first click arms (pin/light), second throws with remaining fuse.
        if (alreadyFired == null && isCookable(gun.definition())) {
            handleCookableClick(gun);
            return;
        }
        if (alreadyFired != null) {
            Optional<RoundDefinition> round = pickRound(gun.definition(), gun.definition().ammoAmtNeeded());
            if (round.isEmpty() && !gun.definition().consumable() && !gun.definition().throwable()) {
                player.sendActionBar(ItemFactory.colorize(gun.definition().outOfAmmoMessage()));
                return;
            }
            RoundDefinition used = round.orElse(null);
            Vector vel = alreadyFired.getVelocity();
            if (used != null) {
                vel = vel.clone().normalize().multiply(used.speedFor(gun.definition()));
            }
            boolean suppressed = plugin.items().hasSuppressor(player.getInventory().getItemInMainHand());
            Bullet bullet = new Bullet(plugin, player, vel, gun.definition(), alreadyFired, used, suppressed);
            plugin.bullets().add(bullet);
            removeAmmo(gun.definition(), Math.max(0, gun.definition().ammoAmtNeeded() - 1), used);
            if (used != null) {
                lastUsedRoundId = used.fileName();
            }
            lastFiredGun = gun;
            lastFiredTicks = ticks;
            return;
        }
        currentlyFiring = gun;
        gun.setFiring(true);
        lastFiredGun = gun;
        lastFiredTicks = ticks;
        // Semi-auto: fire on this click. Waiting a tick meant RMB often scoped
        // (arm swing) and never spawned a projectile if the next tick cleared firing.
        if (alreadyFired == null && gun.definition().roundsPerBurst() <= 1) {
            shoot(gun);
            gun.finishShooting();
            currentlyFiring = null;
        }
    }

    private boolean canShoot(GunInstance gun) {
        int cooldown = plugin.getConfig().getInt("gunswapcooldown", 0);
        return !(lastFiredGun != null && lastFiredGun != gun && ticks - lastFiredTicks <= cooldown);
    }

    public void checkAim() {
        if (!tryAimClick()) {
            return;
        }
        Player player = player();
        if (player == null || ticks < ignoreAimUntilTick) {
            return;
        }
        if (adsActive) {
            exitAim();
        } else {
            ensureAimedIn();
        }
    }

    /** True if this click should toggle ADS (ignores duplicates in the same ~tick). */
    public boolean tryAimClick() {
        long now = System.currentTimeMillis();
        if (now - lastAimClickMs < 75L) {
            return false;
        }
        lastAimClickMs = now;
        return true;
    }

    /** Call when RMB fires so the follow-up arm swing does not toggle ADS. */
    public void suppressAimSwing(long ms) {
        ignoreSwingAimUntilMs = Math.max(ignoreSwingAimUntilMs, System.currentTimeMillis() + Math.max(0L, ms));
    }

    public boolean shouldIgnoreSwingAim() {
        return System.currentTimeMillis() < ignoreSwingAimUntilMs;
    }

    /** Force ADS on (does not toggle off). Used when melee-punching while holding a gun. */
    public void ensureAimedIn() {
        Player player = player();
        if (player == null || adsActive || ticks < ignoreAimUntilTick) {
            return;
        }
        lastAimClickMs = System.currentTimeMillis();
        adsActive = true;
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 60 * 10, 4, false, false, true));
    }

    public void exitAim() {
        adsActive = false;
        swayYaw = 0f;
        swayPitch = 0f;
        Player player = player();
        if (player == null) {
            return;
        }
        // Only strip our ADS slow (amp 4); keep bleed/medical slowness
        PotionEffect slow = player.getPotionEffect(PotionEffectType.SLOWNESS);
        if (slow != null && slow.getAmplifier() >= 4) {
            player.removePotionEffect(PotionEffectType.SLOWNESS);
        }
    }

    public boolean isAimedIn() {
        return adsActive;
    }

    public void tick() {
        ticks++;
        Player player = player();
        if (player == null) {
            return;
        }
        tickCook(player);
        if (ticks % 5 == 0) {
            tickHeCook(player);
        }
        tickBarrelHeat(player);
        tickSniperBreath(player);
        tickSniperSway(player);
        // Keep ADS slow present while scoped (bleed uses lower amplifiers)
        if (adsActive) {
            PotionEffect slow = player.getPotionEffect(PotionEffectType.SLOWNESS);
            if (slow == null || slow.getAmplifier() < 4) {
                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.SLOWNESS, 20 * 60 * 10, 4, false, false, true));
            }
        }
        renameGuns(player);
        ItemStack hand = player.getInventory().getItemInMainHand();
        lastHeldItem = hand;
        Optional<GunDefinition> held = heldGun(hand);
        if (held.isPresent()) {
            if (!player.getScoreboardTags().contains("pgm_gun")) {
                player.addScoreboardTag("pgm_gun");
            }
            GunInstance heldInst = guns.get(held.get().fileName().toLowerCase(Locale.ROOT));
            OpticType handOptic = plugin.items().resolvedOptic(hand);
            if (isAimedIn()) {
                boolean wasAim = player.getScoreboardTags().contains("pgm_aim");
                if (!wasAim) {
                    player.addScoreboardTag("pgm_aim");
                    if (plugin.scopeSync() != null) {
                        plugin.scopeSync().force(player);
                    }
                }
                player.removeScoreboardTag("pgm_fire");
                hipfireUntilTick = 0;
                // Optic ADS hint — Shift breath / Shift+F zero (when optic allows)
                if (handOptic != null && handOptic.usesBreathHud()
                        && ticks % 20 == 0
                        && cook == null
                        && (heldInst == null || !heldInst.reloading())
                        && !Boolean.TRUE.equals(jammed.get(held.get().fileName().toLowerCase(Locale.ROOT)))) {
                    boolean prone = plugin.prone() != null && plugin.prone().isProne(player);
                    GripType grip = plugin.items().gripType(hand);
                    boolean rest = SniperBallistics.hasRifleRest(player, prone)
                            || (grip.isBipod() && (prone || player.isSneaking()));
                    String brace = rest ? " &aREST" : (prone ? " &7PRONE" : "");
                    String breath = holdingBreath
                            ? " &bHOLD BREATH &f" + Math.round(breathStamina * 100) + "%"
                            : " &8Shift breath";
                    String zeroBit = handOptic.allowsZeroing()
                            ? "&bShift+F &7Zero &f" + plugin.items().zeroYards(hand) + " yd"
                            : handOptic.displayName();
                    player.sendActionBar(ItemFactory.colorize(zeroBit + brace + breath));
                }
            } else {
                if (player.getScoreboardTags().contains("pgm_aim")
                        && handOptic != null
                        && plugin.scopeSync() != null) {
                    player.removeScoreboardTag("pgm_aim");
                    plugin.scopeSync().force(player);
                } else {
                    player.removeScoreboardTag("pgm_aim");
                }
            }
            if (hipfireUntilTick > 0 && ticks >= hipfireUntilTick) {
                player.removeScoreboardTag("pgm_fire");
                hipfireUntilTick = 0;
            }
        } else {
            if (adsActive) {
                exitAim();
                if (plugin.scopeSync() != null) {
                    plugin.scopeSync().force(player);
                }
            }
            player.removeScoreboardTag("pgm_gun");
            player.removeScoreboardTag("pgm_aim");
            player.removeScoreboardTag("pgm_fire");
            player.removeScoreboardTag("pgm_reload");
            hipfireUntilTick = 0;
            clearReloadHud(player);
        }
        tickLaserSight(player, hand);
        if (ticks % 8 == 0) {
            tickAutoFillHeldMag(player, hand);
        }
        for (GunInstance gun : guns.values()) {
            tickGun(gun);
            if (player.isDead()) {
                gun.finishReloading();
                clearReloadHud(player);
            }
            if (currentlyFiring != null && gun.timer() <= 0 && currentlyFiring == gun) {
                currentlyFiring = null;
            }
        }
        if (held.isPresent()) {
            GunInstance heldInst = guns.get(held.get().fileName().toLowerCase(Locale.ROOT));
            boolean cooking = cook != null && cook.fileName.equalsIgnoreCase(held.get().fileName());
            if ((heldInst != null && heldInst.reloading()) || cooking) {
                if (!player.getScoreboardTags().contains("pgm_reload")) {
                    player.addScoreboardTag("pgm_reload");
                }
            } else {
                player.removeScoreboardTag("pgm_reload");
            }
            tickReloadHud(player, heldInst);
        }
        // Button released — allow next arm/throw click (must not require an active cook;
        // after a throw cook is null and the old check left cookButtonHeld stuck forever).
        boolean anyHeld = false;
        for (GunInstance gun : guns.values()) {
            if (gun.heldDownTicks() > 0) {
                anyHeld = true;
                break;
            }
        }
        if (!anyHeld) {
            cookButtonHeld = false;
        }
        if (player.isDead() && cook != null) {
            handDetonate(player);
        }
    }

    /** Visual pointer only — never damages anything. Real light via temporary LIGHT blocks when glow is on. */
    private void tickLaserSight(Player player, ItemStack hand) {
        if (!enabled || player.isDead()) {
            clearLaserLights();
            return;
        }
        Optional<GunDefinition> held = heldGun(hand);
        if (held.isEmpty()) {
            clearLaserLights();
            return;
        }
        GunDefinition def = held.get();
        LaserModColor laserMod = plugin.items().laserColor(hand);
        boolean hasAttachmentLaser = laserMod.isInstalled();
        boolean hasPeq = plugin.items().hasPeq(hand);
        PeqMode optic = plugin.items().canToggleOptic(hand)
                ? plugin.items().opticMode(hand)
                : PeqMode.OFF;
        boolean peqLaser = hasPeq && optic.laserActive();
        boolean kitLaser = !hasPeq && hasAttachmentLaser && optic.laserActive();
        boolean builtInLaser = def.laserSight() && !hasPeq && !hasAttachmentLaser;
        if (!peqLaser && !kitLaser && !builtInLaser) {
            clearLaserLights();
            return;
        }
        if (builtInLaser && def.laserSightAimOnly() && !isAimedIn()) {
            clearLaserLights();
            return;
        }
        GunInstance instance = guns.get(def.fileName().toLowerCase(Locale.ROOT));
        if (instance != null && instance.reloading()) {
            clearLaserLights();
            return;
        }
        double range = def.laserSightRange() > 0 ? def.laserSightRange() : Math.max(8, def.maxDistance());
        boolean infrared;
        Color color;
        if (peqLaser) {
            infrared = optic.infrared();
            if (infrared) {
                color = NvgGear.IR_PHOSPHOR;
            } else if (hasAttachmentLaser && laserMod.color() != null && !laserMod.infrared()) {
                // Workbench laser module tints the PEQ visible laser.
                color = laserMod.color();
            } else {
                color = LaserModColor.GREEN.color();
            }
        } else if (kitLaser) {
            infrared = laserMod.infrared() || optic.infrared();
            if (infrared) {
                color = NvgGear.IR_PHOSPHOR;
            } else if (laserMod.color() != null) {
                color = laserMod.color();
            } else {
                color = LaserModColor.GREEN.color();
            }
        } else {
            infrared = def.laserSightIr();
            color = def.laserSightColor() == null ? Color.RED : def.laserSightColor();
        }
        float size = def.laserSightSize() <= 0 ? 0.28f : def.laserSightSize();
        if (infrared) {
            // IR pointers are optically thinner through tubes.
            size = Math.max(0.08f, size * 0.55f);
        }
        var eye = player.getEyeLocation();
        // Optic ADS: laser follows scope sway (same offsets as companion camera)
        OpticType laserOptic = plugin.items().resolvedOptic(hand);
        if (adsActive && laserOptic != null && (swayYaw != 0f || swayPitch != 0f)) {
            eye.setYaw(eye.getYaw() + swayYaw);
            eye.setPitch(Math.max(-90f, Math.min(90f, eye.getPitch() + swayPitch)));
        }
        // Visible lasers stop on plants/leaves; IR punches through foliage
        var aim = LaserBeams.aimPoint(player, eye, range, true, infrared);
        var muzzle = LaserBeams.muzzleOrigin(
                eye,
                def.laserSightOffsetRight(),
                def.laserSightOffsetUp(),
                def.laserSightOffsetForward()
        );
        LaserOptics.BeamPath path = LaserOptics.traceFromTo(muzzle, aim, size, def.laserSightDensity(), infrared);
        Location tip = path.tip() != null ? path.tip() : muzzle;

        LaserCompanionBridge bridge = plugin.laserBridge();
        if (bridge != null) {
            if (!infrared) {
                List<Player> vanilla = bridge.vanillaViewersNear(muzzle);
                if (!vanilla.isEmpty()) {
                    LaserOptics.spawnParticles(path, color, size, def.laserSightDensity(), vanilla);
                }
                // Tip spark in the world color buffer so Iris SSR / WSR can reflect the burn mark
                LaserOptics.spawnWorldTipSpark(tip, color, size);
            }
            boolean suppressed = plugin.items().hasSuppressor(player.getInventory().getItemInMainHand());
            bridge.broadcastBeam(player, path, color, size, infrared, false, suppressed);
            // Suppressed IR: no eye dazzle — NOD bloom was the giveaway
            if (!(suppressed && infrared)) {
                bridge.applyEyeFlashes(player, muzzle, path, color, infrared);
            }
            laserActive = true;
        } else if (!infrared) {
            // Fallback: world particles for everyone (pre-bridge). Never for IR.
            LaserBeams.drawFromTo(muzzle, aim, color, size, def.laserSightDensity(), false);
            LaserOptics.spawnWorldTipSpark(tip, color, size);
            laserActive = true;
        } else {
            laserActive = true;
        }

        // Tip-only LIGHT (no face cluster / beam trail).
        if (!infrared) {
            int level = def.laserSightGlow()
                    ? Math.max(2, Math.min(12, (int) Math.round(2 + def.laserSightGlowStrength() * 10)))
                    : 7;
            Set<Block> updated = LaserBeams.updateTipLight(muzzle, tip, level, laserLights);
            laserLights.clear();
            laserLights.addAll(updated);
        } else {
            LaserBeams.clearLights(laserLights);
        }
    }

    private void tickGun(GunInstance gun) {
        gun.setLastFired(gun.lastFired() + 1);
        gun.setTimer(gun.timer() - 1);
        gun.setGunReloadTimer(gun.gunReloadTimer() - 1);

        if (gun.gunReloadTimer() < 0) {
            if (gun.reloading()) {
                boolean handload = gun.handloading();
                gun.finishReloading();
                // Seat a round after mag change / handload completes.
                Player p = player();
                if (p != null && !gun.definition().consumable() && !gun.definition().throwable()) {
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (heldGun(hand).filter(d -> d.fileName().equalsIgnoreCase(gun.definition().fileName())).isPresent()) {
                        if (handload) {
                            tryFeedChamberFromLoose(hand, gun.definition());
                        } else {
                            activeMagSlot.remove(gun.definition().fileName().toLowerCase(Locale.ROOT));
                            tryFeedChamberFromMag(hand, gun.definition());
                        }
                        p.getInventory().setItemInMainHand(hand);
                    }
                    p.playSound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, SoundCategory.PLAYERS, 0.85f, 1.35f);
                    p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, SoundCategory.PLAYERS, 0.45f, 1.6f);
                    clearReloadHud(p);
                }
            }
            gun.setReloading(false);
        }
        playReloadSounds(gun);

        if (gun.lastFired() > 6) {
            gun.setHeldDownTicks(0);
        }

        if (((gun.heldDownTicks() >= 2 && gun.timer() <= 0) || gun.firing()) && !gun.reloading()) {
            if (gun.definition().roundsPerBurst() > 1) {
                if (ticks % 2 == 0) {
                    gun.setBulletsShot(gun.bulletsShot() + 1);
                    if (gun.bulletsShot() <= gun.definition().roundsPerBurst()) {
                        shoot(gun);
                    } else {
                        gun.finishShooting();
                    }
                }
            } else {
                shoot(gun);
                gun.finishShooting();
            }
        }
        if (gun.reloading()) {
            gun.setFiring(false);
        }
    }

    private void shoot(GunInstance gun) {
        Player player = player();
        if (player == null || !player.isOnline() || gun.reloading()) {
            return;
        }
        GunDefinition def = gun.definition();
        // Cookables never auto-fire from the burst/tick path — only via handleCookableClick.
        if (isCookable(def)) {
            gun.finishShooting();
            return;
        }

        ItemStack heldForMods = player.getInventory().getItemInMainHand();
        String gunKey = def.fileName().toLowerCase(Locale.ROOT);
        if (Boolean.TRUE.equals(jammed.get(gunKey))) {
            player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.7f, 0.6f);
            player.sendActionBar(ItemFactory.colorize("&cJammed! &7Reload to clear"));
            gun.finishShooting();
            return;
        }
        double accuracy = currentAccuracy(def);
        Optional<RoundDefinition> roundOpt = Optional.empty();
        if (!def.consumable() && !def.throwable()) {
            if (plugin.items().hasChamberRound(heldForMods)) {
                String cid = plugin.items().chamberRound(heldForMods);
                roundOpt = plugin.rounds().get(cid);
            } else {
                roundOpt = peekMagRound(def);
            }
            if (roundOpt.isEmpty() && countMagazineRounds(def) <= 0 && !hasLooseAmmo(def)
                    && !plugin.items().hasChamberRound(heldForMods)) {
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1.4f);
                player.sendActionBar(ItemFactory.colorize(def.outOfAmmoMessage()));
                gun.finishShooting();
                return;
            }
            if (roundOpt.isPresent()) {
                accuracy = roundOpt.get().accuracyFor(def, accuracy);
            }
        }
        accuracy *= plugin.items().accuracySpreadMultiplier(heldForMods, isAimedIn());
        accuracy *= classHipfirePenalty(def);
        accuracy *= sniperStabilitySpread(player, def);

        GunFireEvent event = new GunFireEvent(player, def, def.ammoAmtNeeded(), accuracy);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            gun.finishShooting();
            return;
        }
        if (event.getAmmoNeeded() > 0 && !checkAmmo(def, event.getAmmoNeeded())
                && !plugin.items().hasChamberRound(heldForMods)) {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1.4f);
            player.sendActionBar(ItemFactory.colorize(def.outOfAmmoMessage()));
            gun.finishShooting();
            return;
        }
        RoundDefinition used = roundOpt.orElse(null);
        ItemStack handForChamber = player.getInventory().getItemInMainHand();
        boolean usedChamber = false;
        if (!def.consumable() && !def.throwable() && event.getAmmoNeeded() > 0) {
            // Mag feeds instantly; loose ammo requires a slow handload reload first.
            if (!plugin.items().hasChamberRound(handForChamber)) {
                if (!tryFeedChamberFromMag(handForChamber, def)) {
                    if (hasLooseAmmo(def)) {
                        gun.finishShooting();
                        player.getInventory().setItemInMainHand(handForChamber);
                        reloadGun(gun); // handload
                        return;
                    }
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1.4f);
                    player.sendActionBar(ItemFactory.colorize(def.outOfAmmoMessage()));
                    gun.finishShooting();
                    return;
                }
                player.getInventory().setItemInMainHand(handForChamber);
            }
            String chamberId = plugin.items().chamberRound(handForChamber);
            used = chamberId != null ? plugin.rounds().get(chamberId).orElse(used) : used;
            plugin.items().setChamberRound(handForChamber, null);
            usedChamber = true;
            player.getInventory().setItemInMainHand(handForChamber);
        } else if (event.getAmmoNeeded() > 0) {
            if (used == null) {
                used = pickRound(def, event.getAmmoNeeded()).orElse(null);
            }
            removeAmmo(def, event.getAmmoNeeded(), used);
        }
        if (used != null) {
            lastUsedRoundId = used.fileName();
        }

        gun.setChanged(true);
        gun.setRoundsFired(gun.roundsFired() + 1);
        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean suppressed = plugin.items().hasSuppressor(hand);
        plugin.items().wearGun(hand, 1);
        // Barrel heat — skipped in water / rain (barrel stays cool)
        boolean wetBarrel = isBarrelCooledByWeather(player);
        int heat;
        if (wetBarrel) {
            heat = Math.max(0, barrelHeat.getOrDefault(gunKey, 0) - 8);
            if (heat <= 0) {
                barrelHeat.remove(gunKey);
                overheatWarned.remove(gunKey);
            } else {
                barrelHeat.put(gunKey, heat);
            }
        } else {
            int heatGain = 4 + Math.max(0, 6 - def.bulletDelayTime());
            if (def.bulletsPerClick() > 1) {
                heatGain += 2;
            }
            heat = Math.min(100, barrelHeat.getOrDefault(gunKey, 0) + heatGain);
            barrelHeat.put(gunKey, heat);
        }
        // Jam: rare unless the gun is quite worn; overheat adds a little more
        int cond = plugin.items().gunCondition(hand);
        int jamChance = 0;
        if (cond < 85) {
            jamChance = Math.max(0, (100 - cond) / 25); // was /8
        }
        if (def.bulletDelayTime() <= 2 && cond < 70) {
            jamChance += 1;
        }
        if (!wetBarrel && heat >= OVERHEAT_WARN_AT) {
            jamChance += 1; // was +3
        }
        // Roll out of 200 so even dirty guns jam less often than before
        if (jamChance > 0 && random.nextInt(200) < jamChance) {
            jammed.put(gunKey, true);
            player.sendActionBar(ItemFactory.colorize("&cJam! &7Reload to clear"));
            player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.85f, 0.55f);
        }
        playFireSounds(player, def, used, suppressed);

        // Hipfire pose for unaimed shots (ADS uses pgm_aim instead)
        if (!isAimedIn()) {
            player.addScoreboardTag("pgm_fire");
            hipfireUntilTick = ticks + 16;
        }

        // Locked Javelin → guided missile (skips dumb projectile)
        if (JavelinService.isJavelin(def) && plugin.javelin() != null
                && plugin.javelin().tryLaunchGuided(player, def, used)) {
            doRecoil(player, def, heldForMods);
            if (usedChamber) {
                afterShotFeedOrReload(gun, def);
            }
            return;
        }

        double speed = used != null ? used.speedFor(def) : def.bulletSpeed();
        // Unguided Javelin rockets are still very fast
        if (JavelinService.isJavelin(def)) {
            speed = Math.max(speed, 4.8);
        }
        for (int i = 0; i < def.bulletsPerClick(); i++) {
            Vector vec = createShotVector(player, event.getAccuracy(), speed);
            Bullet bullet = new Bullet(plugin, player, vec, def, null, used, suppressed);
            plugin.bullets().add(bullet);
        }
        doRecoil(player, def, heldForMods);

        // Closed-bolt: seat next from the seated mag, or start reload / handload.
        if (usedChamber) {
            afterShotFeedOrReload(gun, def);
        }
    }

    /** After firing the chamber: feed from active mag, else reload (mag swap or handload). */
    private void afterShotFeedOrReload(GunInstance gun, GunDefinition def) {
        Player player = player();
        if (player == null) {
            return;
        }
        ItemStack after = player.getInventory().getItemInMainHand();
        if (tryFeedChamberFromMag(after, def)) {
            player.getInventory().setItemInMainHand(after);
            return;
        }
        player.getInventory().setItemInMainHand(after);
        if (countMagazineRounds(def) > 0 || hasLooseAmmo(def)) {
            reloadGun(gun);
        }
    }

    private int eventNeeds(GunDefinition def) {
        return Math.max(0, def.ammoAmtNeeded());
    }

    /** Pull one round from the seated magazine only (not loose ammo). */
    private boolean tryFeedChamberFromMag(ItemStack gunItem, GunDefinition def) {
        if (gunItem == null || def == null || plugin.items().hasChamberRound(gunItem)) {
            return plugin.items().hasChamberRound(gunItem);
        }
        Player player = player();
        if (player == null) {
            return false;
        }
        PlayerInventory inv = player.getInventory();
        String gunKey = def.fileName().toLowerCase(Locale.ROOT);
        Integer active = activeMagSlot.get(gunKey);
        if (active != null) {
            if (feedFromMagSlot(gunItem, def, inv, active)) {
                return true;
            }
            // Seated mag empty / gone — must reload to insert another.
            activeMagSlot.remove(gunKey);
            return false;
        }
        // First feed: seat the first loaded mag that fits.
        for (int slot : ammoScanOrder(inv)) {
            if (feedFromMagSlot(gunItem, def, inv, slot)) {
                activeMagSlot.put(gunKey, slot);
                return true;
            }
        }
        return false;
    }

    private boolean feedFromMagSlot(ItemStack gunItem, GunDefinition def, PlayerInventory inv, int slot) {
        if (slot < 0 || slot >= inv.getSize()) {
            return false;
        }
        ItemStack mag = inv.getItem(slot);
        if (mag == null || mag.getAmount() <= 0) {
            return false;
        }
        ItemStack gunHeld = gunItem;
        if (!plugin.items().isMagazine(mag) || !plugin.items().magazineFitsGun(mag, def, gunHeld)) {
            return false;
        }
        String rid = plugin.items().magazinePeekNext(mag);
        if (rid == null || !def.allowsRound(rid)) {
            return false;
        }
        if (plugin.rounds().get(rid).isEmpty()) {
            return false;
        }
        String popped = plugin.items().magazinePopNext(mag);
        if (popped == null) {
            return false;
        }
        plugin.items().setChamberRound(gunItem, popped);
        inv.setItem(slot, mag);
        warnIfHeChambered(popped);
        return true;
    }

    /** While holding a mag, siphon matching loose ammo into the stack. */
    private void tickAutoFillHeldMag(Player player, ItemStack hand) {
        if (player == null || hand == null || !plugin.items().isMagazine(hand)) {
            return;
        }
        if (plugin.items().magazineCount(hand) >= plugin.items().magazineTotalCapacity(hand)) {
            return;
        }
        int n = plugin.items().autoFillMagazine(player.getInventory(), hand);
        if (n > 0) {
            player.getInventory().setItemInMainHand(hand);
            player.sendActionBar(ItemFactory.colorize("&aAuto-loaded &f" + n
                    + " &7→ [" + plugin.items().magazineCount(hand)
                    + "/" + plugin.items().magazineTotalCapacity(hand) + "]"));
        }
    }

    /** Handload one loose round into the chamber (slow reload finish). */
    private boolean tryFeedChamberFromLoose(ItemStack gunItem, GunDefinition def) {
        if (gunItem == null || def == null) {
            return false;
        }
        if (plugin.items().hasChamberRound(gunItem)) {
            return true;
        }
        Optional<RoundDefinition> loose = pickLooseRound(def, 1);
        if (loose.isEmpty()) {
            return false;
        }
        removeLooseAmmo(def, 1, loose.get());
        plugin.items().setChamberRound(gunItem, loose.get().fileName());
        warnIfHeChambered(loose.get().fileName());
        return true;
    }

    private void warnIfHeChambered(String roundId) {
        if (roundId == null) {
            return;
        }
        Player player = player();
        Optional<RoundDefinition> r = plugin.rounds().get(roundId);
        if (player == null || r.isEmpty() || r.get().explodeRadiusAdd() <= 0) {
            return;
        }
        player.sendActionBar(ItemFactory.colorize("&6HE chambered &7— cook risk if you catch fire"));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.55f);
    }

    private Optional<RoundDefinition> peekMagRound(GunDefinition def) {
        Player player = player();
        if (player == null) {
            return Optional.empty();
        }
        PlayerInventory inv = player.getInventory();
        String gunKey = def.fileName().toLowerCase(Locale.ROOT);
        Integer active = activeMagSlot.get(gunKey);
        if (active != null) {
            ItemStack mag = inv.getItem(active);
            if (plugin.items().isMagazine(mag) && plugin.items().magazineFitsGun(mag, def, heldGunItem())
                    && plugin.items().magazineCount(mag) > 0) {
                String rid = plugin.items().magazineRoundId(mag);
                if (rid != null && def.allowsRound(rid)) {
                    return plugin.rounds().get(rid);
                }
            }
        }
        for (int slot : ammoScanOrder(inv)) {
            ItemStack mag = inv.getItem(slot);
            if (!plugin.items().isMagazine(mag) || !plugin.items().magazineFitsGun(mag, def, heldGunItem())) {
                continue;
            }
            if (plugin.items().magazineCount(mag) <= 0) {
                continue;
            }
            String rid = plugin.items().magazineRoundId(mag);
            if (rid != null && def.allowsRound(rid)) {
                return plugin.rounds().get(rid);
            }
        }
        return Optional.empty();
    }

    private boolean hasLooseAmmo(GunDefinition def) {
        return pickLooseRound(def, 1).isPresent();
    }

    private int activeMagCount(GunDefinition def) {
        Player player = player();
        if (player == null) {
            return 0;
        }
        Integer slot = activeMagSlot.get(def.fileName().toLowerCase(Locale.ROOT));
        if (slot == null) {
            // Preview first available loaded mag
            for (int s : ammoScanOrder(player.getInventory())) {
                ItemStack mag = player.getInventory().getItem(s);
                if (plugin.items().isMagazine(mag) && plugin.items().magazineFitsGun(mag, def, heldGunItem())
                        && plugin.items().magazineCount(mag) > 0) {
                    return plugin.items().magazineCount(mag);
                }
            }
            return 0;
        }
        ItemStack mag = player.getInventory().getItem(slot);
        if (!plugin.items().isMagazine(mag) || !plugin.items().magazineFitsGun(mag, def, heldGunItem())) {
            return 0;
        }
        return plugin.items().magazineCount(mag);
    }

    private int activeMagCapacity(GunDefinition def) {
        Player player = player();
        if (player == null) {
            return def.maxClipSize();
        }
        Integer slot = activeMagSlot.get(def.fileName().toLowerCase(Locale.ROOT));
        ItemStack mag = null;
        if (slot != null) {
            mag = player.getInventory().getItem(slot);
        }
        if (mag == null || !plugin.items().isMagazine(mag)) {
            for (int s : ammoScanOrder(player.getInventory())) {
                ItemStack m = player.getInventory().getItem(s);
                if (plugin.items().isMagazine(m) && plugin.items().magazineFitsGun(m, def, heldGunItem())
                        && plugin.items().magazineCount(m) > 0) {
                    mag = m;
                    break;
                }
            }
        }
        MagazineType type = plugin.items().magazineType(mag);
        return type != null ? type.capacity() : def.maxClipSize();
    }

    private double currentAccuracy(GunDefinition def) {
        if (isAimedIn() && def.accuracyAimed() >= 0) {
            return Math.max(0.0005, def.accuracyAimed() * classAdsBonus(def));
        }
        Player player = player();
        if (player != null && player.isSneaking() && def.accuracyCrouched() >= 0) {
            return def.accuracyCrouched();
        }
        return def.accuracy();
    }

    /** Hipfire spread penalty by platform (ADS uses {@link #classAdsBonus}). */
    private double classHipfirePenalty(GunDefinition def) {
        if (isAimedIn()) {
            return 1.0;
        }
        return switch (MagPlatform.forGun(def)) {
            case SNIPER -> 1.85;
            case AR, AK -> 1.15;
            case SMG, PISTOL_9, PISTOL_45 -> 0.92;
            case SHOTGUN -> 1.05;
            default -> 1.0;
        };
    }

    /** Multiplier on aimed spread (&lt;1 = tighter). */
    private double classAdsBonus(GunDefinition def) {
        return switch (MagPlatform.forGun(def)) {
            case SNIPER -> 0.55;
            case AR, AK -> 0.85;
            case SMG -> 0.95;
            case SHOTGUN -> 1.0;
            default -> 0.9;
        };
    }

    public boolean isHoldingBreath() {
        return holdingBreath;
    }

    public float breathStamina() {
        return breathStamina;
    }

    /** True while optic ADS — Shift is breath, not prone exit. */
    public boolean sniperAdsBlockingProneSneak() {
        Player p = player();
        if (p == null || !isAimedIn()) {
            return false;
        }
        OpticType optic = plugin.items().resolvedOptic(p.getInventory().getItemInMainHand());
        return optic != null && optic.usesBreathHud();
    }

    private void tickSniperBreath(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        OpticType optic = plugin.items().resolvedOptic(hand);
        boolean opticAds = isAimedIn() && optic != null && optic.usesBreathHud();
        boolean prone = plugin.prone() != null && plugin.prone().isProne(player);
        holdingBreath = opticAds && player.isSneaking();
        if (holdingBreath && breathStamina > 0f) {
            breathStamina = Math.max(0f, breathStamina - 0.0075f);
        } else if (!holdingBreath) {
            float recover = prone ? 0.014f : 0.01f;
            breathStamina = Math.min(1f, breathStamina + recover);
        }
    }

    /** Same sway model as companion {@code ScopeOverlay} — drives laser + shot direction. */
    private void tickSniperSway(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        OpticType optic = plugin.items().resolvedOptic(hand);
        GripType grip = plugin.items().gripType(hand);
        boolean opticAds = adsActive && optic != null;
        if (!opticAds) {
            swayYaw *= 0.7f;
            swayPitch *= 0.7f;
            if (Math.abs(swayYaw) < 0.001f) {
                swayYaw = 0f;
            }
            if (Math.abs(swayPitch) < 0.001f) {
                swayPitch = 0f;
            }
            return;
        }
        boolean prone = plugin.prone() != null && plugin.prone().isProne(player);
        boolean rest = SniperBallistics.hasRifleRest(player, prone)
                || (grip.isBipod() && (prone || player.isSneaking()));
        float amp = 0.55f * (float) optic.swayMult() * (float) grip.adsSwayMult();
        if (prone) {
            amp *= 0.42f;
        }
        if (rest) {
            amp *= 0.48f * (float) grip.restSwayMult();
        }
        if (holdingBreath && breathStamina > 0.08f) {
            amp *= 0.12f + 0.25f * (1f - breathStamina);
        } else if (holdingBreath) {
            amp *= 1.35f;
        }
        if (player.getVelocity().clone().setY(0).lengthSquared() > 0.0004) {
            amp *= 1.55f;
        }
        swayPhase = player.getTicksLived() * 0.07f + amp * 0.04f * (ticks % 200);
        float targetYaw = (float) (Math.sin(swayPhase) * amp
                + Math.sin(swayPhase * 0.37f) * amp * 0.35f);
        float targetPitch = (float) (Math.cos(swayPhase * 0.81f) * amp * 0.72f
                + Math.sin(swayPhase * 1.7f) * amp * 0.18f);
        swayYaw += (targetYaw - swayYaw) * 0.28f;
        swayPitch += (targetPitch - swayPitch) * 0.28f;
    }

    public float swayYaw() {
        return swayYaw;
    }

    public float swayPitch() {
        return swayPitch;
    }

    /**
     * Spread multiplier for optic ADS (&lt;1 tighter). Prone / rest / breath / grip stack.
     */
    private double sniperStabilitySpread(Player player, GunDefinition def) {
        if (player == null || def == null || !isAimedIn()) {
            return 1.0;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        OpticType optic = plugin.items().resolvedOptic(hand);
        if (optic == null) {
            return 1.0;
        }
        GripType grip = plugin.items().gripType(hand);
        boolean prone = plugin.prone() != null && plugin.prone().isProne(player);
        boolean rest = SniperBallistics.hasRifleRest(player, prone)
                || (grip.isBipod() && (prone || player.isSneaking()));
        double mult = 1.0;
        if (prone) {
            mult *= 0.55;
        }
        if (rest) {
            mult *= 0.62 * grip.restSwayMult();
        }
        if (holdingBreath && breathStamina > 0.08f) {
            mult *= 0.35 + 0.35 * (1.0 - breathStamina);
        } else if (holdingBreath) {
            mult *= 1.15;
        }
        return Math.max(0.12, mult);
    }

    private ItemStack heldGunItem() {
        if (magFitGunOverride != null && plugin.items().isGunItem(magFitGunOverride)) {
            return magFitGunOverride;
        }
        Player p = player();
        if (p == null) {
            return null;
        }
        ItemStack h = p.getInventory().getItemInMainHand();
        return plugin.items().isGunItem(h) ? h : null;
    }

    private Vector createShotVector(Player player, double accuracy, double speed) {
        int acc = (int) (accuracy * 1000);
        if (acc <= 0) {
            acc = 1;
        }
        float lookYaw = player.getLocation().getYaw();
        float lookPitch = player.getLocation().getPitch();
        ItemStack held = player.getInventory().getItemInMainHand();
        Optional<GunDefinition> heldDef = plugin.items().isGunItem(held)
                ? plugin.items().gunId(held).flatMap(id -> plugin.registry().get(id))
                : Optional.empty();
        OpticType shotOptic = plugin.items().resolvedOptic(held);
        if (adsActive && shotOptic != null) {
            lookYaw += swayYaw;
            lookPitch = Math.max(-90f, Math.min(90f, lookPitch + swayPitch));
        }
        double dir = -lookYaw - 90.0F;
        double pitch = -lookPitch;
        // Zeroing: elevation so POI meets look ray at zero distance
        if (isAimedIn() && heldDef.isPresent() && shotOptic != null && shotOptic.allowsZeroing()) {
            int zero = plugin.items().zeroYards(held);
            pitch += SniperBallistics.holdoverDegrees(heldDef.get(), zero);
        }
        double xwep = (random.nextInt(acc) - random.nextInt(acc) + 0.5D) / 1000.0D;
        double ywep = (random.nextInt(acc) - random.nextInt(acc) + 0.5D) / 1000.0D;
        double zwep = (random.nextInt(acc) - random.nextInt(acc) + 0.5D) / 1000.0D;
        double xd = Math.cos(Math.toRadians(dir)) * Math.cos(Math.toRadians(pitch)) + xwep;
        double yd = Math.sin(Math.toRadians(pitch)) + ywep;
        double zd = -Math.sin(Math.toRadians(dir)) * Math.cos(Math.toRadians(pitch)) + zwep;
        return new Vector(xd, yd, zd).normalize().multiply(speed);
    }

    private void doRecoil(Player player, GunDefinition def, ItemStack held) {
        // Camera recoil: kick the view upward by the per-gun configured degrees.
        // Independent of the velocity knockback below, so a gun can have one
        // without the other.
        double mod = plugin.items().recoilMultiplier(held);
        mod *= switch (MagPlatform.forGun(def)) {
            case SNIPER -> isAimedIn() ? 1.35 : 1.8;
            case AR, AK -> isAimedIn() ? 0.85 : 1.15;
            case SMG, PISTOL_9, PISTOL_45 -> isAimedIn() ? 0.7 : 0.95;
            case SHOTGUN -> 1.25;
            default -> 1.0;
        };
        double pitchKick = def.recoilPitch() * mod;
        if (pitchKick < 0.5 && MagPlatform.forGun(def) == MagPlatform.SNIPER) {
            pitchKick = Math.max(pitchKick, isAimedIn() ? 2.2 : 3.5);
        }
        if (pitchKick < 0.15 && (MagPlatform.forGun(def) == MagPlatform.AR
                || MagPlatform.forGun(def) == MagPlatform.AK) && !isAimedIn()) {
            pitchKick = Math.max(pitchKick, 1.1);
        }
        if (pitchKick != 0) {
            final double kick = pitchKick;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                org.bukkit.Location loc = player.getLocation();
                // Pitch ranges -90 (straight up) .. +90 (straight down).
                float newPitch = (float) Math.max(-90.0, loc.getPitch() - kick);
                player.setRotation(loc.getYaw(), newPitch);
            });
        }

        double bodyRecoil = def.recoil() * mod;
        if (bodyRecoil == 0) {
            return;
        }
        Vector vec = player.getEyeLocation().getDirection().normalize().multiply(-bodyRecoil);
        Vector vel = player.getVelocity().add(vec);
        double y = player.getVelocity().getY();
        if (y > 0) {
            y = 0;
        }
        player.setVelocity(vel.setY(y));
    }

    private void playFireSounds(Player player, GunDefinition def, RoundDefinition used, boolean suppressed) {
        boolean subsonic = BulletAudio.isSubsonic(used);
        boolean wet = WaterBallistics.shooterMuzzleWet(player);
        Location at = player.getEyeLocation();
        float volume = (float) Math.max(0.35, def.gunVolume());
        float pitch = firePitch(def, used);
        if (wet) {
            BulletAudio.playUnderwaterMuzzle(at, volume, suppressed, subsonic);
            if (!subsonic && !suppressed) {
                BulletAudio.playMuzzleCrack(at, def, used, true);
            }
            playConfiguredGunSounds(player, def, volume * 0.35f, Math.max(0.55f, pitch * 0.7f));
            return;
        }
        if (suppressed) {
            BulletAudio.playSuppressedLayer(player.getLocation(), volume, subsonic);
            if (!subsonic) {
                BulletAudio.playMuzzleCrack(at, def, used, true);
            }
            playConfiguredGunSounds(player, def, volume * (subsonic ? 0.18f : 0.32f), pitch + 0.15f);
            return;
        }
        if (subsonic) {
            BulletAudio.playSubsonicUnsuppressed(player.getLocation(), volume);
            playConfiguredGunSounds(player, def, volume * 0.55f, Math.max(0.7f, pitch - 0.2f));
            return;
        }
        BulletAudio.playMuzzleCrack(at, def, used, false);
        playConfiguredGunSounds(player, def, volume, pitch);
    }

    /** World report so nearby players hear it; extra local layer so the shooter feels the shot. */
    private void playConfiguredGunSounds(Player player, GunDefinition def, float volume, float pitch) {
        if (player == null || def == null || volume < 0.04f) {
            return;
        }
        Location at = player.getLocation();
        float worldVol = Math.min(1.6f, volume);
        float localVol = def.localGunSound() ? Math.min(1.8f, volume * 1.15f) : worldVol;
        float usePitch = Math.max(0.5f, Math.min(2.0f, pitch));
        for (Sound sound : def.gunSounds()) {
            if (sound == null) {
                continue;
            }
            player.getWorld().playSound(at, sound, SoundCategory.PLAYERS, worldVol, usePitch);
            if (def.localGunSound()) {
                player.playSound(at, sound, SoundCategory.PLAYERS, localVol * 0.55f, usePitch);
            }
        }
    }

    private static float firePitch(GunDefinition def, RoundDefinition used) {
        return switch (MagPlatform.forGun(def)) {
            case SNIPER -> 0.82f;
            case SHOTGUN -> 0.72f;
            case PISTOL_9, PISTOL_45 -> 1.12f;
            case SMG -> 1.18f;
            case AR, AK -> 0.98f;
            default -> used != null && "sniper".equalsIgnoreCase(AmmoCaliber.normalize(used.caliber()))
                    ? 0.82f : 1.0f;
        };
    }

    private void playReloadSounds(GunInstance gun) {
        if (!gun.reloading()) {
            return;
        }
        Player player = player();
        if (player == null) {
            return;
        }
        int dur = Math.max(1, gun.reloadDuration() > 0 ? gun.reloadDuration() : gun.definition().reloadTime());
        int amtReload = dur - gun.gunReloadTimer();
        String type = gun.definition().reloadType();
        Location at = player.getLocation();
        if ("BOLT".equalsIgnoreCase(type)) {
            if (amtReload == 6) {
                player.playSound(at, Sound.BLOCK_WOODEN_DOOR_OPEN, SoundCategory.PLAYERS, 1.2f, 1.5f);
            }
            if (amtReload == dur - 4) {
                player.playSound(at, Sound.BLOCK_WOODEN_DOOR_CLOSE, SoundCategory.PLAYERS, 1.0f, 1.5f);
            }
            return;
        }
        if ("PUMP".equalsIgnoreCase(type) || "INDIVIDUAL_BULLET".equalsIgnoreCase(type) || gun.handloading()) {
            int rep = Math.max(1, (dur - 10) / Math.max(1, gun.definition().maxClipSize()));
            if (amtReload >= 4 && amtReload <= dur - 4 && amtReload % rep == 0) {
                player.playSound(at, Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.PLAYERS, 0.9f, 1.05f);
                player.playSound(at, Sound.BLOCK_NOTE_BLOCK_SNARE, SoundCategory.PLAYERS, 0.55f, 1.8f);
            }
            return;
        }
        // Mag-fed (NORMAL): mag out, clicks, mag in.
        if (amtReload == 2) {
            player.playSound(at, Sound.BLOCK_IRON_TRAPDOOR_OPEN, SoundCategory.PLAYERS, 0.9f, 1.25f);
            player.playSound(at, Sound.ITEM_ARMOR_EQUIP_GENERIC, SoundCategory.PLAYERS, 0.5f, 0.9f);
        } else if (amtReload == Math.max(8, dur / 2)) {
            player.playSound(at, Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.45f, 1.35f);
        } else if (amtReload == Math.max(4, dur - 8)) {
            player.playSound(at, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, SoundCategory.PLAYERS, 0.85f, 1.15f);
            player.playSound(at, Sound.ITEM_ARMOR_EQUIP_LEATHER, SoundCategory.PLAYERS, 0.55f, 1.4f);
        }
    }

    private void tickReloadHud(Player player, GunInstance gun) {
        if (player == null) {
            return;
        }
        if (cook != null) {
            // Cook fuse owns the action bar (same slot as out-of-ammo).
            reloadHudShown = false;
            return;
        }
        if (gun == null || !gun.reloading()) {
            clearReloadHud(player);
            return;
        }
        int dur = Math.max(1, gun.reloadDuration() > 0 ? gun.reloadDuration() : gun.definition().reloadTime());
        double frac = (dur - gun.gunReloadTimer()) / (double) dur;
        player.sendActionBar(reloadBar(frac, gun.handloading()));
        reloadHudShown = true;
    }

    private void clearReloadHud(Player player) {
        if (!reloadHudShown || player == null) {
            return;
        }
        reloadHudShown = false;
        player.sendActionBar(Component.empty());
    }

    /**
     * Same slot as {@code Out of ammo!} — ASCII # / - so the default font stays
     * upright (no italic action-bar slant).
     */
    private static Component reloadBar(double frac, boolean handload) {
        int size = 16;
        double clamped = Math.min(1.0, Math.max(0.0, frac));
        int filled = (int) Math.round(clamped * size);
        int empty = size - filled;
        int pct = (int) Math.round(clamped * 100.0);
        Component bar = Component.text(handload ? "LOAD  [" : "RELOAD  [", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("#".repeat(Math.max(0, filled)), NamedTextColor.WHITE)
                        .decoration(TextDecoration.ITALIC, false))
                .append(Component.text("-".repeat(Math.max(0, empty)), NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false))
                .append(Component.text("]  " + pct + "%", NamedTextColor.WHITE)
                        .decoration(TextDecoration.ITALIC, false));
        return bar;
    }

    public void reloadHeldGun() {
        Player player = player();
        if (player == null) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        Optional<GunDefinition> held = heldGun(hand);
        if (held.isEmpty()) {
            return;
        }
        GunInstance gun = guns.get(held.get().fileName().toLowerCase(Locale.ROOT));
        if (gun != null) {
            reloadGun(gun, hand);
        }
    }

    public boolean tryDropReload(ItemStack dropped) {
        Optional<GunDefinition> gunDef = heldGun(dropped);
        if (gunDef.isEmpty() && !plugin.items().isGunItem(dropped)) {
            return false;
        }
        // Q is reload/drop — never ADS. Block aim toggles for a few ticks.
        ignoreAimUntilTick = ticks + 8;
        if (adsActive) {
            exitAim();
            Player p = player();
            if (p != null && plugin.scopeSync() != null) {
                plugin.scopeSync().force(p);
            }
        }
        if (gunDef.isEmpty()) {
            return false;
        }
        GunInstance gun = guns.get(gunDef.get().fileName().toLowerCase(Locale.ROOT));
        if (gun == null || !gun.definition().hasClip() || !gun.changed() || !gun.definition().reloadGunOnDrop()) {
            return false;
        }
        // Pass the dropped stack: hand is empty during PlayerDropItemEvent, so adapter
        // PDC (and chamber state) must come from the drop entity's item.
        reloadGun(gun, dropped);
        // Always cancel the drop once this is a reload attempt — never dump the gun.
        return true;
    }

    private void reloadGun(GunInstance gun) {
        reloadGun(gun, heldGunItem());
    }

    /**
     * @param gunItem gun stack used for mag-fit / chamber / adapter checks (required during Q-drop)
     * @return true if a reload (or jam-clear rack) actually started
     */
    private boolean reloadGun(GunInstance gun, ItemStack gunItem) {
        Player player = player();
        if (player == null || gun.reloading()) {
            return false;
        }
        if (adsActive) {
            exitAim();
            if (plugin.scopeSync() != null) {
                plugin.scopeSync().force(player);
            }
        }
        GunDefinition def = gun.definition();
        String key = def.fileName().toLowerCase(Locale.ROOT);
        ItemStack fitGun = gunItem != null && plugin.items().isGunItem(gunItem) ? gunItem : heldGunItem();
        magFitGunOverride = fitGun;
        try {
            boolean hasMag = countMagazineRounds(def) > 0;
            boolean loose = hasLooseAmmo(def);
            boolean isJammed = Boolean.TRUE.equals(jammed.get(key));
            boolean chambered = fitGun != null && plugin.items().hasChamberRound(fitGun);
            if (!hasMag && !loose && !chambered && !isJammed) {
                return false;
            }
            // Empty + jammed: short rack to clear without needing a mag.
            boolean jamClearOnly = isJammed && !hasMag && !loose && !chambered;
            // No loaded mag → slow single-round handload from loose stacks.
            boolean handload = !jamClearOnly && !hasMag && loose;
            int base = def.reloadTime();
            int time = jamClearOnly ? Math.max(12, base / 3)
                    : handload ? Math.max(40, (int) Math.round(base * 2.5)) : base;
            GunReloadEvent event = new GunReloadEvent(player, def, time);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return false;
            }
            activeMagSlot.remove(key);
            jammed.remove(key);
            gun.setHandloading(handload);
            gun.setReloadDuration(event.getReloadTime());
            gun.setGunReloadTimer(event.getReloadTime());
            gun.setReloading(true);
            return true;
        } finally {
            magFitGunOverride = null;
        }
    }

    private static String plainRoundName(RoundDefinition r) {
        if (r == null || r.displayName() == null) {
            return "?";
        }
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(ItemFactory.colorize(r.displayName()))
                .replaceAll("§.", "")
                .trim();
    }

    /** Sneak + F cycles 100/200/300 yd zero when fitted optic allows it. */
    public boolean tryCycleZero(Player player) {
        if (player == null || !player.isSneaking()) {
            return false;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        Optional<GunDefinition> def = heldGun(hand);
        OpticType optic = plugin.items().resolvedOptic(hand);
        if (def.isEmpty() || optic == null || !optic.allowsZeroing()) {
            return false;
        }
        int z = plugin.items().cycleZeroYards(hand);
        player.getInventory().setItemInMainHand(hand);
        player.sendActionBar(ItemFactory.colorize("&bZero &f" + z + " &7yd"));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
        if (plugin.scopeSync() != null) {
            plugin.scopeSync().force(player);
        }
        return true;
    }

    /** Cool barrels + light muzzle smoke when overheating. */
    private void tickBarrelHeat(Player player) {
        if (player == null) {
            return;
        }
        boolean wet = isBarrelCooledByWeather(player);
        // Cool every tick a little (faster when wet)
        if (ticks % 4 == 0) {
            int cool = wet ? 6 : 2;
            for (String key : new ArrayList<>(barrelHeat.keySet())) {
                int h = barrelHeat.getOrDefault(key, 0) - cool;
                if (h <= 0) {
                    barrelHeat.remove(key);
                    overheatWarned.remove(key);
                } else {
                    barrelHeat.put(key, h);
                }
            }
        }
        if (wet) {
            return; // no overheat FX in water / rain
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        Optional<GunDefinition> held = heldGun(hand);
        if (held.isEmpty()) {
            return;
        }
        String key = held.get().fileName().toLowerCase(Locale.ROOT);
        int heat = barrelHeat.getOrDefault(key, 0);
        if (heat < OVERHEAT_SMOKE_AT) {
            overheatWarned.remove(key);
            return;
        }
        if (heat >= OVERHEAT_WARN_AT && !Boolean.TRUE.equals(overheatWarned.get(key))) {
            overheatWarned.put(key, true);
            player.sendActionBar(ItemFactory.colorize("&6Barrel overheating…"));
        }
        // Soft smoke from the muzzle area
        Location muzzle = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(0.55));
        int count = heat >= 85 ? 3 : 1;
        player.getWorld().spawnParticle(Particle.SMOKE, muzzle, count, 0.04, 0.05, 0.04, 0.005);
        if (heat >= 80 && ticks % 3 == 0) {
            player.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, muzzle, 1, 0.03, 0.06, 0.03, 0.002);
        }
    }

    /** True when the barrel should not build heat (submerged or exposed to rain). */
    private static boolean isBarrelCooledByWeather(Player player) {
        return player != null && player.isInWaterOrRain();
    }

    /** Explosive chamber cook-off while on fire. */
    public void tickHeCook(Player player) {
        if (player == null || player.getFireTicks() <= 0) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        String cid = plugin.items().chamberRound(hand);
        if (cid == null) {
            return;
        }
        Optional<RoundDefinition> round = plugin.rounds().get(cid);
        if (round.isEmpty() || round.get().explodeRadiusAdd() <= 0) {
            return;
        }
        if (random.nextInt(45) != 0) {
            return;
        }
        plugin.items().setChamberRound(hand, null);
        player.getInventory().setItemInMainHand(hand);
        Location at = player.getEyeLocation();
        player.sendActionBar(ItemFactory.colorize("&c&lHE cooked off in the chamber!"));
        float cookPower = (float) Math.min(3.5, 1.2 + round.get().explodeRadiusAdd());
        if (plugin.explosionRegen() != null) {
            plugin.explosionRegen().blastTerrain(at, cookPower);
        }
        at.getWorld().createExplosion(at, cookPower, false, true, null);
        player.setFireTicks(Math.max(player.getFireTicks(), 60));
    }

    public Optional<GunDefinition> heldGun(ItemStack stack) {
        return plugin.items().gunId(stack).flatMap(plugin.registry()::get);
    }

    public boolean checkAmmo(GunDefinition gun, int amount) {
        if (gun.consumable()) {
            return countGunItems(gun) >= amount;
        }
        if (gun.throwable()) {
            return true;
        }
        Player player = player();
        if (player != null) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (plugin.items().hasChamberRound(hand) && amount <= 1) {
                return true;
            }
        }
        return ammoPool(gun) >= Math.max(1, amount);
    }

    public void removeAmmo(GunDefinition gun, int amount) {
        removeAmmo(gun, amount, pickRound(gun, amount).orElse(null));
    }

    public void removeAmmo(GunDefinition gun, int amount, RoundDefinition preferred) {
        if (amount <= 0) {
            return;
        }
        Player player = player();
        if (player == null) {
            return;
        }
        if (gun.consumable()) {
            removeGunItems(gun, amount);
            return;
        }
        if (gun.throwable()) {
            return;
        }
        RoundDefinition round = preferred != null ? preferred : pickRound(gun, amount).orElse(null);
        if (round == null) {
            return;
        }
        int remaining = amount;
        PlayerInventory inv = player.getInventory();
        // Magazines first
        for (int slot : ammoScanOrder(inv)) {
            if (remaining <= 0) {
                break;
            }
            ItemStack mag = inv.getItem(slot);
            if (mag == null || mag.getAmount() <= 0) {
                continue;
            }
            if (!plugin.items().isMagazine(mag) || !plugin.items().magazineFitsGun(mag, gun, heldGunItem())) {
                continue;
            }
            int have = plugin.items().magazineCountOf(mag, round.fileName());
            if (have <= 0) {
                continue;
            }
            int take = Math.min(have, remaining);
            plugin.items().magazineTakeRounds(mag, round.fileName(), take);
            inv.setItem(slot, mag);
            remaining -= take;
        }
        if (remaining > 0) {
            removeLooseAmmo(gun, remaining, round);
        }
    }

    /**
     * Prefer chamber → mag → offhand loose → sticky last-used → hotbar/inventory loose.
     */
    public Optional<RoundDefinition> pickRound(GunDefinition gun, int amountNeeded) {
        if (gun == null || gun.consumable() || gun.throwable()) {
            return Optional.empty();
        }
        Player player = player();
        if (player == null) {
            return Optional.empty();
        }
        int need = Math.max(1, amountNeeded);
        PlayerInventory inv = player.getInventory();

        ItemStack hand = inv.getItemInMainHand();
        if (plugin.items().hasChamberRound(hand) && need <= 1) {
            String cid = plugin.items().chamberRound(hand);
            if (cid != null && gun.allowsRound(cid)) {
                Optional<RoundDefinition> chambered = plugin.rounds().get(cid);
                if (chambered.isPresent()) {
                    return chambered;
                }
            }
        }

        for (int slot : ammoScanOrder(inv)) {
            ItemStack mag = inv.getItem(slot);
            if (!plugin.items().isMagazine(mag) || !plugin.items().magazineFitsGun(mag, gun, heldGunItem())) {
                continue;
            }
            int count = plugin.items().magazineCount(mag);
            String rid = plugin.items().magazineRoundId(mag);
            if (count < need || rid == null || !gun.allowsRound(rid)) {
                continue;
            }
            Optional<RoundDefinition> fromMag = plugin.rounds().get(rid);
            if (fromMag.isPresent()) {
                return fromMag;
            }
        }

        return pickLooseRound(gun, need);
    }

    private Optional<RoundDefinition> pickLooseRound(GunDefinition gun, int amountNeeded) {
        if (gun == null) {
            return Optional.empty();
        }
        Player player = player();
        if (player == null) {
            return Optional.empty();
        }
        int need = Math.max(1, amountNeeded);
        PlayerInventory inv = player.getInventory();

        ItemStack off = inv.getItemInOffHand();
        Optional<RoundDefinition> offRound = plugin.items().roundOf(off);
        if (offRound.isPresent() && plugin.items().isAllowedRound(off, gun)
                && countLooseRound(gun, offRound.get().fileName()) >= need) {
            return offRound;
        }

        if (lastUsedRoundId != null && gun.allowsRound(lastUsedRoundId)
                && countLooseRound(gun, lastUsedRoundId) >= need) {
            return plugin.rounds().get(lastUsedRoundId);
        }

        for (int slot : ammoScanOrder(inv)) {
            ItemStack item = inv.getItem(slot);
            if (!plugin.items().isAllowedRound(item, gun)) {
                continue;
            }
            Optional<RoundDefinition> round = plugin.items().roundOf(item);
            if (round.isEmpty()) {
                continue;
            }
            if (countLooseRound(gun, round.get().fileName()) >= need) {
                return round;
            }
        }
        return Optional.empty();
    }

    private void removeLooseAmmo(GunDefinition gun, int amount, RoundDefinition preferred) {
        if (amount <= 0 || preferred == null) {
            return;
        }
        Player player = player();
        if (player == null) {
            return;
        }
        int remaining = amount;
        PlayerInventory inv = player.getInventory();
        for (int slot : ammoScanOrder(inv)) {
            if (remaining <= 0) {
                break;
            }
            ItemStack item = inv.getItem(slot);
            Optional<RoundDefinition> found = plugin.items().roundOf(item);
            if (found.isEmpty() || !found.get().fileName().equalsIgnoreCase(preferred.fileName())) {
                continue;
            }
            if (!plugin.items().isAllowedRound(item, gun)) {
                continue;
            }
            int take = Math.min(item.getAmount(), remaining);
            item.setAmount(item.getAmount() - take);
            remaining -= take;
        }
    }

    private int[] ammoScanOrder(PlayerInventory inv) {
        int size = inv.getSize();
        int[] order = new int[size];
        int idx = 0;
        for (int i = 0; i < Math.min(9, size); i++) {
            order[idx++] = i;
        }
        for (int i = 9; i < size; i++) {
            order[idx++] = i;
        }
        return order;
    }

    private int countRound(GunDefinition gun, String roundId) {
        return countLooseRound(gun, roundId) + countMagazineRound(gun, roundId);
    }

    private int countLooseRound(GunDefinition gun, String roundId) {
        Player player = player();
        if (player == null || roundId == null) {
            return 0;
        }
        int total = 0;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            Optional<RoundDefinition> round = plugin.items().roundOf(item);
            if (round.isEmpty() || !round.get().fileName().equalsIgnoreCase(roundId)) {
                continue;
            }
            if (!plugin.items().isAllowedRound(item, gun)) {
                continue;
            }
            total += item.getAmount();
        }
        ItemStack off = inv.getItemInOffHand();
        Optional<RoundDefinition> offRound = plugin.items().roundOf(off);
        if (offRound.isPresent() && offRound.get().fileName().equalsIgnoreCase(roundId)
                && plugin.items().isAllowedRound(off, gun)) {
            boolean counted = false;
            for (int i = 0; i < inv.getSize(); i++) {
                if (inv.getItem(i) == off) {
                    counted = true;
                    break;
                }
            }
            if (!counted) {
                total += off.getAmount();
            }
        }
        return total;
    }

    private int countMagazineRound(GunDefinition gun, String roundId) {
        Player player = player();
        if (player == null || roundId == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getAmount() <= 0) {
                continue;
            }
            if (!plugin.items().isMagazine(item) || !plugin.items().magazineFitsGun(item, gun, heldGunItem())) {
                continue;
            }
            total += plugin.items().magazineCountOf(item, roundId);
        }
        return total;
    }

    private int countMagazineRounds(GunDefinition def) {
        Player player = player();
        if (player == null || def == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getAmount() <= 0) {
                continue;
            }
            if (!plugin.items().isMagazine(item) || !plugin.items().magazineFitsGun(item, def, heldGunItem())) {
                continue;
            }
            for (String rid : plugin.items().magazineLoadList(item)) {
                if (rid != null && def.allowsRound(rid)) {
                    total++;
                }
            }
        }
        return total;
    }

    private void removeGunItems(GunDefinition gun, int amount) {
        Player player = player();
        if (player == null) {
            return;
        }
        int remaining = amount;
        PlayerInventory inv = player.getInventory();
        // Prefer main hand so the thrown consumable is the one being used.
        int[] order = new int[inv.getSize()];
        int hand = inv.getHeldItemSlot();
        order[0] = hand;
        int idx = 1;
        for (int i = 0; i < inv.getSize(); i++) {
            if (i != hand) {
                order[idx++] = i;
            }
        }
        for (int slot : order) {
            if (remaining <= 0) {
                break;
            }
            ItemStack item = inv.getItem(slot);
            if (!isGunItem(item, gun)) {
                continue;
            }
            int take = Math.min(item.getAmount(), remaining);
            item.setAmount(item.getAmount() - take);
            remaining -= take;
        }
    }

    private int countGunItems(GunDefinition gun) {
        Player player = player();
        if (player == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isGunItem(item, gun)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private boolean isGunItem(ItemStack item, GunDefinition gun) {
        if (item == null) {
            return false;
        }
        return plugin.items().gunId(item)
                .filter(id -> id.equalsIgnoreCase(gun.fileName()))
                .isPresent();
    }

    private int countMaterial(org.bukkit.Material material) {
        Player player = player();
        if (player == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private int ammoPool(GunDefinition def) {
        if (def.consumable()) {
            return countGunItems(def);
        }
        if (def.throwable()) {
            return 999;
        }
        Player player = player();
        if (player == null) {
            return 0;
        }
        int total = countMagazineRounds(def);
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (plugin.items().isAllowedRound(item, def)) {
                total += item.getAmount();
            }
        }
        ItemStack off = inv.getItemInOffHand();
        if (plugin.items().isAllowedRound(off, def)) {
            boolean counted = false;
            for (int i = 0; i < inv.getSize(); i++) {
                if (inv.getItem(i) == off) {
                    counted = true;
                    break;
                }
            }
            if (!counted) {
                total += off.getAmount();
            }
        }
        ItemStack hand = inv.getItemInMainHand();
        if (plugin.items().hasChamberRound(hand)) {
            String cid = plugin.items().chamberRound(hand);
            if (cid != null && def.allowsRound(cid)) {
                total += 1;
            }
        }
        return total;
    }

    private void renameGuns(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) {
                continue;
            }
            Optional<GunDefinition> defOpt = heldGun(item);
            if (defOpt.isEmpty()) {
                continue;
            }
            GunDefinition def = defOpt.get();
            GunInstance gun = guns.get(def.fileName().toLowerCase(Locale.ROOT));
            if (gun == null) {
                continue;
            }
            plugin.items().applyDisplay(item, gunNameHud(def, gun));
        }
    }

    private String gunNameHud(GunDefinition def, GunInstance gun) {
        String add = "";
        // Cook / pin-pull progress is action-bar only (tickCook) — do not duplicate on the item name.
        if (def.hasClip()) {
            Player p = player();
            boolean chambered = p != null
                    && plugin.items().hasChamberRound(p.getInventory().getItemInMainHand());
            int inMag = activeMagCount(def);
            int chamber = chambered ? 1 : 0;
            int pool = ammoPool(def);
            int reserve = Math.max(0, pool - inMag - chamber);
            int cap = activeMagCapacity(def);
            String magPart = inMag + (chambered ? "+1" : "");
            if (cap > 0 && (inMag > 0 || chambered) && cap != def.maxClipSize()) {
                magPart = magPart + "/" + cap;
            }
            String chamberLabel = "";
            String nextLabel = "";
            if (p != null) {
                ItemStack hand = p.getInventory().getItemInMainHand();
                String cid = plugin.items().chamberRound(hand);
                if (cid != null) {
                    chamberLabel = plugin.rounds().get(cid)
                            .map(r -> plainRoundName(r)).orElse(cid);
                }
                Optional<RoundDefinition> next = peekMagRound(def);
                if (next.isPresent()) {
                    nextLabel = plainRoundName(next.get());
                }
            }
            add = "&e    «" + magPart + " │ " + reserve + "»";
            if (!chamberLabel.isEmpty() || !nextLabel.isEmpty()) {
                String ch = chamberLabel.isEmpty() ? "—" : chamberLabel;
                String nx = nextLabel.isEmpty() ? "—" : nextLabel;
                add = add + " &8| &7" + ch + " &8→ &7" + nx;
            }
            if (Boolean.TRUE.equals(jammed.get(def.fileName().toLowerCase(Locale.ROOT)))) {
                add = "&c    JAMMED";
            }
            // Reload progress is the action bar (same slot as out-of-ammo).
        }
        return def.displayName() + add;
    }

    public ItemStack getLastItemHeld() {
        return lastHeldItem;
    }

    public void unload() {
        clearLaserLights();
        currentlyFiring = null;
        lastFiredGun = null;
        cook = null;
        cookButtonHeld = false;
        activeMagSlot.clear();
        guns.clear();
    }

    /* -------------------- cookable throwables (pin / light) -------------------- */

    /** Flashbang, grenade, molotov — splash throwable that cooks before throw. */
    static boolean isCookable(GunDefinition def) {
        if (def == null || !(def.throwable() || def.consumable())) {
            return false;
        }
        return def.flashRadius() > 0 || def.explodeRadius() > 0 || def.fireRadius() > 0;
    }

    private void handleCookableClick(GunInstance gun) {
        Player player = player();
        if (player == null) {
            return;
        }
        GunDefinition def = gun.definition();
        if (!checkAmmo(def, Math.max(1, def.ammoAmtNeeded()))) {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1.4f);
            player.sendActionBar(ItemFactory.colorize(def.outOfAmmoMessage()));
            return;
        }

        if (cook != null && cook.fileName.equalsIgnoreCase(def.fileName())) {
            if (cookButtonHeld) {
                return; // still holding the engage click — release first, then click to throw
            }
            throwCooked(gun);
            return;
        }

        if (cook != null) {
            // Armed a different nade — previous one cooks off in their hand
            handDetonate(player);
        }
        if (cookButtonHeld) {
            return;
        }
        startCook(def);
        cookButtonHeld = true;
        gun.setHeldDownTicks(0);
        gun.setLastFired(0);
    }

    private void startCook(GunDefinition def) {
        Player player = player();
        if (player == null) {
            return;
        }
        int fuse = def.releaseTime() > 0 ? def.releaseTime() : DEFAULT_COOK_FUSE_TICKS;
        fuse = Math.max(20, fuse);
        String label = cookLabel(def);
        cook = new CookState(def.fileName().toLowerCase(Locale.ROOT), def, fuse, fuse, label);
        playCookStartSound(player, def);
        player.sendActionBar(ItemFactory.colorize("&e" + label + "&7 — click again to throw"));
    }

    private void tickCook(Player player) {
        if (cook == null) {
            return;
        }
        cook.fuseRemaining--;
        double burnFrac = 1.0 - (cook.fuseRemaining / (double) Math.max(1, cook.fuseMax));
        int percent = (int) Math.round(Math.min(1.0, Math.max(0.0, burnFrac)) * 100.0);
        int cookSize = 10;
        int amt = (int) Math.round(Math.min(1.0, Math.max(0.0, burnFrac)) * cookSize);
        String refresh = "\u2588".repeat(amt) + "\u2592".repeat(cookSize - amt);
        player.sendActionBar(ItemFactory.colorize("&c" + refresh + " " + cook.label + " " + percent + "%"));

        if (cook.def.fireRadius() > 0 && ticks % 4 == 0) {
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_FIRE_AMBIENT,
                    SoundCategory.PLAYERS, 0.35f, 1.4f);
        }

        if (cook.fuseRemaining <= 0) {
            handDetonate(player);
        }
    }

    private void throwCooked(GunInstance gun) {
        Player player = player();
        CookState state = cook;
        if (player == null || state == null) {
            return;
        }
        GunDefinition def = state.def;
        int remaining = Math.max(1, state.fuseRemaining);
        cook = null;
        cookButtonHeld = true; // until RMB released; cleared in tick() when heldDownTicks==0

        if (!checkAmmo(def, Math.max(1, def.ammoAmtNeeded()))) {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1.4f);
            player.sendActionBar(ItemFactory.colorize(def.outOfAmmoMessage()));
            cookButtonHeld = false;
            return;
        }

        GunFireEvent event = new GunFireEvent(player, def, def.ammoAmtNeeded(), currentAccuracy(def));
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            // Pin already pulled — put the fuse back
            cook = state;
            cookButtonHeld = false;
            return;
        }
        if (event.getAmmoNeeded() > 0) {
            removeAmmo(def, event.getAmmoNeeded(), null);
        }

        gun.setChanged(true);
        gun.setRoundsFired(gun.roundsFired() + 1);
        gun.setTimer(Math.max(gun.timer(), def.bulletDelayTime()));
        gun.setLastFired(0);
        playFireSounds(player, def, null, false);

        double speed = def.bulletSpeed();
        Vector vec = createShotVector(player, event.getAccuracy(), speed);
        // Slight lob — clears a bit farther without becoming a mortar throw
        vec.setY(vec.getY() + 0.14);
        Bullet bullet = new Bullet(plugin, player, vec, def, null, null, false, remaining);
        plugin.bullets().add(bullet);
        lastFiredGun = gun;
        lastFiredTicks = ticks;
        player.sendActionBar(ItemFactory.colorize("&7Thrown — fuse &f"
                + String.format(Locale.ROOT, "%.1f", remaining / 20.0) + "s"));
        player.playSound(player.getLocation(), Sound.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 0.8f, 0.7f);
    }

    private void handDetonate(Player player) {
        CookState state = cook;
        cook = null;
        cookButtonHeld = false;
        if (state == null || player == null) {
            return;
        }
        if (checkAmmo(state.def, Math.max(1, state.def.ammoAmtNeeded()))) {
            removeAmmo(state.def, Math.max(1, state.def.ammoAmtNeeded()), null);
        }
        Location at = player.getLocation().add(0.0, 1.0, 0.0);
        ImpactEffects.apply(state.def, player, at, null, plugin);
        player.setNoDamageTicks(0);
        Bullet.applyAttributedDamage(player, Math.max(4, state.def.gunDamage() * 0.35), player);
        player.sendActionBar(ItemFactory.colorize("&c&lCooked off in your hand!"));
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 1.2f, 1.2f);
        player.removeScoreboardTag("pgm_reload");
    }

    private static String cookLabel(GunDefinition def) {
        if (def.fireRadius() > 0) {
            return "LIT";
        }
        if (def.flashRadius() > 0) {
            return "PIN PULLED";
        }
        return "COOKING";
    }

    private static void playCookStartSound(Player player, GunDefinition def) {
        if (def.fireRadius() > 0) {
            player.playSound(player.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, SoundCategory.PLAYERS, 1f, 1.1f);
            player.playSound(player.getLocation(), Sound.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS, 0.7f, 1.3f);
            return;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, SoundCategory.PLAYERS, 0.8f, 1.6f);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.5f, 0.8f);
    }

    private static final class CookState {
        final String fileName;
        final GunDefinition def;
        final int fuseMax;
        int fuseRemaining;
        final String label;

        CookState(String fileName, GunDefinition def, int fuseMax, int fuseRemaining, String label) {
            this.fileName = fileName;
            this.def = def;
            this.fuseMax = fuseMax;
            this.fuseRemaining = fuseRemaining;
            this.label = label;
        }
    }

}
