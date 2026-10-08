use tauri::{AppHandle, Runtime, State};

use super::blocking;
use std::sync::Arc;

use crate::app::events::EventSink;
use crate::error::{Error, ErrorCode, Result};
use crate::fs::{ProjectRoot, map};
use crate::project;
use crate::server::process::StartConfig;
use crate::server::{EulaStatus, ServerState};
use crate::settings::{self, EULA_URL, EulaRecord, Settings};
use crate::state::AppState;

#[tauri::command]
#[specta::specta]
pub async fn server_state(state: State<'_, AppState>) -> Result<ServerState> {
    Ok(state.server.state())
}

#[tauri::command]
#[specta::specta]
pub async fn server_eula_status(state: State<'_, AppState>) -> Result<EulaStatus> {
    let record: EulaRecord = settings::load(&state.dirs.eula_file());
    Ok(EulaStatus {
        accepted: record.accepted,
        url: EULA_URL.into(),
    })
}

/// Records that the user accepted the EULA in the UI. Each start writes
/// `eula.txt` into the server's folder from this record.
#[tauri::command]
#[specta::specta]
pub async fn server_eula_accept(state: State<'_, AppState>) -> Result<()> {
    let record = EulaRecord {
        accepted: true,
        accepted_at: Some(project::now_iso()),
    };
    settings::save(&state.dirs.eula_file(), &record)
}

#[tauri::command]
#[specta::specta]
pub async fn server_start(state: State<'_, AppState>) -> Result<()> {
    // The server runs the project's scripts: only a project the user trusts.
    let root = state.trusted_root()?;
    let dirs = state.dirs.clone();
    // Re-read the manifest: the target version may have changed since opening.
    let (opened, settings, eula) = blocking(move || {
        let settings: Settings = settings::load(&dirs.settings_file());
        let eula: EulaRecord = settings::load(&dirs.eula_file());
        // The file may have been edited by hand: the allowlist applies at start too.
        settings.validate()?;
        Ok((project::inspect(root.path())?, settings, eula))
    })
    .await?;
    let config = StartConfig {
        project_root: opened.root.path().to_path_buf(),
        project_name: opened.info.name.clone(),
        minecraft: opened.minecraft.clone(),
        settings,
        eula_accepted: eula.accepted,
        tools: state.tools.clone(),
    };
    state.server.start(config).await
}

#[tauri::command]
#[specta::specta]
pub async fn server_stop(state: State<'_, AppState>) -> Result<()> {
    state.server.stop().await
}

#[tauri::command]
#[specta::specta]
pub async fn server_command(state: State<'_, AppState>, line: String) -> Result<()> {
    state.server.command(&line).await
}

/// Relays one JSON-RPC message (or batch) from the UI's bridge connection to
/// the plugin; its answers come back as `bridge://message`.
#[tauri::command]
#[specta::specta]
pub async fn bridge_send(state: State<'_, AppState>, message: String) -> Result<()> {
    state.server.bridge_send(&message)
}

/// Copies a dev-server world the plugin just saved (`save_world` answered
/// [level] and [dimension], relative to the server's folder) into the project
/// as `maps/<id>/`, reporting `map://capture-progress` as it goes.
#[tauri::command]
#[specta::specta]
pub async fn map_capture<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, AppState>,
    level: String,
    dimension: String,
    id: String,
    replace: bool,
) -> Result<()> {
    let root = state.root()?;
    let server_dir = state.dirs.server_dir(root.path());
    let sink: Arc<dyn EventSink> = Arc::new(app);
    blocking(move || {
        let server = ProjectRoot::new(&server_dir).map_err(|_| {
            Error::new(
                ErrorCode::NotFound,
                "The dev server has no folder yet: start it once first",
            )
        })?;
        map::capture(
            &root,
            &server,
            &level,
            &dimension,
            &id,
            replace,
            &mut |progress| sink.emit(&progress),
        )
    })
    .await
}
