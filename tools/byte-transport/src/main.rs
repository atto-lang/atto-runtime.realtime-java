use atto_nats_byte_transport::{
    ensure_loopback, serve, verify_guest_binary, Config, DEFAULT_SESSION_TIMEOUT,
};
use std::env;
use std::ffi::OsString;
use std::net::{SocketAddr, TcpListener};
use std::path::PathBuf;
use std::process::ExitCode;

fn run() -> Result<(), String> {
    let mut args = env::args_os().skip(1);
    let address: SocketAddr = args
        .next()
        .ok_or("usage: byte-transport <loopback_ip:port> <guest-binary> <sha256> <compiler-sha40> [guest-args...]")?
        .to_string_lossy()
        .parse()
        .map_err(|error| format!("invalid listener address: {error}"))?;
    ensure_loopback(address).map_err(|error| error.to_string())?;
    let binary = PathBuf::from(args.next().ok_or("missing guest binary path")?);
    let sha256: OsString = args.next().ok_or("missing guest binary SHA-256")?;
    let compiler_sha: OsString = args.next().ok_or("missing compiler source commit SHA")?;
    verify_guest_binary(
        &binary,
        &sha256.to_string_lossy(),
        &compiler_sha.to_string_lossy(),
    )
    .map_err(|error| format!("guest binary admission denied: {error}"))?;
    let cfg = Config {
        guest_binary: binary,
        guest_args: args.collect(),
        max_active_sessions: 16,
        session_timeout: DEFAULT_SESSION_TIMEOUT,
        max_accepts: None,
    };
    let listener = TcpListener::bind(address).map_err(|error| error.to_string())?;
    eprintln!("ATTO_HOST_BYTE_TRANSPORT_READY={address}");
    eprintln!("ATTO_GUEST_EXECUTION_NOT_PROVEN_BY_TRANSPORT=true");
    serve(listener, cfg).map_err(|error| error.to_string())
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("BYTE_TRANSPORT_NOT_READY: {error}");
            ExitCode::FAILURE
        }
    }
}
