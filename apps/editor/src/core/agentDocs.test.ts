import { describe, expect, it } from 'vitest'
import { agentDocFiles, agentDocPaths, hasCli } from './agentDocs'

describe('agent docs', () => {
  it('bundles the guides, the format spec and the API reference, and indexes every page', async () => {
    expect(agentDocPaths).toEqual(expect.arrayContaining(['format/centity.md', 'reference/nf.md']))
    expect(agentDocPaths.some((it) => it.startsWith('guide/'))).toBe(true)
    const files = await agentDocFiles('1.2.3', null)
    const readme = files['.netherforge/docs/README.md']!
    expect(readme).toMatch(/^# NetherForge 1\.2\.3/)
    for (const path of agentDocPaths) {
      expect(files[`.netherforge/docs/${path}`]).toBeDefined()
      expect(readme).toContain(`](${path})`)
    }
    expect(Object.keys(files).at(-1)).toBe('.netherforge/docs/README.md')
  })

  it("points at lua-language-server's stubs rather than keeping a copy of its own", async () => {
    const files = await agentDocFiles('1.2.3', null)
    expect(Object.keys(files).filter((it) => it.endsWith('.lua'))).toEqual([])
    expect(files['.netherforge/docs/README.md']).toContain('`../luals/nf.lua`')
  })

  it('writes the netherforge command when it was built', async () => {
    const files = await agentDocFiles('1.2.3', null)
    expect('.netherforge/bin/netherforge.mjs' in files).toBe(hasCli)
    expect(files['.netherforge/docs/README.md']!.includes('netherforge.mjs check')).toBe(hasCli)
  })

  it('writes nothing when the README already there is this bundle', async () => {
    const readme = (await agentDocFiles('1.2.3', null))['.netherforge/docs/README.md']!
    expect(await agentDocFiles('1.2.3', readme)).toEqual({})
    expect(Object.keys(await agentDocFiles('1.2.4', readme)).length).toBeGreaterThan(0)
  })
})
