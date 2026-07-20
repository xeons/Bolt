package org.popcraft.bolt.util;

import org.popcraft.bolt.Bolt;
import org.popcraft.bolt.source.PlayerSourceResolver;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceResolver;
import org.popcraft.bolt.source.SourceTypes;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.Player;

import java.util.UUID;

public class SpongePlayerResolver implements SourceResolver {
    private final Bolt bolt;
    private final UUID uuid;
    private final BoltPlayer boltPlayer;
    private final Player player;

    public SpongePlayerResolver(final Bolt bolt, final UUID uuid) {
        this.bolt = bolt;
        this.uuid = uuid;
        this.boltPlayer = bolt.getBoltPlayer(uuid);
        this.player = Sponge.getServer().getPlayer(uuid).orElse(null);
    }

    @Override
    public boolean resolve(final Source source) {
        if (boltPlayer.sources().contains(source)) {
            return true;
        }
        if (SourceTypes.GROUP.equals(source.getType())) {
            final Group group = bolt.getStore().loadGroup(source.getIdentifier()).join();
            if (group != null && group.getMembers().contains(uuid)) {
                return true;
            }
        }
        if (player != null && SourceTypes.PERMISSION.equals(source.getType()) && player.hasPermission(source.getIdentifier())) {
            return true;
        }
        for (final PlayerSourceResolver playerSourceResolver : bolt.getRegisteredPlayerResolvers()) {
            if (playerSourceResolver.resolve(source, uuid)) {
                return true;
            }
        }
        return false;
    }
}
