#!/usr/bin/env node
/**
 * Type-checks the plugin's Lua (the prelude's modules, the caps, the generated bindings and
 * schema) with the pinned lua-language-server, the same binary and version the editor runs
 * (`tools/luals.mjs` fetches it, cached): `--check` over `apps/plugin/runtime/src`, with the
 * prelude's modules resolvable by `require` as they are at runtime (`src/luals/input.lua` says
 * what the Kotlin host hands the entry).
 *
 * Prints one line per diagnostic, at every level down to hints (an unused local is one), and
 * exits non-zero when there are any. Run by `pnpm lint`.
 */
import { spawnSync } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { binaryIn, fetchLuals } from './luals.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const src = path.join(root, 'apps/plugin/runtime/src')
const lua = 'dev/netherforge/plugin/lua'

/** The settings the check runs with; `require("std")` finds the prelude's module of that name. */
const settings = {
  'runtime.version': 'Lua 5.4',
  'runtime.path': [
    path.join(src, 'main/resources', lua, 'prelude/?.lua'),
    path.join(src, 'generated/lua', lua, 'prelude/?.lua'),
    path.join(src, 'luals/?.lua'),
  ].map((it) => it.split(path.sep).join('/')),
  'workspace.checkThirdParty': false,
  // Everything but `strong` (`no-unknown`, which wants every value's type written out).
  'diagnostics.groupFileStatus': {
    ambiguity: 'Any',
    await: 'Any',
    duplicate: 'Any',
    global: 'Any',
    luadoc: 'Any',
    redefined: 'Any',
    strict: 'Any',
    'type-check': 'Any',
    unbalanced: 'Any',
    unused: 'Any',
  },
  // The prelude resumes and yields coroutines itself: `await` is how scripts are held to
  // waiting only in tasks, which the generated stubs (not these modules) mark.
  'diagnostics.disable': ['await-in-sync'],
}

const work = mkdtempSync(path.join(tmpdir(), 'netherforge-lint-prelude-'))
try {
  const target = process.env.LUA_LANGUAGE_SERVER
  const binary = target ?? binaryIn(await fetchLuals())
  if (!existsSync(binary)) throw new Error(`no lua-language-server at ${binary}`)
  const config = path.join(work, 'luarc.json')
  const report = path.join(work, 'report.json')
  writeFileSync(config, JSON.stringify(settings))
  const result = spawnSync(
    binary,
    [
      `--check=${src}`,
      `--configpath=${config}`,
      '--checklevel=Hint',
      '--check_format=json',
      `--check_out_path=${report}`,
      `--logpath=${path.join(work, 'log')}`,
    ],
    { encoding: 'utf8', timeout: 300_000 },
  )
  if (!existsSync(report))
    throw new Error(`lua-language-server wrote no report:\n${result.stdout}${result.stderr}`)
  const text = readFileSync(report, 'utf8').trim()
  /** @type {Record<string, { code: string, message: string, range: { start: { line: number } } }[]>} */
  const found = text ? JSON.parse(text) : {}
  const lines = Object.entries(found).flatMap(([uri, diagnostics]) =>
    diagnostics.map(
      (d) =>
        `${path.relative(root, fileURLToPath(uri))}:${d.range.start.line + 1} [${d.code}] ${d.message.split('\n')[0]}`,
    ),
  )
  if (lines.length) {
    console.error(lines.sort().join('\n'))
    console.error(`\n${lines.length} problem(s) in the plugin's Lua`)
    process.exitCode = 1
  } else {
    console.log("lua-language-server: the plugin's Lua type-checks")
  }
} catch (error) {
  console.error(`lint-prelude: ${error.message}`)
  process.exitCode = 1
} finally {
  rmSync(work, { recursive: true, force: true })
}
