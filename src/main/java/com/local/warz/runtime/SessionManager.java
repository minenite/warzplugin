package com.local.warz.runtime;

import net.minenite.warzplugin.WarzPlugin;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SessionManager {
    private final WarzPlugin plugin;
    private final Map<UUID, GunPlayerSession> sessions = new HashMap<>();

    public SessionManager(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public GunPlayerSession get(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), id -> new GunPlayerSession(plugin, player));
    }

    public void join(Player player) {
        get(player);
    }

    public void quit(Player player) {
        GunPlayerSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            session.unload();
        }
        if (plugin.companions() != null) {
            plugin.companions().forget(player.getUniqueId());
        }
    }

    public void clear() {
        sessions.values().forEach(GunPlayerSession::unload);
        sessions.clear();
    }

    public void rebuildAll() {
        for (GunPlayerSession session : sessions.values()) {
            session.rebuildGuns();
        }
    }

    public Collection<GunPlayerSession> all() {
        return sessions.values();
    }

    public void tick() {
        for (GunPlayerSession session : sessions.values()) {
            session.tick();
        }
    }
}
