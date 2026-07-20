package org.popcraft.bolt.listeners;

import org.popcraft.bolt.BoltPlugin;
import org.popcraft.bolt.util.BoltPlayer;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.network.ClientConnectionEvent;

import java.util.UUID;

public class BoltPlayerListener {
    private final BoltPlugin plugin;

    public BoltPlayerListener(final BoltPlugin plugin) {
        this.plugin = plugin;
    }

    @Listener
    public void onJoin(final ClientConnectionEvent.Join event) {
        final UUID uuid = event.getTargetEntity().getUniqueId();
        final BoltPlayer boltPlayer = plugin.player(uuid);
        plugin.loadPlayerModes(uuid, boltPlayer);
    }

    @Listener
    public void onDisconnect(final ClientConnectionEvent.Disconnect event) {
        plugin.getBolt().removeBoltPlayer(event.getTargetEntity().getUniqueId());
    }
}
