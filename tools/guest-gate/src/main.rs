use atto_guest_gate::{validate_execution, validate_pins};
use std::env;
use std::path::Path;
use std::process::{exit, Command};

fn main() {
    if let Err(error) = run() {
        eprintln!("ATTO_GUEST_ADMISSION_FAILED: {error}");
        exit(1);
    }
}

fn run() -> Result<(), String> {
    let mut args = env::args().skip(1);
    let runner = args.next().ok_or("missing runner executable")?;
    let source = args.next().ok_or("missing .atto source path")?;
    let compiler_sha = args.next().ok_or("missing exact compiler source SHA")?;
    let binary_sha256 = args.next().ok_or("missing exact runner SHA-256")?;
    let marker = args
        .next()
        .ok_or("missing explicit expected guest marker")?;
    let runner_prefix: Vec<String> = args.collect();

    validate_pins(&compiler_sha, &binary_sha256)
        .map_err(|error| format!("invalid pin: {error:?}"))?;
    if Path::new(&source).extension().and_then(|x| x.to_str()) != Some("atto") {
        return Err("source must be a .atto file".into());
    }
    if !Path::new(&source).is_file() || !Path::new(&runner).is_file() {
        return Err("runner and Atto guest program must both exist".into());
    }

    let digest_output = Command::new("/usr/bin/sha256sum")
        .arg(&runner)
        .output()
        .map_err(|error| format!("cannot hash pinned runner: {error}"))?;
    if !digest_output.status.success() {
        return Err("sha256sum failed".into());
    }
    let actual_sha = String::from_utf8(digest_output.stdout)
        .map_err(|_| "non-UTF8 digest output")?
        .split_ascii_whitespace()
        .next()
        .ok_or("missing runner digest")?
        .to_owned();
    if !actual_sha.eq_ignore_ascii_case(&binary_sha256) {
        return Err("runner binary SHA-256 does not match the supplied pin".into());
    }

    let output = Command::new(&runner)
        .args(&runner_prefix)
        .arg(&source)
        .output()
        .map_err(|error| format!("runner could not execute: {error}"))?;
    let stdout = String::from_utf8(output.stdout)
        .map_err(|_| "guest stdout must be UTF-8 for receipt marker")?;
    validate_execution(output.status.success(), &stdout, &marker)
        .map_err(|error| format!("no executable guest proof: {error:?}"))?;
    println!("EVIDENCE_SCOPE=ATTO_GUEST_EXECUTED");
    println!("COMPILER_SOURCE_SHA={compiler_sha}");
    println!("COMPILER_RUNNER_SHA256={actual_sha}");
    println!("GUEST_SOURCE={source}");
    println!("GUEST_MARKER={marker}");
    Ok(())
}
