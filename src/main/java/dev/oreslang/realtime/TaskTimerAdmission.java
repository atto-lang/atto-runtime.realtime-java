package dev.oreslang.realtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fail-closed *policy preflight* for Task-owned and actor-owned timers in a
 * qualified hard-RT deployment. This does not implement a timer wheel, perform
 * WCET analysis, or attest the truth of vendor-provided evidence.
 *
 * <p>Call this in the non-real-time admission/control domain, never from the
 * timing-critical task or timer interrupt path: evaluation allocates.</p>
 */
public final class TaskTimerAdmission {
    private TaskTimerAdmission() { }

    public record Evidence(
            int fixedTimerCapacity,
            int reservedReadyCapacity,
            boolean monotonicAbsoluteClockQualified,
            boolean fixedPreallocatedAllocationFreeTimerPath,
            boolean boundedCrossThreadIngress,
            boolean timerThreadPublishesOpaqueTokensOnly,
            boolean boundedPriorityAwareRtWakeLane,
            boolean actorTaskAndScopeGenerationFencing,
            boolean ownerLeaseUntilPhysicalRetirement,
            boolean boundedUnregisterAndCancellationCleanup,
            boolean firstGuestInstructionMeasurement,
            boolean timerAwaitReusesExistingTaskContinuation,
            boolean earliestInheritedDeadlineEnforced,
            boolean phaseAccuratePeriodicRelease,
            boolean onTargetWakeAndWcetEvidence) { }

    /**
     * All components are independently established upper bounds in the same
     * monotonic clock domain. No statistical percentiles or average latency.
     * Periodic releases use fixed absolute phase, not demand-driven next().
     */
    public record Budget(
            long periodNanos,
            long completionDeadlineNanos,
            long startDeadlineNanos,
            long releaseJitterNanos,
            long wakeQueueNanos,
            long nonPreemptibleBlockingNanos,
            long claimToInstructionNanos,
            long taskWcetNanos,
            long higherPriorityInterferenceNanos,
            long cancelCleanupWcetNanos,
            long cancellationDeadlineNanos) {

        public Budget {
            if (periodNanos <= 0 || completionDeadlineNanos <= 0
                    || startDeadlineNanos <= 0 || cancellationDeadlineNanos <= 0) {
                throw new IllegalArgumentException("period and deadlines must be positive");
            }
            if (startDeadlineNanos > completionDeadlineNanos
                    || completionDeadlineNanos > periodNanos
                    || cancellationDeadlineNanos > periodNanos) {
                throw new IllegalArgumentException("deadlines must fit the declared period");
            }
            if (releaseJitterNanos < 0 || wakeQueueNanos < 0
                    || nonPreemptibleBlockingNanos < 0 || claimToInstructionNanos < 0
                    || taskWcetNanos < 0 || higherPriorityInterferenceNanos < 0
                    || cancelCleanupWcetNanos < 0) {
                throw new IllegalArgumentException("timing bounds cannot be negative");
            }
        }

        public boolean withinStartDeadline() {
            return sumWithin(startDeadlineNanos, releaseJitterNanos, wakeQueueNanos,
                    nonPreemptibleBlockingNanos, claimToInstructionNanos);
        }

        public boolean withinCompletionDeadline() {
            return sumWithin(completionDeadlineNanos, releaseJitterNanos, wakeQueueNanos,
                    nonPreemptibleBlockingNanos, claimToInstructionNanos,
                    taskWcetNanos, higherPriorityInterferenceNanos);
        }

        public boolean withinCancellationDeadline() {
            return sumWithin(cancellationDeadlineNanos, releaseJitterNanos, wakeQueueNanos,
                    nonPreemptibleBlockingNanos, claimToInstructionNanos,
                    cancelCleanupWcetNanos, higherPriorityInterferenceNanos);
        }

        private static boolean sumWithin(long limit, long... terms) {
            long result = 0L;
            try {
                for (long term : terms) result = Math.addExact(result, term);
            } catch (ArithmeticException overflow) {
                return false;
            }
            return result <= limit;
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
            HardRealtimeAdmission.TimingBudget totalWorkloadBudget,
            Evidence timers,
            Budget timerTaskBudget) {
        Objects.requireNonNull(timers, "timers");
        Objects.requireNonNull(timerTaskBudget, "timerTaskBudget");
        // The general workload budget must already include timer/Task effects;
        // the dedicated timer bound cannot substitute for the base gate.
        ArrayList<String> failures = new ArrayList<>(
                HardRealtimeAdmission.evaluate(presence, artifact, qualification,
                        totalWorkloadBudget).failures());
        need(timers.fixedTimerCapacity() > 0, "timer registration capacity is not positive", failures);
        need(timers.reservedReadyCapacity() > 0, "no reserved RT wake capacity", failures);
        need(timers.reservedReadyCapacity() <= timers.fixedTimerCapacity(),
                "RT wake capacity exceeds preallocated timer capacity", failures);
        need(timers.monotonicAbsoluteClockQualified(), "qualified absolute monotonic clock missing", failures);
        need(timers.fixedPreallocatedAllocationFreeTimerPath(), "timer hot path can allocate or grow", failures);
        need(timers.boundedCrossThreadIngress(), "unbounded timer ingress", failures);
        need(timers.timerThreadPublishesOpaqueTokensOnly(), "timer thread may execute guest code", failures);
        need(timers.boundedPriorityAwareRtWakeLane(), "bounded reserved RT dispatch lane missing", failures);
        need(timers.actorTaskAndScopeGenerationFencing(), "timer ownership/generation fencing missing", failures);
        need(timers.ownerLeaseUntilPhysicalRetirement(), "Task cleanup/physical retirement lease missing", failures);
        need(timers.boundedUnregisterAndCancellationCleanup(), "cancellation/expiry teardown unbounded", failures);
        need(timers.firstGuestInstructionMeasurement(), "first guest instruction timestamp unproven", failures);
        need(timers.timerAwaitReusesExistingTaskContinuation(), "timer await spawns an implicit Task", failures);
        need(timers.earliestInheritedDeadlineEnforced(), "scope/actor deadline inheritance missing", failures);
        need(timers.phaseAccuratePeriodicRelease(), "periodic release is demand-driven or phase-unbounded", failures);
        need(timers.onTargetWakeAndWcetEvidence(), "on-target bounded timer/Task timing evidence missing", failures);
        need(timerTaskBudget.withinStartDeadline(), "timer-to-first-instruction start deadline exceeded", failures);
        need(timerTaskBudget.withinCompletionDeadline(), "Task completion deadline exceeded", failures);
        need(timerTaskBudget.withinCancellationDeadline(), "physical cancellation cleanup deadline exceeded", failures);
        return new Result(failures);
    }

    private static void need(boolean condition, String message, List<String> failures) {
        if (!condition) failures.add(message);
    }
}
