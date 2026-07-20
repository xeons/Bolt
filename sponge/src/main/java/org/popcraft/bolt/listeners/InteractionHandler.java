package org.popcraft.bolt.listeners;

import org.popcraft.bolt.BoltPlugin;
import org.popcraft.bolt.access.Access;
import org.popcraft.bolt.lang.Translation;
import org.popcraft.bolt.protection.Protection;
import org.popcraft.bolt.util.Action;
import org.popcraft.bolt.util.BoltComponents;
import org.popcraft.bolt.util.BoltPlayer;
import org.popcraft.bolt.util.Permission;
import org.popcraft.bolt.util.Placeholder;
import org.popcraft.bolt.util.ProtectableConfig;
import org.popcraft.bolt.util.Protections;
import org.popcraft.bolt.util.Time;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.service.user.UserStorageService;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import java.util.Optional;
import java.util.UUID;

/**
 * Consumes a {@link BoltPlayer}'s pending {@link Action} when they click a block or entity —
 * the heart of Bolt's lock/unlock/info/trust UX. Ported from the Bukkit
 * {@code InteractionListener}, trimmed to the MVP action set (LOCK/UNLOCK/INFO/EDIT) and with
 * the addon event bus omitted.
 */
public class InteractionHandler {
    private final BoltPlugin plugin;

    public InteractionHandler(final BoltPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean triggerBlock(final Player player, final Location<World> location) {
        final Protection protection = plugin.findProtection(location);
        final boolean protectable = plugin.isProtectable(location);
        final ProtectableConfig config = plugin.getProtectableConfig(location);
        final String display = Protections.displayType(location.getBlockType().getId());
        final String lockPermission = "bolt.protection.lock." + shortId(location.getBlockType().getId());
        return trigger(player, protection, location, null, protectable, config, display, lockPermission);
    }

    public boolean triggerEntity(final Player player, final Entity entity) {
        final Protection protection = plugin.findProtection(entity);
        final boolean protectable = plugin.isProtectable(entity);
        final ProtectableConfig config = plugin.getProtectableConfig(entity);
        final String display = Protections.displayType(entity.getType().getId());
        final String lockPermission = "bolt.protection.lock." + shortId(entity.getType().getId());
        return trigger(player, protection, null, entity, protectable, config, display, lockPermission);
    }

    private boolean trigger(final Player player, final Protection protection, final Location<World> location,
                            final Entity entity, final boolean protectable, final ProtectableConfig config,
                            final String display, final String lockPermission) {
        final BoltPlayer boltPlayer = plugin.player(player);
        final Action action = boltPlayer.getAction();
        if (action == null) {
            return false;
        }
        if (!player.hasPermission(action.getPermission())) {
            BoltComponents.sendMessage(player, Translation.COMMAND_NO_PERMISSION);
            return false;
        }
        switch (action.getType()) {
            case LOCK:
                handleLock(player, boltPlayer, action, protection, location, entity, protectable, config, display, lockPermission);
                break;
            case UNLOCK:
                handleUnlock(player, protection, display);
                break;
            case INFO:
                handleInfo(player, protection, display);
                break;
            case EDIT:
                handleEdit(player, boltPlayer, action, protection, display);
                break;
            case TRANSFER:
                handleTransfer(player, action, protection, display);
                break;
            case DEBUG:
                handleDebug(player, protection);
                break;
            default:
                break;
        }
        boltPlayer.clearAction();
        return true;
    }

    private void handleLock(final Player player, final BoltPlayer boltPlayer, final Action action,
                            final Protection protection, final Location<World> location, final Entity entity,
                            final boolean protectable, final ProtectableConfig config, final String display,
                            final String lockPermission) {
        String protectionType = plugin.getDefaultProtectionType();
        if (action.getData() != null) {
            final Optional<Access> byType = plugin.getBolt().getAccessRegistry().getProtectionByType(action.getData());
            if (byType.isPresent()) {
                protectionType = byType.get().type();
            }
        }
        final boolean requiresLockPermission = config != null && config.lockPermission();
        if (protection != null) {
            if (!protection.getType().equals(protectionType) && plugin.canAccess(protection, player, Permission.EDIT)) {
                protection.setType(protectionType);
                plugin.saveProtection(protection);
                BoltComponents.sendMessage(player, Translation.CLICK_LOCKED_CHANGED, plugin.isUseActionBar(),
                        Placeholder.of(Translation.Placeholder.PROTECTION_TYPE, Protections.protectionType(protection)));
            } else {
                BoltComponents.sendMessage(player, Translation.CLICK_LOCKED_ALREADY, plugin.isUseActionBar(),
                        Placeholder.of(Translation.Placeholder.PROTECTION, display));
            }
        } else if ((protectable || action.isAdmin()) && (!requiresLockPermission || player.hasPermission(lockPermission))) {
            final UUID owner = player.getUniqueId();
            final Protection newProtection = location != null
                    ? plugin.createProtection(location, owner, protectionType)
                    : plugin.createProtection(entity, owner, protectionType);
            plugin.saveProtection(newProtection);
            BoltComponents.sendMessage(player, Translation.CLICK_LOCKED, plugin.isUseActionBar(),
                    Placeholder.of(Translation.Placeholder.PROTECTION_TYPE, Protections.protectionType(newProtection)),
                    Placeholder.of(Translation.Placeholder.PROTECTION, display));
        } else {
            BoltComponents.sendMessage(player, Translation.CLICK_NOT_LOCKABLE, plugin.isUseActionBar(),
                    Placeholder.of(Translation.Placeholder.PROTECTION, display));
        }
    }

    private void handleUnlock(final Player player, final Protection protection, final String display) {
        if (protection != null) {
            if (plugin.canAccess(protection, player, Permission.DESTROY)) {
                plugin.removeProtection(protection);
                BoltComponents.sendMessage(player, Translation.CLICK_UNLOCKED, plugin.isUseActionBar(),
                        Placeholder.of(Translation.Placeholder.PROTECTION_TYPE, Protections.protectionType(protection)),
                        Placeholder.of(Translation.Placeholder.PROTECTION, Protections.displayType(protection)));
            } else {
                BoltComponents.sendMessage(player, Translation.CLICK_UNLOCKED_NO_PERMISSION, plugin.isUseActionBar());
            }
        } else {
            BoltComponents.sendMessage(player, Translation.CLICK_NOT_LOCKED, plugin.isUseActionBar(),
                    Placeholder.of(Translation.Placeholder.PROTECTION, display));
        }
    }

    private void handleInfo(final Player player, final Protection protection, final String display) {
        if (protection != null) {
            final boolean showFull = protection.getOwner().equals(player.getUniqueId()) || player.hasPermission("bolt.command.info.full");
            final boolean showAccessList = !protection.getAccess().isEmpty();
            final String ownerName = ownerName(protection.getOwner());
            final String key = showFull
                    ? (showAccessList ? Translation.INFO_FULL_ACCESS : Translation.INFO_FULL_NO_ACCESS)
                    : Translation.INFO;
            BoltComponents.sendMessage(player, key,
                    Placeholder.of(Translation.Placeholder.PROTECTION_TYPE, Protections.protectionType(protection)),
                    Placeholder.of(Translation.Placeholder.PROTECTION, Protections.displayType(protection)),
                    Placeholder.of(Translation.Placeholder.PLAYER, ownerName != null ? ownerName
                            : BoltComponents.translateRaw(Translation.UNKNOWN, player)),
                    Placeholder.of(Translation.Placeholder.ACCESS_LIST_SIZE, String.valueOf(protection.getAccess().size())),
                    Placeholder.of(Translation.Placeholder.ACCESS_LIST, Protections.accessList(protection.getAccess(), plugin, player)),
                    Placeholder.of(Translation.Placeholder.CREATED_TIME, Time.relativeTimestamp(protection.getCreated(), player)),
                    Placeholder.of(Translation.Placeholder.ACCESSED_TIME, Time.relativeTimestamp(protection.getAccessed(), player)));
        } else {
            BoltComponents.sendMessage(player, Translation.CLICK_NOT_LOCKED, plugin.isUseActionBar(),
                    Placeholder.of(Translation.Placeholder.PROTECTION, display));
        }
    }

    private void handleEdit(final Player player, final BoltPlayer boltPlayer, final Action action,
                            final Protection protection, final String display) {
        if (protection != null) {
            if (plugin.canAccess(protection, player, Permission.EDIT)) {
                final boolean adding = Boolean.parseBoolean(action.getData());
                boltPlayer.consumeModifications().forEach((source, type) -> {
                    if (adding) {
                        protection.getAccess().put(source.toString(), type);
                    } else {
                        protection.getAccess().remove(source.toString());
                    }
                });
                plugin.saveProtection(protection);
                BoltComponents.sendMessage(player, Translation.CLICK_EDITED, plugin.isUseActionBar(),
                        Placeholder.of(Translation.Placeholder.PROTECTION_TYPE, Protections.protectionType(protection)),
                        Placeholder.of(Translation.Placeholder.PROTECTION, Protections.displayType(protection)));
            } else {
                BoltComponents.sendMessage(player, Translation.CLICK_EDITED_NO_PERMISSION, plugin.isUseActionBar());
            }
        } else {
            BoltComponents.sendMessage(player, Translation.CLICK_NOT_LOCKED, plugin.isUseActionBar(),
                    Placeholder.of(Translation.Placeholder.PROTECTION, display));
        }
    }

    private void handleTransfer(final Player player, final Action action, final Protection protection, final String display) {
        if (protection != null) {
            if (player.getUniqueId().equals(protection.getOwner()) || action.isAdmin()) {
                final UUID uuid = UUID.fromString(action.getData());
                protection.setOwner(uuid);
                plugin.saveProtection(protection);
                final String name = ownerName(uuid);
                BoltComponents.sendMessage(player, Translation.CLICK_TRANSFER_CONFIRM, plugin.isUseActionBar(),
                        Placeholder.of(Translation.Placeholder.PROTECTION_TYPE, Protections.protectionType(protection)),
                        Placeholder.of(Translation.Placeholder.PROTECTION, Protections.displayType(protection)),
                        Placeholder.of(Translation.Placeholder.PLAYER, name != null ? name
                                : BoltComponents.translateRaw(Translation.UNKNOWN, player)));
            } else {
                BoltComponents.sendMessage(player, Translation.CLICK_EDITED_NO_OWNER, plugin.isUseActionBar());
            }
        } else {
            BoltComponents.sendMessage(player, Translation.CLICK_NOT_LOCKED, plugin.isUseActionBar(),
                    Placeholder.of(Translation.Placeholder.PROTECTION, display));
        }
    }

    private void handleDebug(final Player player, final Protection protection) {
        player.sendMessage(Text.of(protection == null ? "No protection here." : protection.toString()));
    }

    private String ownerName(final UUID owner) {
        final Optional<Player> online = Sponge.getServer().getPlayer(owner);
        if (online.isPresent()) {
            return online.get().getName();
        }
        final Optional<UserStorageService> service = Sponge.getServiceManager().provide(UserStorageService.class);
        if (service.isPresent()) {
            final Optional<User> user = service.get().get(owner);
            if (user.isPresent()) {
                return user.get().getName();
            }
        }
        return null;
    }

    private static String shortId(final String catalogId) {
        final int colon = catalogId.indexOf(':');
        return colon >= 0 ? catalogId.substring(colon + 1) : catalogId;
    }
}
