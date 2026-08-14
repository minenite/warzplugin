package com.local.warz;

import org.bukkit.NamespacedKey;

/**
 * Stable item / entity PDC namespace.
 * Kept as {@code pvpgunminus} so existing gear and world data keep working after the WarZ rename.
 * Plugin messaging channels also stay on {@code pvpgunminus:*} for the Fabric companion.
 */
public final class WarzKeys {
    public static final String NAMESPACE = "pvpgunminus";

    private WarzKeys() {
    }

    public static NamespacedKey of(String key) {
        return new NamespacedKey(NAMESPACE, key);
    }
}
