package com.local.warz.projectile;

import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.List;

public final class BulletManager {
    private final List<Bullet> bullets = new ArrayList<>();

    public void add(Bullet bullet) {
        bullets.add(bullet);
    }

    public void remove(Bullet bullet) {
        bullets.remove(bullet);
    }

    public Bullet get(Entity entity) {
        if (entity == null) {
            return null;
        }
        for (Bullet bullet : bullets) {
            if (bullet.getProjectile() != null && bullet.getProjectile().getEntityId() == entity.getEntityId()) {
                return bullet;
            }
        }
        return null;
    }

    public void tick() {
        if (bullets.isEmpty()) {
            return;
        }
        // Iterate a snapshot: a bullet's tick() can hit something and call
        // Bullet.remove(), which mutates this list (plugin.bullets().remove(this)).
        // Iterating the live list directly caused a ConcurrentModificationException.
        List<Bullet> snapshot = new ArrayList<>(bullets);
        for (Bullet bullet : snapshot) {
            if (!bullet.isDead()) {
                bullet.tick();
            }
        }
        // Ensure cleanup (projectile despawn + remnant) even if tick only marked dead.
        for (Bullet bullet : new ArrayList<>(bullets)) {
            if (bullet.isDead()) {
                bullet.remove();
            }
        }
        bullets.removeIf(Bullet::isDead);
    }

    public void clear() {
        for (Bullet bullet : new ArrayList<>(bullets)) {
            bullet.remove();
        }
        bullets.clear();
    }
}
