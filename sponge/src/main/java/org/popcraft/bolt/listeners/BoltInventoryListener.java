package org.popcraft.bolt.listeners;

import org.popcraft.bolt.BoltPlugin;
import org.popcraft.bolt.protection.Protection;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceResolver;
import org.popcraft.bolt.source.SourceTypeResolver;
import org.popcraft.bolt.source.SourceTypes;
import org.popcraft.bolt.util.Permission;
import org.spongepowered.api.block.tileentity.TileEntity;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.filter.cause.First;
import org.spongepowered.api.event.item.inventory.ChangeInventoryEvent;
import org.spongepowered.api.event.item.inventory.ClickInventoryEvent;
import org.spongepowered.api.event.item.inventory.InteractInventoryEvent;
import org.spongepowered.api.item.inventory.BlockCarrier;
import org.spongepowered.api.item.inventory.Inventory;
import org.spongepowered.api.item.inventory.ItemStackSnapshot;
import org.spongepowered.api.item.inventory.Slot;
import org.spongepowered.api.item.inventory.transaction.SlotTransaction;
import org.spongepowered.api.item.inventory.type.CarriedInventory;

import java.util.Optional;

/**
 * Protects container contents from automation and inventory interactions. In particular
 * {@link ChangeInventoryEvent.Transfer.Pre} covers hoppers/droppers/hopper-minecarts pulling from
 * or pushing into a protected container - the "hopper emptying a locked chest" case. Mirrors the
 * core of Bukkit's InventoryListener (onInventoryMoveItem + onInventoryOpen).
 */
public final class BoltInventoryListener {
    private static final SourceResolver BLOCK_SOURCE_RESOLVER = new SourceTypeResolver(Source.of(SourceTypes.BLOCK));

    private final BoltPlugin plugin;

    public BoltInventoryListener(final BoltPlugin plugin) {
        this.plugin = plugin;
    }

    @Listener
    public void onInventoryTransfer(final ChangeInventoryEvent.Transfer.Pre event) {
        final Protection source = getInventoryProtection(event.getSourceInventory());
        final Protection destination = getInventoryProtection(event.getTargetInventory());
        if (source == null && destination == null) {
            return;
        }
        if (source != null && destination != null) {
            // Between two protected containers: each owner must permit the other side.
            if (!plugin.canAccess(destination, source.getOwner(), Permission.DEPOSIT)
                    || !plugin.canAccess(source, destination.getOwner(), Permission.WITHDRAW)) {
                event.setCancelled(true);
            }
        } else if (source != null && !plugin.canAccess(source, BLOCK_SOURCE_RESOLVER, Permission.WITHDRAW)) {
            // A hopper/dropper pulling from a protected container (needs the block WITHDRAW right).
            event.setCancelled(true);
        } else if (destination != null && !plugin.canAccess(destination, BLOCK_SOURCE_RESOLVER, Permission.DEPOSIT)) {
            // A hopper pushing into a protected container (needs the block DEPOSIT right).
            event.setCancelled(true);
        }
    }

    @Listener
    public void onInventoryOpen(final InteractInventoryEvent.Open event, @First final Player player) {
        final Protection protection = getInventoryProtection(event.getTargetInventory());
        if (protection != null && !plugin.canAccess(protection, player, Permission.OPEN)) {
            event.setCancelled(true);
        }
    }

    /**
     * Gates item movement within an open protected container by DEPOSIT/WITHDRAW. Covers normal
     * clicks, shift-clicks and drags (all extend {@link ClickInventoryEvent}). A player may be able
     * to open a container (e.g. a "display" protection) yet not be allowed to add or remove items.
     */
    @Listener
    public void onClickInventory(final ClickInventoryEvent event, @First final Player player) {
        final Protection protection = getInventoryProtection(event.getTargetInventory());
        if (protection == null) {
            return;
        }
        boolean deposit = false;
        boolean withdraw = false;
        final Inventory playerInventory = player.getInventory();
        for (final SlotTransaction transaction : event.getTransactions()) {
            final Slot slot = transaction.getSlot();
            // Only care about changes to the container's own slots, not the player's inventory.
            if (playerInventory.containsInventory(slot)) {
                continue;
            }
            final ItemStackSnapshot before = transaction.getOriginal();
            final ItemStackSnapshot after = transaction.getFinal();
            final int beforeQuantity = before.getQuantity();
            final int afterQuantity = after.getQuantity();
            // Compare the actual item, not just the count, so an equal-size swap of two different
            // items (a pure rearrange) is still detected on e.g. a display protection.
            final boolean sameItem = beforeQuantity > 0 && afterQuantity > 0 && before.getType().equals(after.getType());
            // Items left this container slot: emptied, reduced, or replaced by a different item.
            if (beforeQuantity > 0 && (afterQuantity == 0 || !sameItem || afterQuantity < beforeQuantity)) {
                withdraw = true;
            }
            // Items entered this container slot: filled, increased, or a different item placed in.
            if (afterQuantity > 0 && (beforeQuantity == 0 || !sameItem || afterQuantity > beforeQuantity)) {
                deposit = true;
            }
        }
        if (deposit && !plugin.canAccess(protection, player, Permission.DEPOSIT)) {
            event.setCancelled(true);
        } else if (withdraw && !plugin.canAccess(protection, player, Permission.WITHDRAW)) {
            event.setCancelled(true);
        }
    }

    private Protection getInventoryProtection(final Inventory inventory) {
        if (!(inventory instanceof CarriedInventory)) {
            return null;
        }
        final Optional<?> carrier = ((CarriedInventory<?>) inventory).getCarrier();
        if (!carrier.isPresent()) {
            return null;
        }
        final Object holder = carrier.get();
        if (holder instanceof Entity) {
            return plugin.findProtection((Entity) holder);
        }
        if (holder instanceof BlockCarrier) {
            return plugin.findProtection(((BlockCarrier) holder).getLocation());
        }
        if (holder instanceof TileEntity) {
            return plugin.findProtection(((TileEntity) holder).getLocation());
        }
        return null;
    }
}
