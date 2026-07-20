package org.popcraft.bolt.util;

import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.protection.Protection;

import java.util.Locale;

public final class Protections {
    private Protections() {
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
