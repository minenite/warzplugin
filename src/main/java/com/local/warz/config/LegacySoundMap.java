package com.local.warz.config;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class LegacySoundMap {
    private static final Map<String, Sound> MAP = new HashMap<>();

    static {
        map("ghast fireball", Sound.ENTITY_GHAST_SHOOT);
        map("explode", Sound.ENTITY_GENERIC_EXPLODE);
        map("skeleton hurt", Sound.ENTITY_SKELETON_HURT);
        map("zombie wood", Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR);
        map("zombie metal", Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR);
        map("irongolem hit", Sound.ENTITY_IRON_GOLEM_HURT);
        map("wither shoot", Sound.ENTITY_WITHER_SHOOT);
        map("note snare drum", Sound.BLOCK_NOTE_BLOCK_SNARE);
        map("item break", Sound.ENTITY_ITEM_BREAK);
        map("fizz", Sound.BLOCK_FIRE_EXTINGUISH);
        map("fuse", Sound.ENTITY_TNT_PRIMED);
        map("shoot arrow", Sound.ENTITY_ARROW_SHOOT);
        map("door open", Sound.BLOCK_WOODEN_DOOR_OPEN);
        map("door close", Sound.BLOCK_WOODEN_DOOR_CLOSE);
        map("note sticks", Sound.BLOCK_NOTE_BLOCK_HAT);
        map("piston extend", Sound.BLOCK_PISTON_EXTEND);
        map("piston retract", Sound.BLOCK_PISTON_CONTRACT);
        map("fire ignite", Sound.ITEM_FLINTANDSTEEL_USE);
        map("glass", Sound.BLOCK_GLASS_BREAK);
        map("splash", Sound.ENTITY_GENERIC_SPLASH);
    }

    private LegacySoundMap() {
    }

    private static void map(String legacy, Sound sound) {
        MAP.put(normalize(legacy), sound);
        if (sound != null && sound.getKey() != null) {
            MAP.put(normalize(sound.getKey().getKey().replace('.', ' ')), sound);
            MAP.put(normalize(sound.getKey().getKey().replace('.', '_')), sound);
        }
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT)
                .replace('-', ' ')
                .replace('.', ' ')
                .replace('_', ' ')
                .replace("  ", " ");
    }

    public static Sound resolve(String legacy) {
        if (legacy == null || legacy.isBlank()) {
            return null;
        }
        String raw = legacy.trim();
        Sound mapped = MAP.get(normalize(raw));
        if (mapped != null) {
            return mapped;
        }
        Sound fromRegistry = fromRegistry(raw);
        if (fromRegistry != null) {
            return fromRegistry;
        }
        String enumName = raw.toUpperCase(Locale.ROOT)
                .replace(' ', '_')
                .replace('.', '_')
                .replace('-', '_');
        try {
            return Sound.valueOf(enumName);
        } catch (IllegalArgumentException ignored) {
            try {
                return Sound.valueOf("ENTITY_" + enumName);
            } catch (Exception ignoredAgain) {
                return Sound.ENTITY_GENERIC_EXPLODE;
            }
        } catch (Exception ignored) {
            return Sound.ENTITY_GENERIC_EXPLODE;
        }
    }

    private static Sound fromRegistry(String raw) {
        String key = raw.toLowerCase(Locale.ROOT).trim();
        if (key.startsWith("minecraft:")) {
            key = key.substring("minecraft:".length());
        }
        key = key.replace(' ', '_').replace('-', '_');
        // "entity.zombie.attack wooden door" → entity.zombie.attack_wooden_door
        if (key.contains(".") && key.contains("_")) {
            // already mixed; fine
        } else if (key.contains(" ")) {
            key = key.replace(' ', '_');
        }
        try {
            NamespacedKey ns = NamespacedKey.minecraft(key.replace('_', '.'));
            Sound sound = Registry.SOUNDS.get(ns);
            if (sound != null) {
                return sound;
            }
        } catch (Throwable ignored) {
        }
        try {
            NamespacedKey ns = NamespacedKey.minecraft(key);
            return Registry.SOUNDS.get(ns);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
