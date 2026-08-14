package com.local.warz.config;

import com.local.warz.model.RoundDraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RoundWriter {
    private RoundWriter() {
    }

    public static void write(Path file, RoundDraft draft) throws IOException {
        draft.sanitizeFileName();
        List<String> lines = new ArrayList<>();
        lines.add("displayName=" + nullToEmpty(draft.displayName));
        lines.add("material=" + draft.material.name());
        lines.add("customModelData=" + draft.customModelData);
        lines.add("caliber=" + AmmoCaliber.normalize(draft.caliber));
        lines.add("damageMult=" + trimDouble(draft.damageMult));
        lines.add("armorPenAdd=" + draft.armorPenAdd);
        lines.add("accuracyMult=" + trimDouble(draft.accuracyMult));
        lines.add("speedMult=" + trimDouble(draft.speedMult));
        lines.add("knockbackMult=" + trimDouble(draft.knockbackMult));
        lines.add("rangeMult=" + trimDouble(draft.rangeMult));
        lines.add("tracer=" + draft.tracer);
        lines.add("tracerColor=" + (draft.tracerColor == null || draft.tracerColor.isBlank() ? "#FFC850" : draft.tracerColor.trim()));
        lines.add("tracerWidth=" + trimDouble(draft.tracerWidth));
        lines.add("muzzleFlash=" + draft.muzzleFlash);
        lines.add("muzzleColor=" + (draft.muzzleColor == null || draft.muzzleColor.isBlank() ? "#FFBE5A" : draft.muzzleColor.trim()));
        lines.add("muzzleScale=" + trimDouble(draft.muzzleScale));
        lines.add("explodeRadiusAdd=" + trimDouble(draft.explodeRadiusAdd));
        lines.add("fireRadiusAdd=" + trimDouble(draft.fireRadiusAdd));
        lines.add("setFireTicks=" + draft.setFireTicks);
        lines.add("subsonic=" + draft.subsonic);
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String trimDouble(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        if (s.indexOf('.') >= 0) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s.isEmpty() ? "0" : s;
    }
}
