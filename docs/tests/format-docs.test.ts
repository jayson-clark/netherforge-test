/**
 * Keeps the file-format pages (`docs/format/*.md`) in step with the checker the editor and the `netherforge` command
 * run (format's JS build):
 *
 * - every ```json block that is a whole file validates, as its kind, with that checker (and, where the kind has a
 *   place in a project, as part of one);
 * - every key the JSON Schema of a kind knows is mentioned on that kind's page.
 *
 * How to write the examples is in docs/contributing.md ("Examples in the format pages").
 */
import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import * as nf from '@netherforge/format'
import { DOCUMENT_KINDS, KINDS } from '@netherforge/format/constants'
import type { CanonicalResult, ProjectOutline } from '@netherforge/format/types'
import { describe, expect, it } from 'vitest'

const FORMAT = path.resolve(import.meta.dirname, '../format')
const SCHEMAS = path.resolve(
  import.meta.dirname,
  '../../packages/format/build/generated-contract/schema',
)

interface Block {
  page: string
  line: number
  /** The words after the fence's language. */
  attributes: string[]
  text: string
}

const pages = readdirSync(FORMAT)
  .filter((it) => it.endsWith('.md'))
  .sort()
const pageText = (page: string) => readFileSync(path.join(FORMAT, page), 'utf8')

/** The ```json blocks of [markdown], with what follows `json` on the fence. */
function jsonBlocks(page: string, markdown: string): Block[] {
  const blocks: Block[] = []
  let open: { info: string; line: number; body: string[] } | null = null
  markdown.split('\n').forEach((line, index) => {
    const fence = /^```(.*)$/.exec(line)
    if (!fence) {
      open?.body.push(line)
    } else if (!open) {
      open = { info: fence[1]!.trim(), line: index + 1, body: [] }
    } else {
      const [language, ...attributes] = open.info.split(/\s+/)
      if (language === 'json') {
        blocks.push({ page, line: open.line, attributes, text: open.body.join('\n') })
      }
      open = null
    }
  })
  return blocks
}

type Classified = { kind: string } | 'partial' | 'unmarked'

/**
 * What a block is: `{ kind }` for a whole file (its `$schema` names the kind, or `kind=<id>` follows `json`),
 * `'partial'` for a snippet (`json partial`), or `'unmarked'` for a plain block that says neither: a mistake in the
 * page.
 */
function classify(block: Pick<Block, 'attributes' | 'text'>): Classified {
  if (block.attributes.includes('partial')) return 'partial'
  const named = block.attributes.find((it) => it.startsWith('kind='))?.slice('kind='.length)
  const schema = /"\$schema":\s*"[^"]*\/([a-z_]+)\.schema\.json"/.exec(block.text)?.[1]
  const kind = named ?? schema
  return kind ? { kind } : 'unmarked'
}

const classified = pages
  .flatMap((page) => jsonBlocks(page, pageText(page)))
  .map((block) => ({ block, class: classify(block) }))

/** What a project that holds only the example can still complain about: its neighbours aren't there. */
const NEIGHBOURS = [
  'reference.',
  'script.missing',
  'terrain.script-missing',
  'resource_pack.texture-missing',
  'package.missing',
  'package.export-missing',
]

const MANIFEST = JSON.stringify({
  formatVersion: 1,
  name: 'Docs',
  version: '1.0.0',
  namespace: 'docs',
  minecraft: '26.3',
})

/** Where a project holds a [kind]'s one example, or null for a kind that isn't a resource of its own. */
function projectPathOf(kind: string): string | null {
  if (kind === 'netherforge') return 'netherforge.json'
  const spec = (KINDS as Record<string, (typeof KINDS)[keyof typeof KINDS]>)[kind]
  if (!spec || spec.contents !== 'json') return null
  return spec.layout === 'folder'
    ? `${spec.folder}/example/${spec.main}`
    : `${spec.folder}/example${spec.extension}`
}

describe('the examples in docs/format', () => {
  it('has examples to check', () => {
    expect(classified.length).toBeGreaterThan(20)
    expect(classified.some((it) => typeof it.class === 'object')).toBe(true)
  })

  it('says of every json block whether it is a whole file or a snippet', () => {
    const unmarked = classified
      .filter((it) => it.class === 'unmarked')
      .map((it) => `${it.block.page}:${it.block.line}`)
    expect(
      unmarked,
      'a ```json block is a whole file, so it has a "$schema" line (or ```json kind=<id>); a snippet is ```json partial',
    ).toEqual([])
  })

  it('tells whole files from snippets', () => {
    const text = '{ "$schema": "../.netherforge/schema/loot_table.schema.json" }'
    expect(classify({ attributes: [], text })).toEqual({ kind: 'loot_table' })
    expect(classify({ attributes: ['partial'], text })).toBe('partial')
    expect(classify({ attributes: ['kind=bundle'], text: '{}' })).toEqual({ kind: 'bundle' })
    expect(classify({ attributes: [], text: '{}' })).toBe('unmarked')
  })

  const whole = classified.flatMap((it) =>
    typeof it.class === 'object' ? [{ block: it.block, kind: it.class.kind }] : [],
  )

  it.each(whole.map((it) => [`${it.block.page}:${it.block.line} (${it.kind})`, it] as const))(
    '%s validates',
    (_, { block, kind }) => {
      expect(DOCUMENT_KINDS as readonly string[]).toContain(kind)
      const parsed = JSON.parse(
        nf.canonicalize(kind, `${kind}.json`, block.text),
      ) as CanonicalResult
      expect(parsed.problems.map((it) => it.message)).toEqual([])
      expect(parsed.text).toBeDefined()
      const file = projectPathOf(kind)
      if (file === null) return
      const files: Record<string, string> = { 'netherforge.json': MANIFEST }
      files[file] = block.text
      const outline = JSON.parse(
        nf.loadProject(JSON.stringify(files), null, null),
      ) as ProjectOutline
      const problems = outline.problems.filter(
        (it) => !NEIGHBOURS.some((prefix) => it.code?.startsWith(prefix)),
      )
      expect(
        problems.map((it) => `${it.file}: [${it.code}] ${it.message} (at ${it.path})`),
      ).toEqual([])
    },
  )
})

/** An item stack (`{ "kind": …, "lore": … }`) is written the same wherever a file holds one: menu.md says how. */
const STACK = ['menu.md']

/** Which pages describe each kind of file; one that is spread over several lists them all. */
const PAGES: Record<(typeof DOCUMENT_KINDS)[number], string[]> = {
  netherforge: ['project.md', 'packages.md', 'settings.md', 'worlds.md', 'terrain.md'],
  lock: ['packages.md'],
  default_font: ['project.md'],
  structure_generation: ['worlds.md'],
  resource_pack: ['resource-pack.md'],
  particle_effect: ['particle-effect.md', ...STACK],
  cutscene: ['cutscene.md'],
  item: ['item.md', ...STACK],
  recipe: ['recipe.md', ...STACK],
  loot_table: ['loot.md', ...STACK],
  block: ['block.md'],
  biome: ['biome.md'],
  dimension_type: ['dimension-type.md'],
  terrain: ['terrain.md'],
  advancement: ['advancement.md'],
  menu: ['menu.md'],
  dialog: ['dialog.md', ...STACK],
  centity: ['centity.md'],
  bundle: ['packages.md'],
  datapack: ['datapack.md'],
}

/** Every name a schema gives a property: its `properties`, and the names a map's keys may be (`propertyNames`). */
function keysOf(node: unknown, into: Set<string> = new Set()): Set<string> {
  if (Array.isArray(node)) {
    for (const item of node) keysOf(item, into)
  } else if (node && typeof node === 'object') {
    for (const [key, value] of Object.entries(node)) {
      if (key === 'properties' && value && typeof value === 'object') {
        for (const name of Object.keys(value)) into.add(name)
      }
      if (key === 'propertyNames') {
        const names = (value as { enum?: unknown }).enum
        if (Array.isArray(names)) for (const name of names) into.add(String(name))
      }
      keysOf(value, into)
    }
  }
  return into
}

/** The names a page writes in code: inside backticks (`terrain.base`), or as a key of an example (`"base":`). */
function mentionsIn(markdown: string): Set<string> {
  const words = new Set<string>()
  const add = (text: string) => {
    for (const word of text.split(/[^\w$-]+/)) if (word) words.add(word)
  }
  for (const span of markdown.matchAll(/`([^`\n]+)`/g)) add(span[1]!)
  for (const key of markdown.matchAll(/"([^"\n]+)"\s*:/g)) add(key[1]!)
  return words
}

describe('the keys in the schemas', () => {
  it('finds the keys of a schema and the names a page writes', () => {
    const schema = JSON.parse(readFileSync(path.join(SCHEMAS, 'terrain.schema.json'), 'utf8'))
    expect([...keysOf(schema)]).toEqual(expect.arrayContaining(['terrain', 'seaLevel', 'ores']))
    const mentioned = mentionsIn(
      '`terrain.seaLevel`, `{ "blend": 1 }` and { "ores": 2 }; not blank',
    )
    expect([...mentioned]).toEqual(expect.arrayContaining(['terrain', 'seaLevel', 'blend', 'ores']))
    expect(mentioned.has('blank')).toBe(false)
  })

  it('has a page for every kind of file', () => {
    expect(Object.keys(PAGES).sort()).toEqual([...DOCUMENT_KINDS].sort())
    for (const page of Object.values(PAGES).flat()) expect(pages).toContain(page)
  })

  it.each(DOCUMENT_KINDS.map((kind) => [kind] as const))(
    "%s: every key is on its page's",
    (kind) => {
      const schema = JSON.parse(readFileSync(path.join(SCHEMAS, `${kind}.schema.json`), 'utf8'))
      const mentioned = new Set(PAGES[kind].flatMap((page) => [...mentionsIn(pageText(page))]))
      const missing = [...keysOf(schema)].filter((key) => key !== '$schema' && !mentioned.has(key))
      expect(missing, `keys of ${kind} files that ${PAGES[kind].join(', ')} never names`).toEqual(
        [],
      )
    },
  )
})
