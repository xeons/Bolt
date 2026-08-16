package org.popcraft.bolt.listeners;

import org.popcraft.bolt.BoltPlugin;
import org.popcraft.bolt.lang.Translation;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.protection.Protection;
import org.popcraft.bolt.util.BoltComponents;
import org.popcraft.bolt.util.BoltPlayer;
import org.popcraft.bolt.util.Mode;
import org.popcraft.bolt.util.Permission;
import org.popcraft.bolt.util.Placeholder;
import org.popcraft.bolt.util.ProtectableConfig;
import org.popcraft.bolt.util.Protections;
import org.popcraft.bolt.util.SchedulerUtil;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.entity.DamageEntityEvent;
import org.spongepowered.api.event.entity.DestructEntityEvent;
import org.spongepowered.api.event.entity.InteractEntityEvent;
import org.spongepowered.api.event.entity.RideEntityEvent;
import org.spongepowered.api.event.entity.SpawnEntityEvent;
import org.spongepowered.api.event.filter.cause.First;

import java.util.Optional;

public class BoltEntityListener {
    private final BoltPlugin plugin;
    private final InteractionHandler interactionHandler;

    public BoltEntityListener(final BoltPlugin plugin) {
        this.plugin = plugin;
        this.interactionHandler = new InteractionHandler(plugin);
    }

    @Listener
    public void onInteractEntitySecondary(final InteractEntityEvent.Secondary event, @First final Player player) {
        handleInteract(event, player);
    }

    @Listener
    public void onInteractEntityPrimary(final InteractEntityEvent.Primary event, @First final Player player) {
        // Left-click covers removing an item frame's contents / attacking a protected entity.
        handleInteract(event, player);
    }

    private void handleInteract(final InteractEntityEvent event, final Player player) {
        final Entity entity = event.getTargetEntity();
        if (interactionHandler.triggerEntity(player, entity)) {
            event.setCancelled(true);
            return;
        }
        final Protection protection = plugin.findProtection(entity);
        if (protection == null) {
            return;
        }
        final BoltPlayer boltPlayer = plugin.player(player);
        // Both hands can fire this event in the same tick; only act on the first to avoid double
        // messages (the block listener relies on a main-hand filter for the same reason).
        final boolean firstInteraction = !boltPlayer.hasInteracted();
        final boolean hasNotify = player.hasPermission("bolt.protection.notify");
        if (!plugin.canAccess(protection, player, Permission.INTERACT)) {
            event.setCancelled(true);
            if (firstInteraction && !hasNotify) {
                BoltComponents.sendMessage(player, Translation.LOCKED, plugin.isUseActionBar(),
                        Placeholder.of(Translation.Placeholder.PROTECTION, Protections.displayType(protection)));
            }
        }
        if (firstInteraction && hasNotify) {
            interactionHandler.notifyProtection(player, protection);
        }
        if (firstInteraction) {
            boltPlayer.setInteracted();
            SchedulerUtil.schedule(plugin, boltPlayer::clearInteraction);
        }
    }

    @Listener
    public void onRideEntity(final RideEntityEvent.Mount event, @First final Player player) {
        // Mounting a protected vehicle (boat / minecart) - mirrors Bukkit's onVehicleEnter/onEntityMount.
        final Protection protection = plugin.findProtection(event.getTargetEntity());
        if (protection != null && !plugin.canAccess(protection, player, Permission.MOUNT)) {
            event.setCancelled(true);
            BoltComponents.sendMessage(player, Translation.LOCKED, plugin.isUseActionBar(),
                    Placeholder.of(Translation.Placeholder.PROTECTION, Protections.displayType(protection)));
        }
    }

    @Listener
    public void onDamageEntity(final DamageEntityEvent event) {
        final Entity entity = event.getTargetEntity();
        final Protection protection = plugin.findProtection(entity);
        if (protection == null) {
            return;
        }
        final Optional<Player> player = event.getCause().first(Player.class);
        final boolean allowed = player.isPresent() && plugin.canAccess(protection, player.get(), Permission.DESTROY);
        if (!allowed) {
            event.setCancelled(true);
        }
    }

    @Listener
    public void onDestructEntity(final DestructEntityEvent event) {
        final EntityProtection protection = plugin.loadProtection(event.getTargetEntity());
        if (protection != null) {
            plugin.removeProtection(protection);
        }
    }

    @Listener
    public void onSpawnEntity(final SpawnEntityEvent event) {
        final Optional<Player> player = event.getCause().first(Player.class);
        if (!player.isPresent()) {
            return;
        }
        final BoltPlayer boltPlayer = plugin.player(player.get());
        if (boltPlayer.hasMode(Mode.NOLOCK)) {
            return;
        }
        for (final Entity entity : event.getEntities()) {
            final ProtectableConfig config = plugin.getProtectableConfig(entity);
            if (config == null || config.defaultAccess() == null) {
                continue;
            }
            if (plugin.isProtected(entity)) {
                continue;
            }
            final String protectionType = config.defaultAccess().type();
            final EntityProtection protection = plugin.createProtection(entity, player.get().getUniqueId(), protectionType);
            plugin.saveProtection(protection);
            if (!boltPlayer.hasMode(Mode.NOSPAM)) {
                BoltComponents.sendMessage(player.get(), Translation.CLICK_LOCKED, plugin.isUseActionBar(),
                        Placeholder.of(Translation.Placeholder.PROTECTION_TYPE, protectionType),
                        Placeholder.of(Translation.Placeholder.PROTECTION, Protections.displayType(entity.getType().getId())));
            }
        }
    }
}
