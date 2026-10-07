//! The operating system and the environment the search code reads, in one
//! place. Each search (Java, Minecraft installs, launchers) takes an
//! environment struct built from these, so tests can hand it a fake OS and a
//! temp folder for the filesystem root.

use std::path::PathBuf;

/// Serialized as `macos`, `windows` or `linux` (`AppInfo.os`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, specta::Type)]
#[serde(rename_all = "lowercase")]
pub enum Os {
    MacOs,
    Windows,
    Linux,
}

impl Os {
    pub fn current() -> Self {
        if cfg!(target_os = "macos") {
            Os::MacOs
        } else if cfg!(windows) {
            Os::Windows
        } else {
            Os::Linux
        }
    }

    /// `macos`, `windows` or `linux`, as types.ts spells them.
    pub fn name(self) -> &'static str {
        match self {
            Os::MacOs => "macos",
            Os::Windows => "windows",
            Os::Linux => "linux",
        }
    }

    /// Where absolute system paths start: `C:\` on Windows, `/` elsewhere.
    pub fn system_root(self) -> PathBuf {
        match self {
            Os::Windows => PathBuf::from("C:\\"),
            _ => PathBuf::from("/"),
        }
    }
}

/// An environment variable holding a path; unset and empty are both None.
pub fn env_path(key: &str) -> Option<PathBuf> {
    std::env::var_os(key)
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
}

/// The folders on `PATH`.
pub fn path_dirs() -> Vec<PathBuf> {
    std::env::var_os("PATH")
        .map(|p| std::env::split_paths(&p).collect())
        .unwrap_or_default()
}
