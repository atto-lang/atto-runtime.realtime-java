//! Independent, dependency-free NATS semantics oracle.
//! This is NOT the AttoNATS server: it never opens a listening socket.
//! Passing tests here is not Atto guest/compiler/NativeAOT evidence.

pub mod broker_model;

const MAX_CONTROL_LINE: usize = 4096;
const MAX_PAYLOAD: usize = 1_048_576;
const MAX_BUFFER: usize = 2_097_152;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    Limit,
    InvalidCommand,
    Poisoned,
    InvalidLength,
    InvalidSubject,
    BadTerminator,
    BadHeaders,
    CorruptWal,
    InvalidPartitionCount,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Frame {
    Control(Vec<u8>),
    Publish {
        subject: String,
        reply: Option<String>,
        headers: Vec<u8>,
        payload: Vec<u8>,
    },
}

#[derive(Default)]
pub struct Decoder {
    buffer: Vec<u8>,
    poisoned: bool,
}

impl Decoder {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn push(&mut self, bytes: &[u8]) -> Result<Vec<Frame>, Error> {
        if self.poisoned {
            return Err(Error::Poisoned);
        }
        let result = self.parse(bytes);
        if result.is_err() {
            self.poisoned = true;
            self.buffer.clear();
        }
        result
    }

    fn parse(&mut self, bytes: &[u8]) -> Result<Vec<Frame>, Error> {
        if bytes.len() > MAX_BUFFER.saturating_sub(self.buffer.len()) {
            return Err(Error::Limit);
        }
        self.buffer.extend_from_slice(bytes);
        let mut frames = Vec::new();
        loop {
            let Some(line_end) = self.buffer.windows(2).position(|w| w == b"\r\n") else {
                if self.buffer.len() > MAX_CONTROL_LINE {
                    return Err(Error::Limit);
                }
                break;
            };
            if line_end > MAX_CONTROL_LINE {
                return Err(Error::Limit);
            }
            let line =
                std::str::from_utf8(&self.buffer[..line_end]).map_err(|_| Error::InvalidCommand)?;
            let tokens: Vec<&str> = line.split_ascii_whitespace().collect();
            if tokens.is_empty() {
                return Err(Error::InvalidCommand);
            }
            let op = tokens[0].to_ascii_uppercase();
            if line.chars().any(char::is_control) {
                return Err(Error::InvalidCommand);
            }
            if op != "PUB" && op != "HPUB" {
                if !valid_client_command(line, &tokens, &op) {
                    return Err(Error::InvalidCommand);
                }
                let control = self.buffer.drain(..line_end + 2).collect();
                frames.push(Frame::Control(control));
                continue;
            }
            let header_mode = op == "HPUB";
            let expected_a = if header_mode { 4 } else { 3 };
            let expected_b = expected_a + 1;
            if tokens.len() != expected_a && tokens.len() != expected_b {
                return Err(Error::InvalidCommand);
            }
            if !valid_publish_subject(tokens[1]) {
                return Err(Error::InvalidSubject);
            }
            let subject = tokens[1].to_string();
            let has_reply = tokens.len() == expected_b;
            let reply = if has_reply {
                if !valid_publish_subject(tokens[2]) {
                    return Err(Error::InvalidSubject);
                }
                Some(tokens[2].to_string())
            } else {
                None
            };
            let sizes_start = if has_reply { 3 } else { 2 };
            let header_len = if header_mode {
                parse_length(tokens[sizes_start])?
            } else {
                0
            };
            let total_bytes = parse_length(tokens[tokens.len() - 1])?;
            if total_bytes < header_len || total_bytes > MAX_PAYLOAD {
                return Err(Error::InvalidLength);
            }
            let body_start = line_end + 2;
            let frame_end = body_start
                .checked_add(total_bytes)
                .and_then(|n| n.checked_add(2))
                .ok_or(Error::InvalidLength)?;
            if frame_end > MAX_BUFFER {
                return Err(Error::Limit);
            }
            if self.buffer.len() < frame_end {
                break;
            }
            if &self.buffer[frame_end - 2..frame_end] != b"\r\n" {
                return Err(Error::BadTerminator);
            }
            let headers = self.buffer[body_start..body_start + header_len].to_vec();
            if header_mode
                && (!headers.starts_with(b"NATS/1.0\r\n") || !headers.ends_with(b"\r\n\r\n"))
            {
                return Err(Error::BadHeaders);
            }
            let payload = self.buffer[body_start + header_len..frame_end - 2].to_vec();
            self.buffer.drain(..frame_end);
            frames.push(Frame::Publish {
                subject,
                reply,
                headers,
                payload,
            });
        }
        Ok(frames)
    }

    pub fn buffered_bytes(&self) -> usize {
        self.buffer.len()
    }
}

fn valid_sid(text: &str) -> bool {
    text.parse::<u64>().is_ok_and(|sid| sid > 0)
}

fn valid_queue_name(queue: &str) -> bool {
    !queue.is_empty()
        && !queue
            .chars()
            .any(|ch| ch.is_control() || ch.is_whitespace() || ch == '*' || ch == '>')
}

fn valid_client_command(line: &str, tokens: &[&str], op: &str) -> bool {
    match op {
        "PING" | "PONG" => tokens.len() == 1,
        "CONNECT" => {
            let document = line[tokens[0].len()..].trim();
            document.starts_with('{') && document.ends_with('}')
        }
        "SUB" => {
            (tokens.len() == 3 || tokens.len() == 4)
                && valid_subscribe_pattern(tokens[1])
                && valid_sid(tokens[tokens.len() - 1])
                && (tokens.len() == 3 || valid_queue_name(tokens[2]))
        }
        "UNSUB" => {
            (tokens.len() == 2 || tokens.len() == 3)
                && valid_sid(tokens[1])
                && (tokens.len() == 2 || tokens[2].parse::<u64>().is_ok())
        }
        _ => false,
    }
}

fn parse_length(text: &str) -> Result<usize, Error> {
    if text.is_empty() || !text.as_bytes().iter().all(u8::is_ascii_digit) {
        return Err(Error::InvalidLength);
    }
    text.parse().map_err(|_| Error::InvalidLength)
}

pub fn valid_publish_subject(subject: &str) -> bool {
    !subject.is_empty()
        && subject.split('.').all(|part| {
            !part.is_empty()
                && part != "*"
                && part != ">"
                && !part
                    .chars()
                    .any(|ch| ch.is_control() || ch.is_whitespace() || ch == '*' || ch == '>')
        })
}

pub fn valid_subscribe_pattern(pattern: &str) -> bool {
    let tokens: Vec<&str> = pattern.split('.').collect();
    !tokens.is_empty()
        && tokens.iter().enumerate().all(|(i, part)| {
            !part.is_empty()
                && (if *part == ">" {
                    i == tokens.len() - 1
                } else if *part == "*" {
                    true
                } else {
                    !part
                        .chars()
                        .any(|ch| ch.is_control() || ch.is_whitespace() || ch == '*' || ch == '>')
                })
        })
}

pub fn subject_matches(pattern: &str, subject: &str) -> bool {
    if !valid_subscribe_pattern(pattern) || !valid_publish_subject(subject) {
        return false;
    }
    let pat: Vec<&str> = pattern.split('.').collect();
    let sub: Vec<&str> = subject.split('.').collect();
    for (i, token) in pat.iter().enumerate() {
        if *token == ">" {
            return sub.len() > i;
        }
        if i >= sub.len() || (*token != "*" && *token != sub[i]) {
            return false;
        }
    }
    pat.len() == sub.len()
}

pub fn partition_for(key: &[u8], count: u32) -> Result<u32, Error> {
    if count == 0 || count > 4096 {
        return Err(Error::InvalidPartitionCount);
    }
    let mut h: u64 = 0xcbf29ce484222325;
    for &byte in key {
        h ^= u64::from(byte);
        h = h.wrapping_mul(0x100000001b3);
    }
    Ok((h % u64::from(count)) as u32)
}

pub fn crc32(data: &[u8]) -> u32 {
    let mut crc: u32 = !0;
    for &byte in data {
        crc ^= u32::from(byte);
        for _ in 0..8 {
            let mask = 0_u32.wrapping_sub(crc & 1);
            crc = (crc >> 1) ^ (0xedb88320 & mask);
        }
    }
    !crc
}

/// Frame only: length LE + CRC32 LE + data, no durability barrier.
pub fn encode_wal_record(payload: &[u8]) -> Result<Vec<u8>, Error> {
    if payload.len() > MAX_PAYLOAD {
        return Err(Error::Limit);
    }
    let mut output = Vec::with_capacity(8 + payload.len());
    output.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    output.extend_from_slice(&crc32(payload).to_le_bytes());
    output.extend_from_slice(payload);
    Ok(output)
}

/// Only incomplete final frames may be truncated. Complete CRC mismatches
/// are corruption, even if the corrupt frame is the final one.
pub fn recover_wal(bytes: &[u8]) -> Result<(Vec<Vec<u8>>, usize), Error> {
    let mut index = 0_usize;
    let mut records = Vec::new();
    while index < bytes.len() {
        if bytes.len() - index < 8 {
            break;
        }
        let size = u32::from_le_bytes(
            bytes[index..index + 4]
                .try_into()
                .map_err(|_| Error::CorruptWal)?,
        ) as usize;
        if size > MAX_PAYLOAD {
            return Err(Error::CorruptWal);
        }
        let checksum = u32::from_le_bytes(
            bytes[index + 4..index + 8]
                .try_into()
                .map_err(|_| Error::CorruptWal)?,
        );
        if bytes.len() - index - 8 < size {
            break;
        }
        let payload = &bytes[index + 8..index + 8 + size];
        if crc32(payload) != checksum {
            return Err(Error::CorruptWal);
        }
        records.push(payload.to_vec());
        index += 8 + size;
    }
    Ok((records, index))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn standard_pub_splits_at_every_tcp_byte() {
        let mut decoder = Decoder::new();
        let input = b"PING\r\nPUB foo.bar inbox 5\r\nhello\r\n";
        let mut frames = Vec::new();
        for byte in input {
            frames.extend(decoder.push(&[*byte]).unwrap());
        }
        assert_eq!(frames.len(), 2);
        assert_eq!(frames[0], Frame::Control(b"PING\r\n".to_vec()));
        assert_eq!(
            frames[1],
            Frame::Publish {
                subject: "foo.bar".into(),
                reply: Some("inbox".into()),
                headers: Vec::new(),
                payload: b"hello".to_vec(),
            }
        );
        assert_eq!(decoder.buffered_bytes(), 0);
    }

    #[test]
    fn multiple_frames_and_embedded_binary() {
        let mut decoder = Decoder::new();
        let frames = decoder.push(b"PUB f 4\r\n\0\r\n\xff\r\nPING\r\n").unwrap();
        assert_eq!(frames.len(), 2);
        match &frames[0] {
            Frame::Publish { payload, .. } => assert_eq!(payload, b"\0\r\n\xff"),
            _ => panic!("expected binary publish"),
        }
    }

    #[test]
    fn hpub_header_length_is_separate_from_total_length() {
        let header = b"NATS/1.0\r\nK: v\r\n\r\n";
        let mut wire = format!("HPUB foo {} {}\r\n", header.len(), header.len() + 2).into_bytes();
        wire.extend_from_slice(header);
        wire.extend_from_slice(b"ok\r\n");
        let frames = Decoder::new().push(&wire).unwrap();
        assert_eq!(frames.len(), 1);
        match &frames[0] {
            Frame::Publish {
                headers, payload, ..
            } => {
                assert_eq!(headers, b"NATS/1.0\r\nK: v\r\n\r\n");
                assert_eq!(payload, b"ok");
            }
            _ => panic!("expected HPUB"),
        }
    }

    #[test]
    fn reject_payload_injection_and_bad_lengths() {
        assert_eq!(
            Decoder::new().push(b"PUB foo -1\r\n"),
            Err(Error::InvalidLength)
        );
        assert_eq!(
            Decoder::new().push(b"PUB foo 1\r\nxXX"),
            Err(Error::BadTerminator)
        );
        assert_eq!(
            Decoder::new().push(b"HPUB foo 4 3\r\n"),
            Err(Error::InvalidLength)
        );
        assert_eq!(
            Decoder::new().push(b"PUB x.> 1\r\na\r\n"),
            Err(Error::InvalidSubject)
        );
    }

    #[test]
    fn wildcard_match_is_token_based_and_validated() {
        assert!(subject_matches("orders.*", "orders.created"));
        assert!(!subject_matches("orders.*", "orders.created.eu"));
        assert!(subject_matches("orders.>", "orders.created.eu"));
        assert!(!subject_matches("orders.>", "orders"));
        assert!(!subject_matches("orders.>.no", "orders.created.no"));
        assert!(!valid_publish_subject("a..b"));
        assert!(valid_subscribe_pattern(">"));
        assert!(subject_matches(">", "orders.created"));
        assert!(subject_matches("abc", "abc"));
    }

    #[test]
    fn stable_shard_assignment() {
        let a = partition_for(b"customer-42", 16).unwrap();
        assert_eq!(a, partition_for(b"customer-42", 16).unwrap());
        assert!(a < 16);
        assert_eq!(partition_for(b"k", 0), Err(Error::InvalidPartitionCount));
    }

    #[test]
    fn wal_replays_records_and_truncates_only_torn_tail() {
        let mut data = encode_wal_record(b"one").unwrap();
        data.extend(encode_wal_record(b"two").unwrap());
        let complete = data.len();
        data.extend_from_slice(&[1, 0, 0]);
        let (records, end) = recover_wal(&data).unwrap();
        assert_eq!(end, complete);
        assert_eq!(records, vec![b"one".to_vec(), b"two".to_vec()]);
    }

    #[test]
    fn corruption_in_complete_wal_record_is_fatal() {
        let mut data = encode_wal_record(b"one").unwrap();
        data[8] ^= 1;
        assert_eq!(recover_wal(&data), Err(Error::CorruptWal));
    }

    #[test]
    fn crc32_matches_standard_vector() {
        assert_eq!(crc32(b"123456789"), 0xcbf43926);
    }

    #[test]
    fn actor_mutation_source_contract_is_preserved() {
        fn check(directory: &std::path::Path, seen: &mut usize) {
            for entry in std::fs::read_dir(directory).unwrap() {
                let path = entry.unwrap().path();
                if path.is_dir() {
                    check(&path, seen);
                } else if path.extension().is_some_and(|ext| ext == "atto") {
                    let source = std::fs::read_to_string(&path).unwrap();
                    if source.contains("define actor ") {
                        *seen += 1;
                        assert!(
                            source.contains("state {"),
                            "{} must own mutable state in state{{}}",
                            path.display()
                        );
                        assert!(
                            source.contains("critical "),
                            "{} must protect mutation with critical methods",
                            path.display()
                        );
                    }
                }
            }
        }
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../src");
        let mut seen = 0;
        check(&root, &mut seen);
        assert!(seen >= 7, "expected the complete actor foundation");
    }

    #[test]
    fn rejects_unknown_and_server_only_commands() {
        for invalid in [
            b"BOGUS value\r\n".as_slice(),
            b"INFO {}\r\n",
            b"MSG foo 1 2\r\n",
            b"+OK\r\n",
            b"PING with_args\r\n",
            b"SUB f\r\n",
            b"UNSUB 0\r\n",
            b"CONNECT {\r\n",
            b"SUB foo workers not_a_sid\r\n",
        ] {
            assert_eq!(Decoder::new().push(invalid), Err(Error::InvalidCommand));
        }
    }

    #[test]
    fn valid_core_control_commands_pass() {
        let mut decoder = Decoder::new();
        let wire = b"CONNECT {\"verbose\": false}\r\nPING\r\nPONG\r\nSUB orders.* workers 12\r\nSUB > 13\r\nUNSUB 12 100\r\nUNSUB 13\r\n";
        assert_eq!(decoder.push(wire).unwrap().len(), 7);
    }

    #[test]
    fn poisoned_decoder_cannot_recover_after_protocol_error() {
        let mut decoder = Decoder::new();
        assert_eq!(decoder.push(b"PUB foo 1\r\naXX"), Err(Error::BadTerminator));
        assert_eq!(decoder.buffered_bytes(), 0);
        assert_eq!(decoder.push(b"PING\r\n"), Err(Error::Poisoned));
    }

    #[test]
    fn rejects_control_bytes_in_subjects_and_queues() {
        assert!(!valid_publish_subject("hi\0"));
        assert!(!valid_publish_subject("hi\n"));
        assert!(!valid_publish_subject("a.\u{2003}b"));
        assert!(!valid_subscribe_pattern("foo.\u{2003}"));
        assert_eq!(
            Decoder::new().push(b"SUB foo queue* 12\r\n"),
            Err(Error::InvalidCommand)
        );
        assert_eq!(
            Decoder::new().push(b"PUB foo\0bar 0\r\n\r\n"),
            Err(Error::InvalidCommand)
        );
    }

    #[test]
    fn fragmented_headers_and_zero_length_pub_are_supported() {
        let header = b"NATS/1.0\r\nK: v\r\n\r\n";
        let mut wire = format!("HPUB foo {} {}\r\n", header.len(), header.len()).into_bytes();
        wire.extend_from_slice(header);
        wire.extend_from_slice(b"\r\nPUB foo 0\r\n\r\n");
        for chunk_size in 1..=wire.len() {
            let mut decoder = Decoder::new();
            let mut frames = Vec::new();
            for chunk in wire.chunks(chunk_size) {
                frames.extend(decoder.push(chunk).unwrap());
            }
            assert_eq!(frames.len(), 2);
            assert_eq!(decoder.buffered_bytes(), 0);
        }
    }

    #[test]
    fn wal_torn_tail_at_every_byte_and_full_corruption() {
        let previous = encode_wal_record(b"first").unwrap();
        let last = encode_wal_record(b"second").unwrap();
        for prefix_len in 0..last.len() {
            let mut bytes = previous.clone();
            bytes.extend_from_slice(&last[..prefix_len]);
            let (records, consumed) = recover_wal(&bytes).unwrap();
            assert_eq!(records, vec![b"first".to_vec()]);
            assert_eq!(consumed, previous.len());
        }
        let mut all = previous.clone();
        all.extend_from_slice(&last);
        let (records, end) = recover_wal(&all).unwrap();
        assert_eq!(records.len(), 2);
        assert_eq!(end, all.len());
        all[previous.len() + 8] ^= 0x80;
        assert_eq!(recover_wal(&all), Err(Error::CorruptWal));
    }

    #[test]
    fn refuses_unbounded_control_lines_and_frames() {
        let mut decoder = Decoder::new();
        assert_eq!(decoder.push(&vec![b'x'; 4097]), Err(Error::Limit));
        assert_eq!(
            Decoder::new().push(b"PUB f 1048577\r\n"),
            Err(Error::InvalidLength)
        );
    }
}
