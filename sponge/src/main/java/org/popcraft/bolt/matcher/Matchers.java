package org.popcraft.bolt.matcher;

import org.spongepowered.api.block.tileentity.TileEntity;
import org.spongepowered.api.block.tileentity.carrier.Chest;
import org.spongepowered.api.data.key.Keys;
import org.spongepowered.api.data.type.PortionType;
import org.spongepowered.api.data.type.PortionTypes;
import org.spongepowered.api.util.Direction;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Expands a block into the set of other blocks that share a single protection with it. On
 * SpongeAPI 7.4 there is no Bukkit-style {@code BlockData} API, so this works against tile
 * entities ({@link Chest#getConnectedChests()}) and {@link Keys#PORTION_TYPE} traits.
 *
 * <p>MVP scope: double chests and doors. The other ~55 compound blocks Bolt handles on modern
 * Bukkit are out of scope.
 */
public final class Matchers {
    private Matchers() {
    }

    public static Set<Location<World>> expand(final Location<World> location) {
        final Set<Location<World>> matches = new HashSet<>();
        expandChest(location, matches);
        expandDoor(location, matches);
        return matches;
    }

    private static void expandChest(final Location<World> location, final Set<Location<World>> matches) {
        final Optional<TileEntity> tileEntity = location.getTileEntity();
        if (tileEntity.isPresent() && tileEntity.get() instanceof Chest) {
            final Chest chest = (Chest) tileEntity.get();
            for (final Chest connected : chest.getConnectedChests()) {
                final Location<World> connectedLocation = connected.getLocation();
                if (!connectedLocation.getBlockPosition().equals(location.getBlockPosition())) {
                    matches.add(connectedLocation);
                }
            }
        }
    }

    private static void expandDoor(final Location<World> location, final Set<Location<World>> matches) {
        final Optional<PortionType> portion = location.get(Keys.PORTION_TYPE);
        if (!portion.isPresent()) {
            return;
        }
        if (PortionTypes.BOTTOM.equals(portion.get())) {
            final Location<World> upper = location.getRelative(Direction.UP);
            if (upper.get(Keys.PORTION_TYPE).isPresent()) {
                matches.add(upper);
            }
        } else {
            final Location<World> lower = location.getRelative(Direction.DOWN);
            if (lower.get(Keys.PORTION_TYPE).isPresent()) {
                matches.add(lower);
            }
        }
    }
}
