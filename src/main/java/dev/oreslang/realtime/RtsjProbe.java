package dev.oreslang.realtime;

import java.util.Objects;

/**
 * Reflective RTSJ discovery keeps this integration module buildable on a
 * standard JDK. Vendor-specific adapters can depend on their RTSJ implementation
 * and must provide separate qualification evidence.
 */
public final class RtsjProbe {
    private RtsjProbe() { }

    public static RtsjPresence probe() {
        return probe(Thread.currentThread().getContextClassLoader());
    }

    static RtsjPresence probe(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        return new RtsjPresence(
                present(loader, "javax.realtime.RealtimeThread"),
                present(loader, "javax.realtime.MemoryArea"),
                anyPresent(loader,
                        /* RTSJ 2.0 */
                        "javax.realtime.memory.ScopedMemory",
                        "javax.realtime.memory.LTMemory",
                        /* deprecated RTSJ 1.x compatibility */
                        "javax.realtime.ScopedMemory",
                        "javax.realtime.LTMemory",
                        "javax.realtime.VTMemory"),
                present(loader, "javax.realtime.SchedulingParameters"),
                present(loader, "javax.realtime.ReleaseParameters"),
                present(loader, "javax.realtime.NoHeapRealtimeThread"));
    }

    private static boolean anyPresent(ClassLoader loader, String... names) {
        for (String name : names) {
            if (present(loader, name)) return true;
        }
        return false;
    }

    private static boolean present(ClassLoader loader, String name) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError unavailable) {
            return false;
        }
    }
}
