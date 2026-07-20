package org.popcraft.bolt.listeners;

import org.popcraft.bolt.BoltPlugin;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.network.ClientConnectionEvent;

public class BoltPlayerListener {
    private final BoltPlugin plugin;

    public BoltPlayerListener(final BoltPlugin plugin) {
        this.plugin = plugin;
    }

    @Listener
    public void onJoin(final ClientConnectionEvent.Join event) {
        plugin.player(event.getTargetEntity().getUniqueId());
    }

    @Listener
    public void onDisconnect(final ClientConnectionEvent.Disconnect event) {
        plugin.getBolt().removeBoltPlayer(event.getTargetEntity().getUniqueId());
    }
}
