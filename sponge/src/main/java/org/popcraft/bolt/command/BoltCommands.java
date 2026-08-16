package org.popcraft.bolt.command;

import org.popcraft.bolt.BoltPlugin;
import org.popcraft.bolt.access.Access;
import org.popcraft.bolt.access.AccessList;
import org.popcraft.bolt.data.Store;
import org.popcraft.bolt.lang.Translation;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceType;
import org.popcraft.bolt.source.SourceTypes;
import org.popcraft.bolt.util.Action;
import org.popcraft.bolt.util.BoltComponents;
import org.popcraft.bolt.util.BoltPlayer;
import org.popcraft.bolt.util.Group;
import org.popcraft.bolt.util.Mode;
import org.popcraft.bolt.util.Placeholder;
import org.popcraft.bolt.util.Protections;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandCallable;
import org.spongepowered.api.command.CommandManager;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.service.user.UserStorageService;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final String[] SUBCOMMANDS = {"lock", "unlock", "info", "trust", "edit", "modify", "group", "mode", "password", "transfer", "help", "admin"};

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
            case "edit":
                edit(plugin, source, arguments);
                break;
            case "modify":
                modify(plugin, source, arguments);
                break;
            case "group":
                group(plugin, source, arguments);
                break;
            case "mode":
                mode(plugin, source, arguments);
                break;
            case "help":
                help(plugin, source, arguments);
                break;
            case "password":
                password(plugin, source, arguments);
                break;
            case "transfer":
                transfer(plugin, source, arguments);
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
        // "/lock <type> force" lets an admin lock non-protectable blocks (marks the action admin).
        final boolean force = "force".equalsIgnoreCase(arguments.next()) && source.hasPermission("bolt.admin");
        boltPlayer.setAction(new Action(Action.Type.LOCK, "bolt.command.lock", type, force));
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
     * {@code /bolt trust [add|remove <sourceType> <identifier> [accessType]]} - edits the sender's
     * global access list (applies to all of their protections). With no add/remove, lists the
     * sender's access list. Matches Bukkit's TrustCommand.
     */
    private static void trust(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        if (!(source instanceof Player)) {
            BoltComponents.sendMessage(source, Translation.COMMAND_PLAYER_ONLY);
            return;
        }
        final Player player = (Player) source;
        final String action = arguments.next();
        if ("add".equalsIgnoreCase(action) || "remove".equalsIgnoreCase(action)) {
            if (arguments.remaining() < 2) {
                BoltComponents.sendMessage(source, Translation.HELP_COMMAND_SHORT_TRUST,
                        Placeholder.of(Translation.Placeholder.COMMAND, "/bolt trust"),
                        Placeholder.of(Translation.Placeholder.LITERAL, "(add|remove)"));
                return;
            }
            trustModify(plugin, source, player.getUniqueId(), "add".equalsIgnoreCase(action), arguments);
        } else {
            trustList(plugin, source, player.getUniqueId());
        }
    }

    /**
     * {@code /bolt edit <add|remove> <player>} - click-based; grants/revokes a player on the next
     * protection clicked (default access type). Matches Bukkit's EditCommand.
     */
    private static void edit(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        if (!(source instanceof Player)) {
            BoltComponents.sendMessage(source, Translation.COMMAND_PLAYER_ONLY);
            return;
        }
        final Player player = (Player) source;
        if (arguments.remaining() < 2) {
            BoltComponents.sendMessage(source, Translation.HELP_COMMAND_SHORT_EDIT,
                    Placeholder.of(Translation.Placeholder.COMMAND, "/bolt edit"),
                    Placeholder.of(Translation.Placeholder.LITERAL, "(add|remove)"));
            return;
        }
        final boolean adding = "add".equalsIgnoreCase(arguments.next());
        final String target = arguments.next();
        final UUID uuid = resolvePlayer(target);
        if (uuid == null) {
            BoltComponents.sendMessage(source, Translation.PLAYER_NOT_FOUND, Placeholder.of(Translation.Placeholder.PLAYER, target));
            return;
        }
        final BoltPlayer boltPlayer = plugin.player(player);
        boltPlayer.setAction(new Action(Action.Type.EDIT, "bolt.command.edit", Boolean.toString(adding)));
        boltPlayer.getModifications().put(Source.player(uuid), plugin.getDefaultAccessType());
        BoltComponents.sendMessage(player, Translation.CLICK_ACTION, plugin.isUseActionBar(),
                Placeholder.of(Translation.Placeholder.ACTION, BoltComponents.translateRaw(Translation.EDIT, player)));
    }

    /**
     * {@code /bolt modify <add|remove> <accessType> <sourceType> <identifier...>} - click-based;
     * stages one or more source→access modifications applied to the next protection clicked.
     * Matches Bukkit's ModifyCommand.
     */
    private static void modify(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        if (!(source instanceof Player)) {
            BoltComponents.sendMessage(source, Translation.COMMAND_PLAYER_ONLY);
            return;
        }
        final Player player = (Player) source;
        if (arguments.remaining() < 3) {
            BoltComponents.sendMessage(source, Translation.HELP_COMMAND_SHORT_MODIFY,
                    Placeholder.of(Translation.Placeholder.COMMAND, "/bolt modify"),
                    Placeholder.of(Translation.Placeholder.LITERAL, "(add|remove)"));
            return;
        }
        final BoltPlayer boltPlayer = plugin.player(player);
        final boolean adding = "add".equalsIgnoreCase(arguments.next());
        final String accessTypeName = arguments.next().toLowerCase();
        final Access access = plugin.getBolt().getAccessRegistry().getAccessByType(accessTypeName).orElse(null);
        if (access == null) {
            BoltComponents.sendMessage(source, Translation.EDIT_ACCESS_INVALID, Placeholder.of(Translation.Placeholder.ACCESS_TYPE, accessTypeName));
            return;
        }
        if (access.restricted() && !source.hasPermission("bolt.type.access." + access.type())) {
            BoltComponents.sendMessage(source, Translation.EDIT_ACCESS_NO_PERMISSION);
            return;
        }
        final String sourceTypeName = arguments.next().toLowerCase();
        final SourceType sourceType = plugin.getBolt().getSourceTypeRegistry().getSourceByName(sourceTypeName).orElse(null);
        if (sourceType == null) {
            BoltComponents.sendMessage(source, Translation.EDIT_SOURCE_INVALID, Placeholder.of(Translation.Placeholder.SOURCE_TYPE, sourceTypeName));
            return;
        }
        if (sourceType.restricted() && !source.hasPermission("bolt.type.source." + sourceType.name())) {
            BoltComponents.sendMessage(source, Translation.EDIT_SOURCE_NO_PERMISSION);
            return;
        }
        final List<String> identifiers = new ArrayList<>();
        if (sourceType.unique()) {
            identifiers.add(sourceType.name());
        } else {
            if (arguments.remaining() < 1) {
                BoltComponents.sendMessage(source, Translation.HELP_COMMAND_SHORT_MODIFY,
                        Placeholder.of(Translation.Placeholder.COMMAND, "/bolt modify"),
                        Placeholder.of(Translation.Placeholder.LITERAL, "(add|remove)"));
                return;
            }
            String identifier;
            while ((identifier = arguments.next()) != null) {
                identifiers.add(identifier);
            }
        }
        for (final String identifier : identifiers) {
            final String transformed = transformSource(sourceType.name(), identifier);
            if (transformed == null) {
                BoltComponents.sendMessage(source, Translation.GENERIC_NOT_FOUND, Placeholder.of(Translation.Placeholder.X, identifier));
                continue;
            }
            boltPlayer.getModifications().put(Source.of(sourceType.name(), transformed), access.type());
        }
        if (boltPlayer.getModifications().isEmpty()) {
            return;
        }
        boltPlayer.setAction(new Action(Action.Type.EDIT, "bolt.command.edit", Boolean.toString(adding)));
        BoltComponents.sendMessage(player, Translation.CLICK_ACTION, plugin.isUseActionBar(),
                Placeholder.of(Translation.Placeholder.ACTION, BoltComponents.translateRaw(Translation.EDIT, player)));
    }

    /**
     * Shared global-access-list edit used by {@code /bolt trust} (on the sender) and
     * {@code /bolt admin trust} (on a target player).
     */
    static void trustModify(final BoltPlugin plugin, final CommandSource source, final UUID uuid, final boolean adding, final Arguments arguments) {
        final String sourceTypeName = arguments.next().toLowerCase();
        final SourceType sourceType = plugin.getBolt().getSourceTypeRegistry().getSourceByName(sourceTypeName).orElse(null);
        if (sourceType == null) {
            BoltComponents.sendMessage(source, Translation.EDIT_SOURCE_INVALID, Placeholder.of(Translation.Placeholder.SOURCE_TYPE, sourceTypeName));
            return;
        }
        if (sourceType.restricted() && !source.hasPermission("bolt.type.source." + sourceType.name())) {
            BoltComponents.sendMessage(source, Translation.EDIT_SOURCE_NO_PERMISSION);
            return;
        }
        final String identifier = arguments.next();
        final String accessTypeName = Optional.ofNullable(arguments.next()).orElse(plugin.getDefaultAccessType()).toLowerCase();
        final Access access = plugin.getBolt().getAccessRegistry().getAccessByType(accessTypeName).orElse(null);
        if (access == null) {
            BoltComponents.sendMessage(source, Translation.EDIT_ACCESS_INVALID, Placeholder.of(Translation.Placeholder.ACCESS_TYPE, accessTypeName));
            return;
        }
        if (access.restricted() && !source.hasPermission("bolt.type.access." + access.type())) {
            BoltComponents.sendMessage(source, Translation.EDIT_ACCESS_NO_PERMISSION);
            return;
        }
        final String transformed = transformSource(sourceType.name(), identifier);
        if (transformed == null) {
            BoltComponents.sendMessage(source, Translation.GENERIC_NOT_FOUND, Placeholder.of(Translation.Placeholder.X, identifier == null ? "" : identifier));
            return;
        }
        AccessList accessList = plugin.getBolt().getStore().loadAccessList(uuid).join();
        if (accessList == null) {
            accessList = new AccessList(uuid, new HashMap<>());
        }
        final Source src = Source.of(sourceType.name(), transformed);
        if (adding) {
            accessList.getAccess().put(src.toString(), access.type());
        } else {
            accessList.getAccess().remove(src.toString());
        }
        plugin.getBolt().getStore().saveAccessList(accessList);
        BoltComponents.sendMessage(source, Translation.TRUST_EDITED);
    }

    static void trustList(final BoltPlugin plugin, final CommandSource source, final UUID uuid) {
        final AccessList accessList = plugin.getBolt().getStore().loadAccessList(uuid).join();
        final Map<String, String> accessMap = accessList == null ? new HashMap<>() : accessList.getAccess();
        BoltComponents.sendMessage(source, Translation.INFO_SELF,
                Placeholder.of(Translation.Placeholder.ACCESS_LIST_SIZE, String.valueOf(accessMap.size())),
                Placeholder.of(Translation.Placeholder.ACCESS_LIST, Protections.accessList(accessMap, plugin, source)));
    }

    /**
     * {@code /bolt password <password>} - registers a password on the player's session so they can
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

    /**
     * {@code /bolt transfer <player>} - click-based; transfers the next protection the sender clicks
     * (which they must own) to another player. Matches Bukkit's TransferCommand.
     */
    private static void transfer(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        if (!(source instanceof Player)) {
            BoltComponents.sendMessage(source, Translation.COMMAND_PLAYER_ONLY);
            return;
        }
        final Player player = (Player) source;
        final String target = arguments.next();
        if (target == null) {
            BoltComponents.sendMessage(source, Translation.HELP_COMMAND_SHORT_TRANSFER,
                    Placeholder.of(Translation.Placeholder.COMMAND, "/bolt transfer"));
            return;
        }
        final UUID uuid = resolvePlayer(target);
        if (uuid == null) {
            BoltComponents.sendMessage(source, Translation.PLAYER_NOT_FOUND, Placeholder.of(Translation.Placeholder.PLAYER, target));
            return;
        }
        plugin.player(player).setAction(new Action(Action.Type.TRANSFER, "bolt.command.transfer", uuid.toString()));
        BoltComponents.sendMessage(player, Translation.CLICK_TRANSFER, plugin.isUseActionBar());
    }

    /** {@code /bolt mode <persist|nolock|nospam>} - toggles a per-player mode (persisted). */
    private static void mode(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        if (!(source instanceof Player)) {
            BoltComponents.sendMessage(source, Translation.COMMAND_PLAYER_ONLY);
            return;
        }
        final Player player = (Player) source;
        final BoltPlayer boltPlayer = plugin.player(player);
        final String modeArgument = arguments.next();
        if (modeArgument == null) {
            BoltComponents.sendMessage(player, Translation.MODE_INVALID);
            return;
        }
        final Mode mode;
        try {
            mode = Mode.valueOf(modeArgument.toUpperCase());
        } catch (IllegalArgumentException e) {
            BoltComponents.sendMessage(player, Translation.MODE_INVALID);
            return;
        }
        boltPlayer.toggleMode(mode);
        final boolean hasMode = boltPlayer.hasMode(mode);
        BoltComponents.sendMessage(player, hasMode ? Translation.MODE_ENABLED : Translation.MODE_DISABLED,
                Placeholder.of(Translation.Placeholder.MODE, BoltComponents.translateRaw("mode_" + mode.name().toLowerCase(), player)));
        plugin.savePlayerMode(player.getUniqueId(), mode, hasMode);
    }

    /** {@code /bolt group <create|delete|add|remove|list> <group> [players...]} - manage player groups. */
    private static void group(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        if (!(source instanceof Player)) {
            BoltComponents.sendMessage(source, Translation.COMMAND_PLAYER_ONLY);
            return;
        }
        final Player player = (Player) source;
        if (arguments.remaining() < 2) {
            groupHelp(source);
            return;
        }
        final String action = arguments.next().toLowerCase();
        final String groupName = arguments.next();
        final List<UUID> uuids = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        String member;
        while ((member = arguments.next()) != null) {
            final UUID uuid = resolvePlayer(member);
            if (uuid != null) {
                uuids.add(uuid);
                names.add(member);
            }
        }
        final Store store = plugin.getBolt().getStore();
        final Group existingGroup = store.loadGroup(groupName).join();
        switch (action) {
            case "create":
                if (existingGroup != null) {
                    BoltComponents.sendMessage(player, Translation.GROUP_ALREADY_EXISTS, Placeholder.of(Translation.Placeholder.GROUP, groupName));
                } else {
                    store.saveGroup(new Group(groupName, player.getUniqueId(), uuids));
                    BoltComponents.sendMessage(player, Translation.GROUP_CREATED, Placeholder.of(Translation.Placeholder.GROUP, groupName));
                }
                break;
            case "delete":
                if (existingGroup == null) {
                    BoltComponents.sendMessage(player, Translation.GROUP_DOESNT_EXIST, Placeholder.of(Translation.Placeholder.GROUP, groupName));
                } else if (!existingGroup.getOwner().equals(player.getUniqueId())) {
                    BoltComponents.sendMessage(player, Translation.GROUP_NOT_OWNER, Placeholder.of(Translation.Placeholder.GROUP, groupName));
                } else {
                    store.removeGroup(existingGroup);
                    BoltComponents.sendMessage(player, Translation.GROUP_DELETED, Placeholder.of(Translation.Placeholder.GROUP, groupName));
                }
                break;
            case "add":
                if (existingGroup == null) {
                    BoltComponents.sendMessage(player, Translation.GROUP_DOESNT_EXIST, Placeholder.of(Translation.Placeholder.GROUP, groupName));
                } else if (!existingGroup.getOwner().equals(player.getUniqueId())) {
                    BoltComponents.sendMessage(player, Translation.GROUP_NOT_OWNER, Placeholder.of(Translation.Placeholder.GROUP, groupName));
                } else {
                    existingGroup.getMembers().addAll(uuids);
                    store.saveGroup(existingGroup);
                    for (final String name : names) {
                        BoltComponents.sendMessage(player, Translation.GROUP_PLAYER_ADD,
                                Placeholder.of(Translation.Placeholder.PLAYER, name),
                                Placeholder.of(Translation.Placeholder.GROUP, groupName));
                    }
                }
                break;
            case "remove":
                if (existingGroup == null) {
                    BoltComponents.sendMessage(player, Translation.GROUP_DOESNT_EXIST, Placeholder.of(Translation.Placeholder.GROUP, groupName));
                } else if (!existingGroup.getOwner().equals(player.getUniqueId())) {
                    BoltComponents.sendMessage(player, Translation.GROUP_NOT_OWNER, Placeholder.of(Translation.Placeholder.GROUP, groupName));
                } else {
                    existingGroup.getMembers().removeAll(uuids);
                    store.saveGroup(existingGroup);
                    for (final String name : names) {
                        BoltComponents.sendMessage(player, Translation.GROUP_PLAYER_REMOVE,
                                Placeholder.of(Translation.Placeholder.PLAYER, name),
                                Placeholder.of(Translation.Placeholder.GROUP, groupName));
                    }
                }
                break;
            case "list":
                if (existingGroup == null) {
                    BoltComponents.sendMessage(player, Translation.GROUP_DOESNT_EXIST, Placeholder.of(Translation.Placeholder.GROUP, groupName));
                } else {
                    final List<String> memberNames = new ArrayList<>();
                    for (final UUID uuid : existingGroup.getMembers()) {
                        memberNames.add(nameOf(uuid));
                    }
                    BoltComponents.sendMessage(player, Translation.GROUP_LIST_MEMBERS,
                            Placeholder.of(Translation.Placeholder.GROUP, groupName),
                            Placeholder.of(Translation.Placeholder.GROUP_MEMBERS, String.join(", ", memberNames)));
                }
                break;
            default:
                groupHelp(source);
                break;
        }
    }

    private static void groupHelp(final CommandSource source) {
        BoltComponents.sendMessage(source, Translation.HELP_COMMAND_SHORT_GROUP,
                Placeholder.of(Translation.Placeholder.COMMAND, "/bolt group"),
                Placeholder.of(Translation.Placeholder.LITERAL, "(create|delete|add|remove|list)"));
    }

    /** {@code /bolt help [command]} - shows general help or a specific command's help. */
    private static void help(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        final String command = arguments.next();
        if (command == null) {
            BoltComponents.sendMessage(source, Translation.HELP_COMMAND_SHORT_HELP, Placeholder.of(Translation.Placeholder.COMMAND, "/bolt help"));
            BoltComponents.sendMessage(source, Translation.HELP_COMMAND_LONG_HELP);
            final StringBuilder available = new StringBuilder();
            for (final String sub : SUBCOMMANDS) {
                if (source.hasPermission("bolt.command." + sub)) {
                    if (available.length() > 0) {
                        available.append(", ");
                    }
                    available.append(sub);
                }
            }
            source.sendMessage(Text.of(TextColors.GRAY, "Commands: ", TextColors.YELLOW, available.toString()));
            return;
        }
        final String cmd = command.toLowerCase();
        final String shortKey;
        final String longKey;
        final String label;
        if ("admin".equals(cmd)) {
            final String subArg = arguments.next();
            final String sub = subArg == null ? null : subArg.toLowerCase();
            if (sub != null && contains(AdminCommands.SUBCOMMANDS, sub)) {
                shortKey = "help_command_short_admin_" + sub;
                longKey = "help_command_long_admin_" + sub;
                label = "/bolt admin " + sub;
            } else {
                shortKey = "help_command_short_admin";
                longKey = "help_command_long_admin";
                label = "/bolt admin";
            }
        } else if (contains(SUBCOMMANDS, cmd)) {
            shortKey = "help_command_short_" + cmd;
            longKey = "help_command_long_" + cmd;
            label = "/bolt " + cmd;
        } else {
            BoltComponents.sendMessage(source, Translation.COMMAND_INVALID);
            return;
        }
        BoltComponents.sendMessage(source, shortKey, Placeholder.of(Translation.Placeholder.COMMAND, label));
        BoltComponents.sendMessage(source, longKey);
    }

    private static String nameOf(final UUID uuid) {
        final Optional<Player> online = Sponge.getServer().getPlayer(uuid);
        if (online.isPresent()) {
            return online.get().getName();
        }
        final Optional<UserStorageService> service = Sponge.getServiceManager().provide(UserStorageService.class);
        if (service.isPresent()) {
            final Optional<User> user = service.get().get(uuid);
            if (user.isPresent()) {
                return user.get().getName();
            }
        }
        return uuid.toString();
    }

    private static boolean contains(final String[] array, final String value) {
        for (final String item : array) {
            if (item.equals(value)) {
                return true;
            }
        }
        return false;
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
        switch (command) {
            case "lock":
                if (argIndex == 0) {
                    return filter(protectionTypeNames(plugin, source), partial);
                } else if (argIndex == 1 && source.hasPermission("bolt.admin")) {
                    return filter(Collections.singletonList("force"), partial);
                }
                break;
            case "trust":
                if (argIndex == 0) {
                    return filter(Arrays.asList("add", "remove", "list"), partial);
                } else if (argIndex == 1) {
                    return filter(sourceTypeNames(plugin, source), partial);
                } else if (argIndex == 2) {
                    return filter(sourceIdentifierNames(plugin, source, tokens[1]), partial);
                } else if (argIndex == 3) {
                    return filter(accessTypeNames(plugin, source), partial);
                }
                break;
            case "edit":
                if (argIndex == 0) {
                    return filter(Arrays.asList("add", "remove"), partial);
                } else if (argIndex == 1) {
                    return filter(playerNames(), partial);
                }
                break;
            case "modify":
                if (argIndex == 0) {
                    return filter(Arrays.asList("add", "remove"), partial);
                } else if (argIndex == 1) {
                    return filter(accessTypeNames(plugin, source), partial);
                } else if (argIndex == 2) {
                    return filter(sourceTypeNames(plugin, source), partial);
                } else if (argIndex >= 3) {
                    return filter(sourceIdentifierNames(plugin, source, tokens[2]), partial);
                }
                break;
            case "mode":
                if (argIndex == 0) {
                    final List<String> modes = new ArrayList<>();
                    for (final Mode mode : Mode.values()) {
                        modes.add(mode.name().toLowerCase());
                    }
                    return filter(modes, partial);
                }
                break;
            case "transfer":
                if (argIndex == 0) {
                    return filter(playerNames(), partial);
                }
                break;
            case "group":
                if (argIndex == 0) {
                    return filter(Arrays.asList("create", "delete", "add", "remove", "list"), partial);
                } else if (argIndex == 1 && source instanceof Player) {
                    return filter(plugin.getPlayersOwnedGroups((Player) source), partial);
                } else if (argIndex >= 2) {
                    return filter(playerNames(), partial);
                }
                break;
            case "help":
                if (argIndex == 0) {
                    final List<String> subs = new ArrayList<>();
                    for (final String sub : SUBCOMMANDS) {
                        if (source.hasPermission("bolt.command." + sub)) {
                            subs.add(sub);
                        }
                    }
                    return filter(subs, partial);
                } else if (argIndex == 1 && "admin".equalsIgnoreCase(tokens[0])) {
                    final List<String> subs = new ArrayList<>();
                    for (final String sub : AdminCommands.SUBCOMMANDS) {
                        if (source.hasPermission("bolt.command.admin." + sub)) {
                            subs.add(sub);
                        }
                    }
                    return filter(subs, partial);
                }
                break;
            case "admin":
                if (argIndex == 0) {
                    final List<String> subs = new ArrayList<>();
                    for (final String sub : AdminCommands.SUBCOMMANDS) {
                        if (source.hasPermission("bolt.command.admin." + sub)) {
                            subs.add(sub);
                        }
                    }
                    return filter(subs, partial);
                }
                return suggestAdminArgs(plugin, source, tokens[0].toLowerCase(), afterFirstToken(raw));
            default:
                break;
        }
        return Collections.emptyList();
    }

    private static List<String> suggestAdminArgs(final BoltPlugin plugin, final CommandSource source, final String sub, final String raw) {
        String[] tokens = tokenize(raw);
        if (tokens.length == 0) {
            tokens = new String[]{""};
        }
        final int argIndex = tokens.length - 1;
        final String partial = tokens[argIndex];
        switch (sub) {
            case "storage":
                if (argIndex == 0) {
                    return filter(Arrays.asList("export", "import"), partial);
                }
                break;
            case "expire":
                if (argIndex == 1) {
                    return filter(Arrays.asList("seconds", "minutes", "hours", "days"), partial);
                }
                break;
            case "purge":
            case "find":
                if (argIndex == 0) {
                    return filter(playerNames(), partial);
                }
                break;
            case "transfer":
                if (argIndex == 0 || argIndex == 1) {
                    return filter(playerNames(), partial);
                }
                break;
            case "trust":
                if (argIndex == 0) {
                    return filter(playerNames(), partial);
                } else if (argIndex == 1) {
                    return filter(Arrays.asList("add", "remove", "list"), partial);
                } else if (argIndex == 2) {
                    return filter(sourceTypeNames(plugin, source), partial);
                } else if (argIndex == 3) {
                    return filter(sourceIdentifierNames(plugin, source, tokens[2]), partial);
                } else if (argIndex == 4) {
                    return filter(accessTypeNames(plugin, source), partial);
                }
                break;
            default:
                break;
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

    /**
     * Completions for a source's identifier argument, mirroring Bukkit's per-source-type
     * {@code SourceTransformer.completions}: {@code player} → online players, {@code group} → the
     * sender's owned groups, everything else → nothing.
     */
    private static List<String> sourceIdentifierNames(final BoltPlugin plugin, final CommandSource source, final String sourceType) {
        if (SourceTypes.PLAYER.equalsIgnoreCase(sourceType)) {
            return playerNames();
        }
        if (SourceTypes.GROUP.equalsIgnoreCase(sourceType) && source instanceof Player) {
            return plugin.getPlayersOwnedGroups((Player) source);
        }
        return Collections.emptyList();
    }

    /**
     * Player-name completions: online players only, matching the Bukkit build. Enumerating offline
     * players (via {@code UserStorageService.getAll()}) was deliberately avoided - that call rebuilds
     * a map of every stored profile on each request, on the main thread, which does not scale to
     * servers with a large playerdata history.
     */
    private static List<String> playerNames() {
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
