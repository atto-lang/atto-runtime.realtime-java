//! Executed Rust socket/child-process tests for BYTE RELAY ONLY.
use atto_nats_byte_transport::{serve, verify_guest_binary, Config};
use std::io::{Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::path::PathBuf;
use std::thread;
use std::time::Duration;

fn cfg() -> Config {
    Config {
        guest_binary: PathBuf::from(env!("CARGO_BIN_EXE_echo_fixture")),
        guest_args: Vec::new(),
        max_active_sessions: 2,
        session_timeout: Duration::from_secs(5),
        max_accepts: Some(1),
    }
}

#[test]
fn live_tcp_forwards_arbitrary_binary_without_parsing() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let server = thread::spawn(move || serve(listener, cfg()));
    let mut client = TcpStream::connect(addr).unwrap();
    client.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    let mut bytes = vec![0_u8, 13, 10, 255, b'P', b'U', b'B', b'\n'];
    bytes.extend((0..65536).map(|value| (value % 251) as u8));
    for chunk in bytes.chunks(997) {
        client.write_all(chunk).unwrap();
    }
    client.shutdown(Shutdown::Write).unwrap();
    let mut response = Vec::new();
    client.read_to_end(&mut response).unwrap();
    assert_eq!(response, bytes);
    server.join().unwrap().unwrap();
}

#[test]
fn non_guest_hash_is_rejected_by_admission() {
    let binary = PathBuf::from(env!("CARGO_BIN_EXE_echo_fixture"));
    assert!(verify_guest_binary(&binary, &"0".repeat(64), &"f".repeat(40)).is_err());
}

#[test]
fn matching_binary_hash_admits_only_byte_transport_not_atto_provenance() {
    let binary = PathBuf::from(env!("CARGO_BIN_EXE_echo_fixture"));
    let digest = std::process::Command::new("/usr/bin/sha256sum")
        .arg(&binary)
        .output()
        .unwrap();
    assert!(digest.status.success());
    let text = String::from_utf8(digest.stdout).unwrap();
    let sha = text.split_ascii_whitespace().next().unwrap();
    assert!(verify_guest_binary(&binary, sha, &"f".repeat(40)).is_ok());
    // A SHA match alone cannot prove that this Rust fixture is Atto code.
}
