use std::io::{BufRead, BufReader, Write};
use std::net::TcpStream;
use std::time::Duration;

use anyhow::{anyhow, Context, Result};
use serde_json::{json, Value};

use crate::adb::Adb;

/// Client for the Looker accessibility-service control socket
/// (`localabstract:looker_a11y`), reached via `adb forward`. Rust port of
/// scripts/looker_client.py's protocol: one line of JSON in, one line back.
pub struct A11yClient<'a> {
    adb: &'a Adb,
    port: u16,
}

impl<'a> A11yClient<'a> {
    pub fn connect(adb: &'a Adb, port: u16) -> Result<Self> {
        adb.forward(port, "looker_a11y")
            .context("adb forward to looker_a11y (is the accessibility service enabled?)")?;
        Ok(Self { adb, port })
    }

    pub fn send(&self, cmd: Value) -> Result<Value> {
        self.send_retry(cmd, 4)
    }

    /// Single attempt (no retry) with a caller-chosen timeout — for requests
    /// that legitimately take a long time server-side (e.g. whisper_load,
    /// which blocks until a ~514MB download finishes).
    pub fn send_with_timeout(&self, cmd: Value, timeout: Duration) -> Result<Value> {
        self.send_once(&cmd, timeout)
    }

    fn send_once(&self, cmd: &Value, timeout: Duration) -> Result<Value> {
        let mut stream = TcpStream::connect(("127.0.0.1", self.port))
            .context("connecting to forwarded looker_a11y socket")?;
        stream.set_read_timeout(Some(timeout))?;
        stream.set_write_timeout(Some(timeout))?;
        stream.write_all((cmd.to_string() + "\n").as_bytes())?;
        let mut reader = BufReader::new(stream);
        let mut line = String::new();
        let n = reader.read_line(&mut line)?;
        if n == 0 {
            return Err(anyhow!("empty response (connection dropped)"));
        }
        Ok(serde_json::from_str(line.trim())?)
    }

    fn send_retry(&self, cmd: Value, retries: u32) -> Result<Value> {
        let mut last_err = None;
        for _ in 0..retries {
            match self.send_once(&cmd, Duration::from_secs(10)) {
                Ok(v) => return Ok(v),
                Err(e) => {
                    last_err = Some(e);
                    self.adb.forward(self.port, "looker_a11y").ok();
                    std::thread::sleep(Duration::from_millis(500));
                }
            }
        }
        Err(last_err.unwrap_or_else(|| anyhow!("send failed")))
    }

    pub fn dump(&self) -> Result<Value> {
        self.send(json!({"cmd": "dump"}))
    }

    pub fn tap(&self, x: i64, y: i64) -> Result<bool> {
        Ok(self
            .send(json!({"cmd": "tap", "x": x, "y": y}))?
            .get("ok")
            .and_then(Value::as_bool)
            .unwrap_or(false))
    }

    pub fn airplane(&self, on: bool) -> Result<Value> {
        self.send(json!({"cmd": "airplane", "on": on}))
    }

    pub fn whisper_status(&self) -> Result<Value> {
        self.send(json!({"cmd": "whisper_status"}))
    }
}

/// Case-insensitive substring search over text/desc, mirroring
/// LookerClient.find_text. When `require_clickable` is set, non-clickable
/// nodes are skipped — needed for dialogs where the title text and a button
/// both contain the search word (e.g. "Allow otso-cita to access Shizuku?"
/// vs. the "Allow all the time" button).
pub fn find_text<'v>(tree: &'v Value, needle: &str, require_clickable: bool) -> Option<&'v Value> {
    let needle = needle.to_lowercase();
    fn walk<'v>(n: &'v Value, needle: &str, require_clickable: bool) -> Option<&'v Value> {
        let t = n.get("text").and_then(Value::as_str).unwrap_or("").to_lowercase();
        let d = n.get("desc").and_then(Value::as_str).unwrap_or("").to_lowercase();
        let matches = t.contains(needle) || d.contains(needle);
        let clickable = n.get("clickable").and_then(Value::as_bool).unwrap_or(false);
        if matches && (!require_clickable || clickable) {
            return Some(n);
        }
        for c in n.get("children").and_then(Value::as_array).into_iter().flatten() {
            if let Some(found) = walk(c, needle, require_clickable) {
                return Some(found);
            }
        }
        None
    }
    for w in tree.get("windows").and_then(Value::as_array).into_iter().flatten() {
        if let Some(found) = walk(w, &needle, require_clickable) {
            return Some(found);
        }
    }
    None
}

/// Center point of a node's `bounds: [l, t, r, b]`.
pub fn center(node: &Value) -> Option<(i64, i64)> {
    let b = node.get("bounds")?.as_array()?;
    if b.len() != 4 {
        return None;
    }
    let l = b[0].as_i64()?;
    let t = b[1].as_i64()?;
    let r = b[2].as_i64()?;
    let bo = b[3].as_i64()?;
    Some(((l + r) / 2, (t + bo) / 2))
}
