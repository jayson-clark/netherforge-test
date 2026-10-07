import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { asyncFunction } from '../../src/async.ts'
import { api } from '../../src/spec/index.ts'
import type { ApiSpec, LuaClass } from '../../src/types.ts'
import { bindings, classEvents, classFunctions, handwrittenShapes } from './bindings.ts'
import { bindingsLua, schemaLua } from './lua.ts'
import { useFeatures } from './common.ts'
import { luals } from './luals.ts'
import { referencePages } from './docs.ts'
import {
  apiKotlin,
  argumentCodecsKotlin,
  argumentTypesKotlin,
  eventsKotlin,
  gatesKotlin,
  handlesKotlin,
  primitivesKotlin,
  shapesKotlin,
  unionsKotlin,
} from './kotlin.ts'

const handle: LuaClass = {
  name: 'Thing',
  doc: 'A thing.',
  methods: true,
  handle: { key: [{ name: 'id', type: 'string' }] },
  fields: [],
  functions: [],
}

const shape = (
  name: string,
  fields: LuaClass['fields'],
  extra: Partial<LuaClass> = {},
): LuaClass => ({
  name,
  doc: `A ${name}.`,
  methods: false,
  functions: [],
  fields,
  ...extra,
})

function spec(fn: LuaClass['functions'][number], cls: Partial<LuaClass> = {}): ApiSpec {
  return {
    globals: [],
    classes: [{ ...handle, ...cls, functions: [fn] }],
    surfaces: [],
    shapes: [],
    removed: [],
  }
}

/** Everything generated for [it]: the Lua half and every Kotlin file, to search. */
function generated(it: ApiSpec) {
  const bound = bindings(it)
  return {
    lua: bindingsLua(bound.classes, it),
    schema: schemaLua(it),
    api: apiKotlin(bound.classes),
    primitives: primitivesKotlin(bound.classes),
    shapes: shapesKotlin(it, bound),
    unions: unionsKotlin(bound),
    events: eventsKotlin(it),
  }
}

describe('the binding model', () => {
  it('binds every Kotlin-implemented function in the spec', () => {
    const { classes } = bindings(api)
    const kotlin = api.classes.flatMap((cls) => cls.functions.filter((fn) => fn.impl !== 'lua'))
    expect(classes.flatMap((it) => it.functions)).toHaveLength(kotlin.length)
  })

  it('wraps impl lua functions: the checks, then the hand-written body', () => {
    const lua = bindingsLua(bindings(api).classes, api)
    expect(lua).toContain(
      'function Entity.is_player(self)\n  self_of(self, "Entity")\n  return hand.Entity.is_player(self)\nend',
    )
    expect(lua).toContain('function Centity.play_animation(self, name, options)')
    expect(lua).not.toContain('hand.Centity.play_animation')
  })

  it('refuses a handle class that does not say what identifies it', () => {
    expect(() =>
      bindings(spec({ name: 'f', doc: 'd', params: [], returns: [] }, { handle: undefined })),
    ).toThrow(/without `handle`/)
  })

  it('refuses types it has no binding for, naming where', () => {
    const fn = {
      name: 'f',
      doc: 'd',
      params: [{ name: 'x', type: 'Nowhere', doc: '' }],
      returns: [],
    }
    expect(() => bindings(spec(fn))).toThrow(/Thing\.f\(x\): no binding for Nowhere/)
    const keys = { ...fn, params: [{ name: 'x', type: 'table<boolean, string>', doc: '' }] }
    expect(() => bindings(spec(keys))).toThrow(/keys must be strings or whole numbers/)
  })

  it('passes every argument as the one Lua value it is, for Kotlin to read with its codec', () => {
    const fn = {
      name: 'use',
      doc: 'd',
      params: [
        { name: 'with', type: 'Thing', doc: '' },
        { name: 'count', type: 'integer', doc: '', optional: true },
        { name: 'mode', type: '"loud"|"quiet"', doc: '' },
        { name: 'weights', type: 'number[]', doc: '' },
      ],
      returns: [{ type: 'Thing?' }],
    }
    const out = generated(spec(fn))
    // The handle crosses as its key in the handle table, which Kotlin finds it by.
    expect(out.lua).toContain('local self_key = self_of(self, "Thing")')
    expect(out.lua).toContain('return prim["Thing.use"](self_key, with, count, mode, weights)')
    expect(out.primitives).toContain('val self = call.self(1) as LuaHandle.Thing')
    expect(out.primitives).toContain('val with = call.arg(2, "with", LuaHandle.Thing.Codec)')
    expect(out.primitives).toContain('val count = call.arg(3, "count", CODEC_1)')
    expect(out.primitives).toContain('private val CODEC_1 = LuaOptional(LuaCodecs.INTEGER)')
    expect(out.primitives).toContain('LuaChoice(listOf("loud", "quiet"))')
    expect(out.primitives).toContain('LuaList(LuaCodecs.NUMBER)')
    expect(out.primitives).toContain('call.push(result, CODEC_')
    expect(out.api).toContain(
      'fun use(self: LuaHandle.Thing, with: LuaHandle.Thing, count: Long?, mode: String, weights: List<Double>): LuaHandle.Thing?',
    )
  })

  it('keeps the Vec3 fast path: three numbers both ways, checked and rebuilt in Lua', () => {
    const lua = bindingsLua(bindings(api).classes, api)
    expect(lua).toContain(
      'local translation_x, translation_y, translation_z = want_vec3(translation, "translation")',
    )
    expect(lua).toContain('return vec3_of(prim["Node.translation"](self_key))')
    const kotlin = primitivesKotlin(bindings(api).classes)
    expect(kotlin).toContain('val translation = call.vec3(2)')
    expect(kotlin).toContain('call.pushVec3(result)')
    // Inside anything else a vector is a value like any other.
    expect(kotlin).not.toContain('LuaList(LuaCodecs.VEC3)')
  })

  it('crosses any union as a sealed interface Kotlin `when`s on, members tried in order', () => {
    const out = generated(api)
    expect(out.api).toContain(
      'fun moveTo(self: LuaHandle.Centity, target: LocationOrVec3OrEntityOrCentity, options: CentityPathOptions?): Boolean',
    )
    expect(out.unions).toContain('sealed interface LocationOrVec3 {')
    expect(out.unions).toContain(
      'data class Vec3(val value: dev.netherforge.format.Vec3) : LocationOrVec3',
    )
    expect(out.unions).toContain('data class String(val value: kotlin.String) : StringOrDialog')
    // A union of handle classes only is the `LuaHandle` it is.
    expect(out.api).toContain(
      'fun follow(self: LuaHandle.Effect, target: LuaHandle, offset: Vec3?): Boolean',
    )
    expect(out.unions).toContain(
      'val EntityOrBlock: LuaCodec<LuaHandle> = LuaHandleUnion(listOf(LuaHandle.Entity.Codec, LuaHandle.Block.Codec))',
    )
  })

  it('refuses a union whose members a value could not be told apart by', () => {
    const fn = {
      name: 'f',
      doc: 'd',
      params: [{ name: 'x', type: 'string|"a"', doc: '' }],
      returns: [],
    }
    expect(() => bindings(spec(fn))).toThrow(/told apart/)
  })

  it('fills namespaces under nf, parents first', () => {
    const lua = bindingsLua(bindings(api).classes, api)
    expect(lua).toContain('  nf.server = {}')
    // A namespace with only hand-written functions still gets its table.
    expect(lua).toContain('  nf.commands = {}')
    expect(lua.indexOf('nf.server = {}')).toBeLessThan(lua.indexOf('function nf.server.tick('))
    expect(lua).toContain('return prim["nf.server.tick"](scope)')
  })

  it('binds an async function the same way every time: a waker, a CompletionStage and the wait', () => {
    const fn = asyncFunction({
      name: 'fetch',
      doc: 'Fetches.',
      params: [{ name: 'at', type: 'Vec3', doc: '' }],
      value: { name: 'thing', type: 'Thing', doc: 'the thing' },
    })
    expect(fn.params.map((it) => it.name)).toEqual(['at', 'callback'])
    expect(fn.params[1]!.type).toBe('fun(thing: Thing?, err: string?)')
    expect(fn.returns.map((it) => it.type)).toEqual(['Thing?', 'string?'])
    const it = spec(fn)
    const namespaced: ApiSpec = {
      ...it,
      classes: [
        handle,
        { name: 'nf.things', doc: 'd', methods: false, fields: [], functions: [fn] },
      ],
    }
    const out = generated(namespaced)
    expect(out.lua).toContain(
      [
        '  function nf.things.fetch(at, callback)',
        '    local at_x, at_y, at_z = want_vec3(at, "at")',
        '    local waker, task, token = async.begin("nf.things.fetch", callback)',
        '    local wait_id = prim["nf.things.fetch"](scope, at_x, at_y, at_z, waker)',
        '    if task ~= nil then',
        '      local value, err = async.wait(task, token, wait_id)',
        '      return value, err',
        '    end',
        '  end',
      ].join('\n'),
    )
    expect(out.api).toContain(
      'fun fetch(caller: Caller, at: Vec3): CompletionStage<LuaHandle.Thing>',
    )
    expect(out.primitives).toContain('val result = api.nfThings.fetch(caller, at)')
    expect(out.primitives).toMatch(
      /lua\.push\(marshal\.await\("nf\.things\.fetch", caller\.scope, result, call\.keep\(5\), LuaHandle\.Thing\.Codec\)\.toLong\(\)\)/,
    )
    // On a handle, the wait is the calling scope's, which the prelude's `begin` hands over.
    const method = generated(it)
    expect(method.lua).toContain(
      [
        'function Thing.fetch(self, at, callback)',
        '  local self_key = self_of(self, "Thing")',
        '  local at_x, at_y, at_z = want_vec3(at, "at")',
        '  local waker, task, token, scope = async.begin("Thing:fetch", callback)',
        '  local wait_id = prim["Thing.fetch"](self_key, at_x, at_y, at_z, scope, waker)',
      ].join('\n'),
    )
    expect(method.api).toContain(
      'fun fetch(self: LuaHandle.Thing, at: Vec3): CompletionStage<LuaHandle.Thing>',
    )
    expect(method.primitives).toContain('val caller = marshal.caller(lua, 5)')
    expect(method.primitives).toMatch(
      /marshal\.await\("Thing:fetch", caller\.scope, result, call\.keep\(6\), LuaHandle\.Thing\.Codec\)/,
    )
    // LuaLS: an overload per form, so only the waiting one counts as waiting.
    expect(luals(namespaced)).toContain(
      [
        '---@overload async fun(at: Vec3): Thing?, string?',
        '---@overload fun(at: Vec3, callback: fun(thing: Thing?, err: string?))',
        'function nf.things.fetch(at, callback) end',
      ].join('\n'),
    )
    expect(luals(it)).toContain('---@overload async fun(self: Thing, at: Vec3): Thing?, string?')
    // Only in the form asyncFunction gives.
    const bare = { ...fn, returns: [{ type: 'Thing?' }] }
    const malformed: ApiSpec = {
      ...it,
      classes: [
        handle,
        { name: 'nf.things', doc: 'd', methods: false, fields: [], functions: [bare] },
      ],
    }
    expect(() => bindings(malformed)).toThrow(/declared with asyncFunction/)
    // A parameter its binding's own locals would shadow.
    for (const name of ['token', 'wait_id', 'prim']) {
      const shadowed = asyncFunction({
        ...fn,
        params: [{ name, type: 'string', doc: '' }],
        value: { name: 'thing', type: 'Thing', doc: 'the thing' },
      })
      expect(() => generated(spec(shadowed))).toThrow(
        new RegExp(`a parameter can't be called ${name}`),
      )
    }
  })

  it('refuses a class without methods that is not a namespace under nf', () => {
    const fn = { name: 'f', doc: 'd', params: [], returns: [] }
    expect(() => bindings(spec(fn, { name: 'loose', methods: false, handle: undefined }))).toThrow(
      /must be nf or a namespace/,
    )
  })

  it('reads and pushes every shape the same way: an option table, a record, lists and maps of them', () => {
    const fn = {
      name: 'find',
      doc: 'd',
      params: [{ name: 'filters', type: 'table<string, Filter>', doc: '' }],
      returns: [{ type: 'Hit[]' }],
    }
    const withShapes: ApiSpec = {
      ...spec(fn),
      shapes: [
        shape('Filter', [
          { name: 'kind', type: 'string', doc: '' },
          { name: 'near', type: 'Vec3[]?', doc: '' },
          { name: 'context', type: 'any', doc: '' },
        ]),
        shape('Hit', [
          { name: 'by', type: 'Thing', doc: '' },
          { name: 'extra', type: 'any', doc: '' },
        ]),
      ],
      values: [{ name: 'Vec3', doc: 'v', fields: [], functions: [], operators: [] }],
    }
    const out = generated(withShapes)
    expect(out.api).toContain(
      'fun find(self: LuaHandle.Thing, filters: Map<String, Filter>): List<Hit>',
    )
    expect(out.primitives).toContain('LuaMap(LuaCodecs.STRING, Filter.Codec)')
    expect(out.primitives).toContain('LuaList(Hit.Codec)')
    // A shape only taken keeps an `any` as the Lua value itself; one handed back holds anything.
    expect(out.shapes).toContain(
      'data class Filter(val kind: String, val near: List<Vec3>? = null, val context: LuaValue? = null) : LuaShape {',
    )
    expect(out.shapes).toContain(
      'data class Hit(val by: LuaHandle.Thing, val extra: Any? = null) : LuaShape {',
    )
    expect(out.shapes).toContain('val nearCodec = LuaOptional(LuaList(LuaCodecs.VEC3))')
    expect(out.shapes).toContain('near = fields.field("near", nearCodec)')
    expect(out.shapes).toContain('fields.field("by", value.by, byCodec)')
  })

  it('reads a shape that holds itself, naming its own codec lazily', () => {
    const fn = {
      name: 'f',
      doc: 'd',
      params: [{ name: 'tree', type: 'Tree', doc: '' }],
      returns: [],
    }
    const recursive: ApiSpec = {
      ...spec(fn),
      shapes: [
        shape('Tree', [
          { name: 'children', type: 'table<string, Tree>?', doc: '' },
          { name: 'leaf', type: 'Leaf?', doc: '' },
        ]),
        shape('Leaf', [{ name: 'value', type: 'any', doc: '' }]),
      ],
    }
    const out = generated(recursive)
    expect(out.shapes).toContain(
      'data class Tree(val children: Map<String, Tree>? = null, val leaf: Leaf? = null) : LuaShape {',
    )
    expect(out.shapes).toContain(
      'val childrenCodec = LuaOptional(LuaMap(LuaCodecs.STRING, LuaLazy("table") { Tree.Codec }))',
    )
    // A shape it holds that doesn't hold it back is named as any other.
    expect(out.shapes).toContain('val leafCodec = LuaOptional(Leaf.Codec)')
    // Whether reading pins is worked out here: a `Leaf` holds an `any`, so a `Tree` does too.
    expect(out.shapes).toContain('override val pinned = true')
  })

  it('names a shape in full where a union case would shadow it', () => {
    const fn = {
      name: 'f',
      doc: 'd',
      params: [{ name: 'definition', type: 'Options|fun()', doc: '' }],
      returns: [],
    }
    const out = generated({
      ...spec(fn),
      shapes: [shape('Options', [{ name: 'name', type: 'string?', doc: '' }])],
    })
    expect(out.unions).toContain(
      'data class Options(val value: dev.netherforge.plugin.api.Options) : OptionsOrFunction',
    )
    expect(out.unions).toContain('(dev.netherforge.plugin.api.Options.Codec, ::Options)')
    expect(out.unions).toContain(
      'data class Function(val value: dev.netherforge.plugin.lua.LuaFunction)',
    )
  })

  it('gives the prelude the fields of every table a hand-written function takes, nested ones too', () => {
    const shapes = handwrittenShapes(api)
    expect(shapes.map((it) => it.name)).toEqual([
      'DialogOpenOptions',
      'EventOptions',
      'GoalDefinition',
    ])
    const goal = shapes.find((it) => it.name === 'GoalDefinition')
    expect(goal?.required).toEqual(['priority'])
    expect(goal?.fields).toContainEqual({ name: 'priority', kind: 'integer' })
    expect(goal?.fields).toContainEqual({ name: 'controls', kind: 'table' })
    expect(goal?.fields).toContainEqual({ name: 'should_start', kind: 'function' })
    const options = shapes.find((it) => it.name === 'DialogOpenOptions')
    expect(options?.fields).toContainEqual({ name: 'context', kind: 'any' })
    const lua = schemaLua(api)
    expect(lua).toContain('    required = { "priority" },')
    expect(lua).toContain(
      '  shapes = shapes,\n  saved_handles = saved_handles,\n  test_only = test_only,\n}',
    )
  })
})

describe('handle classes that extend another', () => {
  const { classes } = bindings(api)
  const byName = (name: string) => classes.find((it) => it.cls.name === name)!
  const cls = (name: string) => api.classes.find((it) => it.name === name)!

  it('form a chain of any depth, keyed as its top is', () => {
    const mob = byName('Mob').handle!
    expect(mob.chain).toEqual(['Mob', 'Living', 'Entity'])
    expect(mob.root).toBe('Entity')
    expect(mob.fields).toEqual([{ name: 'id', type: 'string' }])
    expect(byName('Entity').handle!.descendants).toEqual([
      'Entity',
      'Living',
      'DroppedItem',
      'Mob',
      'Player',
    ])
    expect(byName('Living').handle!.descendants).toEqual(['Living', 'Mob', 'Player'])
  })

  it('refuse a chain that goes round, a key below the top, or extending what is no handle class', () => {
    const fn = { name: 'f', doc: 'd', params: [], returns: [] }
    const chain = (...more: LuaClass[]): ApiSpec => ({ ...spec(fn), classes: [handle, ...more] })
    const below = (name: string, extend: string, extra: Partial<LuaClass> = {}): LuaClass => ({
      ...handle,
      name,
      handle: undefined,
      extends: extend,
      ...extra,
    })
    expect(() => bindings(chain(below('A', 'B', { handle: undefined }), below('B', 'A')))).toThrow(
      /circle/,
    )
    expect(() => bindings(chain(below('A', 'Thing', { handle: handle.handle })))).toThrow(
      /no `handle` of its own/,
    )
    expect(() => bindings(chain(below('A', 'nf')))).toThrow(/both must be handle classes/)
  })

  it('make one constructor, for the top of the chain, and register each class with its parent', () => {
    const lua = bindingsLua(classes, api)
    expect(lua).toContain(
      'function new.Entity(id)\n  return prim["handles.new"]("Entity", id)\nend',
    )
    expect(lua).not.toContain('function new.Mob(')
    expect(lua).toContain('local Mob = class("Mob", "Living")')
    expect(lua).toContain('local Living = class("Living", "Entity")')
  })

  it("list a class's functions with those up its chain, its own replacing theirs", () => {
    const names = classFunctions(api, cls('Mob')).map((it) => it.name)
    expect(names).toContain('set_target')
    expect(names).toContain('health')
    expect(names).toContain('teleport')
    expect(names.filter((it) => it === 'on')).toHaveLength(1)
    const player = classFunctions(api, cls('Player'))
    expect(player.map((it) => it.name)).not.toContain('set_target')
    // A Player's own `name` replaces an Entity's.
    expect(player.find((it) => it.name === 'name')?.returns).toEqual([{ type: 'string' }])
  })

  it("list a class's events with those up its chain, its own replacing theirs", () => {
    const mob = classEvents(api, cls('Mob'))
    expect(mob.find((it) => it.event.name === 'death')?.owner.name).toBe('Living')
    expect(mob.find((it) => it.event.name === 'damage')?.owner.name).toBe('Entity')
    expect(mob.find((it) => it.event.name === 'path_end')?.owner.name).toBe('Mob')
    const player = classEvents(api, cls('Player'))
    expect(player.find((it) => it.event.name === 'death')?.owner.name).toBe('Player')
    expect(player.find((it) => it.event.name === 'heal')?.owner.name).toBe('Living')
    expect(player.some((it) => it.event.name === 'path_end')).toBe(false)
  })

  it('give Kotlin a class per handle class, subclasses equal to their chain by key', () => {
    const kotlin = handlesKotlin(classes)
    expect(kotlin).toContain('open class Entity(val id: String) : LuaHandle {')
    expect(kotlin).toContain('open class Living(id: String) : Entity(id) {')
    expect(kotlin).toContain('class Mob(id: String) : Living(id) {')
    expect(kotlin).toContain(
      'final override fun equals(other: Any?): Boolean = other is Entity && other.id == id',
    )
    expect(kotlin).toContain(
      'object Codec : HandleCodec<Living>("Living", setOf("Living", "Mob", "Player"))',
    )
    expect(kotlin).toContain('"Living" to "Entity"')
    expect(primitivesKotlin(classes)).toContain('val self = call.self(1) as LuaHandle.Mob')
  })

  it('resolve an event on a class to the class that declares it, for the runtime', () => {
    const events = eventsKotlin(api)
    expect(events).toContain('"Mob" to mapOf(')
    expect(events).toMatch(/"Mob" to mapOf\([^\n]*"death" to LIVING_DEATH/)
    expect(events).toMatch(/"Player" to mapOf\([^\n]*"death" to PLAYER_DEATH/)
    const lua = schemaLua(api)
    const mob = lua.slice(lua.indexOf('  ["Mob"] = {'), lua.indexOf('  ["DroppedItem"] = {'))
    expect(mob).toContain('    ["death"] = { owner = "Living",')
    expect(mob).toContain('    ["path_end"] = { owner = "Mob",')
  })
})

describe('the event registry', () => {
  const evented: ApiSpec = {
    ...spec({ name: 'f', doc: 'd', params: [], returns: [] }),
    classes: [
      {
        ...handle,
        events: [
          { name: 'poke', doc: 'Poked.', payload: 'PokeEvent', cancellable: true, bubbles: true },
          { name: 'rename', doc: 'Renamed.', payload: 'RenameEvent', writable: ['name', 'drops'] },
          { name: 'tick', doc: 'Ticked.', options: ['every'] },
        ],
      },
    ],
    shapes: [
      shape(
        'PokeEvent',
        [
          { name: 'by', type: 'Thing', doc: '' },
          { name: 'where', type: 'Vec3?', doc: '' },
          { name: 'item', type: 'Item?', doc: '' },
        ],
        { extends: 'Event' },
      ),
      shape(
        'RenameEvent',
        [
          { name: 'name', type: 'string?', doc: '' },
          { name: 'drops', type: 'Item[]', doc: '' },
        ],
        { extends: 'Event' },
      ),
    ],
    values: [{ name: 'Vec3', doc: 'v', fields: [], functions: [], operators: [] }],
  }

  it('types every payload with its codec, writable fields var and read back by theirs', () => {
    const { shapes } = generated(evented)
    expect(shapes).toContain(
      'data class PokeEvent(val by: LuaHandle.Thing, val where: Vec3? = null, val item: ItemData? = null) : LuaEvent {',
    )
    expect(shapes).toContain(
      'data class RenameEvent(var name: String? = null, var drops: List<ItemData>) : LuaEvent {',
    )
    expect(shapes).toContain('"drops" -> drops = Codec.dropsCodec.read(call, index, "event.drops")')
    expect(shapes).toContain('val dropsCodec = LuaList(LuaCodecs.ITEM)')
  })

  it('registers each event with its owner, payload codec and flags', () => {
    const { events, schema: lua } = generated(evented)
    expect(events).toContain(
      'val THING_POKE = EventType<PokeEvent>("Thing", "poke", payload = PokeEvent.Codec, cancellable = true, bubbles = true, writable = emptyList(), local = false, since = null)',
    )
    expect(events).toContain(
      'val THING_TICK = EventType<NoPayload>("Thing", "tick", payload = null',
    )
    expect(events).toContain('writable = listOf("name", "drops")')
    expect(lua).toContain(
      '["rename"] = { owner = "Thing", cancellable = false, options = {}, writable = { name = true, drops = true } },',
    )
    expect(lua).toContain(
      '["tick"] = { owner = "Thing", cancellable = false, options = { every = true }, writable = {} },',
    )
  })

  it('refuses a writable field that is a function', () => {
    const bad: ApiSpec = {
      ...evented,
      shapes: evented.shapes.map((it) =>
        it.name === 'RenameEvent'
          ? {
              ...it,
              fields: [
                { name: 'name', type: 'fun()', doc: '' },
                { name: 'drops', type: 'string', doc: '' },
              ],
            }
          : it,
      ),
    }
    expect(() => bindings(bad)).toThrow(/a writable field can't be a function/)
  })
})

describe('version gates', () => {
  // Nothing in the spec needs a newer Minecraft than the oldest supported one today, so these
  // features and the table they're in are made up.
  let restore: () => void
  beforeAll(() => {
    restore = useFeatures({
      poking: { since: '27.1' },
      shaking: { since: '27.2' },
      hard_poking: { since: '27.3' },
    })
  })
  afterAll(() => restore())
  const gated: ApiSpec = {
    globals: [],
    classes: [
      {
        ...handle,
        functions: [
          {
            name: 'poke',
            doc: 'Pokes it.',
            params: [{ name: 'options', type: 'PokeOptions', doc: '', optional: true }],
            returns: [],
            since: 'poking',
          },
        ],
        events: [{ name: 'shake', doc: 'Shaken.', since: 'shaking' }],
      },
    ],
    surfaces: [],
    shapes: [
      shape('PokeOptions', [
        { name: 'hard', type: 'boolean?', doc: 'Harder.', since: 'hard_poking' },
        { name: 'soft', type: 'boolean?', doc: 'Softer.' },
      ]),
    ],
    removed: [],
  }

  it('lists every gated function, event and option field for the runtime', () => {
    expect(gatesKotlin(gated)).toContain(
      'val SPEC = VersionGates(functions = mapOf("Thing.poke" to "27.1"), events = mapOf("Thing.shake" to "27.2"), options = mapOf("PokeOptions.hard" to "27.3"))',
    )
    expect(gatesKotlin(api)).toContain('val SPEC = VersionGates(')
  })

  it("names an option table's shape in its codec, so its gated fields can be checked", () => {
    expect(generated(gated).shapes).toContain(
      'object Codec : ShapeCodec<PokeOptions>("PokeOptions") {',
    )
  })

  it('shows the version in the docs and stubs', () => {
    const pages = referencePages(gated)
    expect(pages.get('thing.md')).toContain('*Since Minecraft 27.1.*')
    expect(pages.get('events.md')).toContain('Shaken. *Since Minecraft 27.2.*')
    expect(pages.get('events.md')).toContain('Harder. *Since Minecraft 27.3.*')
    const stubs = luals(gated)
    expect(stubs).toContain('--- Since Minecraft 27.1.')
    expect(stubs).toContain('---@field hard boolean? Harder. (Since Minecraft 27.3.)')
  })
})

describe('requirements', () => {
  const ban = { name: 'ban', doc: 'Bans it.', params: [], returns: [] }

  it('are checked by the generated primitive before anything is read, naming the call', () => {
    const out = generated(spec({ ...ban, requires: 'moderation' }))
    expect(out.primitives).toContain(
      [
        '        val self = call.self(1) as LuaHandle.Thing',
        '        marshal.requires("moderation", "Thing:ban")',
        '        api.thing.ban(self)',
      ].join('\n'),
    )
  })

  it('say how to declare them in the docs and stubs, and list them in the index', () => {
    const it = spec({ ...ban, requires: 'moderation' })
    const needs =
      'Needs `"requires": { "moderation": true }` in `netherforge.json`: without it, calling it is an error.'
    expect(referencePages(it).get('thing.md')).toContain(`*${needs}*`)
    expect(referencePages(it).get('index.md')).toContain('| `moderation` |')
    expect(luals(it)).toContain(`--- ${needs}`)
  })

  it("say how each kind is declared, a plugin's by its name", () => {
    expect(luals(spec({ ...ban, requires: 'plugin:vault' }))).toContain(
      '--- Needs `"requires": { "plugins": ["vault"] }` in `netherforge.json`',
    )
    expect(luals(spec({ ...ban, requires: 'db' }))).toContain('`"requires": { "db": true }`')
    expect(luals(spec({ ...ban, requires: 'http' }))).toContain('`"requires": { "http": [...] }`')
    const out = generated(spec({ ...ban, requires: 'plugin:vault' }))
    expect(out.primitives).toContain('marshal.requires("plugin:vault", "Thing:ban")')
  })

  it('refuse what is no requirement, and a hand-written function', () => {
    expect(() => bindings(spec({ ...ban, requires: 'plugin:' as never }))).toThrow(
      /isn't a requirement/,
    )
    expect(() => bindings(spec({ ...ban, requires: 'plugin:Vault' as never }))).toThrow(
      /isn't a requirement/,
    )
    expect(() => bindings(spec({ ...ban, requires: 'moderation', impl: 'lua' }))).toThrow(
      /not on impl lua/,
    )
  })
})

describe('hand-written functions', () => {
  const lua = (fn: LuaClass['functions'][number], extra: Partial<LuaClass> = {}) =>
    generated(spec({ ...fn, impl: 'lua' }, extra)).lua

  it('check self and every argument by its type, then call the body as a tail call', () => {
    const out = lua({
      name: 'use',
      doc: 'd',
      params: [
        { name: 'name', type: 'string', doc: '' },
        { name: 'count', type: 'integer', doc: '', optional: true },
        { name: 'other', type: 'Thing', doc: '' },
        { name: 'maybe', type: 'Thing?', doc: '' },
        { name: 'at', type: 'Vec3', doc: '' },
        { name: 'mode', type: '"loud"|"quiet"', doc: '' },
        { name: 'callback', type: 'fun(x: number)', doc: '' },
        { name: 'list', type: 'number[]', doc: '' },
        { name: 'anything', type: 'any', doc: '' },
        { name: 'either', type: 'Thing|Vec3', doc: '' },
      ],
      returns: [],
    })
    expect(out).toContain(
      'function Thing.use(self, name, count, other, maybe, at, mode, callback, list, anything, either)',
    )
    const body = out.slice(out.indexOf('function Thing.use('))
    for (const line of [
      '  self_of(self, "Thing")',
      '  want(name, "string", "name")',
      '  count = want_integer_opt(count, "count")',
      '  want_handle(other, "Thing", "other")',
      '  if maybe ~= nil then\n    want_handle(maybe, "Thing", "maybe")\n  end',
      '  vector_arg(at, "at", 2)',
      '  choice.want(mode, Thing_use_mode, "mode")',
      '  want(callback, "function", "callback")',
      '  want(list, "table", "list")',
      '  return hand.Thing.use(self, name, count, other, maybe, at, mode, callback, list, anything, either)',
    ])
      expect(body).toContain(line)
    // A union the grammar can't check is the body's to check; any value needs none.
    expect(body).not.toContain('"anything"')
    expect(body).not.toContain('"either"')
    // Each choice set is built once, as a lookup.
    expect(out).toContain('local Thing_use_mode = choice.set({ "loud", "quiet" })')
  })

  it('check a table as its shape, and an alias as the string it is', () => {
    const it: ApiSpec = {
      ...spec({
        name: 'open',
        impl: 'lua',
        doc: 'd',
        params: [
          { name: 'options', type: 'OpenOptions', doc: '', optional: true },
          { name: 'text', type: 'Text', doc: '' },
        ],
        returns: [],
      }),
      shapes: [shape('OpenOptions', [{ name: 'size', type: 'integer', doc: '' }])],
      aliases: [{ name: 'Text', type: 'string', doc: 'MiniMessage.' }],
    }
    const out = bindingsLua(bindings(it).classes, it)
    expect(out).toContain(
      '  if options ~= nil then\n    check_shape(options, "options", "OpenOptions")\n  end',
    )
    expect(out).toContain('  want(text, "string", "text")')
    expect(schemaLua(it)).toContain('  OpenOptions = {')
  })

  it('on a namespace pass the calling scope to the body; a vararg goes through as it is', () => {
    const it: ApiSpec = {
      ...spec({ name: 'f', doc: 'd', params: [], returns: [] }),
      classes: [
        {
          name: 'nf',
          doc: 'nf',
          methods: false,
          fields: [],
          functions: [
            {
              name: 'start',
              impl: 'lua',
              doc: 'd',
              params: [
                { name: 'callback', type: 'fun()', doc: '' },
                { name: '...', type: 'any', doc: '' },
              ],
              returns: [],
            },
          ],
        },
        {
          name: 'nf.math',
          doc: 'math',
          methods: false,
          fields: [],
          functions: [
            {
              name: 'twice',
              impl: 'lua',
              doc: 'd',
              params: [{ name: 'x', type: 'number', doc: '' }],
              returns: [{ type: 'number' }],
            },
          ],
        },
      ],
    }
    const out = bindingsLua(bindings(it).classes, it)
    expect(out).toContain(
      '  function nf.start(callback, ...)\n    want(callback, "function", "callback")\n    return hand.nf.start(scope, callback, ...)\n  end',
    )
    expect(out).toContain('return hand["nf.math"].twice(scope, x)')
  })

  it('on a value type check self as that value, and go in the values table', () => {
    const it: ApiSpec = {
      ...spec({ name: 'f', doc: 'd', params: [], returns: [] }),
      values: [
        {
          name: 'Vec3',
          doc: 'v',
          fields: [],
          operators: [],
          functions: [
            {
              name: 'dot',
              impl: 'lua',
              doc: 'd',
              params: [{ name: 'other', type: 'Vec3', doc: '' }],
              returns: [{ type: 'number' }],
            },
          ],
          library: {
            name: 'vec3',
            doc: 'l',
            call: { name: 'vec3', impl: 'lua', doc: 'c', params: [], returns: [{ type: 'Vec3' }] },
            fields: [],
            functions: [
              {
                name: 'unit',
                impl: 'lua',
                doc: 'd',
                params: [{ name: 'yaw', type: 'number', doc: '' }],
                returns: [{ type: 'Vec3' }],
              },
            ],
          },
        },
      ],
    }
    const out = bindingsLua(bindings(it).classes, it)
    expect(out).toContain(
      'function values.Vec3.dot(self, other)\n  value_self(self, "Vec3")\n  vector_arg(other, "other", 2)\n  return value_body.Vec3.dot(self, other)\nend',
    )
    expect(out).toContain('function vec3_functions.unit(yaw)')
    expect(out).toContain('  vec3 = vec3_functions,')
  })

  it("can't take a parameter the wrapper's own names need", () => {
    const fn = { name: 'f', impl: 'lua' as const, doc: 'd', returns: [] }
    expect(() => lua({ ...fn, params: [{ name: 'hand', type: 'number', doc: '' }] })).toThrow(
      /a parameter can't be called hand/,
    )
    expect(() => lua({ ...fn, params: [{ name: 'self', type: 'number', doc: '' }] })).toThrow(
      /a parameter can't be called self/,
    )
  })
})

describe('handle keys', () => {
  it("get an accessor from each root class's HandleSpec, typed by what it is made of", () => {
    const it: ApiSpec = {
      ...spec({ name: 'f', doc: 'd', params: [], returns: [] }),
      classes: [
        {
          ...handle,
          handle: {
            key: [
              { name: 'world', type: 'string' },
              { name: 'index', type: 'integer' },
            ],
          },
        },
        { ...handle, name: 'Part', handle: undefined, extends: 'Thing' },
      ],
    }
    const out = bindingsLua(bindings(it).classes, it)
    expect(out).toContain(
      '---@param handle table\n---@return string world\n---@return integer index\nfunction keys.Thing(handle)\n  return prim["handles.key"](handle)\nend',
    )
    // One for the class at the top of the chain, as for the constructor.
    expect(out).not.toContain('function keys.Part(')
  })
})

describe('functions that wait', () => {
  const wait = { name: 'pause', doc: 'Pauses.', params: [], returns: [], waits: true }

  it('are async to LuaLS and say they need a task', () => {
    const it = spec({ ...wait, impl: 'lua' })
    expect(luals(it)).toContain('---@async\nfunction Thing:pause() end')
    expect(luals(it)).toContain("--- Only in a task's own code (`nf.task`)")
    expect(referencePages(it).get('thing.md')).toContain("*Only in a task's own code (`nf.task`)")
  })

  it('are hand-written, and never async', () => {
    expect(() => bindings(spec(wait))).toThrow(/hand-written in the prelude/)
  })
})

describe('saveable handle classes', () => {
  it('give the prelude their tags, each at the top of its chain', () => {
    const it: ApiSpec = { ...spec({ name: 'f', doc: 'd', params: [], returns: [] }) }
    it.classes = [{ ...it.classes[0]!, saveable: true }]
    expect(generated(it).schema).toContain('local saved_handles = {\n  Thing = "thing",\n}')
    expect(luals(it)).toContain('--- A `data()` table, `Item.data` and `nf.json` can keep one')
    const below: ApiSpec = {
      ...it,
      classes: [
        handle,
        { ...handle, name: 'Part', handle: undefined, extends: 'Thing', saveable: true },
      ],
    }
    expect(() => bindings(below)).toThrow(/only a handle class at the top of its chain/)
  })
})

describe('command argument types', () => {
  const typed: ApiSpec = {
    ...spec({ name: 'f', doc: 'd', params: [], returns: [] }),
    commandArguments: [
      { name: 'word', doc: 'one word', value: 'string', reading: 'word' },
      { name: 'things', doc: 'some things', value: 'Thing[]', reading: 'entities' },
      { name: 'place', doc: 'a place', value: 'Thing', reading: 'word', names: true },
    ],
  }

  it("are the runtime's ArgumentType, with what the server reads each as", () => {
    const enum_ = argumentTypesKotlin(bindings(typed))
    expect(enum_).toContain('    WORD("word", ArgumentReading.WORD, names = false),')
    expect(enum_).toContain('    PLACE("place", ArgumentReading.WORD, names = true);')
    expect(enum_).toContain('/** `"things"`: some things. */')
  })

  it("cross their handler's value, and a default, by its type's codec", () => {
    const codecs = argumentCodecsKotlin(bindings(typed))
    expect(codecs).toContain('        ArgumentType.WORD -> LuaCodecs.STRING')
    expect(codecs).toContain('        ArgumentType.THINGS -> CODEC_1')
    expect(codecs).toContain('private val CODEC_1 = LuaList(LuaHandle.Thing.Codec)')
    expect(codecs).toContain('        ArgumentType.PLACE -> LuaHandle.Thing.Codec')
  })

  it('are the type field of a command argument, each with its line in its doc', () => {
    const shape = api.shapes.find((it) => it.name === 'CommandArgument')!
    const type = shape.fields.find((it) => it.name === 'type')!
    expect(type.type.split('|')).toEqual(api.commandArguments!.map((it) => `"${it.name}"`))
    expect(type.doc).toContain('`"player"`: an online player by name')
    expect(type.doc).toContain(', a `Player`.')
  })
})
