package io.github.evanhrosenthal.blockablock;

import java.io.IOException;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.java.JavaPlugin;

public final class BlockablockPlugin extends JavaPlugin {
    private PaletteInventory inventory;

    @Override
    public void onEnable() {
        var store = new PaletteStore(getDataFolder().toPath().resolve("config.yml"));
        try {
            store.load();
        } catch (IOException | InvalidConfigurationException ex) {
            getLogger().log(Level.SEVERE, "Could not load Blockablock config.yml", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        inventory = new PaletteInventory(store, new NamespacedKey(this, "infinite-owner"), getLogger());
        var hooks = new PaletteHooks(this, store);
        var commands = new PaletteCommand(this, store, inventory, hooks);
        var root = Objects.requireNonNull(getCommand("blockablock"));
        root.setExecutor(commands);
        root.setTabCompleter(commands);
        getServer().getPluginManager().registerEvents(inventory, this);
        getServer().getPluginManager().registerEvents(hooks, this);
        getServer().getPluginManager().registerEvents(commands, this);
        getServer().getScheduler().runTaskTimer(this, inventory::tick, 1, 1);
        for (var player : Bukkit.getOnlinePlayers()) {
            inventory.refreshOnJoin(player);
            hooks.schedule(player);
        }
    }

    @Override
    public void onDisable() {
        if (inventory != null) {
            Bukkit.getOnlinePlayers().forEach(inventory::clearRewards);
        }
    }

    void reportSaveFailure(CommandSender sender, IOException exception) {
        getLogger().log(Level.SEVERE, "Could not save Blockablock config.yml", exception);
        sender.sendMessage("Could not save Blockablock settings. Check the server log.");
    }
}
