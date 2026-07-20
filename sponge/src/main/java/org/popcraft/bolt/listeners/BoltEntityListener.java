package org.popcraft.bolt.listeners;

import org.popcraft.bolt.BoltPlugin;
import org.popcraft.bolt.lang.Translation;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.protection.Protection;
import org.popcraft.bolt.util.BoltComponents;
import org.popcraft.bolt.util.Permission;
import org.popcraft.bolt.util.Placeholder;
import org.popcraft.bolt.util.Protections;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.entity.DamageEntityEvent;
import org.spongepowered.api.event.entity.DestructEntityEvent;
import org.spongepowered.api.event.entity.InteractEntityEvent;
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
    public void onInteractEntity(final InteractEntityEvent.Secondary event, @First final Player player) {
        final Entity entity = event.getTargetEntity();
        if (interactionHandler.triggerEntity(player, entity)) {
            event.setCancelled(true);
            return;
        }
        final Protection protection = plugin.findProtection(entity);
        if (protection == null) {
            return;
        }
        if (!plugin.canAccess(protection, player, Permission.INTERACT)) {
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
}
