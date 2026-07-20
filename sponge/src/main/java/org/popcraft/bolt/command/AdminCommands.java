package org.popcraft.bolt.command;

import org.popcraft.bolt.BoltPlugin;
import org.popcraft.bolt.data.Store;
import org.popcraft.bolt.lang.Translation;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.util.BoltComponents;
import org.popcraft.bolt.util.Placeholder;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import java.util.ArrayList;
import java.util.UUID;

/**
 * {@code /bolt admin <sub>} — moderation/maintenance commands. Each subcommand is gated on
 * {@code bolt.command.admin.<sub>}. Migration/import tooling is intentionally omitted.
 */
public final class AdminCommands {
    static final String[] SUBCOMMANDS = {"reload", "flush", "purge", "cleanup", "expire", "nearby", "report", "transfer"};

    private AdminCommands() {
    }

    static void handle(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        final String sub = arguments.next();
        if (sub == null) {
            source.sendMessage(Text.of(TextColors.GRAY, "Bolt admin: ", TextColors.YELLOW, String.join(", ", SUBCOMMANDS)));
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
                plugin.getBolt().getStore().flush();
                BoltComponents.sendMessage(source, Translation.FLUSH);
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
            case "report":
                report(plugin, source);
                break;
            case "transfer":
                transfer(plugin, source, arguments);
                break;
            default:
                BoltComponents.sendMessage(source, Translation.COMMAND_INVALID);
                break;
        }
    }

    private static void purge(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        final String name = arguments.next();
        if (name == null) {
            source.sendMessage(Text.of(TextColors.RED, "Usage: /bolt admin purge <player>"));
            return;
        }
        final UUID owner = BoltCommands.resolvePlayer(name);
        if (owner == null) {
            BoltComponents.sendMessage(source, Translation.PLAYER_NOT_FOUND, Placeholder.of(Translation.Placeholder.PLAYER, name));
            return;
        }
        final Store store = plugin.getBolt().getStore();
        int removed = 0;
        for (final BlockProtection protection : new ArrayList<>(store.loadBlockProtections().join())) {
            if (owner.equals(protection.getOwner())) {
                store.removeBlockProtection(protection);
                removed++;
            }
        }
        for (final EntityProtection protection : new ArrayList<>(store.loadEntityProtections().join())) {
            if (owner.equals(protection.getOwner())) {
                store.removeEntityProtection(protection);
                removed++;
            }
        }
        BoltComponents.sendMessage(source, Translation.PURGE, Placeholder.of(Translation.Placeholder.COUNT, String.valueOf(removed)));
    }

    private static void cleanup(final BoltPlugin plugin, final CommandSource source) {
        // Conservative cleanup: only remove block protections whose world no longer exists (safe
        // without loading chunks; a fuller "block no longer protectable" sweep is deferred).
        final Store store = plugin.getBolt().getStore();
        int removed = 0;
        for (final BlockProtection protection : new ArrayList<>(store.loadBlockProtections().join())) {
            if (!Sponge.getServer().getWorld(protection.getWorld()).isPresent()) {
                store.removeBlockProtection(protection);
                removed++;
            }
        }
        BoltComponents.sendMessage(source, Translation.CLEANUP_COMPLETE,
                Placeholder.of(Translation.Placeholder.COUNT, String.valueOf(removed)),
                Placeholder.of(Translation.Placeholder.SECONDS, "0"));
    }

    private static void expire(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        final Integer days = arguments.nextAsInteger();
        if (days == null || days < 0) {
            BoltComponents.sendMessage(source, Translation.EXPIRE_INVALID_TIME);
            return;
        }
        final long cutoff = System.currentTimeMillis() - days.longValue() * 86_400_000L;
        final Store store = plugin.getBolt().getStore();
        int removed = 0;
        for (final BlockProtection protection : new ArrayList<>(store.loadBlockProtections().join())) {
            if (protection.getAccessed() < cutoff) {
                store.removeBlockProtection(protection);
                removed++;
            }
        }
        for (final EntityProtection protection : new ArrayList<>(store.loadEntityProtections().join())) {
            if (protection.getAccessed() < cutoff) {
                store.removeEntityProtection(protection);
                removed++;
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
        final Integer radiusArg = arguments.nextAsInteger();
        final int radius = radiusArg == null ? 10 : Math.max(1, radiusArg);
        final String world = player.getWorld().getName();
        final Location<World> location = player.getLocation();
        final int cx = location.getBlockX();
        final int cy = location.getBlockY();
        final int cz = location.getBlockZ();
        final long r2 = (long) radius * radius;
        int count = 0;
        for (final BlockProtection protection : new ArrayList<>(plugin.getBolt().getStore().loadBlockProtections().join())) {
            if (!world.equals(protection.getWorld())) {
                continue;
            }
            final long dx = protection.getX() - cx;
            final long dy = protection.getY() - cy;
            final long dz = protection.getZ() - cz;
            if (dx * dx + dy * dy + dz * dz <= r2) {
                source.sendMessage(Text.of(TextColors.YELLOW, protection.getType(), TextColors.GRAY,
                        " " + protection.getBlock() + " @ " + protection.getX() + ", " + protection.getY() + ", " + protection.getZ()));
                count++;
            }
        }
        source.sendMessage(Text.of(TextColors.GRAY, "Found ", TextColors.YELLOW, String.valueOf(count),
                TextColors.GRAY, " protection(s) within " + radius + " blocks."));
    }

    private static void report(final BoltPlugin plugin, final CommandSource source) {
        final Store store = plugin.getBolt().getStore();
        final int blocks = store.loadBlockProtections().join().size();
        final int entities = store.loadEntityProtections().join().size();
        source.sendMessage(Text.of(TextColors.GRAY, "Bolt protections: ", TextColors.YELLOW,
                blocks + " block(s), " + entities + " entity/entities", TextColors.GRAY,
                ", pending saves: " + store.pendingSave()));
    }

    private static void transfer(final BoltPlugin plugin, final CommandSource source, final Arguments arguments) {
        final String fromName = arguments.next();
        final String toName = arguments.next();
        if (fromName == null || toName == null) {
            source.sendMessage(Text.of(TextColors.RED, "Usage: /bolt admin transfer <from> <to>"));
            return;
        }
        final UUID from = BoltCommands.resolvePlayer(fromName);
        final UUID to = BoltCommands.resolvePlayer(toName);
        if (from == null || to == null) {
            BoltComponents.sendMessage(source, Translation.PLAYER_NOT_FOUND,
                    Placeholder.of(Translation.Placeholder.PLAYER, from == null ? fromName : toName));
            return;
        }
        final Store store = plugin.getBolt().getStore();
        int moved = 0;
        for (final BlockProtection protection : new ArrayList<>(store.loadBlockProtections().join())) {
            if (from.equals(protection.getOwner())) {
                protection.setOwner(to);
                store.saveBlockProtection(protection);
                moved++;
            }
        }
        for (final EntityProtection protection : new ArrayList<>(store.loadEntityProtections().join())) {
            if (from.equals(protection.getOwner())) {
                protection.setOwner(to);
                store.saveEntityProtection(protection);
                moved++;
            }
        }
        source.sendMessage(Text.of(TextColors.GRAY, "Transferred ", TextColors.YELLOW, String.valueOf(moved),
                TextColors.GRAY, " protection(s) to " + toName + "."));
    }
}
