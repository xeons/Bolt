package org.popcraft.bolt.util;

import org.popcraft.bolt.lang.Translation;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.channel.MessageReceiver;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a unix timestamp as a compact relative duration (e.g. {@code 5m ago}) as SpongeAPI
 * {@link Text}, using Bolt's {@code time_*} translation keys. Duration part-extraction is done
 * with Java-8-safe arithmetic (no {@code toHoursPart()} etc.).
 */
public final class Time {
    private static final int DAYS_IN_YEAR = 365;
    private static final int DAYS_IN_MONTH = 30;

    private Time() {
    }

    public static Text relativeTimestamp(final long unixMs, final MessageReceiver receiver) {
        final long now = System.currentTimeMillis();
        final Duration duration = Duration.of(Math.max(0L, now - unixMs), ChronoUnit.MILLIS);
        final Text ago = ago(duration, receiver);
        return BoltComponents.resolveTranslation(Translation.TIME_AGO, receiver,
                Placeholder.of(Translation.Placeholder.TIME, ago));
    }

    private static Text ago(final Duration duration, final MessageReceiver receiver) {
        final long totalSeconds = duration.getSeconds();
        long days = totalSeconds / 86400L;
        final int hours = (int) ((totalSeconds % 86400L) / 3600L);
        final int minutes = (int) ((totalSeconds % 3600L) / 60L);
        final int seconds = (int) (totalSeconds % 60L);

        final List<Text> parts = new ArrayList<>();
        if (days >= DAYS_IN_YEAR) {
            final long years = days / DAYS_IN_YEAR;
            days -= years * DAYS_IN_YEAR;
            parts.add(part(Translation.TIME_YEARS, years, receiver));
        }
        if (days >= DAYS_IN_MONTH) {
            final long months = days / DAYS_IN_MONTH;
            days -= months * DAYS_IN_MONTH;
            parts.add(part(Translation.TIME_MONTHS, months, receiver));
        }
        if (days != 0) {
            parts.add(part(Translation.TIME_DAYS, days, receiver));
        }
        if (hours != 0) {
            parts.add(part(Translation.TIME_HOURS, hours, receiver));
        }
        if (minutes != 0) {
            parts.add(part(Translation.TIME_MINUTES, minutes, receiver));
        }
        if (seconds != 0 || parts.isEmpty()) {
            parts.add(part(Translation.TIME_SECONDS, seconds, receiver));
        }

        final Text.Builder builder = Text.builder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                builder.append(Text.of(" "));
            }
            builder.append(parts.get(i));
        }
        return builder.build();
    }

    private static Text part(final String key, final long number, final MessageReceiver receiver) {
        return BoltComponents.resolveTranslation(key, receiver,
                Placeholder.of(Translation.Placeholder.NUMBER, String.valueOf(number)));
    }
}
