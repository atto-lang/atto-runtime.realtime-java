package dev.oreslang.realtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TaskTimerAdmissionTest {
    private static RtsjPresence rtsj2() {
        return new RtsjPresence(true, true, true, true, true, false);
    }

    private static CompilerArtifactEvidence artifact() {
        return new CompilerArtifactEvidence(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                2, true, true, true, true, true, true, true, true);
    }

    private static VmQualification qualified() {
        return new VmQualification("synthetic-qualified-vm",
                true, true, true, true, true, true,
                true, true, true, true, true, true,
                true, true, true, true, true, true);
    }

    private static HardRealtimeAdmission.TimingBudget totalWorkload() {
        return new HardRealtimeAdmission.TimingBudget(900_000, 400_000, 80_000, 100_000, 50_000);
    }

    private static TaskTimerAdmission.Evidence evidence() {
        return new TaskTimerAdmission.Evidence(64, 16,
                true, true, true, true, true, true, true,
                true, true, true, true, true, true);
    }

    private static TaskTimerAdmission.Budget budget() {
        return new TaskTimerAdmission.Budget(
                1_000_000, 900_000, 200_000,
                40_000, 30_000, 20_000, 10_000,
                400_000, 100_000, 200_000, 400_000);
    }

    private static TaskTimerAdmission.Result check(
            VmQualification qualification,
            TaskTimerAdmission.Evidence evidence,
            TaskTimerAdmission.Budget budget) {
        return TaskTimerAdmission.evaluate(rtsj2(), artifact(), qualification,
                totalWorkload(), evidence, budget);
    }

    @Test void syntheticFullyQualifiedPolicyCanPass() {
        assertTrue(check(qualified(), evidence(), budget()).admitted());
    }

    @Test void ordinaryJdkCannotAcquireHardRtTimerStatusFromTimerFlags() {
        var result = check(VmQualification.unqualified("ordinary-jdk"), evidence(), budget());
        assertFalse(result.admitted());
        assertTrue(result.failures().stream().anyMatch(s -> s.contains("fully AOT")));
    }

    @Test void zeroReadyCapacityIsDeniedEvenWhenOtherEvidencePasses() {
        var old = evidence();
        var noReady = new TaskTimerAdmission.Evidence(old.fixedTimerCapacity(), 0,
                true, true, true, true, true, true, true, true,
                true, true, true, true, true);
        assertTrue(check(qualified(), noReady, budget()).failures().stream()
                .anyMatch(s -> s.contains("reserved RT wake capacity")));
    }

    @Test void timerThreadMustNotRunGuestWork() {
        var old = evidence();
        var unsafe = new TaskTimerAdmission.Evidence(old.fixedTimerCapacity(), old.reservedReadyCapacity(),
                true, true, true, false, true, true, true, true,
                true, true, true, true, true);
        assertTrue(check(qualified(), unsafe, budget()).failures().stream()
                .anyMatch(s -> s.contains("timer thread")));
    }

    @Test void clockAndGenerationFencingAreSeparateRequirements() {
        var old = evidence();
        var unsafe = new TaskTimerAdmission.Evidence(old.fixedTimerCapacity(), old.reservedReadyCapacity(),
                false, true, true, true, true, false, true, true,
                true, true, true, true, true);
        var failures = check(qualified(), unsafe, budget()).failures();
        assertTrue(failures.stream().anyMatch(s -> s.contains("absolute monotonic clock")));
        assertTrue(failures.stream().anyMatch(s -> s.contains("generation fencing")));
    }

    @Test void lateWakeAndCancellationCleanupBothFailClosed() {
        var late = new TaskTimerAdmission.Budget(
                1_000_000, 900_000, 200_000,
                150_000, 100_000, 20_000, 10_000,
                400_000, 100_000, 500_000, 400_000);
        var failures = check(qualified(), evidence(), late).failures();
        assertTrue(failures.stream().anyMatch(s -> s.contains("start deadline")));
        assertTrue(failures.stream().anyMatch(s -> s.contains("cancellation cleanup")));
    }

    @Test void overflowDoesNotWrapIntoAnAdmittedTimingBudget() {
        var overflow = new TaskTimerAdmission.Budget(
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, 1, 1, 1,
                0, 0, 0, Long.MAX_VALUE);
        assertFalse(overflow.withinStartDeadline());
        assertFalse(overflow.withinCompletionDeadline());
        assertFalse(check(qualified(), evidence(), overflow).admitted());
    }

    @Test void invalidPeriodAndNegativeBudgetAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new TaskTimerAdmission.Budget(100, 101, 90, 0, 0, 0, 0, 0, 0, 0, 90));
        assertThrows(IllegalArgumentException.class, () ->
                new TaskTimerAdmission.Budget(100, 90, 80, -1, 0, 0, 0, 0, 0, 0, 90));
    }

    @Test void immutabilityOfFailuresPreventsLaterCertificationByMutation() {
        var result = check(VmQualification.unqualified("test"), evidence(), budget());
        assertThrows(UnsupportedOperationException.class, () -> result.failures().clear());
    }
}
