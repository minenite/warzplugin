package com.local.warz.runtime.anomaly;

import java.util.Locale;

/** Paranormal contacts visible only under Red NV. */
public enum AnomalyType {
    SHADOW(0),
    ECHO(1),
    WATCHER(2),
    CRAWLER(3),
    STATIC_MAN(4),
    RESIDUAL(5),
    MIMIC(6),
    STALKER(7),
    VOID(8),
    BURN_IN(9),
    DISTORTION(10),
    FALSE_CONTACT(11),
    GLITCH(12),
    OBSERVER(13),
    IMPOSTER(14);

    public final int id;

    AnomalyType(int id) {
        this.id = id;
    }

    public static AnomalyType byId(int id) {
        for (AnomalyType t : values()) {
            if (t.id == id) {
                return t;
            }
        }
        return null;
    }

    public static AnomalyType byName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            return AnomalyType.valueOf(key);
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        for (AnomalyType t : values()) {
            if (t.name().replace("_", "").equalsIgnoreCase(key.replace("_", ""))) {
                return t;
            }
        }
        return null;
    }
}
