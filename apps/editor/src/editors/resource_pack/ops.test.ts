import { describe, expect, it } from 'vitest'
import { canonicalizeModel, type ResourcePackFile } from '@/core/format'
import { exampleFiles, exampleProject } from '@/testing/fixtures'
import {
  addEntry,
  duplicateEntry,
  freeEntryKey,
  idFrom,
  importPath,
  isOgg,
  newEntry,
  pngSize,
  removeEntry,
  renameEntry,
  selectionForFile,
  setSoundValue,
  soundEvents,
  soundFilesOf,
  soundImportPath,
  texturesOf,
  unusedTextures,
} from './ops'

const PACK = 'resource_packs/ui/pack.json'
const ui = () => JSON.parse(exampleFiles[PACK]!) as ResourcePackFile

describe('pack ops', () => {
  it("lists a pack's textures and which are unused", () => {
    const files = [
      ...Object.keys(exampleProject),
      'resource_packs/ui/textures/gui/extra.png',
      'resource_packs/ui/notes.txt',
    ]
    const textures = texturesOf(files, 'ui')
    expect(textures).toContain('gui/shop.png')
    expect(textures).not.toContain('notes.txt')
    expect(unusedTextures(ui(), textures)).toEqual(['gui/extra.png'])
  })

  it('adds, renames and removes entries', () => {
    const pack = ui()
    const key = freeEntryKey(pack, 'skins', 'shop.png')
    expect(key).toBe('shop_2')
    addEntry(pack, 'skins', key, newEntry('skins', 'gui/extra.png'))
    renameEntry(pack, 'skins', 'shop_2', 'banner')
    expect(Object.keys(pack.skins!)).toEqual(['shop', 'banner'])
    renameEntry(pack, 'skins', 'banner', 'shop')
    expect(pack.skins!.banner).toBeDefined()
    removeEntry(pack, 'glyphs', 'coin')
    expect(pack.glyphs).toBeUndefined()
    expect(newEntry('tooltips', 'a.png')).toEqual({ background: 'a.png' })
    expect(newEntry('equipment', 'armor/a.png')).toEqual({ humanoid: 'armor/a.png' })
    const text = canonicalizeModel('resource_pack', PACK, pack).text!
    expect(text.indexOf('"banner"')).toBeLessThan(text.indexOf('"shop"'))
  })

  it('names imports after the file', () => {
    expect(idFrom('My Banner (1).PNG')).toBe('my_banner_1')
    expect(importPath('My Banner.png', 'gui')).toBe('gui/my_banner.png')
    expect(importPath('###.png', '')).toBe('texture.png')
  })

  it('reads PNG sizes', () => {
    expect(
      pngSize(exampleProject['resource_packs/ui/textures/gui/shop.png'] as Uint8Array),
    ).toEqual({
      width: 176,
      height: 168,
    })
    expect(pngSize(new Uint8Array([1, 2, 3]))).toBeNull()
  })

  it("lists a pack's sounds: every file is an event, pack.json refines or adds", () => {
    const files = [
      'resource_packs/ui/sounds/click.ogg',
      'resource_packs/ui/sounds/step/a.ogg',
      'resource_packs/ui/sounds/readme.txt',
      'resource_packs/ui/sounds/Door Open.OGG',
      'resource_packs/other/sounds/x.ogg',
    ]
    // Listed whatever their name (format says what's wrong with it), but only a usable one is an event.
    const sounds = soundFilesOf(files, 'ui')
    expect(sounds).toEqual(['Door Open.OGG', 'click.ogg', 'step/a.ogg'])
    const pack: ResourcePackFile = { sounds: { step: { files: ['step/a.ogg'] } } }
    expect(soundEvents(pack, sounds)).toEqual([
      { key: 'click', files: ['click.ogg'] },
      { key: 'step', files: ['step/a.ogg'] },
      { key: 'step/a', files: ['step/a.ogg'] },
    ])
  })

  it('edits a sound entry, dropping it once empty', () => {
    const pack: ResourcePackFile = {}
    setSoundValue(pack, 'click', 'volume', 0.5)
    setSoundValue(pack, 'click', 'subtitle', 'Click')
    expect(pack.sounds).toEqual({ click: { volume: 0.5, subtitle: 'Click' } })
    setSoundValue(pack, 'click', 'stream', true)
    expect(pack.sounds?.click?.stream).toBe(true)
    setSoundValue(pack, 'click', 'stream', undefined)
    setSoundValue(pack, 'click', 'volume', undefined)
    setSoundValue(pack, 'click', 'subtitle', '')
    expect(pack.sounds).toBeUndefined()
    expect(
      canonicalizeModel('resource_pack', PACK, { sounds: { a: { pitch: 2 } } }).problems,
    ).toEqual([])
  })

  it('names sound imports and recognises Ogg files', () => {
    expect(soundImportPath('Door Open.OGG', 'fx')).toBe('fx/door_open.ogg')
    expect(soundImportPath('###.ogg', '')).toBe('sound.ogg')
    expect(isOgg(new TextEncoder().encode('OggS\0'))).toBe(true)
    expect(isOgg(new TextEncoder().encode('RIFF'))).toBe(false)
  })
})

describe('duplicateEntry', () => {
  it('copies an entry under the next free key, deeply', () => {
    const pack: ResourcePackFile = { skins: { shop: { texture: 'gui/shop.png', ascent: 13 } } }
    expect(duplicateEntry(pack, 'skins', 'shop')).toBe('shop_2')
    expect(duplicateEntry(pack, 'skins', 'shop')).toBe('shop_3')
    expect(pack.skins!.shop_2).toEqual(pack.skins!.shop)
    expect(pack.skins!.shop_2).not.toBe(pack.skins!.shop)
    expect(duplicateEntry(pack, 'glyphs', 'nope')).toBeNull()
  })

  it("selects a file's gallery entry: a texture, or a sound that can be an event", () => {
    expect(selectionForFile('textures/gui/shop.png')).toEqual({
      kind: 'textures',
      key: 'gui/shop.png',
    })
    expect(selectionForFile('sounds/menu/open.ogg')).toEqual({ kind: 'sounds', key: 'menu/open' })
    expect(selectionForFile('sounds/Door Open.ogg')).toBeNull()
    expect(selectionForFile('pack.json')).toBeNull()
  })
})
