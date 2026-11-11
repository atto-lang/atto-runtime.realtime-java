//! Explicit opt-in live Atto guest gate: never run on ordinary Rust CI.
//! An actual Atto executable must supply NATS Core protocol semantics.
//! A mock fixture or stock nats-server is NOT acceptable evidence.
use atto_nats_tcp_interop::probe;
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::thread;
use std::time::Duration;

struct ChildGuard(Child);
impl Drop for ChildGuard {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

fn required(name: &str) -> String {
    let value = std::env::var(name).unwrap_or_else(|_| panic!("missing {name}"));
    assert!(!value.trim().is_empty(), "{name} must not be empty");
    value
}

fn exact_sha(value: &str, digits: usize) -> bool {
    value.len() == digits
        && value.bytes().all(|byte| byte.is_ascii_hexdigit())
        && value.bytes().any(|byte| byte != b'0')
}

#[test]
fn actual_atto_process_passes_binary_nats_core_socket_exchange() {
    let guest_path = PathBuf::from(required("ATTO_NATS_GUEST_BINARY"));
    assert!(guest_path.is_absolute(), "guest binary path must be absolute");
    let sha256 = required("ATTO_NATS_GUEST_SHA256");
    let compiler_sha = required("ATTO_NATS_COMPILER_SHA");
    let source_sha = required("ATTO_NATS_SOURCE_SHA");
    assert!(exact_sha(&sha256, 64), "invalid guest digest pin");
    assert!(exact_sha(&compiler_sha, 40), "invalid compiler commit pin");
    assert!(exact_sha(&source_sha, 40), "invalid Atto application commit pin");

    // An externally reviewed provenance receipt is separately mandatory;
    // simply providing a text file cannot verify its signature or claims.
    let provenance = PathBuf::from(required("ATTO_NATS_PROVENANCE_RECEIPT"));
    assert!(provenance.is_file(), "missing independent provenance receipt");
    let receipt_data = std::fs::read_to_string(provenance).unwrap();
    assert!(receipt_data.contains(&compiler_sha));
    assert!(receipt_data.contains(&sha256));
    assert!(receipt_data.contains(&source_sha));

    let free = TcpListener::bind("127.0.0.1:0").unwrap();
    let address: SocketAddr = free.local_addr().unwrap();
    drop(free);

    let transport = env!("CARGO_BIN_EXE_atto-nats-byte-transport");
    let child = Command::new(transport)
        .arg(address.to_string())
        .arg(&guest_path)
        .arg(&sha256)
        .arg(&compiler_sha)
        .env_clear()
        .env("PATH", "/usr/bin:/bin")
        .stdout(Stdio::null())
        .stderr(Stdio::inherit())
        .spawn()
        .expect("byte-only transport could not start");
    let mut server = ChildGuard(child);

    let mut ready = false;
    for _ in 0..50 {
        if let Some(status) = server.0.try_wait().unwrap() {
            panic!("byte-only transport exited before accepting TCP: {status}");
        }
        if TcpStream::connect_timeout(&address, Duration::from_millis(100)).is_ok() {
            ready = true;
            break;
        }
        thread::sleep(Duration::from_millis(100));
    }
    assert!(ready, "no loopback TCP listener admitted after timeout");

    // The probe performs actual CONNECT/PING/SUB/PUB/MSG/UNSUB and exact
    // embedded NUL/CRLF/non-UTF8 payload comparison against the guest.
    probe(address).expect("REAL ATTO GUEST FAILED NATS TCP INTEROPERABILITY");
    println!("EVIDENCE_SCOPE=LIVE_ATTO_GUEST_TCP_INTEROPERABILITY_CANDIDATE");
    println!("ATTO_NATS_SOURCE_SHA={source_sha}");
    println!("ATTO_NATS_COMPILER_SHA={compiler_sha}");
    println!("ATTO_NATS_GUEST_SHA256={sha256}");
}
