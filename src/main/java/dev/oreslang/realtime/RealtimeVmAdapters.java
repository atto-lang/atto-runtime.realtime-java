package dev.oreslang.realtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Discovers explicit vendor adapters. There is intentionally no generic JVM
 * fallback: absence of a provider means the VM is not qualified by Oreslang.
 */
public final class RealtimeVmAdapters {
    private RealtimeVmAdapters() { }

    public static List<RealtimeVmAdapter> discover() {
        ArrayList<RealtimeVmAdapter> adapters = new ArrayList<>();
        ServiceLoader.load(RealtimeVmAdapter.class).forEach(adapters::add);
        adapters.sort(Comparator.comparing(RealtimeVmAdapter::id));
        return List.copyOf(adapters);
    }
}
