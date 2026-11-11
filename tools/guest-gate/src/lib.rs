//! Fail-closed checks for *real* Atto guest execution receipts.
//! These tests never pretend to execute the Atto compiler.

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GateFailure {
    MissingOrInvalidCompilerSha,
    MissingOrInvalidBinarySha,
    MissingMarker,
    MissingExecutedGuestOutput,
    ProcessFailure,
}

pub fn valid_sha40(sha: &str) -> bool {
    sha.len() == 40 && sha.bytes().all(|c| c.is_ascii_hexdigit()) && sha.bytes().any(|c| c != b'0')
}

pub fn valid_sha256(sha: &str) -> bool {
    sha.len() == 64 && sha.bytes().all(|c| c.is_ascii_hexdigit()) && sha.bytes().any(|c| c != b'0')
}

pub fn validate_pins(compiler_sha: &str, binary_sha256: &str) -> Result<(), GateFailure> {
    if !valid_sha40(compiler_sha) {
        return Err(GateFailure::MissingOrInvalidCompilerSha);
    }
    if !valid_sha256(binary_sha256) {
        return Err(GateFailure::MissingOrInvalidBinarySha);
    }
    Ok(())
}

pub fn validate_execution(
    process_success: bool,
    stdout: &str,
    expected_marker: &str,
) -> Result<(), GateFailure> {
    if !process_success {
        return Err(GateFailure::ProcessFailure);
    }
    if !expected_marker.starts_with("ATTO_GUEST_EXECUTED:")
        || expected_marker.len() < "ATTO_GUEST_EXECUTED:x".len()
        || expected_marker.contains('\n')
        || expected_marker.contains('\r')
    {
        return Err(GateFailure::MissingMarker);
    }
    // A compiler successfully parsing a program is NOT guest execution.
    // The *runner's stdout* must contain an exact, isolated guest marker.
    if !stdout
        .lines()
        .any(|line| line.trim_end_matches('\r') == expected_marker)
    {
        return Err(GateFailure::MissingExecutedGuestOutput);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exact_compiler_commit_is_mandatory() {
        assert!(valid_sha40("6d8eb70048eafc9a84c5aebe9c1bb06104f16e76"));
        assert!(!valid_sha40("main"));
        assert!(!valid_sha40("0".repeat(40).as_str()));
        assert!(!valid_sha40("g".repeat(40).as_str()));
    }

    #[test]
    fn exact_compiler_binary_digest_is_mandatory() {
        assert!(valid_sha256("f".repeat(64).as_str()));
        assert!(!valid_sha256("0".repeat(64).as_str()));
        assert!(!valid_sha256("UNVERIFIED"));
    }

    #[test]
    fn success_without_guest_marker_is_not_evidence() {
        assert_eq!(
            validate_execution(
                true,
                "compiled successfully",
                "ATTO_GUEST_EXECUTED:state-critical"
            ),
            Err(GateFailure::MissingExecutedGuestOutput)
        );
    }

    #[test]
    fn marker_only_on_stderr_is_not_evidence() {
        assert_eq!(
            validate_execution(true, "", "ATTO_GUEST_EXECUTED:state-critical"),
            Err(GateFailure::MissingExecutedGuestOutput)
        );
    }

    #[test]
    fn failed_process_cannot_claim_execution() {
        assert_eq!(
            validate_execution(
                false,
                "ATTO_GUEST_EXECUTED:state-critical\n",
                "ATTO_GUEST_EXECUTED:state-critical"
            ),
            Err(GateFailure::ProcessFailure)
        );
    }

    #[test]
    fn exact_guest_stdout_line_is_accepted() {
        assert!(validate_execution(
            true,
            "compiler status\nATTO_GUEST_EXECUTED:state-critical\n",
            "ATTO_GUEST_EXECUTED:state-critical",
        )
        .is_ok());
        assert_eq!(
            validate_execution(
                true,
                "prefix ATTO_GUEST_EXECUTED:state-critical\n",
                "ATTO_GUEST_EXECUTED:state-critical"
            ),
            Err(GateFailure::MissingExecutedGuestOutput)
        );
    }

    #[test]
    fn blank_or_multiline_marker_is_rejected() {
        assert_eq!(
            validate_execution(true, "ATTO_GUEST_EXECUTED:\n", "ATTO_GUEST_EXECUTED:"),
            Err(GateFailure::MissingMarker)
        );
        assert_eq!(
            validate_execution(true, "ATTO_GUEST_EXECUTED:a\n", "ATTO_GUEST_EXECUTED:a\n"),
            Err(GateFailure::MissingMarker)
        );
    }
}
