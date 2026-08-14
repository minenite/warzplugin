package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.combat.ImpactEffects;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-channel explosion shock: pressure / flash / sound / fragment / knockback
 * each get their own cover + distance attenuation and recovery curve.
 */
public final class BlastShockService implements Listener {
    /** Frag reference acoustic envelope (blocks). */
    private static final double REF_SHOCK_RADIUS = 20.0;
    private static final ThreadLocal<Boolean> WARZ_HANDLED = new ThreadLocal<>();

    private final WarzPlugin plugin;
    private final Map<UUID, Long> lastBlastMs = new ConcurrentHashMap<>();
    private final Map<String, Long> recentOrigins = new ConcurrentHashMap<>();

    public BlastShockService(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (Boolean.TRUE.equals(WARZ_HANDLED.get())) {
            return;
        }
        if (event.getEntity() instanceof Player) {
            return;
        }
        Location at = event.getLocation();
        if (at == null || at.getWorld() == null) {
            return;
        }
        // Vanilla / environmental: frag-like envelope, slightly stronger if huge crater
        double strength = event.blockList().size() >= 40 ? 1.25 : 1.0;
        apply(at, REF_SHOCK_RADIUS, strength);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (Boolean.TRUE.equals(WARZ_HANDLED.get())) {
            return;
        }
        Location at = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        apply(at, REF_SHOCK_RADIUS, 1.0);
    }

    /** Legacy scale helper — maps old fragScale into radius/strength. */
    public void apply(Location at, double fragScale) {
        double s = fragScale <= 0 ? 1.0 : fragScale;
        apply(at, REF_SHOCK_RADIUS * Math.min(1.35, Math.max(0.85, s)), s);
    }

    /**
     * @param shockRadius outer acoustic / tiny-shake envelope (typically ~20 for frag)
     * @param shockStrength 1.0 = frag; LAW ~1.3–1.55
     */
    public void apply(Location at, double shockRadius, double shockStrength) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String originKey = at.getWorld().getUID() + ":"
                + at.getBlockX() + ":" + at.getBlockY() + ":" + at.getBlockZ()
                + ":" + (now / 150L);
        if (recentOrigins.putIfAbsent(originKey, now) != null) {
            return;
        }
        if (recentOrigins.size() > 64) {
            recentOrigins.entrySet().removeIf(e -> now - e.getValue() > 500L);
        }

        WARZ_HANDLED.set(Boolean.TRUE);
        try {
            applyPlayers(at, Math.max(8.0, shockRadius), Math.max(0.35, Math.min(2.5, shockStrength)), now);
        } finally {
            Bukkit.getScheduler().runTask(plugin, () -> WARZ_HANDLED.remove());
        }
    }

    private void applyPlayers(Location at, double shockRadius, double strength, long now) {
        World world = at.getWorld();
        Location origin = at.clone().add(0, 0.35, 0);
        // Normalize distances to frag-space so a 22-block LAW envelope still uses the same curve shape
        double distScale = shockRadius / REF_SHOCK_RADIUS;

        for (Player player : world.getPlayers()) {
            if (!player.isOnline() || player.isDead()) {
                continue;
            }
            if (player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            // Drone operators are at altitude / remote-viewing — never concussion, shake, or tinnitus.
            if (plugin.bigDrone() != null && plugin.bigDrone().isPiloting(player)) {
                continue;
            }
            Location eyes = player.getEyeLocation();
            if (eyes.getWorld() == null || !eyes.getWorld().equals(world)) {
                continue;
            }
            double dist = Math.min(eyes.distance(origin), player.getLocation().add(0, 1.0, 0).distance(origin));
            if (dist > shockRadius + 0.5) {
                continue;
            }
            Long last = lastBlastMs.get(player.getUniqueId());
            if (last != null && now - last < 90L) {
                continue;
            }

            // Frag-normalized distance for curves
            double d = dist / distScale;
            Cover cover = wallCover(origin, eyes);

            Channels ch = Channels.compute(d, cover, strength);
            // LAW / Javelin: impact surface must not erase concussion for people on the blast side
            if (strength >= 1.6 && d <= 12.0 && cover.sound >= 0.25f) {
                float floor = (float) ((1.0 - Math.min(1.0, d / 12.0)) * Math.min(1.0, (strength - 1.0) * 0.85));
                ch = ch.withPressureFloor(Math.max(0.42f, floor * 0.95f));
            }
            if (ch.isSilent()) {
                continue;
            }

            lastBlastMs.put(player.getUniqueId(), now);
            applyToPlayer(player, origin, ch, strength);
        }
    }

    private void applyToPlayer(Player player, Location origin, Channels ch, double strength) {
        // --- 0 ms: flash impulse + boom + camera packet + potions (before crater can kill) ---
        long protectMs = (long) (5_000 + Math.max(ch.pressure, strength >= 1.6 ? 0.55f : 0f) * 14_000);
        ImpactEffects.protectVision(player, protectMs);

        Vector away = player.getLocation().toVector().subtract(origin.toVector());
        if (away.lengthSquared() < 1.0e-4) {
            away = player.getLocation().getDirection().multiply(-1);
        }
        away.setY(0);
        if (away.lengthSquared() < 1.0e-4) {
            away = new Vector(1, 0, 0);
        }
        final Vector blastDir = away.normalize().clone();

        LaserCompanionBridge bridge = plugin.laserBridge();
        if (bridge != null) {
            // Longer envelopes — close blasts ring/muffle for many seconds
            int shakeTicks = ticksForRecovery(ch.pressure, 8, 48);
            int flashTicks = Math.max(2, Math.round(3 + ch.flash * 6)); // still brief vs flashbang
            int tinnitusTicks = ticksForRecovery(ch.tinnitus, 40, 360);   // ~2–18s
            int muffleTicks = ticksForRecovery(Math.max(ch.pressure, ch.sound * 0.65f), 30, 280);
            bridge.sendBlast(player,
                    ch.pressure, ch.flash, ch.tinnitus, ch.muffle, ch.knockback,
                    (float) blastDir.getX(), (float) blastDir.getZ(),
                    shakeTicks, flashTicks, tinnitusTicks, muffleTicks);
            // No sendWhiteout — flashbangs own that identity
        }

        // Acoustic: always play something if sound channel is alive
        float boomVol = 0.12f + 0.75f * ch.sound;
        float boomPitch = 0.55f + 0.35f * (1f - ch.pressure);
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE,
                SoundCategory.PLAYERS, boomVol, boomPitch);
        if (ch.muffle > 0.35f) {
            player.playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH,
                    SoundCategory.PLAYERS, 0.12f * ch.muffle, 0.4f);
        }
        if (ch.pressure > 0.55f) {
            player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE,
                    SoundCategory.MASTER, 0.18f * ch.pressure, 1.45f);
        }

        // Potions immediately — delayed apply was skipped when the crater killed on the same tick,
        // and ADS Slowness (amp 4) would block weaker blast Slowness without force.
        applyDebuffs(player, ch, strength);
        if (ch.tinnitus > 0.08f) {
            startTinnitus(player, ch);
        }

        // --- next tick: knockback + knockdown ---
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || player.isDead()) {
                return;
            }
            if (ch.knockback >= 0.06f) {
                Vector push = blastDir.clone().multiply(0.35 + ch.knockback * 1.55)
                        .setY(Math.min(0.62, 0.10 + ch.knockback * 0.42));
                player.setVelocity(player.getVelocity().add(push));
            }
            if (ch.pressure > 0.82f && ch.knockback > 0.55f && plugin.prone() != null
                    && player.isOnGround()) {
                plugin.prone().enterProne(player);
            }
            if (ch.pressure > 0.35f) {
                forceEffect(player, PotionEffectType.MINING_FATIGUE,
                        Math.max(30, (int) (30 + 50 * ch.pressure)),
                        ch.pressure > 0.7f ? 1 : 0);
            }
        }, 1L);
    }

    private void startTinnitus(Player player, Channels ch) {
        int tinnitusTicks = ticksForRecovery(ch.tinnitus, 40, 360);
        AtomicInteger elapsed = new AtomicInteger(0);
        float baseVol = 0.06f + 0.38f * ch.tinnitus;
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int t = elapsed.addAndGet(2);
            if (t > tinnitusTicks || !player.isOnline() || player.isDead()) {
                task.cancel();
                return;
            }
            float u = t / (float) tinnitusTicks;
            // 100 → 90 → 60 → 20 → 0
            float curve = recoveryTinnitus(u);
            float vol = baseVol * curve;
            float pitch = 1.7f + ThreadLocalRandom.current().nextFloat() * 0.3f;
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, vol, pitch);
            if (u < 0.35f && t % 6 == 0) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME,
                        SoundCategory.MASTER, vol * 0.5f, 2.0f);
            }
        }, 0L, 2L);
    }

    private void applyDebuffs(Player player, Channels ch, double strength) {
        float p = ch.pressure;
        float frag = ch.fragment;
        // Rockets: treat strength as extra concussion so wall-adjacent hits still stun
        if (strength >= 1.6) {
            p = Math.min(1f, Math.max(p, p * 0.55f + 0.40f * (float) Math.min(1.0, strength / 2.2)));
        }
        if (p < 0.10f && frag < 0.12f) {
            return;
        }
        // Long enough that medical short-refresh clear (>60 ticks) won't strip them next tick
        int base = Math.max(100, (int) Math.round((5.0 + p * 14.0) * 20));
        if (strength >= 1.6) {
            base = Math.max(base, (int) Math.round(base * (0.9 + 0.25 * (strength - 1.5))));
        }
        // Darkness sooner on rockets (was 0.75 — rarely reached after cover)
        float darkAt = strength >= 1.6 ? 0.38f : 0.70f;
        float heavyAt = strength >= 1.6 ? 0.55f : 0.75f;
        float midAt = strength >= 1.6 ? 0.28f : 0.45f;
        float lightAt = strength >= 1.6 ? 0.16f : 0.22f;

        if (p >= heavyAt) {
            forceEffect(player, PotionEffectType.DARKNESS, base, 0);
            forceEffect(player, PotionEffectType.SLOWNESS, base, 2);
            forceEffect(player, PotionEffectType.WEAKNESS, base, 2);
            forceEffect(player, PotionEffectType.NAUSEA, (int) (base * 0.55), 0);
        } else if (p >= darkAt) {
            forceEffect(player, PotionEffectType.DARKNESS, (int) (base * 0.85), 0);
            forceEffect(player, PotionEffectType.SLOWNESS, (int) (base * 0.9), 1);
            forceEffect(player, PotionEffectType.WEAKNESS, (int) (base * 0.85), 1);
            forceEffect(player, PotionEffectType.NAUSEA, (int) (base * 0.55), 0);
        } else if (p >= midAt) {
            forceEffect(player, PotionEffectType.SLOWNESS, (int) (base * 0.9), 1);
            forceEffect(player, PotionEffectType.WEAKNESS, (int) (base * 0.9), 1);
            forceEffect(player, PotionEffectType.NAUSEA, (int) (base * 0.65), 0);
            if (strength >= 1.6) {
                forceEffect(player, PotionEffectType.DARKNESS, (int) (base * 0.55), 0);
            }
        } else if (p >= lightAt) {
            forceEffect(player, PotionEffectType.SLOWNESS, (int) (base * 0.65), 0);
            forceEffect(player, PotionEffectType.NAUSEA, (int) (base * 0.5), 0);
        } else if (p >= 0.10f) {
            forceEffect(player, PotionEffectType.NAUSEA, Math.max(60, (int) (base * 0.35)), 0);
        }
    }

    private static void forceEffect(Player player, PotionEffectType type, int ticks, int amplifier) {
        if (player == null || type == null || ticks <= 0) {
            return;
        }
        player.addPotionEffect(new PotionEffect(type, ticks, Math.max(0, amplifier), false, true, true), true);
    }

    private static int ticksForRecovery(float amount, int min, int max) {
        return Math.max(min, Math.min(max, Math.round(min + amount * (max - min))));
    }

    /** Tinnitus recovery: stays high longer, then eases out. */
    static float recoveryTinnitus(float u) {
        u = clamp01(u);
        if (u < 0.25f) {
            return 1f;
        }
        if (u < 0.45f) {
            return lerp(1f, 0.9f, (u - 0.25f) / 0.2f);
        }
        if (u < 0.7f) {
            return lerp(0.9f, 0.55f, (u - 0.45f) / 0.25f);
        }
        if (u < 0.9f) {
            return lerp(0.55f, 0.2f, (u - 0.7f) / 0.2f);
        }
        return lerp(0.2f, 0f, (u - 0.9f) / 0.1f);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * clamp01(t);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /**
     * Continuous distance curves in frag-space blocks (d), then cover per-channel.
     * One curve family — no second steep falloff multiply.
     */
    private record Channels(float pressure, float flash, float sound, float fragment,
                            float knockback, float tinnitus, float muffle) {
        boolean isSilent() {
            return sound < 0.04f && pressure < 0.04f && flash < 0.03f && knockback < 0.03f;
        }

        Channels withPressureFloor(float floor) {
            float p = Math.max(pressure, clamp01(floor));
            float t = Math.max(tinnitus, clamp01(p * 0.85f));
            float m = Math.max(muffle, clamp01(p * 0.75f));
            float kb = Math.max(knockback, clamp01(p * 0.45f));
            return new Channels(p, flash, Math.max(sound, p * 0.55f), fragment, kb, t, m);
        }

        static Channels compute(double d, Cover cover, double strength) {
            float s = (float) strength;
            // Distance responses (frag-normalized blocks)
            float pressureD = distCurve(d, 2.0, 4.0, 7.0, 12.0, 20.0, 1.00f, 0.78f, 0.42f, 0.14f, 0.03f);
            float flashD = distCurve(d, 2.0, 4.0, 7.0, 10.0, 14.0, 0.95f, 0.45f, 0.12f, 0.02f, 0.0f);
            float soundD = distCurve(d, 2.0, 7.0, 12.0, 20.0, 28.0, 1.00f, 0.72f, 0.45f, 0.18f, 0.06f);
            float fragD = distCurve(d, 2.0, 4.0, 7.0, 11.0, 16.0, 1.00f, 0.70f, 0.28f, 0.08f, 0.0f);
            float kbD = distCurve(d, 2.0, 4.0, 7.0, 10.0, 14.0, 1.00f, 0.65f, 0.22f, 0.05f, 0.0f);

            float pressure = clamp01(pressureD * cover.pressure * s);
            float flash = clamp01(flashD * cover.flash * s);
            float sound = clamp01(soundD * cover.sound * Math.min(1.35f, s));
            float fragment = clamp01(fragD * cover.fragment * s);
            float knockback = clamp01(kbD * cover.knockback * s);
            float tinnitus = clamp01(pressure * 0.85f + fragment * 0.25f);
            float muffle = clamp01(Math.max(pressure * 0.9f, (1f - cover.sound) * 0.55f + pressure * 0.35f));

            // Past ~20 frag-blocks: sound only
            if (d > 20.0) {
                return new Channels(0, 0, sound * 0.7f, 0, 0, 0, muffle * 0.3f);
            }
            return new Channels(pressure, flash, sound, fragment, knockback, tinnitus, muffle);
        }

        /**
         * Piecewise linear curve through (d0..d4) → (v0..v4). Beyond d4 → 0.
         */
        private static float distCurve(double d,
                                      double d0, double d1, double d2, double d3, double d4,
                                      float v0, float v1, float v2, float v3, float v4) {
            if (d <= 0) {
                return v0;
            }
            if (d <= d0) {
                return v0; // flat extreme zone
            }
            if (d <= d1) {
                return lerp(v0, v1, (float) ((d - d0) / (d1 - d0)));
            }
            if (d <= d2) {
                return lerp(v1, v2, (float) ((d - d1) / (d2 - d1)));
            }
            if (d <= d3) {
                return lerp(v2, v3, (float) ((d - d2) / (d3 - d2)));
            }
            if (d <= d4) {
                return lerp(v3, v4, (float) ((d - d3) / (d4 - d3)));
            }
            // Soft tail for sound-capable callers; pressure curves use v4≈0
            double t = Math.min(1.0, (d - d4) / 8.0);
            return v4 * (1f - (float) t);
        }
    }

    private record Cover(float flash, float knockback, float pressure, float sound, float fragment) {
        static final Cover CLEAR = new Cover(1f, 1f, 1f, 1f, 1f);
    }

    /** Per-channel cover from raycast; multiplies through each solid hit. */
    static Cover wallCover(Location from, Location to) {
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return new Cover(0, 0, 0, 0, 0);
        }
        Vector delta = to.toVector().subtract(from.toVector());
        double dist = delta.length();
        if (dist < 0.05) {
            return Cover.CLEAR;
        }
        Vector dir = delta.clone().multiply(1.0 / dist);
        Cover c = Cover.CLEAR;
        Location cursor = from.clone();
        Block originBlock = from.getBlock();
        double traveled = 0.0;
        for (int step = 0; step < 10; step++) {
            double remain = dist - traveled;
            if (remain < 0.08) {
                break;
            }
            RayTraceResult hit = cursor.getWorld().rayTraceBlocks(cursor, dir, remain, FluidCollisionMode.NEVER, true);
            if (hit == null || hit.getHitBlock() == null || hit.getHitPosition() == null) {
                break;
            }
            Block block = hit.getHitBlock();
            double hitDist = hit.getHitPosition().distance(from.toVector());
            boolean insideBlastCell = block.getX() == originBlock.getX()
                    && block.getY() == originBlock.getY()
                    && block.getZ() == originBlock.getZ();
            // Rocket / LAW detonates ON a surface — don't treat that wall as cover for same-side victims
            double hitDistFromOrigin = hit.getHitPosition().distance(from.toVector());
            boolean blastSurface = hitDistFromOrigin <= 1.65;
            if (insideBlastCell || blastSurface) {
                cursor = hit.getHitPosition().toLocation(from.getWorld()).add(dir.clone().multiply(0.35));
                traveled = cursor.distance(from);
                continue;
            }
            Cover layer = classify(block);
            c = new Cover(
                    c.flash * layer.flash,
                    c.knockback * layer.knockback,
                    c.pressure * layer.pressure,
                    c.sound * layer.sound,
                    c.fragment * layer.fragment
            );
            cursor = hit.getHitPosition().toLocation(from.getWorld()).add(dir.clone().multiply(0.35));
            traveled = Math.max(hitDist + 0.35, traveled + 0.35);
            if (traveled >= dist - 0.05) {
                break;
            }
        }
        return c;
    }

    private static Cover classify(Block block) {
        if (block == null || block.isEmpty() || block.isPassable()) {
            return Cover.CLEAR;
        }
        String name = block.getType().name();
        if (name.contains("IRON") || name.contains("COPPER") || name.contains("GOLD")
                || name.contains("NETHERITE") || name.contains("ANVIL") || name.contains("OBSIDIAN")
                || name.contains("DEEPSLATE") || name.contains("ANCIENT_DEBRIS")
                || name.contains("BARRIER") || name.equals("BEDROCK")) {
            // Iron wall: hear/feel boom, almost no flash/KB/concussion
            return new Cover(0.02f, 0.06f, 0.14f, 0.48f, 0.02f);
        }
        if (name.contains("GLASS") || name.contains("LEAVES") || name.contains("ICE")
                || name.contains("SLAB") || name.contains("STAIRS") || name.contains("FENCE")) {
            return new Cover(0.55f, 0.60f, 0.70f, 0.88f, 0.50f);
        }
        if (name.contains("WOOD") || name.contains("PLANKS") || name.contains("LOG")
                || name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR") || name.contains("WOOL")
                || name.contains("DIRT") || name.contains("SAND") || name.contains("GRAVEL")) {
            return new Cover(0.22f, 0.28f, 0.38f, 0.72f, 0.18f);
        }
        if (block.getType().isOccluding() || block.getType().isSolid()) {
            return new Cover(0.05f, 0.10f, 0.18f, 0.55f, 0.05f);
        }
        return new Cover(0.55f, 0.60f, 0.70f, 0.85f, 0.50f);
    }
}
