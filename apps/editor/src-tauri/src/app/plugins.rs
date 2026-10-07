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
//! picks it. Beside each, its dev-only bots companion,
//! `NetherForgeBots-<pluginVersion>-paper-<minecraft>.jar` (the fake players
//! coding agents and tests drive over the bridge), which a dev server gets too.

use std::path::{Path, PathBuf};

use crate::minecraft::version;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PluginJar {
    pub minecraft: String,
    pub plugin_version: String,
    pub path: PathBuf,
    /// The bots companion of the same build, when it's beside the plugin.
    pub bots: Option<PathBuf>,
}

/// The bots companion's name for a plugin jar's version and Minecraft version.
pub fn bots_jar_name(plugin_version: &str, minecraft: &str) -> String {
    format!("NetherForgeBots-{plugin_version}-paper-{minecraft}.jar")
}

/// Whether a file in a server's `plugins/` is one of ours: the plugin or its bots.
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
                let bots = dir.join(bots_jar_name(&plugin_version, &minecraft));
                Some((
                    PluginJar {
                        bots: bots.is_file().then_some(bots),
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
        std::fs::write(dev.join("NetherForgeBots-0.1.1-paper-1.21.11.jar"), "d").unwrap();
        // Another build's bots don't go with this plugin.
        std::fs::write(bundled.join("NetherForgeBots-0.0.9-paper-26.3.jar"), "e").unwrap();
        std::fs::write(dev.join("README.txt"), "").unwrap();

        let jars = scan(&[bundled.clone(), dev.clone(), tmp.path().join("missing")]);
        let versions: Vec<_> = jars.iter().map(|j| j.minecraft.as_str()).collect();
        assert_eq!(versions, ["26.3", "1.21.11"]);
        assert_eq!(
            jars[0].path,
            bundled.join("NetherForge-0.1.0-paper-26.3.jar")
        );
        assert_eq!(jars[0].bots, None);
        let old = find(&[bundled, dev.clone()], "1.21.11").unwrap();
        assert_eq!(old.path, dev.join("NetherForge-0.1.1-paper-1.21.11.jar"));
        assert_eq!(
            old.bots,
            Some(dev.join("NetherForgeBots-0.1.1-paper-1.21.11.jar"))
        );
        assert!(is_ours("NetherForgeBots-0.1.1-paper-1.21.11.jar"));
        assert!(!is_ours("NetherForgeExtras.zip") && !is_ours("OtherPlugin.jar"));
    }
}
