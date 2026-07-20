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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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

    private static final String[] SUBCOMMANDS = {"lock", "unlock", "info", "trust", "password", "admin"};

    private interface Handler {
        void handle(BoltPlugin plugin, CommandSource source, Arguments arguments);
    }

    private interface Suggester {
        List<String> suggest(BoltPlugin plugin, CommandSource source, String raw);
    }

    private static final Suggester NO_SUGGESTIONS = (plugin, source, raw) -> Collections.emptyList();

    public static void register(final BoltPlugin plugin) {
        final CommandManager commandManager = Sponge.getCommandManager();
        commandManager.register(plugin, callable(plugin, "bolt.command", BoltCommands::dispatch, BoltCommands::suggestRoot), "bolt");
        commandManager.register(plugin, callable(plugin, "bolt.command.lock", BoltCommands::lock, (p, s, raw) -> suggestArgs(p, s, "lock", raw)), "lock");
        commandManager.register(plugin, callable(plugin, "bolt.command.unlock", BoltCommands::unlock, NO_SUGGESTIONS), "unlock");
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
            case "admin":
                AdminCommands.handle(plugin, source, arguments);
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
    static String transformSource(final String sourceType, final String identifier) {
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

    // --- Tab completion ---------------------------------------------------------------------

    private static List<String> suggestRoot(final BoltPlugin plugin, final CommandSource source, final String raw) {
        final String[] tokens = tokenize(raw);
        if (tokens.length <= 1) {
            final String partial = tokens.length == 0 ? "" : tokens[0];
            final List<String> subs = new ArrayList<>();
            for (final String sub : SUBCOMMANDS) {
                if (source.hasPermission("bolt.command." + sub)) {
                    subs.add(sub);
                }
            }
            return filter(subs, partial);
        }
        return suggestArgs(plugin, source, tokens[0].toLowerCase(), afterFirstToken(raw));
    }

    private static List<String> suggestArgs(final BoltPlugin plugin, final CommandSource source, final String command, final String raw) {
        String[] tokens = tokenize(raw);
        if (tokens.length == 0) {
            tokens = new String[]{""};
        }
        final int argIndex = tokens.length - 1;
        final String partial = tokens[argIndex];
        if ("lock".equals(command)) {
            if (argIndex == 0) {
                return filter(protectionTypeNames(plugin, source), partial);
            }
        } else if ("trust".equals(command)) {
            if (argIndex == 0) {
                return filter(sourceTypeNames(plugin, source), partial);
            } else if (argIndex == 1) {
                if (SourceTypes.PLAYER.equalsIgnoreCase(tokens[0])) {
                    return filter(onlinePlayerNames(), partial);
                }
            } else if (argIndex == 2) {
                return filter(accessTypeNames(plugin, source), partial);
            }
        }
        return Collections.emptyList();
    }

    private static String[] tokenize(final String raw) {
        if (raw == null) {
            return new String[0];
        }
        final boolean trailing = !raw.isEmpty() && Character.isWhitespace(raw.charAt(raw.length() - 1));
        final String trimmed = raw.trim();
        final String[] base = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        if (trailing) {
            final String[] withPartial = new String[base.length + 1];
            System.arraycopy(base, 0, withPartial, 0, base.length);
            withPartial[base.length] = "";
            return withPartial;
        }
        return base;
    }

    private static String afterFirstToken(final String raw) {
        final String s = raw == null ? "" : raw;
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(Math.min(i, s.length()));
    }

    private static List<String> filter(final Collection<String> options, final String partial) {
        final String prefix = partial.toLowerCase(Locale.ROOT);
        final List<String> result = new ArrayList<>();
        for (final String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(option);
            }
        }
        Collections.sort(result);
        return result;
    }

    private static List<String> protectionTypeNames(final BoltPlugin plugin, final CommandSource source) {
        final List<String> names = new ArrayList<>();
        for (final Access access : plugin.getBolt().getAccessRegistry().protections()) {
            if (!access.restricted() || source.hasPermission("bolt.type.protection." + access.type())) {
                names.add(access.type());
            }
        }
        return names;
    }

    private static List<String> accessTypeNames(final BoltPlugin plugin, final CommandSource source) {
        final List<String> names = new ArrayList<>();
        for (final Access access : plugin.getBolt().getAccessRegistry().access()) {
            if (!access.restricted() || source.hasPermission("bolt.type.access." + access.type())) {
                names.add(access.type());
            }
        }
        return names;
    }

    private static List<String> sourceTypeNames(final BoltPlugin plugin, final CommandSource source) {
        final List<String> names = new ArrayList<>();
        for (final SourceType sourceType : plugin.getBolt().getSourceTypeRegistry().sourceTypes()) {
            if (!sourceType.restricted() || source.hasPermission("bolt.type.source." + sourceType.name())) {
                names.add(sourceType.name());
            }
        }
        return names;
    }

    private static List<String> onlinePlayerNames() {
        final List<String> names = new ArrayList<>();
        for (final Player player : Sponge.getServer().getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    static UUID resolvePlayer(final String name) {
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

    private static CommandCallable callable(final BoltPlugin plugin, final String permission, final Handler handler, final Suggester suggester) {
        return new CommandCallable() {
            @Override
            public CommandResult process(final CommandSource source, final String arguments) {
                handler.handle(plugin, source, Arguments.fromString(arguments));
                return CommandResult.success();
            }

            @Override
            public List<String> getSuggestions(final CommandSource source, final String arguments, @Nullable final Location<World> targetPosition) {
                return suggester.suggest(plugin, source, arguments);
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
