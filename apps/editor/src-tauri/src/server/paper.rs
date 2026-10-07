//! Downloading Paper through PaperMC's Fill API (v3), cached per build in
//! `<data>/paper/paper-<version>-<build>.jar`.
//!
//! We ask for `builds/latest` each start. If that fails (offline), the newest
//! cached build for the version is used, so a dev server still starts on a
//! plane. Each download is verified against the API's sha256.

use std::path::{Path, PathBuf};

use serde::Deserialize;

use crate::app::dirs::AppDirs;
use crate::error::{Context, Result, bail};

use super::{PrepareStep, Progress, download, progress};

const API: &str = "https://fill.papermc.io/v3/projects/paper";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PaperBuild {
    pub id: u64,
    pub channel: String,
    pub name: String,
    pub sha256: String,
    pub size: u64,
    pub url: String,
}

#[derive(Deserialize)]
struct BuildJson {
    id: u64,
    #[serde(default)]
    channel: String,
    downloads: std::collections::HashMap<String, DownloadJson>,
}

#[derive(Deserialize)]
struct DownloadJson {
    name: String,
    checksums: Checksums,
    #[serde(default)]
    size: u64,
    url: String,
}

#[derive(Deserialize)]
struct Checksums {
    sha256: String,
}

pub fn latest_build_url(minecraft: &str) -> String {
    format!("{API}/versions/{minecraft}/builds/latest")
}

/// The server download of a Fill `builds/latest` response.
pub fn parse_build(json: &str) -> Result<PaperBuild> {
    let build: BuildJson =
        serde_json::from_str(json).context(|| "Unexpected answer from the PaperMC API".into())?;
    let mut downloads = build.downloads;
    let download = downloads
        .remove("server:default")
        .context(|| format!("Paper build {} has no server download", build.id))?;
    if download.name.contains(['/', '\\']) || !download.name.ends_with(".jar") {
        bail!("Unexpected Paper download name {}", download.name);
    }
    Ok(PaperBuild {
        id: build.id,
        channel: build.channel,
        name: download.name,
        sha256: download.checksums.sha256,
        size: download.size,
        url: download.url,
    })
}

fn cached_name(minecraft: &str, build: u64) -> String {
    format!("paper-{minecraft}-{build}.jar")
}

/// Cached builds for [minecraft], newest first.
pub fn cached_builds(dir: &Path, minecraft: &str) -> Vec<(u64, PathBuf)> {
    let prefix = format!("paper-{minecraft}-");
    let mut builds: Vec<(u64, PathBuf)> = std::fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|entry| {
            let name = entry.file_name().to_string_lossy().into_owned();
            let build = name
                .strip_prefix(&prefix)?
                .strip_suffix(".jar")?
                .parse()
                .ok()?;
            Some((build, entry.path()))
        })
        .collect();
    builds.sort_by_key(|build| std::cmp::Reverse(build.0));
    builds
}

/// The Paper jar to run for [minecraft], downloading it if needed.
pub async fn ensure(
    dirs: &AppDirs,
    client: &reqwest::Client,
    minecraft: &str,
    report: Progress<'_>,
    cancelled: &(dyn Fn() -> bool + Send + Sync),
) -> Result<PathBuf> {
    let dir = dirs.paper();
    report(progress(
        PrepareStep::Paper,
        format!("Checking for Paper {minecraft}"),
        0,
        None,
    ));
    let latest = fetch_latest(client, minecraft).await;
    let build = match latest {
        Ok(build) => build,
        Err(error) => {
            if let Some((_, path)) = cached_builds(&dir, minecraft).into_iter().next() {
                return Ok(path);
            }
            return Err(error);
        }
    };

    let path = dir.join(cached_name(minecraft, build.id));
    if path.is_file()
        && download::sha256_file(&path)
            .await?
            .eq_ignore_ascii_case(&build.sha256)
    {
        return Ok(path);
    }
    let label = format!("Downloading Paper {minecraft} (build {})", build.id);
    download::download(
        client,
        &build.url,
        &path,
        Some(&build.sha256),
        &|done, total| {
            report(progress(
                PrepareStep::Paper,
                label.clone(),
                done,
                total.or(Some(build.size)),
            ))
        },
        cancelled,
    )
    .await?;
    Ok(path)
}

async fn fetch_latest(client: &reqwest::Client, minecraft: &str) -> Result<PaperBuild> {
    let response = client
        .get(latest_build_url(minecraft))
        .send()
        .await
        .context(|| "Couldn't reach the PaperMC API".into())?;
    if response.status() == reqwest::StatusCode::NOT_FOUND {
        bail!("Paper has no builds for Minecraft {minecraft}");
    }
    if !response.status().is_success() {
        bail!("The PaperMC API answered HTTP {}", response.status());
    }
    parse_build(&response.text().await?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_the_fill_fixture() {
        let json = std::fs::read_to_string(
            Path::new(env!("CARGO_MANIFEST_DIR")).join("testdata/paper-build-latest.json"),
        )
        .unwrap();
        let build = parse_build(&json).unwrap();
        assert!(build.id > 0);
        assert_eq!(build.name, format!("paper-26.3-{}.jar", build.id));
        assert_eq!(build.sha256.len(), 64);
        assert!(build.url.starts_with("https://") && build.url.ends_with(&build.name));
        assert!(build.size > 0);
        assert_eq!(
            latest_build_url("26.3"),
            "https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/latest"
        );
    }

    #[test]
    fn rejects_odd_responses() {
        assert!(parse_build(r#"{"ok":false,"error":"version_not_found"}"#).is_err());
        assert!(parse_build(r#"{"id":1,"downloads":{}}"#).is_err());
        let traversal = r#"{"id":1,"downloads":{"server:default":{"name":"../x.jar","checksums":{"sha256":"00"},"url":"https://x"}}}"#;
        assert!(parse_build(traversal).is_err());
    }

    #[test]
    fn finds_cached_builds_newest_first() {
        let tmp = tempfile::tempdir().unwrap();
        for name in [
            "paper-26.3-12.jar",
            "paper-26.3-143.jar",
            "paper-26.3.1-200.jar",
            "paper-26.3-9.jar.part",
        ] {
            std::fs::write(tmp.path().join(name), "").unwrap();
        }
        let builds: Vec<u64> = cached_builds(tmp.path(), "26.3")
            .into_iter()
            .map(|b| b.0)
            .collect();
        assert_eq!(builds, [143, 12]);
    }
    /// Hits the real Fill API and downloads Paper: `cargo test -- --ignored`.
    #[tokio::test]
    #[ignore = "network"]
    async fn downloads_the_latest_paper() {
        let tmp = tempfile::tempdir().unwrap();
        let dirs = AppDirs::in_one(tmp.path());
        let client = download::http_client().unwrap();
        let jar = ensure(&dirs, &client, "26.3", &|_| {}, &|| false)
            .await
            .unwrap();
        assert!(jar.metadata().unwrap().len() > 1_000_000);
        let again = ensure(&dirs, &client, "26.3", &|_| {}, &|| false)
            .await
            .unwrap();
        assert_eq!(jar, again);
        let missing = ensure(&dirs, &client, "9.9", &|_| {}, &|| false).await;
        assert!(missing.unwrap_err().message().contains("no builds"));
    }
}
