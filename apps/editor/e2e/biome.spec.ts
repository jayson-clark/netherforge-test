import { expect, type Page } from '@playwright/test'
import { canonicalize } from '@netherforge/format'
import {
  files,
  openExampleWithAssets,
  openResource,
  save,
  showPanel,
  test,
  showKind,
} from './helpers'

const GROVE = 'biomes/ruby_grove.json'
const biomes = (page: Page) => page.getByRole('listbox', { name: 'Biomes' })

test('edits a biome: its colours drawn, a mob and a feature added, saved canonically', async ({
  page,
}) => {
  await openExampleWithAssets(page)
  await openResource(page, 'ruby_grove', 'Biomes')

  // The landscape is painted with the file's colours.
  const scene = page.getByRole('img', { name: 'Biome colours' })
  await expect(scene.locator('[data-color="#d94f7a"]')).toBeVisible()

  const inspector = page.getByRole('complementary', { name: 'Inspector' })
  const water = inspector.getByRole('textbox', { name: 'Water', exact: true })
  await expect(water).toHaveValue('#d94f7a')
  await water.fill('#3366CC')
  await water.press('Enter')
  await expect(scene.locator('[data-color="#3366cc"]')).toBeVisible()
  await inspector.getByRole('button', { name: 'Clear Fog' }).click()

  // A mob of another category, and a feature at the end of a step's list.
  await inspector.getByRole('button', { name: 'Add a spawn of ambient' }).click()
  const entity = inspector.locator('[data-path="spawns.ambient[0].entity"]')
  await entity.fill('minecraft:bat')
  await entity.press('Enter')
  await inspector.getByRole('button', { name: 'Add a feature to top_layer_modification' }).click()
  const feature = inspector.locator('[data-path="features.top_layer_modification[0]"]')
  await feature.fill('minecraft:freeze_top_layer')
  await feature.press('Enter')
  // The order a step's features are placed in is the list's.
  await inspector.getByRole('button', { name: 'Move minecraft:trees_cherry earlier' }).click()

  await save(page, 'ruby_grove')
  const written = (await files(page))[GROVE]!
  expect(written).toBe(JSON.parse(canonicalize('biome', GROVE, written)).text)
  const grove = JSON.parse(written)
  expect(grove.colors.water).toBe('#3366cc')
  expect(grove.colors.fog).toBeUndefined()
  expect(grove.spawns.ambient).toEqual([{ entity: 'minecraft:bat' }])
  expect(grove.features.top_layer_modification).toEqual(['minecraft:freeze_top_layer'])
  expect(grove.features.vegetal_decoration).toEqual([
    'minecraft:patch_grass_plain',
    'minecraft:trees_cherry',
    'minecraft:flower_cherry',
  ])
})

test('renames a biome everywhere it is named, and a game biome stays the game’s', async ({
  page,
}) => {
  await openExampleWithAssets(page)
  await showPanel(page, 'Project')
  await showKind(page, 'Biomes')
  await biomes(page).getByRole('option', { name: 'ruby_grove', exact: true }).click()
  await page.keyboard.press('F2')
  const rename = biomes(page).getByRole('textbox', { name: 'Rename ruby_grove' })
  await rename.fill('ash_grove')
  await rename.press('Enter')
  await expect(biomes(page).getByRole('option', { name: 'ash_grove' })).toBeVisible()
  await expect
    .poll(async () => JSON.parse((await files(page))['terrain/ruby_hills.json']!).biomes)
    .toMatchObject({
      grove: { biome: 'ash_grove' },
      plains: { biome: 'minecraft:plains' },
      snowy: { biome: 'minecraft:snowy_plains' },
    })
  expect(Object.keys(await files(page))).toContain('biomes/ash_grove.json')
  await showPanel(page, 'Problems')
  await expect(page.getByText('No problems.')).toBeVisible()
})
