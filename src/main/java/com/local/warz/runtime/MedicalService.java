package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Broken bones, bleeding (blood volume drain), tourniquet / bandage / blood bag.
 * Blood % drives combat/vision debuffs; 0% is fatal.
 */
public final class MedicalService implements Listener {
    public enum BleedSeverity {
        NONE,
        NORMAL,
        FAST
    }

    /** Blood remaining tiers (percent of {@link #MAX_BLOOD_L}). */
    public enum BloodTier {
        FULL,       // 100–75%
        MILD,       // 75–50%
        MODERATE,   // 50–35%
        SEVERE,     // 35–20%
        CRITICAL,   // 20–10%
        COLLAPSE,   // &lt;10% (still alive)
        DEAD        // 0%
    }

    private static final Set<EntityType> ZOMBIE_DROPS = EnumSet.of(
            EntityType.ZOMBIE,
            EntityType.HUSK,
            EntityType.ZOMBIE_VILLAGER,
            EntityType.ZOMBIFIED_PIGLIN,
            EntityType.PARCHED
    );

    private static final int SPLINT_CHANCE = 4;      // 1/4
    private static final int BANDAGE_CHANCE = 6;     // 1/6
    private static final int TOURNIQUET_CHANCE = 8;  // 1/8
    private static final int BLOOD_BAG_CHANCE = 12;  // 1/12
    private static final int BLEED_CHANCE = 12;      // 1/12 normal bleed
    private static final int BLEED_CHANCE_BULLET = 7; // ~1/7 on gunshot
    private static final int FAST_BLEED_CHANCE = 16; // 1/16 arterial
    private static final int FAST_BLEED_BULLET = 9;  // ~1/9 on gunshot
    private static final int BONES_ACTIONBAR_INTERVAL_SEC = 10;

    /** Adult circulating volume ~5.0 L. */
    public static final double MAX_BLOOD_L = 5.0;
    /** Sidebar: orange below 75%. */
    public static final double BLOOD_ORANGE_L = MAX_BLOOD_L * 0.75;
    /** Sidebar: red below 50%. */
    public static final double BLOOD_CRITICAL_L = MAX_BLOOD_L * 0.50;
    private static final double NORMAL_BLEED_L_PER_SEC = 0.025; // ~1.5 L/min
    private static final double FAST_BLEED_L_PER_SEC = 0.12;    // severe arterial
    /** Max blood regen at full hunger (20/20); scales down linearly with food level. */
    private static final double BLOOD_REGEN_MAX_L_PER_SEC = 0.04;

    private static final int EFFECT_REFRESH_TICKS = 40;
    private static final int PULSE_INTERVAL_SEC = 4;
    private static final int DARKNESS_BURST_INTERVAL_SEC = 4;
    private static final int BLINDNESS_BURST_INTERVAL_SEC = 3;

    private static final double BONES_SPEED_SCALAR = -0.30;
    private static final NamespacedKey BONES_SLOW_KEY = new NamespacedKey("warz", "broken_bones");

    private final WarzPlugin plugin;
    private final Set<UUID> brokenBones = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BleedSeverity> bleeding = new ConcurrentHashMap<>();
    private final Map<UUID, Double> bloodLiters = new ConcurrentHashMap<>();
    private final Set<UUID> suppressBleedProc = ConcurrentHashMap.newKeySet();
    private final Set<UUID> recentBulletHit = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BloodTier> lastBloodTier = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> bloodTickSec = new ConcurrentHashMap<>();
    private final Set<UUID> bloodVisionProtected = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastHealMs = new ConcurrentHashMap<>();
    private BukkitTask task;
    private int bonesActionBarSec;

    public MedicalService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) {
            task.cancel();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            ensureBlood(player);
            rematerializeMedical(player);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        brokenBones.clear();
        bleeding.clear();
        bloodLiters.clear();
        recentBulletHit.clear();
        lastBloodTier.clear();
        bloodTickSec.clear();
        bloodVisionProtected.clear();
        lastHealMs.clear();
    }

    /** True while low-blood Darkness/Blindness must not be stripped by NVG/thermal. */
    public boolean isBloodVisionProtected(Player player) {
        return player != null && bloodVisionProtected.contains(player.getUniqueId());
    }

    /** Near-death collapse — player cannot stand from prone. */
    public boolean isBloodCollapsed(Player player) {
        return player != null && bloodPercent(player) < 10.0;
    }

    public double bloodPercent(Player player) {
        if (player == null) {
            return 100.0;
        }
        return (ensureBlood(player) / MAX_BLOOD_L) * 100.0;
    }

    public BloodTier bloodTier(Player player) {
        return tierFor(ensureBlood(player));
    }

    public static BloodTier tierFor(double liters) {
        if (liters <= 0.0) {
            return BloodTier.DEAD;
        }
        double pct = (liters / MAX_BLOOD_L) * 100.0;
        if (pct >= 75.0) {
            return BloodTier.FULL;
        }
        if (pct >= 50.0) {
            return BloodTier.MILD;
        }
        if (pct >= 35.0) {
            return BloodTier.MODERATE;
        }
        if (pct >= 20.0) {
            return BloodTier.SEVERE;
        }
        if (pct >= 10.0) {
            return BloodTier.CRITICAL;
        }
        return BloodTier.COLLAPSE;
    }

    /** Called from gun hit path just before {@code damage()}. */
    public void flagBulletWound(UUID playerId) {
        if (playerId == null) {
            return;
        }
        recentBulletHit.add(playerId);
        Bukkit.getScheduler().runTask(plugin, () -> recentBulletHit.remove(playerId));
    }

    public double bloodLiters(Player player) {
        if (player == null) {
            return MAX_BLOOD_L;
        }
        return ensureBlood(player);
    }

    public BleedSeverity bleedSeverity(Player player) {
        if (player == null) {
            return BleedSeverity.NONE;
        }
        return bleeding.getOrDefault(player.getUniqueId(), BleedSeverity.NONE);
    }

    public boolean isBleeding(Player player) {
        return bleedSeverity(player) != BleedSeverity.NONE;
    }

    public boolean isFastBleeding(Player player) {
        return bleedSeverity(player) == BleedSeverity.FAST;
    }

    public boolean hasBrokenBones(Player player) {
        return player != null && brokenBones.contains(player.getUniqueId());
    }

    private void tick() {
        bonesActionBarSec++;
        boolean bonesBar = bonesActionBarSec % BONES_ACTIONBAR_INTERVAL_SEC == 0;

        for (UUID id : brokenBones) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline() || player.isDead()) {
                continue;
            }
            applyBonesSlow(player);
            if (bonesBar) {
                player.sendActionBar(ItemFactory.colorize("&cLegs are broken - Find a Splint"));
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
                bloodLiters.put(player.getUniqueId(), MAX_BLOOD_L);
                bleeding.remove(player.getUniqueId());
                applyBloodState(player, MAX_BLOOD_L, false);
                continue;
            }
            if (player.isDead()) {
                continue;
            }

            double blood = ensureBlood(player);
            BleedSeverity sev = bleeding.getOrDefault(player.getUniqueId(), BleedSeverity.NONE);

            if (sev == BleedSeverity.NORMAL) {
                blood -= NORMAL_BLEED_L_PER_SEC;
                player.sendActionBar(ItemFactory.colorize("&cBleeding… &7Use a &fBandage"));
            } else if (sev == BleedSeverity.FAST) {
                blood -= FAST_BLEED_L_PER_SEC;
                player.sendActionBar(ItemFactory.colorize("&4&lArterial bleed! &7Use a &6Tourniquet"));
            } else if (blood < MAX_BLOOD_L) {
                // Full hunger → fastest regen; empty bars → almost none
                double foodFactor = Math.max(0, player.getFoodLevel()) / 20.0;
                blood += BLOOD_REGEN_MAX_L_PER_SEC * foodFactor;
            }

            blood = clamp(blood, 0.0, MAX_BLOOD_L);
            bloodLiters.put(player.getUniqueId(), blood);
            applyBloodState(player, blood, true);
        }

        // Bones slow after blood effects so short bleed-slowness cannot wipe it.
        for (UUID id : brokenBones) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline() || player.isDead()) {
                continue;
            }
            applyBonesSlow(player);
        }
    }

    public void clearAll(Player player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        brokenBones.remove(id);
        bleeding.remove(id);
        bloodLiters.put(id, MAX_BLOOD_L);
        lastBloodTier.remove(id);
        bloodTickSec.remove(id);
        bloodVisionProtected.remove(id);
        clearBloodPotionEffects(player);
        removeBonesSlow(player);
        sendBloodFx(player, 0f, false);
    }

    private void applyBloodState(Player player, double blood, boolean canKill) {
        BloodTier tier = tierFor(blood);
        UUID id = player.getUniqueId();
        int sec = bloodTickSec.merge(id, 1, Integer::sum);

        if (tier == BloodTier.DEAD) {
            if (canKill && !player.isDead()) {
                clearBloodPotionEffects(player);
                bloodVisionProtected.remove(id);
                lastBloodTier.put(id, BloodTier.DEAD);
                sendBloodFx(player, 1f, false);
                suppressBleedProc.add(id);
                try {
                    player.sendActionBar(ItemFactory.colorize("&4&lYou bled out…"));
                    if (plugin.killFeed() != null) {
                        plugin.killFeed().markBleedOut(player);
                    }
                    player.setHealth(0.0);
                } finally {
                    suppressBleedProc.remove(id);
                }
            }
            return;
        }

        BloodTier prev = lastBloodTier.put(id, tier);
        boolean pulse = false;

        switch (tier) {
            case FULL -> clearBloodPotionEffects(player);
            case MILD -> {
                applyBloodEffect(player, PotionEffectType.WEAKNESS, 0);
                pulse = sec % PULSE_INTERVAL_SEC == 0;
            }
            case MODERATE -> {
                applyBloodEffect(player, PotionEffectType.WEAKNESS, 0);
                applyBloodEffect(player, PotionEffectType.SLOWNESS, 0);
            }
            case SEVERE -> {
                applyBloodEffect(player, PotionEffectType.SLOWNESS, 1);
                applyBloodEffect(player, PotionEffectType.WEAKNESS, 1);
                applyBloodEffect(player, PotionEffectType.MINING_FATIGUE, 0);
            }
            case CRITICAL -> {
                applyBloodEffect(player, PotionEffectType.SLOWNESS, 2);
                applyBloodEffect(player, PotionEffectType.WEAKNESS, 2);
                applyBloodEffect(player, PotionEffectType.NAUSEA, 0);
                if (sec % DARKNESS_BURST_INTERVAL_SEC == 0) {
                    applyBloodEffect(player, PotionEffectType.DARKNESS, 0, 30);
                }
            }
            case COLLAPSE -> {
                applyBloodEffect(player, PotionEffectType.SLOWNESS, 3);
                applyBloodEffect(player, PotionEffectType.WEAKNESS, 2);
                applyBloodEffect(player, PotionEffectType.NAUSEA, 0);
                applyBloodEffect(player, PotionEffectType.DARKNESS, 0, EFFECT_REFRESH_TICKS + 10);
                if (sec % BLINDNESS_BURST_INTERVAL_SEC == 0) {
                    applyBloodEffect(player, PotionEffectType.BLINDNESS, 0, 40);
                }
                if (plugin.prone() != null && !plugin.prone().isProne(player)) {
                    plugin.prone().enterProne(player);
                    if (prev != BloodTier.COLLAPSE) {
                        player.sendActionBar(ItemFactory.colorize("&4&lCollapsing… &7Find a &4Blood Bag"));
                    }
                }
            }
            default -> {
            }
        }

        if (tier.ordinal() < BloodTier.CRITICAL.ordinal()) {
            // Drop intermittent vision effects when recovering above those tiers
            if (prev != null && prev.ordinal() >= BloodTier.CRITICAL.ordinal()) {
                player.removePotionEffect(PotionEffectType.DARKNESS);
                player.removePotionEffect(PotionEffectType.BLINDNESS);
            }
        }
        if (tier != BloodTier.SEVERE && tier != BloodTier.CRITICAL && tier != BloodTier.COLLAPSE) {
            player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        }
        if (tier.ordinal() < BloodTier.CRITICAL.ordinal()) {
            player.removePotionEffect(PotionEffectType.NAUSEA);
        }
        if (tier == BloodTier.FULL) {
            player.removePotionEffect(PotionEffectType.WEAKNESS);
            // Don't strip stronger aiming Slowness
            PotionEffect slow = effectOf(player, PotionEffectType.SLOWNESS);
            if (slow != null && slow.getAmplifier() <= 3 && slow.getDuration() <= EFFECT_REFRESH_TICKS + 5
                    && !hasBrokenBones(player)) {
                player.removePotionEffect(PotionEffectType.SLOWNESS);
            }
        }

        if (tier == BloodTier.CRITICAL || tier == BloodTier.COLLAPSE) {
            bloodVisionProtected.add(id);
        } else {
            bloodVisionProtected.remove(id);
        }

        sendBloodFx(player, vignetteFor(tier), pulse);
    }

    private static float vignetteFor(BloodTier tier) {
        return switch (tier) {
            case FULL -> 0f;
            case MILD -> 0.12f;
            case MODERATE -> 0.28f;
            case SEVERE -> 0.48f;
            case CRITICAL -> 0.72f;
            case COLLAPSE, DEAD -> 0.92f;
        };
    }

    private static PotionEffect effectOf(Player player, PotionEffectType type) {
        try {
            return player.getPotionEffect(type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void applyBloodEffect(Player player, PotionEffectType type, int amplifier) {
        applyBloodEffect(player, type, amplifier, EFFECT_REFRESH_TICKS);
    }

    private static void applyBloodEffect(Player player, PotionEffectType type, int amplifier, int durationTicks) {
        PotionEffect current = effectOf(player, type);
        if (current != null && current.getAmplifier() > amplifier) {
            return; // keep stronger effects (e.g. ADS slowness)
        }
        player.addPotionEffect(new PotionEffect(type, durationTicks, amplifier, false, false, true));
    }

    private void clearBloodPotionEffects(Player player) {
        // Only clear if they look like our short refresh effects
        for (PotionEffectType type : new PotionEffectType[]{
                PotionEffectType.WEAKNESS,
                PotionEffectType.MINING_FATIGUE,
                PotionEffectType.NAUSEA,
                PotionEffectType.DARKNESS,
                PotionEffectType.BLINDNESS
        }) {
            PotionEffect e = effectOf(player, type);
            if (e != null && e.getDuration() <= EFFECT_REFRESH_TICKS + 20) {
                player.removePotionEffect(type);
            }
        }
        PotionEffect slow = effectOf(player, PotionEffectType.SLOWNESS);
        if (slow != null && slow.getAmplifier() <= 3 && slow.getDuration() <= EFFECT_REFRESH_TICKS + 20
                && !brokenBones.contains(player.getUniqueId())) {
            player.removePotionEffect(PotionEffectType.SLOWNESS);
        }
    }

    private void sendBloodFx(Player player, float severity, boolean pulse) {
        LaserCompanionBridge bridge = plugin.laserBridge();
        if (bridge != null) {
            bridge.sendBloodFx(player, severity, pulse);
        }
    }

    private double ensureBlood(Player player) {
        return bloodLiters.computeIfAbsent(player.getUniqueId(), id -> MAX_BLOOD_L);
    }

    /* -------------------- damage / death -------------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return;
        }
        if (suppressBleedProc.contains(player.getUniqueId())) {
            return;
        }

        // Burn / lava / poison / magic DoT is not a wound — never starts a bleed.
        // CardForge often remaps fire ticks to CUSTOM, so also skip while actually burning
        // unless this hit is a gunshot / melee / explosion.
        if (isNonWoundDamage(event.getCause())
                || (!recentBulletHit.contains(player.getUniqueId())
                        && isFireBleedImmune(player, event.getCause()))) {
            return;
        }

        // Hypothermia tick damage must never start a bleed; gunshots while cold still can.
        if (plugin.scuba() != null && plugin.scuba().isColdDamageActive(player)) {
            return;
        }

        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            if (event.getFinalDamage() < 3.0 && player.getFallDistance() < 5.0f) {
                return;
            }
            if (brokenBones.add(player.getUniqueId())) {
                player.sendMessage(ItemFactory.colorize("&cYour bones are broken! &7Use a &cSplint &7to heal."));
                applyBonesSlow(player);
            }
            // Fall damage: broken bones only — never rolls bleed / arterial.
            return;
        }

        boolean bullet = recentBulletHit.contains(player.getUniqueId());
        // While on fire, vanilla i-frames often zero gunshot finalDamage — still proc bleed.
        if (event.getFinalDamage() <= 0 && !bullet) {
            return;
        }

        double dmg = Math.max(event.getFinalDamage(), bullet ? 1.0 : 0.0);
        BleedSeverity current = bleeding.getOrDefault(player.getUniqueId(), BleedSeverity.NONE);
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Fast / arterial — rare base, still more likely on gunshots / heavy hits
        int fastDenom = bullet ? FAST_BLEED_BULLET : FAST_BLEED_CHANCE;
        if (dmg >= 10.0) {
            fastDenom = Math.max(6, fastDenom - 1);
        }
        if (current != BleedSeverity.FAST && rng.nextInt(fastDenom) == 0) {
            startBleed(player, BleedSeverity.FAST);
            return;
        }

        // Normal bleed — 1/12 (1/7 gunshot)
        if (current == BleedSeverity.NONE) {
            int normalDenom = bullet ? BLEED_CHANCE_BULLET : BLEED_CHANCE;
            if (rng.nextInt(normalDenom) == 0) {
                startBleed(player, BleedSeverity.NORMAL);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        event.setDroppedExp(0);
        event.setKeepLevel(true);
    }

    private void startBleed(Player player, BleedSeverity severity) {
        BleedSeverity prev = bleeding.getOrDefault(player.getUniqueId(), BleedSeverity.NONE);
        if (prev == BleedSeverity.FAST) {
            return;
        }
        if (prev == BleedSeverity.NORMAL && severity == BleedSeverity.NORMAL) {
            return;
        }
        bleeding.put(player.getUniqueId(), severity);
        if (severity == BleedSeverity.FAST) {
            player.sendMessage(ItemFactory.colorize(
                    "&4&lHeavy bleeding! &7Apply a &6Tourniquet &7immediately."));
        } else {
            player.sendMessage(ItemFactory.colorize(
                    "&cYou're bleeding! &7Use a &fBandage &7to stop it."));
        }
    }

    /** External wound sources (e.g. thrown broken glass). */
    public void inflictBleed(Player player, BleedSeverity severity) {
        if (player == null || severity == null || severity == BleedSeverity.NONE) {
            return;
        }
        startBleed(player, severity);
    }

    /**
     * Apply damage that must never roll a bleed (lava heat, hydrazine, etc.).
     * Sync {@link Player#damage} fires {@link EntityDamageEvent} before returning.
     */
    public void damageWithoutBleed(Player player, double amount) {
        if (player == null || amount <= 0 || player.isDead()) {
            return;
        }
        UUID id = player.getUniqueId();
        suppressBleedProc.add(id);
        try {
            player.damage(amount);
        } finally {
            suppressBleedProc.remove(id);
        }
    }

    private static boolean isNonWoundDamage(EntityDamageEvent.DamageCause cause) {
        if (cause == null) {
            return false;
        }
        return isEnvironmentalFire(cause)
                || cause == EntityDamageEvent.DamageCause.MAGIC
                || cause == EntityDamageEvent.DamageCause.POISON
                || cause == EntityDamageEvent.DamageCause.WITHER
                || cause == EntityDamageEvent.DamageCause.STARVATION
                || cause == EntityDamageEvent.DamageCause.DROWNING
                || cause == EntityDamageEvent.DamageCause.FREEZE
                || cause == EntityDamageEvent.DamageCause.SUFFOCATION;
    }

    private static boolean isFireBleedImmune(Player player, EntityDamageEvent.DamageCause cause) {
        if (isEnvironmentalFire(cause)) {
            return true;
        }
        if (isCombatWound(cause)) {
            return false;
        }
        return player.getFireTicks() > 0 || isStandingInFire(player);
    }

    private static boolean isCombatWound(EntityDamageEvent.DamageCause cause) {
        if (cause == null) {
            return false;
        }
        return cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || cause == EntityDamageEvent.DamageCause.PROJECTILE
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.THORNS;
    }

    private static boolean isStandingInFire(Player player) {
        if (player == null) {
            return false;
        }
        Block feet = player.getLocation().getBlock();
        Block below = player.getLocation().clone().subtract(0, 0.15, 0).getBlock();
        return isFireBlock(feet.getType()) || isFireBlock(below.getType());
    }

    private static boolean isFireBlock(Material type) {
        if (type == null) {
            return false;
        }
        return type == Material.FIRE
                || type == Material.SOUL_FIRE
                || type == Material.LAVA
                || type == Material.LAVA_CAULDRON
                || type == Material.CAMPFIRE
                || type == Material.SOUL_CAMPFIRE
                || type == Material.MAGMA_BLOCK;
    }

    private static boolean isEnvironmentalFire(EntityDamageEvent.DamageCause cause) {
        if (cause == null) {
            return false;
        }
        return cause == EntityDamageEvent.DamageCause.FIRE
                || cause == EntityDamageEvent.DamageCause.FIRE_TICK
                || cause == EntityDamageEvent.DamageCause.LAVA
                || cause == EntityDamageEvent.DamageCause.HOT_FLOOR
                || cause == EntityDamageEvent.DamageCause.CAMPFIRE;
    }

    private boolean isExposedUnderwater(Player player) {
        if (!player.isUnderWater() && !eyeInWater(player)) {
            return false;
        }
        ItemFactory items = plugin.items();
        var inv = player.getInventory();
        boolean helmet = items.isScubaHelmet(inv.getHelmet());
        boolean tank = items.isScubaTank(inv.getChestplate());
        boolean legs = items.isWetsuitLeggings(inv.getLeggings());
        boolean boots = items.isWetsuitBoots(inv.getBoots());
        return !helmet || !tank || !legs || !boots;
    }

    private static boolean eyeInWater(Player player) {
        Material eye = player.getEyeLocation().getBlock().getType();
        return eye == Material.WATER || eye == Material.BUBBLE_COLUMN;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onZombieDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!ZOMBIE_DROPS.contains(entity.getType())) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (rng.nextInt(SPLINT_CHANCE) == 0) {
            event.getDrops().add(plugin.items().createSplint(1));
        }
        if (rng.nextInt(BANDAGE_CHANCE) == 0) {
            event.getDrops().add(plugin.items().createBandage(1));
        }
        if (rng.nextInt(TOURNIQUET_CHANCE) == 0) {
            event.getDrops().add(plugin.items().createTourniquet(1));
        }
        if (rng.nextInt(BLOOD_BAG_CHANCE) == 0) {
            event.getDrops().add(plugin.items().createBloodBag(1));
        }
    }

    /**
     * Ground medical (zombie loot) often has different FOOD/potion components than
     * kit items, so vanilla {@code isSimilar} refuses to merge. Stack by medical_id.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMedicalPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (plugin.restraints() != null && plugin.restraints().isRestrained(player)) {
            return;
        }
        ItemFactory items = plugin.items();
        if (items == null) {
            return;
        }
        Item entity = event.getItem();
        ItemStack incoming = entity.getItemStack();
        String id = items.medicalId(incoming);
        if (id == null) {
            return;
        }
        int amount = Math.max(1, incoming.getAmount());
        event.setCancelled(true);
        entity.remove();
        absorbMedical(player, id, amount);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.2f, 1.0f);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMedicalClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemFactory items = plugin.items();
        if (items == null) {
            return;
        }
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        String idCursor = items.medicalId(cursor);
        String idSlot = items.medicalId(current);
        if (idCursor == null || !idCursor.equals(idSlot)) {
            return;
        }
        int cursorAmt = cursor.getAmount();
        int slotAmt = current.getAmount();
        int max = ItemFactory.MEDICAL_STACK_MAX;
        if (click == ClickType.LEFT) {
            int space = max - slotAmt;
            if (space <= 0) {
                return;
            }
            int move = Math.min(space, cursorAmt);
            event.setCancelled(true);
            event.setCurrentItem(items.createMedical(idSlot, slotAmt + move));
            int left = cursorAmt - move;
            player.setItemOnCursor(left <= 0 ? null : items.createMedical(idCursor, left));
        } else {
            if (slotAmt >= max) {
                return;
            }
            event.setCancelled(true);
            event.setCurrentItem(items.createMedical(idSlot, slotAmt + 1));
            int left = cursorAmt - 1;
            player.setItemOnCursor(left <= 0 ? null : items.createMedical(idCursor, left));
        }
    }

    private void absorbMedical(Player player, String id, int amount) {
        ItemFactory items = plugin.items();
        ItemStack extra = items.createMedical(id, amount);
        if (extra == null) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        for (var leftover : inv.addItem(extra).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        rematerializeMedical(player);
    }

    /* -------------------- use items -------------------- */

    private boolean isMedicalItem(ItemStack hand) {
        ItemFactory items = plugin.items();
        return items != null && (items.isSplint(hand) || items.isBandage(hand)
                || items.isTourniquet(hand) || items.isBloodBag(hand));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isMedicalItem(hand)) {
            // Also accept the event item (CardForge sometimes mirrors a copy).
            ItemStack eventItem = event.getItem();
            if (!isMedicalItem(eventItem)) {
                return;
            }
            hand = eventItem;
        }
        event.setCancelled(true);
        try {
            player.clearActiveItem();
        } catch (Throwable ignored) {
        }
        if (!healReady(player)) {
            return;
        }
        Player target = rayPlayer(player, 4.0);
        boolean used;
        if (target != null && !target.equals(player)) {
            used = tryHealOther(player, target, hand);
        } else {
            used = tryHealSelf(player, hand);
        }
        if (!used) {
            // Allow another attempt soon when the item didn't apply (wrong wound type).
            lastHealMs.remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!(event.getRightClicked() instanceof Player target)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isMedicalItem(hand)) {
            return;
        }
        event.setCancelled(true);
        try {
            player.clearActiveItem();
        } catch (Throwable ignored) {
        }
        if (!healReady(player)) {
            return;
        }
        if (!tryHealOther(player, target, hand)) {
            lastHealMs.remove(player.getUniqueId());
        }
    }

    private boolean healReady(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastHealMs.get(player.getUniqueId());
        if (last != null && now - last < 250L) {
            return false;
        }
        lastHealMs.put(player.getUniqueId(), now);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onConsume(org.bukkit.event.player.PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (!isMedicalItem(item)) {
            return;
        }
        // Consumable tag only exists to force a UseItem packet; never finish the eat.
        event.setCancelled(true);
        Player player = event.getPlayer();
        try {
            player.clearActiveItem();
        } catch (Throwable ignored) {
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isMedicalItem(hand)) {
            hand = item;
        }
        if (!healReady(player)) {
            return;
        }
        if (!tryHealSelf(player, hand)) {
            lastHealMs.remove(player.getUniqueId());
        }
    }

    /** @return true if an item was consumed / effect applied */
    private boolean tryHealSelf(Player player, ItemStack hand) {
        ItemFactory items = plugin.items();
        if (items.isSplint(hand)) {
            if (!hasBrokenBones(player)) {
                player.sendActionBar(ItemFactory.colorize("&7Your bones aren't broken."));
                player.sendMessage(Component.text("Your bones aren't broken.", NamedTextColor.GRAY));
                return false;
            }
            consumeOne(player, hand);
            healBones(player);
            player.sendMessage(ItemFactory.colorize("&aBones set. &7You can walk again."));
            player.sendActionBar(ItemFactory.colorize("&aBones set"));
            player.playSound(player.getLocation(), Sound.ITEM_BONE_MEAL_USE, 1f, 0.8f);
            return true;
        }
        if (items.isBandage(hand)) {
            BleedSeverity sev = bleedSeverity(player);
            if (sev == BleedSeverity.NONE) {
                player.sendActionBar(ItemFactory.colorize("&7You're not bleeding."));
                player.sendMessage(Component.text("You're not bleeding.", NamedTextColor.GRAY));
                return false;
            }
            if (sev == BleedSeverity.FAST) {
                player.sendActionBar(ItemFactory.colorize("&cNeed a &6Tourniquet"));
                player.sendMessage(ItemFactory.colorize("&cBandage won't stop this! &7Use a &6Tourniquet&7."));
                return false;
            }
            consumeOne(player, hand);
            healBleed(player);
            player.sendMessage(ItemFactory.colorize("&aBandaged. &7Bleeding stopped."));
            player.sendActionBar(ItemFactory.colorize("&aBandaged"));
            player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1.2f);
            return true;
        }
        if (items.isTourniquet(hand)) {
            if (!isBleeding(player)) {
                player.sendActionBar(ItemFactory.colorize("&7You're not bleeding."));
                player.sendMessage(Component.text("You're not bleeding.", NamedTextColor.GRAY));
                return false;
            }
            consumeOne(player, hand);
            healBleed(player);
            player.sendMessage(ItemFactory.colorize("&aTourniquet applied. &7Bleeding stopped."));
            player.sendActionBar(ItemFactory.colorize("&aTourniquet applied"));
            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 0.7f);
            return true;
        }
        if (items.isBloodBag(hand)) {
            if (ensureBlood(player) >= MAX_BLOOD_L - 0.01) {
                player.sendActionBar(ItemFactory.colorize("&7Blood volume already full."));
                player.sendMessage(Component.text("Your blood volume is already full.", NamedTextColor.GRAY));
                return false;
            }
            consumeOne(player, hand);
            restoreBlood(player);
            player.sendMessage(ItemFactory.colorize("&aBlood transfused. &7Volume restored."));
            player.sendActionBar(ItemFactory.colorize("&aBlood restored"));
            player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_EMPTY, 1f, 0.85f);
            return true;
        }
        return false;
    }

    /** @return true if an item was consumed / effect applied */
    private boolean tryHealOther(Player healer, Player target, ItemStack hand) {
        ItemFactory items = plugin.items();
        if (items.isSplint(hand)) {
            if (!hasBrokenBones(target)) {
                healer.sendActionBar(ItemFactory.colorize("&7No broken bones."));
                healer.sendMessage(Component.text(target.getName() + " doesn't have broken bones.", NamedTextColor.GRAY));
                return false;
            }
            consumeOne(healer, hand);
            healBones(target);
            healer.sendMessage(ItemFactory.colorize("&aYou set &f" + target.getName() + "&a's bones."));
            target.sendMessage(ItemFactory.colorize("&a" + healer.getName() + " &7set your broken bones."));
            healer.playSound(healer.getLocation(), Sound.ITEM_BONE_MEAL_USE, 1f, 0.8f);
            target.playSound(target.getLocation(), Sound.ITEM_BONE_MEAL_USE, 1f, 0.8f);
            return true;
        }
        if (items.isBandage(hand)) {
            BleedSeverity sev = bleedSeverity(target);
            if (sev == BleedSeverity.NONE) {
                healer.sendActionBar(ItemFactory.colorize("&7Not bleeding."));
                healer.sendMessage(Component.text(target.getName() + " isn't bleeding.", NamedTextColor.GRAY));
                return false;
            }
            if (sev == BleedSeverity.FAST) {
                healer.sendActionBar(ItemFactory.colorize("&cNeed a &6Tourniquet"));
                healer.sendMessage(ItemFactory.colorize("&cNeed a &6Tourniquet &cfor this bleed!"));
                return false;
            }
            consumeOne(healer, hand);
            healBleed(target);
            healer.sendMessage(ItemFactory.colorize("&aYou bandaged &f" + target.getName() + "&a."));
            target.sendMessage(ItemFactory.colorize("&a" + healer.getName() + " &7bandaged your wounds."));
            healer.playSound(healer.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1.2f);
            target.playSound(target.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1.2f);
            return true;
        }
        if (items.isTourniquet(hand)) {
            if (!isBleeding(target)) {
                healer.sendActionBar(ItemFactory.colorize("&7Not bleeding."));
                healer.sendMessage(Component.text(target.getName() + " isn't bleeding.", NamedTextColor.GRAY));
                return false;
            }
            consumeOne(healer, hand);
            healBleed(target);
            healer.sendMessage(ItemFactory.colorize("&aYou applied a tourniquet to &f" + target.getName() + "&a."));
            target.sendMessage(ItemFactory.colorize("&a" + healer.getName() + " &7applied a tourniquet."));
            healer.playSound(healer.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 0.7f);
            target.playSound(target.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 0.7f);
            return true;
        }
        if (items.isBloodBag(hand)) {
            if (ensureBlood(target) >= MAX_BLOOD_L - 0.01) {
                healer.sendActionBar(ItemFactory.colorize("&7Blood already full."));
                healer.sendMessage(Component.text(target.getName() + " already has full blood volume.", NamedTextColor.GRAY));
                return false;
            }
            consumeOne(healer, hand);
            restoreBlood(target);
            healer.sendMessage(ItemFactory.colorize("&aYou transfused &f" + target.getName() + "&a."));
            target.sendMessage(ItemFactory.colorize("&a" + healer.getName() + " &7gave you a blood transfusion."));
            healer.playSound(healer.getLocation(), Sound.ITEM_BOTTLE_EMPTY, 1f, 0.85f);
            target.playSound(target.getLocation(), Sound.ITEM_BOTTLE_EMPTY, 1f, 0.85f);
            return true;
        }
        return false;
    }

    private void healBones(Player player) {
        brokenBones.remove(player.getUniqueId());
        removeBonesSlow(player);
    }

    private void healBleed(Player player) {
        bleeding.remove(player.getUniqueId());
    }

    /** Instant full blood restore; does not stop bleeding. */
    private void restoreBlood(Player player) {
        bloodLiters.put(player.getUniqueId(), MAX_BLOOD_L);
        applyBloodState(player, MAX_BLOOD_L, false);
    }

    private static void applyBonesSlow(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr != null && attr.getModifier(BONES_SLOW_KEY) == null) {
            try {
                attr.addTransientModifier(new AttributeModifier(
                        BONES_SLOW_KEY,
                        BONES_SPEED_SCALAR,
                        AttributeModifier.Operation.ADD_SCALAR
                ));
            } catch (Throwable ignored) {
            }
        }
        // Potion fallback — CardForge sometimes drops transient attributes.
        PotionEffect current = effectOf(player, PotionEffectType.SLOWNESS);
        if (current == null || current.getAmplifier() < 1 || current.getDuration() < 40) {
            if (current == null || current.getAmplifier() <= 1) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1, false, false, true));
            }
        }
    }

    private static void removeBonesSlow(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr != null) {
            try {
                attr.removeModifier(BONES_SLOW_KEY);
            } catch (Throwable ignored) {
            }
        }
        PotionEffect slow = effectOf(player, PotionEffectType.SLOWNESS);
        if (slow != null && slow.getAmplifier() == 1 && slow.getDuration() <= 100) {
            player.removePotionEffect(PotionEffectType.SLOWNESS);
        }
    }

    private static void consumeOne(Player player, ItemStack hand) {
        ItemStack live = player.getInventory().getItemInMainHand();
        if (live == null || live.getType().isAir()) {
            live = hand;
        }
        int amt = live.getAmount();
        if (amt <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            live.setAmount(amt - 1);
            player.getInventory().setItemInMainHand(live);
        }
    }

    /**
     * Prefer a nearby player under the crosshair. Avoids CraftWorld.rayTraceEntities —
     * CardForge used to NPE on a null NMS entity selector inside getNearbyEntities.
     */
    private static Player rayPlayer(Player from, double range) {
        if (from == null || range <= 0) {
            return null;
        }
        org.bukkit.Location eye = from.getEyeLocation();
        org.bukkit.util.Vector dir = eye.getDirection();
        if (dir.lengthSquared() < 1.0e-6) {
            return null;
        }
        dir.normalize();
        org.bukkit.util.Vector start = eye.toVector();
        Player best = null;
        double bestDist = range;
        double rangeSq = (range + 2.0) * (range + 2.0);
        for (Player p : from.getWorld().getPlayers()) {
            if (p == null || p.equals(from) || !p.isValid() || p.isDead()) {
                continue;
            }
            if (p.getLocation().distanceSquared(eye) > rangeSq) {
                continue;
            }
            org.bukkit.util.BoundingBox box = p.getBoundingBox().expand(0.3);
            RayTraceResult hit = box.rayTrace(start, dir, range);
            if (hit == null || hit.getHitPosition() == null) {
                continue;
            }
            double d = start.distance(hit.getHitPosition());
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        if (best == null) {
            return null;
        }
        RayTraceResult blockHit = from.getWorld().rayTraceBlocks(
                eye,
                dir,
                bestDist,
                FluidCollisionMode.NEVER,
                true
        );
        if (blockHit != null && blockHit.getHitBlock() != null && !blockHit.getHitBlock().getType().isAir()) {
            return null;
        }
        return best;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ensureBlood(player);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            rematerializeMedical(player);
            if (hasBrokenBones(player)) {
                applyBonesSlow(player);
            }
            applyBloodState(player, ensureBlood(player), false);
        });
    }

    /** Rewrite medical stacks to canonical items and merge matching ids. */
    private void rematerializeMedical(Player player) {
        ItemFactory items = plugin.items();
        if (items == null || player == null) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        Map<String, Integer> totals = new LinkedHashMap<>();
        Map<String, List<Integer>> slots = new LinkedHashMap<>();
        boolean dirty = false;
        for (int i = 0; i < contents.length; i++) {
            if (i >= 36 && i <= 39) {
                continue;
            }
            ItemStack stack = contents[i];
            String id = items.medicalId(stack);
            if (id == null) {
                continue;
            }
            totals.merge(id, Math.max(1, stack.getAmount()), Integer::sum);
            slots.computeIfAbsent(id, k -> new ArrayList<>()).add(i);
            contents[i] = null;
            dirty = true;
        }
        if (!dirty) {
            return;
        }
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            String id = entry.getKey();
            int left = entry.getValue();
            List<Integer> dest = slots.get(id);
            int di = 0;
            while (left > 0) {
                int n = Math.min(ItemFactory.MEDICAL_STACK_MAX, left);
                ItemStack fresh = items.createMedical(id, n);
                if (fresh == null) {
                    break;
                }
                int slot;
                if (di < dest.size()) {
                    slot = dest.get(di++);
                } else {
                    slot = firstEmptyContentSlot(contents);
                    if (slot < 0) {
                        player.getWorld().dropItemNaturally(player.getLocation(), fresh);
                        left -= n;
                        continue;
                    }
                }
                contents[slot] = fresh;
                left -= n;
            }
        }
        inv.setContents(contents);
    }

    private static int firstEmptyContentSlot(ItemStack[] contents) {
        for (int i = 0; i < contents.length; i++) {
            if (i >= 36 && i <= 39) {
                continue;
            }
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir()) {
                return i;
            }
        }
        return -1;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        lastHealMs.remove(player.getUniqueId());
        bloodVisionProtected.remove(player.getUniqueId());
        sendBloodFx(player, 0f, false);
        // Keep bleed / bones / blood volume so a reconnect does not magically heal.
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        clearAll(event.getPlayer());
    }
}
