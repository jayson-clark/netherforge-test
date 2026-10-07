//! App-level pieces: where the editor keeps its files, events, bundled plugin
//! jars, the script test runner and `app_info`.

pub mod dirs;
pub mod events;
pub mod plugins;
pub mod test_runner;

use serde::Serialize;

use crate::platform::Os;

#[derive(Debug, Clone, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    pub version: String,
    /// Minecraft versions the available plugin adapters support, newest first.
    pub minecraft_versions: Vec<String>,
    pub os: Os,
}
