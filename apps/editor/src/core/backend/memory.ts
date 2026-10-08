/**
 * A `Backend` with no disk, no server and no Tauri: projects are maps of
 * path → text, and the dev server is a small state machine.
 *
 * Unit tests and Playwright run on this, and only they: the app always
 * runs the Rust backend. It behaves like the Rust side where the UI can tell
 * the difference: every write (the editor's own included) is echoed as a
 * debounced `onFilesChanged`, folders are implicit, paths are scoped to the
 * project, and failures reject with the same `BackendError` codes.
 * `contract.json` holds it to that: `contract.test.ts` runs it here and
 * `src-tauri/src/commands/contract.rs` on the real commands.
 *
 * The `test*` methods are the "outside world" for tests: a git checkout or an
 * agent editing a file, the plugin sending a message.
 */
import type {
  BridgeRequests,
  BotEvent,
  BotInfo,
  GameDataBundle,
  HandlerTime,
  LoadedWorld,
  PlayerPosition,
  ProfileSample,
  ProfileTick,
} from '@netherforge/format/types'
import { classify, readSetting, type ServerSettings, type SettingDef } from '@/core/format'
import { writeNbt } from '@netherforge/terrain-preview/nbt'
import { structureRoot } from '@/testing/nbtFixtures'
import { sha256Hex } from '@/core/sha256'
import type { DebugProtocol } from '@vscode/debugprotocol'
import { base64ToBytes, bytesToBase64 } from './base64'
import { FakeDebugAdapter, type FakeStop } from './memoryDebug'
import { BackendError } from './types'
import type {
  AppMenu,
  AppInfo,
  Backend,
  BridgeEventName,
  BridgeEvents,
  BridgeMethod,
  BridgeParamsArg,
  BridgeResult,
  CacheStatus,
  CaptureProgress,
  ErrorCode,
  EulaStatus,
  FileEntry,
  PackageFiles,
  FsChangedEvent,
  ImportProgress,
  LualsStarted,
  McpMessage,
  McpStatus,
  MinecraftInstall,
  PrepareProgress,
  ProjectInfo,
  RecentProject,
  ServerOutputEvent,
  ServerState,
  Settings,
  TestReport,
  TestStatus,
  Unlisten,
  UpdateInfo,
  UpdateProgress,
} from './types'

type Listener<T> = (value: T) => void

function fail(code: ErrorCode, message: string): never {
  throw new BackendError(code, message)
}

/** A file's contents: text, or bytes for binary files (PNGs). */
export type FileContents = string | Uint8Array

const textDecoder = new TextDecoder('utf-8', { fatal: true })

export { base64ToBytes, bytesToBase64 } from './base64'

const MIME: Record<string, string> = {
  png: 'image/png',
  json: 'application/json',
  lua: 'text/plain',
  mcmeta: 'application/json',
}

class Emitter<T> {
  private listeners = new Set<Listener<T>>()

  listen(listener: Listener<T>): Promise<Unlisten> {
    this.listeners.add(listener)
    return Promise.resolve(() => {
      this.listeners.delete(listener)
    })
  }

  emit(value: T) {
    for (const listener of [...this.listeners]) listener(value)
  }

  get listened(): boolean {
    return this.listeners.size > 0
  }
}

/** A bridge request as `bridgeLog` records it: its method, and its params when it takes any. */
export type BridgeCall = {
  [M in BridgeMethod]: { method: M; params: BridgeRequests[M]['params'] }
}[BridgeMethod]

/** The MCP token `mcpStatus` reports in memory. */
export const MEMORY_MCP_TOKEN = 'memory-mcp-token'

/** A JSON-RPC response, as an agent gets it (`testMcpCall`). */
export interface McpTestResponse {
  result?: unknown
  error?: { code: number; message: string }
}

export interface MemoryBackendOptions {
  /** Projects that exist, by absolute root → files (text, or bytes for binary files). */
  projects?: Record<string, Record<string, FileContents>>
  recent?: RecentProject[]
  appInfo?: Partial<AppInfo>
  installs?: MinecraftInstall[]
  /** Versions whose client assets are already imported. */
  importedClients?: string[]
  /**
   * Client asset files (path in the jar → URL, usually a data: URL) that an
   * "import" makes available, for every version. Without them, asset URLs
   * point nowhere and previews show placeholders.
   */
  clientAssets?: Record<string, string>
  /** Server game data by version. */
  gameData?: Record<string, GameDataBundle>
  /** A newer release the "updater" reports; none by default. */
  update?: UpdateInfo
  /** Default-font glyph advances an "import" provides, by version (see `Backend.glyphAdvances`). */
  glyphAdvances?: Record<string, Record<string, number>>
  eulaAccepted?: boolean
  /** Folders `pickFolder` returns, in order; afterwards it returns null. */
  pickFolderResults?: (string | null)[]
  /** Milliseconds the fake server takes per phase; 0 in unit tests. */
  serverDelayMs?: number
  /** How often the fake profiler sends a batch, while subscribed (the plugin: once a second). */
  profileEveryMs?: number
  /** How long change events are debounced, like the watcher's ~100 ms. */
  watchDebounceMs?: number
  /** The dev server's folder (paths relative to it): what `save_world` points into and a capture copies. */
  serverFiles?: Record<string, FileContents>
  /** Git repositories `packageFetchGit` fetches from, by URL. */
  gitRepos?: Record<string, MemoryGitRepo>
  /**
   * Project roots the user trusted on an earlier run. Left out, every project in `projects` is
   * (as most tests want a project that runs); the contract suite gives none, as the app starts.
   */
  trusted?: string[]
}

/**
 * A git repository, as `MemoryBackend` serves it: what each rev names
 * (`HEAD` for the default branch; a full commit names itself when it's in
 * [commits]) and each commit's files.
 */
export interface MemoryGitRepo {
  refs: Record<string, string>
  commits: Record<string, Record<string, FileContents>>
}

/** What the Rust side's `git::is_url` accepts: https, ssh, file, or user@host:path. */
const GIT_URL = /^(?:(?:https|ssh|file):\/\/\S+|[A-Za-z0-9._-]+@[A-Za-z0-9.-]+:[^/\s]\S*)$/
const GIT_REV = /^[A-Za-z0-9_][A-Za-z0-9_./+-]*$/
const COMMIT = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/

const DEFAULT_SETTINGS: Settings = {
  serverJvmArgs: [],
  serverMemoryMb: 2048,
  serverPort: 25565,
  mcpEnabled: true,
  mcpPort: 47615,
}

/** A file as a `data:` URL, for `<img>` and textures. */
function dataUrl(path: string, contents: FileContents): string {
  const bytes = typeof contents === 'string' ? new TextEncoder().encode(contents) : contents
  const mime = MIME[path.slice(path.lastIndexOf('.') + 1).toLowerCase()]
  return `data:${mime ?? 'application/octet-stream'};base64,${bytesToBase64(bytes)}`
}

export class MemoryBackend implements Backend {
  private projects: Map<string, Map<string, FileContents>>
  private recent: RecentProject[]
  private openRoot: string | null = null
  /** Project roots the user trusts (the Rust side's `trusted.json`). */
  private trustedRoots: Set<string>
  private settings: Settings = { ...DEFAULT_SETTINGS }
  private info: AppInfo
  private installs: MinecraftInstall[]
  private clients: Set<string>
  private assets: Record<string, string>
  private game: Map<string, GameDataBundle>
  private advances: Record<string, Record<string, number>>
  private eula: boolean
  private pickResults: (string | null)[]
  private serverDelay: number
  private profileEvery: number
  private profileTimer: ReturnType<typeof setInterval> | null = null
  private profileTick = 0
  private debounce: number
  private state: ServerState = {
    phase: 'stopped',
    minecraft: null,
    port: null,
    bridgeConnected: false,
    message: null,
  }
  private instances: {
    uuid: string
    centity: string
    world: string
    x: number
    y: number
    z: number
  }[] = []
  /** The dev server's folder. */
  private serverFiles: Map<string, FileContents>
  /** Players online on the fake server, where they stand. */
  private players: PlayerPosition[] = []
  /** Its loaded worlds, the main one first. */
  private worlds: LoadedWorld[] = [{ name: 'world', environment: 'normal', main: true }]
  private captureEvents = new Emitter<CaptureProgress>()
  private pendingChanges = new Set<string>()
  private gitRepos: Record<string, MemoryGitRepo>
  /** The package cache's checkouts, by commit. */
  private checkouts = new Map<string, Map<string, FileContents>>()
  /** Every `packageFetchGit` that reached a repository, for assertions: what was asked. */
  readonly gitFetchLog: { url: string; rev: string | null; commit: string | null }[] = []
  private flushTimer: ReturnType<typeof setTimeout> | null = null

  /** Every bridge request the UI sent, for assertions. */
  readonly bridgeLog: BridgeCall[] = []
  /** Whether the fake plugin says a reload needs the server restarted (what it learns only as it starts changed). */
  private reloadRestarts = false
  /** What the test runner answers, when a test sets it (`testTestReport`): there is no Lua here to run. */
  private testReport: TestReport | null = null
  /** Every console command the UI sent. */
  readonly commandLog: string[] = []
  /** The plugin's debug adapter, on the bridge's `dap` channel. */
  private debugAdapter = new FakeDebugAdapter((message) => this.testBridgeEvent('dap', message))

  /** The values the fake server's owner set for the project's settings (`set_setting`), by name. */
  private settingValues = new Map<string, unknown>()

  /** Bots online, with what they were sent. An action records itself; `chat` is heard back as a chat line. */
  private bots = new Map<string, { info: BotInfo; events: BotEvent[] }>()
  /** How many times the UI asked to open the Minecraft launcher. */
  launcherOpened = 0
  /** Versions the UI installed (it would have restarted after each). */
  readonly updatesInstalled: string[] = []
  private update: UpdateInfo | null
  /** The last `setAppMenu`, for tests (the in-app menu bar never sets it). */
  appMenu: AppMenu[] | null = null
  private menuListeners = new Set<(id: string) => void>()

  private fsChanged = new Emitter<FsChangedEvent>()
  private serverStateEvents = new Emitter<ServerState>()
  private serverOutput = new Emitter<ServerOutputEvent>()
  private prepare = new Emitter<PrepareProgress>()
  private bridge = new Emitter<{ event: BridgeEventName; params: unknown }>()
  private importEvents = new Emitter<ImportProgress>()
  private cacheEvents = new Emitter<CacheStatus>()
  private mcpEvents = new Emitter<McpMessage>()
  private mcpWaiting = new Map<number, (response: McpTestResponse) => void>()
  private mcpNext = 1

  constructor(options: MemoryBackendOptions = {}) {
    this.projects = new Map(
      Object.entries(options.projects ?? {}).map(([root, files]) => [
        root,
        new Map(Object.entries(files)),
      ]),
    )
    this.recent = options.recent ?? []
    this.info = {
      version: '0.0.0-memory',
      minecraftVersions: ['26.3'],
      os: 'linux',
      ...options.appInfo,
    }
    this.installs = options.installs ?? []
    this.clients = new Set(options.importedClients ?? [])
    this.assets = options.clientAssets ?? {}
    this.game = new Map(Object.entries(options.gameData ?? {}))
    this.advances = options.glyphAdvances ?? {}
    this.update = options.update ?? null
    this.eula = options.eulaAccepted ?? false
    this.pickResults = [...(options.pickFolderResults ?? [])]
    this.serverDelay = options.serverDelayMs ?? 0
    this.profileEvery = options.profileEveryMs ?? 1000
    this.debounce = options.watchDebounceMs ?? 0
    this.gitRepos = options.gitRepos ?? {}
    this.trustedRoots = new Set(options.trusted ?? Object.keys(options.projects ?? {}))
    this.serverFiles = new Map(Object.entries(options.serverFiles ?? {}))
  }

  // ---- app -----------------------------------------------------------------

  async appInfo(): Promise<AppInfo> {
    return { ...this.info }
  }

  async getSettings(): Promise<Settings> {
    return { ...this.settings, serverJvmArgs: [...this.settings.serverJvmArgs] }
  }

  async setSettings(settings: Settings): Promise<void> {
    validateSettings(settings)
    this.settings = { ...settings, serverJvmArgs: [...settings.serverJvmArgs] }
  }

  async openExternal(): Promise<void> {}
  async windowAction(): Promise<void> {}
  async setAppMenu(menus: AppMenu[]): Promise<void> {
    this.appMenu = structuredClone(menus)
  }
  onMenuAction(listener: (id: string) => void): Unlisten {
    this.menuListeners.add(listener)
    return () => this.menuListeners.delete(listener)
  }
  /** A browser has no webview zoom to set: CSS zoom stands in, so Playwright sees the UI scale. */
  async setZoom(factor: number): Promise<void> {
    if (typeof document !== 'undefined') document.documentElement.style.zoom = String(factor)
  }

  async checkForUpdate(): Promise<UpdateInfo | null> {
    return this.update ? { ...this.update } : null
  }

  async installUpdate(onProgress?: (progress: UpdateProgress) => void): Promise<void> {
    if (!this.update) throw new Error('There is no update to install')
    onProgress?.({ downloaded: 1, total: 1 })
    this.updatesInstalled.push(this.update.version)
  }

  // ---- projects ------------------------------------------------------------

  async recentProjects(): Promise<RecentProject[]> {
    return [...this.recent]
  }

  async pickFolder(): Promise<string | null> {
    return this.pickResults.shift() ?? null
  }

  async pickJar(): Promise<string | null> {
    return null
  }

  async openProject(root: string): Promise<ProjectInfo> {
    const files = this.projects.get(root)
    if (!files || !files.has('netherforge.json')) {
      fail('invalid', `${root} isn't a NetherForge project (it has no netherforge.json)`)
    }
    this.openRoot = root
    this.state = { ...this.state, minecraft: this.projectMinecraft() }
    const manifest = files.get('netherforge.json')
    const info = {
      root,
      name: projectName(root, typeof manifest === 'string' ? manifest : undefined),
      trusted: this.trustedRoots.has(root),
    }
    this.recent = [
      { root, name: info.name, openedAt: new Date().toISOString() },
      ...this.recent.filter((it) => it.root !== root),
    ]
    return info
  }

  async createProject(root: string, files: Record<string, FileContents>): Promise<ProjectInfo> {
    if (!('netherforge.json' in files)) fail('invalid', 'A new project needs a netherforge.json')
    for (const path of Object.keys(files)) checkPath(path)
    const existing = this.projects.get(root)
    if (existing && existing.size > 0)
      fail('alreadyExists', `${root} isn't empty; pick an empty or new folder`)
    this.projects.set(root, new Map(Object.entries(files)))
    // Made here by the user: theirs to run.
    this.trustedRoots.add(root)
    return this.openProject(root)
  }

  async trustProject(trusted: boolean): Promise<ProjectInfo> {
    const root = this.openRoot
    if (!root) fail('noProject', 'No project is open')
    if (trusted) this.trustedRoots.add(root)
    else {
      this.trustedRoots.delete(root)
      await this.stopServer()
    }
    const manifest = this.projects.get(root)?.get('netherforge.json')
    return {
      root,
      name: projectName(root, typeof manifest === 'string' ? manifest : undefined),
      trusted,
    }
  }

  /** The open project's root, refusing as the Rust side's `trusted_root` does when it isn't trusted. */
  private trustedRoot(): string {
    const root = this.openRoot
    if (!root) fail('noProject', 'No project is open')
    if (!this.trustedRoots.has(root)) {
      fail(
        'untrusted',
        `"${root}" isn't trusted yet: its dev server, Lua language features, git packages and agents' tools stay off until you trust it`,
      )
    }
    return root
  }

  async closeProject(): Promise<void> {
    await this.stopServer()
    this.openRoot = null
    this.state = { ...this.state, minecraft: null }
  }

  // ---- files ---------------------------------------------------------------

  private files(): Map<string, FileContents> {
    if (!this.openRoot) fail('noProject', 'No project is open')
    return this.projects.get(this.openRoot)!
  }

  async listFiles(): Promise<FileEntry[]> {
    return (
      [...this.files().entries()]
        .filter(([path]) => !path.startsWith('.netherforge/') && !path.startsWith('.git/'))
        .map(([path, contents]) => ({ path, size: contents.length, modified: 0 }))
        // Byte order, as Rust sorts: `B.txt` before `_x.txt` before `a.txt`.
        .sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0))
    )
  }

  async readText(path: string): Promise<string> {
    const files = this.files()
    checkPath(path)
    const contents = files.get(path)
    if (contents === undefined) fail('notFound', `Couldn't read ${path}: no such file`)
    if (typeof contents === 'string') return contents
    try {
      return textDecoder.decode(contents)
    } catch {
      fail('invalid', `${path} isn't text`)
    }
  }

  async writeText(path: string, text: string): Promise<void> {
    checkWritable(path)
    this.files().set(path, text)
    this.changed([path])
  }

  async writeBytes(path: string, bytes: Uint8Array): Promise<void> {
    checkWritable(path)
    this.files().set(path, bytes.slice())
    this.changed([path])
  }

  projectFileUrl(path: string): string {
    const contents =
      this.openRoot && isServed(path, true)
        ? this.projects.get(this.openRoot)?.get(path)
        : undefined
    if (contents === undefined) return `memory-project://${path}`
    return dataUrl(path, contents)
  }

  packageFileUrl(location: string, path: string): string {
    let contents: FileContents | undefined
    try {
      contents = isServed(path, false) ? this.packageAt(location).files.get(path) : undefined
    } catch {
      contents = undefined
    }
    if (contents === undefined) return `memory-package://${location}/${path}`
    return dataUrl(path, contents)
  }

  async deletePath(path: string): Promise<void> {
    checkWritable(path)
    const files = this.files()
    const removed = [...files.keys()].filter((it) => it === path || it.startsWith(`${path}/`))
    if (removed.length === 0) fail('notFound', `Couldn't find ${path}`)
    for (const it of removed) files.delete(it)
    this.changed(removed)
  }

  async renamePath(from: string, to: string): Promise<void> {
    checkWritable(from)
    checkWritable(to)
    const files = this.files()
    const moved = [...files.keys()].filter((it) => it === from || it.startsWith(`${from}/`))
    if (moved.length === 0) fail('notFound', `Couldn't find ${from}`)
    if ([...files.keys()].some((it) => it === to || it.startsWith(`${to}/`))) {
      fail('alreadyExists', `${to} already exists`)
    }
    const changed: string[] = []
    for (const old of moved) {
      const next = to + old.slice(from.length)
      files.set(next, files.get(old)!)
      files.delete(old)
      changed.push(old, next)
    }
    this.changed(changed)
  }

  // ---- packages --------------------------------------------------------------

  /**
   * As the Rust side's `package::open`: a relative location (`..` allowed)
   * naming a project beside the open one, or `git:<commit>`, a checkout in
   * the package cache.
   */
  private packageAt(location: string): { root: string; files: Map<string, FileContents> } {
    this.files()
    if (location.startsWith('git:')) {
      const commit = location.slice('git:'.length)
      if (!COMMIT.test(commit)) {
        fail('invalidPath', `"${location}" isn't a git package's location (git:<commit>)`)
      }
      const files = this.checkouts.get(commit)
      if (!files?.has('netherforge.json')) {
        fail('notFound', `${location} hasn't been fetched into the package cache`)
      }
      return { root: `/packages/git/checkouts/${commit}`, files }
    }
    if (
      !location ||
      location.startsWith('/') ||
      /[\\:\0]/.test(location) ||
      location.split('/').some((it) => it === '')
    ) {
      fail(
        'invalidPath',
        `"${location}" isn't a package folder relative to the project (like ../economy)`,
      )
    }
    const parts = this.openRoot!.split('/').filter(Boolean)
    for (const part of location.split('/')) {
      if (part === '..') parts.pop()
      else if (part !== '.') parts.push(part)
    }
    const root = `/${parts.join('/')}`
    const files = this.projects.get(root) ?? this.folderOfOpenProject(root)
    if (!files) fail('notFound', `There's no folder at ${location}`)
    if (!files.has('netherforge.json')) {
      fail('notFound', `${location} isn't a project (it has no netherforge.json)`)
    }
    return { root, files }
  }

  /**
   * A folder inside the open project (a package kept in it, like a template at
   * `templates/<namespace>`), its files by path inside it; undefined when
   * there's none there.
   */
  private folderOfOpenProject(root: string): Map<string, FileContents> | undefined {
    const prefix = `${this.openRoot}/`
    if (!root.startsWith(prefix)) return undefined
    const folder = `${root.slice(prefix.length)}/`
    const inside = [...(this.projects.get(this.openRoot!)?.entries() ?? [])]
      .filter(([path]) => path.startsWith(folder))
      .map(([path, contents]) => [path.slice(folder.length), contents] as const)
    return inside.length > 0 ? new Map(inside) : undefined
  }

  async packageFiles(location: string): Promise<PackageFiles> {
    const { root, files } = this.packageAt(location)
    const listed = [...files.entries()]
      .filter(([path]) => !path.startsWith('.netherforge/') && !path.split('/').includes('.git'))
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    return {
      root,
      files: await Promise.all(
        listed.map(async ([path, contents]) => ({ path, sha256: await sha256Hex(contents) })),
      ),
    }
  }

  async packageReadText(location: string, path: string): Promise<string> {
    checkPath(path)
    const contents = this.packageAt(location).files.get(path)
    if (contents === undefined) fail('notFound', `Couldn't read ${path}: no such file`)
    if (typeof contents === 'string') return contents
    try {
      return textDecoder.decode(contents)
    } catch {
      fail('invalid', `${path} isn't text`)
    }
  }

  async packageCopy(location: string, from: string, to: string): Promise<void> {
    checkPath(from)
    checkWritable(to)
    const source = this.packageAt(location).files
    const copied = [...source.keys()].filter((it) => it === from || it.startsWith(`${from}/`))
    if (copied.length === 0) fail('notFound', `Couldn't find ${from}`)
    const files = this.files()
    if ([...files.keys()].some((it) => it === to || it.startsWith(`${to}/`))) {
      fail('alreadyExists', `${to} already exists`)
    }
    const changed: string[] = []
    for (const path of copied) {
      const contents = source.get(path)!
      const next = to + path.slice(from.length)
      files.set(next, typeof contents === 'string' ? contents : contents.slice())
      changed.push(next)
    }
    this.changed(changed)
  }

  async packageFetchGit(url: string, rev: string | null, commit: string | null): Promise<string> {
    if (url.startsWith('-') || !GIT_URL.test(url)) {
      fail('invalid', `"${url}" isn't a repository NetherForge fetches from`)
    }
    if (rev !== null && (!GIT_REV.test(rev) || /\.\.|\/\/|\/$|\.$|\.lock$|(^|\/)\./.test(rev))) {
      fail('invalid', `"${rev}" isn't the name of a branch, a tag or a full commit`)
    }
    if (commit !== null) {
      if (!COMMIT.test(commit)) fail('invalid', `"${commit}" isn't a commit`)
      if (this.checkouts.has(commit)) return commit
    }
    // Reaching the repository the project names needs the project trusted.
    this.trustedRoot()
    this.gitFetchLog.push({ url, rev, commit })
    const repo = this.gitRepos[url]
    if (!repo) fail('network', `fatal: '${url}' does not appear to be a git repository`)
    const wanted =
      commit ?? repo.refs[rev ?? 'HEAD'] ?? (rev !== null && repo.commits[rev] ? rev : undefined)
    if (wanted === undefined) {
      fail(
        'network',
        `fatal: couldn't find remote ref ${rev ?? 'HEAD'} (is "${rev}" a branch, a tag or a full commit there?)`,
      )
    }
    const files = repo.commits[wanted]
    if (!files) fail('notFound', `${url} has no commit ${wanted} (was it force-pushed away?)`)
    this.checkouts.set(wanted, new Map(Object.entries(files)))
    return wanted
  }

  onFilesChanged(listener: (event: FsChangedEvent) => void): Promise<Unlisten> {
    return this.fsChanged.listen(listener)
  }

  private changed(paths: string[]) {
    for (const path of paths) {
      if (!path.startsWith('.netherforge/') && !path.startsWith('.git/'))
        this.pendingChanges.add(path)
    }
    if (this.flushTimer !== null || this.pendingChanges.size === 0) return
    this.flushTimer = setTimeout(() => {
      this.flushTimer = null
      const paths = [...this.pendingChanges].sort()
      this.pendingChanges.clear()
      this.fsChanged.emit({ paths, rescan: false })
    }, this.debounce)
  }

  // ---- server --------------------------------------------------------------

  async serverState(): Promise<ServerState> {
    return { ...this.state }
  }

  async eulaStatus(): Promise<EulaStatus> {
    return { accepted: this.eula, url: 'https://aka.ms/MinecraftEULA' }
  }

  async acceptEula(): Promise<void> {
    this.eula = true
  }

  async startServer(): Promise<void> {
    this.trustedRoot()
    if (!this.eula) fail('eulaRequired', 'Accept the Minecraft EULA before starting the dev server')
    if (this.state.phase !== 'stopped' && this.state.phase !== 'crashed')
      fail('busy', 'The dev server is already running')
    const minecraft = this.projectMinecraft()
    this.setState({ phase: 'preparing', minecraft, message: 'Downloading Paper' })
    const steps: [PrepareProgress['step'], string][] = [
      ['java', 'Java runtime'],
      ['paper', `Paper ${minecraft ?? ''}`.trim()],
      ['plugin', 'NetherForge plugin'],
    ]
    void (async () => {
      for (const [step, label] of steps) {
        for (let done = 0; done <= 4; done += 1) {
          this.prepare.emit({ step, label, done, total: 4 })
          await this.wait()
        }
      }
      this.setState({ phase: 'starting', message: null })
      this.output('stdout', `[Server] Starting minecraft server version ${minecraft ?? '?'}`)
      await this.wait()
      this.setState({ phase: 'running', port: this.settings.serverPort })
      this.output('stdout', '[Server] Done! For help, type "help"')
      await this.wait()
      this.setState({ bridgeConnected: true })
      this.testBridgeEvent('console', {
        items: [{ type: 'log', level: 'info', message: 'NetherForge loaded the project' }],
      })
    })()
  }

  async stopServer(): Promise<void> {
    if (this.state.phase === 'stopped') return
    this.profiling(false)
    this.setState({ phase: 'stopping', bridgeConnected: false })
    await this.wait()
    this.output('stdout', '[Server] Stopping server')
    this.instances = []
    this.setState({ phase: 'stopped', port: null, message: null })
  }

  async runTests(filter?: string): Promise<TestReport> {
    this.trustedRoot()
    const report = this.testReport
    if (!report) fail('unavailable', 'This editor has no test runner')
    if (!filter) return report
    const wanted = report.results.filter((it) =>
      `${it.file}: ${it.name}`.toLowerCase().includes(filter.toLowerCase()),
    )
    const count = (status: TestStatus) => wanted.filter((it) => it.status === status).length
    return {
      ...report,
      results: wanted,
      passed: count('passed'),
      failed: count('failed'),
      errored: count('errored'),
    }
  }

  async serverCommand(line: string): Promise<void> {
    if (this.state.phase !== 'starting' && this.state.phase !== 'running')
      fail('notConnected', "The dev server isn't running")
    this.commandLog.push(line)
    this.output('stdout', `> ${line}`)
  }

  bridgeRequest<M extends BridgeMethod>(
    method: M,
    ...params: BridgeParamsArg<M>
  ): Promise<BridgeResult<M>> {
    // The switch below can't narrow a generic, so the answer is typed here.
    return this.answer({ method, params: params[0] } as BridgeCall) as Promise<BridgeResult<M>>
  }

  async dapSend(message: DebugProtocol.ProtocolMessage): Promise<void> {
    if (!this.state.bridgeConnected)
      fail('notConnected', "The NetherForge plugin isn't connected to the editor yet")
    this.debugAdapter.receive(message)
  }

  /** Every DAP request the UI sent the plugin's debug adapter, for assertions. */
  get dapLog(): DebugProtocol.Request[] {
    return this.debugAdapter.log
  }

  /** The breakpoint lines the debug adapter has, by file. */
  testBreakpoints(): Record<string, number[]> {
    return Object.fromEntries(this.debugAdapter.breakpoints)
  }

  /** A breakpoint (or a step, a pause, an error) holds the fake server (see `FakeDebugAdapter`). */
  testDebugStop(options: FakeStop = {}) {
    this.debugAdapter.stop(options)
  }

  /** What the plugin answers; a method it doesn't have is `unknownMethod`, as the real one's JSON-RPC error is. */
  private async answer(request: BridgeCall): Promise<unknown> {
    this.bridgeLog.push(
      request.params === undefined ? ({ method: request.method } as BridgeCall) : request,
    )
    if (!this.state.bridgeConnected)
      fail('notConnected', "The NetherForge plugin isn't connected to the editor yet")
    // As the plugin: a breakpoint holds the main thread, so only the bridge thread's requests answer.
    if (
      this.debugAdapter.paused &&
      request.method !== 'ping' &&
      request.method !== 'profiler_subscribe'
    )
      fail('plugin', 'the dev server is paused at a breakpoint; continue it first')
    switch (request.method) {
      case 'reload': {
        // Each path's resource, named as the plugin names it; a project file is the whole package.
        const pkg = this.manifestField('namespace') ?? 'project'
        return {
          resources: request.params.paths.map((path) => {
            const found = classify(path)
            return found?.kind && found.id
              ? { package: pkg, kind: found.kind, id: found.id, ok: true, reattached: 0 }
              : { package: pkg, ok: true, reattached: 0 }
          }),
          ...(this.reloadRestarts ? { restart: true } : {}),
        }
      }
      case 'spawn': {
        const instance = {
          uuid: `00000000-0000-4000-8000-${String(this.instances.length + 1).padStart(12, '0')}`,
          centity: request.params.centity,
          world: 'world',
          x: 0,
          y: 64,
          z: 0,
        }
        this.instances.push(instance)
        return instance
      }
      case 'instances':
        return [...this.instances]
      case 'export_game_data':
        return this.game.get(this.state.minecraft ?? '') ?? null
      case 'command':
        this.commandLog.push(request.params.line)
        return null
      case 'play_particle_effect':
      case 'stop_particle_effects':
        // Nothing to draw: the request itself is what tests look at (`bridgeLog`).
        return null
      case 'bots/join': {
        if (this.bots.has(request.params.name))
          fail('plugin', `${request.params.name} is already online`)
        const info: BotInfo = {
          name: request.params.name,
          uuid: `00000000-0000-3000-8000-${String(this.bots.size + 1).padStart(12, '0')}`,
          world: request.params.at?.world ?? 'world',
          x: request.params.at?.x ?? 0,
          y: request.params.at?.y ?? 64,
          z: request.params.at?.z ?? 0,
          events: 1,
        }
        this.bots.set(request.params.name, {
          info,
          events: [{ type: 'chat', seq: 0, text: `${request.params.name} joined the game` }],
        })
        return info
      }
      case 'bots/list':
        return [...this.bots.values()].map((it) => ({ ...it.info, events: it.events.length }))
      case 'bots/leave':
        this.bot(request.params.name)
        this.bots.delete(request.params.name)
        return null
      case 'bots/act': {
        const bot = this.bot(request.params.name)
        const since = bot.events.length
        if (request.params.action.type === 'chat') {
          bot.events.push({
            type: 'chat',
            seq: since,
            text: `<${request.params.name}> ${request.params.action.message}`,
          })
        }
        const { world, x, y, z } = bot.info
        return { world, x, y, z, since }
      }
      case 'bots/state': {
        const { info, events } = this.bot(request.params.name)
        return {
          ...info,
          yaw: 0,
          pitch: 0,
          onGround: true,
          health: 20,
          food: 20,
          gameMode: 'survival',
          dead: false,
          sneaking: false,
          sprinting: false,
          selectedSlot: 0,
          inventory: [],
          events: events.length,
        }
      }
      case 'bots/events': {
        const { events } = this.bot(request.params.name)
        return {
          events: events.filter((it) => it.seq >= (request.params.since ?? 0)),
          next: events.length,
        }
      }
      case 'player_position': {
        const found = request.params.player
          ? this.players.find((it) => it.player === request.params.player)
          : this.players[0]
        if (!found) {
          fail(
            'plugin',
            request.params.player
              ? `no player "${request.params.player}" online`
              : 'nobody is online',
          )
        }
        return { ...found }
      }
      case 'worlds':
        return this.worlds.map((it) => ({ ...it }))
      case 'save_structure': {
        this.world(request.params.world)
        const lo = (axis: 'x' | 'y' | 'z') =>
          Math.min(request.params.from[axis], request.params.to[axis])
        const side = (axis: 'x' | 'y' | 'z') =>
          Math.abs(request.params.from[axis] - request.params.to[axis]) + 1
        const size: [number, number, number] = [side('x'), side('y'), side('z')]
        // The fake world: stone bricks at y 64 and below, air above.
        const blocks = []
        for (let x = 0; x < size[0]; x += 1)
          for (let y = 0; y < size[1]; y += 1)
            for (let z = 0; z < size[2]; z += 1)
              blocks.push({
                pos: [x, y, z] as [number, number, number],
                state: lo('y') + y <= 64 ? 0 : 1,
              })
        const nbt = await writeNbt(
          structureRoot({
            size,
            palette: [{ Name: 'minecraft:stone_bricks' }, { Name: 'minecraft:air' }],
            blocks,
            entities: request.params.entities ? 1 : 0,
          }),
        )
        return { nbt: bytesToBase64(nbt), size: { x: size[0], y: size[1], z: size[2] } }
      }
      case 'save_world': {
        const world = this.world(request.params.world)
        const dimension = world.main ? 'overworld' : world.name
        return {
          level: 'world/level.dat',
          dimension: `world/dimensions/minecraft/${dimension}`,
          main: world.main,
          spawn: { x: 0, y: 64, z: 0, yaw: 0, pitch: 0 },
        }
      }
      case 'settings':
        return this.serverSettings()
      case 'set_setting': {
        const { namespace, setting, value } = request.params
        const settings = this.serverSettings()
        const definition = settings.packages.find((it) => it.namespace === namespace)?.settings[
          setting
        ]?.definition
        if (!definition) fail('plugin', `there's no setting "${setting}" in ${namespace}`)
        if (value === undefined || value === null) {
          this.settingValues.delete(setting)
        } else {
          const read = readSetting(definition, JSON.stringify(value), false)
          if (read.error !== undefined) fail('plugin', `${setting}: ${read.error}`)
          this.settingValues.set(setting, read.value)
        }
        const now = this.serverSettings()
        this.testBridgeEvent('settings_changed', now)
        return now
      }
      case 'ping':
        return null
      case 'profiler_subscribe':
        this.profiling(request.params.on)
        return null
      default:
        fail('unknownMethod', `Unknown method "${(request as { method: string }).method}"`)
    }
  }

  /**
   * The fake profiler: while subscribed, a batch every `profileEveryMs` of
   * twenty ticks, timing a handler for each `on("<event>", …)` the project's
   * scripts register, at its line, with numbers that follow from where it is
   * (so tests can count on them).
   */
  private profiling(on: boolean) {
    if (this.profileTimer !== null) clearInterval(this.profileTimer)
    this.profileTimer = null
    if (!on) return
    this.profileTimer = setInterval(() => {
      if (!this.state.bridgeConnected) return this.profiling(false)
      this.testBridgeEvent('profiler', { items: [this.profileSample()] })
    }, this.profileEvery)
  }

  private profileSample(): ProfileSample {
    const handlers: HandlerTime[] = []
    for (const [path, contents] of this.files()) {
      // The project's own scripts (not .netherforge/'s docs and stubs).
      if (typeof contents !== 'string' || !path.endsWith('.lua') || path.startsWith('.')) continue
      const [folder, id] = path.split('/')
      const script =
        folder === 'modules'
          ? `module ${id}`
          : `${folder?.replace(/ies$/, 'y').replace(/s$/, '')} ${id}`
      contents.split('\n').forEach((text, i) => {
        const event = /\bon\(\s*"([a-z_:]+)"/.exec(text)?.[1]
        if (!event) return
        const line = i + 1
        const each = 20_000 + 7_000 * ((handlers.length * 3 + line) % 11)
        handlers.push({
          script,
          kind: event,
          source: { file: path, line },
          calls: 20,
          nanos: each * 20,
          max: each * 2,
        })
      })
    }
    const first = this.profileTick + 1
    this.profileTick += 20
    const scripts = handlers.reduce((sum, it) => sum + it.nanos, 0) / 20
    const ticks: ProfileTick[] = Array.from({ length: 20 }, (_, i) => {
      // A spike now and then, so the timeline has something to show.
      const world = 150_000 + ((first + i) % 37 === 0 ? 2_000_000 : 0)
      const phases = {
        timers: 20_000,
        async: 5_000,
        events: scripts,
        world,
        effects: 30_000,
        upkeep: 10_000,
        accounts: 15_000,
        save: 5_000,
      }
      const nanos = Object.values(phases).reduce((sum, it) => sum + it, 0)
      return { tick: first + i, nanos, phases, scripts }
    })
    const scopes = [...new Set(handlers.map((it) => it.script))].map((scope) => {
      const own = handlers.filter((it) => it.script === scope)
      const nanos = own.reduce((sum, it) => sum + it.nanos, 0)
      return { scope, nanos, calls: own.reduce((sum, it) => sum + it.calls, 0), max: nanos / 10 }
    })
    return { ticks, scopes, handlers }
  }

  private world(name: string): LoadedWorld {
    const world = this.worlds.find((it) => it.name === name)
    if (!world) fail('plugin', `no world "${name}" is loaded`)
    return world
  }

  async captureMap(
    source: { level: string; dimension: string },
    id: string,
    replace: boolean,
  ): Promise<void> {
    const files = this.files()
    // As the Rust side: a path first, then one segment that isn't hidden.
    checkPath(id)
    if (id.includes('/') || id.startsWith('.')) fail('invalid', `"${id}" can't name a map`)
    const folder = `maps/${id}`
    const existing = [...files.keys()].filter((it) => it.startsWith(`${folder}/`))
    if (existing.length > 0 && !replace) fail('alreadyExists', `${folder} already exists`)
    const level = this.serverFiles.get(source.level)
    if (level === undefined || !source.level.endsWith('/level.dat')) {
      fail('notFound', `${source.level} isn't a world's level.dat in the server's folder`)
    }
    const prefix = `${source.dimension}/`
    const copied: [string, FileContents][] = [[`${folder}/level.dat`, level]]
    for (const [path, contents] of this.serverFiles) {
      if (!path.startsWith(prefix)) continue
      const relative = path.slice(prefix.length)
      if (!isMapFile(relative)) continue
      copied.push([`${folder}/dimensions/minecraft/overworld/${relative}`, contents])
    }
    if (copied.length === 1)
      fail('notFound', `${source.dimension} isn't a world folder in the server's folder`)
    const size = (contents: FileContents) =>
      typeof contents === 'string' ? contents.length : contents.length
    const total = copied.reduce((sum, [, contents]) => sum + size(contents), 0)
    for (const path of existing) files.delete(path)
    let done = 0
    for (const [path, contents] of copied) {
      files.set(path, contents)
      done += size(contents)
      this.captureEvents.emit({ done, total })
    }
    this.changed([...existing, ...copied.map(([path]) => path)])
  }

  onCaptureProgress(listener: (event: CaptureProgress) => void): Promise<Unlisten> {
    return this.captureEvents.listen(listener)
  }

  private bot(name: string) {
    const bot = this.bots.get(name)
    if (!bot) fail('plugin', `no bot named "${name}"`)
    return bot
  }

  onServerState(listener: (state: ServerState) => void): Promise<Unlisten> {
    return this.serverStateEvents.listen(listener)
  }

  onServerOutput(listener: (event: ServerOutputEvent) => void): Promise<Unlisten> {
    return this.serverOutput.listen(listener)
  }

  onPrepareProgress(listener: (event: PrepareProgress) => void): Promise<Unlisten> {
    return this.prepare.listen(listener)
  }

  onBridgeEvent<E extends BridgeEventName>(
    event: E,
    listener: (params: BridgeEvents[E]) => void,
  ): Promise<Unlisten> {
    return this.bridge.listen((it) => {
      if (it.event === event) listener(it.params as BridgeEvents[E])
    })
  }

  // ---- Minecraft -------------------------------------------------------------

  async findInstalls(): Promise<MinecraftInstall[]> {
    return this.installs.map((it) => ({ ...it, versions: [...it.versions] }))
  }

  async cacheStatus(version: string): Promise<CacheStatus> {
    checkVersion(version)
    return { version, client: this.clients.has(version), server: this.game.has(version) }
  }

  async importClient(version: string): Promise<void> {
    checkVersion(version)
    const total = 5
    for (let done = 0; done <= total; done += 1) {
      this.importEvents.emit({ version, done, total })
      await this.wait()
    }
    this.clients.add(version)
    this.cacheEvents.emit(await this.cacheStatus(version))
  }

  onImportProgress(listener: (event: ImportProgress) => void): Promise<Unlisten> {
    return this.importEvents.listen(listener)
  }

  onCacheChanged(listener: (status: CacheStatus) => void): Promise<Unlisten> {
    return this.cacheEvents.listen(listener)
  }

  /** Nothing listens in memory; the status is what the real backend would report. */
  async mcpStatus(): Promise<McpStatus> {
    const { mcpEnabled: enabled, mcpPort: port } = this.settings
    return {
      enabled,
      port,
      url: enabled ? `http://127.0.0.1:${port}/mcp` : null,
      error: null,
      token: MEMORY_MCP_TOKEN,
    }
  }

  /** As the Rust pipe: a response completes the request it answers; anything else is dropped. */
  async mcpSend(message: unknown): Promise<void> {
    const response = message as { id?: unknown; method?: unknown } & McpTestResponse
    if (response.method !== undefined || typeof response.id !== 'number') return
    const waiter = this.mcpWaiting.get(response.id)
    this.mcpWaiting.delete(response.id)
    waiter?.(response)
  }

  onMcpMessage(listener: (message: McpMessage) => void): Promise<Unlisten> {
    return this.mcpEvents.listen(listener)
  }

  /** There's no language server in memory: Lua gets highlighting only, as in an editor without LuaLS. */
  async lualsStart(): Promise<LualsStarted> {
    this.trustedRoot()
    fail('unavailable', 'lua-language-server only runs in the desktop editor')
  }

  async lualsSend(): Promise<void> {
    fail('unavailable', 'lua-language-server has stopped')
  }

  async lualsStop(): Promise<void> {}

  async onLualsMessage(): Promise<Unlisten> {
    return () => {}
  }

  async onLualsExit(): Promise<Unlisten> {
    return () => {}
  }

  /** Whether the UI's MCP server has connected (listens for `mcp://message`). */
  get testMcpConnected(): boolean {
    return this.mcpEvents.listened
  }

  /** Plays the Rust pipe: hands one JSON-RPC message to the UI's MCP server. */
  testMcpMessage(message: unknown, protocolVersion: string | null = null): void {
    // The Rust pipe keeps agents out while the open project isn't trusted: a request gets
    // the refusal, nothing reaches the UI's tools.
    if (this.openRoot && !this.trustedRoots.has(this.openRoot)) {
      const request = message as { id?: number; method?: unknown }
      if (typeof request.method === 'string' && typeof request.id === 'number') {
        this.mcpWaiting.get(request.id)?.({
          error: { code: -32001, message: `"${this.openRoot}" isn't trusted yet` },
        })
        this.mcpWaiting.delete(request.id)
      }
      return
    }
    this.mcpEvents.emit({ message, protocolVersion })
  }

  /**
   * Plays a coding agent: sends a JSON-RPC request to the UI's MCP server and
   * resolves with its response.
   */
  testMcpCall(
    method: string,
    params: unknown = {},
    protocolVersion: string | null = null,
  ): Promise<McpTestResponse> {
    const id = this.mcpNext++
    const answer = new Promise<McpTestResponse>((resolve) => this.mcpWaiting.set(id, resolve))
    this.testMcpMessage({ jsonrpc: '2.0', id, method, params }, protocolVersion)
    return answer
  }

  async gameData(version: string): Promise<GameDataBundle | null> {
    checkVersion(version)
    return this.game.get(version) ?? null
  }

  async glyphAdvances(version: string): Promise<Record<string, number> | null> {
    checkVersion(version)
    if (!this.clients.has(version)) return null
    return { ...(this.advances[version] ?? {}) }
  }

  async openLauncher(): Promise<void> {
    this.launcherOpened += 1
  }

  assetUrl(version: string, path: string): string {
    const served = this.clients.has(version) ? this.assets[path] : undefined
    // Anything else points at the real protocol's URL, which nothing serves
    // here: the viewport degrades to placeholders, as it does before a real
    // import. (The app's CSP allows that scheme, and only that one.)
    return served ?? `nfasset://localhost/${encodeURIComponent(version)}/${path}`
  }

  // ---- the outside world, for tests ------------------------------------------

  /** The open project's text files as they are "on disk" (binary files are left out). */
  /** Chooses a menu item, as clicking it in the macOS menu bar would. */
  testMenuAction(id: string) {
    for (const listener of this.menuListeners) listener(id)
  }

  testFiles(): Record<string, string> {
    return Object.fromEntries(
      [...this.files()].filter((entry): entry is [string, string] => typeof entry[1] === 'string'),
    )
  }

  /** A file's bytes, base64 (so Playwright can carry them out of the page), or null. */
  testBytes(path: string): string | null {
    const contents = this.files().get(path)
    if (contents === undefined) return null
    return bytesToBase64(
      typeof contents === 'string' ? new TextEncoder().encode(contents) : contents,
    )
  }

  /** Another program writes a binary file, given as base64. */
  testWriteBytes(path: string, base64: string) {
    this.files().set(path, base64ToBytes(base64))
    this.changed([path])
  }

  /** Another program writes a file (git, an agent, another editor). */
  testWrite(path: string, text: string) {
    this.files().set(path, text)
    this.changed([path])
  }

  /** Another program writes a file and the watcher misses it, saying only that it lost events. */
  testWriteMissed(path: string, text: string) {
    this.files().set(path, text)
    setTimeout(() => this.fsChanged.emit({ paths: [], rescan: true }), this.debounce)
  }

  /** Another program deletes a file or folder. */
  testDelete(path: string) {
    const files = this.files()
    const removed = [...files.keys()].filter((it) => it === path || it.startsWith(`${path}/`))
    for (const it of removed) files.delete(it)
    this.changed(removed)
  }

  /** Another program renames a file or folder (`git mv`, a file manager). */
  testRename(from: string, to: string) {
    const files = this.files()
    const changed: string[] = []
    for (const old of [...files.keys()].filter((it) => it === from || it.startsWith(`${from}/`))) {
      const next = to + old.slice(from.length)
      files.set(next, files.get(old)!)
      files.delete(old)
      changed.push(old, next)
    }
    this.changed(changed)
  }

  /** The plugin sends a notification. */
  testBridgeEvent<E extends BridgeEventName>(event: E, params: BridgeEvents[E]) {
    this.bridge.emit({ event, params })
  }

  /** From now on the test runner answers with [report] (filtered by the test's file and name, as the real one does). */
  testTestReport(report: TestReport | null) {
    this.testReport = report
  }

  /** From now on the plugin's answer to a reload says the server must restart. */
  testReloadRestarts(on = true) {
    this.reloadRestarts = on
  }

  /** The server prints a line. */
  testServerOutput(line: string, stream: 'stdout' | 'stderr' = 'stdout') {
    this.output(stream, line)
  }

  /** A player joins the fake server, standing at a block position (or moves there). */
  testPlayer(player: string, world: string, x: number, y: number, z: number) {
    this.players = [
      ...this.players.filter((it) => it.player !== player),
      { player, world, x, y, z },
    ]
  }

  /** The fake server loads another world. */
  testWorld(name: string, environment = 'normal') {
    if (!this.worlds.some((it) => it.name === name)) {
      this.worlds = [...this.worlds, { name, environment, main: false }]
    }
  }

  /** Files appear in the dev server's folder, as base64 (a world the server saved). */
  testServerFiles(files: Record<string, string>) {
    for (const [path, base64] of Object.entries(files))
      this.serverFiles.set(path, base64ToBytes(base64))
  }

  /** Jumps the fake server straight to running with the bridge up. */
  testConnect() {
    this.eula = true
    this.setState({
      phase: 'running',
      minecraft: this.projectMinecraft(),
      port: this.settings.serverPort,
      bridgeConnected: true,
      message: null,
    })
  }

  // ---- internals -------------------------------------------------------------

  private setState(patch: Partial<ServerState>) {
    // The debugger's session is the bridge connection's.
    if (patch.bridgeConnected === false) this.debugAdapter.reset()
    this.state = { ...this.state, ...patch }
    this.serverStateEvents.emit({ ...this.state })
  }

  private output(stream: 'stdout' | 'stderr', line: string) {
    this.serverOutput.emit({ stream, line })
  }

  private wait(): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, this.serverDelay))
  }

  private projectMinecraft(): string | null {
    return this.manifestField('minecraft')
  }

  /**
   * The fake server's settings: the project's own (as its netherforge.json
   * declares them now), each with what `set_setting` set, else its default.
   * Its packages' aren't there: a real dev server has those too.
   */
  private serverSettings(): ServerSettings {
    const namespace = this.manifestField('namespace') ?? 'project'
    const manifest = this.openRoot
      ? this.projects.get(this.openRoot)?.get('netherforge.json')
      : null
    let declared: Record<string, SettingDef> = {}
    let name = namespace
    try {
      const parsed = JSON.parse(typeof manifest === 'string' ? manifest : '{}') as {
        name?: string
        settings?: Record<string, SettingDef>
      }
      declared = parsed.settings ?? {}
      name = parsed.name ?? namespace
    } catch {
      // A manifest that doesn't parse declares nothing.
    }
    if (Object.keys(declared).length === 0) return { packages: [] }
    const settings = Object.fromEntries(
      Object.entries(declared).map(([key, definition]) => {
        const set = this.settingValues.has(key)
        return [
          key,
          { definition, value: set ? this.settingValues.get(key) : definition.default, set },
        ]
      }),
    )
    return {
      packages: [
        { namespace, name, file: `plugins/NetherForge/settings/${namespace}.json`, settings },
      ],
    }
  }

  private manifestField(field: 'minecraft' | 'namespace'): string | null {
    const manifest = this.openRoot
      ? this.projects.get(this.openRoot)?.get('netherforge.json')
      : null
    if (typeof manifest !== 'string') return null
    try {
      return (JSON.parse(manifest) as Record<string, string | undefined>)[field] ?? null
    } catch {
      return null
    }
  }
}

function projectName(root: string, manifest: string | undefined): string {
  try {
    const name = (JSON.parse(manifest ?? '') as { name?: unknown }).name
    if (typeof name === 'string' && name) return name
  } catch {
    // Fall back to the folder name, as the Rust side does.
  }
  return root.split('/').filter(Boolean).pop() ?? root
}

/** As the Rust side's `scope::is_reserved_name`: a device name Windows reserves, in any case, with or without an extension. */
function isReservedName(segment: string): boolean {
  const stem = segment.split('.')[0]!.replace(/ +$/, '').toLowerCase()
  return /^(con|prn|aux|nul|(com|lpt)[1-9\u00b9\u00b2\u00b3])$/.test(stem)
}

/**
 * As the Rust side's `scope::is_named`: [segment] is [name] (lowercase) on a
 * case-insensitive file system, after dropping the trailing dots and spaces
 * Windows ignores. Unicode lowercase, the same as Rust's `to_lowercase`.
 */
function isNamed(segment: string, name: string): boolean {
  return segment.replace(/[. ]+$/, '').toLowerCase() === name
}

/** As the Rust side's `scope::segments`. */
function checkPath(path: string) {
  const parts = path.split('/')
  if (
    !path ||
    path.startsWith('/') ||
    /[\\:\0]/.test(path) ||
    parts.some((it) => it === '..' || it === '.' || it === '') ||
    parts.some((it) => /[. ]$/.test(it) || isReservedName(it))
  ) {
    fail('invalidPath', `"${path}" isn't a project path`)
  }
}

/**
 * As the Rust side's `fs::serve`: what the `nfproject` protocol serves of a
 * project (or, not [project], of a package): a project path that isn't
 * hidden, or the project's own cached thumbnails.
 */
function isServed(path: string, project: boolean): boolean {
  try {
    checkPath(path)
  } catch {
    return false
  }
  const [top, folder] = path.split('/')
  if (project && isNamed(top!, '.netherforge') && folder === 'thumbnails') return true
  return !isNamed(top!, '.netherforge') && !path.split('/').some((it) => isNamed(it, '.git'))
}

/** As the Rust side's `map::is_excluded`, inverted: what a map keeps of a world's folder. */
function isMapFile(relative: string): boolean {
  const parts = relative.split('/')
  const name = parts[parts.length - 1]!
  if (['session.lock', 'uid.dat', 'level.dat_old', 'paper-world.yml'].includes(name)) return false
  if (
    parts.slice(0, -1).some((it) => ['players', 'playerdata', 'stats', 'advancements'].includes(it))
  )
    return false
  return !relative.startsWith('data/paper/')
}

/** As the Rust side's `fs::is_writable`: never `.git`, nothing in `.netherforge/` but what the UI regenerates. */
function checkWritable(path: string) {
  const [top, folder, inner] = path.split('/')
  const writable =
    !path.split('/').some((it) => isNamed(it, '.git')) &&
    (!isNamed(top!, '.netherforge') ||
      (inner !== undefined && ['schema', 'docs', 'bin', 'thumbnails', 'luals'].includes(folder!)))
  if (!writable) fail('readOnly', `${path} can't be changed from the editor`)
  checkPath(path)
}

/** As the Rust side's `version::is_release`: `26.3`, `1.21.11`. */
function checkVersion(version: string) {
  if (!/^\d+\.\d+(\.\d+)?$/.test(version))
    fail('invalid', `"${version}" isn't a Minecraft release version`)
}

/** As the Rust side's `Settings::validate`. */
function validateSettings(settings: Settings) {
  if (settings.serverMemoryMb < 512)
    fail('invalid', 'The dev server needs at least 512 MB of memory')
  if (settings.serverPort === 0) fail('invalid', 'Pick a server port between 1 and 65535')
  if (settings.mcpPort === 0) fail('invalid', 'Pick an MCP port between 1 and 65535')
  if (settings.mcpEnabled && settings.mcpPort === settings.serverPort)
    fail('invalid', "The MCP port can't be the dev server's port")
  for (const arg of settings.serverJvmArgs) {
    const why = jvmArgumentProblem(arg)
    if (why) fail('jvmArgument', `JVM argument ${JSON.stringify(arg)} isn't allowed: ${why}`)
  }
}

const JVM_BOOLEAN_FLAGS = new Set([
  'UseG1GC',
  'UseZGC',
  'ZGenerational',
  'UseShenandoahGC',
  'UseParallelGC',
  'UseSerialGC',
  'AlwaysPreTouch',
  'DisableExplicitGC',
  'ParallelRefProcEnabled',
  'UseStringDeduplication',
  'PerfDisableSharedMem',
  'UseNUMA',
  'UseTransparentHugePages',
  'UseLargePages',
  'UnlockExperimentalVMOptions',
  'HeapDumpOnOutOfMemoryError',
  'ExitOnOutOfMemoryError',
  'CrashOnOutOfMemoryError',
  'OmitStackTraceInFastThrow',
  'UseCompressedOops',
  'UseCompressedClassPointers',
  'AlwaysActAsServerClassMachine',
  'UseFastUnorderedTimeStamps',
])
const JVM_NUMBER_FLAGS = new Set([
  'MaxGCPauseMillis',
  'G1NewSizePercent',
  'G1MaxNewSizePercent',
  'G1HeapRegionSize',
  'G1ReservePercent',
  'G1HeapWastePercent',
  'G1MixedGCCountTarget',
  'G1MixedGCLiveThresholdPercent',
  'G1RSetUpdatingPauseTimePercent',
  'InitiatingHeapOccupancyPercent',
  'SurvivorRatio',
  'MaxTenuringThreshold',
  'ParallelGCThreads',
  'ConcGCThreads',
  'MaxRAMPercentage',
  'MinRAMPercentage',
  'InitialRAMPercentage',
  'MaxMetaspaceSize',
  'ReservedCodeCacheSize',
  'SoftMaxHeapSize',
  'ZCollectionInterval',
  'MaxInlineLevel',
])
const JVM_BLOCKED_PROPERTY_PREFIXES = [
  'java.',
  'javax.',
  'jdk',
  'sun.',
  'com.sun.',
  'user.',
  'os.',
  'file.separator',
  'path.separator',
  'line.separator',
  'netherforge.',
  'log4j',
  'org.apache.logging.',
  'jna.',
  'polyglot.',
  'org.graalvm.',
]

/** As the Rust side's `jvm_args::check` (the list and its rules are documented there): why [arg] isn't allowed, or null. */
function jvmArgumentProblem(arg: string): string | null {
  const size = (text: string) => /^[0-9]{1,12}[kKmMgG]?$/.test(text)
  if (new TextEncoder().encode(arg).length > 1024) return "it's too long"
  if (!arg || arg.trim() !== arg) return "it's empty or has spaces around it"
  const memory = /^-X(?:mx|ms|mn|ss)(.*)$/s.exec(arg)
  if (memory) return size(memory[1]!) ? null : 'give a size such as 4G, 512m or 2048'
  if (arg.startsWith('-XX:')) {
    const flag = arg.slice(4)
    if (/^[+-]/.test(flag)) {
      return JVM_BOOLEAN_FLAGS.has(flag.slice(1))
        ? null
        : "that -XX switch isn't on the editor's list"
    }
    const eq = flag.indexOf('=')
    const name = eq < 0 ? '' : flag.slice(0, eq)
    if (!JVM_NUMBER_FLAGS.has(name)) return "that -XX option isn't on the editor's list"
    const value = flag.slice(eq + 1)
    const ok = name.endsWith('Percentage')
      ? /^[0-9]{1,12}(\.[0-9]{1,6})?$/.test(value)
      : size(value)
    return ok ? null : 'the value must be a number (with k, m or g for a size)'
  }
  if (arg.startsWith('-D')) {
    const eq = arg.indexOf('=')
    const name = eq < 0 ? arg.slice(2) : arg.slice(2, eq)
    const value = eq < 0 ? '' : arg.slice(eq + 1)
    if (!/^[A-Za-z][A-Za-z0-9_.-]*$/.test(name))
      return 'a property name is letters, digits, `_`, `.` and `-`, starting with a letter'
    const lower = name.toLowerCase()
    if (JVM_BLOCKED_PROPERTY_PREFIXES.some((it) => lower.startsWith(it)))
      return 'that property belongs to the JVM, its libraries or the editor'
    // eslint-disable-next-line no-control-regex
    if (/[\u0000-\u001f\u007f-\u009f]/.test(value)) return 'the value has a control character'
    return null
  }
  if (
    ['-server', '-Xshare:auto', '-Xshare:off', '-verbose:gc', '-Xlog:gc', '-Xlog:gc*'].includes(arg)
  )
    return null
  return /^(-javaagent|-agent|-Xrun|-Xbootclasspath|-cp|-classpath|--|@)/.test(arg)
    ? 'agents, class paths, module options and argument files can run or load code'
    : "it isn't an allowed memory, -XX or -D argument"
}
