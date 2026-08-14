package com.local.warz.config;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.model.GunDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class GunRegistry {
    private final WarzPlugin plugin;
    private final Map<String, GunDefinition> byFile = new HashMap<>();
    private final Map<Integer, String> modelIds = new HashMap<>();

    public GunRegistry(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void seedDefaultsIfNeeded() {
        if (!plugin.getConfig().getBoolean("seed-defaults", true)) {
            return;
        }
        Path gunsDir = plugin.getDataFolder().toPath().resolve("guns");
        Path projectileDir = plugin.getDataFolder().toPath().resolve("projectile");
        try {
            Files.createDirectories(gunsDir);
            Files.createDirectories(projectileDir);
            copyDefaults("defaults/guns", gunsDir);
            copyDefaults("defaults/projectile", projectileDir);
            seedExampleTemplate();
        } catch (IOException e) {
            plugin.getLogger().severe("Failed seeding gun configs: " + e.getMessage());
        }
    }

    private void seedExampleTemplate() throws IOException {
        // Always refresh official EXAMPLE templates so updates ship with the jar.
        copyResource("defaults/EXAMPLE-GUN-FULL.txt",
                plugin.getDataFolder().toPath().resolve("EXAMPLE-GUN-FULL.txt"));
        copyResource("defaults/guns/EXAMPLE-GUN-FULL",
                plugin.getDataFolder().toPath().resolve("guns").resolve("EXAMPLE-GUN-FULL"));
    }

    private void copyResource(String resourcePath, Path out) throws IOException {
        Files.createDirectories(out.getParent());
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                plugin.getLogger().warning("Missing jar resource " + resourcePath);
                return;
            }
            Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void copyDefaults(String resourceFolder, Path destination) throws IOException {
        Files.createDirectories(destination);
        extractDefaultsFromJar(resourceFolder, destination);
    }

    private void extractDefaultsFromJar(String resourceFolder, Path destination) throws IOException {
        Path codeSource;
        try {
            codeSource = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            plugin.getLogger().warning("Could not resolve plugin jar for defaults: " + e.getMessage());
            return;
        }
        if (!Files.isRegularFile(codeSource)) {
            Path projectDefaults = Path.of("src/main/resources").resolve(resourceFolder);
            if (Files.isDirectory(projectDefaults)) {
                try (Stream<Path> stream = Files.list(projectDefaults)) {
                    stream.filter(Files::isRegularFile).forEach(path -> {
                        try {
                            Path out = destination.resolve(path.getFileName().toString());
                            if (!Files.exists(out)) {
                                Files.copy(path, out);
                            }
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
            return;
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(codeSource.toFile())) {
            var entries = zip.entries();
            String prefix = resourceFolder.endsWith("/") ? resourceFolder : resourceFolder + "/";
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix)) {
                    continue;
                }
                String fileName = name.substring(prefix.length());
                if (fileName.isEmpty() || fileName.contains("/")) {
                    continue;
                }
                Path out = destination.resolve(fileName);
                if (Files.exists(out)) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry); OutputStream outStream = Files.newOutputStream(out)) {
                    in.transferTo(outStream);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not extract defaults for " + resourceFolder + ": " + e.getMessage());
        }
    }

    public void reload() {
        byFile.clear();
        modelIds.clear();
        seedDefaultsIfNeeded();
        loadFolder(plugin.getDataFolder().toPath().resolve("guns"), false);
        loadFolder(plugin.getDataFolder().toPath().resolve("projectile"), true);
        writeModelIndex();
        plugin.getLogger().info("Loaded " + byFile.size() + " modular guns/projectiles.");
    }

    private void loadFolder(Path folder, boolean throwable) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(folder)) {
            stream.filter(Files::isRegularFile).sorted(Comparator.comparing(p -> p.getFileName().toString())).forEach(files::add);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed listing " + folder + ": " + e.getMessage());
            return;
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (isTemplateFile(name)) {
                plugin.getLogger().info("Skipping template file: " + name);
                continue;
            }
            try {
                int model = ResourcePackCmd.forGun(name);
                GunDefinition def = WeaponReader.read(file, throwable, model);
                byFile.put(def.fileName().toLowerCase(Locale.ROOT), def);
                modelIds.put(def.customModelData(), def.fileName());
                plugin.getLogger().info("LOADED " + (throwable ? "PROJECTILE" : "GUN") + ": " + def.displayName()
                        + " (cmd=" + def.customModelData()
                        + ResourcePackCmd.packModel(def.customModelData()).map(m -> " → " + m).orElse("") + ")");
            } catch (Exception e) {
                plugin.getLogger().warning("FAILED TO LOAD " + file.getFileName() + ": " + e.getMessage());
            }
        }
    }

    private static boolean isTemplateFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("_")
                || lower.startsWith("example")
                || lower.endsWith(".example")
                || lower.endsWith(".txt.example");
    }

    private void writeModelIndex() {
        Path out = plugin.getDataFolder().toPath().resolve("custom-model-data.txt");
        List<String> lines = new ArrayList<>();
        lines.add("# CustomModelData ↔ gun id ↔ resource-pack model (bone item)");
        lines.add("# Base material: " + plugin.getConfig().getString("gun-base-material", "BONE"));
        lines.add("# Pack: enable WarZ-guns in Options → Resource Packs");
        byFile.values().stream()
                .sorted(Comparator.comparing(GunDefinition::fileName))
                .forEach(def -> {
                    String model = ResourcePackCmd.packModel(def.customModelData()).orElse("?");
                    lines.add(def.customModelData() + "=" + def.fileName() + " → " + model);
                });
        try {
            Files.write(out, lines);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write custom-model-data.txt: " + e.getMessage());
        }
    }

    public Optional<GunDefinition> get(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String key = name.toLowerCase(Locale.ROOT);
        GunDefinition exact = byFile.get(key);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (GunDefinition def : byFile.values()) {
            if (def.fileName().equalsIgnoreCase(name) || stripColor(def.displayName()).equalsIgnoreCase(name)) {
                return Optional.of(def);
            }
        }
        return Optional.empty();
    }

    public Optional<GunDefinition> byModelData(int modelData) {
        String file = modelIds.get(modelData);
        return file == null ? Optional.empty() : Optional.ofNullable(byFile.get(file.toLowerCase(Locale.ROOT)));
    }

    public Collection<GunDefinition> all() {
        return Collections.unmodifiableCollection(byFile.values());
    }

    private static String stripColor(String input) {
        return input.replaceAll("(?i)&[0-9a-fk-or]", "").trim();
    }
}
