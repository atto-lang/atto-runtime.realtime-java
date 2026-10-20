package dev.oreslang.realtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * Build-time evidence produced by the Oreslang compiler/classfile verifier.
 * This is distinct from VM/deployment qualification: both are required.
 */
public record CompilerArtifactEvidence(
        String bytecodeSha256,
        int verifiedMethods,
        boolean sourceOwnershipChecked,
        boolean closedWorldCalls,
        boolean boundedControlFlow,
        boolean classfileAudited,
        boolean noAllocationBytecodes,
        boolean noDynamicDispatch,
        boolean noMonitorOperations,
        boolean acyclicCallGraph) {

    private static final String SCHEMA = "oreslang.realtime-artifact.v1";

    public CompilerArtifactEvidence {
        bytecodeSha256 = Objects.requireNonNull(bytecodeSha256, "bytecodeSha256")
                .toLowerCase(Locale.ROOT);
        if (!bytecodeSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "bytecodeSha256 must be 64 lowercase/uppercase hex digits");
        }
        if (verifiedMethods <= 0) {
            throw new IllegalArgumentException("verifiedMethods must be positive");
        }
    }

    public boolean complete() {
        return sourceOwnershipChecked
                && closedWorldCalls
                && boundedControlFlow
                && classfileAudited
                && noAllocationBytecodes
                && noDynamicDispatch
                && noMonitorOperations
                && acyclicCallGraph;
    }

    /**
     * Loads compiler evidence and cryptographically binds it to the exact
     * classfile that will be handed to the AOT toolchain.
     */
    public static CompilerArtifactEvidence loadBound(
            Path manifestFile, Path classFile) throws IOException {
        Objects.requireNonNull(manifestFile, "manifestFile");
        Objects.requireNonNull(classFile, "classFile");
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(manifestFile)) {
            properties.load(in);
        }
        requireValue(properties, "schema", SCHEMA);

        String expectedSha = required(properties, "bytecode.sha256")
                .toLowerCase(Locale.ROOT);
        String observedSha = sha256(Files.readAllBytes(classFile));
        if (!expectedSha.equals(observedSha)) {
            throw new IllegalArgumentException(
                    "RT artifact digest mismatch: manifest=" + expectedSha
                            + " classfile=" + observedSha);
        }

        int verifiedMethods;
        int reachableFunctions;
        try {
            verifiedMethods = Integer.parseInt(
                    required(properties, "classfile.verified_methods"));
            reachableFunctions = Integer.parseInt(
                    required(properties, "source.reachable_functions"));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "RT artifact manifest contains an invalid integer", invalid);
        }
        if (verifiedMethods != reachableFunctions) {
            throw new IllegalArgumentException(
                    "source/classfile method-set evidence does not match");
        }

        String classfileAssurance = required(properties, "classfile.assurance");
        return new CompilerArtifactEvidence(
                observedSha,
                verifiedMethods,
                requiredTrue(properties, "source.frontend_type_ownership_checked"),
                requiredTrue(properties, "source.closed_world"),
                requiredTrue(properties, "source.bounded_control_flow"),
                classfileAssurance.contains("SOURCE_AND_CLASSFILE_PRIMITIVE_SUBSET"),
                requiredTrue(properties, "classfile.no_allocation_opcodes"),
                requiredTrue(properties, "classfile.no_dynamic_dispatch"),
                requiredTrue(properties, "classfile.no_monitor_operations"),
                requiredTrue(properties, "classfile.acyclic_call_graph"));
    }

    public static CompilerArtifactEvidence unverified(String sha256) {
        return new CompilerArtifactEvidence(
                sha256, 1, false, false, false, false, false, false, false, false);
    }

    private static boolean requiredTrue(Properties properties, String key) {
        String value = required(properties, key);
        if (!"true".equals(value)) {
            throw new IllegalArgumentException(
                    "RT artifact manifest requires " + key + "=true");
        }
        return true;
    }

    private static void requireValue(
            Properties properties, String key, String expected) {
        String value = required(properties, key);
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(
                    "RT artifact manifest " + key + " must be " + expected);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "RT artifact manifest is missing " + key);
        }
        return value.trim();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
