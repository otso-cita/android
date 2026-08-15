use std::io::{Read, Write};
use std::path::Path;

use anyhow::{Context, Result};
use indicatif::{ProgressBar, ProgressStyle};

/// Download `url` to `dest`, showing a progress bar labeled with `what`.
pub fn download_with_progress(url: &str, dest: &Path, what: &str) -> Result<()> {
    let resp = ureq::get(url)
        .set("User-Agent", "otso-setup")
        .call()
        .with_context(|| format!("requesting {url}"))?;

    let len: u64 = resp
        .header("Content-Length")
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);

    let pb = ProgressBar::new(len);
    pb.set_style(
        ProgressStyle::with_template(
            "{msg} [{bar:40.cyan/blue}] {bytes}/{total_bytes} ({eta})",
        )
        .unwrap_or_else(|_| ProgressStyle::default_bar())
        .progress_chars("=> "),
    );
    pb.set_message(what.to_string());

    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent).ok();
    }
    let mut file = std::fs::File::create(dest).with_context(|| format!("creating {dest:?}"))?;
    let mut reader = pb.wrap_read(resp.into_reader());
    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = reader.read(&mut buf)?;
        if n == 0 {
            break;
        }
        file.write_all(&buf[..n])?;
    }
    pb.finish_with_message(format!("{what} downloaded"));
    Ok(())
}
