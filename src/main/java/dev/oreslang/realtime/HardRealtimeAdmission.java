package dev.oreslang.realtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Hard-real-time Java admission. This is intentionally stricter than merely
 * running on a low-pause collector or finding RTSJ classes on the class path.
 */
public final class HardRealtimeAdmission {
    private HardRealtimeAdmission() { }

    public record TimingBudget(
            long deadlineNanos,
            long wcetNanos,
            long blockingNanos,
            long interferenceNanos,
            long jitterNanos) {

        public TimingBudget {
            if (deadlineNanos <= 0) throw new IllegalArgumentException("deadline must be positive");
            if (wcetNanos < 0 || blockingNanos < 0 || interferenceNanos < 0 || jitterNanos < 0) {
                throw new IllegalArgumentException("timing components cannot be negative");
            }
        }

        public boolean withinDeadline() {
            try {
                long response = Math.addExact(
                        Math.addExact(wcetNanos, blockingNanos),
                        Math.addExact(interferenceNanos, jitterNanos));
                return response <= deadlineNanos;
            } catch (ArithmeticException overflow) {
                return false;
            }
        }
    }

    public record Result(List<String> failures) {
        public Result {
            failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        }
        public boolean admitted() { return failures.isEmpty(); }
    }

    public static Result evaluate(
            RtsjPresence presence,
            CompilerArtifactEvidence artifact,
            VmQualification qualification,
            TimingBudget timing) {
        Objects.requireNonNull(presence, "presence");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(qualification, "qualification");
        Objects.requireNonNull(timing, "timing");

        ArrayList<String> failures = new ArrayList<>();
        need(presence.coreApiPresent(), "required RTSJ core API surface is absent", failures);
        need(artifact.complete(), "compiler/classfile artifact evidence is incomplete", failures);
        need(qualification.fullGuestAot(), "guest execution is not fully AOT", failures);
        need(qualification.runtimeCodegenDisabled(), "runtime code generation remains enabled", failures);
        need(qualification.dynamicLoadingDisabled(), "dynamic class/module loading remains enabled in the critical domain", failures);
        need(qualification.collectorIndependentCriticalExecution(), "collector activity can delay critical execution beyond the qualified bound", failures);
        need(qualification.scopedOrNativeActorMemory(), "actor state lacks scoped/native non-tracing storage", failures);
        need(qualification.deterministicRegionReclamation(), "actor region reclamation is not deterministic", failures);
        need(qualification.memoryPreallocated(), "critical memory is not preallocated", failures);
        need(qualification.memoryPrefaultedAndLocked(), "critical memory is not prefaulted/locked", failures);
        need(qualification.criticalWorkersPrestarted(), "critical workers may be created lazily after admission", failures);
        need(qualification.boundedSchedulerDispatch(), "scheduler dispatch is not bounded", failures);
        need(qualification.boundedPriorityInversion(), "priority inversion is not bounded", failures);
        need(qualification.criticalCpuIsolation(), "critical execution is not isolated from general workers", failures);
        need(qualification.boundedCancellationCleanup(), "cancellation/cleanup is not bounded", failures);
        need(qualification.blockingIoExcluded(), "blocking I/O remains reachable", failures);
        need(qualification.ffiQualified(), "unqualified FFI/native code remains reachable", failures);
        need(qualification.untrustedJitExcluded(), "untrusted/JIT code can enter the critical domain", failures);
        need(qualification.timingEvidence(), "WCET/response-time evidence is missing", failures);
        need(qualification.deploymentRtPolicy(), "deployment does not provide a qualified RT scheduler policy", failures);
        need(timing.withinDeadline(), "response-time budget exceeds the deadline or overflowed", failures);
        return new Result(failures);
    }

    public static void require(
            RtsjPresence presence,
            CompilerArtifactEvidence artifact,
            VmQualification qualification,
            TimingBudget timing) {
        Result result = evaluate(presence, artifact, qualification, timing);
        if (!result.admitted()) {
            throw new IllegalStateException(
                    "hard real-time Java admission denied for " + qualification.vmName()
                            + ": " + String.join("; ", result.failures()));
        }
    }

    private static void need(boolean value, String reason, List<String> failures) {
        if (!value) failures.add(reason);
    }
}
