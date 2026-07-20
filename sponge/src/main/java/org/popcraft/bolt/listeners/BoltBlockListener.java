package org.popcraft.bolt.listeners;

import org.popcraft.bolt.BoltPlugin;
import org.popcraft.bolt.lang.Translation;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.Protection;
import org.popcraft.bolt.util.BoltComponents;
import org.popcraft.bolt.util.Permission;
import org.popcraft.bolt.util.Placeholder;
import org.popcraft.bolt.util.ProtectableConfig;
import org.popcraft.bolt.util.Protections;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.data.Transaction;
import org.spongepowered.api.data.type.HandTypes;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.block.ChangeBlockEvent;
import org.spongepowered.api.event.block.InteractBlockEvent;
import org.spongepowered.api.event.filter.cause.First;
import org.spongepowered.api.event.world.ExplosionEvent;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import java.util.Optional;

public class BoltBlockListener {
    private final BoltPlugin plugin;
    private final InteractionHandler interactionHandler;

    public BoltBlockListener(final BoltPlugin plugin) {
        this.plugin = plugin;
        this.interactionHandler = new InteractionHandler(plugin);
    }

    @Listener
    public void onInteractSecondary(final InteractBlockEvent.Secondary event, @First final Player player) {
        if (event.getHandType() != HandTypes.MAIN_HAND) {
            return;
        }
        final Optional<Location<World>> location = event.getTargetBlock().getLocation();
        if (!location.isPresent()) {
            return;
        }
        if (interactionHandler.triggerBlock(player, location.get())) {
            event.setCancelled(true);
            return;
        }
        final Protection protection = plugin.findProtection(location.get());
        if (protection == null) {
            return;
        }
        if (!plugin.canAccess(protection, player, Permission.INTERACT)) {
            event.setCancelled(true);
            BoltComponents.sendMessage(player, Translation.LOCKED, plugin.isUseActionBar(),
                    Placeholder.of(Translation.Placeholder.PROTECTION, Protections.displayType(protection)));
        } else {
            protection.setAccessed(System.currentTimeMillis());
            plugin.saveProtection(protection);
        }
    }

    @Listener
    public void onInteractPrimary(final InteractBlockEvent.Primary event, @First final Player player) {
        if (event.getHandType() != HandTypes.MAIN_HAND) {
            return;
        }
        final Optional<Location<World>> location = event.getTargetBlock().getLocation();
        if (!location.isPresent()) {
            return;
        }
        if (interactionHandler.triggerBlock(player, location.get())) {
            event.setCancelled(true);
        }
    }

    @Listener
    public void onBreak(final ChangeBlockEvent.Break event) {
        final Optional<Player> player = event.getCause().first(Player.class);
        for (final Transaction<BlockSnapshot> transaction : event.getTransactions()) {
            final Optional<Location<World>> location = transaction.getOriginal().getLocation();
            if (!location.isPresent()) {
                continue;
            }
            final Protection exact = plugin.loadProtection(location.get());
            final Protection protection = exact != null ? exact : plugin.findProtection(location.get());
            if (protection == null) {
                continue;
            }
            final boolean allowed = player.isPresent() && plugin.canAccess(protection, player.get(), Permission.DESTROY);
            if (!allowed) {
                transaction.setValid(false);
                if (player.isPresent()) {
                    BoltComponents.sendMessage(player.get(), Translation.LOCKED, plugin.isUseActionBar(),
                            Placeholder.of(Translation.Placeholder.PROTECTION, Protections.displayType(protection)));
                }
            } else if (exact != null) {
                plugin.removeProtection(exact);
            }
        }
    }

    @Listener
    public void onPlace(final ChangeBlockEvent.Place event) {
        final Optional<Player> player = event.getCause().first(Player.class);
        if (!player.isPresent()) {
            return;
        }
        for (final Transaction<BlockSnapshot> transaction : event.getTransactions()) {
            final BlockSnapshot placed = transaction.getFinal();
            final Optional<Location<World>> location = placed.getLocation();
            if (!location.isPresent()) {
                continue;
            }
            final BlockType type = placed.getState().getType();
            final ProtectableConfig config = plugin.getProtectableConfig(type);
            if (config == null || config.defaultAccess() == null) {
                continue;
            }
            if (plugin.isProtected(location.get())) {
                continue;
            }
            final String protectionType = config.defaultAccess().type();
            final BlockProtection protection = plugin.createProtection(location.get(), player.get().getUniqueId(), protectionType);
            protection.setBlock(type.getId());
            plugin.saveProtection(protection);
            BoltComponents.sendMessage(player.get(), Translation.CLICK_LOCKED, plugin.isUseActionBar(),
                    Placeholder.of(Translation.Placeholder.PROTECTION_TYPE, protectionType),
                    Placeholder.of(Translation.Placeholder.PROTECTION, Protections.displayType(type.getId())));
        }
    }

    @Listener
    public void onExplosion(final ExplosionEvent.Detonate event) {
        event.getAffectedLocations().removeIf(plugin::isProtected);
    }
}
