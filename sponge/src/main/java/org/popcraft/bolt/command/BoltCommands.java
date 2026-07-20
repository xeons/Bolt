package org.popcraft.bolt.command;

import org.popcraft.bolt.BoltPlugin;
import org.popcraft.bolt.access.Access;
import org.popcraft.bolt.lang.Translation;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceType;
import org.popcraft.bolt.source.SourceTypes;
import org.popcraft.bolt.util.Action;
import org.popcraft.bolt.util.BoltComponents;
import org.popcraft.bolt.util.BoltPlayer;
import org.popcraft.bolt.util.Placeholder;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandCallable;
import org.spongepowered.api.command.CommandManager;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.service.user.UserStorageService;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Registers and implements Bolt's MVP commands on SpongeAPI 7.4. Command bodies mirror the Bukkit
 * implementations: they set a pending {@link Action} on the {@link BoltPlayer} which the next
 * block/entity click consumes (see {@code InteractionHandler}).
 */
public final class BoltCommands {
    private BoltCommands() {
    }

    private interface Handler {
        void handle(BoltPlugin plugin, CommandSource source, Arguments arguments);
    }

    public static void register(final BoltPlugin plugin) {
        final CommandManager commandManager = Sponge.getCommandManager();
        commandManager.register(plugin, callable(plugin, "bolt.command", BoltCommands::dispatch), "bolt");
        commandManager.register(plugin, callable(plugin, "bolt.command.lock", BoltCommands::lock), "lock");
        commandManager.register(plugin, callable(plugin, "bolt.command.unlock", BoltCommands::unlock), "unlock");
    }

    private static void dispatch(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        final String sub = arguments.next();
        if (sub == null) {
            BoltComponents.sendMessage(source, Translation.HELP_COMMAND_SHORT_HELP,
                    Placeholder.of(Translation.Placeholder.COMMAND, "/bolt"));
            return;
        }
        final String key = sub.toLowerCase();
        if (!source.hasPermission("bolt.command." + key)) {
            BoltComponents.sendMessage(source, Translation.COMMAND_NO_PERMISSION);
            return;
        }
        switch (key) {
            case "lock":
                lock(plugin, source, arguments);
                break;
            case "unlock":
                unlock(plugin, source, arguments);
                break;
            case "info":
                info(plugin, source, arguments);
                break;
            case "trust":
                trust(plugin, source, arguments);
                break;
            case "password":
                password(plugin, source, arguments);
                break;
            default:
                BoltComponents.sendMessage(source, Translation.COMMAND_INVALID);
                break;
        }
    }

    private static void lock(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        if (!(source instanceof Player)) {
            BoltComponents.sendMessage(source, Translation.COMMAND_PLAYER_ONLY);
            return;
        }
        final Player player = (Player) source;
        final BoltPlayer boltPlayer = plugin.player(player);
        final String argument = arguments.next();
        final String type = argument == null ? plugin.getDefaultProtectionType() : argument.toLowerCase();
        final Access access = plugin.getBolt().getAccessRegistry().getProtectionByType(type).orElse(null);
        if (access == null) {
            BoltComponents.sendMessage(source, Translation.CLICK_LOCKED_NO_EXIST,
                    Placeholder.of(Translation.Placeholder.PROTECTION_TYPE, type));
            return;
        }
        if (access.restricted() && !source.hasPermission("bolt.type.protection." + access.type())) {
            BoltComponents.sendMessage(source, Translation.CLICK_LOCKED_NO_PERMISSION);
            return;
        }
        boltPlayer.setAction(new Action(Action.Type.LOCK, "bolt.command.lock", type, false));
        BoltComponents.sendMessage(player, Translation.CLICK_ACTION, plugin.isUseActionBar(),
                Placeholder.of(Translation.Placeholder.ACTION, BoltComponents.translateRaw(Translation.LOCK, player)));
    }

    private static void unlock(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        if (!(source instanceof Player)) {
            BoltComponents.sendMessage(source, Translation.COMMAND_PLAYER_ONLY);
            return;
        }
        final Player player = (Player) source;
        plugin.player(player).setAction(new Action(Action.Type.UNLOCK, "bolt.command.unlock"));
        BoltComponents.sendMessage(player, Translation.CLICK_ACTION, plugin.isUseActionBar(),
                Placeholder.of(Translation.Placeholder.ACTION, BoltComponents.translateRaw(Translation.UNLOCK, player)));
    }

    private static void info(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        if (!(source instanceof Player)) {
            BoltComponents.sendMessage(source, Translation.COMMAND_PLAYER_ONLY);
            return;
        }
        final Player player = (Player) source;
        plugin.player(player).setAction(new Action(Action.Type.INFO, "bolt.command.info"));
        BoltComponents.sendMessage(player, Translation.CLICK_INFO, plugin.isUseActionBar());
    }

    /**
     * {@code /bolt trust <sourceType> <identifier> [accessType]} — stages a source→access
     * modification and sets an EDIT action; the next click applies it to that protection. Supports
     * player, password, and group source types.
     */
    private static void trust(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        if (!(source instanceof Player)) {
            BoltComponents.sendMessage(source, Translation.COMMAND_PLAYER_ONLY);
            return;
        }
        final Player player = (Player) source;
        final String sourceTypeArg = arguments.next();
        if (sourceTypeArg == null) {
            BoltComponents.sendMessage(source, Translation.HELP_COMMAND_SHORT_TRUST,
                    Placeholder.of(Translation.Placeholder.COMMAND, "/bolt trust"),
                    Placeholder.of(Translation.Placeholder.LITERAL, "<sourceType> <identifier>"));
            return;
        }
        final String sourceTypeName = sourceTypeArg.toLowerCase();
        final SourceType sourceType = plugin.getBolt().getSourceTypeRegistry().getSourceByName(sourceTypeName).orElse(null);
        if (sourceType == null) {
            BoltComponents.sendMessage(source, Translation.EDIT_SOURCE_INVALID,
                    Placeholder.of(Translation.Placeholder.SOURCE_TYPE, sourceTypeName));
            return;
        }
        if (sourceType.restricted() && !source.hasPermission("bolt.type.source." + sourceType.name())) {
            BoltComponents.sendMessage(source, Translation.EDIT_SOURCE_NO_PERMISSION);
            return;
        }
        final String identifier = arguments.next();
        if (identifier == null) {
            BoltComponents.sendMessage(source, Translation.HELP_COMMAND_SHORT_TRUST,
                    Placeholder.of(Translation.Placeholder.COMMAND, "/bolt trust"),
                    Placeholder.of(Translation.Placeholder.LITERAL, sourceTypeName + " <identifier>"));
            return;
        }
        final String accessTypeName = Optional.ofNullable(arguments.next()).orElse(plugin.getDefaultAccessType()).toLowerCase();
        final Access access = plugin.getBolt().getAccessRegistry().getAccessByType(accessTypeName).orElse(null);
        if (access == null) {
            BoltComponents.sendMessage(source, Translation.EDIT_ACCESS_INVALID,
                    Placeholder.of(Translation.Placeholder.ACCESS_TYPE, accessTypeName));
            return;
        }
        if (access.restricted() && !source.hasPermission("bolt.type.access." + access.type())) {
            BoltComponents.sendMessage(source, Translation.EDIT_ACCESS_NO_PERMISSION);
            return;
        }
        final String transformed = transformSource(sourceType.name(), identifier);
        if (transformed == null) {
            BoltComponents.sendMessage(source, Translation.GENERIC_NOT_FOUND,
                    Placeholder.of(Translation.Placeholder.X, identifier));
            return;
        }
        final BoltPlayer boltPlayer = plugin.player(player);
        boltPlayer.getModifications().put(Source.of(sourceType.name(), transformed), access.type());
        boltPlayer.setAction(new Action(Action.Type.EDIT, "bolt.command.edit", "true"));
        BoltComponents.sendMessage(player, Translation.CLICK_ACTION, plugin.isUseActionBar(),
                Placeholder.of(Translation.Placeholder.ACTION, BoltComponents.translateRaw(Translation.EDIT, player)));
    }

    /**
     * {@code /bolt password <password>} — registers a password on the player's session so they can
     * access password-protected protections until they disconnect.
     */
    private static void password(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        if (!(source instanceof Player)) {
            BoltComponents.sendMessage(source, Translation.COMMAND_PLAYER_ONLY);
            return;
        }
        final Player player = (Player) source;
        if (arguments.remaining() > 0) {
            plugin.player(player).addPassword(arguments.next());
            BoltComponents.sendMessage(player, Translation.ENTER_PASSWORD);
        } else {
            BoltComponents.sendMessage(player, Translation.ENTER_PASSWORD_NONE);
        }
    }

    /** Converts user-facing identifiers into the stored source identifier for each source type. */
    private static String transformSource(final String sourceType, final String identifier) {
        if (SourceTypes.PLAYER.equals(sourceType)) {
            final UUID uuid = resolvePlayer(identifier);
            return uuid == null ? null : uuid.toString();
        }
        if (SourceTypes.PASSWORD.equals(sourceType)) {
            final Source password = Source.password(identifier);
            return password == null ? null : password.getIdentifier();
        }
        return identifier;
    }

    private static UUID resolvePlayer(final String name) {
        final Optional<Player> online = Sponge.getServer().getPlayer(name);
        if (online.isPresent()) {
            return online.get().getUniqueId();
        }
        final Optional<UserStorageService> service = Sponge.getServiceManager().provide(UserStorageService.class);
        if (service.isPresent()) {
            final Optional<User> user = service.get().get(name);
            if (user.isPresent()) {
                return user.get().getUniqueId();
            }
        }
        return null;
    }

    private static CommandCallable callable(final BoltPlugin plugin, final String permission, final Handler handler) {
        return new CommandCallable() {
            @Override
            public CommandResult process(final CommandSource source, final String arguments) {
                handler.handle(plugin, source, Arguments.fromString(arguments));
                return CommandResult.success();
            }

            @Override
            public List<String> getSuggestions(final CommandSource source, final String arguments, @Nullable final Location<World> targetPosition) {
                return Collections.emptyList();
            }

            @Override
            public boolean testPermission(final CommandSource source) {
                return source.hasPermission(permission);
            }

            @Override
            public Optional<Text> getShortDescription(final CommandSource source) {
                return Optional.empty();
            }

            @Override
            public Optional<Text> getHelp(final CommandSource source) {
                return Optional.empty();
            }

            @Override
            public Text getUsage(final CommandSource source) {
                return Text.of("/bolt");
            }
        };
    }
}
