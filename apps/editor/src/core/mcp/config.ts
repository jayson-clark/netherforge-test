/**
 * How agents find the editor's MCP server. `.mcp.json` in a project's root is
 * Claude Code's project-scoped config (it asks the user before trusting one);
 * other agents take the URL and the header in their own settings.
 *
 * The server wants the install's bearer token on every request, so the entry
 * carries it as a header. That makes `.mcp.json` this computer's, not the
 * project's: the project's `.gitignore` leaves it out.
 */
import type { Backend } from '@/core/backend/types'

export const MCP_CONFIG_FILE = '.mcp.json'
export const MCP_SERVER_NAME = 'netherforge'

/** Where this editor's MCP server listens and the token it wants (`Backend.mcpStatus`). */
export interface McpConnection {
  port: number
  token: string
}

export const mcpUrl = (port: number) => `http://127.0.0.1:${port}/mcp`

const authorization = (token: string) => `Bearer ${token}`

/** The `netherforge` entry for `.mcp.json`. */
export const netherforgeServer = ({ port, token }: McpConnection) => ({
  type: 'http',
  url: mcpUrl(port),
  headers: { Authorization: authorization(token) },
})

type Config = { mcpServers?: Record<string, unknown> } & Record<string, unknown>

/** `.mcp.json`'s text as an object, or null when it isn't one (it's the user's, so it's left alone). */
function parse(text: string): Config | null {
  try {
    const parsed: unknown = JSON.parse(text)
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null
    const servers = (parsed as Config).mcpServers
    if (servers !== undefined && (typeof servers !== 'object' || servers === null)) return null
    return parsed as Config
  } catch {
    return null
  }
}

/**
 * [existing] (`.mcp.json`'s text, or null) with the `netherforge` server
 * added, or brought up to date when this editor's port or token differ;
 * null when there's nothing to write: it's current already, or the file
 * doesn't parse. Everything else in the file is kept.
 */
export function withNetherforgeServer(
  existing: string | null,
  connection: McpConnection,
): string | null {
  const config = existing === null ? {} : parse(existing)
  if (config === null) return null
  const servers = config.mcpServers ?? {}
  const entry = netherforgeServer(connection)
  if (JSON.stringify(servers[MCP_SERVER_NAME]) === JSON.stringify(entry)) return null
  return (
    JSON.stringify({ ...config, mcpServers: { ...servers, [MCP_SERVER_NAME]: entry } }, null, 2) +
    '\n'
  )
}

/**
 * Brings the open project's `.mcp.json` up to date with this editor's port
 * and token, if it has a `netherforge` server (one cloned from elsewhere, or
 * written before the port changed); with [add], also adds one or creates the
 * file. Returns whether it wrote.
 */
export async function syncMcpConfig(
  backend: Backend,
  files: readonly string[],
  add: boolean,
): Promise<boolean> {
  const exists = files.includes(MCP_CONFIG_FILE)
  if (!exists && !add) return false
  const text = exists ? await backend.readText(MCP_CONFIG_FILE).catch(() => undefined) : null
  if (text === undefined) return false
  const listed = text !== null && parse(text)?.mcpServers?.[MCP_SERVER_NAME] !== undefined
  if (!add && !listed) return false
  const status = await backend.mcpStatus()
  if (!add && !status.enabled) return false
  const next = withNetherforgeServer(text, status)
  if (next === null) return false
  await backend.writeText(MCP_CONFIG_FILE, next)
  return true
}

/** For agents configured from a terminal. */
export const claudeAddCommand = ({ port, token }: McpConnection) =>
  `claude mcp add --transport http ${MCP_SERVER_NAME} ${mcpUrl(port)} --header "Authorization: ${authorization(token)}"`
