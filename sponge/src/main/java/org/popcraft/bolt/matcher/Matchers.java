package org.popcraft.bolt.matcher;

import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.block.BlockTypes;
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
 * SpongeAPI 7.4 (Minecraft 1.12.2) there is no Bukkit-style {@code BlockData} API and chests have
 * no left/right connection trait — a double chest is simply two horizontally-adjacent chests of
 * the same type. Detection therefore reads block types (reliable even mid-break, unlike the tile
 * entity's {@code getConnectedChests()}), and doors use the {@link Keys#PORTION_TYPE} trait.
 *
 * <p>MVP scope: double chests and doors. The other ~55 compound blocks Bolt handles on modern
 * Bukkit are out of scope.
 */
public final class Matchers {
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private Matchers() {
    }

    public static boolean isChest(final BlockType type) {
        return type == BlockTypes.CHEST || type == BlockTypes.TRAPPED_CHEST;
    }

    /**
     * The location of a chest's double-chest partner — a horizontally-adjacent chest of the same
     * type — or empty if none. The type is supplied explicitly so a caller breaking a block can
     * pass the block's pre-break type, since the live world may already read as air mid-break. In
     * 1.12.2 the game prevents triple chests, so there is at most one partner.
     */
    public static Optional<Location<World>> chestPartner(final Location<World> location, final BlockType type) {
        if (!isChest(type)) {
            return Optional.empty();
        }
        for (final Direction direction : HORIZONTAL) {
            final Location<World> relative = location.getRelative(direction);
            if (relative.getBlockType() == type) {
                return Optional.of(relative);
            }
        }
        return Optional.empty();
    }

    public static Set<Location<World>> expand(final Location<World> location) {
        final Set<Location<World>> matches = new HashSet<>();
        chestPartner(location, location.getBlockType()).ifPresent(matches::add);
        expandDoor(location, matches);
        return matches;
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
