package io.github.evanhrosenthal.blockablock;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

final class PaletteStore {
    private record Entry(String name, Palette palette) {}

    private final Path file;
    private Map<UUID, Entry> players = Map.of();
    private boolean enabled = true;

    PaletteStore(Path file) {
        this.file = file;
    }

    void load() throws IOException, InvalidConfigurationException {
        if (Files.notExists(file)) {
            save(enabled, players);
            return;
        }

        var yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        var loaded = new TreeMap<UUID, Entry>();
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if ((yaml.contains("players") && section == null) || (yaml.contains("enabled") && !yaml.isBoolean("enabled"))) {
            throw new InvalidConfigurationException("Expected a players section and a boolean enabled setting");
        }
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) {
                    throw new InvalidConfigurationException("Expected player settings for " + key);
                }
                UUID id;
                try {
                    id = UUID.fromString(key);
                } catch (IllegalArgumentException ex) {
                    throw new InvalidConfigurationException("Invalid player UUID " + key, ex);
                }
                boolean blacklistAll = entry.getBoolean("blacklist-all");
                Set<Material> grants = readBlocks(entry, "grants");
                Set<Material> blacklist = readBlocks(entry, "blacklist");
                grants.removeAll(blacklist);
                var palette = new Palette(grants, blacklistAll ? Set.of() : blacklist, blacklistAll);
                if (!palette.equals(Palette.EMPTY)) {
                    loaded.put(id, new Entry(entry.getString("name"), palette));
                }
            }
        }
        players = loaded;
        enabled = yaml.getBoolean("enabled", true);
    }

    private static Set<Material> readBlocks(ConfigurationSection section, String path)
            throws InvalidConfigurationException {
        var materials = new HashSet<Material>();
        if (section.contains(path) && !section.isList(path)) {
            throw new InvalidConfigurationException("Expected a block list at " + section.getCurrentPath() + "." + path);
        }
        for (Object value : section.getList(path, List.of())) {
            Material material = value instanceof String name ? Palette.parseBlock(name) : null;
            if (material == null) {
                throw new InvalidConfigurationException("Unknown block " + value + " at " + section.getCurrentPath());
            }
            materials.add(material);
        }
        return materials;
    }

    boolean enabled() {
        return enabled;
    }

    Palette palette(UUID id) {
        Entry entry = players.get(id);
        return entry == null ? Palette.EMPTY : entry.palette();
    }

    UUID findByName(String name) {
        for (var entry : players.entrySet()) {
            if (name.equalsIgnoreCase(entry.getValue().name())) {
                return entry.getKey();
            }
        }
        return null;
    }

    String name(UUID id) {
        Entry entry = players.get(id);
        return entry == null ? null : entry.name();
    }

    void update(Collection<OfflinePlayer> targets, UnaryOperator<Palette> change) throws IOException {
        var updated = new TreeMap<>(players);
        for (OfflinePlayer player : targets) {
            UUID id = player.getUniqueId();
            Palette palette = change.apply(palette(id));
            if (palette.equals(Palette.EMPTY)) {
                updated.remove(id);
            } else {
                String name = player.getName();
                updated.put(id, new Entry(name == null ? name(id) : name, palette));
            }
        }
        if (!updated.equals(players)) {
            save(enabled, updated);
        }
    }

    void toggle() throws IOException {
        save(!enabled, players);
    }

    void reset() throws IOException {
        save(enabled, Map.of());
    }

    private void save(boolean nextEnabled, Map<UUID, Entry> updated) throws IOException {
        var yaml = new YamlConfiguration();
        yaml.set("enabled", nextEnabled);
        ConfigurationSection section = yaml.createSection("players");
        for (var player : updated.entrySet()) {
            Entry entry = player.getValue();
            Palette palette = entry.palette();
            ConfigurationSection row = section.createSection(player.getKey().toString());
            row.set("name", entry.name());
            row.set("grants", blockKeys(palette.grants()));
            row.set("blacklist", blockKeys(palette.blacklist()));
            row.set("blacklist-all", palette.blacklistAll());
        }

        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "config-", ".yml.tmp");
        try {
            Files.writeString(temporary, yaml.saveToString());
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        players = updated;
        enabled = nextEnabled;
    }

    static List<String> blockKeys(Collection<Material> materials) {
        return materials.stream().map(material -> material.getKey().toString()).sorted().toList();
    }
}
