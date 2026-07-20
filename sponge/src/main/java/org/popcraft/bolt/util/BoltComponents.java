package org.popcraft.bolt.util;

import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.channel.MessageReceiver;
import org.spongepowered.api.text.format.TextColor;
import org.spongepowered.api.text.format.TextColors;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.popcraft.bolt.lang.Translator.translate;

/**
 * Bridges Bolt's translation strings (a small subset of MiniMessage syntax) onto SpongeAPI 7.4's
 * {@link Text} model. SpongeAPI 7 predates Adventure/Kyori Text, so this parser handles only the
 * tags actually used by Bolt's language files: named colors, {@code <newline>}, and
 * {@code <placeholder>} substitutions. Unknown tags are dropped.
 */
public final class BoltComponents {
    private static final Map<String, TextColor> COLORS = new HashMap<>();

    static {
        COLORS.put("black", TextColors.BLACK);
        COLORS.put("dark_blue", TextColors.DARK_BLUE);
        COLORS.put("dark_green", TextColors.DARK_GREEN);
        COLORS.put("dark_aqua", TextColors.DARK_AQUA);
        COLORS.put("dark_red", TextColors.DARK_RED);
        COLORS.put("dark_purple", TextColors.DARK_PURPLE);
        COLORS.put("gold", TextColors.GOLD);
        COLORS.put("gray", TextColors.GRAY);
        COLORS.put("grey", TextColors.GRAY);
        COLORS.put("dark_gray", TextColors.DARK_GRAY);
        COLORS.put("dark_grey", TextColors.DARK_GRAY);
        COLORS.put("blue", TextColors.BLUE);
        COLORS.put("green", TextColors.GREEN);
        COLORS.put("aqua", TextColors.AQUA);
        COLORS.put("red", TextColors.RED);
        COLORS.put("light_purple", TextColors.LIGHT_PURPLE);
        COLORS.put("yellow", TextColors.YELLOW);
        COLORS.put("white", TextColors.WHITE);
    }

    private BoltComponents() {
    }

    public static void sendMessage(final MessageReceiver receiver, final String key, final Placeholder... placeholders) {
        final Text text = resolveTranslation(key, receiver, placeholders);
        if (!text.isEmpty()) {
            receiver.sendMessage(text);
        }
    }

    /**
     * Action-bar variant. SpongeAPI 7.4 action bars require a {@code ChatTypeMessageReceiver}; for
     * the MVP this collapses to a normal chat message (Bolt's default config disables action bars).
     */
    public static void sendMessage(final MessageReceiver receiver, final String key, final boolean actionBar, final Placeholder... placeholders) {
        sendMessage(receiver, key, placeholders);
    }

    public static Text resolveTranslation(final String key, final MessageReceiver receiver, final Placeholder... placeholders) {
        return parse(translateRaw(key, receiver), placeholders);
    }

    public static String translateRaw(final String key, final MessageReceiver receiver) {
        return translate(key, getLocaleOf(receiver));
    }

    public static Locale getLocaleOf(final MessageReceiver receiver) {
        if (receiver instanceof Player) {
            return ((Player) receiver).getLocale();
        }
        return Locale.ROOT;
    }

    static Text parse(final String raw, final Placeholder... placeholders) {
        final Map<String, Text> values = new HashMap<>();
        for (final Placeholder placeholder : placeholders) {
            values.put(placeholder.key(), placeholder.value());
        }
        final Text.Builder root = Text.builder();
        final Deque<TextColor> colors = new ArrayDeque<>();
        final StringBuilder buffer = new StringBuilder();
        final int length = raw.length();
        int i = 0;
        while (i < length) {
            final char c = raw.charAt(i);
            if (c == '<') {
                final int close = raw.indexOf('>', i);
                if (close < 0) {
                    buffer.append(c);
                    i++;
                    continue;
                }
                final String tag = raw.substring(i + 1, close);
                final String lower = tag.toLowerCase(Locale.ROOT);
                if ("newline".equals(lower) || "br".equals(lower)) {
                    flush(root, buffer, colors);
                    root.append(Text.NEW_LINE);
                } else if (lower.startsWith("/")) {
                    final String name = lower.substring(1);
                    if (COLORS.containsKey(name) && !colors.isEmpty()) {
                        flush(root, buffer, colors);
                        colors.pop();
                    }
                } else if (COLORS.containsKey(lower)) {
                    flush(root, buffer, colors);
                    colors.push(COLORS.get(lower));
                } else if (values.containsKey(tag)) {
                    flush(root, buffer, colors);
                    Text value = values.get(tag);
                    if (!colors.isEmpty()) {
                        value = Text.builder().color(colors.peek()).append(value).build();
                    }
                    root.append(value);
                }
                // Unknown tag: dropped.
                i = close + 1;
            } else {
                buffer.append(c);
                i++;
            }
        }
        flush(root, buffer, colors);
        return root.build();
    }

    private static void flush(final Text.Builder root, final StringBuilder buffer, final Deque<TextColor> colors) {
        if (buffer.length() == 0) {
            return;
        }
        final Text.Builder segment = Text.builder(buffer.toString());
        if (!colors.isEmpty()) {
            segment.color(colors.peek());
        }
        root.append(segment.build());
        buffer.setLength(0);
    }
}
