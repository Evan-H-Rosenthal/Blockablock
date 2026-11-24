package io.github.evanhrosenthal.blockablock;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

final class PaletteInventory implements Listener {
    private static final int OFF_HAND_SLOT = 40;

    private final PaletteStore store;
    private final NamespacedKey ownerKey;
    private final Map<UUID, Map<Material, Integer>> placementSlots = new HashMap<>();

    PaletteInventory(PaletteStore store, NamespacedKey ownerKey) {
        this.store = store;
        this.ownerKey = ownerKey;
    }

    private Palette activePalette(Player player) {
        if (!store.enabled() || player.isOp() || player.getGameMode() != GameMode.SURVIVAL) {
            return Palette.EMPTY;
        }
        return store.palette(player.getUniqueId());
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.isEmpty();
    }

    private String owner(ItemStack item) {
        if (isEmpty(item)) {
            return null;
        }
        return item.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
    }

    private ItemStack reward(Material block, String owner) {
        var stack = ItemStack.of(block);
        var meta = stack.getItemMeta();
        meta.itemName(Component.text("Infinite ").append(Component.translatable(block.translationKey())));
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner);
        stack.setItemMeta(meta);
        return stack;
    }

    void tick() {
        if (!store.enabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (store.palette(player.getUniqueId()) != Palette.EMPTY) {
                refresh(player);
            }
        }
    }

    void refresh(Player player) {
        Map<Material, Integer> preferred = placementSlots.remove(player.getUniqueId());
        if (!player.isDead()) {
            reconcile(player, activePalette(player), preferred == null ? Map.of() : preferred);
        }
    }

    void clearRewards(Player player) {
        reconcile(player, Palette.EMPTY, Map.of());
    }

    private void reconcile(Player player, Palette palette, Map<Material, Integer> preferred) {
        var inventory = player.getInventory();
        String playerId = player.getUniqueId().toString();
        Set<Material> found = EnumSet.noneOf(Material.class);

        // Count the cursor first so moving a reward never causes a second copy to be issued.
        ItemStack cursor = player.getItemOnCursor();
        ItemStack keptCursor = keep(cursor, palette, playerId, found);
        if (keptCursor != cursor) {
            player.setItemOnCursor(keptCursor);
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            ItemStack kept = keep(item, palette, playerId, found);
            if (kept != item) {
                inventory.setItem(slot, kept);
            }
        }
        for (Material block : palette.grants()) {
            Integer slot = preferred.get(block);
            if (!found.contains(block) && slot != null && isEmpty(inventory.getItem(slot))) {
                inventory.setItem(slot, reward(block, playerId));
                found.add(block);
            }
        }
        for (Material block : palette.grants()) {
            if (!found.contains(block)) {
                int slot = inventory.firstEmpty();
                if (slot == -1) {
                    break;
                }
                inventory.setItem(slot, reward(block, playerId));
            }
        }
    }

    private ItemStack keep(ItemStack item, Palette palette, String playerId, Set<Material> found) {
        if (isEmpty(item)) {
            return item;
        }
        Material material = item.getType();
        if (palette.blocks(material)) {
            return null;
        }
        String owner = owner(item);
        if (owner == null) {
            return item;
        }
        if (!owner.equals(playerId) || !palette.grants().contains(material) || !found.add(material)) {
            return null;
        }
        return item.getAmount() == 1 ? item : reward(material, playerId);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        placementSlots.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(PlayerAttemptPickupItemEvent event) {
        ItemStack stack = event.getItem().getItemStack();
        Palette palette = activePalette(event.getPlayer());
        if (owner(stack) != null || palette.blocks(stack.getType()) || palette.grants().contains(stack.getType())) {
            event.setCancelled(true);
            event.getItem().remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (owner(event.getItemDrop().getItemStack()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (owner(event.getEntity().getItemStack()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(item -> owner(item) != null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (activePalette(event.getPlayer()).blocks(event.getItemInHand().getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void rememberPlacementSlot(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItemInHand();
        UUID id = player.getUniqueId();
        if (event.canBuild() && id.toString().equals(owner(item))
                && activePalette(player).grants().contains(item.getType())) {
            int slot = event.getHand() == EquipmentSlot.OFF_HAND ? OFF_HAND_SLOT : player.getInventory().getHeldItemSlot();
            // The server consumes the placed item after BlockPlaceEvent returns.
            placementSlots.computeIfAbsent(id, ignored -> new EnumMap<>(Material.class)).put(item.getType(), slot);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        ItemStack item = event.getItem();
        if (isEmpty(item)) {
            return;
        }
        Palette palette = activePalette(event.getPlayer());
        String owner = owner(item);
        if (palette.blocks(item.getType()) || (owner != null && (!palette.grants().contains(item.getType())
                || !owner.equals(event.getPlayer().getUniqueId().toString())))) {
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
        } else if (owner != null && event.getClickedBlock() != null) {
            Material clicked = event.getClickedBlock().getType();
            // These blocks can take items directly from the player's hand.
            if (clicked == Material.DECORATED_POT || clicked == Material.COMPOSTER
                    || clicked.name().endsWith("CAULDRON") || clicked.name().endsWith("_SHELF")) {
                event.setUseInteractedBlock(Event.Result.DENY);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUseOnEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItem(event.getHand());
        if (!isEmpty(item) && (owner(item) != null || activePalette(player).blocks(item.getType()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUseAtEntity(PlayerInteractAtEntityEvent event) {
        onUseOnEntity(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        onUseOnEntity(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean cursorReward = owner(event.getCursor()) != null;
        boolean clickedReward = owner(event.getCurrentItem()) != null;
        boolean personalSlot = event.getClickedInventory() instanceof PlayerInventory;
        ItemStack swap = switch (event.getClick()) {
            case NUMBER_KEY -> player.getInventory().getItem(event.getHotbarButton());
            case SWAP_OFFHAND -> player.getInventory().getItemInOffHand();
            default -> null;
        };
        boolean cancel = switch (event.getAction()) {
            case DROP_ALL_CURSOR, DROP_ONE_CURSOR -> cursorReward;
            case DROP_ALL_SLOT, DROP_ONE_SLOT, CLONE_STACK -> clickedReward;
            case MOVE_TO_OTHER_INVENTORY -> clickedReward && event.getView().getType() != InventoryType.CRAFTING;
            case PICKUP_ALL_INTO_BUNDLE, PICKUP_SOME_INTO_BUNDLE -> clickedReward;
            case PLACE_ALL_INTO_BUNDLE, PLACE_SOME_INTO_BUNDLE -> cursorReward;
            default -> false;
        };
        if (cancel || (!personalSlot && (cursorReward || owner(swap) != null))
                || (clickedReward && event.getClick() == ClickType.CREATIVE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (owner(event.getOldCursor()) != null
                && event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }
}
