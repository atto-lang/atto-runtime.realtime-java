package dev.oreslang.realtime;

import java.util.Objects;

/**
 * Explicit evidence supplied by a VM/deployment adapter. A normal JVM must not
 * manufacture this record from API discovery alone.
 *
 * <p>{@code collectorIndependentCriticalExecution} is intentionally
 * implementation-neutral. Some qualified runtimes may use scoped/no-heap
 * execution; others may provide a collector whose activity is preemptible with
 * a documented bound. Merely selecting a low-pause collector is insufficient.</p>
 */
public record VmQualification(
        String vmName,
        boolean fullGuestAot,
        boolean runtimeCodegenDisabled,
        boolean dynamicLoadingDisabled,
        boolean collectorIndependentCriticalExecution,
        boolean scopedOrNativeActorMemory,
        boolean deterministicRegionReclamation,
        boolean memoryPreallocated,
        boolean memoryPrefaultedAndLocked,
        boolean criticalWorkersPrestarted,
        boolean boundedSchedulerDispatch,
        boolean boundedPriorityInversion,
        boolean criticalCpuIsolation,
        boolean boundedCancellationCleanup,
        boolean blockingIoExcluded,
        boolean ffiQualified,
        boolean untrustedJitExcluded,
        boolean timingEvidence,
        boolean deploymentRtPolicy) {

    public VmQualification {
        vmName = Objects.requireNonNull(vmName, "vmName").trim();
        if (vmName.isEmpty()) throw new IllegalArgumentException("vmName cannot be blank");
    }

    public static VmQualification unqualified(String vmName) {
        return new VmQualification(
                vmName, false, false, false, false, false, false, false,
                false, false, false, false, false, false, false, false, false,
                false, false);
    }
}
