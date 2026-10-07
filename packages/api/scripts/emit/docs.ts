/** `docs/reference/*.md`: the API reference. */
import { articles } from '../../src/spec/articles.ts'
import type {
  ApiSpec,
  Article,
  EventSpec,
  Field,
  Fn,
  LuaClass,
  Surface,
  ValueType,
} from '../../src/types.ts'
import { REQUIREMENTS, requirementKind, requirementSentence } from '../../src/requirements.ts'
import type { Requirement } from '../../src/types.ts'
import { chainOf } from './bindings.ts'
import { GENERATED_BY, SAVEABLE, WAITS, eventedClasses, sinceVersion } from './common.ts'

const header = `<!-- ${GENERATED_BY} -->\n`

/** How a function is called: `nf.centities.spawn(kind, x, y, z, world?)`, `centity:node(name)`. */
function callShape(receiver: string, separator: string, fn: Fn): string {
  const params = fn.params.map((p) => (p.optional ? `${p.name}?` : p.name)).join(', ')
  return `${receiver}${separator}${fn.name}(${params})`
}

function code(text: string): string {
  return '`' + text.replace(/\|/g, '\\|') + '`'
}

function cell(text: string): string {
  return text.replace(/\|/g, '\\|').replace(/\n/g, ' ')
}

export function fnDoc(receiver: string, separator: string, fn: Fn, level = '##'): string {
  const parts = [`${level} \`${callShape(receiver, separator, fn)}\``, '', fn.doc, '']
  if (fn.waits) parts.push(`*${WAITS}*`, '')
  if (fn.since) parts.push(`*Since Minecraft ${sinceVersion(fn.since)}.*`, '')
  if (fn.requires)
    parts.push(`*${requirementSentence(fn.requires, `${receiver}${separator}${fn.name}`)}*`, '')
  if (fn.params.length) {
    parts.push('| Parameter | Type | |', '| --- | --- | --- |')
    for (const p of fn.params) {
      parts.push(
        `| \`${p.name}\`${p.optional ? ' (optional)' : ''} | ${code(p.type)} | ${cell(p.doc)} |`,
      )
    }
    parts.push('')
  }
  if (fn.returns.length) {
    const returns = fn.returns.map((r) => `${code(r.type)}${r.doc ? `: ${r.doc}` : ''}`).join(', ')
    parts.push(`**Returns** ${returns}`, '')
  }
  if (fn.example) parts.push('```lua', fn.example, '```', '')
  return parts.join('\n')
}

export function fieldsTable(cls: { fields: Field[] }): string {
  const rows = ['| Field | Type | |', '| --- | --- | --- |']
  for (const field of cls.fields) {
    const doc = field.since
      ? `${field.doc} *Since Minecraft ${sinceVersion(field.since)}.*`.trim()
      : field.doc
    rows.push(`| \`${field.name}\` | ${code(field.type)} | ${cell(doc)} |`)
  }
  return rows.join('\n')
}

function pageName(cls: { name: string }): string {
  return cls.name.toLowerCase()
}

/** The lowercase name examples use for an object: `centity:node(...)`, `player:send_message(...)`. */
function receiver(cls: LuaClass): string {
  return cls.methods ? cls.name.toLowerCase() : cls.name
}

/** A payload's link on the events page, or "`Event`" for an event without one. */
function payloadLink(event: EventSpec): string {
  const name = event.payload ?? 'Event'
  return `[${name}](events.md#${name.toLowerCase()})`
}

/** A class's events as a table: what `:on` accepts on it. */
function eventsTable(events: EventSpec[]): string {
  const rows = ['| Event | Payload | Cancellable | |', '| --- | --- | --- | --- |']
  for (const event of events) {
    const flags = [
      event.bubbles ? 'Bubbles.' : '',
      event.writable?.length
        ? `Writable: ${event.writable.map((it) => `\`${it}\``).join(', ')}.`
        : '',
      event.options?.length ? `Options: ${event.options.map((it) => `\`${it}\``).join(', ')}.` : '',
      event.local ? 'Only the registering script hears it.' : '',
      event.since ? `*Since Minecraft ${sinceVersion(event.since)}.*` : '',
    ].filter(Boolean)
    rows.push(
      `| \`${event.name}\` | ${payloadLink(event)} | ${event.cancellable ? 'yes' : ''} | ${cell([event.doc, ...flags].join(' '))} |`,
    )
  }
  return rows.join('\n')
}

/** `[Living](living.md)`. */
const classLink = (cls: { name: string }) => `[${cls.name}](${pageName(cls)}.md)`

/** `a`, or `an` before `Entity`. */
const article = (name: string) => (/^[AEIOU]/.test(name) ? 'an' : 'a')

/** What a handle class is a kind of (up its chain), and the kinds there are of it. */
function chainText(spec: ApiSpec, cls: LuaClass): string {
  const above = chainOf(spec, cls).slice(1)
  const below = spec.classes.filter((it) => it.extends === cls.name)
  const parts: string[] = []
  if (above.length) {
    const kinds = above
      .map((it, k) => `${k ? 'which is ' : ''}${article(it.name)} ${classLink(it)}`)
      .join(', ')
    const taken = above.map((it) => `${article(it.name)} \`${it.name}\``).join(' or ')
    parts.push(
      `It is ${kinds}: it has every method and event of ${above.length === 1 ? 'that class' : 'those classes'} too (its own replace any of the same name), and goes wherever ${taken} is taken.`,
    )
  }
  if (below.length)
    parts.push(
      `Its kinds, each with more methods: ${below.map(classLink).join(', ')}. A handle is the most specific kind the server knows it to be.`,
    )
  return parts.length ? parts.join(' ') + '\n' : ''
}

function classPage(spec: ApiSpec, cls: LuaClass): string {
  const separator = cls.methods ? ':' : '.'
  const intro = cls.methods
    ? `A handle: call its methods with a colon, \`${receiver(cls)}:method()\`.`
    : 'A table of functions: call them with a dot.'
  const body = cls.functions.map((fn) => fnDoc(receiver(cls), separator, fn)).join('\n')
  const events = cls.events?.length
    ? [
        '## Events',
        '',
        `What \`${receiver(cls)}${separator}on(event, handler)\` listens for. See [Events](events.md) for how events work.${cls.customEvents ? ' Any name with a `:` in it is a custom event, raised with `emit`.' : ''}`,
        '',
        eventsTable(cls.events),
        '',
      ].join('\n')
    : ''
  const inherited = cls.methods ? chainText(spec, cls) : ''
  const saved = cls.saveable ? `${SAVEABLE}\n` : ''
  return [header, `# ${cls.name}`, '', cls.doc, '', intro, '', inherited, saved, events, body].join(
    '\n',
  )
}

const OPERATOR_SYMBOLS = { add: '+', sub: '-', mul: '*', div: '/', unm: '-' } as const

function valuePage(value: ValueType): string {
  // `a:length()`: the plan's own name for a vector, and not the library's (`vec3`).
  const self = value.library ? 'a' : value.name.toLowerCase()
  const parts = [
    header,
    `# ${value.name}`,
    '',
    value.doc,
    '',
    `A value: read its fields with a dot (\`${self}.${value.fields[0]!.name}\`), call its methods with a colon (\`${self}:method()\`). Its fields can't be assigned.`,
    '',
    fieldsTable(value),
    '',
  ]
  if (value.operators.length) {
    parts.push('## Operators', '', '| Expression | Result | |', '| --- | --- | --- |')
    for (const it of value.operators) {
      const symbol = OPERATOR_SYMBOLS[it.op]
      const expression = it.operand ? `${self} ${symbol} ${it.operand}` : `${symbol}${self}`
      parts.push(`| ${code(expression)} | ${code(it.result)} | ${cell(it.doc)} |`)
    }
    parts.push('')
  }
  const library = value.library
  if (library) {
    parts.push(`## \`${library.name}\``, '', library.doc, '')
    parts.push(fnDoc('', '', library.call, '###'))
    parts.push('### Constants', '', fieldsTable(library), '')
    for (const fn of library.functions) parts.push(fnDoc(library.name, '.', fn, '###'))
  }
  parts.push('## Methods', '')
  for (const fn of value.functions) parts.push(fnDoc(self, ':', fn, '###'))
  return parts.join('\n')
}

function surfacePage(surfaces: Surface[]): string {
  const parts = [
    header,
    '# Scripts',
    '',
    "Where a script runs decides what it can see. Every script also gets the [globals](index.md#globals). A centity, a menu, a dialog and an item each have at most one script, and its own handle is `this`; a slot or a button is `this:slot(index)` or `this:button(key)`. A script can `require` more `.lua` files beside it, which run as part of it, with the same `this`. A script's body runs every time it starts, and that's where it registers its handlers: see [Events](events.md).",
    '',
    'A script the editor creates starts with `local this = this --[[@as Centity]]` (or `Menu`, or `Dialog`), so lua-language-server knows which `this` it is: on its own it only knows `Centity|Menu|Dialog`.',
    '',
  ]
  for (const surface of surfaces) {
    parts.push(
      `## ${surface.name[0]!.toUpperCase()}${surface.name.slice(1)} scripts`,
      '',
      surface.doc,
      '',
    )
    if (surface.globals.length) {
      parts.push('| Global | Type | |', '| --- | --- | --- |')
      for (const g of surface.globals)
        parts.push(`| \`${g.name}\` | ${code(g.type)} | ${cell(g.doc)} |`)
      parts.push('')
    }
  }
  return parts.join('\n')
}

const EVENTS_INTRO = [
  'Events live on the handle they are about: `this:on("tick", handler)`, `node:on("click", handler)`, `menu:slot(13):on("click", handler)`. Server-wide events are on `nf`: `nf.on("player_join", handler)`. Every evented class also has `once`, which listens for the next one only. An event a class does not have is an error.',
  '',
  '```lua',
  'local lid = this:node("lid")',
  'lid:on("click", function(event)',
  '  this:play_animation("open")',
  '  event:stop()',
  'end)',
  'local subscription = this:on("tick", function(event) end, { every = 20 })',
  'subscription:cancel()',
  '```',
  '',
  '## The event object',
  '',
  "A handler gets one table: the event's payload fields (each event's payload is listed below) and the members of [Event](#event): `name`, `current`, `cancelled`, `stopped`, `stop()`, `cancel()` and `uncancel()`. **What a handler returns means nothing.** `event:cancel()` cancels what caused a cancellable event and is an error on any other. Fields an event lists as writable may be assigned, and the runtime reads them back after every handler has run; assigning any other field is an error.",
  '',
  '## Bubbling',
  '',
  'Some events go on along a path after the handle they happened to. A centity click is heard by the clicked node, then each node above it, then the centity, then `nf.on("centity_click")`. A menu click: the slot, the menu, the template the menu was made from (when a script made it with `nf.menus.create`), then `nf.on("menu_click")`. A dialog press: the button, the dialog, then `nf.on("dialog_press")`. A menu\'s `open` and `close` go on, through its template if it has one, to `menu_open` and `menu_close`. `event:stop()` ends it anywhere, after the handlers on the same handle registered before. `event.target` is what was hit; `event.current` is the handle whose handler is running.',
  '',
  '## Lifetimes',
  '',
  "A handler lives as long as **both** the script that registered it and the handle it is on: a module's handler on a centity's node goes when the module unloads or the centity is removed, whichever comes first, and one on a menu window goes with the window. A script's body runs every time it starts, so it registers its handlers afresh each time.",
  '',
  '## Custom events',
  '',
  'A name with a `:` in it is a custom event, never an error: `nf.emit("shop:purchased", payload)` reaches every `nf.on("shop:purchased")`, and `centity:emit("door:open", payload)` every `centity:on("door:open")` on that instance, whoever registered them. Delivery is immediate, the payload is handed over as it is, and `emit` returns whether a handler cancelled it.',
  '',
  '## Errors',
  '',
  "A handler that errors is logged with its file and line (in the console and the editor), and the other handlers still run. The same error repeated is logged only now and then. After 20 errors in a row a subscription is cancelled, with a line saying so; a success resets the count. Tasks from `nf.every` stop the same way. An error in a script's body fails the whole script, because its setup didn't finish.",
  '',
]

function eventsPage(spec: ApiSpec): string {
  const parts = [header, '# Events', '', ...EVENTS_INTRO]
  for (const cls of eventedClasses(spec)) {
    const where = cls.name === 'nf' ? '`nf.on`' : `[${cls.name}](${pageName(cls)}.md)`
    parts.push(
      `## Events on ${cls.name === 'nf' ? 'nf' : cls.name}`,
      '',
      `What ${where} listens for.`,
      '',
    )
    parts.push(eventsTable(cls.events), '')
    for (const event of cls.events.filter((it) => it.example)) {
      parts.push(`### ${cls.name === 'nf' ? 'nf' : receiver(cls)} ${event.name}`, '')
      parts.push('```lua', event.example!, '```', '')
    }
  }
  parts.push('# Payloads and other tables', '')
  for (const shape of spec.shapes) {
    parts.push(`## ${shape.name}`, '', shape.doc, '')
    if (shape.extends)
      parts.push(
        `Has the fields and methods of [${shape.extends}](#${shape.extends.toLowerCase()}) too.`,
        '',
      )
    parts.push(fieldsTable(shape), '')
    for (const fn of shape.functions) parts.push(fnDoc('event', ':', fn, '###'))
  }
  return parts.join('\n')
}

function indexPage(spec: ApiSpec): string {
  const parts = [
    header,
    '# Lua API reference',
    '',
    "Everything a NetherForge script can call. Scripts run Lua 5.4 in a sandbox, on the server's main thread, under an instruction budget per call.",
    '',
    '- [Scripts](scripts.md): centity, menu and dialog scripts (one per resource) and modules, and the globals each gets',
    '- [Events](events.md): how `:on` works, every event each class has, and the tables handlers receive',
    ...articles.map((it) => `- [${it.title}](${it.file}): ${it.summary}`),
    ...[...spec.classes, ...(spec.values ?? [])].map(
      (cls) =>
        `- [${cls.name}](${pageName(cls)}.md): ${cls.doc.split('. ')[0]!.replace(/\.$/, '')}`,
    ),
    '',
    '## Globals',
    '',
    '| Global | Type | |',
    '| --- | --- | --- |',
    ...spec.globals.map((g) => `| \`${g.name}\` | ${code(g.type)} | ${cell(g.doc)} |`),
    '',
    ...(spec.aliases?.length
      ? [
          '## Types',
          '',
          'Names the reference uses for a plain Lua type that means more than it.',
          '',
          '| Type | Is a | |',
          '| --- | --- | --- |',
          ...spec.aliases.map((it) => `| \`${it.name}\` | ${code(it.type)} | ${cell(it.doc)} |`),
          '',
        ]
      : []),
    'Also there from Lua itself: `string`, `table`, `math`, `utf8`, `coroutine`, `pairs`, `ipairs`, `pcall`, `error`, `tostring`, `tonumber`, `type`, `select`, `setmetatable` and the rest of the basics. The shared library tables are read-only.',
    '',
    "`math.random` is seeded afresh each time the server starts the project's scripts (at server start, and on a full reload), so its numbers differ from run to run. Every script shares the one generator: `math.randomseed(n)` gives a repeatable sequence, for every script at once.",
    '',
    ...requirementsSection(spec),
    '## Removed',
    '',
    `The sandbox takes away whatever could reach the machine or escape the limits: ${spec.removed.map((name) => `\`${name}\``).join(', ')}. Files go through \`nf.files\`, and \`require\` reaches only the project\'s modules.`,
    '',
    '## Editor support',
    '',
    "Outside the NetherForge editor, lua-language-server gets completions, hover docs and type checking from `nf.lua`, which the editor writes into each project's `.netherforge/luals/`, marking what the project can't use deprecated (and which is generated into `packages/api/generated/luals/`): see [Editing outside the editor](../guide/external-editors.md#lua-luals-stubs) for the `.luarc.json`. `this` is `Centity|Menu|Dialog` there, so start each script with `local this = this --[[@as Centity]]` (or `Menu`, or `Dialog`), as the editor does. `luals/surfaces/<surface>.lua` says exactly what each kind of script sees.",
  ]
  return parts.join('\n')
}

/**
 * What the functions that need something declared need, and how a package declares it: only
 * the requirements some function has.
 */
function requirementsSection(spec: ApiSpec): string[] {
  const needing = new Map<Requirement, string[]>()
  for (const cls of spec.classes)
    for (const fn of cls.functions)
      if (fn.requires) {
        const separator = cls.methods ? ':' : '.'
        needing.set(fn.requires, [
          ...(needing.get(fn.requires) ?? []),
          `[\`${receiver(cls)}${separator}${fn.name}\`](${pageName(cls)}.md)`,
        ])
      }
  if (needing.size === 0) return []
  const rows = [...needing].map(([requirement, fns]) => {
    const spec = REQUIREMENTS[requirementKind(requirement)!]
    const declared = spec.declared!(requirement)
    return `| \`${requirement}\` | ${cell(spec.what)} | ${cell(declared)} | ${fns.join(', ')} |`
  })
  return [
    '## Requirements',
    '',
    'Some functions need the package calling them to have declared what they do, so whoever runs it sees in one place what it may do. Calling one without is an error saying what to declare; the editor marks such a call.',
    '',
    '| Requirement | Lets scripts | Declared with | Functions |',
    '| --- | --- | --- | --- |',
    ...rows,
    '',
  ]
}

function articlePage(article: Article): string {
  return [header, `# ${article.title}`, '', article.body.trim(), ''].join('\n')
}

/** Every page of `docs/reference/`, by file name. */
export function referencePages(spec: ApiSpec): Map<string, string> {
  const pages = new Map<string, string>()
  for (const article of articles) pages.set(article.file, articlePage(article))
  for (const cls of spec.classes) pages.set(`${pageName(cls)}.md`, classPage(spec, cls))
  for (const value of spec.values ?? []) pages.set(`${pageName(value)}.md`, valuePage(value))
  pages.set('scripts.md', surfacePage(spec.surfaces))
  pages.set('events.md', eventsPage(spec))
  pages.set('index.md', indexPage(spec))
  return pages
}
