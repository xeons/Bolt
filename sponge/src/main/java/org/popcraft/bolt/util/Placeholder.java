package org.popcraft.bolt.util;

import org.spongepowered.api.text.Text;

/**
 * A named substitution for the {@link BoltComponents} tag bridge. Mirrors the role of Adventure's
 * {@code TagResolver} on other platforms, but resolves against SpongeAPI 7.4's {@link Text}.
 */
public final class Placeholder {
    private final String key;
    private final Text value;

    private Placeholder(final String key, final Text value) {
        this.key = key;
        this.value = value;
    }

    public static Placeholder of(final String key, final String value) {
        return new Placeholder(key, Text.of(value == null ? "" : value));
    }

    public static Placeholder of(final String key, final Text value) {
        return new Placeholder(key, value == null ? Text.EMPTY : value);
    }

    public String key() {
        return key;
    }

    public Text value() {
        return value;
    }
}
