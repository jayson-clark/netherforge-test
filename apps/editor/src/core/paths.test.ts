import { describe, expect, it } from 'vitest'
import {
  findSourceRefs,
  isFileKind,
  locationOf,
  mainFileOf,
  opensAs,
  resourcePackTexturePath,
  parseJsonPath,
  resourceIdsOf,
  resourceOf,
} from './paths'

describe('paths', () => {
  it('parses format JSON paths', () => {
    expect(parseJsonPath('$.nodes.top.display.block')).toEqual(['nodes', 'top', 'display', 'block'])
    expect(parseJsonPath('$.nodes["a b"].hitbox.boxes[1]')).toEqual([
      'nodes',
      'a b',
      'hitbox',
      'boxes',
      1,
    ])
    expect(parseJsonPath('$')).toEqual([])
  })

  it('maps paths to resources', () => {
    expect(resourceOf('centities/tower/script.lua')).toEqual({
      kind: 'centity',
      id: 'tower',
      role: 'file',
      rest: 'script.lua',
    })
    expect(resourceOf('modules/greeter/lib/a.lua')).toMatchObject({
      kind: 'module',
      id: 'greeter',
      rest: 'lib/a.lua',
    })
    expect(resourceOf('menus/shop/menu.json')).toMatchObject({ kind: 'menu', role: 'main' })
    expect(resourceOf('resource_packs/ui/textures/gui/shop.png')?.kind).toBe('resource_pack')
    expect(resourceOf('dialogs/welcome')).toEqual({
      kind: 'dialog',
      id: 'welcome',
      role: 'folder',
      rest: '',
    })
    expect(resourceOf('maps/arena/region/r.0.0.mca')).toMatchObject({ kind: 'map' })
    expect(resourcePackTexturePath('ui', 'gui/shop.png')).toBe(
      'resource_packs/ui/textures/gui/shop.png',
    )
    expect(resourcePackTexturePath('library:gems', 'gem.png')).toBe(
      'library:resource_packs/gems/textures/gem.png',
    )
    expect(resourceOf('netherforge.json')).toBeNull()
    expect(
      resourceIdsOf(
        ['centities/a/centity.json', 'centities/b/x.lua', 'centities/.gitkeep', 'centities/c'],
        'centity',
      ),
    ).toEqual(['a', 'b'])
  })

  it('knows where each kind keeps a resource, folder or file', () => {
    expect(isFileKind('recipe')).toBe(true)
    expect(isFileKind('item')).toBe(false)
    expect(mainFileOf('recipe', 'torch')).toBe('recipes/torch.json')
    expect(mainFileOf('item', 'ruby')).toBe('items/ruby/item.json')
    expect(locationOf('recipe', 'torch')).toBe('recipes/torch.json')
    expect(locationOf('item', 'ruby')).toBe('items/ruby')
    expect(opensAs('module', 'greeter')).toBe('modules/greeter/init.lua')
    expect(opensAs('map', 'arena')).toBe('maps/arena')
    expect(opensAs('structure', 'house')).toBe('structures/house.nbt')
    // A migration is one SQL file, opened as that file.
    expect(isFileKind('migration')).toBe(true)
    expect(opensAs('migration', '001_init')).toBe('migrations/001_init.sql')
    expect(resourceOf('migrations/001_init.sql')).toMatchObject({
      kind: 'migration',
      id: '001_init',
      role: 'main',
    })
    expect(resourceOf('recipes/torch.json')).toEqual({
      kind: 'recipe',
      id: 'torch',
      role: 'main',
      rest: '',
    })
    expect(resourceOf('recipes/.json')).toBeNull()
    expect(resourceOf('recipes/a/b.json')).toBeNull()
    expect(resourceOf('recipes/notes.txt')).toBeNull()
    const files = ['recipes/b.json', 'recipes/a.json', 'recipes/notes.txt', 'items/ruby/item.json']
    expect(resourceIdsOf(files, 'recipe')).toEqual(['a', 'b'])
    expect(resourceIdsOf(files, 'item')).toEqual(['ruby'])
  })

  it('finds file:line references in console output', () => {
    const refs = findSourceRefs('error at centities/tower/script.lua:12: attempt to index nil')
    expect(refs).toEqual([{ start: 9, end: 38, file: 'centities/tower/script.lua', line: 12 }])
    expect(findSourceRefs('at menus/shop/script.lua:3')[0]?.file).toBe('menus/shop/script.lua')
  })
})
