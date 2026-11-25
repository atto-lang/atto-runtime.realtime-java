package dev.oreslang.realtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class RtsjThreadBootstrapTest {
    @Test
    void ordinaryJdkCannotPretendToCreateRealtimeThread() {
        if (!RtsjProbe.probe().coreApiPresent()) {
            assertThrows(IllegalStateException.class, () ->
                    RtsjThreadBootstrap.createRealtimeThread(10, () -> { }));
        }
    }

    @Test
    void noGenericVmAdapterIsInstalledByThisModule() {
        assertTrue(RealtimeVmAdapters.discover().isEmpty(),
                "vendor qualification must be supplied explicitly");
    }
}
