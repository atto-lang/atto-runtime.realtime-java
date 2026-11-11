//! Minimal byte-only transport boundary for a guest NATS broker.
//!
//! IMPORTANT: The Rust adapter is NOT a NATS broker. It knows no NATS grammar,
//! routing rules, sessions, permissions, JetStream streams or acknowledgments.
//! It relays raw socket bytes to/from a separate executable's stdin/stdout.
//! Production admission additionally requires independently proven guest
//! executable provenance and a verified Atto broker implementation.

use std::ffi::OsString;
use std::io::{self, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{mpsc, Arc};
use std::thread;
use std::time::Duration;

pub const DEFAULT_SESSION_TIMEOUT: Duration = Duration::from_secs(30);
pub const MAX_ACTIVE_SESSIONS: usize = 64;

#[derive(Clone)]
pub struct Config {
    pub guest_binary: PathBuf,
    pub guest_args: Vec<OsString>,
    pub max_active_sessions: usize,
    pub session_timeout: Duration,
    /// Only for bounded integration tests. Production passes None.
    pub max_accepts: Option<usize>,
}

impl Config {
    pub fn validate(&self) -> io::Result<()> {
        if self.max_active_sessions == 0 || self.max_active_sessions > MAX_ACTIVE_SESSIONS {
            return Err(invalid("invalid session budget"));
        }
        if self.session_timeout.is_zero() || self.session_timeout > Duration::from_secs(300) {
            return Err(invalid("invalid session timeout"));
        }
        if self.guest_binary.as_os_str().is_empty() {
            return Err(invalid("missing guest executable"));
        }
        Ok(())
    }
}

fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, message)
}

pub fn ensure_loopback(address: SocketAddr) -> io::Result<()> {
    if !address.ip().is_loopback() {
        return Err(invalid("public/non-loopback listener forbidden until TLS and auth"));
    }
    Ok(())
}

pub fn verify_guest_binary(
    guest_binary: &Path,
    expected_sha256: &str,
    compiler_source_sha: &str,
) -> io::Result<()> {
    if compiler_source_sha.len() != 40
        || !compiler_source_sha.bytes().all(|byte| byte.is_ascii_hexdigit())
        || compiler_source_sha.bytes().all(|byte| byte == b'0')
    {
        return Err(invalid("exact compiler source SHA must be nonzero 40-hex"));
    }
    if expected_sha256.len() != 64
        || !expected_sha256.bytes().all(|byte| byte.is_ascii_hexdigit())
        || expected_sha256.bytes().all(|byte| byte == b'0')
    {
        return Err(invalid("guest binary SHA-256 must be nonzero 64-hex"));
    }
    let metadata = std::fs::symlink_metadata(guest_binary)?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(invalid("guest binary must be a regular non-symlink file"));
    }
    // Explicit host utility invocation; NO shell and no network access.
    let digest = Command::new("/usr/bin/sha256sum").arg(guest_binary).output()?;
    if !digest.status.success() {
        return Err(invalid("sha256sum failed"));
    }
    let digest_text =
        String::from_utf8(digest.stdout).map_err(|_| invalid("invalid digest output"))?;
    let actual = digest_text
        .split_ascii_whitespace()
        .next()
        .ok_or_else(|| invalid("empty sha256sum output"))?;
    if !actual.eq_ignore_ascii_case(expected_sha256) {
        return Err(invalid("guest binary digest mismatch"));
    }
    Ok(())
}

struct AdmissionGuard {
    active: Arc<AtomicUsize>,
}

impl Drop for AdmissionGuard {
    fn drop(&mut self) {
        self.active.fetch_sub(1, Ordering::AcqRel);
    }
}

fn spawn_guest(cfg: &Config) -> io::Result<Child> {
    let mut command = Command::new(&cfg.guest_binary);
    command.args(&cfg.guest_args);
    // Fail-closed environment: do not pass CI, cloud or GitHub credentials
    // into untrusted guest program executions.
    command.env_clear();
    command.stdin(Stdio::piped());
    command.stdout(Stdio::piped());
    command.stderr(Stdio::inherit());
    command.spawn()
}

fn relay_session(socket: TcpStream, cfg: &Config) -> io::Result<()> {
    socket.set_read_timeout(Some(cfg.session_timeout))?;
    socket.set_write_timeout(Some(cfg.session_timeout))?;
    let mut child = spawn_guest(cfg)?;
    let mut stdin = child
        .stdin
        .take()
        .ok_or_else(|| invalid("child stdin missing"))?;
    let mut stdout = child
        .stdout
        .take()
        .ok_or_else(|| invalid("child stdout missing"))?;
    let mut inbound_socket = socket.try_clone()?;
    let mut outbound_socket = socket.try_clone()?;
    let (tx, rx) = mpsc::channel();

    let in_tx = tx.clone();
    let ingress = thread::spawn(move || {
        let result = io::copy(&mut inbound_socket, &mut stdin);
        drop(stdin);
        let _ = in_tx.send(result);
    });
    let egress = thread::spawn(move || {
        let result = io::copy(&mut stdout, &mut outbound_socket);
        let _ = outbound_socket.shutdown(Shutdown::Write);
        let _ = tx.send(result);
    });

    let mut failed = false;
    for _ in 0..2 {
        match rx.recv_timeout(cfg.session_timeout) {
            Ok(Ok(_)) => {}
            Ok(Err(_)) | Err(_) => {
                failed = true;
                break;
            }
        }
    }
    let _ = socket.shutdown(Shutdown::Both);
    // Kill after both pumps finish as well: a child that leaves stdout
    // closed but continues running must not outlive the session.
    let _ = child.kill();
    let _ = child.wait();
    let _ = ingress.join();
    let _ = egress.join();
    if failed {
        return Err(io::Error::new(
            io::ErrorKind::TimedOut,
            "guest byte relay failed, timed out or lost its worker",
        ));
    }
    Ok(())
}

/// Serves loopback raw TCP; no NATS commands are recognized by this module.
/// Session counts are bounded and reclaimed on worker teardown.
pub fn serve(listener: TcpListener, cfg: Config) -> io::Result<()> {
    cfg.validate()?;
    ensure_loopback(listener.local_addr()?)?;
    let active = Arc::new(AtomicUsize::new(0));
    let mut accepted = 0_usize;
    loop {
        if cfg.max_accepts.is_some_and(|limit| accepted >= limit) {
            break;
        }
        let (socket, _) = listener.accept()?;
        accepted += 1;
        let previous = active.fetch_add(1, Ordering::AcqRel);
        if previous >= cfg.max_active_sessions {
            active.fetch_sub(1, Ordering::AcqRel);
            let _ = socket.shutdown(Shutdown::Both);
            continue;
        }
        let worker_cfg = cfg.clone();
        let worker_active = Arc::clone(&active);
        thread::spawn(move || {
            let _guard = AdmissionGuard {
                active: worker_active,
            };
            if let Err(error) = relay_session(socket, &worker_cfg) {
                eprintln!("byte-only session failed closed: {error}");
            }
        });
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn listener_must_be_loopback_only() {
        assert!(ensure_loopback("127.0.0.1:4222".parse().unwrap()).is_ok());
        assert!(ensure_loopback("[::1]:4222".parse().unwrap()).is_ok());
        assert!(ensure_loopback("0.0.0.0:4222".parse().unwrap()).is_err());
        assert!(ensure_loopback("[::]:4222".parse().unwrap()).is_err());
    }

    #[test]
    fn invalid_session_budgets_are_rejected() {
        let fixture = PathBuf::from("missing");
        let mut cfg = Config {
            guest_binary: fixture,
            guest_args: Vec::new(),
            max_active_sessions: 0,
            session_timeout: DEFAULT_SESSION_TIMEOUT,
            max_accepts: None,
        };
        assert!(cfg.validate().is_err());
        cfg.max_active_sessions = MAX_ACTIVE_SESSIONS + 1;
        assert!(cfg.validate().is_err());
        cfg.max_active_sessions = 2;
        assert!(cfg.validate().is_ok());
        cfg.session_timeout = Duration::ZERO;
        assert!(cfg.validate().is_err());
    }

    #[test]
    fn hashes_and_compiler_pins_cannot_be_placeholders() {
        let missing = Path::new("/nonexistent/atto-guest");
        assert!(verify_guest_binary(missing, "0".repeat(64).as_str(), "f".repeat(40).as_str()).is_err());
        assert!(verify_guest_binary(missing, "f".repeat(64).as_str(), "main").is_err());
    }
}
