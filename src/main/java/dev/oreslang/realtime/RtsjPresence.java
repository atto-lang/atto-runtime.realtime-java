package dev.oreslang.realtime;

/**
 * Discovery only. Presence of RTSJ classes is not hard-real-time evidence.
 *
 * <p>RTSJ 2.0 moved scoped-memory APIs into {@code javax.realtime.memory} and
 * deprecated {@code NoHeapRealtimeThread}. The legacy class is therefore
 * recorded for compatibility but is not a prerequisite for admission.</p>
 */
public record RtsjPresence(
        boolean realtimeThread,
        boolean memoryArea,
        boolean scopedMemory,
        boolean schedulingParameters,
        boolean releaseParameters,
        boolean legacyNoHeapRealtimeThread) {

    public boolean coreApiPresent() {
        return realtimeThread
                && memoryArea
                && scopedMemory
                && schedulingParameters
                && releaseParameters;
    }

    /**
     * Compatibility alias retained for callers of the initial prototype.
     */
    public boolean apiSurfacePresent() {
        return coreApiPresent();
    }
}
