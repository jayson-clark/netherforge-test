import { parseArgs } from 'node:util'
import { statSync } from 'node:fs'
import path from 'node:path'
import { build, check, format, lock, type Outcome } from './commands.ts'
import { findProjectRoot } from './project.ts'
import { preview } from './preview.ts'
import { test } from './test.ts'

declare const __NETHERFORGE_VERSION__: string

const HELP = `netherforge ${__NETHERFORGE_VERSION__}

Usage:
  netherforge check  [dir] [--json] [--game-data <file>]
      Validate the whole project, as the editor's Problems panel does.
      Prints file:line: severity: message (at $.json.path), one per line.
      Exits 1 if there are errors. Ids are checked against the game data
      the editor's dev server exported, when it has.
  netherforge format [dir] [--check]
      Rewrite project JSON in canonical form, as the editor saves it.
      With --check, change nothing and exit 1 if a file isn't canonical.
  netherforge lock   [dir] [--update]
      Write netherforge.lock as the project's dependencies resolve now
      (the editor does this whenever they change). A git dependency stays
      at the commit the lock pins until its git or rev changes; --update
      resolves every one afresh (a branch's newest commit). Git packages
      are fetched with your git into the editor's package cache.
  netherforge build  [dir] [--out <folder>]
      Write a bundle: the project and the packages it depends on, for a
      production server to run (point the plugin's project setting at it).
      Default folder: build/<namespace>-<version> in the project. Refuses
      a project with errors or an out-of-date netherforge.lock.
  netherforge preview terrain/<id>.json --out <file>.png [--seed <n>]
                     [--x <n>] [--z <n>] [--size <cells>] [--step <blocks>]
                     [--scale <pixels>] [--slice x|z --at <n> [--width <n>]]
                     [--min-y <n> --max-y <n> | --dimension-type <id>]
      Draw a terrain as the editor's preview does, from the same code:
      the top-down map (default), or with --slice x (along the x axis at z
      --at) or --slice z (along z at x --at) the ground through that line.
      --x and --z centre it (default 0, 0); the map is --size cells (96) of
      --step blocks (4), each --scale pixels (4; 3 for a slice); a slice is
      --width columns (128). --seed is a whole number (1337). The world is
      --min-y (-64) up to --max-y (320, one above its highest block), or the
      heights of the project's dimension type --dimension-type (dimension_types/<id>.json).
      The structures its decorations place are read from structures/<name>.nbt,
      and a file with a script runs terrain/<id>.lua (and the modules it
      requires) as the server does. Prints the picture's size, what its
      colours are and what the script failed at. Problems in the file print
      as check prints them and exit 1.
  netherforge test   [dir|file] [--json] [--junit <file>] [--filter <text>]
                     [--game-data <file>]
      Run the project's *_test.lua files on a fake server, each test on a
      fresh one, and print every test with its verdict and where it failed.
      Needs Java (the one the editor downloads is found) and the test runner's
      jar (the editor puts it in its data folder; see NETHERFORGE_TEST_JAR).
      The project runs on the real game data of its Minecraft version, the
      dev server's export the editor caches (or --game-data <file>); without
      it the tests don't run.
      --json prints one JSON event per line, --junit also writes JUnit XML,
      --filter keeps the tests whose "file: name" contains the text. Giving a
      *_test.lua file runs only that file. Exits 1 if a test fails or errors,
      2 if the tests couldn't run.

[dir] is the project folder, or any folder inside it (default: here).
`

export async function run(argv: string[], env: NodeJS.ProcessEnv = process.env): Promise<Outcome> {
  let parsed
  try {
    parsed = parseArgs({
      args: attachNegatives(argv),
      allowPositionals: true,
      options: {
        json: { type: 'boolean' },
        check: { type: 'boolean' },
        'game-data': { type: 'string' },
        out: { type: 'string' },
        update: { type: 'boolean' },
        junit: { type: 'string' },
        filter: { type: 'string' },
        seed: { type: 'string' },
        x: { type: 'string' },
        z: { type: 'string' },
        size: { type: 'string' },
        step: { type: 'string' },
        scale: { type: 'string' },
        slice: { type: 'string' },
        at: { type: 'string' },
        width: { type: 'string' },
        'min-y': { type: 'string' },
        'max-y': { type: 'string' },
        'dimension-type': { type: 'string' },
        help: { type: 'boolean', short: 'h' },
        version: { type: 'boolean', short: 'v' },
      },
    })
  } catch (error) {
    return { out: [], err: [(error as Error).message, '', HELP], code: 2 }
  }
  const { values, positionals } = parsed
  if (values.version) return { out: [__NETHERFORGE_VERSION__], err: [], code: 0 }
  const [command, dir = '.', ...extra] = positionals
  if (values.help || !command || command === 'help') return { out: [HELP], err: [], code: 0 }
  if (extra.length > 0) return { out: [], err: [`Unexpected argument: ${extra[0]}`], code: 2 }
  // `netherforge test tests/shop_test.lua` runs that file's tests, from the project it's in.
  const testFile =
    command === 'test' && dir.endsWith('_test.lua') && isFile(dir) ? path.resolve(dir) : null
  // `netherforge preview terrain/hills.json` draws that file, from the project it's in.
  const previewFile = command === 'preview' ? path.resolve(dir) : null
  const root = findProjectRoot(
    testFile ? path.dirname(testFile) : previewFile ? path.dirname(previewFile) : dir,
  )
  if (!root)
    return { out: [], err: [`No netherforge.json in ${dir} or any folder above it.`], code: 2 }
  switch (command) {
    case 'check':
      return check(root, { json: values.json, gameData: values['game-data'] }, env)
    case 'format':
      return format(root, { check: values.check })
    case 'lock':
      return lock(root, { update: values.update }, env)
    case 'build':
      return build(root, { out: values.out }, env)
    case 'preview': {
      const numbers: Record<string, number | undefined> = {}
      for (const key of ['x', 'z', 'size', 'step', 'scale', 'at', 'width', 'min-y', 'max-y']) {
        const text = values[key as keyof typeof values] as string | undefined
        if (text === undefined) continue
        // Whole numbers only: Number('') and Number('1e2') would pass for one.
        if (!/^-?\d+$/.test(text)) {
          return { out: [], err: [`--${key} is a whole number, not "${text}".`], code: 2 }
        }
        numbers[key] = Number(text)
      }
      return preview(root, previewFile!, {
        seed: values.seed,
        x: numbers.x,
        z: numbers.z,
        size: numbers.size,
        step: numbers.step,
        scale: numbers.scale,
        slice: values.slice,
        at: numbers.at,
        width: numbers.width,
        minY: numbers['min-y'],
        maxY: numbers['max-y'],
        dimensionType: values['dimension-type'],
        out: values.out,
      })
    }
    case 'test':
      return test(
        root,
        {
          json: values.json,
          junit: values.junit,
          gameData: values['game-data'],
          filter:
            values.filter ??
            (testFile ? path.relative(root, testFile).split(path.sep).join('/') : undefined),
        },
        __NETHERFORGE_VERSION__,
        env,
      )
    default:
      return { out: [], err: [`Unknown command: ${command}`, '', HELP], code: 2 }
  }
}

/**
 * `--z -500` as `--z=-500`: parseArgs takes a value that starts with `-` for the next option, and coordinates
 * are often negative.
 */
function attachNegatives(argv: string[]): string[] {
  const out: string[] = []
  for (const arg of argv) {
    const last = out.at(-1)
    if (last !== undefined && /^--[\w-]+$/.test(last) && /^-\d+$/.test(arg)) {
      out[out.length - 1] = `${last}=${arg}`
    } else {
      out.push(arg)
    }
  }
  return out
}

function isFile(file: string): boolean {
  try {
    return statSync(file).isFile()
  } catch {
    return false
  }
}
