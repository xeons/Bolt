package org.popcraft.bolt.command;

import org.popcraft.bolt.BoltPlugin;
import org.popcraft.bolt.data.SQLStore;
import org.popcraft.bolt.data.Store;
import org.popcraft.bolt.lang.Translation;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.protection.Protection;
import org.popcraft.bolt.util.Action;
import org.popcraft.bolt.util.BoltComponents;
import org.popcraft.bolt.util.Placeholder;
import org.popcraft.bolt.util.Protections;
import org.popcraft.bolt.util.SchedulerUtil;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code /bolt admin <sub>} - moderation/maintenance commands, mirroring the Bukkit
 * implementation. Each subcommand is gated on {@code bolt.command.admin.<sub>}. Migration/convert
 * tooling is intentionally omitted; {@code report} is a reduced version (the Bukkit hit/miss
 * profiler is bStats-backed and not part of this port).
 */
public final class AdminCommands {
    static final String[] SUBCOMMANDS = {"cleanup", "debug", "expire", "find", "flush", "nearby", "purge", "reload", "report", "storage", "transfer", "trust"};
    private static final int MAX_LISTED = 25;
    private static final AtomicBoolean STORAGE_BUSY = new AtomicBoolean();

    private AdminCommands() {
    }

    static void handle(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        final String sub = arguments.next();
        if (sub == null) {
            final Store store = plugin.getBolt().getStore();
            BoltComponents.sendMessage(source, Translation.STATUS,
                    Placeholder.of(Translation.Placeholder.COUNT_BLOCKS, String.valueOf(store.loadBlockProtections().join().size())),
                    Placeholder.of(Translation.Placeholder.COUNT_ENTITIES, String.valueOf(store.loadEntityProtections().join().size())));
            return;
        }
        final String key = sub.toLowerCase();
        if (!source.hasPermission("bolt.command.admin." + key)) {
            BoltComponents.sendMessage(source, Translation.COMMAND_NO_PERMISSION);
            return;
        }
        switch (key) {
            case "reload":
                plugin.reload();
                BoltComponents.sendMessage(source, Translation.RELOAD);
                break;
            case "flush":
                flush(plugin, source);
                break;
            case "purge":
                purge(plugin, source, arguments);
                break;
            case "cleanup":
                cleanup(plugin, source);
                break;
            case "expire":
                expire(plugin, source, arguments);
                break;
            case "nearby":
                nearby(plugin, source, arguments);
                break;
            case "find":
                find(plugin, source, arguments);
                break;
            case "report":
                report(plugin, source);
                break;
            case "transfer":
                transfer(plugin, source, arguments);
                break;
            case "debug":
                debug(plugin, source);
                break;
            case "storage":
                storage(plugin, source, arguments);
                break;
            case "trust":
                trust(plugin, source, arguments);
                break;
            default:
                BoltComponents.sendMessage(source, Translation.COMMAND_INVALID);
                break;
        }
    }

    private static void flush(final BoltPlugin plugin, final CommandSource source) {
        final Store store = plugin.getBolt().getStore();
        BoltComponents.sendMessage(source, Translation.FLUSH, Placeholder.of(Translation.Placeholder.COUNT, String.valueOf(store.pendingSave())));
        store.flush().join();
    }

    private static void purge(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        final String owner = arguments.next();
        if (owner == null) {
            source.sendMessage(Text.of(TextColors.RED, "Usage: /bolt admin purge <player>"));
            return;
        }
        final UUID uuid = BoltCommands.resolvePlayer(owner);
        if (uuid == null) {
            BoltComponents.sendMessage(source, Translation.PLAYER_NOT_FOUND, Placeholder.of(Translation.Placeholder.PLAYER, owner));
            return;
        }
        for (final Protection protection : allProtections(plugin)) {
            if (uuid.equals(protection.getOwner())) {
                plugin.removeProtection(protection);
            }
        }
        BoltComponents.sendMessage(source, Translation.PURGE, Placeholder.of(Translation.Placeholder.PLAYER, owner));
    }

    private static void cleanup(final BoltPlugin plugin, final CommandSource source) {
        final Store store = plugin.getBolt().getStore();
        final List<BlockProtection> protections = new ArrayList<>(store.loadBlockProtections().join());
        BoltComponents.sendMessage(source, Translation.CLEANUP_START, Placeholder.of(Translation.Placeholder.COUNT, String.valueOf(protections.size())));
        final long start = System.currentTimeMillis();
        int removed = 0;
        for (final BlockProtection protection : protections) {
            final Optional<World> world = Sponge.getServer().getWorld(protection.getWorld());
            if (!world.isPresent()) {
                continue;
            }
            // Loading each block loads its chunk; heavy for large datasets, but this mirrors the
            // Bukkit "block no longer matches the stored type" cleanup.
            final String currentId = world.get().getBlockType(protection.getX(), protection.getY(), protection.getZ()).getId();
            if (!protection.getBlock().equals(currentId)) {
                store.removeBlockProtection(protection);
                removed++;
                BoltComponents.sendMessage(source, Translation.CLEANUP_REMOVE, Placeholder.of(Translation.Placeholder.RAW_PROTECTION, Protections.raw(protection)));
            }
        }
        final long seconds = (System.currentTimeMillis() - start) / 1000L;
        BoltComponents.sendMessage(source, Translation.CLEANUP_COMPLETE,
                Placeholder.of(Translation.Placeholder.COUNT, String.valueOf(removed)),
                Placeholder.of(Translation.Placeholder.SECONDS, String.valueOf(seconds)));
    }

    private static void expire(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        final Integer timeValue = arguments.nextAsInteger();
        TimeUnit timeUnit = null;
        final String unitArg = arguments.next();
        if (unitArg != null) {
            try {
                timeUnit = TimeUnit.valueOf(unitArg.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Handled below.
            }
        }
        if (timeValue == null || timeUnit == null) {
            BoltComponents.sendMessage(source, Translation.EXPIRE_INVALID_TIME);
            return;
        }
        final long expireTime = System.currentTimeMillis() - TimeUnit.MILLISECONDS.convert(timeValue, timeUnit);
        final Store store = plugin.getBolt().getStore();
        long removed = 0;
        for (final BlockProtection protection : new ArrayList<>(store.loadBlockProtections().join())) {
            final long lastAccessed = protection.getAccessed();
            if (lastAccessed > 0 && lastAccessed < expireTime) {
                store.removeBlockProtection(protection);
                ++removed;
            }
        }
        BoltComponents.sendMessage(source, Translation.EXPIRE_COMPLETE, Placeholder.of(Translation.Placeholder.COUNT, String.valueOf(removed)));
    }

    private static void nearby(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        if (!(source instanceof Player)) {
            BoltComponents.sendMessage(source, Translation.COMMAND_PLAYER_ONLY);
            return;
        }
        final Player player = (Player) source;
        final Integer limit = arguments.nextAsInteger();
        if (limit == null) {
            source.sendMessage(Text.of(TextColors.RED, "Usage: /bolt admin nearby <radius>"));
            return;
        }
        final Location<World> playerLocation = player.getLocation();
        final List<Protection> nearby = new ArrayList<>();
        for (final Protection protection : allProtections(plugin)) {
            final double distance = distance(playerLocation, protection);
            if (!Double.isNaN(distance) && distance < limit) {
                nearby.add(protection);
            }
        }
        nearby.sort(Comparator.comparingDouble(protection -> distanceOrMax(playerLocation, protection)));
        listProtections(source, nearby);
    }

    private static void find(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        final String owner = arguments.next();
        if (owner == null) {
            source.sendMessage(Text.of(TextColors.RED, "Usage: /bolt admin find <player>"));
            return;
        }
        final UUID uuid = BoltCommands.resolvePlayer(owner);
        if (uuid == null) {
            BoltComponents.sendMessage(source, Translation.PLAYER_NOT_FOUND, Placeholder.of(Translation.Placeholder.PLAYER, owner));
            return;
        }
        final List<Protection> owned = new ArrayList<>();
        for (final Protection protection : allProtections(plugin)) {
            if (uuid.equals(protection.getOwner())) {
                owned.add(protection);
            }
        }
        owned.sort(Comparator.comparingLong(Protection::getCreated).reversed());
        listProtections(source, owned);
    }

    private static void report(final BoltPlugin plugin, final CommandSource source) {
        // Reduced report: the Bukkit version is a bStats-backed live hit/miss profiler, which is
        // not part of this port. Show protection counts and pending saves instead.
        final Store store = plugin.getBolt().getStore();
        source.sendMessage(Text.of(TextColors.GRAY, "Protected blocks: ", TextColors.YELLOW, String.valueOf(store.loadBlockProtections().join().size())));
        source.sendMessage(Text.of(TextColors.GRAY, "Protected entities: ", TextColors.YELLOW, String.valueOf(store.loadEntityProtections().join().size())));
        source.sendMessage(Text.of(TextColors.GRAY, "Pending saves: ", TextColors.YELLOW, String.valueOf(store.pendingSave())));
    }

    private static void transfer(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        if (!(source instanceof Player)) {
            BoltComponents.sendMessage(source, Translation.COMMAND_PLAYER_ONLY);
            return;
        }
        final Player player = (Player) source;
        final String owner = arguments.next();
        if (owner == null) {
            source.sendMessage(Text.of(TextColors.RED, "Usage: /bolt admin transfer <player> [newOwner]"));
            return;
        }
        final String newOwner = arguments.next();
        final UUID from = BoltCommands.resolvePlayer(owner);
        if (from == null) {
            BoltComponents.sendMessage(source, Translation.PLAYER_NOT_FOUND, Placeholder.of(Translation.Placeholder.PLAYER, owner));
            return;
        }
        if (newOwner != null) {
            final UUID to = BoltCommands.resolvePlayer(newOwner);
            if (to == null) {
                BoltComponents.sendMessage(source, Translation.PLAYER_NOT_FOUND, Placeholder.of(Translation.Placeholder.PLAYER, newOwner));
                return;
            }
            for (final Protection protection : allProtections(plugin)) {
                if (from.equals(protection.getOwner())) {
                    protection.setOwner(to);
                    plugin.saveProtection(protection);
                }
            }
            BoltComponents.sendMessage(player, Translation.CLICK_TRANSFER_ALL,
                    Placeholder.of(Translation.Placeholder.OLD_PLAYER, owner),
                    Placeholder.of(Translation.Placeholder.NEW_PLAYER, newOwner));
        } else {
            // Click-based single transfer to `owner`.
            plugin.player(player).setAction(new Action(Action.Type.TRANSFER, "bolt.command.admin.transfer", from.toString(), true));
            BoltComponents.sendMessage(player, Translation.CLICK_TRANSFER, plugin.isUseActionBar());
        }
    }

    private static void debug(final BoltPlugin plugin, final CommandSource source) {
        if (!(source instanceof Player)) {
            BoltComponents.sendMessage(source, Translation.COMMAND_PLAYER_ONLY);
            return;
        }
        final Player player = (Player) source;
        plugin.player(player).setAction(new Action(Action.Type.DEBUG, "bolt.command.admin.debug"));
        source.sendMessage(Text.of("Click to debug object"));
    }

    private static void storage(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        final String method = arguments.next();
        final boolean export = "export".equalsIgnoreCase(method);
        final boolean importing = "import".equalsIgnoreCase(method);
        if (!export && !importing) {
            source.sendMessage(Text.of(TextColors.RED, "Usage: /bolt admin storage <export|import>"));
            return;
        }
        if (STORAGE_BUSY.get()) {
            BoltComponents.sendMessage(source, Translation.STORAGE_IN_PROGRESS);
            return;
        }
        final Path exportPath = plugin.getConfigDir().resolve("export.db");
        final SQLStore.Configuration configuration = new SQLStore.Configuration("sqlite", exportPath.toString(), "", "", "", "", "", new HashMap<>());
        final Store currentStore = plugin.getBolt().getStore();
        if (export) {
            if (Files.exists(exportPath)) {
                BoltComponents.sendMessage(source, Translation.STORAGE_EXPORT_EXISTS);
                return;
            }
            final SQLStore exportStore = new SQLStore(configuration);
            BoltComponents.sendMessage(source, Translation.STORAGE_EXPORT_STARTED);
            STORAGE_BUSY.set(true);
            transferStore(currentStore, exportStore).whenComplete((v, throwable) -> SchedulerUtil.schedule(plugin.getContainer(), () -> {
                STORAGE_BUSY.set(false);
                if (throwable != null) {
                    throwable.printStackTrace();
                }
                BoltComponents.sendMessage(source, Translation.STORAGE_EXPORT_COMPLETED);
                exportStore.close();
            }));
        } else {
            if (!Files.exists(exportPath)) {
                BoltComponents.sendMessage(source, Translation.STORAGE_IMPORT_DOESNT_EXIST);
                return;
            }
            final SQLStore exportStore = new SQLStore(configuration);
            BoltComponents.sendMessage(source, Translation.STORAGE_IMPORT_STARTED);
            STORAGE_BUSY.set(true);
            transferStore(exportStore, currentStore).whenComplete((v, throwable) -> SchedulerUtil.schedule(plugin.getContainer(), () -> {
                STORAGE_BUSY.set(false);
                if (throwable != null) {
                    throwable.printStackTrace();
                }
                BoltComponents.sendMessage(source, Translation.STORAGE_IMPORT_COMPLETED);
                exportStore.close();
            }));
        }
    }

    private static CompletableFuture<Void> transferStore(final Store from, final Store to) {
        return CompletableFuture.runAsync(() -> {
            from.loadBlockProtections().join().forEach(to::saveBlockProtection);
            from.loadEntityProtections().join().forEach(to::saveEntityProtection);
            from.loadGroups().join().forEach(to::saveGroup);
            from.loadAccessLists().join().forEach(to::saveAccessList);
            to.flush().join();
        });
    }

    private static void trust(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        final String targetName = arguments.next();
        if (targetName == null) {
            source.sendMessage(Text.of(TextColors.RED, "Usage: /bolt admin trust <player> (add|remove|list) [sourceType] [identifier] [access]"));
            return;
        }
        final UUID target = BoltCommands.resolvePlayer(targetName);
        if (target == null) {
            BoltComponents.sendMessage(source, Translation.PLAYER_NOT_FOUND, Placeholder.of(Translation.Placeholder.PLAYER, targetName));
            return;
        }
        final String action = arguments.next();
        if ("add".equalsIgnoreCase(action) || "remove".equalsIgnoreCase(action)) {
            if (arguments.remaining() < 2) {
                source.sendMessage(Text.of(TextColors.RED, "Usage: /bolt admin trust <player> " + action.toLowerCase() + " <sourceType> <identifier> [access]"));
                return;
            }
            BoltCommands.trustModify(plugin, source, target, "add".equalsIgnoreCase(action), arguments);
        } else {
            BoltCommands.trustList(plugin, source, target);
        }
    }

    // --- Shared helpers ---------------------------------------------------------------------

    private static List<Protection> allProtections(final BoltPlugin plugin) {
        final Store store = plugin.getBolt().getStore();
        final List<Protection> all = new ArrayList<>(store.loadBlockProtections().join());
        all.addAll(store.loadEntityProtections().join());
        return all;
    }

    private static void listProtections(final CommandSource source, final List<Protection> protections) {
        if (protections.isEmpty()) {
            BoltComponents.sendMessage(source, Translation.FIND_NONE);
            return;
        }
        final int shown = Math.min(protections.size(), MAX_LISTED);
        source.sendMessage(Text.of(TextColors.GRAY, "Found ", TextColors.YELLOW, String.valueOf(protections.size()),
                TextColors.GRAY, " protection(s)" + (protections.size() > shown ? " (showing " + shown + ")" : "") + ":"));
        for (int i = 0; i < shown; i++) {
            final Protection protection = protections.get(i);
            source.sendMessage(Text.of(TextColors.YELLOW, protection.getType(), TextColors.GRAY, " " + describe(protection)));
        }
    }

    private static String describe(final Protection protection) {
        if (protection instanceof BlockProtection) {
            final BlockProtection block = (BlockProtection) protection;
            return block.getBlock() + " @ " + block.getWorld() + " " + block.getX() + ", " + block.getY() + ", " + block.getZ();
        } else if (protection instanceof EntityProtection) {
            return ((EntityProtection) protection).getEntity() + " (" + protection.getId() + ")";
        }
        return "";
    }

    private static Optional<Location<World>> protectionLocation(final Protection protection) {
        if (protection instanceof BlockProtection) {
            final BlockProtection block = (BlockProtection) protection;
            return Sponge.getServer().getWorld(block.getWorld())
                    .map(world -> world.getLocation(block.getX() + 0.5, block.getY(), block.getZ() + 0.5));
        } else if (protection instanceof EntityProtection) {
            for (final World world : Sponge.getServer().getWorlds()) {
                final Optional<Entity> entity = world.getEntity(protection.getId());
                if (entity.isPresent()) {
                    return Optional.of(entity.get().getLocation());
                }
            }
        }
        return Optional.empty();
    }

    private static double distance(final Location<World> from, final Protection protection) {
        final Optional<Location<World>> to = protectionLocation(protection);
        if (!to.isPresent() || !from.getExtent().equals(to.get().getExtent())) {
            return Double.NaN;
        }
        return from.getPosition().distance(to.get().getPosition());
    }

    private static double distanceOrMax(final Location<World> from, final Protection protection) {
        final double distance = distance(from, protection);
        return Double.isNaN(distance) ? Double.MAX_VALUE : distance;
    }
}
