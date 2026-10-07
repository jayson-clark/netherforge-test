import { readFileSync } from 'node:fs'
import { spawnSync, type SpawnSyncReturns } from 'node:child_process'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { MIN_JAVA, currentJavaEnv, findJava, type JavaEnv } from './java.ts'
import { isCurrent, projectMinecraft, type Outcome } from './commands.ts'
import { editorDataDir, gameDataFile, packageCache } from './project.ts'

/** What `netherforge test` takes besides the project. */
export interface TestOptions {
  /** One JSON event per line instead of text: what the editor reads. */
  json?: boolean
  /** Also write JUnit XML here. */
  junit?: string
  /** Only the tests whose "file: name" contains this. */
  filter?: string
  /** Game data to run on (the dev server's export), instead of the editor's cache for the project's Minecraft version. */
  gameData?: string
}

/** The runner's jar for this version of the command: `NetherForgeTest-<version>.jar`. */
export const jarName = (version: string) => `NetherForgeTest-${version}.jar`

/**
 * Where the runner's jar is, for [version]: `NETHERFORGE_TEST_JAR`, beside this script (a CI job
 * that downloaded both), or the editor's data folder (`<data>/test-runner/`, where the editor puts
 * the one it carries). Null when none is.
 */
export function findJar(
  version: string,
  env: NodeJS.ProcessEnv = process.env,
  beside: string = path.dirname(fileURLToPath(import.meta.url)),
): string | null {
  const explicit = env.NETHERFORGE_TEST_JAR
  if (explicit) return existsSync(explicit) ? explicit : null
  return (
    [
      path.join(beside, jarName(version)),
      path.join(editorDataDir(env), 'test-runner', jarName(version)),
    ].find(existsSync) ?? null
  )
}

/**
 * The game data the tests run on: [explicit] if given, else the editor's cache for the project's
 * target version. Unlike `check`, which then only skips checking ids, the tests need it (what
 * exists in the game decides what a project can do), so the error says how to get it.
 */
export function findGameData(
  root: string,
  explicit: string | undefined,
  env: NodeJS.ProcessEnv = process.env,
): { file: string } | { err: string } {
  let manifest: string | null = null
  try {
    manifest = readFileSync(path.join(root, 'netherforge.json'), 'utf8')
  } catch {
    // Said below, or by the runner.
  }
  const minecraft = projectMinecraft(manifest)
  const file = explicit
    ? path.resolve(explicit)
    : minecraft && gameDataFile(editorDataDir(env), minecraft)
  if (!file)
    return {
      err: "netherforge.json doesn't name a Minecraft release (`minecraft`), so there is no game data to test on.",
    }
  const how =
    'Start the dev server from the NetherForge editor once to export it, or pass --game-data <file>.'
  let json: string
  try {
    json = readFileSync(file, 'utf8')
  } catch {
    return {
      err: `Running tests needs the game data of Minecraft ${minecraft ?? 'the project'}, and ${file} doesn't exist. ${how}`,
    }
  }
  if (!isCurrent(json))
    return {
      err: `${file} is game data of another shape than this NetherForge reads, so the tests can't run on it. ${how}`,
    }
  return { file }
}

type Spawn = (command: string, args: string[]) => Pick<SpawnSyncReturns<Buffer>, 'status' | 'error'>

/**
 * Runs the project's `*_test.lua` files: the JVM half (the jar) does the work, with output going
 * straight to the terminal, so a long run shows its tests as they finish. The exit code is the
 * jar's: 0 when every test passed, 1 when one failed, 2 when the tests couldn't run.
 */
export function test(
  root: string,
  options: TestOptions,
  version: string,
  env: NodeJS.ProcessEnv = process.env,
  /** What launches the JVM, the machine searched for Java and where the script lies: what a test replaces. */
  host: { spawn?: Spawn; machine?: JavaEnv; beside?: string } = {},
): Outcome {
  const spawn: Spawn =
    host.spawn ?? ((command, args) => spawnSync(command, args, { stdio: 'inherit' }))
  const java = findJava(host.machine ?? currentJavaEnv(env))
  if (!java)
    return {
      out: [],
      err: [
        `Running tests needs Java ${MIN_JAVA} or newer, and none was found. The NetherForge editor downloads one when it starts a dev server; ` +
          'or install one (https://adoptium.net) and set JAVA_HOME, or NETHERFORGE_JAVA to its java binary.',
      ],
      code: 2,
    }
  const jar = findJar(version, env, host.beside)
  if (!jar)
    return {
      out: [],
      err: [
        `Running tests needs ${jarName(version)}, which was not found. The NetherForge editor puts it in ${path.join(editorDataDir(env), 'test-runner')} when it starts; ` +
          `each release also attaches it. Put it beside this script, or set NETHERFORGE_TEST_JAR to it.`,
      ],
      code: 2,
    }
  const game = findGameData(root, options.gameData, env)
  if ('err' in game) return { out: [], err: [game.err], code: 2 }
  const args = ['-jar', jar, root, '--packages', packageCache(env), '--game-data', game.file]
  if (options.json) args.push('--json')
  if (options.junit) args.push('--junit', path.resolve(options.junit))
  if (options.filter) args.push('--filter', options.filter)
  const ran = spawn(java, args)
  if (ran.error) return { out: [], err: [`Couldn't run ${java}: ${ran.error.message}`], code: 2 }
  return { out: [], err: [], code: ran.status ?? 2 }
}
