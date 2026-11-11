//! Rust-only test fixture. No NATS grammar, not an Atto broker.
use std::io::{self, Read, Write};

fn main() -> io::Result<()> {
    let stdin = io::stdin();
    let stdout = io::stdout();
    let mut input = stdin.lock();
    let mut output = stdout.lock();
    let mut buf = [0_u8; 4096];
    loop {
        let count = input.read(&mut buf)?;
        if count == 0 {
            return output.flush();
        }
        output.write_all(&buf[..count])?;
        output.flush()?;
    }
}
