mod a11y;
mod adb;
mod download;
mod github;

use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use clap::Parser;
use serde_json::{json, Value};

use crate::a11y::A11yClient;
use crate::adb::Adb;
use crate::github::latest_release;

const OTSO_REPO: &str = "otso-cita/android";
const SHIZUKU_REPO: &str = "RikkaApps/Shizuku";
const OTSO_PACKAGE: &str = "com.looker.a11y";
const OTSO_SERVICE: &str = "com.looker.a11y/com.looker.a11y.LookerAccessibilityService";
const SHIZUKU_PACKAGE: &str = "moe.shizuku.privileged.api";
const A11Y_PORT: u16 = 7913;

/// Downloads adb and installs + configures otso-cita on a USB-connected phone.
#[derive(Parser)]
#[command(name = "otso-setup", version, about)]
struct Cli {
    /// Use this device serial instead of auto-detecting a single device.
    #[arg(long)]
    device: Option<String>,

    /// Skip the Whisper speech-model download (~514 MB).
    #[arg(long)]
    skip_whisper: bool,

    /// Just re-start Shizuku over USB and verify it (fast path after a phone
    /// reboot — Shizuku's service doesn't survive a restart).
    #[arg(long)]
    restart_shizuku_only: bool,
}

fn main() -> Result<()> {
    let cli = Cli::parse();

    println!("== otso-cita desktop setup ==\n");

    println!("[1/8] Locating adb...");
    let mut adb = Adb::ensure()?.with_device(cli.device.clone());
    println!("      using {}", adb.bin.display());

    if adb.device.is_none() {
        println!("\n[2/8] Waiting for a USB-connected, authorized phone...");
        adb.wait_for_single_authorized_device()?;
    } else {
        adb.start_server().ok();
        println!("\n[2/8] Using device {}", adb.device.as_deref().unwrap());
    }

    if cli.restart_shizuku_only {
        println!("\n--restart-shizuku-only: skipping install/config, just (re)starting Shizuku.");
        start_shizuku(&adb)?;
        verify(&adb)?;
        return Ok(());
    }

    println!("\n[3/8] Downloading otso-cita + Shizuku from GitHub Releases...");
    let cache = std::env::temp_dir().join("otso-setup-downloads");
    std::fs::create_dir_all(&cache).ok();
    let otso_apk = cache.join("otso-cita.apk");
    let shizuku_apk = cache.join("shizuku.apk");

    let otso_release = latest_release(OTSO_REPO).context("fetching otso-cita release")?;
    println!("      otso-cita {}", otso_release.tag_name);
    github::download_asset(&otso_release, |n| n.ends_with(".apk"), &otso_apk, "otso-cita.apk")?;

    let shizuku_release = latest_release(SHIZUKU_REPO).context("fetching Shizuku release")?;
    println!("      Shizuku {}", shizuku_release.tag_name);
    github::download_asset(
        &shizuku_release,
        |n| n.ends_with(".apk"),
        &shizuku_apk,
        "shizuku.apk",
    )?;

    println!("\n[4/8] Installing both APKs (can take a minute or two on older phones, especially \
              the first install \u{2014} this is not stuck)...");
    adb.install(&otso_apk).context("installing otso-cita")?;
    println!("      otso-cita installed.");
    adb.install(&shizuku_apk).context("installing Shizuku")?;
    println!("      Shizuku installed.");

    println!("\n[5/8] Enabling the accessibility service (headless, no phone taps needed)...");
    enable_accessibility(&adb)?;
    println!("      done.");

    println!("\n[6/8] Starting Shizuku over USB...");
    start_shizuku(&adb)?;

    println!("\n      Granting the Shizuku permission to otso-cita...");
    match grant_shizuku_permission(&adb) {
        Ok(true) => println!("      granted."),
        Ok(false) | Err(_) => {
            println!(
                "      Couldn't confirm this automatically. Open otso-cita on the phone, \
                 tap GRANT on the Shizuku row, and accept the permission dialog by hand."
            );
        }
    }

    if !cli.skip_whisper {
        println!("\n[7/8] Whisper speech model...");
        if let Err(e) = ensure_whisper(&adb) {
            println!("      couldn't complete the Whisper download automatically ({e}). \
                      Open the app and tap DOWNLOAD on the Whisper row.");
        }
    } else {
        println!("\n[7/8] Skipping Whisper download (--skip-whisper).");
    }

    println!("\n[8/8] Verifying...");
    verify(&adb)?;

    adb.shell(&format!("am start -n {OTSO_PACKAGE}/.MainActivity")).ok();

    println!("\nSetup complete. Remaining manual steps (can't be automated \u{2014} they involve \
              your own credentials):");
    println!("  - Install your Cl@ve digital certificate on the phone (Settings > Security > \
              Encryption & credentials), OR sign in to Cl@ve permanente once by hand in Chrome \
              and let it save the password.");
    println!("  - Make sure the phone is on mobile data before pressing START in the app.");
    println!("  - If accessibility still shows as off in the app, Android's \"Restricted \
              settings\" may have re-blocked it \u{2014} Settings > Apps > otso-cita > \u{22ee} > \
              Allow restricted settings.");
    println!("  - After every phone reboot, Shizuku needs restarting: run this tool again with \
              --restart-shizuku-only.");

    Ok(())
}

fn enable_accessibility(adb: &Adb) -> Result<()> {
    let current = adb
        .shell("settings get secure enabled_accessibility_services")
        .unwrap_or_default();
    let current = current.trim();
    let mut services: Vec<&str> = if current.is_empty() || current == "null" {
        Vec::new()
    } else {
        current.split(':').filter(|s| !s.is_empty()).collect()
    };
    if !services.contains(&OTSO_SERVICE) {
        services.push(OTSO_SERVICE);
    }
    let joined = services.join(":");
    adb.shell(&format!(
        "settings put secure enabled_accessibility_services {joined}"
    ))?;
    adb.shell("settings put secure accessibility_enabled 1")?;
    Ok(())
}

/// Mirrors the README's documented "start Shizuku over USB" adb command:
/// resolve Shizuku's installed lib path and exec its starter binary directly
/// (Shizuku's own bootstrap; it daemonizes itself with shell UID).
fn start_shizuku(adb: &Adb) -> Result<()> {
    let out = adb
        .shell(&format!("pm path {SHIZUKU_PACKAGE}"))
        .context("Shizuku doesn't seem to be installed (pm path failed)")?;
    let first_line = out.lines().next().unwrap_or("").trim();
    let apk_path = first_line.strip_prefix("package:").unwrap_or(first_line);
    let dir = apk_path
        .rsplit_once('/')
        .map(|(d, _)| d)
        .unwrap_or(apk_path);
    let starter = format!("{dir}/lib/arm64/libshizuku.so");
    adb.shell(&starter)
        .context("starting Shizuku's binary over adb shell")?;
    println!("      Shizuku start command sent.");
    Ok(())
}

/// Best-effort UI automation: launch otso-cita, tap GRANT on its Shizuku
/// checklist row, then tap the resulting system permission dialog's allow
/// button. Returns Ok(false) (not an error) if it can't find what it expects
/// — the caller falls back to printing manual instructions.
// The app's UI strings vary by device locale (the checked-out source may also
// be ahead of whatever's in the latest tagged release — seen in testing: a
// device set to Spanish showed "DAR PERMISO" while this repo's HEAD has
// already been translated to English "GRANT"). Try both known languages
// rather than assuming one.
const GRANT_LABELS: &[&str] = &["GRANT", "DAR PERMISO", "OTORGAR", "CONCEDER"];
const ALLOW_LABELS: &[&str] = &[
    "ALLOW",
    "Allow",
    "OK",
    "PERMITIR",
    "Permitir",
    "Allow only this time",
    "While using the app",
    "Solo esta vez",
    "Mientras se usa la app",
];

fn tap_first_match(client: &A11yClient, tree: &Value, labels: &[&str]) -> Result<bool> {
    for label in labels {
        if let Some(node) = a11y::find_text(tree, label, true) {
            if let Some((x, y)) = a11y::center(node) {
                client.tap(x, y)?;
                return Ok(true);
            }
        }
    }
    Ok(false)
}

/// Whether the Setup checklist's Shizuku row already shows "done" (✓) rather
/// than "pending" (○) — its icon sits a couple of tree nodes before the row's
/// title in document order. The checklist section only renders at all while
/// something is still pending (MainActivity hides it once accessibility +
/// Shizuku + Whisper are all ready), so a missing row means everything,
/// Shizuku included, is already done.
fn shizuku_row_done(tree: &Value) -> bool {
    let flat = a11y::flatten(tree);
    let idx = match flat.iter().position(|n| {
        n.get("text")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_lowercase()
            .contains("shizuku")
    }) {
        Some(i) => i,
        None => return true,
    };
    for i in (0..idx).rev().take(3) {
        if let Some(t) = flat[i].get("text").and_then(Value::as_str) {
            if t == "\u{2713}" {
                return true;
            }
            if t == "\u{25cb}" {
                return false;
            }
        }
    }
    false
}

fn grant_shizuku_permission(adb: &Adb) -> Result<bool> {
    adb.shell(&format!("am start -n {OTSO_PACKAGE}/.MainActivity")).ok();
    std::thread::sleep(Duration::from_secs(2));

    let client = A11yClient::connect(adb, A11Y_PORT)?;

    let tree = client.dump()?;
    if shizuku_row_done(&tree) {
        return Ok(true);
    }
    if !tap_first_match(&client, &tree, GRANT_LABELS)? {
        return Ok(false);
    }

    // The system permission dialog can take a moment to render; poll for it
    // instead of a single fixed sleep.
    for _ in 0..6 {
        std::thread::sleep(Duration::from_millis(700));
        let tree = client.dump()?;
        if tap_first_match(&client, &tree, ALLOW_LABELS)? {
            std::thread::sleep(Duration::from_secs(1));
            return Ok(true);
        }
    }
    Ok(false)
}

fn ensure_whisper(adb: &Adb) -> Result<()> {
    let client = A11yClient::connect(adb, A11Y_PORT)?;
    let st = client.whisper_status()?;
    if st.get("model_present").and_then(Value::as_bool).unwrap_or(false) {
        println!("      Whisper model already present.");
        return Ok(());
    }

    println!("      Triggering Whisper speech-model download (~514 MB, one-time \u{2014} use Wi-Fi)...");
    let bg_adb = adb.clone();
    std::thread::spawn(move || {
        if let Ok(c) = A11yClient::connect(&bg_adb, A11Y_PORT) {
            let _ = c.send_with_timeout(json!({"cmd": "whisper_load"}), Duration::from_secs(60 * 60));
        }
    });

    let start = Instant::now();
    let mut last = String::new();
    loop {
        std::thread::sleep(Duration::from_secs(4));
        let st = match client.whisper_status() {
            Ok(v) => v,
            Err(_) => continue,
        };
        let s = st.get("status").and_then(Value::as_str).unwrap_or("").to_string();
        if s != last {
            println!("        whisper: {s}");
            last = s.clone();
        }
        if st.get("model_present").and_then(Value::as_bool).unwrap_or(false) {
            println!("      Whisper model ready.");
            return Ok(());
        }
        if s.contains("failed") {
            println!("      Whisper download reported a failure \u{2014} check the app and retry the DOWNLOAD button.");
            return Ok(());
        }
        if start.elapsed() > Duration::from_secs(45 * 60) {
            println!("      Whisper download is taking a long time; leaving it running in the background \u{2014} check the app later.");
            return Ok(());
        }
    }
}

fn verify(adb: &Adb) -> Result<()> {
    let client = A11yClient::connect(adb, A11Y_PORT)?;
    let on = client.airplane(true)?;
    std::thread::sleep(Duration::from_secs(2));
    let off = client.airplane(false)?;

    let shizuku_ready = off
        .get("shizuku_ready")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    if on.get("ok").and_then(Value::as_bool).unwrap_or(false) && shizuku_ready {
        println!("      Verified: accessibility + Shizuku are working (airplane-mode toggle succeeded).");
    } else {
        println!(
            "      Could not verify the airplane-mode toggle (on={on}, off={off}). \
             IP rotation may not work until Shizuku/accessibility are confirmed in the app."
        );
    }
    Ok(())
}
