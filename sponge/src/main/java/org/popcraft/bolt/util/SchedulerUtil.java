package org.popcraft.bolt.util;

import org.spongepowered.api.Sponge;

import java.util.concurrent.Executor;

public final class SchedulerUtil {
    private SchedulerUtil() {
    }

    public static void schedule(final Object plugin, final Runnable runnable) {
        Sponge.getScheduler().createTaskBuilder().execute(runnable).submit(plugin);
    }

    public static void scheduleAsync(final Object plugin, final Runnable runnable) {
        Sponge.getScheduler().createTaskBuilder().async().execute(runnable).submit(plugin);
    }

    public static Executor executor(final Object plugin) {
        return command -> schedule(plugin, command);
    }
}
