import { describe, expect, it } from 'vitest'
import { MEMORY_MCP_TOKEN, MemoryBackend } from '@/core/backend/memory'
import { claudeAddCommand, syncMcpConfig, withNetherforgeServer } from './config'

const here = { port: 4000, token: 'abc' }
const server = {
  type: 'http',
  url: 'http://127.0.0.1:4000/mcp',
  headers: { Authorization: 'Bearer abc' },
}

describe('.mcp.json', () => {
  it('is created with the netherforge server and its token', () => {
    expect(JSON.parse(withNetherforgeServer(null, here)!)).toEqual({
      mcpServers: { netherforge: server },
    })
  })

  it("merges into the user's own, keeping everything in it", () => {
    const mine = JSON.stringify({ mcpServers: { other: { command: 'x' } }, extra: 1 })
    expect(JSON.parse(withNetherforgeServer(mine, here)!)).toEqual({
      mcpServers: { other: { command: 'x' }, netherforge: server },
      extra: 1,
    })
  })

  it("brings another editor's entry up to date, and leaves a current one alone", () => {
    const stale = JSON.stringify({
      mcpServers: { netherforge: { type: 'http', url: 'http://127.0.0.1:47615/mcp' } },
    })
    expect(JSON.parse(withNetherforgeServer(stale, here)!)).toEqual({
      mcpServers: { netherforge: server },
    })
    const current = withNetherforgeServer(null, here)
    expect(withNetherforgeServer(current, here)).toBeNull()
  })

  it("leaves a file alone that doesn't parse", () => {
    expect(withNetherforgeServer('{ nope', here)).toBeNull()
    expect(withNetherforgeServer('[]', here)).toBeNull()
    expect(withNetherforgeServer('{"mcpServers": 1}', here)).toBeNull()
  })

  it('on open, updates only a file that lists the netherforge server', async () => {
    const backend = new MemoryBackend({ projects: { '/p': { 'netherforge.json': '{}' } } })
    await backend.openProject('/p')
    expect(await syncMcpConfig(backend, [], false)).toBe(false)
    expect(backend.testFiles()['.mcp.json']).toBeUndefined()

    const others = JSON.stringify({ mcpServers: { other: { command: 'x' } } })
    backend.testWrite('.mcp.json', others)
    expect(await syncMcpConfig(backend, ['.mcp.json'], false)).toBe(false)
    expect(backend.testFiles()['.mcp.json']).toBe(others)

    backend.testWrite('.mcp.json', JSON.stringify({ mcpServers: { netherforge: {} } }))
    expect(await syncMcpConfig(backend, ['.mcp.json'], false)).toBe(true)
    const written = JSON.parse(backend.testFiles()['.mcp.json']!)
    expect(written.mcpServers.netherforge.headers).toEqual({
      Authorization: `Bearer ${MEMORY_MCP_TOKEN}`,
    })
    expect(await syncMcpConfig(backend, ['.mcp.json'], false)).toBe(false)
  })

  it('tells a terminal how to connect, header and all', () => {
    expect(claudeAddCommand(here)).toBe(
      'claude mcp add --transport http netherforge http://127.0.0.1:4000/mcp --header "Authorization: Bearer abc"',
    )
  })
})
