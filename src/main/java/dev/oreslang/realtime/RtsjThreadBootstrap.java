package dev.oreslang.realtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;

/**
 * Setup-time RTSJ 2 bridge for creating a real {@code RealtimeThread} without
 * making this module depend on a vendor RTSJ jar at ordinary-JDK build time.
 *
 * <p>Reflection and thread construction are explicitly bootstrap operations.
 * They must complete before a hard-RT domain is admitted. Critical execution
 * must use prestarted carriers and may not invoke this bridge.</p>
 */
public final class RtsjThreadBootstrap {
    private RtsjThreadBootstrap() { }

    public static Thread createRealtimeThread(int priority, Runnable logic) {
        return createRealtimeThread(
                Thread.currentThread().getContextClassLoader(), priority, logic);
    }

    static Thread createRealtimeThread(
            ClassLoader loader, int priority, Runnable logic) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(logic, "logic");
        RtsjPresence presence = RtsjProbe.probe(loader);
        if (!presence.coreApiPresent()) {
            throw new IllegalStateException("RTSJ core API is unavailable");
        }
        try {
            Class<?> scheduling = Class.forName(
                    "javax.realtime.SchedulingParameters", true, loader);
            Class<?> release = Class.forName(
                    "javax.realtime.ReleaseParameters", true, loader);
            Class<?> priorityParameters = Class.forName(
                    "javax.realtime.PriorityParameters", true, loader);
            Class<?> realtimeThread = Class.forName(
                    "javax.realtime.RealtimeThread", true, loader);

            Object schedulingValue =
                    priorityParameters.getConstructor(int.class).newInstance(priority);
            Constructor<?> constructor = realtimeThread.getConstructor(
                    scheduling, release, Runnable.class);
            Object value = constructor.newInstance(schedulingValue, null, logic);
            if (!(value instanceof Thread thread)) {
                throw new IllegalStateException(
                        "javax.realtime.RealtimeThread is not a java.lang.Thread");
            }
            return thread;
        } catch (ClassNotFoundException
                 | NoSuchMethodException
                 | InstantiationException
                 | IllegalAccessException
                 | InvocationTargetException failure) {
            throw new IllegalStateException(
                    "RTSJ 2 realtime-thread bootstrap is incompatible with this runtime",
                    failure);
        }
    }
}
