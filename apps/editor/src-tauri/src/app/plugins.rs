//! Finding the NetherForge plugin jars the editor can install into a dev server.
//!
//! - **Bundled app**: `tauri.bundle.conf.json` copies
//!   `apps/plugin/paper-*/build/libs/NetherForge*-paper-*.jar` into the app's
//!   resources under `plugins/`.
//! - **Dev (debug builds)**: also looks in the repo's
//!   `apps/plugin/paper-*/build/libs/`, so `node tools/gradle.mjs :plugin:paper-26.3:assemble`
//!   is all it takes to try a plugin change.
//!
//! One plugin jar per Minecraft version, named
//! `NetherForge-<pluginVersion>-paper-<minecraft>.jar`; the project's target
//! picks it. The bots coding agents and tests drive over the bridge are in it.
//! It also names the Paper build it was tested against ([paper_build]), which
//! is the one the dev server runs.

use std::io::Read;
use std::path::{Path, PathBuf};

use crate::error::{Context, Result, bail};
use crate::minecraft::version;

/// Where a plugin jar names its Paper build (the adapter's, written by the
/// `paperBuildProperties` task): `minecraft=26.3` and `build=152`.
pub const PAPER_PROPERTIES: &str = "META-INF/netherforge/paper.properties";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PluginJar {
    pub minecraft: String,
    pub plugin_version: String,
    pub path: PathBuf,
}

/// Whether a file in a server's `plugins/` is one of ours: the plugin, or the
/// bots companion jar older builds installed beside it (the bots are in the
/// plugin now), which a new install takes away.
pub fn is_ours(name: &str) -> bool {
    (name.starts_with("NetherForge-") || name.starts_with("NetherForgeBots-"))
        && name.ends_with(".jar")
}

/// `NetherForge-0.1.0-paper-26.3.jar` → (`0.1.0`, `26.3`).
pub fn parse_jar_name(name: &str) -> Option<(String, String)> {
    let stem = name.strip_prefix("NetherForge-")?.strip_suffix(".jar")?;
    let (plugin_version, minecraft) = stem.rsplit_once("-paper-")?;
    if plugin_version.is_empty() || !version::is_release(minecraft) {
        return None;
    }
    Some((plugin_version.to_string(), minecraft.to_string()))
}

/// The folders to search, best first.
pub fn search_dirs(resource_dir: Option<&Path>) -> Vec<PathBuf> {
    let mut dirs = Vec::new();
    if let Some(resources) = resource_dir {
        dirs.push(resources.join("plugins"));
    }
    if cfg!(debug_assertions) {
        let plugin_root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../plugin");
        if let Ok(entries) = std::fs::read_dir(&plugin_root) {
            let mut adapters: Vec<PathBuf> = entries
                .flatten()
                .filter(|e| e.file_name().to_string_lossy().starts_with("paper-"))
                .map(|e| e.path().join("build").join("libs"))
                .collect();
            adapters.sort();
            dirs.extend(adapters);
        }
    }
    dirs
}

/// Every plugin jar in [dirs]. For one Minecraft version, a jar from an
/// earlier folder wins; within a folder, the most recently built one.
pub fn scan(dirs: &[PathBuf]) -> Vec<PluginJar> {
    let mut found: Vec<PluginJar> = Vec::new();
    for dir in dirs {
        let Ok(entries) = std::fs::read_dir(dir) else {
            continue;
        };
        let mut here: Vec<(PluginJar, std::time::SystemTime)> = entries
            .flatten()
            .filter_map(|entry| {
                let name = entry.file_name().to_string_lossy().into_owned();
                let (plugin_version, minecraft) = parse_jar_name(&name)?;
                let meta = entry.metadata().ok().filter(|m| m.is_file())?;
                let modified = meta.modified().unwrap_or(std::time::UNIX_EPOCH);
                Some((
                    PluginJar {
                        minecraft,
                        plugin_version,
                        path: entry.path(),
                    },
                    modified,
                ))
            })
            .collect();
        here.sort_by_key(|entry| std::cmp::Reverse(entry.1));
        for (jar, _) in here {
            if !found.iter().any(|f| f.minecraft == jar.minecraft) {
                found.push(jar);
            }
        }
    }
    found.sort_by(|a, b| version::compare(&b.minecraft, &a.minecraft));
    found
}

pub fn find(dirs: &[PathBuf], minecraft: &str) -> Option<PluginJar> {
    scan(dirs)
        .into_iter()
        .find(|jar| version::compare(&jar.minecraft, minecraft).is_eq())
}

/// The Paper build [jar] was tested against, for its Minecraft version.
pub fn paper_build(jar: &PluginJar) -> Result<u64> {
    let name = jar.path.file_name().unwrap_or_default().to_string_lossy();
    let file = std::fs::File::open(&jar.path).context(|| format!("Couldn't open {name}"))?;
    let mut archive = zip::ZipArchive::new(std::io::BufReader::new(file))
        .context(|| format!("{name} isn't a jar"))?;
    let Ok(entry) = archive.by_name(PAPER_PROPERTIES) else {
        bail!(
            "{name} doesn't say which Paper build it's for: it's from an older build, so build it again"
        );
    };
    let mut text = String::new();
    entry
        .take(64 * 1024)
        .read_to_string(&mut text)
        .context(|| format!("Couldn't read {PAPER_PROPERTIES} in {name}"))?;
    parse_paper_properties(&text, &jar.minecraft)
        .context(|| format!("{PAPER_PROPERTIES} in {name}"))
}

/// The `build` of a `paper.properties`, which must be for [minecraft].
fn parse_paper_properties(text: &str, minecraft: &str) -> Result<u64> {
    let value = |key: &str| {
        text.lines()
            .filter(|line| !line.trim_start().starts_with('#'))
            .find_map(|line| Some(line.split_once('=')?).filter(|(k, _)| k.trim() == key))
            .map(|(_, v)| v.trim())
    };
    if value("minecraft") != Some(minecraft) {
        bail!("names no Paper build for Minecraft {minecraft}");
    }
    match value("build").and_then(|build| build.parse().ok()) {
        Some(build) => Ok(build),
        None => bail!("names no Paper build number"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_jar_names() {
        assert_eq!(
            parse_jar_name("NetherForge-0.1.0-paper-26.3.jar"),
            Some(("0.1.0".into(), "26.3".into()))
        );
        assert_eq!(
            parse_jar_name("NetherForge-0.2.0-SNAPSHOT-paper-1.21.11.jar"),
            Some(("0.2.0-SNAPSHOT".into(), "1.21.11".into()))
        );
        assert_eq!(
            parse_jar_name("NetherForge-0.1.0-paper-26.3-sources.jar"),
            None
        );
        assert_eq!(parse_jar_name("NetherForge-0.1.0.jar"), None);
        assert_eq!(parse_jar_name("NetherForgeBots-0.1.0-paper-26.3.jar"), None);
        assert_eq!(parse_jar_name("paper-26.3.jar"), None);
    }

    #[test]
    fn scans_folders_in_order() {
        let tmp = tempfile::tempdir().unwrap();
        let bundled = tmp.path().join("bundled");
        let dev = tmp.path().join("dev");
        std::fs::create_dir_all(&bundled).unwrap();
        std::fs::create_dir_all(&dev).unwrap();
        std::fs::write(bundled.join("NetherForge-0.1.0-paper-26.3.jar"), "a").unwrap();
        std::fs::write(dev.join("NetherForge-0.1.1-paper-26.3.jar"), "b").unwrap();
        std::fs::write(dev.join("NetherForge-0.1.1-paper-1.21.11.jar"), "c").unwrap();
        // An older build's bots companion isn't a plugin jar.
        std::fs::write(bundled.join("NetherForgeBots-0.0.9-paper-26.3.jar"), "e").unwrap();
        std::fs::write(dev.join("README.txt"), "").unwrap();

        let jars = scan(&[bundled.clone(), dev.clone(), tmp.path().join("missing")]);
        let versions: Vec<_> = jars.iter().map(|j| j.minecraft.as_str()).collect();
        assert_eq!(versions, ["26.3", "1.21.11"]);
        assert_eq!(
            jars[0].path,
            bundled.join("NetherForge-0.1.0-paper-26.3.jar")
        );
        let old = find(&[bundled, dev.clone()], "1.21.11").unwrap();
        assert_eq!(old.path, dev.join("NetherForge-0.1.1-paper-1.21.11.jar"));
        assert!(is_ours("NetherForgeBots-0.1.1-paper-1.21.11.jar"));
        assert!(!is_ours("NetherForgeExtras.zip") && !is_ours("OtherPlugin.jar"));
    }

    fn jar_with(dir: &Path, entries: &[(&str, &str)]) -> PluginJar {
        use std::io::Write;
        let path = dir.join("NetherForge-0.1.0-paper-26.3.jar");
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&path).unwrap());
        for (name, text) in entries {
            zip.start_file(*name, zip::write::SimpleFileOptions::default())
                .unwrap();
            zip.write_all(text.as_bytes()).unwrap();
        }
        zip.finish().unwrap();
        PluginJar {
            minecraft: "26.3".into(),
            plugin_version: "0.1.0".into(),
            path,
        }
    }

    #[test]
    fn reads_the_paper_build_a_jar_was_tested_against() {
        let tmp = tempfile::tempdir().unwrap();
        // As Gradle's WriteProperties writes it.
        let jar = jar_with(
            tmp.path(),
            &[(PAPER_PROPERTIES, "build=152\nminecraft=26.3\n")],
        );
        assert_eq!(paper_build(&jar).unwrap(), 152);

        let older = jar_with(tmp.path(), &[("paper-plugin.yml", "name: NetherForge")]);
        let error = paper_build(&older).unwrap_err();
        assert!(error.message().contains("build it again"), "{error}");
    }

    #[test]
    fn paper_properties_must_be_for_the_jars_version() {
        assert_eq!(
            parse_paper_properties("# a comment\nminecraft = 26.3\nbuild = 7\n", "26.3").unwrap(),
            7
        );
        assert!(parse_paper_properties("minecraft=26.2\nbuild=7", "26.3").is_err());
        assert!(parse_paper_properties("minecraft=26.3\nbuild=latest", "26.3").is_err());
        assert!(parse_paper_properties("minecraft=26.3", "26.3").is_err());
    }
}
