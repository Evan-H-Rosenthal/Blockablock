package io.github.evanhrosenthal.blockablock;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;

record Palette(Set<Material> grants, Set<Material> blacklist, boolean blacklistAll) {
    static final Palette EMPTY = new Palette(Set.of(), Set.of(), false);

    Palette {
        grants = Set.copyOf(grants);
        blacklist = Set.copyOf(blacklist);
    }

    static boolean isBlockItem(Material material) {
        return material != null && !material.isLegacy() && material.isBlock()
                && material.isItem() && !material.isAir();
    }

    static Material parseBlock(String input) {
        String key = input.toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:")) {
            key = key.substring("minecraft:".length());
        }
        if (!key.matches("[a-z0-9_]+")) {
            return null;
        }
        Material material = Material.matchMaterial(key);
        return isBlockItem(material) ? material : null;
    }

    boolean blocks(Material material) {
        return blacklistAll ? !grants.contains(material) && isBlockItem(material) : blacklist.contains(material);
    }

    Palette grant(Material material) {
        var nextGrants = new HashSet<>(grants);
        var nextBlacklist = new HashSet<>(blacklist);
        nextGrants.add(material);
        nextBlacklist.remove(material);
        return new Palette(nextGrants, nextBlacklist, blacklistAll);
    }

    Palette revoke(Material material) {
        if (material == null) {
            return new Palette(Set.of(), blacklist, blacklistAll);
        }
        var nextGrants = new HashSet<>(grants);
        nextGrants.remove(material);
        return new Palette(nextGrants, blacklist, blacklistAll);
    }

    Palette blacklist(Material material) {
        if (material == null) {
            return new Palette(Set.of(), Set.of(), true);
        }
        var nextBlacklist = new HashSet<>(blacklist);
        if (!blacklistAll) {
            nextBlacklist.add(material);
        }
        return new Palette(revoke(material).grants(), nextBlacklist, blacklistAll);
    }
}
