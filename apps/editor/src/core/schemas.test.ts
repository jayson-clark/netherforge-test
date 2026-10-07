import { describe, expect, it } from 'vitest'
import { schemaFiles } from './schemas'

describe('schemas', () => {
  it('bundles the schema for every document kind', () => {
    expect(Object.keys(schemaFiles).sort()).toEqual([
      'advancement.schema.json',
      'biome.schema.json',
      'block.schema.json',
      'bundle.schema.json',
      'centity.schema.json',
      'cutscene.schema.json',
      'datapack.schema.json',
      'default_font.schema.json',
      'dialog.schema.json',
      'dimension_type.schema.json',
      'item.schema.json',
      'lock.schema.json',
      'loot_table.schema.json',
      'menu.schema.json',
      'netherforge.schema.json',
      'particle_effect.schema.json',
      'recipe.schema.json',
      'resource_pack.schema.json',
      'structure_generation.schema.json',
      'terrain.schema.json',
    ])
    for (const text of Object.values(schemaFiles)) expect(() => JSON.parse(text)).not.toThrow()
  })
})
