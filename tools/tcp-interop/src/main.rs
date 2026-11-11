use atto_nats_tcp_interop::probe;
use std::env;
use std::net::SocketAddr;
use std::process::exit;

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    if args.len() != 1 {
        eprintln!("usage: atto-nats-tcp-interop <ip:port>");
        exit(2);
    }
    let address: SocketAddr = match args[0].parse() {
        Ok(address) => address,
        Err(error) => {
            eprintln!("invalid socket address: {error}");
            exit(2);
        }
    };
    if let Err(error) = probe(address) {
        eprintln!("NATS_TCP_INTEROP_FAILED: {error}");
        exit(1);
    }
    println!("NATS_TCP_INTEROP_CLIENT_PROBE=PASS");
    println!("SERVER_IDENTITY_NOT_VERIFIED_BY_THIS_CLIENT=true");
}
