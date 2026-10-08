//! A stand-in for the programs the backend starts, so its tests drive real
//! processes on every OS without Java, Paper or lua-language-server. The
//! tests find it beside their own executable (`cargo test` builds the
//! examples first; see `testing::fake_server`).
//!
//! What it plays follows from its arguments, which are what the backend
//! passes the real program:
//!
//! - `-version`: Java 25 (on stderr, as `java -version` prints it).
//! - `-jar <dir>/NetherForgeTest-<v>.jar <args>`: Java running the script test
//!   runner. The "jar" is the runner's `--json` output, printed as it is; the
//!   arguments go to `<jar>.args`, one per line. A missing jar fails as Java does.
//! - `-jar <anything else>` (Java running Paper) or `serve`: a dev server. It
//!   prints Paper's lines, says hello on the dev bridge when given
//!   `-Dnetherforge.bridge.port=` (with `NETHERFORGE_BRIDGE_TOKEN`), echoes each
//!   console line as `> <line>` and stops on `stop`.
//! - `--metapath=…` (lua-language-server): answers each LSP message with
//!   itself; a `{"method":"exit","params":{"code":<n>}}` makes it exit with `<n>`.
//! - `ignore-stop`: a dev server that never stops by itself.
//! - `exit <code>`: prints one line and exits with `<code>`.
//! - `sleep <seconds> [anything…]`: waits (an orphaned server: the rest of its
//!   command line is what a leftover is recognised by).

use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpStream;
use std::path::Path;
use std::time::Duration;

use serde_json::{Value, json};

/// Paper's ready line, which moves the dev server to `running`.
const DONE: &str = "[00:00:00 INFO]: Done (0.1s)! For help, type \"help\"";

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let first = args.first().map(String::as_str).unwrap_or_default();
    if args.iter().any(|a| a == "-version") {
        eprintln!("openjdk version \"25.0.1\" 2025-10-21");
        return;
    }
    if let Some(jar) = args
        .iter()
        .position(|a| a == "-jar")
        .and_then(|i| args.get(i + 1))
    {
        let name = Path::new(jar)
            .file_name()
            .unwrap_or_default()
            .to_string_lossy();
        if name.starts_with("NetherForgeTest-") {
            test_runner(Path::new(jar), &args);
        } else {
            serve(&args, false);
        }
        return;
    }
    if args.iter().any(|a| a.starts_with("--metapath=")) {
        language_server();
        return;
    }
    match first {
        "serve" => serve(&args, false),
        "ignore-stop" => serve(&args, true),
        "exit" => {
            println!("starting");
            std::process::exit(args.get(1).and_then(|c| c.parse().ok()).unwrap_or(1));
        }
        "sleep" => {
            let seconds = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(30);
            std::thread::sleep(Duration::from_secs(seconds));
        }
        _ => {
            eprintln!("fake_server: what should I play? {args:?}");
            std::process::exit(2);
        }
    }
}

fn test_runner(jar: &Path, args: &[String]) {
    let Ok(output) = std::fs::read_to_string(jar) else {
        eprintln!("Error: Unable to access jarfile {}", jar.display());
        std::process::exit(1);
    };
    let mut recorded = jar.as_os_str().to_owned();
    recorded.push(".args");
    std::fs::write(recorded, args.join("\n")).expect("the arguments are recorded");
    print!("{output}");
    // The runner's exit status: 1 when a test failed or errored.
    let failed =
        output.contains("\"status\":\"failed\"") || output.contains("\"status\":\"errored\"");
    std::process::exit(i32::from(failed));
}

/// The dev server: Paper's lines, the plugin's hello, the console.
fn serve(args: &[String], ignore_stop: bool) {
    println!("[00:00:00 INFO]: Starting minecraft server");
    eprintln!("[00:00:00 WARN]: a warning on stderr");
    let port = args
        .iter()
        .find_map(|a| a.strip_prefix("-Dnetherforge.bridge.port="))
        .and_then(|p| p.parse::<u16>().ok());
    if let Some(port) = port {
        let minecraft = minecraft_of(args);
        std::thread::spawn(move || plugin(port, &minecraft));
    }
    println!("{DONE}");
    for line in std::io::stdin().lock().lines() {
        let Ok(line) = line else { break };
        if line == "stop" && !ignore_stop {
            println!("[00:00:00 INFO]: Stopping server");
            std::process::exit(0);
        }
        println!("> {line}");
    }
    if ignore_stop {
        // Stdin is gone but this one stays: only a kill ends it.
        loop {
            std::thread::sleep(Duration::from_secs(60));
        }
    }
}

/// The version the "Paper jar" is for (`paper-<minecraft>-<build>.jar`), as the plugin would know it.
fn minecraft_of(args: &[String]) -> String {
    args.iter()
        .find_map(|a| {
            let name = Path::new(a).file_name()?.to_str()?;
            let rest = name.strip_prefix("paper-")?.strip_suffix(".jar")?;
            Some(
                rest.rsplit_once('-')
                    .map_or(rest, |(minecraft, _)| minecraft)
                    .to_string(),
            )
        })
        .unwrap_or_else(|| "26.3".into())
}

/// The NetherForge plugin's side of the dev bridge: the hello, what the memory
/// backend's fake plugin says once connected, `ping`, and "no such method" for
/// every other request (the game data export included: there's no game here).
fn plugin(port: u16, minecraft: &str) {
    let Ok(stream) = TcpStream::connect(("127.0.0.1", port)) else {
        eprintln!("fake_server: no dev bridge on port {port}");
        return;
    };
    let mut writer = stream.try_clone().expect("the socket clones");
    let mut send = move |frame: Value| {
        let _ = writeln!(writer, "{frame}");
        let _ = writer.flush();
    };
    let project = std::env::args()
        .find_map(|a| a.strip_prefix("-Dnetherforge.project=").map(str::to_string))
        .unwrap_or_default();
    send(json!({
        "jsonrpc": "2.0",
        "id": 0,
        "method": "hello",
        "params": {
            "token": std::env::var("NETHERFORGE_BRIDGE_TOKEN").unwrap_or_default(),
            "protocol": 1,
            "pluginVersion": "0.0.0-fake",
            "minecraft": minecraft,
            "project": project,
        },
    }));
    let mut lines = BufReader::new(stream).lines();
    // The editor answers the hello, or closes the connection without a word.
    if lines.next().is_none() {
        eprintln!("[fake plugin] the editor closed the dev bridge without answering the hello");
        return;
    }
    send(json!({
        "jsonrpc": "2.0",
        "method": "console",
        "params": { "items": [{ "type": "log", "level": "info", "message": "NetherForge loaded the project" }] },
    }));
    for line in lines {
        let Ok(line) = line else { break };
        let Ok(frame) = serde_json::from_str::<Value>(&line) else {
            continue;
        };
        let (Some(id), Some(method)) =
            (frame.get("id"), frame.get("method").and_then(Value::as_str))
        else {
            continue;
        };
        send(match method {
            "ping" => json!({ "jsonrpc": "2.0", "id": id, "result": null }),
            _ => json!({
                "jsonrpc": "2.0",
                "id": id,
                "error": { "code": -32601, "message": format!("Unknown method \"{method}\"") },
            }),
        });
    }
}

/// lua-language-server: each LSP message back as it came, until `exit`.
fn language_server() {
    let mut input = BufReader::new(std::io::stdin().lock());
    let mut output = std::io::stdout().lock();
    loop {
        let mut length = None;
        loop {
            let mut header = String::new();
            if input.read_line(&mut header).unwrap_or(0) == 0 {
                return;
            }
            let header = header.trim_end();
            if header.is_empty() {
                break;
            }
            if let Some(value) = header.strip_prefix("Content-Length:") {
                length = value.trim().parse::<usize>().ok();
            }
        }
        let mut body = vec![0; length.unwrap_or(0)];
        if input.read_exact(&mut body).is_err() {
            return;
        }
        let message: Value = serde_json::from_slice(&body).unwrap_or(Value::Null);
        if message["method"] == "exit" {
            std::process::exit(message["params"]["code"].as_i64().unwrap_or(1) as i32);
        }
        let _ = write!(output, "Content-Length: {}\r\n\r\n", body.len());
        let _ = output.write_all(&body);
        let _ = output.flush();
    }
}
