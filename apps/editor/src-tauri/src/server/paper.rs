//! Downloading Paper through PaperMC's Fill API (v3), cached per build in
//! `<data>/paper/paper-<version>-<build>.jar`.
//!
//! The build is the one the plugin jar was tested against (its
//! `META-INF/netherforge/paper.properties`, see `plugins::paper_build`), never
//! `builds/latest`: a newer Paper build can change the server internals the
//! adapter reaches into. Once cached, a build starts without the network.
//! Each download is verified against the API's sha256.

use std::path::PathBuf;

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

pub fn build_url(minecraft: &str, build: u64) -> String {
    format!("{API}/versions/{minecraft}/builds/{build}")
}

/// The server download of a Fill `builds/<id>` response.
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

/// The Paper jar to run for [minecraft]'s [build], downloading it if it
/// isn't cached.
pub async fn ensure(
    dirs: &AppDirs,
    client: &reqwest::Client,
    minecraft: &str,
    build: u64,
    report: Progress<'_>,
    cancelled: &(dyn Fn() -> bool + Send + Sync),
) -> Result<PathBuf> {
    // Only a download whose sha256 matched is renamed into place.
    let path = dirs.paper().join(cached_name(minecraft, build));
    if path.is_file() {
        return Ok(path);
    }
    report(progress(
        PrepareStep::Paper,
        format!("Looking up Paper {minecraft} build {build}"),
        0,
        None,
    ));
    let build = fetch_build(client, minecraft, build).await?;
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

async fn fetch_build(client: &reqwest::Client, minecraft: &str, id: u64) -> Result<PaperBuild> {
    let response = client
        .get(build_url(minecraft, id))
        .send()
        .await
        .context(|| {
            format!("Couldn't reach the PaperMC API to download Paper {minecraft} build {id}")
        })?;
    if response.status() == reqwest::StatusCode::NOT_FOUND {
        bail!("Paper has no build {id} for Minecraft {minecraft}");
    }
    if !response.status().is_success() {
        bail!("The PaperMC API answered HTTP {}", response.status());
    }
    let build = parse_build(&response.text().await?)?;
    if build.id != id {
        bail!("The PaperMC API answered build {} for build {id}", build.id);
    }
    Ok(build)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_the_fill_fixture() {
        let json = std::fs::read_to_string(
            std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("testdata/paper-build-latest.json"),
        )
        .unwrap();
        let build = parse_build(&json).unwrap();
        assert!(build.id > 0);
        assert_eq!(build.name, format!("paper-26.3-{}.jar", build.id));
        assert_eq!(build.sha256.len(), 64);
        assert!(build.url.starts_with("https://") && build.url.ends_with(&build.name));
        assert!(build.size > 0);
        assert_eq!(
            build_url("26.3", 152),
            "https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/152"
        );
    }

    #[test]
    fn rejects_odd_responses() {
        assert!(parse_build(r#"{"ok":false,"error":"version_not_found"}"#).is_err());
        assert!(parse_build(r#"{"id":1,"downloads":{}}"#).is_err());
        let traversal = r#"{"id":1,"downloads":{"server:default":{"name":"../x.jar","checksums":{"sha256":"00"},"url":"https://x"}}}"#;
        assert!(parse_build(traversal).is_err());
    }

    /// Hits the real Fill API and downloads Paper: `cargo test -- --ignored`.
    #[tokio::test]
    #[ignore = "network"]
    async fn downloads_a_paper_build() {
        let tmp = tempfile::tempdir().unwrap();
        let dirs = AppDirs::in_one(tmp.path());
        let client = download::http_client().unwrap();
        let jar = ensure(&dirs, &client, "26.3", 152, &|_| {}, &|| false)
            .await
            .unwrap();
        assert_eq!(jar.file_name().unwrap(), "paper-26.3-152.jar");
        assert!(jar.metadata().unwrap().len() > 1_000_000);
        let again = ensure(&dirs, &client, "26.3", 152, &|_| {}, &|| false)
            .await
            .unwrap();
        assert_eq!(jar, again);
        let missing = ensure(&dirs, &client, "9.9", 1, &|_| {}, &|| false).await;
        assert!(missing.unwrap_err().message().contains("no build"));
    }
}
