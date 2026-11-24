package io.github.evanhrosenthal.blockablock;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandException;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

final class PaletteHooks implements Listener {
    private final BlockablockPlugin plugin;
    private final PaletteStore store;
    private boolean executing;

    PaletteHooks(BlockablockPlugin plugin, PaletteStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    List<String> configured(String action) {
        // A hook may call Blockablock itself. Avoid an unbounded chain of callbacks.
        return executing ? List.of() : store.settings().hooks(action);
    }

    void schedule(Player player) {
        if (!executing && store.hasHooks(player.getUniqueId())) {
            Bukkit.getScheduler().runTask(plugin, () -> runPending(player));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        schedule(event.getPlayer());
    }

    private void runPending(Player player) {
        if (!player.isOnline() || !store.hasHooks(player.getUniqueId())) {
            return;
        }
        List<String> commands;
        try {
            commands = store.takeHooks(player.getUniqueId());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save pending Blockablock commands for " + player.getName(), ex);
            return;
        }

        executing = true;
        try {
            String context = "minecraft:execute as " + player.getUniqueId() + " at @s run ";
            for (String command : commands) {
                try {
                    if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), context + command)) {
                        plugin.getLogger().warning("Could not dispatch a Blockablock hook for " + player.getName() + ": " + command);
                    }
                } catch (CommandException ex) {
                    plugin.getLogger().log(Level.WARNING, "Blockablock hook failed for " + player.getName() + ": " + command, ex);
                }
            }
        } finally {
            executing = false;
        }
    }
}
