import { expect } from '@playwright/test'
import { canonicalize } from '@netherforge/format'
import { files, openExampleWithAssets, openResource, save, test } from './helpers'

const TREASURE = 'loot/treasure.json'

test('edits a loot table: picks a pool, weighs an entry, adds one and a condition, and rolls it', async ({
  page,
}) => {
  await openExampleWithAssets(page)
  await openResource(page, 'treasure', 'Loot tables')
  const pools = page.getByRole('treegrid', { name: 'Pools' })
  await expect(pools.getByRole('row')).toHaveCount(2)
  await pools.getByRole('row', { name: 'gems' }).click()

  // Each entry with its item and its share of a pick: weights 3, 1 and 2.
  const entries = page.getByRole('list', { name: 'Entries' })
  const ruby = entries.getByRole('button', { name: 'Entry: ruby' })
  await expect(ruby).toContainText('50% of a pick')
  await expect(ruby.locator('img')).toHaveCount(1)
  await expect(entries.getByRole('button', { name: 'Entry: Nothing' })).toContainText('33.3%')

  // Weighing it down in the inspector moves every share.
  await ruby.click()
  const inspector = page.getByRole('complementary', { name: 'Inspector' })
  const weight = inspector.getByRole('textbox', { name: 'Weight' })
  await expect(weight).toHaveValue('3')
  await weight.fill('1')
  await weight.press('Enter')
  await expect(ruby).toContainText('25% of a pick')

  // A new entry, and a condition on it.
  await page
    .getByRole('group', { name: 'Add an entry' })
    .getByRole('button', { name: 'Nothing' })
    .click()
  await expect(entries.getByRole('listitem')).toHaveCount(4)
  await inspector.getByRole('combobox', { name: 'New condition' }).last().selectOption('player')
  await inspector.getByRole('button', { name: 'Add condition' }).last().click()
  await expect(entries.getByRole('listitem').last()).toContainText('only if a player did it')

  await save(page, 'treasure')
  const written = (await files(page))[TREASURE]!
  expect(written).toBe(JSON.parse(canonicalize('loot_table', TREASURE, written)).text)
  const gems = JSON.parse(written).pools.gems
  expect(gems.entries[0].weight).toBe(1)
  expect(gems.entries[3]).toEqual({ type: 'empty', conditions: [{ type: 'player' }] })

  // A roll by format's roller: the same seed, the same items.
  const preview = page.getByRole('region', { name: 'Roll preview' })
  await preview.getByRole('button', { name: 'Roll', exact: true }).click()
  const rolled = preview.getByRole('list', { name: 'Rolled' })
  // Each stack by its name and count (`ruby ×2`), as the accessibility tree has them.
  await expect(rolled.getByRole('listitem').first()).toHaveAccessibleName(/ ×\d+$/)
  const first = await rolled.ariaSnapshot()
  await preview.getByRole('button', { name: 'Roll', exact: true }).click()
  await expect(rolled).toMatchAriaSnapshot(first)
})

test("includes a package's exported loot table by its full name", async ({ page }) => {
  await openExampleWithAssets(page)
  await openResource(page, 'treasure', 'Loot tables')
  await page.getByRole('treegrid', { name: 'Pools' }).getByRole('row', { name: 'extra' }).click()
  await page
    .getByRole('list', { name: 'Entries' })
    .getByRole('button', { name: 'Entry: Loot table library:gems' })
    .click()
  // The project's other tables, then what the packages it depends on export.
  const table = page
    .getByRole('complementary', { name: 'Inspector' })
    .getByRole('combobox', { name: 'Loot table' })
  await expect(table).toHaveValue('library:gems')
  await expect(table.getByRole('option')).toHaveText(['junk', 'ruby_ore', 'library:gems'])
})
