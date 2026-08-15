use std::env;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::Duration;

use anyhow::{anyhow, bail, Context, Result};
use indicatif::{ProgressBar, ProgressStyle};

/// A connected (or previously connected) device as reported by `adb devices -l`.
#[derive(Debug, Clone)]
pub struct Device {
    pub serial: String,
    pub state: String, // "device", "unauthorized", "offline", ...
}

#[derive(Clone)]
pub struct Adb {
    pub bin: PathBuf,
    pub device: Option<String>,
}

impl Adb {
    /// Find `adb` on PATH, or download Google's official platform-tools zip
    /// for this OS into a per-user cache dir.
    pub fn ensure() -> Result<Adb> {
        if let Some(p) = which_adb() {
            return Ok(Adb { bin: p, device: None });
        }
        let bin = download_platform_tools()?;
        Ok(Adb { bin, device: None })
    }

    pub fn with_device(mut self, serial: Option<String>) -> Self {
        self.device = serial;
        self
    }

    fn command(&self) -> Command {
        let mut c = Command::new(&self.bin);
        if let Some(d) = &self.device {
            c.arg("-s").arg(d);
        }
        c
    }

    pub fn start_server(&self) -> Result<()> {
        run(Command::new(&self.bin).arg("start-server"))?;
        Ok(())
    }

    pub fn devices(&self) -> Result<Vec<Device>> {
        let mut c = Command::new(&self.bin);
        c.arg("devices").arg("-l");
        let out = run(&mut c)?;
        let mut devices = Vec::new();
        for line in out.lines().skip(1) {
            let line = line.trim();
            if line.is_empty() {
                continue;
            }
            let mut parts = line.split_whitespace();
            let serial = match parts.next() {
                Some(s) => s.to_string(),
                None => continue,
            };
            let state = parts.next().unwrap_or("unknown").to_string();
            devices.push(Device { serial, state });
        }
        Ok(devices)
    }

    /// Block (printing status) until exactly one device is connected and
    /// authorized, then pin this Adb instance to it.
    pub fn wait_for_single_authorized_device(&mut self) -> Result<()> {
        self.start_server().ok();
        let mut last_summary = String::new();
        loop {
            let devices = self.devices().unwrap_or_default();
            let summary = format!("{devices:?}");
            let authorized: Vec<&Device> = devices.iter().filter(|d| d.state == "device").collect();

            if authorized.len() == 1 {
                let serial = authorized[0].serial.clone();
                println!("Using device {serial}");
                self.device = Some(serial);
                return Ok(());
            }

            if summary != last_summary {
                if devices.is_empty() {
                    println!("Waiting for a phone \u{2014} connect it via USB and accept the \u{201c}Allow USB debugging\u{201d} prompt on screen.");
                } else if devices.iter().any(|d| d.state == "unauthorized") {
                    println!("Phone connected but not authorized yet \u{2014} unlock it and accept the USB debugging prompt.");
                } else if authorized.len() > 1 {
                    let serials: Vec<_> = authorized.iter().map(|d| d.serial.clone()).collect();
                    println!(
                        "Multiple authorized devices found ({}). Disconnect all but the one you want, then this will continue automatically.",
                        serials.join(", ")
                    );
                } else {
                    println!("Waiting for device... ({devices:?})");
                }
                last_summary = summary;
            }
            std::thread::sleep(Duration::from_secs(2));
        }
    }

    pub fn shell(&self, cmd: &str) -> Result<String> {
        let mut c = self.command();
        c.arg("shell").arg(cmd);
        run(&mut c)
    }

    pub fn install(&self, apk: &Path) -> Result<()> {
        let mut c = self.command();
        c.arg("install").arg("-r").arg(apk);
        run(&mut c)?;
        Ok(())
    }

    pub fn forward(&self, local_port: u16, remote_abstract: &str) -> Result<()> {
        let mut c = self.command();
        c.arg("forward")
            .arg(format!("tcp:{local_port}"))
            .arg(format!("localabstract:{remote_abstract}"));
        run(&mut c)?;
        Ok(())
    }
}

fn run(cmd: &mut Command) -> Result<String> {
    let out = cmd.output().context("running adb")?;
    let stdout = String::from_utf8_lossy(&out.stdout).to_string();
    if !out.status.success() {
        let stderr = String::from_utf8_lossy(&out.stderr);
        bail!("{:?} failed: {}{}", cmd, stdout, stderr);
    }
    Ok(stdout)
}

fn which_adb() -> Option<PathBuf> {
    let exe = if cfg!(windows) { "adb.exe" } else { "adb" };
    let path_env = env::var_os("PATH")?;
    for dir in env::split_paths(&path_env) {
        let candidate = dir.join(exe);
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    None
}

fn cache_dir() -> Result<PathBuf> {
    let dirs = directories::ProjectDirs::from("", "otso-cita", "otso-setup")
        .ok_or_else(|| anyhow!("could not determine a per-user cache directory"))?;
    Ok(dirs.cache_dir().to_path_buf())
}

fn download_platform_tools() -> Result<PathBuf> {
    let cache = cache_dir()?.join("platform-tools");
    let bin_name = if cfg!(windows) { "adb.exe" } else { "adb" };
    let bin_path = cache.join(bin_name);
    if bin_path.exists() {
        return Ok(bin_path);
    }

    let os = if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "darwin"
    } else {
        "linux"
    };
    let url = format!("https://dl.google.com/android/repository/platform-tools-latest-{os}.zip");

    std::fs::create_dir_all(cache.parent().unwrap_or(&cache))
        .context("creating cache directory")?;

    println!("Downloading adb (Android platform-tools) for {os}...");
    let zip_path = cache_dir()?.join("platform-tools.zip");
    crate::download::download_with_progress(&url, &zip_path, "adb")?;

    let file = std::fs::File::open(&zip_path).context("opening downloaded platform-tools.zip")?;
    let mut archive = zip::ZipArchive::new(file).context("reading platform-tools.zip")?;
    let extract_root = cache_dir()?;
    archive
        .extract(&extract_root)
        .context("extracting platform-tools.zip")?;
    std::fs::remove_file(&zip_path).ok();

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut perms = std::fs::metadata(&bin_path)?.permissions();
        perms.set_mode(0o755);
        std::fs::set_permissions(&bin_path, perms)?;
    }

    if !bin_path.exists() {
        bail!("adb binary not found after extracting platform-tools at {bin_path:?}");
    }

    // Sanity check it actually runs (also silences the pb below if unused).
    let pb = ProgressBar::new_spinner();
    pb.set_style(ProgressStyle::default_spinner());
    pb.finish_and_clear();
    let mut check = Command::new(&bin_path);
    check.arg("version");
    run(&mut check)?;

    Ok(bin_path)
}
