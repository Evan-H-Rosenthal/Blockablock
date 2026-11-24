package io.github.evanhrosenthal.blockablock;

import java.io.IOException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
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
    private final Logger logger;
    private final Map<UUID, Map<Material, Integer>> placementSlots = new HashMap<>();
    private final Map<UUID, Set<Material>> pendingRefills = new HashMap<>();
    private int deliveryTick;

    PaletteInventory(PaletteStore store, NamespacedKey ownerKey, Logger logger) {
        this.store = store;
        this.ownerKey = ownerKey;
        this.logger = logger;
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
        meta.itemName(store.settings().blockName(block));
        if (owner != null) {
            meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    void tick() {
        if (!store.enabled()) {
            return;
        }
        boolean checkDeliveries = ++deliveryTick == 20;
        if (checkDeliveries) {
            deliveryTick = 0;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (store.palette(player.getUniqueId()) != Palette.EMPTY) {
                refresh(player);
                if (checkDeliveries) {
                    deliverFinite(player);
                }
            }
        }
    }

    void refresh(Player player) {
        refresh(player, false);
    }

    void refreshOnJoin(Player player) {
        refresh(player, true);
        deliverFinite(player);
    }

    private void refresh(Player player, boolean updateAppearance) {
        Map<Material, Integer> preferred = placementSlots.remove(player.getUniqueId());
        if (!player.isDead()) {
            reconcile(player, activePalette(player), preferred == null ? Map.of() : preferred, updateAppearance);
        }
    }

    void clearRewards(Player player) {
        reconcile(player, Palette.EMPTY, Map.of(), false);
    }

    private void reconcile(Player player, Palette palette, Map<Material, Integer> preferred, boolean updateAppearance) {
        var inventory = player.getInventory();
        UUID id = player.getUniqueId();
        String playerId = id.toString();
        Map<Material, Integer> stock = new HashMap<>();

        // Count the cursor first so moving a reward never causes a second copy to be issued.
        ItemStack cursor = player.getItemOnCursor();
        ItemStack keptCursor = keep(cursor, palette, playerId, stock, updateAppearance);
        if (keptCursor != cursor) {
            player.setItemOnCursor(keptCursor);
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            ItemStack kept = keep(item, palette, playerId, stock, updateAppearance);
            if (kept != item) {
                inventory.setItem(slot, kept);
            }
        }
        Set<Material> unfinished = pendingRefills.remove(id);
        PaletteSettings settings = store.settings();
        if (!settings.infiniteReplenish()) {
            return;
        }
        for (Material block : palette.grants()) {
            int count = stock.getOrDefault(block, 0);
            if (count < settings.blocksPerGrant() && (count <= settings.blocksForReplenish()
                    || (unfinished != null && unfinished.contains(block)))) {
                Integer slot = preferred.get(block);
                if (count == 0 && slot == null && inventory.firstEmpty() == -1) {
                    continue;
                }
                int needed = settings.blocksPerGrant() - count;
                if (refill(inventory, reward(block, playerId), needed, slot) > 0) {
                    pendingRefills.computeIfAbsent(id, ignored -> EnumSet.noneOf(Material.class)).add(block);
                }
            }
        }
    }

    private ItemStack keep(ItemStack item, Palette palette, String playerId, Map<Material, Integer> stock,
                           boolean updateAppearance) {
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
        PaletteSettings settings = store.settings();
        if (!settings.infiniteReplenish() || !owner.equals(playerId) || !palette.grants().contains(material)) {
            return null;
        }
        int retained = Math.min(item.getAmount(), settings.blocksPerGrant() - stock.getOrDefault(material, 0));
        if (retained == 0) {
            return null;
        }
        stock.merge(material, retained, Integer::sum);
        if (updateAppearance) {
            return reward(material, playerId).asQuantity(retained);
        }
        return retained == item.getAmount() ? item : item.asQuantity(retained);
    }

    private int refill(PlayerInventory inventory, ItemStack template, int needed, Integer preferred) {
        int limit = Math.min(template.getMaxStackSize(), inventory.getMaxStackSize());
        if (preferred != null) {
            ItemStack item = inventory.getItem(preferred);
            if (isEmpty(item) || template.isSimilar(item)) {
                int current = isEmpty(item) ? 0 : item.getAmount();
                int added = Math.min(needed, Math.max(0, limit - current));
                if (added > 0) {
                    inventory.setItem(preferred, template.asQuantity(current + added));
                    needed -= added;
                }
            }
        }
        for (int slot = 0; slot < inventory.getSize() && needed > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!isEmpty(item) && item.getType() == template.getType() && template.isSimilar(item)) {
                int added = Math.min(needed, Math.max(0, limit - item.getAmount()));
                if (added > 0) {
                    inventory.setItem(slot, template.asQuantity(item.getAmount() + added));
                    needed -= added;
                }
            }
        }
        while (needed > 0) {
            int slot = inventory.firstEmpty();
            if (slot == -1) {
                break;
            }
            int added = Math.min(needed, limit);
            inventory.setItem(slot, template.asQuantity(added));
            needed -= added;
        }
        return needed;
    }

    void deliverFinite(Player player) {
        UUID id = player.getUniqueId();
        Map<Material, Long> pending = store.deliveries(id);
        if (pending.isEmpty() || player.isDead() || activePalette(player) == Palette.EMPTY) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        var delivered = new EnumMap<Material, Long>(Material.class);
        for (var entry : pending.entrySet()) {
            ItemStack stack = reward(entry.getKey(), null);
            int limit = Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize());
            long left = entry.getValue();
            for (int slot = 0; slot < storage.length && left > 0; slot++) {
                ItemStack item = storage[slot];
                if (!isEmpty(item) && item.getType() == stack.getType() && stack.isSimilar(item)) {
                    int added = (int) Math.min(left, Math.max(0, limit - item.getAmount()));
                    if (added > 0) {
                        storage[slot] = stack.asQuantity(item.getAmount() + added);
                        left -= added;
                    }
                }
            }
            for (int slot = 0; slot < storage.length && left > 0; slot++) {
                if (isEmpty(storage[slot])) {
                    int added = (int) Math.min(left, limit);
                    storage[slot] = stack.asQuantity(added);
                    left -= added;
                }
            }
            if (left < entry.getValue()) {
                delivered.put(entry.getKey(), entry.getValue() - left);
            }
        }
        if (!delivered.isEmpty()) {
            try {
                store.completeDeliveries(id, delivered);
                inventory.setStorageContents(storage);
            } catch (IOException ex) {
                logger.log(Level.SEVERE, "Could not save Blockablock delivery for " + player.getName(), ex);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refreshOnJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        placementSlots.remove(event.getPlayer().getUniqueId());
        pendingRefills.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(PlayerAttemptPickupItemEvent event) {
        ItemStack stack = event.getItem().getItemStack();
        Palette palette = activePalette(event.getPlayer());
        if (owner(stack) != null || palette.blocks(stack.getType())
                || (store.settings().infiniteReplenish() && palette.grants().contains(stack.getType()))) {
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
        if (store.settings().infiniteReplenish() && event.canBuild() && id.toString().equals(owner(item))
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
        if (palette.blocks(item.getType()) || (owner != null && (!store.settings().infiniteReplenish()
                || !palette.grants().contains(item.getType())
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
