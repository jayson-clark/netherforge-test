import { produce } from 'immer'
import { describe, expect, it } from 'vitest'
import { canonicalizeModel, type StructureGeneration } from '@/core/format'
import { biomesText, newGeneration, parseBiomes, poolNames, setBiomes, setCount } from './ops'

describe('structure generation edits', () => {
  it('starts in the overworld with the game defaults, and format writes it', () => {
    const text = canonicalizeModel('structure_generation', 'structures/ruins.json', newGeneration())
    expect(text.text).toContain('"biomes": ["#minecraft:is_overworld"]')
    expect(text.text).toContain('structure_generation.schema.json')
    expect(text.problems).toEqual([])
  })

  it('reads biomes typed with commas or spaces, once each', () => {
    expect(parseBiomes('plains, minecraft:desert  plains')).toEqual(['plains', 'minecraft:desert'])
    expect(parseBiomes('  ')).toEqual([])
    expect(biomesText({ biomes: ['plains', 'desert'] })).toBe('plains, desert')
    const next = produce(newGeneration(), (draft) => setBiomes(draft, '#minecraft:is_forest'))
    expect(next.biomes).toEqual(['#minecraft:is_forest'])
  })

  it('sets whole numbers and goes back to the default when cleared', () => {
    const base: StructureGeneration = { biomes: ['plains'], spacing: 24 }
    const set = produce(base, (draft) => setCount(draft, 'spacing', 20.6))
    expect(set.spacing).toBe(21)
    const cleared = produce(set, (draft) => setCount(draft, 'spacing', undefined))
    expect('spacing' in cleared).toBe(false)
    const negative = produce(base, (draft) => setCount(draft, 'startHeight', -30))
    expect(negative.startHeight).toBe(-30)
  })

  it('lists the pools the file has, in its order', () => {
    expect(poolNames({ biomes: ['plains'] })).toEqual([])
    expect(
      poolNames({
        biomes: ['plains'],
        pools: { roads: { elements: [] }, houses: { elements: [] } },
      }),
    ).toEqual(['roads', 'houses'])
  })
})
