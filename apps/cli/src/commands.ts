import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import path from 'node:path'
import * as nf from '@netherforge/format'
import {
  BUNDLE_FILE,
  GAME_DATA_SCHEMA,
  LOCK_FILE,
  MANIFEST_FILE,
} from '@netherforge/format/constants'
import type { CanonicalResult, Problem, ProjectOutline } from '@netherforge/format/types'
import {
  documentKindOf,
  editorDataDir,
  gameDataFile,
  hashPackage,
  listFiles,
  packageCache,
  packageDir,
  readPackages,
  readProject,
} from './project.ts'

/** What a command prints and its exit code; main() does the printing. */
export interface Outcome {
  out: string[]
  err: string[]
  code: number
}

/** `file:line:column: severity: message (at $.json.path)`, the shape editors and agents parse. */
export function formatProblem(problem: Problem): string {
  const where = [problem.file, problem.line, problem.line != null ? problem.column : undefined]
    .filter((it) => it != null)
    .join(':')
  const at = problem.path ? ` (at ${problem.path})` : ''
  const code = problem.code ? ` [${problem.code}]` : ''
  // A broken reference's other end, so it can be found from either.
  const related = (problem.related ?? []).map(
    (it) => `\n  see ${it.file}${it.path ? ` (at ${it.path})` : ''}`,
  )
  return `${where}: ${problem.severity}${code}: ${problem.message}${at}${related.join('')}`
}

const RELEASE = /^\d+(\.\d+)*$/

/**
 * The game data to validate ids against: [explicit] if given, else the
 * editor's cache for the project's target version. Null when there is none;
 * validation then checks everything but whether ids exist in the game.
 */
function loadGameData(
  input: Record<string, string | null>,
  explicit: string | undefined,
  env: NodeJS.ProcessEnv,
): { json: string | null; note: string | null } {
  if (explicit) {
    const json = readFileSync(explicit, 'utf8')
    if (isCurrent(json)) return { json, note: null }
    return {
      json: null,
      note:
        `${explicit} is game data of another shape than this NetherForge reads (schema ${GAME_DATA_SCHEMA}), ` +
        "so block, item and entity ids weren't checked.",
    }
  }
  const minecraft = projectMinecraft(input['netherforge.json'])
  if (minecraft == null) return { json: null, note: null }
  const file = gameDataFile(editorDataDir(env), minecraft)
  let json: string | null = null
  try {
    json = readFileSync(file, 'utf8')
  } catch {
    // No cache: said below.
  }
  if (json != null && isCurrent(json)) return { json, note: null }
  // A cache of another schema is as good as none: the editor exports it again on the next start.
  return {
    json: null,
    note:
      `No game data for Minecraft ${minecraft} (${file}), so block, item and entity ids weren't checked. ` +
      'Start the dev server from the NetherForge editor once to export it, or pass --game-data <file>.',
  }
}

/** The release version the manifest's text targets (`minecraft`), or null when it names none. */
export function projectMinecraft(manifest: string | null | undefined): string | null {
  try {
    const minecraft = (JSON.parse(manifest ?? '{}') as { minecraft?: unknown }).minecraft
    return typeof minecraft === 'string' && RELEASE.test(minecraft) ? minecraft : null
  } catch {
    return null
  }
}

/** Whether [json] is a game data export of the shape format reads (`GameDataBundle.schema`). */
export function isCurrent(json: string): boolean {
  try {
    return (JSON.parse(json) as { schema?: unknown }).schema === GAME_DATA_SCHEMA
  } catch {
    return false
  }
}

export function check(
  root: string,
  options: { json?: boolean; gameData?: string },
  env: NodeJS.ProcessEnv = process.env,
): Outcome {
  const input = readProject(root)
  const game = loadGameData(input, options.gameData, env)
  const outline = load(root, input, env, game.json)
  const problems = outline.problems
  const errors = problems.filter((it) => it.severity === 'error').length
  const warnings = problems.length - errors
  const code = errors > 0 ? 1 : 0
  if (options.json) {
    const report = { problems, gameData: game.json != null, errors, warnings }
    return { out: [JSON.stringify(report, null, 2)], err: [], code }
  }
  const out = problems.map(formatProblem)
  const err = game.note ? [game.note] : []
  out.push(
    problems.length === 0
      ? 'No problems.'
      : `${errors} error${errors === 1 ? '' : 's'}, ${warnings} warning${warnings === 1 ? '' : 's'}.`,
  )
  return { out, err, code }
}

/**
 * The project at [root] and the packages it depends on, loaded as the editor
 * loads them: git packages fetched into the editor's package cache.
 */
function load(
  root: string,
  input: Record<string, string | null>,
  env: NodeJS.ProcessEnv,
  gameData: string | null = null,
): ProjectOutline {
  const packages = readPackages(root, packageCache(env), input)
  return JSON.parse(
    nf.loadProject(JSON.stringify(input), JSON.stringify(packages), gameData),
  ) as ProjectOutline
}

/**
 * Writes `netherforge.lock` as the project's dependencies resolve now (as the
 * editor does whenever they change), or removes it from a project with none.
 * Git dependencies stay at the commits the lock pins while their `git` and
 * `rev` are unchanged; with [update], every one is resolved afresh (a
 * branch's newest commit).
 */
export function lock(
  root: string,
  options: { update?: boolean } = {},
  env: NodeJS.ProcessEnv = process.env,
): Outcome {
  const input = readProject(root)
  const resolving = { ...input }
  if (options.update) delete resolving[LOCK_FILE]
  const outline = load(root, resolving, env)
  const file = path.join(root, LOCK_FILE)
  const errors = outline.problems.filter(
    (it) => it.severity === 'error' && it.code?.startsWith('package.'),
  )
  if (errors.length > 0)
    return {
      out: errors.map(formatProblem),
      err: ["Dependencies don't resolve, so nothing was locked."],
      code: 1,
    }
  if (outline.lock == null) {
    if (Object.keys(outline.packages ?? {}).length > 0)
      return { out: [], err: ["A package couldn't be hashed, so nothing was locked."], code: 1 }
    if (!existsSync(file)) return { out: ['No dependencies to lock.'], err: [], code: 0 }
    rmSync(file)
    return { out: [`${LOCK_FILE}: removed (no dependencies)`], err: [], code: 0 }
  }
  if (input[LOCK_FILE] === outline.lock)
    return { out: [`${LOCK_FILE}: up to date`], err: [], code: 0 }
  writeFileSync(file, outline.lock)
  return { out: [`${LOCK_FILE}: written`], err: [], code: 0 }
}

/**
 * Writes a bundle: the project and every package it depends on, each in a
 * folder named after its namespace with only what its content hash covers,
 * and `netherforge-bundle.json` listing them with their hashes, for a
 * production server to run as it is. Refuses a project with errors, or whose
 * `netherforge.lock` isn't what its dependencies resolve to.
 */
export function build(
  root: string,
  options: { out?: string },
  env: NodeJS.ProcessEnv = process.env,
): Outcome {
  const input = readProject(root)
  const outline = load(root, input, env)
  const errors = outline.problems.filter(
    (it) => it.severity === 'error' || it.code === 'lock.missing' || it.code === 'lock.stale',
  )
  if (errors.length > 0) {
    const why = errors.some((it) => it.code?.startsWith('lock.'))
      ? ' Run netherforge lock (or open the project in the editor) to bring netherforge.lock up to date.'
      : ''
    return { out: errors.map(formatProblem), err: [`Not built: fix these first.${why}`], code: 1 }
  }
  const namespace = outline.namespace!
  const version = (JSON.parse(input[MANIFEST_FILE]!) as { version: string }).version
  const out = path.resolve(root, options.out ?? path.join('build', `${namespace}-${version}`))
  if (existsSync(out)) {
    // Only ever replace a bundle: never a folder of something else.
    if (readdirSync(out).length > 0 && !existsSync(path.join(out, BUNDLE_FILE)))
      return {
        out: [],
        err: [`${out} isn't empty and isn't a bundle, so it was left alone.`],
        code: 1,
      }
    rmSync(out, { recursive: true, force: true })
  }
  const folders: Record<string, string> = { [namespace]: root }
  for (const [name, pkg] of Object.entries(outline.packages ?? {}))
    folders[name] = packageDir(root, packageCache(env), pkg.location)
  const packages: Record<string, { version: string; hash: string }> = {}
  for (const [name, dir] of Object.entries(folders).sort(([a], [b]) => a.localeCompare(b))) {
    for (const file of listFiles(dir).filter(nf.isPackageContent)) {
      const target = path.join(out, name, file)
      mkdirSync(path.dirname(target), { recursive: true })
      copyFileSync(path.join(dir, file), target)
    }
    const manifest = JSON.parse(readFileSync(path.join(dir, MANIFEST_FILE), 'utf8')) as {
      version: string
    }
    packages[name] = { version: manifest.version, hash: hashPackage(path.join(out, name)) }
  }
  const { formatVersion } = JSON.parse(input[MANIFEST_FILE]!) as { formatVersion: number }
  const bundle = { formatVersion, project: namespace, packages }
  const written = JSON.parse(
    nf.canonicalize('bundle', BUNDLE_FILE, JSON.stringify(bundle)),
  ) as CanonicalResult
  writeFileSync(path.join(out, BUNDLE_FILE), written.text!)
  const names = Object.keys(packages).filter((it) => it !== namespace)
  const what = names.length === 0 ? '' : ` with ${names.join(', ')}`
  // Where it is, as short as it can say it: from here when it's under here.
  const here = path.relative(process.cwd(), out)
  const shown = here && !here.startsWith('..') && !path.isAbsolute(here) ? here : out
  return {
    out: [`Built ${shown}: ${namespace} ${version}${what}.`],
    err: [],
    code: 0,
  }
}

/**
 * Rewrites every project JSON file in canonical form, as the editor saves it.
 * With [checkOnly], writes nothing and fails if any file would change.
 * A file that doesn't parse is reported and left alone.
 */
export function format(root: string, options: { check?: boolean }): Outcome {
  const out: string[] = []
  const err: string[] = []
  let code = 0
  for (const file of listFiles(root)) {
    const kind = documentKindOf(file)
    if (!kind) continue
    const full = path.join(root, file)
    const text = readFileSync(full, 'utf8')
    const result = JSON.parse(nf.canonicalize(kind, file, text)) as CanonicalResult
    if (result.text == null) {
      err.push(...result.problems.map(formatProblem))
      code = 1
      continue
    }
    if (result.text === text) continue
    if (options.check) {
      out.push(`${file}: not canonical`)
      code = 1
    } else {
      writeFileSync(full, result.text)
      out.push(`${file}: formatted`)
    }
  }
  return { out, err, code }
}
