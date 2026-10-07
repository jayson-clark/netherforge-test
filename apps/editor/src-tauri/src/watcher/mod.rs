//! Watches the open project and emits `fs://changed` with project paths.
//!
//! Split so the logic is testable without a real filesystem watcher:
//! - [map_path] turns an absolute path from `notify` into a project path, or
//!   drops it (outside the root, `.git/`, `.netherforge/`, our temp files).
//! - [Debouncer] collects paths and releases them once nothing new arrived for
//!   [QUIET] (or [MAX_WAIT] after the first, so a constant stream of changes
//!   still gets reported).
//! - [watch] wires `notify` to both on a background thread.
//!
//! Hidden folders aren't watched at all, not just filtered: `.netherforge/`
//! is the editor's own output (schemas, docs, thumbnails, staged world
//! copies), rewritten in bulk, and `.git` churns on every commit (on Linux
//! each watched folder costs an inotify watch). So the root is watched
//! on its own and each other top-level folder recursively, adding folders as
//! they appear. When `notify` reports an error or says events were lost, the
//! batch says `rescan` and the UI re-reads everything instead of trusting it.

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, mpsc};
use std::time::{Duration, Instant};

use notify::{RecursiveMode, Watcher as _};

use crate::error::{Context, Result};
use crate::fs::{atomic, is_hidden, scope};

pub const QUIET: Duration = Duration::from_millis(100);
pub const MAX_WAIT: Duration = Duration::from_millis(1000);

/// The project path for a path `notify` reported, or None if the UI shouldn't
/// hear about it. [roots] are the root as canonicalized plus as originally
/// given: FSEvents reports `/private/var/...` for a root opened as `/var/...`.
pub fn map_path(roots: &[PathBuf], absolute: &Path) -> Option<String> {
    let path = roots
        .iter()
        .find_map(|root| scope::relative_project_path(root, absolute))?;
    if is_hidden(&path) {
        return None;
    }
    let name = path.rsplit('/').next().unwrap_or(&path);
    if atomic::is_temp_file_name(name) {
        return None;
    }
    Some(path)
}

/// What changed since the last batch.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Batch {
    /// Project paths, deduplicated and sorted.
    pub paths: Vec<String>,
    /// Events may have been lost: [paths] isn't the whole story.
    pub rescan: bool,
}

#[derive(Debug)]
pub struct Debouncer {
    pending: BTreeSet<String>,
    rescan: bool,
    first: Option<Instant>,
    last: Option<Instant>,
    quiet: Duration,
    max_wait: Duration,
}

impl Debouncer {
    pub fn new(quiet: Duration, max_wait: Duration) -> Self {
        Self {
            pending: BTreeSet::new(),
            rescan: false,
            first: None,
            last: None,
            quiet,
            max_wait,
        }
    }

    pub fn push(&mut self, paths: impl IntoIterator<Item = String>, now: Instant) {
        let before = self.pending.len();
        self.pending.extend(paths);
        if self.pending.len() > before || self.first.is_some() {
            self.schedule(now);
        }
    }

    /// Something may have been missed; the next batch says so.
    pub fn push_rescan(&mut self, now: Instant) {
        self.rescan = true;
        self.schedule(now);
    }

    fn schedule(&mut self, now: Instant) {
        self.first.get_or_insert(now);
        self.last = Some(now);
    }

    /// When the pending batch is due, if there is one.
    pub fn deadline(&self) -> Option<Instant> {
        let (first, last) = (self.first?, self.last?);
        Some((last + self.quiet).min(first + self.max_wait))
    }

    /// The batch, if it's due at [now].
    pub fn take_due(&mut self, now: Instant) -> Option<Batch> {
        if self.deadline()? > now {
            return None;
        }
        self.first = None;
        self.last = None;
        Some(Batch {
            paths: std::mem::take(&mut self.pending).into_iter().collect(),
            rescan: std::mem::take(&mut self.rescan),
        })
    }
}

/// Stops watching when dropped: the watcher goes, and with it the only
/// sender of its events, which ends the thread.
pub struct WatchHandle {
    _watcher: Arc<Mutex<notify::RecommendedWatcher>>,
}

enum Message {
    Paths(Vec<PathBuf>),
    Rescan,
}

/// Whether [name], a top-level entry of the project, is a folder to watch.
fn watched_folder(root: &Path, name: &str) -> bool {
    !is_hidden(name) && root.join(name).is_dir()
}

/// Watches [root]; [on_change] gets each debounced batch, on a background thread.
pub fn watch(root: &Path, on_change: impl Fn(Batch) + Send + 'static) -> Result<WatchHandle> {
    let mut roots = vec![root.to_path_buf()];
    if let Ok(real) = std::fs::canonicalize(root)
        && real != root
    {
        roots.push(real);
    }
    let (events, rx) = mpsc::channel::<Message>();
    let mut watcher = notify::recommended_watcher(move |event: notify::Result<notify::Event>| {
        let message = match event {
            Ok(event) if event.need_rescan() => Message::Rescan,
            Ok(event) => Message::Paths(event.paths),
            Err(_) => Message::Rescan,
        };
        let _ = events.send(message);
    })
    .context(|| "Couldn't start the file watcher".into())?;
    watcher
        .watch(root, RecursiveMode::NonRecursive)
        .context(|| format!("Couldn't watch {}", root.display()))?;
    let mut folders = BTreeSet::new();
    for entry in std::fs::read_dir(root).context(|| format!("Couldn't list {}", root.display()))? {
        let name = entry?.file_name().to_string_lossy().into_owned();
        if watched_folder(root, &name) {
            watcher
                .watch(&root.join(&name), RecursiveMode::Recursive)
                .context(|| format!("Couldn't watch {name}"))?;
            folders.insert(name);
        }
    }

    let root = root.to_path_buf();
    let watcher = Arc::new(Mutex::new(watcher));
    let adder = Arc::downgrade(&watcher);
    std::thread::Builder::new()
        .name("netherforge-watcher".into())
        .spawn(move || {
            let mut debouncer = Debouncer::new(QUIET, MAX_WAIT);
            loop {
                let received = match debouncer.deadline() {
                    Some(deadline) => {
                        let wait = deadline.saturating_duration_since(Instant::now());
                        rx.recv_timeout(wait)
                    }
                    None => rx.recv().map_err(|_| mpsc::RecvTimeoutError::Disconnected),
                };
                match received {
                    Ok(Message::Paths(paths)) => {
                        let mapped: Vec<String> =
                            paths.iter().filter_map(|p| map_path(&roots, p)).collect();
                        // A new top-level folder: watch inside it too. Anything
                        // written there before the watch started is a rescan.
                        for path in &mapped {
                            if !path.contains('/')
                                && !folders.contains(path)
                                && watched_folder(&root, path)
                            {
                                let added = adder.upgrade().is_some_and(|w| {
                                    w.lock()
                                        .unwrap()
                                        .watch(&root.join(path), RecursiveMode::Recursive)
                                        .is_ok()
                                });
                                if added {
                                    folders.insert(path.clone());
                                }
                                debouncer.push_rescan(Instant::now());
                            }
                        }
                        debouncer.push(mapped, Instant::now());
                    }
                    Ok(Message::Rescan) => debouncer.push_rescan(Instant::now()),
                    Err(mpsc::RecvTimeoutError::Timeout) => {}
                    // The handle was dropped: the project closed.
                    Err(mpsc::RecvTimeoutError::Disconnected) => break,
                }
                if let Some(batch) = debouncer.take_due(Instant::now()) {
                    on_change(batch);
                }
            }
        })
        .context(|| "Couldn't start the file watcher thread".into())?;

    Ok(WatchHandle { _watcher: watcher })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_paths_and_drops_ignored_ones() {
        let root = PathBuf::from("/home/me/project");
        let roots = vec![root.clone(), PathBuf::from("/real/home/me/project")];
        let map = |p: &str| map_path(&roots, Path::new(p));
        assert_eq!(
            map("/home/me/project/centities/tower/root.lua").as_deref(),
            Some("centities/tower/root.lua")
        );
        assert_eq!(
            map("/real/home/me/project/netherforge.json").as_deref(),
            Some("netherforge.json")
        );
        assert_eq!(map("/home/me/project"), None);
        assert_eq!(map("/home/me/other/file"), None);
        assert_eq!(map("/home/me/project/.git/index"), None);
        assert_eq!(
            map("/home/me/project/.netherforge/server/logs/latest.log"),
            None
        );
        assert_eq!(map("/home/me/project/.netherforge"), None);
        assert_eq!(map("/home/me/project/a/.root.lua.nftmp-0a1b2c"), None);
        assert_eq!(
            map("/home/me/project/.gitignore").as_deref(),
            Some(".gitignore")
        );
    }

    #[test]
    fn debounces_until_quiet() {
        let t0 = Instant::now();
        let ms = Duration::from_millis;
        let mut d = Debouncer::new(ms(100), ms(1000));
        assert_eq!(d.deadline(), None);
        assert_eq!(d.take_due(t0), None);

        d.push(["b".to_string(), "a".to_string()], t0);
        d.push(["a".to_string()], t0 + ms(50));
        assert_eq!(d.deadline(), Some(t0 + ms(150)));
        assert_eq!(d.take_due(t0 + ms(149)), None);
        assert_eq!(
            d.take_due(t0 + ms(150)),
            Some(Batch {
                paths: vec!["a".into(), "b".into()],
                rescan: false
            })
        );
        assert_eq!(d.deadline(), None);
        assert_eq!(d.take_due(t0 + ms(400)), None);
    }

    #[test]
    fn a_constant_stream_still_flushes_at_max_wait() {
        let t0 = Instant::now();
        let ms = Duration::from_millis;
        let mut d = Debouncer::new(ms(100), ms(1000));
        for i in 0..30 {
            d.push([format!("f{i}")], t0 + ms(i * 50));
        }
        assert_eq!(d.deadline(), Some(t0 + ms(1000)));
        assert_eq!(d.take_due(t0 + ms(1000)).unwrap().paths.len(), 30);
    }

    #[test]
    fn ignored_paths_alone_never_schedule_a_batch() {
        let mut d = Debouncer::new(QUIET, MAX_WAIT);
        d.push(Vec::<String>::new(), Instant::now());
        assert_eq!(d.deadline(), None);
    }

    #[test]
    fn a_rescan_is_a_batch_of_its_own() {
        let t0 = Instant::now();
        let mut d = Debouncer::new(QUIET, MAX_WAIT);
        d.push_rescan(t0);
        assert_eq!(
            d.take_due(t0 + QUIET),
            Some(Batch {
                paths: vec![],
                rescan: true
            })
        );
        assert_eq!(d.deadline(), None);
    }

    fn collect_until(
        rx: &mpsc::Receiver<Batch>,
        done: impl Fn(&BTreeSet<String>, bool) -> bool,
    ) -> (BTreeSet<String>, bool) {
        let mut seen = BTreeSet::new();
        let mut rescan = false;
        let deadline = Instant::now() + Duration::from_secs(5);
        while !done(&seen, rescan) && Instant::now() < deadline {
            if let Ok(batch) = rx.recv_timeout(Duration::from_millis(200)) {
                seen.extend(batch.paths);
                rescan |= batch.rescan;
            }
        }
        (seen, rescan)
    }

    #[test]
    fn real_watcher_reports_project_paths() {
        let tmp = tempfile::tempdir().unwrap();
        let root = dunce::canonicalize(tmp.path()).unwrap();
        std::fs::create_dir_all(root.join("modules/a")).unwrap();
        std::fs::create_dir_all(root.join(".netherforge/server")).unwrap();
        let (tx, rx) = mpsc::channel();
        let _handle = watch(&root, move |batch| {
            let _ = tx.send(batch);
        })
        .unwrap();
        std::thread::sleep(Duration::from_millis(200));
        crate::fs::atomic::write_atomic(&root.join("modules/a/init.lua"), b"x").unwrap();
        std::fs::write(root.join("netherforge.json"), "{}").unwrap();
        std::fs::write(root.join(".netherforge/server/latest.log"), "x").unwrap();
        std::fs::create_dir_all(root.join(".git")).unwrap();
        std::fs::write(root.join(".git/index"), "x").unwrap();

        let (seen, _) = collect_until(&rx, |seen, _| {
            seen.contains("modules/a/init.lua") && seen.contains("netherforge.json")
        });
        assert!(seen.contains("modules/a/init.lua"), "saw {seen:?}");
        assert!(seen.contains("netherforge.json"), "saw {seen:?}");
        assert!(seen.iter().all(|p| !p.starts_with(".git")
            && !p.starts_with(".netherforge")
            && !p.contains(".nftmp-")));
    }

    #[test]
    fn a_new_top_level_folder_is_watched_and_rescanned() {
        let tmp = tempfile::tempdir().unwrap();
        let root = dunce::canonicalize(tmp.path()).unwrap();
        let (tx, rx) = mpsc::channel();
        let _handle = watch(&root, move |batch| {
            let _ = tx.send(batch);
        })
        .unwrap();
        std::thread::sleep(Duration::from_millis(200));
        // Written before the new folder's watch can exist: only a rescan covers it.
        crate::fs::atomic::write_atomic(&root.join("dialogs/a/dialog.json"), b"x").unwrap();
        let (_, rescan) = collect_until(&rx, |_, rescan| rescan);
        assert!(rescan);

        // Once watched, changes inside it arrive as paths.
        std::thread::sleep(Duration::from_millis(200));
        crate::fs::atomic::write_atomic(&root.join("dialogs/a/dialog.json"), b"y").unwrap();
        let (seen, _) = collect_until(&rx, |seen, _| seen.contains("dialogs/a/dialog.json"));
        assert!(seen.contains("dialogs/a/dialog.json"), "saw {seen:?}");
    }
}
