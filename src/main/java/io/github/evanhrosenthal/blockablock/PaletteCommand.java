package io.github.evanhrosenthal.blockablock;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

final class PaletteCommand implements TabExecutor, Listener {
    private static final Duration RESET_TIMEOUT = Duration.ofSeconds(30);
    private static final List<String> HELP_TOPICS = List.of("grant", "revoke", "list", "blacklist", "toggle", "reset");
    private static final List<String> SELECTORS = List.of("@s", "@p", "@a", "@r", "@e", "@n");
    private static final List<String> BLOCK_KEYS = Arrays.stream(Material.values())
            .filter(Palette::isBlockItem).map(material -> material.getKey().getKey()).sorted().toList();

    private final BlockablockPlugin plugin;
    private final PaletteStore store;
    private final PaletteInventory inventory;
    private final PaletteHooks hooks;
    private final Map<Object, Long> resetPrompts = new ConcurrentHashMap<>();

    PaletteCommand(BlockablockPlugin plugin, PaletteStore store, PaletteInventory inventory, PaletteHooks hooks) {
        this.plugin = plugin;
        this.store = store;
        this.inventory = inventory;
        this.hooks = hooks;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return help(sender, label, args);
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!action.equals("list") && !action.equals("help") && !isOperator(sender)) {
            sender.sendMessage("Only operators can change Blockablock settings.");
            return true;
        }

        try {
            return switch (action) {
                case "grant", "revoke", "blacklist" -> change(sender, label, args, action);
                case "list" -> list(sender, label, args);
                case "toggle" -> toggle(sender, label, args);
                case "reset" -> reset(sender, label, args);
                case "help" -> help(sender, label, args);
                default -> false;
            };
        } catch (IOException ex) {
            plugin.reportSaveFailure(sender, ex);
            return true;
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(ex.getMessage());
            return true;
        }
    }

    private boolean change(CommandSender sender, String label, String[] args, String action) throws IOException {
        if (args.length < 3) {
            sender.sendMessage("/" + label + " " + action + " <player selector> <block" + (action.equals("grant") ? ">" : "|*>"));
            return true;
        }
        String input = args[args.length - 1];
        Material block = Palette.parseBlock(input);
        if (input.equals("*") && action.equals("grant")) {
            sender.sendMessage("Grant requires a single block ID.");
            return true;
        }
        if (block == null && !input.equals("*")) {
            sender.sendMessage("Unknown block item " + input + ". Try an ID such as minecraft:stone.");
            return true;
        }

        List<OfflinePlayer> players = targets(sender, String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1)));
        UnaryOperator<Palette> update = switch (action) {
            case "grant" -> palette -> palette.grant(block);
            case "revoke" -> palette -> palette.revoke(block);
            case "blacklist" -> palette -> palette.blacklist(block);
            default -> throw new IllegalStateException("Unexpected palette command " + action);
        };
        Material finiteGrant = action.equals("grant") && !store.settings().infiniteReplenish() ? block : null;
        store.update(players, update, finiteGrant, hooks.configured(action));
        for (OfflinePlayer target : players) {
            Player online = target.getPlayer();
            if (online != null) {
                inventory.refresh(online);
                inventory.deliverFinite(online);
                hooks.schedule(online);
            }
        }
        String verb = switch (action) {
            case "grant" -> "Granted";
            case "revoke" -> "Revoked";
            default -> "Blacklisted";
        };
        sender.sendMessage(verb + " " + (block == null ? "all blocks" : block.getKey()) + " for "
                + players.size() + (players.size() == 1 ? " player." : " players."));
        return true;
    }

    private boolean help(CommandSender sender, String label, String[] args) {
        String usage = "/" + label + " help <" + String.join("|", HELP_TOPICS) + ">";
        if (args.length != 2) {
            sender.sendMessage(usage);
            return true;
        }
        String detail = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "grant" -> "grant <player> <block> — Gives a block reward.";
            case "revoke" -> "revoke <player> <block|*> — Removes a grant or all grants.";
            case "list" -> "list <player> — Privately shows a player's saved palette.";
            case "blacklist" -> "blacklist <player> <block|*> — Blocks possession and use.";
            case "toggle" -> "toggle — Turns Blockablock on or off.";
            case "reset" -> "reset — Clears all players' palettes after you confirm with yes.";
            default -> null;
        };
        sender.sendMessage(detail == null ? usage : "/" + label + " " + detail);
        return true;
    }

    private boolean list(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("/" + label + " list <player selector>");
            return true;
        }
        var lines = new ArrayList<String>();
        for (OfflinePlayer target : targets(sender, String.join(" ", Arrays.copyOfRange(args, 1, args.length)))) {
            UUID id = target.getUniqueId();
            Palette palette = store.palette(id);
            String name = target.getName() == null ? store.name(id) : target.getName();
            lines.add("Blockablock palette for " + (name == null ? id : name));
            addBlocks(lines, "Granted blocks", PaletteStore.blockKeys(palette.grants()));
            if (palette.blacklistAll()) {
                lines.add("Blacklisted blocks include all block items except the grants above.");
            } else {
                addBlocks(lines, "Blacklisted blocks", PaletteStore.blockKeys(palette.blacklist()));
            }
        }
        if (caller(sender) instanceof BlockCommandSender) {
            // A command block stores a single response in its Previous Output field.
            sender.sendMessage(Component.text(String.join("\n", lines)));
        } else {
            lines.forEach(sender::sendMessage);
        }
        return true;
    }

    private static void addBlocks(List<String> lines, String title, List<String> keys) {
        if (keys.isEmpty()) {
            lines.add(title + " — none");
            return;
        }
        lines.add(title);
        for (int start = 0; start < keys.size(); start += 6) {
            lines.add(String.join(", ", keys.subList(start, Math.min(start + 6, keys.size()))));
        }
    }

    private List<OfflinePlayer> targets(CommandSender sender, String input) {
        if (input.startsWith("@")) {
            List<OfflinePlayer> selected;
            try {
                selected = Bukkit.selectEntities(sender, input).stream().filter(Player.class::isInstance)
                        .map(OfflinePlayer.class::cast).toList();
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Invalid player selector " + input + ".");
            }
            if (selected.isEmpty()) {
                throw new IllegalArgumentException("No online players matched " + input + ".");
            }
            return selected;
        }

        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return List.of(online);
        }
        try {
            return List.of(Bukkit.getOfflinePlayer(UUID.fromString(input)));
        } catch (IllegalArgumentException ex) {
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
            if (cached != null) {
                return List.of(cached);
            }
            UUID saved = store.findByName(input);
            if (saved != null) {
                return List.of(Bukkit.getOfflinePlayer(saved));
            }
        }
        throw new IllegalArgumentException("Unknown player " + input + ". Use their current name or UUID.");
    }

    private boolean toggle(CommandSender sender, String label, String[] args) throws IOException {
        if (args.length != 1) {
            sender.sendMessage("/" + label + " toggle");
            return true;
        }
        store.toggle();
        for (Player player : Bukkit.getOnlinePlayers()) {
            inventory.refresh(player);
            inventory.deliverFinite(player);
        }
        sender.sendMessage(store.enabled() ? "Blockablock is on." : "Blockablock is off.");
        return true;
    }

    private boolean reset(CommandSender sender, String label, String[] args) throws IOException {
        Object key = senderKey(sender);
        if (args.length == 2 && args[1].equalsIgnoreCase("yes")) {
            confirmReset(sender, key, resetPrompts.get(key));
        } else if (args.length == 1) {
            long until = System.nanoTime() + RESET_TIMEOUT.toNanos();
            resetPrompts.put(key, until);
            Bukkit.getScheduler().runTaskLater(plugin, () -> resetPrompts.remove(key, until), 20 * RESET_TIMEOUT.toSeconds());
            String confirmation = key instanceof UUID ? "Type yes in chat or run /" : "Run /";
            sender.sendMessage("This removes every player's grants and blacklists, including offline players. "
                    + confirmation + label + " reset yes within 30 seconds to confirm.");
        } else {
            sender.sendMessage("/" + label + " reset [yes]");
        }
        return true;
    }

    private void confirmReset(CommandSender sender, Object key, Long until) throws IOException {
        if (!isOperator(sender) || until == null || System.nanoTime() - until >= 0 || !resetPrompts.remove(key, until)) {
            sender.sendMessage("Run /blockablock reset to request a new confirmation.");
            return;
        }
        store.reset();
        resetPrompts.clear();
        Bukkit.getOnlinePlayers().forEach(inventory::refresh);
        sender.sendMessage("All Blockablock grants and blacklists were reset.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onResetChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID key = player.getUniqueId();
        Long until = resetPrompts.get(key);
        if (until == null || System.nanoTime() - until >= 0
                || !PlainTextComponentSerializer.plainText().serialize(event.originalMessage()).strip().equalsIgnoreCase("yes")) {
            return;
        }
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                try {
                    confirmReset(player, key, until);
                } catch (IOException ex) {
                    plugin.reportSaveFailure(player, ex);
                }
            }
        });
    }

    private static CommandSender caller(CommandSender sender) {
        while (sender instanceof ProxiedCommandSender proxy) {
            sender = proxy.getCaller();
        }
        return sender;
    }

    private static boolean isOperator(CommandSender sender) {
        return caller(sender).isOp();
    }

    private static Object senderKey(CommandSender sender) {
        return switch (caller(sender)) {
            case Player player -> player.getUniqueId();
            case BlockCommandSender block -> block.getBlock().getLocation();
            case CommandSender original -> original;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean operator = isOperator(sender);
        if (args.length <= 1) {
            Stream<String> actions = operator ? Stream.concat(Stream.of("help"), HELP_TOPICS.stream()) : Stream.of("help", "list");
            return complete(actions, args.length == 0 ? "" : args[0]);
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && action.equals("help")) {
            return complete(HELP_TOPICS.stream(), args[1]);
        }
        if (args.length == 2 && (action.equals("list")
                || (operator && List.of("grant", "revoke", "blacklist").contains(action)))) {
            return complete(Stream.concat(SELECTORS.stream(), Bukkit.getOnlinePlayers().stream().map(Player::getName)), args[1]);
        }
        if (operator && args.length >= 3 && List.of("grant", "revoke", "blacklist").contains(action)) {
            String partial = args[args.length - 1];
            var options = new ArrayList<>(complete(partial.startsWith("minecraft:")
                    ? BLOCK_KEYS.stream().map(key -> "minecraft:" + key) : BLOCK_KEYS.stream(), partial));
            if (!action.equals("grant") && "*".startsWith(partial)) {
                options.addFirst("*");
            }
            return options;
        }
        if (operator && args.length == 2 && action.equals("reset") && resetPrompts.containsKey(senderKey(sender))) {
            return complete(Stream.of("yes"), args[1]);
        }
        return List.of();
    }

    private static List<String> complete(Stream<String> values, String partial) {
        String prefix = partial.toLowerCase(Locale.ROOT);
        return values.filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
