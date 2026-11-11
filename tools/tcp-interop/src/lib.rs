//! NATS wire TCP acceptance *client*, not a broker.
//! Ordinary Rust unit tests use isolated test-only fixture sockets.
//! Only an independently verified actual Atto server process can satisfy
//! the external interoperability gate for AttoNATS.

use std::io::{BufReader, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const MAX_CONTROL_LINE: usize = 4096;
const MAX_PAYLOAD_BYTES: usize = 1_048_576;
const TIMEOUT: Duration = Duration::from_secs(3);

pub type ProbeResult<T> = Result<T, String>;

fn read_crlf_line<R: Read>(reader: &mut R) -> ProbeResult<String> {
    let mut bytes = Vec::new();
    for _ in 0..=MAX_CONTROL_LINE {
        let mut byte = [0_u8; 1];
        reader
            .read_exact(&mut byte)
            .map_err(|err| format!("socket failed before CRLF: {err}"))?;
        bytes.push(byte[0]);
        if bytes.ends_with(b"\r\n") {
            return String::from_utf8(bytes[..bytes.len() - 2].to_vec())
                .map_err(|_| "control line is not UTF-8".to_owned());
        }
        if byte[0] == b'\n' {
            return Err("bare LF is invalid".to_owned());
        }
    }
    Err("server sent oversized control line".to_owned())
}

fn validate_info(line: &str) -> ProbeResult<()> {
    let json = line
        .strip_prefix("INFO ")
        .ok_or("server did not issue NATS INFO first")?;
    if !json.starts_with('{') || !json.ends_with('}') {
        return Err("server INFO does not contain a JSON object".to_owned());
    }
    Ok(())
}

fn send(socket: &mut TcpStream, data: &[u8]) -> ProbeResult<()> {
    socket
        .write_all(data)
        .map_err(|error| format!("socket write failed: {error}"))
}

fn expect_pong<R: Read>(reader: &mut R) -> ProbeResult<()> {
    let line = read_crlf_line(reader)?;
    if line != "PONG" {
        return Err(format!("expected PONG, got {line:?}"));
    }
    Ok(())
}

fn expected_message<R: Read>(
    reader: &mut R,
    subject: &str,
    sid: u64,
    expected: &[u8],
) -> ProbeResult<()> {
    let header = read_crlf_line(reader)?;
    let parts: Vec<&str> = header.split_ascii_whitespace().collect();
    if parts.len() != 4 || parts[0] != "MSG" || parts[1] != subject {
        return Err(format!("invalid or mismatched NATS MSG header: {header:?}"));
    }
    let actual_sid: u64 = parts[2].parse().map_err(|_| "non-numeric MSG sid")?;
    let body_len: usize = parts[3].parse().map_err(|_| "non-numeric MSG length")?;
    if actual_sid != sid || body_len > MAX_PAYLOAD_BYTES || body_len != expected.len() {
        return Err("MSG SID/length did not match subscription and published bytes".into());
    }
    let mut actual = vec![0; body_len];
    reader
        .read_exact(&mut actual)
        .map_err(|error| format!("truncated MSG body: {error}"))?;
    let mut end = [0; 2];
    reader
        .read_exact(&mut end)
        .map_err(|error| format!("missing MSG trailing CRLF: {error}"))?;
    if end != *b"\r\n" || actual != expected {
        return Err("received MSG payload or terminator differed".into());
    }
    Ok(())
}

/// Tests real socket framing with binary data. Does NOT assert server identity:
/// record the server executable's exact source SHA + binary hash separately.
pub fn probe(address: SocketAddr) -> ProbeResult<()> {
    let mut writer = TcpStream::connect_timeout(&address, TIMEOUT)
        .map_err(|error| format!("could not connect to {address}: {error}"))?;
    writer
        .set_read_timeout(Some(TIMEOUT))
        .map_err(|error| error.to_string())?;
    writer
        .set_write_timeout(Some(TIMEOUT))
        .map_err(|error| error.to_string())?;
    let mut reader = BufReader::new(writer.try_clone().map_err(|error| error.to_string())?);

    validate_info(&read_crlf_line(&mut reader)?)?;
    send(
        &mut writer,
        b"CONNECT {\"verbose\":false,\"pedantic\":true,\"headers\":true}\r\nPING\r\n",
    )?;
    expect_pong(&mut reader)?;

    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| error.to_string())?
        .as_nanos();
    let subject = format!("atto.hello.{}.{}", std::process::id(), nanos);
    let sid = 42_u64;
    send(
        &mut writer,
        format!("SUB {subject} {sid}\r\nPING\r\n").as_bytes(),
    )?;
    expect_pong(&mut reader)?;

    let binary_payload = [0, 13, 10, 255, 0, b'x'];
    send(
        &mut writer,
        format!("PUB {subject} {}\r\n", binary_payload.len()).as_bytes(),
    )?;
    send(&mut writer, &binary_payload)?;
    send(&mut writer, b"\r\n")?;
    expected_message(&mut reader, &subject, sid, &binary_payload)?;
    send(&mut writer, format!("UNSUB {sid}\r\n").as_bytes())?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    use std::net::TcpListener;
    use std::thread;

    #[test]
    fn line_reader_rejects_lf_and_oversized_lines() {
        assert!(read_crlf_line(&mut Cursor::new(b"PING\n")).is_err());
        assert!(read_crlf_line(&mut Cursor::new(vec![b'x'; 4099])).is_err());
        assert_eq!(
            read_crlf_line(&mut Cursor::new(b"PONG\r\n")).unwrap(),
            "PONG"
        );
    }

    #[test]
    fn bad_info_never_passes_as_server_ready() {
        assert!(validate_info("INFO {}").is_ok());
        assert!(validate_info("INF0 {}").is_err());
        assert!(validate_info("INFO garbage").is_err());
    }

    #[test]
    fn mismatched_sid_and_malformed_binary_message_fail_closed() {
        let mut wrong_sid = Cursor::new(b"MSG subject 5 1\r\nx\r\n");
        assert!(expected_message(&mut wrong_sid, "subject", 2, b"x").is_err());
        let mut bad_ending = Cursor::new(b"MSG subject 2 1\r\nxXX");
        assert!(expected_message(&mut bad_ending, "subject", 2, b"x").is_err());
    }

    #[test]
    fn probe_harness_succeeds_against_fixture_only() {
        // This Rust-only fixture verifies probe correctness. It is NOT an
        // Atto broker and does NOT count as Atto guest interoperability.
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket.set_read_timeout(Some(TIMEOUT)).unwrap();
            socket
                .write_all(b"INFO {\"server_id\":\"rust-test-fixture\"}\r\n")
                .unwrap();
            assert!(read_crlf_line(&mut socket)
                .unwrap()
                .starts_with("CONNECT {"));
            assert_eq!(read_crlf_line(&mut socket).unwrap(), "PING");
            socket.write_all(b"PONG\r\n").unwrap();
            let sub = read_crlf_line(&mut socket).unwrap();
            let values: Vec<&str> = sub.split_ascii_whitespace().collect();
            assert_eq!(values.len(), 3);
            assert_eq!(values[0], "SUB");
            assert_eq!(values[2], "42");
            let subject = values[1].to_owned();
            assert_eq!(read_crlf_line(&mut socket).unwrap(), "PING");
            socket.write_all(b"PONG\r\n").unwrap();
            assert_eq!(
                read_crlf_line(&mut socket).unwrap(),
                format!("PUB {subject} 6")
            );
            let mut body = [0; 6];
            socket.read_exact(&mut body).unwrap();
            assert_eq!(body, [0, 13, 10, 255, 0, b'x']);
            let mut end = [0; 2];
            socket.read_exact(&mut end).unwrap();
            assert_eq!(end, *b"\r\n");
            socket
                .write_all(format!("MSG {subject} 42 6\r\n").as_bytes())
                .unwrap();
            socket.write_all(&body).unwrap();
            socket.write_all(b"\r\n").unwrap();
            assert_eq!(read_crlf_line(&mut socket).unwrap(), "UNSUB 42");
        });
        probe(addr).unwrap();
        server.join().unwrap();
    }

    #[test]
    fn probe_fails_against_protocol_incompatible_fixture() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream.write_all(b"HELLO\r\n").unwrap();
        });
        assert!(probe(addr).is_err());
        server.join().unwrap();
    }
}
