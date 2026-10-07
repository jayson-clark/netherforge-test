//! The dev server process and its state machine.
//!
//! ```text
//! stopped/crashed ──start──▶ preparing ──launch──▶ starting ──"Done ("──▶ running
//!        ▲                      │ stop (cancels)       │ stop                │ stop
//!        │                      ▼                      ▼                     ▼
//!        └──────── stopped ◀── exit ◀──────────────── stopping ◀────────────┘
//!   unexpected exit from starting/running ──▶ crashed (with a message)
//! ```
//!
//! Every start gets a new *generation*. Tasks (output readers, the exit
//! monitor, bridge callbacks) carry theirs and are ignored once it's stale, so
//! a late line from a previous run can never flip the state of the next one.
//!
//! Locks: `state` before `running` when both are needed; never hold either
//! across an await. `preparing` is async and held by a start for its whole
//! preparation: `stop` during `preparing` reports `stopped` at once, but the
//! cancelled preparation only notices at its next check, so a new start waits
//! on this lock until the old one has left the server folder.

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, Weak};
use std::time::Duration;

use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWriteExt, BufReader};
use tokio::process::{ChildStdin, Command};
use tokio::sync::{oneshot, watch};

use crate::app::dirs::AppDirs;
use crate::app::events::{BridgeMessage, EventSink};
use crate::app::plugins;
use crate::bridge::protocol::{self, Hello};
use crate::bridge::{self, Bridge, BridgeHandler};
use crate::error::{Context, Error, ErrorCode, Result, bail};
use crate::minecraft::cache;
use crate::settings::Settings;

use super::leftover;
use super::{
    PrepareProgress, PrepareStep, ServerOutputEvent, ServerPhase, ServerState, Stream, java, paper,
    progress, setup,
};

/// How long `stop` waits after sending `stop` before killing the JVM.
pub const STOP_TIMEOUT: Duration = Duration::from_secs(30);

pub struct StartConfig {
    pub project_root: PathBuf,
    pub project_name: String,
    /// From netherforge.json.
    pub minecraft: Option<String>,
    pub settings: Settings,
    pub eula_accepted: bool,
    pub plugin_dirs: Vec<PathBuf>,
}

struct Running {
    generation: u64,
    stdin: Arc<tokio::sync::Mutex<ChildStdin>>,
    kill: Option<oneshot::Sender<()>>,
    exited: watch::Receiver<bool>,
    bridge: Bridge,
}

pub struct ServerManager {
    dirs: AppDirs,
    sink: Arc<dyn EventSink>,
    state: Mutex<ServerState>,
    generation: AtomicU64,
    running: Mutex<Option<Running>>,
    preparing: tokio::sync::Mutex<()>,
    exporting: AtomicBool,
    http: OnceLock<reqwest::Client>,
}

impl ServerManager {
    pub fn new(dirs: AppDirs, sink: Arc<dyn EventSink>) -> Arc<Self> {
        Arc::new(Self {
            dirs,
            sink,
            state: Mutex::new(ServerState::default()),
            generation: AtomicU64::new(0),
            running: Mutex::new(None),
            preparing: tokio::sync::Mutex::new(()),
            exporting: AtomicBool::new(false),
            http: OnceLock::new(),
        })
    }

    pub fn state(&self) -> ServerState {
        self.state.lock().unwrap().clone()
    }

    fn is_current(&self, generation: u64) -> bool {
        self.generation.load(Ordering::SeqCst) == generation
    }

    /// Applies [change] if [generation] is still current (or None), and emits
    /// `server://state` when something changed.
    fn update(&self, generation: Option<u64>, change: impl FnOnce(&mut ServerState)) {
        let snapshot = {
            let mut state = self.state.lock().unwrap();
            if generation.is_some_and(|g| !self.is_current(g)) {
                return;
            }
            let before = state.clone();
            change(&mut state);
            if *state == before {
                return;
            }
            state.clone()
        };
        self.sink.emit(&snapshot);
    }

    fn report(&self, progress: PrepareProgress) {
        self.sink.emit(&progress);
    }

    fn output(&self, stream: Stream, line: String) {
        self.sink.emit(&ServerOutputEvent { stream, line });
    }

    fn http(&self) -> Result<&reqwest::Client> {
        if let Some(client) = self.http.get() {
            return Ok(client);
        }
        let client = super::download::http_client()?;
        Ok(self.http.get_or_init(|| client))
    }

    /// Shows which version the open project targets while nothing runs.
    pub fn set_target(&self, minecraft: Option<String>) {
        self.update(None, |state| {
            if !state.phase.is_active() {
                *state = ServerState {
                    minecraft,
                    ..ServerState::default()
                };
            }
        });
    }

    /// Moves to `preparing` with a new generation, or fails if a server is
    /// active. Waits first for any earlier preparation (one just cancelled) to
    /// finish; the guard is this preparation's, held until it launches or fails.
    async fn begin(&self, minecraft: &str) -> Result<(u64, tokio::sync::MutexGuard<'_, ()>)> {
        let preparing = self.preparing.lock().await;
        let snapshot;
        let generation;
        {
            let mut state = self.state.lock().unwrap();
            if state.phase.is_active() {
                bail!(Busy, "The dev server is already running");
            }
            generation = self.generation.fetch_add(1, Ordering::SeqCst) + 1;
            *state = ServerState {
                phase: ServerPhase::Preparing,
                minecraft: Some(minecraft.to_string()),
                port: None,
                bridge_connected: false,
                message: Some("Preparing the dev server".into()),
            };
            snapshot = state.clone();
        }
        self.sink.emit(&snapshot);
        Ok((generation, preparing))
    }

    /// Prepares and launches the server; returns once the JVM is running
    /// (phase `starting`). `server://state` reports the rest.
    pub async fn start(self: &Arc<Self>, config: StartConfig) -> Result<()> {
        if !config.eula_accepted {
            bail!(
                EulaRequired,
                "Accept the Minecraft EULA ({}) before starting the dev server",
                crate::settings::EULA_URL
            );
        }
        let minecraft = config.minecraft.clone().context(|| {
            "netherforge.json has no usable Minecraft version: \"minecraft\" must name a release, like \"26.3\"".into()
        })?;
        if self.state().phase.is_active() {
            bail!(Busy, "The dev server is already running");
        }
        if plugins::find(&config.plugin_dirs, &minecraft).is_none() {
            bail!(
                Unavailable,
                "This NetherForge build has no plugin for Minecraft {minecraft}. In a dev checkout, build it with node tools/gradle.mjs :plugin:paper-{minecraft}:build"
            );
        }
        let (generation, _preparing) = self.begin(&minecraft).await?;
        let result = self
            .prepare_and_launch(generation, &config, &minecraft)
            .await;
        if let Err(error) = &result {
            let message = error.to_string();
            self.update(Some(generation), |state| {
                state.phase = ServerPhase::Stopped;
                state.message = Some(message);
            });
        }
        result
    }

    async fn prepare_and_launch(
        self: &Arc<Self>,
        generation: u64,
        config: &StartConfig,
        minecraft: &str,
    ) -> Result<()> {
        let cancelled = || !self.is_current(generation);
        let check = || -> Result<()> {
            if cancelled() {
                bail!(Cancelled, "Cancelled");
            }
            Ok(())
        };
        let report = |p: PrepareProgress| self.report(p);
        let status = |message: &str| {
            let message = message.to_string();
            self.update(Some(generation), |s| s.message = Some(message));
        };

        status("Finding Java");
        let java = match java::find(&java::JavaEnv::current(&self.dirs)).await {
            Some((java, _)) => java,
            None => {
                status("Downloading Java");
                java::install(&self.dirs, self.http()?, &report, &cancelled).await?
            }
        };
        report(progress(
            PrepareStep::Java,
            format!("Using {}", java.display()),
            1,
            Some(1),
        ));
        check()?;

        status(&format!("Getting Paper {minecraft}"));
        let paper_jar =
            paper::ensure(&self.dirs, self.http()?, minecraft, &report, &cancelled).await?;
        check()?;

        status("Setting up the server folder");
        let plugin = plugins::find(&config.plugin_dirs, minecraft)
            .context(|| format!("No NetherForge plugin for Minecraft {minecraft}"))?;
        let dir = self.dirs.server_dir(&config.project_root);
        // Copying the plugin is blocking file work: off the async workers.
        tokio::task::spawn_blocking({
            let dir = dir.clone();
            let root = config.project_root.clone();
            let plugin = plugin.clone();
            let port = config.settings.server_port;
            let motd = format!("NetherForge dev server: {}", config.project_name);
            move || setup::prepare(&dir, &root, &plugin, port, &motd)
        })
        .await
        .map_err(|e| Error::msg(e.to_string()))??;
        report(progress(
            PrepareStep::Plugin,
            format!(
                "Installed NetherForge {} for Minecraft {minecraft}",
                plugin.plugin_version
            ),
            1,
            Some(1),
        ));
        // A server an earlier session left behind (the editor crashed or was
        // killed) would hold the port; stop it rather than fail on it.
        if let Some(pid) = leftover::find(&dir, &config.project_root) {
            status("Stopping the server an earlier session left running");
            self.output(
                Stream::Stdout,
                format!("[NetherForge] Stopping the dev server left running by an earlier session (pid {pid})"),
            );
            leftover::stop(&dir, pid, leftover::GRACE).await;
            check()?;
        }
        check_port_free(config.settings.server_port)?;
        check()?;

        let token = random_token();
        let handler = Arc::new(Handler {
            manager: Arc::downgrade(self),
            generation,
        });
        let bridge = Bridge::listen(token.clone(), handler).await?;
        let args = setup::java_args(
            &config.settings,
            &config.project_root,
            &self.dirs.packages(),
            &paper_jar,
            bridge.port(),
        );
        let envs = [("NETHERFORGE_BRIDGE_TOKEN".to_string(), token)];
        self.launch(
            generation,
            &java,
            &args,
            &dir,
            &envs,
            bridge,
            config.settings.server_port,
        )
        .await
    }

    /// Spawns the process and the tasks that watch it; moves to `starting`.
    #[allow(clippy::too_many_arguments)]
    async fn launch(
        self: &Arc<Self>,
        generation: u64,
        program: &Path,
        args: &[String],
        cwd: &Path,
        envs: &[(String, String)],
        bridge: Bridge,
        port: u16,
    ) -> Result<()> {
        let mut command = Command::new(program);
        command
            .args(args)
            .current_dir(cwd)
            .envs(envs.iter().map(|(k, v)| (k, v)))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        hide_console(&mut command);
        let mut child = match command.spawn() {
            Ok(child) => child,
            Err(e) => {
                bridge.shutdown();
                bail!("Couldn't start {}: {e}", program.display());
            }
        };
        let (Some(stdin), Some(stdout), Some(stderr)) =
            (child.stdin.take(), child.stdout.take(), child.stderr.take())
        else {
            bridge.shutdown();
            bail!("Couldn't attach to the server's console");
        };

        if let Some(pid) = child.id() {
            // Best effort: without it, a leftover is still stopped by the plugin itself.
            let _ = leftover::record(cwd, pid);
        }
        let server_dir = cwd.to_path_buf();

        let (kill_tx, kill_rx) = oneshot::channel::<()>();
        let (exit_tx, exit_rx) = watch::channel(false);
        {
            let mut running = self.running.lock().unwrap();
            if !self.is_current(generation) {
                drop(running);
                let _ = child.start_kill();
                bridge.shutdown();
                bail!(Cancelled, "Cancelled");
            }
            *running = Some(Running {
                generation,
                stdin: Arc::new(tokio::sync::Mutex::new(stdin)),
                kill: Some(kill_tx),
                exited: exit_rx,
                bridge,
            });
        }
        self.update(Some(generation), |state| {
            state.phase = ServerPhase::Starting;
            state.port = Some(port);
            state.message = Some("Starting Paper".into());
        });

        tokio::spawn(self.clone().pump(generation, stdout, Stream::Stdout));
        tokio::spawn(self.clone().pump(generation, stderr, Stream::Stderr));
        let manager = self.clone();
        tokio::spawn(async move {
            let status = tokio::select! {
                status = child.wait() => status,
                Ok(()) = kill_rx => {
                    let _ = child.kill().await;
                    child.wait().await
                }
            };
            leftover::clear(&server_dir);
            manager.on_exit(generation, status);
            let _ = exit_tx.send(true);
        });
        Ok(())
    }

    async fn pump(self: Arc<Self>, generation: u64, stream: impl AsyncRead + Unpin, kind: Stream) {
        let mut reader = BufReader::new(stream);
        let mut buffer = Vec::new();
        loop {
            buffer.clear();
            match reader.read_until(b'\n', &mut buffer).await {
                Ok(0) | Err(_) => break,
                Ok(_) => {}
            }
            if !self.is_current(generation) {
                continue;
            }
            let text = String::from_utf8_lossy(&buffer);
            let line = strip_ansi_escapes::strip_str(text.trim_end_matches(['\r', '\n']));
            let done = kind == Stream::Stdout && is_done_line(&line);
            self.output(kind, line);
            if done {
                self.update(Some(generation), |state| {
                    if state.phase == ServerPhase::Starting {
                        state.phase = ServerPhase::Running;
                        state.message = None;
                    }
                });
            }
        }
    }

    fn on_exit(&self, generation: u64, status: std::io::Result<std::process::ExitStatus>) {
        let running = {
            let mut running = self.running.lock().unwrap();
            if running.as_ref().is_some_and(|r| r.generation == generation) {
                running.take()
            } else {
                None
            }
        };
        if let Some(running) = running {
            running.bridge.shutdown();
        }
        let code = match &status {
            Ok(status) => match status.code() {
                Some(code) => format!("exit code {code}"),
                None => "killed by a signal".into(),
            },
            Err(e) => e.to_string(),
        };
        self.update(Some(generation), |state| {
            if state.phase == ServerPhase::Stopping {
                state.phase = ServerPhase::Stopped;
                state.message = None;
            } else {
                state.phase = ServerPhase::Crashed;
                state.message = Some(format!(
                    "The server stopped unexpectedly ({code}). The console has the details."
                ));
            }
            state.port = None;
            state.bridge_connected = false;
        });
    }

    /// Sends `stop`, waits up to [STOP_TIMEOUT], then kills. Cancels a start
    /// that's still preparing.
    pub async fn stop(self: &Arc<Self>) -> Result<()> {
        self.stop_with(STOP_TIMEOUT).await
    }

    async fn stop_with(self: &Arc<Self>, timeout: Duration) -> Result<()> {
        let handles = {
            let mut state = self.state.lock().unwrap();
            match state.phase {
                ServerPhase::Stopped | ServerPhase::Crashed => return Ok(()),
                ServerPhase::Preparing => {
                    // Invalidate the start in flight; its next check bails.
                    self.generation.fetch_add(1, Ordering::SeqCst);
                    state.phase = ServerPhase::Stopped;
                    state.message = Some("Cancelled".into());
                    None
                }
                _ => {
                    let mut running = self.running.lock().unwrap();
                    match running.as_mut() {
                        Some(r) => {
                            state.phase = ServerPhase::Stopping;
                            state.message = Some("Stopping the server".into());
                            Some((r.stdin.clone(), r.exited.clone(), r.kill.take()))
                        }
                        None => {
                            state.phase = ServerPhase::Stopped;
                            state.message = None;
                            None
                        }
                    }
                }
            }
        };
        self.sink.emit(&self.state());
        let Some((stdin, exited, kill)) = handles else {
            return Ok(());
        };
        let _ = write_line(&stdin, "stop").await;
        if !wait_exited(exited.clone(), timeout).await {
            if let Some(kill) = kill {
                let _ = kill.send(());
            }
            wait_exited(exited, Duration::from_secs(10)).await;
        }
        Ok(())
    }

    /// Runs a console command. `stop` is treated as a requested stop.
    pub async fn command(self: &Arc<Self>, line: &str) -> Result<()> {
        let line = line.trim();
        if line.is_empty() {
            return Ok(());
        }
        if matches!(line.trim_start_matches('/'), "stop" | "end") {
            return self.stop().await;
        }
        let stdin = self
            .running
            .lock()
            .unwrap()
            .as_ref()
            .map(|r| r.stdin.clone())
            .ok_or_else(|| Error::new(ErrorCode::NotConnected, "The dev server isn't running"))?;
        write_line(&stdin, line).await
    }

    fn bridge(&self) -> Option<Bridge> {
        self.running
            .lock()
            .unwrap()
            .as_ref()
            .map(|r| r.bridge.clone())
    }

    fn running_bridge(&self) -> Result<Bridge> {
        self.bridge()
            .ok_or_else(|| Error::new(ErrorCode::NotConnected, "The dev server isn't running"))
    }

    /// Relays one JSON-RPC frame from the UI to the plugin.
    pub fn bridge_send(&self, message: &str) -> Result<()> {
        self.running_bridge()?.send(message)
    }

    /// On the first hello for a version we have no server data for (or only
    /// an export of another schema than the editor reads), ask the plugin to export
    /// it and cache it.
    fn after_hello(self: &Arc<Self>, generation: u64, minecraft: String) {
        let Ok(needed) = cache::needs_export(&self.dirs, &minecraft) else {
            return;
        };
        if !needed || self.exporting.swap(true, Ordering::SeqCst) {
            return;
        }
        let manager = self.clone();
        tokio::spawn(async move {
            let result = match manager.running_bridge() {
                Ok(bridge) => {
                    bridge
                        .request(protocol::EXPORT_GAME_DATA, bridge::EXPORT_TIMEOUT)
                        .await
                }
                Err(error) => Err(error),
            };
            manager.exporting.store(false, Ordering::SeqCst);
            if !manager.is_current(generation) {
                return;
            }
            let saved =
                result.and_then(|data| cache::write_game_data(&manager.dirs, &minecraft, &data));
            match saved {
                Ok(()) => {
                    manager.output(
                        Stream::Stdout,
                        format!("[NetherForge editor] Cached game data for Minecraft {minecraft}"),
                    );
                    if let Ok(status) = cache::status(&manager.dirs, &minecraft) {
                        manager.sink.emit(&status);
                    }
                }
                Err(error) => manager.output(
                    Stream::Stderr,
                    format!("[NetherForge editor] Game data export failed: {error}"),
                ),
            }
        });
    }
}

struct Handler {
    manager: Weak<ServerManager>,
    generation: u64,
}

impl BridgeHandler for Handler {
    fn connected(&self, hello: &Hello) {
        if let Some(manager) = self.manager.upgrade() {
            manager.update(Some(self.generation), |s| s.bridge_connected = true);
            manager.after_hello(self.generation, hello.minecraft.clone());
        }
    }

    fn disconnected(&self) {
        if let Some(manager) = self.manager.upgrade() {
            manager.update(Some(self.generation), |s| s.bridge_connected = false);
        }
    }

    fn refused(&self, reason: &str) {
        if let Some(manager) = self.manager.upgrade()
            && manager.is_current(self.generation)
        {
            manager.output(Stream::Stderr, format!("[NetherForge editor] {reason}"));
        }
    }

    fn message(&self, frame: String) {
        if let Some(manager) = self.manager.upgrade()
            && manager.is_current(self.generation)
        {
            manager.sink.emit(&BridgeMessage { message: frame });
        }
    }
}

async fn write_line(stdin: &tokio::sync::Mutex<ChildStdin>, line: &str) -> Result<()> {
    let mut stdin = stdin.lock().await;
    stdin
        .write_all(format!("{line}\n").as_bytes())
        .await
        .map_err(|e| Error::msg(format!("Couldn't send to the server console: {e}")))?;
    stdin.flush().await.map_err(Error::from)
}

async fn wait_exited(mut exited: watch::Receiver<bool>, timeout: Duration) -> bool {
    tokio::time::timeout(timeout, exited.wait_for(|done| *done))
        .await
        .is_ok_and(|r| r.is_ok())
}

/// No console window flashes up for child processes on Windows.
pub fn hide_console(command: &mut Command) {
    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    #[cfg(not(windows))]
    let _ = command;
}

/// Paper's ready line: `[12:00:00 INFO]: Done (3.123s)! For help, type "help"`.
pub fn is_done_line(line: &str) -> bool {
    line.match_indices("Done (")
        .any(|(i, m)| line[i + m.len()..].starts_with(|c: char| c.is_ascii_digit()))
}

fn check_port_free(port: u16) -> Result<()> {
    match std::net::TcpListener::bind(("0.0.0.0", port)) {
        Ok(_) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::AddrInUse => bail!(
            Busy,
            "Port {port} is already in use (another Minecraft server?). Stop it or pick another port in Settings."
        ),
        Err(_) => Ok(()),
    }
}

fn random_token() -> String {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).expect("the OS random number generator failed");
    hex::encode(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::app::events::RecordingSink;

    fn manager() -> (tempfile::TempDir, Arc<RecordingSink>, Arc<ServerManager>) {
        let tmp = tempfile::tempdir().unwrap();
        let sink = Arc::new(RecordingSink::default());
        let manager = ServerManager::new(AppDirs::in_one(tmp.path()), sink.clone());
        (tmp, sink, manager)
    }

    fn config(tmp: &Path, eula: bool) -> StartConfig {
        StartConfig {
            project_root: tmp.join("project"),
            project_name: "Test".into(),
            minecraft: Some("26.3".into()),
            settings: Settings::default(),
            eula_accepted: eula,
            plugin_dirs: vec![],
        }
    }

    #[tokio::test]
    async fn refuses_to_start_without_the_eula() {
        let (tmp, sink, manager) = manager();
        let error = manager.start(config(tmp.path(), false)).await.unwrap_err();
        assert!(error.message().contains("EULA"), "{error}");
        assert_eq!(manager.state().phase, ServerPhase::Stopped);
        assert!(sink.named::<ServerState>().is_empty());
        assert!(!tmp.path().join("servers").exists());
    }

    #[tokio::test]
    async fn refuses_without_a_version_or_a_plugin() {
        let (tmp, _sink, manager) = manager();
        let mut no_version = config(tmp.path(), true);
        no_version.minecraft = None;
        assert!(
            manager
                .start(no_version)
                .await
                .unwrap_err()
                .message()
                .contains("netherforge.json")
        );
        let error = manager.start(config(tmp.path(), true)).await.unwrap_err();
        assert!(
            error.message().contains("no plugin for Minecraft 26.3"),
            "{error}"
        );
        assert_eq!(manager.state().phase, ServerPhase::Stopped);
    }

    #[test]
    fn detects_the_ready_line() {
        assert!(is_done_line(
            "[12:00:00 INFO]: Done (3.123s)! For help, type \"help\""
        ));
        assert!(!is_done_line("[12:00:00 INFO]: Done (whatever)"));
        assert!(!is_done_line("[12:00:00 INFO]: Preparing spawn area: 50%"));
    }

    #[test]
    fn strip_ansi_escapes_removes_paper_colours_and_titles() {
        assert_eq!(
            strip_ansi_escapes::strip_str("\u{1b}[0;32m[INFO]\u{1b}[m Done\u{1b}]0;title\u{7}!"),
            "[INFO] Done!"
        );
        assert_eq!(strip_ansi_escapes::strip_str("plain"), "plain");
    }

    #[cfg(unix)]
    async fn launch_script(manager: &Arc<ServerManager>, tmp: &Path, script: &str) -> u64 {
        let (generation, _preparing) = manager.begin("26.3").await.unwrap();
        let bridge = Bridge::listen(
            "t".into(),
            Arc::new(Handler {
                manager: Arc::downgrade(manager),
                generation,
            }),
        )
        .await
        .unwrap();
        manager
            .launch(
                generation,
                Path::new("/bin/sh"),
                &["-c".into(), script.into()],
                tmp,
                &[],
                bridge,
                25565,
            )
            .await
            .unwrap();
        generation
    }

    #[tokio::test]
    async fn a_new_start_waits_for_a_cancelled_preparation() {
        let (_tmp, _sink, manager) = manager();
        let (first, preparing) = manager.begin("26.3").await.unwrap();
        manager.stop().await.unwrap();
        assert_eq!(manager.state().phase, ServerPhase::Stopped);
        assert!(!manager.is_current(first));

        // The cancelled preparation hasn't noticed yet: the next start waits for it.
        let next = tokio::spawn({
            let manager = manager.clone();
            async move {
                manager
                    .begin("26.3")
                    .await
                    .map(|(generation, _)| generation)
            }
        });
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert!(!next.is_finished());
        assert_eq!(manager.state().phase, ServerPhase::Stopped);

        drop(preparing);
        let second = next.await.unwrap().unwrap();
        assert!(manager.is_current(second));
        assert_eq!(manager.state().phase, ServerPhase::Preparing);
    }

    #[cfg(unix)]
    async fn wait_for_phase(manager: &ServerManager, phase: ServerPhase) {
        for _ in 0..500 {
            if manager.state().phase == phase {
                return;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        panic!("never reached {phase:?}; state {:?}", manager.state());
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn runs_a_fake_server_through_its_lifecycle() {
        let (tmp, sink, manager) = manager();
        // Prints the ready line, echoes commands, exits cleanly on `stop`.
        let script = r#"echo 'boot'; echo 'oops' >&2; echo '[INFO]: Done (0.1s)! For help, type "help"';
            while read line; do echo "got $line"; [ "$line" = stop ] && exit 0; done"#;
        launch_script(&manager, tmp.path(), script).await;
        wait_for_phase(&manager, ServerPhase::Running).await;
        assert_eq!(manager.state().port, Some(25565));

        manager.command("say hi").await.unwrap();
        for _ in 0..200 {
            if sink
                .named::<ServerOutputEvent>()
                .iter()
                .any(|o| o["line"] == "got say hi")
            {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        manager.stop().await.unwrap();
        assert_eq!(manager.state().phase, ServerPhase::Stopped);
        assert_eq!(manager.state().message, None);

        let phases: Vec<String> = sink
            .named::<ServerState>()
            .iter()
            .map(|s| s["phase"].as_str().unwrap().to_string())
            .collect();
        let mut distinct = phases.clone();
        distinct.dedup();
        assert_eq!(
            distinct,
            ["preparing", "starting", "running", "stopping", "stopped"]
        );
        let output = sink.named::<ServerOutputEvent>();
        assert!(output.contains(&serde_json::json!({"stream": "stdout", "line": "boot"})));
        assert!(output.contains(&serde_json::json!({"stream": "stderr", "line": "oops"})));
        assert!(output.iter().any(|o| o["line"] == "got say hi"));
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn an_unexpected_exit_is_a_crash() {
        let (tmp, _sink, manager) = manager();
        launch_script(&manager, tmp.path(), "echo starting; exit 3").await;
        wait_for_phase(&manager, ServerPhase::Crashed).await;
        let state = manager.state();
        assert!(state.message.unwrap().contains("exit code 3"));
        assert_eq!(state.port, None);
        assert!(manager.command("list").await.is_err());
        // A crashed server can start again.
        launch_script(&manager, tmp.path(), "sleep 30").await;
        assert_eq!(manager.state().phase, ServerPhase::Starting);
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn kills_a_server_that_ignores_stop() {
        let (tmp, _sink, manager) = manager();
        launch_script(
            &manager,
            tmp.path(),
            "trap '' TERM; while read line; do :; done; sleep 30",
        )
        .await;
        manager.stop_with(Duration::from_millis(200)).await.unwrap();
        assert_eq!(manager.state().phase, ServerPhase::Stopped);
    }
    /// Boots real Paper (downloads it, finds Java) with a placeholder plugin
    /// jar, waits for "Done (", then stops it: `cargo test -- --ignored`.
    #[tokio::test]
    #[ignore = "network, Java 25 and ~1 minute"]
    async fn boots_real_paper() {
        let (tmp, sink, manager) = manager();
        let plugins = tmp.path().join("plugins");
        std::fs::create_dir_all(&plugins).unwrap();
        let jar = plugins.join("NetherForge-0.0.0-paper-26.3.jar");
        zip::ZipWriter::new(std::fs::File::create(&jar).unwrap())
            .finish()
            .unwrap();
        std::fs::create_dir_all(tmp.path().join("project")).unwrap();
        let port = std::net::TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port();
        let mut config = config(tmp.path(), true);
        config.plugin_dirs = vec![plugins];
        config.settings.server_port = port;
        manager.start(config).await.unwrap();
        for _ in 0..1800 {
            if matches!(
                manager.state().phase,
                ServerPhase::Running | ServerPhase::Crashed
            ) {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        let state = manager.state();
        if state.phase != ServerPhase::Running {
            for line in sink.named::<ServerOutputEvent>() {
                println!("{}", line["line"]);
            }
        }
        assert_eq!(state.phase, ServerPhase::Running, "{state:?}");
        let dir = AppDirs::in_one(tmp.path()).server_dir(&tmp.path().join("project"));
        assert!(
            dir.join("plugins/NetherForge-0.0.0-paper-26.3.jar")
                .is_file()
        );
        assert!(
            std::fs::read_to_string(dir.join("eula.txt"))
                .unwrap()
                .contains("eula=true")
        );
        assert!(
            std::fs::read_to_string(dir.join("server.properties"))
                .unwrap()
                .contains(&format!("server-port={port}"))
        );
        manager.stop().await.unwrap();
        assert_eq!(manager.state().phase, ServerPhase::Stopped);
        assert!(!sink.named::<PrepareProgress>().is_empty());
    }
}
