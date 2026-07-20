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
import org.spongepowered.api.event.item.inventory.InteractInventoryEvent;
import org.spongepowered.api.item.inventory.BlockCarrier;
import org.spongepowered.api.item.inventory.Inventory;
import org.spongepowered.api.item.inventory.type.CarriedInventory;

import java.util.Optional;

/**
 * Protects container contents from automation and inventory interactions. In particular
 * {@link ChangeInventoryEvent.Transfer.Pre} covers hoppers/droppers/hopper-minecarts pulling from
 * or pushing into a protected container — the "hopper emptying a locked chest" case. Mirrors the
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
