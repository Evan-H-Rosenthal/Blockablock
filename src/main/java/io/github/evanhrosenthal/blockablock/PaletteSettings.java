package io.github.evanhrosenthal.blockablock;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

record PaletteSettings(int blocksPerGrant, int blocksForReplenish, boolean infiniteReplenish,
                       List<String> onGrant, List<String> onRevoke, List<String> onBlacklist,
                       String prefix, String suffix, TextColor color) {
    static PaletteSettings read(ConfigurationSection config) {
        int amount = config.getInt("blocks-per-grant", 2);
        int threshold = config.getInt("blocks-for-replenish", 2);
        String hex = config.getString("name-color", "#FFFFFF").strip();
        TextColor color = hex.matches("#[0-9a-fA-F]{6}") ? TextColor.fromHexString(hex) : TextColor.color(0xffffff);
        return new PaletteSettings(amount > 0 ? amount : 2, threshold >= 0 ? threshold : 2,
                config.getBoolean("infinite-replenish", true), commands(config, "commands-on-grant"),
                commands(config, "commands-on-revoke"), commands(config, "commands-on-blacklist"),
                config.getString("prepended-text", ""), config.getString("appended-text", ""), color);
    }

    private static List<String> commands(ConfigurationSection config, String key) {
        return config.getStringList(key).stream().map(String::strip)
                .map(command -> command.startsWith("/") ? command.substring(1).stripLeading() : command)
                .filter(command -> !command.isEmpty()).toList();
    }

    Component blockName(Material block) {
        return Component.text().color(color).append(Component.text(prefix),
                Component.translatable(block.translationKey()), Component.text(suffix)).build();
    }

    List<String> hooks(String action) {
        return switch (action) {
            case "grant" -> onGrant;
            case "revoke" -> onRevoke;
            case "blacklist" -> onBlacklist;
            default -> List.of();
        };
    }
}
