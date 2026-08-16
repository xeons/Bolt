package org.popcraft.bolt.listeners;

import org.popcraft.bolt.BoltPlugin;
import org.popcraft.bolt.lang.Translation;
import org.popcraft.bolt.matcher.Matchers;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.Protection;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceResolver;
import org.popcraft.bolt.source.SourceTypeResolver;
import org.popcraft.bolt.source.SourceTypes;
import org.popcraft.bolt.util.BoltComponents;
import org.popcraft.bolt.util.BoltPlayer;
import org.popcraft.bolt.util.Mode;
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
import org.spongepowered.api.event.block.NotifyNeighborBlockEvent;
import org.spongepowered.api.event.block.tileentity.ChangeSignEvent;
import org.spongepowered.api.event.filter.cause.First;
import org.spongepowered.api.event.world.ExplosionEvent;
import org.spongepowered.api.world.LocatableBlock;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import java.util.Optional;

public class BoltBlockListener {
    private static final SourceResolver REDSTONE_SOURCE_RESOLVER = new SourceTypeResolver(Source.of(SourceTypes.REDSTONE));

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
        final boolean hasNotify = player.hasPermission("bolt.protection.notify");
        if (plugin.canAccess(protection, player, Permission.INTERACT)) {
            protection.setAccessed(System.currentTimeMillis());
            plugin.saveProtection(protection);
        } else {
            event.setCancelled(true);
            if (!hasNotify) {
                BoltComponents.sendMessage(player, Translation.LOCKED, plugin.isUseActionBar(),
                        Placeholder.of(Translation.Placeholder.PROTECTION, Protections.displayType(protection)));
            }
        }
        if (hasNotify) {
            interactionHandler.notifyProtection(player, protection);
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
            return;
        }
        // Deny the break attempt up front (left-click / dig start), while the block is still
        // present so double-chest matching against the live world works reliably.
        final Protection protection = plugin.findProtection(location.get());
        if (protection != null && !plugin.canAccess(protection, player, Permission.DESTROY)) {
            event.setCancelled(true);
            BoltComponents.sendMessage(player, Translation.LOCKED, plugin.isUseActionBar(),
                    Placeholder.of(Translation.Placeholder.PROTECTION, Protections.displayType(protection)));
        }
    }

    @Listener
    public void onBreak(final ChangeBlockEvent.Break event) {
        final Optional<Player> player = event.getCause().first(Player.class);
        for (final Transaction<BlockSnapshot> transaction : event.getTransactions()) {
            final BlockSnapshot original = transaction.getOriginal();
            final Optional<Location<World>> location = original.getLocation();
            if (!location.isPresent()) {
                continue;
            }
            // Use the transaction's ORIGINAL block type: mid-break the live world may already read
            // as air, which would defeat live-world-based double-chest matching.
            final Optional<Location<World>> partner = Matchers.chestPartner(location.get(), original.getState().getType());
            final BlockProtection exact = plugin.loadProtection(location.get());
            final Protection protection = exact != null
                    ? exact
                    : partner.map(partnerLocation -> plugin.loadProtection(partnerLocation)).orElse(null);
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
                if (partner.isPresent()) {
                    // Double chest: migrate the protection to the surviving half instead of removing
                    // it (matches Bukkit's behavior), silently.
                    final Location<World> target = partner.get();
                    exact.setX(target.getBlockX());
                    exact.setY(target.getBlockY());
                    exact.setZ(target.getBlockZ());
                    plugin.saveProtection(exact);
                } else {
                    plugin.removeProtection(exact);
                    if (player.isPresent() && !plugin.player(player.get()).hasMode(Mode.NOSPAM)) {
                        BoltComponents.sendMessage(player.get(), Translation.CLICK_UNLOCKED, plugin.isUseActionBar(),
                                Placeholder.of(Translation.Placeholder.PROTECTION_TYPE, Protections.protectionType(exact)),
                                Placeholder.of(Translation.Placeholder.PROTECTION, Protections.displayType(exact)));
                    }
                }
            }
        }
    }

    @Listener
    public void onPlace(final ChangeBlockEvent.Place event) {
        final Optional<Player> player = event.getCause().first(Player.class);
        if (!player.isPresent()) {
            return;
        }
        final BoltPlayer boltPlayer = plugin.player(player.get());
        if (boltPlayer.hasMode(Mode.NOLOCK)) {
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
            if (!boltPlayer.hasMode(Mode.NOSPAM)) {
                BoltComponents.sendMessage(player.get(), Translation.CLICK_LOCKED, plugin.isUseActionBar(),
                        Placeholder.of(Translation.Placeholder.PROTECTION_TYPE, protectionType),
                        Placeholder.of(Translation.Placeholder.PROTECTION, Protections.displayType(type.getId())));
            }
        }
    }

    @Listener
    public void onExplosion(final ExplosionEvent.Detonate event) {
        event.getAffectedLocations().removeIf(plugin::isProtected);
    }

    /**
     * Environmental protection: cancels non-player world changes (pistons, fluids, fire spread)
     * that would affect a protected block, unless the protection type inherently allows redstone
     * (so a private lock - which permits redstone - still opens via redstone). Player-caused
     * changes are handled by the break/place/interact listeners above.
     */
    @Listener
    public void onPreChange(final ChangeBlockEvent.Pre event) {
        if (event.getCause().first(Player.class).isPresent()) {
            return;
        }
        for (final Location<World> location : event.getLocations()) {
            final Protection protection = plugin.findProtection(location);
            if (protection != null && !plugin.protectionTypeAllows(protection, Permission.REDSTONE)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Redstone gating (mirrors Bukkit's {@code onBlockRedstone}): stops a redstone update from
     * reaching a protected block that doesn't allow redstone - e.g. a lever wired to someone
     * else's private door won't open it. The notifying block is the {@link LocatableBlock} in the
     * cause; each notified {@link org.spongepowered.api.util.Direction} points at a neighbor, which
     * we drop from the update if its protection denies the {@code redstone} source.
     */
    @Listener
    public void onNotifyNeighbor(final NotifyNeighborBlockEvent event) {
        final Optional<LocatableBlock> notifier = event.getCause().first(LocatableBlock.class);
        if (!notifier.isPresent()) {
            return;
        }
        final Location<World> source = notifier.get().getLocation();
        event.filterDirections(direction -> {
            final Protection protection = plugin.findProtection(source.getBlockRelative(direction));
            return protection == null || plugin.canAccess(protection, REDSTONE_SOURCE_RESOLVER, Permission.REDSTONE);
        });
    }

    /**
     * Prevents editing the text of a protected sign (mirrors Bukkit's {@code onSignChange}).
     */
    @Listener
    public void onChangeSign(final ChangeSignEvent event, @First final Player player) {
        final Protection protection = plugin.findProtection(event.getTargetTile().getLocation());
        if (protection != null && !plugin.canAccess(protection, player, Permission.INTERACT)) {
            event.setCancelled(true);
            BoltComponents.sendMessage(player, Translation.LOCKED, plugin.isUseActionBar(),
                    Placeholder.of(Translation.Placeholder.PROTECTION, Protections.displayType(protection)));
        }
    }
}
