import { expect } from '@playwright/test'
import { files, openExample, showPanel, test, showKind } from './helpers'

test('shows a dependency read-only and copies its item into the project', async ({ page }) => {
  const errors = await openExample(page)
  await showPanel(page, 'Project')
  await showKind(page, 'Package library')

  const pkg = page.getByRole('list', { name: 'Package library' })
  await expect(pkg.getByRole('listitem', { name: 'library:gem', exact: true })).toBeVisible()
  await expect(pkg.getByRole('listitem', { name: 'library:greetings' })).toBeVisible()
  // Read-only: nothing there renames or deletes.
  await expect(page.getByText('read-only')).toBeVisible()
  await expect(pkg.getByRole('button', { name: /rename|delete/i })).toHaveCount(0)

  // Opened, it's the package's as it is: read-only, every control disabled.
  await pkg.getByRole('listitem', { name: 'library:gem', exact: true }).dblclick()
  await expect(page.getByLabel('Read-only')).toContainText('"library"')

  await pkg.getByRole('button', { name: 'Copy library:gem into project' }).click()
  const id = page.getByRole('textbox', { name: 'Id (the folder name)' })
  await expect(id).toHaveValue('gem')
  await id.fill('shiny_gem')
  await page.getByRole('button', { name: 'Copy', exact: true }).click()

  // The copy is the project's, shown among its items, naming the library's model in full.
  await expect(page.getByRole('option', { name: 'shiny_gem', exact: true })).toBeVisible()
  const written = (await files(page))['items/shiny_gem/item.json']!
  expect(JSON.parse(written).itemModel).toBe('library:gems/gem')
  expect((await files(page))['items/shiny_gem/script.lua']).toContain('The gem hums.')
  expect(errors).toEqual([])
})

test('adds a template as a package and copies its resources into the project', async ({ page }) => {
  const errors = await openExample(page)
  await showPanel(page, 'Project')
  await page.getByRole('button', { name: 'Add template…' }).click()
  await page.getByRole('menuitem', { name: /^Shop:/ }).click()

  // It's a package like any other, read-only under Dependencies, kept in the project's templates folder.
  const pkg = page.getByRole('list', { name: 'Package template_shop' })
  await expect(pkg.getByRole('listitem', { name: 'template_shop:lucky_charm' })).toBeVisible()
  await expect(
    pkg.getByRole('group', { name: 'Menus' }).getByRole('listitem', { name: 'template_shop:shop' }),
  ).toBeVisible()
  const written = await files(page)
  expect(JSON.parse(written['netherforge.json']!).dependencies.template_shop).toEqual({
    path: 'templates/template_shop',
  })
  expect(written['templates/template_shop/modules/shop/init.lua']).toContain('shop_wallets')
  expect(written['templates/template_shop/tests/shop_test.lua']).toBeUndefined()

  // One that's added is greyed out in the menu.
  await page.getByRole('button', { name: 'Add template…' }).click()
  await expect(page.getByRole('menuitem', { name: /^Shop:/ })).toBeDisabled()
  await page.keyboard.press('Escape')

  await pkg.getByRole('button', { name: 'Copy template_shop:lucky_charm into project' }).click()
  await page.getByRole('button', { name: 'Copy', exact: true }).click()
  await expect(page.getByRole('option', { name: 'lucky_charm', exact: true })).toBeVisible()
  expect((await files(page))['items/lucky_charm/item.json']).toContain('Lucky charm')
  expect(errors).toEqual([])
})
