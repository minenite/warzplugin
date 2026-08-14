package com.local.warz.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Weapon-light / laser device modes (AN/PEQ-15 and simpler laser+flashlight kits).
 * Cycle order for a full PEQ: OFF → IR → GREEN → FLASH → STROBE → OFF.
 */
public enum PeqMode {
    OFF("off", "OFF"),
    IR("ir", "IR Laser"),
    GREEN("green", "Green Laser"),
    FLASH("flash", "Flashlight"),
    STROBE("strobe", "Strobe");

    private final String id;
    private final String label;

    PeqMode(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public boolean laserActive() {
        return this == IR || this == GREEN;
    }

    public boolean infrared() {
        return this == IR;
    }

    public boolean whiteLight() {
        return this == FLASH || this == STROBE;
    }

    public boolean strobe() {
        return this == STROBE;
    }

    public static PeqMode fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return OFF;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (PeqMode m : values()) {
            if (m.id.equals(key) || m.name().equalsIgnoreCase(key)) {
                return m;
            }
        }
        return OFF;
    }

    /** Full AN/PEQ-15 cycle. */
    public static List<PeqMode> peqCycle() {
        return List.of(OFF, IR, GREEN, FLASH, STROBE);
    }

    /**
     * Modes available from discrete laser / flashlight attachments (no PEQ).
     * Visible lasers map to {@link #GREEN} (beam color still comes from the fitted module).
     */
    public static List<PeqMode> kitCycle(boolean hasLaser, boolean laserIr, boolean hasFlash) {
        List<PeqMode> modes = new ArrayList<>();
        modes.add(OFF);
        if (hasLaser) {
            modes.add(laserIr ? IR : GREEN);
        }
        if (hasFlash) {
            modes.add(FLASH);
            modes.add(STROBE);
        }
        return modes;
    }

    public PeqMode nextIn(List<PeqMode> cycle) {
        if (cycle == null || cycle.isEmpty()) {
            return OFF;
        }
        int idx = cycle.indexOf(this);
        if (idx < 0) {
            return cycle.get(0);
        }
        return cycle.get((idx + 1) % cycle.size());
    }
}
