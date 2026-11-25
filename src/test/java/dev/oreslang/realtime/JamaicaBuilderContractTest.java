package dev.oreslang.realtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class JamaicaBuilderContractTest {
    @Test
    void strictClosedFullCompileIsAdmitted() {
        assertTrue(JamaicaBuilderContract.evaluate(
                List.of("-compile", "-closed")).admitted());
        assertDoesNotThrow(() -> JamaicaBuilderContract.requireStrictGuestAot(
                List.of("-closed", "-compile")));
    }

    @Test
    void profileOrInterpreterBuildsAreNotStrictFullGuestAot() {
        assertFalse(JamaicaBuilderContract.evaluate(
                List.of("-closed", "-interpret")).admitted());
        assertFalse(JamaicaBuilderContract.evaluate(
                List.of("-closed", "-compile", "-useProfile=hot.prof")).admitted());
        assertFalse(JamaicaBuilderContract.evaluate(
                List.of("-closed", "-compile", "-excludeFromCompile=dev.oreslang.Foo")).admitted());
    }

    @Test
    void compileWithoutClosedWorldIsRejected() {
        var result = JamaicaBuilderContract.evaluate(List.of("-compile"));
        assertFalse(result.admitted());
        assertTrue(result.failures().stream().anyMatch(s -> s.contains("-closed")));
    }
}
