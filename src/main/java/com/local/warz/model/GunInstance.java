package com.local.warz.model;

public final class GunInstance {
    private final GunDefinition definition;
    private int roundsFired;
    private int gunReloadTimer;
    private int timer;
    private int lastFired;
    private int heldDownTicks;
    private int bulletsShot;
    private boolean firing;
    private boolean reloading;
    private boolean changed;
    /** True when reloading a single loose round by hand (no mag). */
    private boolean handloading;
    /** Ticks this reload was started with (for progress UI). */
    private int reloadDuration;

    public GunInstance(GunDefinition definition) {
        this.definition = definition;
    }

    public GunDefinition definition() {
        return definition;
    }

    public int roundsFired() {
        return roundsFired;
    }

    public void setRoundsFired(int roundsFired) {
        this.roundsFired = roundsFired;
    }

    public int gunReloadTimer() {
        return gunReloadTimer;
    }

    public void setGunReloadTimer(int gunReloadTimer) {
        this.gunReloadTimer = gunReloadTimer;
    }

    public int timer() {
        return timer;
    }

    public void setTimer(int timer) {
        this.timer = timer;
    }

    public int lastFired() {
        return lastFired;
    }

    public void setLastFired(int lastFired) {
        this.lastFired = lastFired;
    }

    public int heldDownTicks() {
        return heldDownTicks;
    }

    public void setHeldDownTicks(int heldDownTicks) {
        this.heldDownTicks = heldDownTicks;
    }

    public int bulletsShot() {
        return bulletsShot;
    }

    public void setBulletsShot(int bulletsShot) {
        this.bulletsShot = bulletsShot;
    }

    public boolean firing() {
        return firing;
    }

    public void setFiring(boolean firing) {
        this.firing = firing;
    }

    public boolean reloading() {
        return reloading;
    }

    public void setReloading(boolean reloading) {
        this.reloading = reloading;
    }

    public boolean changed() {
        return changed;
    }

    public void setChanged(boolean changed) {
        this.changed = changed;
    }

    public boolean handloading() {
        return handloading;
    }

    public void setHandloading(boolean handloading) {
        this.handloading = handloading;
    }

    public int reloadDuration() {
        return reloadDuration;
    }

    public void setReloadDuration(int reloadDuration) {
        this.reloadDuration = Math.max(0, reloadDuration);
    }

    public void finishReloading() {
        bulletsShot = 0;
        roundsFired = 0;
        changed = false;
        gunReloadTimer = 0;
        reloading = false;
        handloading = false;
        reloadDuration = 0;
    }

    public void finishShooting() {
        bulletsShot = 0;
        heldDownTicks = 0;
        firing = false;
        timer = definition.bulletDelayTime();
    }

    public int clipRemaining(int totalShotsAvailable) {
        if (!definition.hasClip()) {
            return totalShotsAvailable;
        }
        int max = definition.maxClipSize();
        int leftInClip = Math.min(max, Math.max(0, totalShotsAvailable - Math.max(0, totalShotsAvailable - max + roundsFired)));
        // Prefer explicit roundsFired accounting like legacy:
        int ammoLeft = totalShotsAvailable - max + roundsFired;
        if (ammoLeft < 0) {
            ammoLeft = 0;
        }
        leftInClip = totalShotsAvailable - ammoLeft;
        return Math.max(0, leftInClip);
    }

    public int reserveRemaining(int totalShotsAvailable) {
        if (!definition.hasClip()) {
            return 0;
        }
        int max = definition.maxClipSize();
        int ammoLeft = totalShotsAvailable - max + roundsFired;
        return Math.max(0, ammoLeft);
    }
}
