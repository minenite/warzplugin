package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.projectile.Bullet;
import com.local.warz.util.LaserBeams;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

public final class GunListener implements Listener {
    private final WarzPlugin plugin;

    public GunListener(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.sessions().join(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.sessions().quit(event.getPlayer());
        if (plugin.gunPoses() != null) {
            plugin.gunPoses().clear(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        // Off-hand only; null hand still counts (some CardForge left-clicks omit the slot).
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK
                && action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        GunPlayerSession session = plugin.sessions().get(player);
        var hand = player.getInventory().getItemInMainHand();
        if (plugin.items() != null && (plugin.items().isSplint(hand) || plugin.items().isBandage(hand)
                || plugin.items().isTourniquet(hand) || plugin.items().isBloodBag(hand))) {
            return;
        }
        if (session.heldGun(hand).isEmpty()) {
            return;
        }
        // Optic / laser / PEQ toggle is Z only (companion peq_req) — not sneak+RMB.
        boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        event.setCancelled(true);
        if (right) {
            // UseItem / UseItemOn also swing the arm — do not treat that as ADS.
            session.suppressAimSwing(250L);
        }
        String click = right ? "right" : "left";
        session.onClick(click, null);
    }

    /**
     * RMB on a mob/player is {@link PlayerInteractEntityEvent}, not air/block interact.
     * Without this, crosshair-on-zombie never shoots — and the arm swing scopes instead.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteractEntity(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        GunPlayerSession session = plugin.sessions().get(player);
        var hand = player.getInventory().getItemInMainHand();
        if (plugin.items() != null && (plugin.items().isSplint(hand) || plugin.items().isBandage(hand)
                || plugin.items().isTourniquet(hand) || plugin.items().isBloodBag(hand))) {
            return;
        }
        if (session.heldGun(hand).isEmpty()) {
            return;
        }
        // A corpse is looted with the same click that fires a gun, and in this game
        // you are nearly always holding one - so the gun swallowed the click and
        // the body would not open. Looting wins when the crosshair is on a body.
        if (event.getRightClicked().getPersistentDataContainer()
                .has(com.local.warz.WarzKeys.of("corpse_id"),
                        org.bukkit.persistence.PersistentDataType.STRING)) {
            return;
        }
        event.setCancelled(true);
        session.suppressAimSwing(250L);
        session.onClick("right", null);
    }

    /**
     * Survival left-click air often never becomes {@link PlayerInteractEvent}. Arm swing
     * is the reliable ADS trigger (same as LAW bay cycle).
     * Skipped after RMB, and when the crosshair is on a living entity (entity-use swings
     * must not scope — CardForge used to turn those into LEFT_CLICK_AIR).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player player = event.getPlayer();
        GunPlayerSession session = plugin.sessions().get(player);
        if (session.shouldIgnoreSwingAim()) {
            return;
        }
        var hand = player.getInventory().getItemInMainHand();
        if (session.heldGun(hand).isEmpty()) {
            return;
        }
        if (lookingAtLiving(player, 4.5)) {
            // Entity RMB swing — shoot is handled by InteractEntity; do not ADS.
            session.suppressAimSwing(100L);
            return;
        }
        session.onClick("left", null);
    }

    /** True if a living entity sits under the crosshair within reach. */
    private static boolean lookingAtLiving(Player player, double range) {
        org.bukkit.Location eye = player.getEyeLocation();
        org.bukkit.util.Vector dir = eye.getDirection();
        if (dir.lengthSquared() < 1.0e-6) {
            return false;
        }
        dir.normalize();
        org.bukkit.util.Vector start = eye.toVector();
        double rangeSq = (range + 1.0) * (range + 1.0);
        for (Entity entity : player.getNearbyEntities(range, range, range)) {
            if (!(entity instanceof LivingEntity living) || living.equals(player) || !living.isValid()) {
                continue;
            }
            if (living.getLocation().distanceSquared(eye) > rangeSq) {
                continue;
            }
            org.bukkit.util.BoundingBox box = living.getBoundingBox().expand(0.2);
            if (box.rayTrace(start, dir, range) != null) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        GunPlayerSession session = plugin.sessions().get(player);
        if (session.heldGun(player.getInventory().getItemInMainHand()).isEmpty()) {
            return;
        }
        event.setCancelled(true);
        if (event.getProjectile() instanceof Projectile projectile) {
            session.onClick("bow", projectile);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent event) {
        GunPlayerSession session = plugin.sessions().get(event.getPlayer());
        // tryDropReload always clears ADS / blocks aim for a few ticks (Q ≠ scope)
        if (session.tryDropReload(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /** Sneak + F on a sniper cycles 100/200/300 yd zero. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        GunPlayerSession session = plugin.sessions().get(event.getPlayer());
        if (session.tryCycleZero(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * LAW / Javelin wither skulls prime a vanilla explosion — cancel it so
     * {@link com.local.warz.combat.ImpactEffects} owns crater + blast shock (like M79).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (event.getEntity() == null) {
            return;
        }
        Bullet bullet = plugin.bullets().get(event.getEntity());
        if (bullet != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onProjectileHit(ProjectileHitEvent event) {
        Bullet bullet = plugin.bullets().get(event.getEntity());
        if (bullet == null) {
            return;
        }
        if (event.getHitEntity() instanceof LivingEntity living) {
            event.setCancelled(true);
            bullet.hitEntity(living, event.getEntity().getLocation());
            return;
        }
        Block hitBlock = event.getHitBlock();
        // Throwables smash glass first (before fuse-roll bounce) and keep their path.
        if (hitBlock != null && bullet.gun() != null && bullet.gun().throwable()) {
            if (plugin.glass() != null && plugin.glass().isTacticalGlass(hitBlock)) {
                plugin.glass().smashThroughForThrowable(bullet, hitBlock, event.getHitBlockFace());
                event.setCancelled(true);
                bullet.reassertVelocity();
                plugin.getServer().getScheduler().runTask(plugin, bullet::reassertVelocity);
                return;
            }
            if (GlassService.breakVanillaGlass(hitBlock)) {
                event.setCancelled(true);
                nudgeThrowablePast(bullet, hitBlock, event.getHitBlockFace());
                return;
            }
        }
        // Fuse rollers first — snowballs die on collide even when cancelled.
        if (tryFuseRoll(bullet, event)) {
            return;
        }
        // Plugin projectiles punch through leaves / plants / crops
        if (hitBlock != null && LaserBeams.isFoliage(hitBlock)) {
            event.setCancelled(true);
            Entity proj = event.getEntity();
            Vector keep = bullet.velocity();
            if (keep != null && keep.lengthSquared() > 1.0E-8) {
                Vector dir = keep.clone().normalize();
                proj.teleport(proj.getLocation().add(dir.multiply(0.4)));
                proj.setVelocity(keep.clone());
            }
            return;
        }
        // Tactical glass: shatter / crack / penetrate by caliber & round type
        if (hitBlock != null && plugin.glass() != null) {
            // Same-cell re-hit right after a pierce: keep flying, don't stop the round
            if (bullet.ignoresPierce(GlassService.key(hitBlock))) {
                event.setCancelled(true);
                Vector keep = bullet.velocity();
                if (keep != null && keep.lengthSquared() > 1.0E-8) {
                    Vector dir = keep.clone().normalize();
                    event.getEntity().teleport(event.getEntity().getLocation().add(dir.multiply(0.55)));
                    bullet.reassertVelocity();
                }
                return;
            }
            var result = plugin.glass().handleHit(bullet, hitBlock, event.getHitBlockFace());
            if (result == GlassService.HitResult.PENETRATE) {
                event.setCancelled(true);
                bullet.reassertVelocity();
                // Snowballs still die on block collision even when this event is cancelled —
                // damage anyone past the pane with an explicit ray (covers same-spot follow-ups too).
                if (!bullet.continueAfterPierce(hitBlock)) {
                    plugin.getServer().getScheduler().runTask(plugin, bullet::reassertVelocity);
                }
                return;
            }
            if (result == GlassService.HitResult.STOP) {
                if (tryFuseRoll(bullet, event)) {
                    return;
                }
                bullet.onHit();
                if (bullet.destroyWhenHit()) {
                    bullet.setNextTickDestroy();
                    event.getEntity().remove();
                }
                return;
            }
        }
        // Soft / hard / metal cover (wood doors vs concrete; metal ricochet)
        if (hitBlock != null) {
            if (bullet.ignoresPierce(GlassService.key(hitBlock))) {
                event.setCancelled(true);
                bullet.reassertVelocity();
                return;
            }
            // Timed / rolling rounds bounce instead of treating cover as a hard stop
            if (tryFuseRoll(bullet, event)) {
                return;
            }
            CoverService.HitResult cover = CoverService.handleHit(bullet, hitBlock, event.getHitBlockFace());
            if (cover == CoverService.HitResult.PENETRATE || cover == CoverService.HitResult.RICOCHET) {
                event.setCancelled(true);
                bullet.reassertVelocity();
                if (cover == CoverService.HitResult.PENETRATE) {
                    // Snowballs die on block collide even when cancelled — damage through-cover now.
                    boolean hit = bullet.continueAfterPierce(hitBlock);
                    if (!hit) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            bullet.reassertVelocity();
                            bullet.continueAfterPierce(hitBlock);
                        });
                    }
                } else {
                    // Ricochet: follow the reflected ray for victims; keep projectile if alive
                    boolean hit = bullet.continueAfterRicochet(hitBlock);
                    if (!hit) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            bullet.reassertVelocity();
                            bullet.continueAfterRicochet(hitBlock);
                        });
                    }
                }
                return;
            }
            if (cover == CoverService.HitResult.STOP) {
                if (tryFuseRoll(bullet, event)) {
                    return;
                }
                bullet.onHit();
                if (bullet.destroyWhenHit()) {
                    bullet.setNextTickDestroy();
                    event.getEntity().remove();
                }
                return;
            }
        }
        if (tryFuseRoll(bullet, event)) {
            return;
        }
        bullet.onHit();
        if (bullet.destroyWhenHit()) {
            bullet.setNextTickDestroy();
            event.getEntity().remove();
        }
    }

    private void nudgeThrowablePast(Bullet bullet, Block block, BlockFace face) {
        Entity proj = bullet.getProjectile();
        if (proj == null || !proj.isValid() || block == null) {
            return;
        }
        Vector dir = bullet.velocity();
        if (dir == null || dir.lengthSquared() < 1.0e-8) {
            dir = face != null ? face.getDirection().multiply(-1) : new Vector(0, 0, 1);
        } else {
            dir = dir.clone().normalize();
        }
        org.bukkit.Location to = block.getLocation().add(0.5, 0.5, 0.5).add(dir.multiply(1.1));
        proj.teleport(to);
        Vector keep = bullet.velocity();
        if (keep != null) {
            proj.setVelocity(keep.clone());
        }
        bullet.reassertVelocity();
        plugin.getServer().getScheduler().runTask(plugin, bullet::reassertVelocity);
    }

    /** Fuse-only rounds (M79 etc.): bounce/roll, keep cooking until {@code timeUntilRelease}. */
    private boolean tryFuseRoll(Bullet bullet, ProjectileHitEvent event) {
        if (bullet.gun().explodeOnImpact()) {
            return false;
        }
        BlockFace face = event.getHitBlockFace();
        if (face == null && event.getHitEntity() != null) {
            face = BlockFace.UP;
        }
        if (!bullet.tryRollImpact(face)) {
            return false;
        }
        event.setCancelled(true);
        plugin.getServer().getScheduler().runTask(plugin, bullet::reassertVelocity);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageByEntityEvent event) {
        // Melee while holding a gun: vanilla punch steals the left-click (no InteractEvent),
        // so ADS never toggles — cancel the punch and force scope-in instead.
        if (event.getDamager() instanceof Player player
                && event.getEntity() instanceof LivingEntity) {
            // Gun shots call LivingEntity#damage(amount, shooter) — do not treat that as melee.
            if (Bullet.isApplyingPluginDamage()) {
                return;
            }
            GunPlayerSession session = plugin.sessions().get(player);
            var hand = player.getInventory().getItemInMainHand();
            var held = session.heldGun(hand);
            if (held.isPresent()) {
                event.setCancelled(true);
                if (!held.get().throwable()) {
                    session.ensureAimedIn();
                }
                return;
            }
        }

        if (!(event.getEntity() instanceof LivingEntity hurt)) {
            return;
        }
        if (!(event.getDamager() instanceof Projectile projectile)) {
            return;
        }
        Bullet bullet = plugin.bullets().get(projectile);
        if (bullet == null) {
            return;
        }
        if (bullet.alreadyDamaged(hurt.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        // Cancel vanilla (snowball = 0) and apply plugin damage once
        event.setCancelled(true);
        bullet.hitEntity(hurt, projectile.getLocation());
    }
}
