package dev.oreslang.realtime;

/**
 * Vendor/deployment integration point for a qualified real-time Java VM.
 *
 * <p>Implementations should live in vendor-specific modules and be registered
 * through {@link java.util.ServiceLoader}. Discovery is not certification:
 * the returned qualification must be derived from a named VM/toolchain and
 * deployment profile whose timing/memory guarantees have actually been
 * established.</p>
 */
public interface RealtimeVmAdapter {
    String id();
    RtsjPresence presence();
    VmQualification qualification();
}
