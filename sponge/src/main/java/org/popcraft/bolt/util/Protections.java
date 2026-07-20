package org.popcraft.bolt.util;

import org.popcraft.bolt.BoltPlugin;
import org.popcraft.bolt.lang.Strings;
import org.popcraft.bolt.lang.Translation;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.protection.Protection;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceTypes;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.service.user.UserStorageService;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.channel.MessageReceiver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class Protections {
    private Protections() {
    }

    /** Renders a protection's access map as a newline-joined list of {@code access_list_entry} lines. */
    public static Text accessList(final Map<String, String> accessMap, final BoltPlugin plugin, final MessageReceiver receiver) {
        if (accessMap == null || accessMap.isEmpty()) {
            return Text.EMPTY;
        }
        final List<Text> list = new ArrayList<>();
        for (final Map.Entry<String, String> entry : accessMap.entrySet()) {
            final Source source = Source.parse(entry.getKey());
            final String access = entry.getValue();
            final String subject = source.getType().equals(source.getIdentifier())
                    ? Strings.toTitleCase(source.getType())
                    : unTransformIdentifier(source);
            final String entryKey = plugin.getDefaultAccessType().equals(access)
                    ? Translation.ACCESS_LIST_ENTRY_DEFAULT
                    : Translation.ACCESS_LIST_ENTRY;
            list.add(BoltComponents.resolveTranslation(entryKey, receiver,
                    Placeholder.of(Translation.Placeholder.SOURCE_IDENTIFIER, subject),
                    Placeholder.of(Translation.Placeholder.SOURCE_TYPE, Strings.toTitleCase(source.getType())),
                    Placeholder.of(Translation.Placeholder.ACCESS_TYPE, Strings.toTitleCase(access))));
        }
        final Text.Builder builder = Text.builder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                builder.append(Text.NEW_LINE);
            }
            builder.append(list.get(i));
        }
        return builder.build();
    }

    private static String unTransformIdentifier(final Source source) {
        if (SourceTypes.PASSWORD.equals(source.getType())) {
            return "Password";
        }
        if (SourceTypes.PLAYER.equals(source.getType())) {
            try {
                final UUID uuid = UUID.fromString(source.getIdentifier());
                final Optional<UserStorageService> service = Sponge.getServiceManager().provide(UserStorageService.class);
                if (service.isPresent()) {
                    final Optional<User> user = service.get().get(uuid);
                    if (user.isPresent()) {
                        return user.get().getName();
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // Not a UUID; fall through to the raw identifier.
            }
        }
        return source.getIdentifier();
    }

    /**
     * A human-readable display name for a protected object, derived from its catalog id
     * (e.g. {@code minecraft:trapped_chest} → {@code trapped chest}).
     */
    public static String displayType(final Protection protection) {
        if (protection instanceof BlockProtection) {
            return prettify(((BlockProtection) protection).getBlock());
        } else if (protection instanceof EntityProtection) {
            return prettify(((EntityProtection) protection).getEntity());
        }
        return "protection";
    }

    public static String displayType(final String catalogId) {
        return prettify(catalogId);
    }

    public static String protectionType(final Protection protection) {
        return protection == null ? "" : protection.getType();
    }

    private static String prettify(final String catalogId) {
        if (catalogId == null) {
            return "";
        }
        String id = catalogId;
        final int colon = id.indexOf(':');
        if (colon >= 0) {
            id = id.substring(colon + 1);
        }
        return id.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
