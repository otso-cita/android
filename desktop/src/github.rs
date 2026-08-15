use std::path::Path;

use anyhow::{anyhow, Context, Result};
use serde::Deserialize;

#[derive(Deserialize)]
pub struct Release {
    pub tag_name: String,
    pub assets: Vec<Asset>,
}

#[derive(Deserialize)]
pub struct Asset {
    pub name: String,
    pub browser_download_url: String,
}

/// Fetch the latest release of `owner/repo` from the GitHub API.
pub fn latest_release(repo: &str) -> Result<Release> {
    let url = format!("https://api.github.com/repos/{repo}/releases/latest");
    let release: Release = ureq::get(&url)
        .set("User-Agent", "otso-setup")
        .set("Accept", "application/vnd.github+json")
        .call()
        .with_context(|| format!("fetching latest release of {repo}"))?
        .into_json()
        .with_context(|| format!("parsing release JSON for {repo}"))?;
    Ok(release)
}

/// Find the first asset in `release` whose name satisfies `pred` (e.g. ends
/// with ".apk"), downloading it to `dest`.
pub fn download_asset(
    release: &Release,
    pred: impl Fn(&str) -> bool,
    dest: &Path,
    what: &str,
) -> Result<()> {
    let asset = release
        .assets
        .iter()
        .find(|a| pred(&a.name))
        .ok_or_else(|| anyhow!("no matching release asset found (looked at: {:?})",
            release.assets.iter().map(|a| &a.name).collect::<Vec<_>>()))?;
    crate::download::download_with_progress(&asset.browser_download_url, dest, what)
}
