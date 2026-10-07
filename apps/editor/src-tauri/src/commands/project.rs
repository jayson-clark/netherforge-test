use std::collections::BTreeMap;
use std::path::PathBuf;

use tauri::{AppHandle, Runtime, State};

use super::blocking;
use crate::app::events::{EventSink, FsChangedEvent};
use crate::error::Result;
use crate::project::{self, ProjectInfo, RecentProject};
use crate::settings;
use crate::state::{AppState, OpenProject};
use crate::trust;
use crate::watcher;

#[tauri::command]
#[specta::specta]
pub async fn project_recent(state: State<'_, AppState>) -> Result<Vec<RecentProject>> {
    let list: Vec<RecentProject> = settings::load(&state.dirs.recent_file());
    Ok(project::existing_recent(list))
}

#[tauri::command]
#[specta::specta]
pub async fn project_open<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, AppState>,
    root: String,
) -> Result<ProjectInfo> {
    open(app, &state, PathBuf::from(root)).await
}

#[tauri::command]
#[specta::specta]
pub async fn project_create<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, AppState>,
    root: String,
    files: BTreeMap<String, String>,
) -> Result<ProjectInfo> {
    let path = PathBuf::from(&root);
    let file = state.dirs.trust_file();
    // Made here by the user: theirs to run.
    blocking(move || {
        project::create(&path, &files)?;
        trust::set(&file, crate::fs::ProjectRoot::new(&path)?.path(), true)
    })
    .await?;
    open(app, &state, PathBuf::from(root)).await
}

#[tauri::command]
#[specta::specta]
pub async fn project_close(state: State<'_, AppState>) -> Result<()> {
    state.close_project().await;
    Ok(())
}

/// Trusts the open project (or, `false`, stops trusting it, stopping its dev
/// server and lua-language-server), and answers it as opened.
#[tauri::command]
#[specta::specta]
pub async fn project_trust(state: State<'_, AppState>, trusted: bool) -> Result<ProjectInfo> {
    let root = state.root()?;
    let file = state.dirs.trust_file();
    let path = root.path().to_path_buf();
    blocking(move || trust::set(&file, &path, trusted)).await?;
    if !trusted {
        state.luals.stop();
        state.server.stop().await?;
    }
    state.set_trusted(trusted);
    state.opened().map(|opened| opened.info).ok_or_else(|| {
        crate::error::Error::new(crate::error::ErrorCode::NoProject, "No project is open")
    })
}

async fn open<R: Runtime>(
    app: AppHandle<R>,
    state: &AppState,
    root: PathBuf,
) -> Result<ProjectInfo> {
    let file = state.dirs.trust_file();
    let mut opened = blocking(move || {
        let mut opened = project::inspect(&root)?;
        opened.info.trusted = trust::is_trusted(&file, opened.root.path());
        Ok(opened)
    })
    .await?;
    // Re-opening the open project (a webview reload) keeps its watcher and
    // server; opening another one stops them first.
    if let Some(current) = state.opened()
        && current.root.path() == opened.root.path()
    {
        opened.info.trusted = current.info.trusted;
        return Ok(opened.info);
    }
    state.close_project().await;

    let sink: std::sync::Arc<dyn EventSink> = std::sync::Arc::new(app);
    let watch = watcher::watch(opened.root.path(), move |batch| {
        let event = FsChangedEvent {
            paths: batch.paths,
            rescan: batch.rescan,
        };
        sink.emit(&event);
    })?;

    let recent_file = state.dirs.recent_file();
    let recent: Vec<RecentProject> = settings::load(&recent_file);
    let recent = project::add_recent(recent, RecentProject::from(&opened));
    if let Err(error) = settings::save(&recent_file, &recent) {
        eprintln!("[netherforge] couldn't save the recent list: {error}");
    }

    state.server.set_target(opened.minecraft.clone());
    let info = opened.info.clone();
    *state.project.lock().unwrap() = Some(OpenProject {
        opened,
        _watch: watch,
    });
    state.refresh_agents();
    Ok(info)
}
