package dev.oreslang.realtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class HardRealtimeAdmissionTest {
    private static RtsjPresence rtsj2() {
        return new RtsjPresence(
                true, true, true, true, true,
                false /* NoHeapRealtimeThread is optional/deprecated in RTSJ 2 */);
    }

    private static CompilerArtifactEvidence artifact() {
        return new CompilerArtifactEvidence(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                2, true, true, true, true, true, true, true, true);
    }

    private static VmQualification qualified() {
        return new VmQualification(
                "qualified-test-vm",
                true, true, true, true, true, true,
                true, true, true, true, true, true,
                true, true, true, true, true, true);
    }

    @Test
    void rtsj2DoesNotRequireDeprecatedNoHeapRealtimeThread() {
        assertTrue(rtsj2().coreApiPresent());
        assertFalse(rtsj2().legacyNoHeapRealtimeThread());
        var timing = new HardRealtimeAdmission.TimingBudget(
                1_000_000, 400_000, 100_000, 200_000, 50_000);
        assertTrue(HardRealtimeAdmission.evaluate(
                rtsj2(), artifact(), qualified(), timing).admitted());
    }

    @Test
    void standardJdkDiscoveryDoesNotBecomeQualification() {
        RtsjPresence presence = RtsjProbe.probe();
        VmQualification ordinary = VmQualification.unqualified("standard-jdk");
        var result = HardRealtimeAdmission.evaluate(
                presence,
                artifact(),
                ordinary,
                new HardRealtimeAdmission.TimingBudget(1_000_000, 1, 0, 0, 0));
        assertFalse(result.admitted());
        assertTrue(result.failures().stream()
                .anyMatch(s -> s.contains("fully AOT")));
    }

    @Test
    void qualifiedRtsjDeploymentCanPassThePolicyGate() {
        var timing = new HardRealtimeAdmission.TimingBudget(
                1_000_000, 400_000, 100_000, 200_000, 50_000);
        assertTrue(HardRealtimeAdmission.evaluate(
                rtsj2(), artifact(), qualified(), timing).admitted());
        assertDoesNotThrow(() ->
                HardRealtimeAdmission.require(rtsj2(), artifact(), qualified(), timing));
    }

    @Test
    void apiPresenceAloneNeverPasses() {
        var result = HardRealtimeAdmission.evaluate(
                rtsj2(),
                artifact(),
                VmQualification.unqualified("api-only"),
                new HardRealtimeAdmission.TimingBudget(1_000_000, 1, 0, 0, 0));
        assertFalse(result.admitted());
        assertTrue(result.failures().size() >= 18);
    }

    @Test
    void missingCollectorIndependenceOrDeadlineEvidenceFailsClosed() {
        var q = new VmQualification(
                "almost",
                true, true, true, false, true, true,
                true, true, true, true, true, true,
                true, true, true, true, true, true);
        var result = HardRealtimeAdmission.evaluate(
                rtsj2(),
                artifact(),
                q,
                new HardRealtimeAdmission.TimingBudget(100, 80, 10, 10, 1));
        assertFalse(result.admitted());
        assertTrue(result.failures().stream()
                .anyMatch(s -> s.contains("collector activity")));
        assertTrue(result.failures().stream()
                .anyMatch(s -> s.contains("deadline")));
    }

    @Test
    void lazyWorkersAndDynamicLoadingFailIndependently() {
        var q = new VmQualification(
                "almost",
                true, true, false, true, true, true,
                true, true, false, true, true, true,
                true, true, true, true, true, true);
        var result = HardRealtimeAdmission.evaluate(
                rtsj2(),
                artifact(),
                q,
                new HardRealtimeAdmission.TimingBudget(1_000_000, 1, 0, 0, 0));
        assertFalse(result.admitted());
        assertTrue(result.failures().stream()
                .anyMatch(s -> s.contains("dynamic class/module")));
        assertTrue(result.failures().stream()
                .anyMatch(s -> s.contains("workers")));
    }

    @Test
    void vmQualificationCannotBlessUnverifiedProgramBytecode() {
        var unverified = CompilerArtifactEvidence.unverified(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        var result = HardRealtimeAdmission.evaluate(
                rtsj2(),
                unverified,
                qualified(),
                new HardRealtimeAdmission.TimingBudget(1_000_000, 1, 0, 0, 0));
        assertFalse(result.admitted());
        assertTrue(result.failures().stream()
                .anyMatch(s -> s.contains("artifact evidence")));
    }

    @Test
    void timingOverflowFailsClosed() {
        var timing = new HardRealtimeAdmission.TimingBudget(
                Long.MAX_VALUE, Long.MAX_VALUE, 1, 1, 1);
        assertFalse(timing.withinDeadline());
        assertFalse(HardRealtimeAdmission.evaluate(
                rtsj2(), artifact(), qualified(), timing).admitted());
    }
}
