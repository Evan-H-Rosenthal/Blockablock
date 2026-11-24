package io.github.evanhrosenthal.blockablock;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private record Entry(String name, Palette palette, Map<Material, Long> deliveries, List<String> hooks) {
        private static final Entry EMPTY = new Entry(null, Palette.EMPTY, Map.of(), List.of());

        Entry {
            palette = palette.equals(Palette.EMPTY) ? Palette.EMPTY : palette;
            deliveries = Map.copyOf(deliveries);
            hooks = List.copyOf(hooks);
        }
    }

    private final Path file;
    private YamlConfiguration yaml;
    private PaletteSettings settings;
    private Map<UUID, Entry> players = Map.of();
    private boolean enabled = true;

    PaletteStore(Path file) {
        this.file = file;
    }

    void load() throws IOException, InvalidConfigurationException {
        var defaults = new YamlConfiguration();
        try (var reader = new InputStreamReader(
                Objects.requireNonNull(PaletteStore.class.getResourceAsStream("/config.yml")), StandardCharsets.UTF_8)) {
            defaults.load(reader);
        }
        yaml = new YamlConfiguration();
        boolean addDefaults = Files.notExists(file);
        if (!addDefaults) {
            yaml.load(file.toFile());
        }
        for (String key : defaults.getKeys(false)) {
            if (!yaml.contains(key) && !key.equals("players")) {
                yaml.set(key, defaults.get(key));
                yaml.setComments(key, defaults.getComments(key));
                addDefaults = true;
            }
        }
        settings = PaletteSettings.read(yaml);
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
                writeEntry(loaded, id, new Entry(entry.getString("name"), palette,
                        readDeliveries(entry, grants), entry.getStringList("pending-hooks")));
            }
        }
        players = loaded;
        enabled = yaml.getBoolean("enabled", true);
        if (addDefaults) {
            save(enabled, players);
        }
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

    private static Map<Material, Long> readDeliveries(ConfigurationSection entry, Set<Material> grants)
            throws InvalidConfigurationException {
        var deliveries = new EnumMap<Material, Long>(Material.class);
        ConfigurationSection pending = entry.getConfigurationSection("pending-deliveries");
        if (entry.contains("pending-deliveries") && pending == null) {
            throw new InvalidConfigurationException("Expected a pending-deliveries section at " + entry.getCurrentPath());
        }
        if (pending != null) {
            for (String key : pending.getKeys(false)) {
                Material block = Palette.parseBlock(key);
                long amount = pending.getLong(key);
                if (block == null || amount <= 0) {
                    throw new InvalidConfigurationException("Invalid pending delivery for " + key);
                }
                if (grants.contains(block)) {
                    deliveries.put(block, amount);
                }
            }
        }
        return deliveries;
    }

    PaletteSettings settings() {
        return settings;
    }

    boolean enabled() {
        return enabled;
    }

    Palette palette(UUID id) {
        return players.getOrDefault(id, Entry.EMPTY).palette();
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
        return players.getOrDefault(id, Entry.EMPTY).name();
    }

    void update(Collection<OfflinePlayer> targets, UnaryOperator<Palette> change, Material finiteGrant, List<String> hooks)
            throws IOException {
        var updated = new TreeMap<>(players);
        for (OfflinePlayer player : targets) {
            UUID id = player.getUniqueId();
            Entry previous = updated.getOrDefault(id, Entry.EMPTY);
            Palette palette = change.apply(previous.palette());
            var deliveries = new EnumMap<Material, Long>(Material.class);
            deliveries.putAll(previous.deliveries());
            deliveries.keySet().retainAll(palette.grants());
            if (finiteGrant != null) {
                deliveries.merge(finiteGrant, (long) settings.blocksPerGrant(), Math::addExact);
            }
            var pendingHooks = new ArrayList<>(previous.hooks());
            pendingHooks.addAll(hooks);
            String name = player.getName();
            writeEntry(updated, id, new Entry(name == null ? previous.name() : name, palette, deliveries, pendingHooks));
        }
        if (!updated.equals(players)) {
            save(enabled, updated);
        }
    }

    Map<Material, Long> deliveries(UUID id) {
        return players.getOrDefault(id, Entry.EMPTY).deliveries();
    }

    void completeDeliveries(UUID id, Map<Material, Long> delivered) throws IOException {
        Entry entry = players.getOrDefault(id, Entry.EMPTY);
        var remaining = new EnumMap<Material, Long>(Material.class);
        remaining.putAll(entry.deliveries());
        delivered.forEach((block, amount) -> remaining.computeIfPresent(block,
                (key, pending) -> amount >= pending ? null : pending - amount));
        var updated = new TreeMap<>(players);
        writeEntry(updated, id, new Entry(entry.name(), entry.palette(), remaining, entry.hooks()));
        save(enabled, updated);
    }

    List<String> takeHooks(UUID id) throws IOException {
        Entry entry = players.getOrDefault(id, Entry.EMPTY);
        if (!entry.hooks().isEmpty()) {
            var updated = new TreeMap<>(players);
            writeEntry(updated, id, new Entry(entry.name(), entry.palette(), entry.deliveries(), List.of()));
            save(enabled, updated);
        }
        return entry.hooks();
    }

    boolean hasHooks(UUID id) {
        return !players.getOrDefault(id, Entry.EMPTY).hooks().isEmpty();
    }

    private static void writeEntry(Map<UUID, Entry> updated, UUID id, Entry entry) {
        if (entry.palette() == Palette.EMPTY && entry.deliveries().isEmpty() && entry.hooks().isEmpty()) {
            updated.remove(id);
        } else {
            updated.put(id, entry);
        }
    }

    void toggle() throws IOException {
        save(!enabled, players);
    }

    void reset() throws IOException {
        save(enabled, Map.of());
    }

    private void save(boolean nextEnabled, Map<UUID, Entry> updated) throws IOException {
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
            if (!entry.deliveries().isEmpty()) {
                ConfigurationSection pending = row.createSection("pending-deliveries");
                entry.deliveries().forEach((block, count) -> pending.set(block.getKey().getKey(), count));
            }
            if (!entry.hooks().isEmpty()) {
                row.set("pending-hooks", entry.hooks());
            }
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
