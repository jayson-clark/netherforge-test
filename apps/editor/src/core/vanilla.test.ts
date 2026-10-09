import { describe, expect, it } from 'vitest'
import { vanillaLooks, vanillaToWrite, VANILLA_FILE } from './vanilla'

const tripwire = { variants: { 'attached=false': { model: 'minecraft:block/tripwire_ns' } } }
const index = {
  minecraft: '26.3',
  blockstates: [],
  items: [],
  fonts: [],
  sprites: ['hud/heart/full'],
}

describe('the game’s own looks', () => {
  it('reads the client’s blockstate file of each block that needs one and the HUD’s sprites, and nothing without an import', async () => {
    const files: Record<string, unknown> = {
      'assets/minecraft/blockstates/tripwire.json': tripwire,
      'index.json': index,
    }
    const assets = { json: async <T>(path: string) => (files[path] ?? null) as T | null }
    expect(await vanillaLooks(assets)).toEqual({
      blockstates: { 'minecraft:tripwire': tripwire },
      sprites: ['hud/heart/full'],
    })
    expect(await vanillaLooks({ json: async () => null })).toEqual({ blockstates: {}, sprites: [] })
  })

  it('writes the file only when it would change, and never without something to write from', () => {
    expect(VANILLA_FILE).toBe('client/vanilla.json')
    const looks = { blockstates: { 'minecraft:tripwire': tripwire }, sprites: ['hud/heart/full'] }
    const text = vanillaToWrite('26.3', looks, undefined)
    expect(text).toContain('"minecraft": "26.3"')
    expect(text).toContain('tripwire_ns')
    expect(text).toContain('hud/heart/full')
    expect(vanillaToWrite('26.3', looks, text!)).toBeNull()
    expect(vanillaToWrite('26.3', { blockstates: {}, sprites: [] }, undefined)).toBeNull()
    expect(vanillaToWrite(null, looks, undefined)).toBeNull()
  })
})
