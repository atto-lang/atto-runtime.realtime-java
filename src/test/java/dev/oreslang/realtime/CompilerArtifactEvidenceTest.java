package dev.oreslang.realtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class CompilerArtifactEvidenceTest {
    @TempDir Path temp;

    @Test
    void manifestMustMatchExactClassfileBytes() throws Exception {
        Path classFile = temp.resolve("RtPrimitiveModule.class");
        Files.writeString(classFile, "abc", StandardCharsets.UTF_8);
        Path manifest = temp.resolve("oreslang-rt-artifact-v1.properties");
        Files.writeString(manifest, """
                schema=oreslang.realtime-artifact.v1
                class.name=RtPrimitiveModule
                bytecode.sha256=ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
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
                """, StandardCharsets.UTF_8);

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
        Path classFile = temp.resolve("RtPrimitiveModule.class");
        Files.writeString(classFile, "abc", StandardCharsets.UTF_8);
        Path manifest = temp.resolve("bad.properties");
        Files.writeString(manifest, """
                schema=oreslang.realtime-artifact.v1
                bytecode.sha256=ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
                source.reachable_functions=1
                source.frontend_type_ownership_checked=true
                source.closed_world=true
                source.bounded_control_flow=true
                classfile.verified_methods=1
                classfile.no_allocation_opcodes=false
                classfile.no_dynamic_dispatch=true
                classfile.no_monitor_operations=true
                classfile.acyclic_call_graph=true
                classfile.assurance=SOURCE_AND_CLASSFILE_PRIMITIVE_SUBSET
                """, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () ->
                CompilerArtifactEvidence.loadBound(manifest, classFile));
    }
}
