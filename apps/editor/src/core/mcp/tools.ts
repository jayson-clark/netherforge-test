/**
 * The editor's MCP server for coding agents: the official SDK's `McpServer`,
 * with its tools. The Rust side is only the HTTP pipe (`src-tauri/src/mcp/`)
 * and `transport.ts` carries its messages here, so every call is answered
 * from the same stores the editor's own panels read: an agent sees exactly
 * what the user sees (Problems, Console, Instances) and acts through the same
 * paths (hot reload, the server's console, spawning).
 *
 * Each tool's arguments are a zod schema: the SDK publishes it as the tool's
 * JSON Schema and checks every call against it before the tool runs, so a
 * tool gets typed, valid arguments and only checks what a schema can't say.
 *
 * What files say is the agent's to read and write directly; these tools are
 * for what files can't tell it: is it valid, does it run, what happened.
 */
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import type { CallToolResult, ToolAnnotations } from '@modelcontextprotocol/sdk/types.js'
import { z } from 'zod'
import { BackendError, type Backend } from '@/core/backend/types'
import { BLOCK_REGISTRY } from '@netherforge/format/constants'
import type { BotAction, InstanceInfo, Problem, ReloadResult } from '@netherforge/format/types'
import type { ConsoleLine, RunStore } from '@/core/store/run'
import type { WorkspaceStore } from '@/core/store/workspace'
import { BackendTransport } from './transport'

export interface ToolContext {
  backend: Backend
  workspace: WorkspaceStore
  run: RunStore
  /** Waits; tests pass a faster one. */
  sleep?: (ms: number) => Promise<void>
}

/** A failure the agent should read and act on: becomes `isError: true`, not a protocol error. */
export class ToolError extends Error {}

const INSTRUCTIONS =
  "Tools for the NetherForge editor the user has open: the project's problems (the editor's validation and the dev server's), hot reload onto the running dev server, its console (script errors with file and line), server commands, spawning centities and game-data lookups. Project files are plain files: edit them directly, then reload and read the console. The file format and Lua API for this exact version are in the project's .netherforge/docs/README.md."

const sleep = (ctx: ToolContext, ms: number) =>
  (ctx.sleep ?? ((it) => new Promise((resolve) => setTimeout(resolve, it))))(ms)

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

// ---- shared state -----------------------------------------------------------------

function requireProject(ctx: ToolContext) {
  const project = ctx.workspace.getState().project
  if (!project) throw new ToolError('No project is open in the NetherForge editor.')
  return project
}

function requireBridge(ctx: ToolContext) {
  requireProject(ctx)
  if (!ctx.run.getState().server.bridgeConnected) {
    throw new ToolError(
      "The dev server isn't running (or its plugin hasn't connected yet). Start it with start_server, then try again.",
    )
  }
}

const LEVELS = ['debug', 'info', 'warn', 'error'] as const
type Level = (typeof LEVELS)[number]

function lastLineId(ctx: ToolContext): number {
  return ctx.run.getState().console.last?.id ?? 0
}

function linesSince(ctx: ToolContext, since: number, minLevel: Level = 'debug'): ConsoleLine[] {
  const min = LEVELS.indexOf(minLevel)
  return ctx.run
    .getState()
    .console.since(since)
    .filter((line) => LEVELS.indexOf(line.level) >= min)
}

/** Lets what a server action printed arrive on the console before reporting it. */
const SETTLE_MS = 600

function problemCounts(problems: Problem[]) {
  const errors = problems.filter((it) => it.severity === 'error').length
  return { errors, warnings: problems.length - errors }
}

// ---- registering ------------------------------------------------------------------

/** Agents get the value as JSON; a [ToolError] as its message, anything else as the editor's failure. */
function answer<A>(
  ctx: ToolContext,
  run: (args: A, ctx: ToolContext) => Promise<unknown>,
): (args: A) => Promise<CallToolResult> {
  return async (args) => {
    try {
      const value = await run(args, ctx)
      return { content: [{ type: 'text', text: JSON.stringify(value, null, 2) }] }
    } catch (error) {
      const text =
        error instanceof ToolError ? error.message : `The editor failed: ${errorText(error)}`
      return { content: [{ type: 'text', text }], isError: true }
    }
  }
}

/** Nothing here reaches beyond the editor and its dev server. */
const local = (annotations: ToolAnnotations): ToolAnnotations => ({
  ...annotations,
  openWorldHint: false,
})

const READ_ONLY = local({ readOnlyHint: true })

/** Every tool, in the order `tools/list` gives them. */
function registerTools(server: McpServer, ctx: ToolContext) {
  server.registerTool(
    'get_status',
    {
      title: 'Editor and dev server status',
      description:
        "What's open in the NetherForge editor and whether its dev server is running: the project, its Minecraft version, the server's phase, players online, live centities, problem counts, and files with unsaved edits in the editor (don't write those without telling the user).",
      inputSchema: {},
      annotations: READ_ONLY,
    },
    answer(ctx, async (_args, ctx) => {
      const ws = ctx.workspace.getState()
      const run = ctx.run.getState()
      return {
        editor: ws.appInfo?.version ?? null,
        project: ws.project
          ? { name: ws.project.name, root: ws.project.root, minecraft: ws.minecraft }
          : null,
        server: run.server,
        players: run.players,
        liveInstances: run.liveInstances,
        gameData: ws.gameData !== null,
        problems: {
          editor: problemCounts(ws.problems),
          server: problemCounts(run.serverProblems),
        },
        unsavedFiles: Object.values(ws.docs)
          .filter((doc) => doc.dirty)
          .map((doc) => doc.path)
          .sort(),
      }
    }),
  )

  server.registerTool(
    'get_problems',
    {
      title: 'Project problems',
      description:
        "Every problem in the project, as the editor's Problems panel shows them: `editor` is validation of the files (re-read from disk first, so your latest writes count) against the game's data; `server` is what the dev server's plugin reported when it last loaded the project. Each has file, message, and a JSON path or line. Run this after editing and fix every error.",
      inputSchema: {
        file: z
          .string()
          .optional()
          .describe('Only problems in this project path, or under it if it is a folder.'),
      },
      annotations: READ_ONLY,
    },
    answer(ctx, async ({ file }, ctx) => {
      requireProject(ctx)
      await ctx.workspace.getState().handleFilesChanged([], true)
      await ctx.workspace.getState().validateNow()
      const keep = (problem: Problem) =>
        !file || problem.file === file || problem.file.startsWith(`${file.replace(/\/$/, '')}/`)
      const editor = ctx.workspace.getState().problems.filter(keep)
      const server = ctx.run.getState().serverProblems.filter(keep)
      return { editor, server, ...problemCounts([...editor, ...server]) }
    }),
  )

  server.registerTool(
    'reload',
    {
      title: 'Hot reload onto the dev server',
      description:
        "Reloads project files onto the running dev server, as saving in the editor does: centities move their live instances onto the new definition, modules restart with what requires them, resource packs rebuild. Pass the paths you changed, or nothing to reload the whole project. Advancements are learned only as the server starts: when a reload says it must restart, the dev server is restarted (`restarted`: true). Returns each resource's result plus any errors the server logged while reloading (script errors have file and line). A resource that fails keeps its old version running.",
      inputSchema: {
        paths: z
          .array(z.string())
          .optional()
          .describe('Project paths, e.g. ["modules/shop/init.lua"]. Omit for the whole project.'),
      },
      annotations: local({ readOnlyHint: false, destructiveHint: false, idempotentHint: true }),
    },
    answer(ctx, async ({ paths: given }, ctx) => {
      requireBridge(ctx)
      const paths = given && given.length > 0 ? given : ['netherforge.json']
      const since = lastLineId(ctx)
      let result: ReloadResult
      try {
        result = await ctx.backend.bridgeRequest('reload', { paths })
      } catch (error) {
        throw new ToolError(`Reload failed: ${errorText(error)}`)
      }
      ctx.run.getState().append({
        kind: 'editor',
        level: 'info',
        text: `Reloaded ${paths.join(', ')} for a coding agent`,
      })
      // Something the server learns only as it starts changed: restart it, as a save in the editor does.
      if (result.restart) {
        await ctx.run.getState().restart()
        return {
          resources: result.resources,
          restarted: true,
          errors: [],
          serverProblems: ctx.run.getState().serverProblems,
        }
      }
      await sleep(ctx, SETTLE_MS)
      return {
        resources: result.resources,
        errors: linesSince(ctx, since, 'warn').filter((it) => it.kind !== 'editor'),
        serverProblems: ctx.run.getState().serverProblems,
      }
    }),
  )

  server.registerTool(
    'get_console',
    {
      title: 'Dev server console',
      description:
        "The dev server's console as the editor shows it: server output, the plugin's log lines and Lua script errors (with source file, line and traceback). Lines have increasing ids: pass the last id you saw as `since` to get only what's new.",
      inputSchema: {
        since: z.number().int().min(0).optional().describe('Only lines after this id.'),
        level: z.enum(LEVELS).optional().describe('The lowest level to include (default: info).'),
        limit: z
          .number()
          .int()
          .min(1)
          .max(500)
          .optional()
          .describe('At most this many of the newest matching lines (default: 100).'),
      },
      annotations: READ_ONLY,
    },
    answer(ctx, async ({ since = 0, level = 'info', limit = 100 }, ctx) => {
      const lines = linesSince(ctx, since, level)
      return {
        lines: lines.slice(-limit),
        truncated: lines.length > limit,
        lastId: lastLineId(ctx),
      }
    }),
  )

  server.registerTool(
    'run_command',
    {
      title: 'Run a server command',
      description:
        'Runs a command on the dev server\'s console, as an operator, e.g. "nf list" or "time set day". Returns what the console printed in the next moment. NetherForge\'s own commands are under /nf: spawn, list, find, kill, tp, reload, modules.',
      inputSchema: {
        command: z.string().trim().min(1).describe('The command, with or without a leading /.'),
      },
      annotations: local({ readOnlyHint: false, destructiveHint: true }),
    },
    answer(ctx, async ({ command }, ctx) => {
      requireProject(ctx)
      const { server } = ctx.run.getState()
      if (server.phase !== 'running') {
        throw new ToolError(
          `The dev server is ${server.phase}, not running. Start it with start_server first.`,
        )
      }
      const since = lastLineId(ctx)
      ctx.run
        .getState()
        .append({ kind: 'editor', level: 'info', text: `> ${command} (from a coding agent)` })
      try {
        if (server.bridgeConnected) await ctx.backend.bridgeRequest('command', { line: command })
        else await ctx.backend.serverCommand(command.replace(/^\//, ''))
      } catch (error) {
        throw new ToolError(errorText(error))
      }
      await sleep(ctx, SETTLE_MS)
      return { output: linesSince(ctx, since).filter((it) => it.kind !== 'editor') }
    }),
  )

  server.registerTool(
    'spawn_centity',
    {
      title: 'Spawn a centity',
      description:
        'Spawns a centity on the dev server, next to a player (the first one online if none is named), and returns where. The id is its folder name under centities/.',
      inputSchema: {
        centity: z.string().min(1).describe('The centity id.'),
        player: z.string().optional().describe('Spawn next to this online player.'),
      },
      annotations: local({ readOnlyHint: false, destructiveHint: false }),
    },
    answer(ctx, async ({ centity, player }, ctx) => {
      requireBridge(ctx)
      let instance: InstanceInfo
      try {
        instance = await ctx.backend.bridgeRequest('spawn', {
          centity,
          ...(player ? { player } : {}),
        })
      } catch (error) {
        throw new ToolError(`Couldn't spawn ${centity}: ${errorText(error)}`)
      }
      ctx.run
        .getState()
        .append({ kind: 'editor', level: 'info', text: `Spawned ${centity} for a coding agent` })
      void ctx.run.getState().refreshInstances()
      return instance
    }),
  )

  server.registerTool(
    'list_instances',
    {
      title: 'Live centities',
      description:
        'Every centity instance alive on the dev server: uuid, centity id, world and position.',
      inputSchema: {},
      annotations: READ_ONLY,
    },
    answer(ctx, async (_args, ctx) => {
      requireBridge(ctx)
      try {
        return { instances: await ctx.backend.bridgeRequest('instances') }
      } catch (error) {
        throw new ToolError(errorText(error))
      }
    }),
  )

  server.registerTool(
    'lookup_game_data',
    {
      title: 'Look up Minecraft ids',
      description:
        "Searches the target Minecraft version's real ids, as the dev server exported them, in any of the game's registries: blocks (with their state properties and defaults), items, entity types, biomes, sounds, loot tables and the rest, or a registry's tags. Use it instead of guessing an id; validation rejects ids the game doesn't have.",
      inputSchema: {
        registry: z
          .string()
          .regex(/^[a-z0-9_.-]+:[a-z0-9_./-]+$/, 'registry is a namespaced id')
          .describe(
            'The registry, by its Minecraft name: "minecraft:block", "minecraft:item", "minecraft:entity_type", "minecraft:worldgen/biome", "minecraft:loot_table", …',
          ),
        tags: z
          .boolean()
          .optional()
          .describe("Search the registry's tags instead, each with the ids it holds."),
        query: z
          .string()
          .optional()
          .describe(
            'Part of the id, e.g. "oak_st" (case-insensitive). Omit to list from the start.',
          ),
        limit: z.number().int().min(1).max(200).optional().describe('Default 50.'),
      },
      annotations: READ_ONLY,
    },
    answer(ctx, async ({ registry, tags = false, query = '', limit = 50 }, ctx) => {
      requireProject(ctx)
      const { gameData, minecraft } = ctx.workspace.getState()
      if (!gameData) {
        throw new ToolError(
          `There's no game data for Minecraft ${minecraft ?? '?'} yet. It's exported the first time the dev server runs: start it with start_server.`,
        )
      }
      const ids = gameData.registries?.[registry]
      if (!ids) {
        const names = Object.keys(gameData.registries ?? {}).sort()
        throw new ToolError(
          `Minecraft ${gameData.minecraft} has no registry "${registry}". It has: ${names.join(', ')}.`,
        )
      }
      const registryTags = gameData.tags?.[registry] ?? {}
      const lowerQuery = query.toLowerCase()
      // Best first: the id itself (with or without its namespace), then ids whose
      // name starts with the query, then the rest, each alphabetically.
      const rank = (id: string) => {
        const lower = id.toLowerCase()
        const name = lower.slice(lower.indexOf(':') + 1)
        if (lower === lowerQuery || name === lowerQuery) return 0
        return name.startsWith(lowerQuery) || lower.startsWith(lowerQuery) ? 1 : 2
      }
      const matches = (tags ? Object.keys(registryTags) : ids)
        .filter((id) => id.toLowerCase().includes(lowerQuery))
        .sort((a, b) => rank(a) - rank(b) || a.localeCompare(b))
      const shown = matches.slice(0, limit)
      return {
        minecraft: gameData.minecraft,
        total: matches.length,
        results: tags
          ? shown.map((id) => ({ id: `#${id}`, values: registryTags[id] }))
          : registry === BLOCK_REGISTRY
            ? shown.map((id) => ({ id, ...(gameData.blocks?.[id] ?? {}) }))
            : shown.map((id) => ({ id })),
      }
    }),
  )

  server.registerTool(
    'start_server',
    {
      title: 'Start the dev server',
      description:
        "Starts the editor's dev server for the open project and waits until its plugin connects (a first start can take a minute or two while Java and Paper download). Needs the Minecraft EULA accepted in the editor; this tool never accepts it.",
      inputSchema: {},
      annotations: local({ readOnlyHint: false, destructiveHint: false, idempotentHint: true }),
    },
    answer(ctx, async (_args, ctx) => {
      requireProject(ctx)
      const run = ctx.run.getState()
      if (run.server.bridgeConnected) return { server: run.server, alreadyRunning: true }
      const eula = await run.refreshEula()
      if (!eula.accepted) {
        throw new ToolError(
          "The Minecraft EULA hasn't been accepted. Ask the user to start the dev server once in the NetherForge editor, which shows it.",
        )
      }
      const since = lastLineId(ctx)
      if (run.server.phase === 'stopped' || run.server.phase === 'crashed') await run.start()
      const deadline = Date.now() + START_WAIT_MS
      for (;;) {
        const { server } = ctx.run.getState()
        if (server.bridgeConnected) return { server }
        if (server.phase === 'stopped' || server.phase === 'crashed') {
          throw new ToolError(
            `The dev server ${server.phase === 'crashed' ? 'crashed' : "didn't start"}${server.message ? `: ${server.message}` : ''}. Console:\n${linesSince(
              ctx,
              since,
              'warn',
            )
              .map((it) => it.text)
              .join('\n')}`,
          )
        }
        if (Date.now() > deadline) {
          return { server, note: 'Still starting. Check again with get_status.' }
        }
        await sleep(ctx, 500)
      }
    }),
  )

  server.registerTool(
    'stop_server',
    {
      title: 'Stop the dev server',
      description: "Stops the editor's dev server (players on it are disconnected).",
      inputSchema: {},
      annotations: local({ readOnlyHint: false, destructiveHint: true, idempotentHint: true }),
    },
    answer(ctx, async (_args, ctx) => {
      requireProject(ctx)
      await ctx.run.getState().stop()
      return { server: ctx.run.getState().server }
    }),
  )

  server.registerTool(
    'open_in_editor',
    {
      title: 'Show a file in the editor',
      description:
        'Opens a project file in the NetherForge editor for the user, in its visual editor where it has one, optionally at a line or a JSON path (e.g. $.nodes.top.display). Use it to show the user what you changed or what needs their eyes.',
      inputSchema: {
        path: z.string().min(1).describe('A project path, e.g. centities/tower/centity.json.'),
        line: z.number().int().min(1).optional(),
        jsonPath: z.string().optional(),
      },
      annotations: local({ readOnlyHint: false, destructiveHint: false, idempotentHint: true }),
    },
    answer(ctx, async ({ path, line, jsonPath }, ctx) => {
      requireProject(ctx)
      await ctx.workspace.getState().handleFilesChanged([path])
      if (!ctx.workspace.getState().files.includes(path))
        throw new ToolError(`There's no ${path} in the project.`)
      await ctx.workspace.getState().openFile(path, { line, jsonPath })
      return { opened: path }
    }),
  )

  registerBotTools(server, ctx)
}

/** Long enough for a first start (Java, Paper, world generation), short of the backend's 120 s. */
const START_WAIT_MS = 100_000

// ---- bots ---------------------------------------------------------------------------

/** What an action can be: each `type` with its fields, for the schema and the description. */
const BOT_ACTIONS = [
  'teleport {x, y, z, world?, yaw?, pitch?}: moved there by the server, as /tp',
  'look {yaw, pitch}: yaw 0 is south, 90 west; pitch 90 is straight down',
  'look_at {x, y, z}',
  'walk_to {x, z, sprint?, timeoutTicks? (default 200, at most 500)}: walks in a straight line, jumping up steps; answered on arrival, an error if it gets stuck',
  'jump',
  'sneak {on}',
  'sprint {on}',
  'chat {message}',
  'command {line}: run as this player, with or without the /',
  'interact {entity? | centity? + node?, hand?}: right-click an entity by UUID, a centity instance (by its UUID; node defaults to its first clickable one), or with neither whatever it looks at. Must be within 3 blocks',
  'attack {entity? | centity? + node?}: left-click, chosen the same way',
  'swing: left-click the air',
  'use_item {hand?}: right-click with the held item',
  'release_item: let go of right-click while using an item (a drawn bow shoots, a shield lowers)',
  'fly {on}: start or stop flying, as a double tap of jump (only for a player who may fly)',
  'edit_sign {x, y, z, lines, front?}: write a sign, as the sign editor does; place or right-click it first',
  'click_button {button}: press a button of the open menu (an enchanting table offer 0-2, a stonecutter recipe)',
  'use_block {x, y, z, face? (up, down, north, south, east, west), hand?}: right-click a block (place, open, press); within 4.5 blocks',
  'break_block {x, y, z, face?}: mine it until it breaks, as long as that takes',
  'select_slot {slot 0-8}',
  'swap_hands',
  'drop {all?}',
  'click_slot {slot, click? (left, right, shift_left, shift_right, middle, drop, drop_all, double, hotbar, off_hand), hotbar?}: in the open menu (its slots first, then the inventory) or, with none open, the inventory',
  'drag {slots, button? (left, right)}: drag the cursor stack across slots',
  'close_menu',
  'dialog_button {button? (its label) | index?, inputs? {key: value}}: press a button of the dialog on screen, setting inputs first',
  'dialog_close: Escape on the dialog (its exit action)',
  'respawn',
]

const BOT_ACTION_TYPES = BOT_ACTIONS.map((it) => it.split(/[ :]/)[0]!) as [string, ...string[]]

const botName = (description = 'The bot.') =>
  z
    .string()
    .regex(/^[A-Za-z0-9_]{3,16}$/, 'name is 3 to 16 letters, digits or _')
    .describe(description)

async function botRequest<T>(ctx: ToolContext, request: () => Promise<T>): Promise<T> {
  requireBridge(ctx)
  try {
    return await request()
  } catch (error) {
    // Bots are a bridge extension: a server without them doesn't know `bots/…`.
    if (error instanceof BackendError && error.code === 'unknownMethod')
      throw new ToolError(
        "This dev server has no bots (the plugin doesn't offer the bots extension).",
      )
    throw new ToolError(errorText(error))
  }
}

/** What happened after a bot did something: its events, and what the console warned about (script errors) meanwhile. */
async function botAftermath(ctx: ToolContext, name: string, since: number, consoleSince: number) {
  const { events } = await botRequest(ctx, () =>
    ctx.backend.bridgeRequest('bots/events', { name, since }),
  )
  return {
    events,
    errors: linesSince(ctx, consoleSince, 'warn').filter((it) => it.kind !== 'editor'),
  }
}

const BOT_JOIN_INPUT = z
  .object({
    name: botName('Its player name: 3 to 16 letters, digits or _.'),
    x: z.number().optional(),
    y: z.number().optional(),
    z: z.number().optional(),
    world: z.string().optional().describe('With x/y/z; the default world otherwise.'),
    resourcePack: z
      .enum(['accept', 'decline', 'fail', 'ignore'])
      .optional()
      .describe(
        'How it answers the resource pack: accept (default; it downloads it and checks its hash), decline, fail (accept, then report a failed download), ignore.',
      ),
  })
  .refine(
    ({ x, y, z }) =>
      [x, y, z].every((it) => it === undefined) || [x, y, z].every((it) => it !== undefined),
    { message: 'give all of x, y and z, or none' },
  )

function registerBotTools(server: McpServer, ctx: ToolContext) {
  server.registerTool(
    'bot_join',
    {
      title: 'Join a bot player',
      description:
        'Joins a bot to the dev server: a fake player that is a real player to the server (it joins through the normal login, fires player_join, gets the resource pack, can be clicked, hidden from, sent menus and dialogs). Use bots to try anything only a player can: commands, clicking centities, menus, dialogs, sidebars, boss bars, titles, chat. Returns where it joined and what it was sent while joining (welcome messages, a dialog, the pack). It joins where a player of that name last was, or at x/y/z.',
      inputSchema: BOT_JOIN_INPUT,
      annotations: local({ readOnlyHint: false, destructiveHint: false }),
    },
    answer(ctx, async ({ name, x, y, z, world, resourcePack = 'accept' }, ctx) => {
      const consoleSince = lastLineId(ctx)
      const joined = await botRequest(ctx, () =>
        ctx.backend.bridgeRequest('bots/join', {
          name,
          ...(x !== undefined && y !== undefined && z !== undefined
            ? { at: { x, y, z, ...(world ? { world } : {}) } }
            : {}),
          resourcePack,
        }),
      )
      ctx.run
        .getState()
        .append({ kind: 'editor', level: 'info', text: `Bot ${name} joined for a coding agent` })
      await sleep(ctx, SETTLE_MS)
      return { bot: joined, ...(await botAftermath(ctx, name, 0, consoleSince)) }
    }),
  )

  server.registerTool(
    'bot_act',
    {
      title: 'Have a bot do something',
      description: `Has a bot do one thing, as a player's client would (every check and event fires as for a person), then returns where it is, the events it got since (chat, titles, menus and dialogs opening, sounds, ...) and any warnings or script errors the server logged meanwhile. \`action\` is an object with a \`type\`:\n- ${BOT_ACTIONS.join('\n- ')}`,
      inputSchema: {
        name: botName(),
        action: z
          .looseObject({ type: z.enum(BOT_ACTION_TYPES) })
          .describe('What to do: { "type": "<action>", ...its fields } (see the description).'),
        settleMs: z
          .number()
          .int()
          .min(0)
          .max(10000)
          .optional()
          .describe('How long to wait for what follows before answering (default 600).'),
      },
      annotations: local({ readOnlyHint: false, destructiveHint: true }),
    },
    answer(ctx, async ({ name, action, settleMs = SETTLE_MS }, ctx) => {
      const consoleSince = lastLineId(ctx)
      // The plugin checks each action's own fields, and says what's wrong.
      const result = await botRequest(ctx, () =>
        ctx.backend.bridgeRequest('bots/act', { name, action: action as BotAction }),
      )
      await sleep(ctx, settleMs)
      return { result, ...(await botAftermath(ctx, name, result.since, consoleSince)) }
    }),
  )

  server.registerTool(
    'bot_state',
    {
      title: "What a bot's screen shows",
      description:
        "Everything a bot's screen shows now and its state on the server: position, health, game mode, inventory, the open menu (slots and items), the dialog on screen (inputs and buttons), boss bars, the sidebar, title and action bar, resource packs, and the entities its client knows of within 16 blocks (an entity hidden from it isn't there). `events` is the sequence number of its next event.",
      inputSchema: { name: botName() },
      annotations: READ_ONLY,
    },
    answer(ctx, async ({ name }, ctx) =>
      botRequest(ctx, () => ctx.backend.bridgeRequest('bots/state', { name })),
    ),
  )

  server.registerTool(
    'bot_events',
    {
      title: 'What a bot was sent',
      description:
        "A bot's events in order, from sequence number `since`: chat, action_bar, title, subtitle, menu_open, menu_close, dialog, dialog_close, resource_pack, sound, boss_bar, teleport, death, respawn, kicked. Pass the `next` you got to see only what's new.",
      inputSchema: {
        name: botName(),
        since: z
          .number()
          .int()
          .min(0)
          .optional()
          .describe('From this sequence number (default 0).'),
        types: z
          .array(z.string())
          .optional()
          .describe('Only these event types, e.g. ["chat", "dialog"].'),
      },
      annotations: READ_ONLY,
    },
    answer(ctx, async ({ name, since = 0, types }, ctx) => {
      const events = await botRequest(ctx, () =>
        ctx.backend.bridgeRequest('bots/events', { name, since }),
      )
      return types
        ? { ...events, events: events.events.filter((it) => types.includes(it.type)) }
        : events
    }),
  )

  server.registerTool(
    'bot_leave',
    {
      title: 'A bot leaves',
      description:
        'Disconnects a bot, as a player quitting (player_quit fires). Bots also leave when the server stops.',
      inputSchema: { name: botName() },
      annotations: local({ readOnlyHint: false, destructiveHint: false, idempotentHint: true }),
    },
    answer(ctx, async ({ name }, ctx) => {
      await botRequest(ctx, () => ctx.backend.bridgeRequest('bots/leave', { name }))
      ctx.run.getState().append({ kind: 'editor', level: 'info', text: `Bot ${name} left` })
      return { left: name }
    }),
  )
}

/** The editor's MCP server, with every tool registered. */
export function createMcpServer(ctx: ToolContext, version: string): McpServer {
  const server = new McpServer(
    { name: 'netherforge', title: 'NetherForge editor', version },
    { instructions: INSTRUCTIONS },
  )
  registerTools(server, ctx)
  return server
}

/** Serves agents from now on, over the backend's pipe; resolves to a function that stops. */
export async function connectMcp(ctx: ToolContext): Promise<() => void> {
  const version = await ctx.backend.appInfo().then(
    (info) => info.version,
    () => 'dev',
  )
  const server = createMcpServer(ctx, version)
  await server.connect(new BackendTransport(ctx.backend))
  return () => void server.close()
}
