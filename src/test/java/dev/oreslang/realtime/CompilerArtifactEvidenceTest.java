package dev.oreslang.realtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class CompilerArtifactEvidenceTest {
    @TempDir Path temp;

    private static final String SHA_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private String validManifest() {
        return """
                schema=oreslang.realtime-artifact.v1
                class.name=RtPrimitiveModule
                bytecode.sha256=%s
                source.reachable_functions=2
                source.frontend_type_ownership_checked=true
                source.closed_world=true
                source.bounded_control_flow=true
                classfile.verified_methods=2
                classfile.no_allocation_opcodes=true
                classfile.no_dynamic_dispatch=true
                classfile.no_monitor_operations=true
                classfile.acyclic_call_graph=true
                classfile.assurance=SOURCE_AND_CLASSFILE_PRIMITIVE_SUBSET;ACYCLIC;WCET_AND_VM_QUALIFICATION_REQUIRED
                """.formatted(SHA_ABC);
    }

    private Path writeClass() throws Exception {
        Path classFile = temp.resolve("RtPrimitiveModule.class");
        Files.writeString(classFile, "abc", StandardCharsets.UTF_8);
        return classFile;
    }

    private Path writeManifest(String body) throws Exception {
        Path manifest = temp.resolve("oreslang-rt-artifact-v1.properties");
        Files.writeString(manifest, body, StandardCharsets.UTF_8);
        return manifest;
    }

    @Test
    void manifestMustMatchExactClassfileBytes() throws Exception {
        Path classFile = writeClass();
        Path manifest = writeManifest(validManifest());

        CompilerArtifactEvidence evidence =
                CompilerArtifactEvidence.loadBound(manifest, classFile);
        assertTrue(evidence.complete());
        assertEquals(2, evidence.verifiedMethods());

        Files.writeString(classFile, "abcd", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () ->
                CompilerArtifactEvidence.loadBound(manifest, classFile));
    }

    @Test
    void falseProofBitIsRejectedInsteadOfSilentlyDowngrading() throws Exception {
        Path classFile = writeClass();
        Path manifest = writeManifest(validManifest().replace(
                "classfile.no_allocation_opcodes=true",
                "classfile.no_allocation_opcodes=false"));
        assertThrows(IllegalArgumentException.class, () ->
                CompilerArtifactEvidence.loadBound(manifest, classFile));
    }

    @Test
    void assuranceIsExactTokenSetNotSubstringEvidence() throws Exception {
        Path classFile = writeClass();
        for (String assurance : new String[] {
                "NOT_SOURCE_AND_CLASSFILE_PRIMITIVE_SUBSET;ACYCLIC;WCET_AND_VM_QUALIFICATION_REQUIRED",
                "SOURCE_AND_CLASSFILE_PRIMITIVE_SUBSET;ACYCLIC",
                "SOURCE_AND_CLASSFILE_PRIMITIVE_SUBSET;ACYCLIC;WCET_AND_VM_QUALIFICATION_REQUIRED;EXTRA",
                "SOURCE_AND_CLASSFILE_PRIMITIVE_SUBSET;ACYCLIC;ACYCLIC;WCET_AND_VM_QUALIFICATION_REQUIRED"
        }) {
            Path manifest = writeManifest(validManifest().replace(
                    "SOURCE_AND_CLASSFILE_PRIMITIVE_SUBSET;ACYCLIC;WCET_AND_VM_QUALIFICATION_REQUIRED",
                    assurance));
            assertThrows(IllegalArgumentException.class, () ->
                    CompilerArtifactEvidence.loadBound(manifest, classFile), assurance);
        }
    }

    @Test
    void duplicateUnknownAndEscapedManifestKeysFailClosed() throws Exception {
        Path classFile = writeClass();

        Path duplicate = writeManifest(validManifest()
                + "source.closed_world=true\n");
        assertThrows(IllegalArgumentException.class, () ->
                CompilerArtifactEvidence.loadBound(duplicate, classFile));

        Path unknown = writeManifest(validManifest()
                + "attacker.claim=true\n");
        assertThrows(IllegalArgumentException.class, () ->
                CompilerArtifactEvidence.loadBound(unknown, classFile));

        Path escaped = writeManifest(validManifest().replace(
                "source.closed_world=true", "source.closed\\_world=true"));
        assertThrows(IllegalArgumentException.class, () ->
                CompilerArtifactEvidence.loadBound(escaped, classFile));
    }

    @Test
    void missingKeyAndMalformedClassNameFailClosed() throws Exception {
        Path classFile = writeClass();
        Path missing = writeManifest(validManifest().replace(
                "source.closed_world=true\n", ""));
        assertThrows(IllegalArgumentException.class, () ->
                CompilerArtifactEvidence.loadBound(missing, classFile));

        Path badName = writeManifest(validManifest().replace(
                "class.name=RtPrimitiveModule",
                "class.name=../RtPrimitiveModule"));
        assertThrows(IllegalArgumentException.class, () ->
                CompilerArtifactEvidence.loadBound(badName, classFile));
    }

    @Test
    void manifestAndClassfileHaveHardInputBounds() throws Exception {
        Path classFile = writeClass();
        Path oversizedManifest = temp.resolve("oversized.properties");
        Files.write(oversizedManifest, new byte[64 * 1024 + 1]);
        assertThrows(IllegalArgumentException.class, () ->
                CompilerArtifactEvidence.loadBound(oversizedManifest, classFile));

        Path manifest = writeManifest(validManifest());
        Path oversizedClass = temp.resolve("oversized.class");
        Files.write(oversizedClass, new byte[2 * 1024 * 1024 + 1]);
        assertThrows(IllegalArgumentException.class, () ->
                CompilerArtifactEvidence.loadBound(manifest, oversizedClass));
    }
}
