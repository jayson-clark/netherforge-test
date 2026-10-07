/**
 * The backend contract on `MemoryBackend`. The same cases (`contract.json`)
 * run on the real Rust commands in `src-tauri/src/commands/contract.rs`, so
 * what the UI's tests are told here is what the app does.
 */
import { describe, expect, it } from 'vitest'
import contract from './contract.json'
import type { commands } from './generated/bindings'
import { MemoryBackend, type MemoryGitRepo } from './memory'
import { BackendError, type Backend } from './types'

const ROOT = '/memory/contract'
/** Where the contract's repositories are, as `$GIT` in a case. */
const GIT = 'file:///memory/git'

type Args = Record<string, unknown>
type Call = (backend: Backend, args: Args) => Promise<unknown>

/**
 * Each command, as the backend method `TauriBackend` maps it to. Keyed by
 * the generated command (camelCase), so a renamed or removed command fails
 * the type check here.
 */
const CALLS: Partial<Record<keyof typeof commands, Call>> = {
  settingsGet: (b) => b.getSettings(),
  settingsSet: (b, a) => b.setSettings(a.settings as never),
  projectRecent: (b) => b.recentProjects(),
  projectOpen: (b, a) => b.openProject(a.root as string),
  projectCreate: (b, a) => b.createProject(a.root as string, a.files as Record<string, string>),
  projectClose: (b) => b.closeProject(),
  projectTrust: (b, a) => b.trustProject(a.trusted as boolean),
  fsList: (b) => b.listFiles(),
  fsReadText: (b, a) => b.readText(a.path as string),
  fsWriteText: (b, a) => b.writeText(a.path as string, a.text as string),
  fsWriteBytes: (b, a) => b.writeBytes(a.path as string, new Uint8Array(a.bytes as number[])),
  fsDelete: (b, a) => b.deletePath(a.path as string),
  fsRename: (b, a) => b.renamePath(a.from as string, a.to as string),
  packageFiles: (b, a) => b.packageFiles(a.location as string),
  packageReadText: (b, a) => b.packageReadText(a.location as string, a.path as string),
  packageCopy: (b, a) => b.packageCopy(a.location as string, a.from as string, a.to as string),
  packageFetchGit: (b, a) =>
    b.packageFetchGit(a.url as string, a.rev as string | null, a.commit as string | null),
  lualsStart: (b) => b.lualsStart(),
  lualsSend: (b, a) => b.lualsSend(a.generation as number, a.message as string),
  lualsStop: (b) => b.lualsStop(),
  serverState: (b) => b.serverState(),
  serverEulaStatus: (b) => b.eulaStatus(),
  serverEulaAccept: (b) => b.acceptEula(),
  serverStart: (b) => b.startServer(),
  testsRun: (b, a) => b.runTests((a.filter as string | null) ?? undefined),
  serverStop: (b) => b.stopServer(),
  serverCommand: (b, a) => b.serverCommand(a.line as string),
  // The relay's frame, as the UI's JSON-RPC connection sends it; the memory backend answers the request in it.
  bridgeSend: (b, a) => {
    const { method, params } = JSON.parse(a.message as string) as {
      method: string
      params?: unknown
    }
    const request = b.bridgeRequest.bind(b) as (
      method: string,
      ...params: unknown[]
    ) => Promise<unknown>
    return params === undefined ? request(method) : request(method, params)
  },
  mapCapture: (b, a) =>
    b.captureMap(
      { level: a.level as string, dimension: a.dimension as string },
      a.id as string,
      a.replace as boolean,
    ),
  mcCacheStatus: (b, a) => b.cacheStatus(a.version as string),
  mcGameData: (b, a) => b.gameData(a.version as string),
  mcGlyphAdvances: (b, a) => b.glyphAdvances(a.version as string),
}

const camel = (command: string) =>
  command.replace(/_([a-z])/g, (_, c: string) => c.toUpperCase()) as keyof typeof commands

/** [value] with `$ROOT`, `$GIT` and each value a step saved (`$<name>`) put in. */
function substitute(value: unknown, saved: Record<string, string>): unknown {
  if (typeof value === 'string') {
    let text = value.replaceAll('$ROOT', ROOT).replaceAll('$GIT', GIT)
    for (const [name, it] of Object.entries(saved)) text = text.replaceAll(`$${name}`, it)
    return text
  }
  if (Array.isArray(value)) return value.map((it) => substitute(it, saved))
  if (value && typeof value === 'object')
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, substitute(v, saved)]))
  return value
}

/**
 * The contract's repositories as `MemoryBackend` serves them: each one's
 * files as one commit (an id made up per repository: the memory backend
 * hashes nothing), main and v1 naming it.
 */
function repositories(): Record<string, MemoryGitRepo> {
  return Object.fromEntries(
    Object.entries(contract.repositories).map(([name, files], i) => {
      const commit = (i + 1).toString(16).padStart(40, 'c')
      return [
        `${GIT}/${name}`,
        { refs: { HEAD: commit, main: commit, v1: commit }, commits: { [commit]: { ...files } } },
      ]
    }),
  )
}

/** [actual] has everything [expected] says; `"$any"` matches anything. */
function matches(expected: unknown, actual: unknown): boolean {
  if (expected === '$any') return true
  if (Array.isArray(expected))
    return (
      Array.isArray(actual) &&
      actual.length === expected.length &&
      expected.every((it, i) => matches(it, actual[i]))
    )
  if (expected && typeof expected === 'object')
    return (
      !!actual &&
      typeof actual === 'object' &&
      Object.entries(expected).every(([k, v]) => matches(v, (actual as Args)[k]))
    )
  // Commands answer null where the memory backend's methods return nothing.
  return expected === actual || (expected === null && actual === undefined)
}

interface Step {
  call: string
  args?: Args
  ok?: unknown
  error?: string
  save?: string
}

describe('MemoryBackend keeps the backend contract', () => {
  for (const testCase of contract.cases) {
    it(testCase.name, async () => {
      // Each package beside the project, at `../<name>` as the UI names it.
      const packages = Object.fromEntries(
        Object.entries(contract.packages).map(([name, files]) => [
          `${ROOT.slice(0, ROOT.lastIndexOf('/'))}/${name}`,
          { ...files },
        ]),
      )
      const backend = new MemoryBackend({
        projects: { [ROOT]: { ...contract.project }, ...packages },
        gitRepos: repositories(),
        // As the app starts: nothing trusted yet.
        trusted: [],
      })
      const saved: Record<string, string> = {}
      if (testCase.open !== false) await backend.openProject(ROOT)
      for (const [index, step] of (testCase.steps as Step[]).entries()) {
        const at = `step ${index + 1} (${step.call})`
        const call = CALLS[camel(step.call)]
        expect(call, `${at}: the memory backend has no mapping for it`).toBeDefined()
        const outcome = await call!(backend, substitute(step.args ?? {}, saved) as Args).then(
          (value) => ({ value }),
          (error: unknown) => ({ error }),
        )
        if ('error' in outcome) {
          expect(
            outcome.error,
            `${at}: rejected with something other than a BackendError`,
          ).toBeInstanceOf(BackendError)
          const code = (outcome.error as BackendError).code
          expect(
            step.error === undefined ? `succeeds, not ${code}: ${String(outcome.error)}` : code,
            at,
          ).toBe(step.error ?? 'succeeds')
        } else {
          expect(step.error, `${at}: should have failed`).toBeUndefined()
          const ok = substitute(step.ok, saved)
          expect(
            matches(ok, outcome.value),
            `${at}: expected ${JSON.stringify(ok)}, got ${JSON.stringify(outcome.value)}`,
          ).toBe(true)
          if (step.save) saved[step.save] = outcome.value as string
        }
      }
    })
  }
})
