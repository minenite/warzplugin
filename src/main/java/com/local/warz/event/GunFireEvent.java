package com.local.warz.event;

import com.local.warz.model.GunDefinition;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

public final class GunFireEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final GunDefinition gun;
    private boolean cancelled;
    private int ammoNeeded;
    private double accuracy;

    public GunFireEvent(Player player, GunDefinition gun, int ammoNeeded, double accuracy) {
        super(player);
        this.gun = gun;
        this.ammoNeeded = ammoNeeded;
        this.accuracy = accuracy;
    }

    public GunDefinition getGun() {
        return gun;
    }

    public int getAmmoNeeded() {
        return ammoNeeded;
    }

    public void setAmmoNeeded(int ammoNeeded) {
        this.ammoNeeded = ammoNeeded;
    }

    public double getAccuracy() {
        return accuracy;
    }

    public void setAccuracy(double accuracy) {
        this.accuracy = accuracy;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
