package com.local.warz.config;

import net.minenite.warzplugin.WarzPlugin;
import com.local.warz.model.RoundDefinition;

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

public final class RoundRegistry {
    private final WarzPlugin plugin;
    private final Map<String, RoundDefinition> byId = new HashMap<>();
    private final Map<String, List<RoundDefinition>> byCaliber = new HashMap<>();

    public RoundRegistry(WarzPlugin plugin) {
        this.plugin = plugin;
    }

    public void seedDefaultsIfNeeded() {
        Path dir = roundsDir();
        try {
            Files.createDirectories(dir);
            if (hasAnyFile(dir)) {
                return;
            }
            extractDefaultsFromJar("defaults/rounds", dir);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed seeding rounds: " + e.getMessage());
        }
    }

    private boolean hasAnyFile(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.anyMatch(Files::isRegularFile);
        }
    }

    private void extractDefaultsFromJar(String resourceFolder, Path destination) throws IOException {
        Path codeSource;
        try {
            codeSource = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            plugin.getLogger().warning("Could not resolve plugin jar for round defaults: " + e.getMessage());
            copyFromProjectSource(resourceFolder, destination);
            return;
        }
        if (!Files.isRegularFile(codeSource)) {
            copyFromProjectSource(resourceFolder, destination);
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
            plugin.getLogger().warning("Could not extract round defaults: " + e.getMessage());
            copyFromProjectSource(resourceFolder, destination);
        }
    }

    private void copyFromProjectSource(String resourceFolder, Path destination) throws IOException {
        Path projectDefaults = Path.of("src/main/resources").resolve(resourceFolder);
        if (!Files.isDirectory(projectDefaults)) {
            projectDefaults = plugin.getDataFolder().toPath().getParent().getParent()
                    .resolve("barrett-plugin/src/main/resources").resolve(resourceFolder);
        }
        if (!Files.isDirectory(projectDefaults)) {
            return;
        }
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

    public void reload() {
        byId.clear();
        byCaliber.clear();
        seedDefaultsIfNeeded();
        Path dir = roundsDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).sorted(Comparator.comparing(p -> p.getFileName().toString())).forEach(files::add);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed listing rounds: " + e.getMessage());
            return;
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.startsWith("_") || name.toLowerCase(Locale.ROOT).startsWith("example")) {
                continue;
            }
            try {
                RoundDefinition def = RoundReader.read(file);
                byId.put(def.fileName().toLowerCase(Locale.ROOT), def);
                byCaliber.computeIfAbsent(def.caliber(), k -> new ArrayList<>()).add(def);
                plugin.getLogger().info("LOADED ROUND: " + def.displayName() + " (" + def.fileName()
                        + " / " + def.caliber() + ")");
            } catch (Exception e) {
                plugin.getLogger().warning("FAILED TO LOAD ROUND " + name + ": " + e.getMessage());
            }
        }
        plugin.getLogger().info("Loaded " + byId.size() + " ammo rounds.");
    }

    public Path roundsDir() {
        return plugin.getDataFolder().toPath().resolve("rounds");
    }

    public Optional<RoundDefinition> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id.toLowerCase(Locale.ROOT)));
    }

    public Collection<RoundDefinition> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public List<RoundDefinition> byCaliber(String caliber) {
        List<RoundDefinition> list = byCaliber.getOrDefault(AmmoCaliber.normalize(caliber), List.of());
        return List.copyOf(list);
    }

    public List<String> ids() {
        return byId.keySet().stream().sorted().toList();
    }
}
