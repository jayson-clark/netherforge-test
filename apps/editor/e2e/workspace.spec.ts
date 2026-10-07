import { expect } from '@playwright/test'
import {
  chooseMenu,
  files,
  filesOf,
  openCentity,
  openExample,
  openResource,
  outside,
  showPanel,
  test,
  TOWER,
} from './helpers'

async function makeDirty(page: import('@playwright/test').Page) {
  await openCentity(page, 'tower')
  await page.getByRole('row', { name: 'top' }).click()
  const z = page.getByRole('textbox', { name: 'Translation Z' })
  await z.fill('4')
  await z.press('Enter')
  await expect(page.getByRole('tab', { name: 'tower (unsaved)' })).toBeVisible()
}

/** Another program rewrites the tower's name on disk. */
async function writeTheirs(page: import('@playwright/test').Page, name: string) {
  await outside(
    page,
    (backend, arg) => {
      const text = backend.testFiles()[arg.path]!.replace('"Stone tower"', JSON.stringify(arg.name))
      backend.testWrite(arg.path, text)
    },
    { path: TOWER, name },
  )
}

test('an external change while dirty: keep mine, or diff and take theirs', async ({ page }) => {
  await openExample(page)
  await makeDirty(page)
  await writeTheirs(page, 'Their tower')
  const banner = page.getByRole('alert', { name: 'File changed on disk' })
  await expect(banner).toBeVisible()

  // Keep mine: the banner goes, and saving writes my version over theirs.
  await banner.getByRole('button', { name: 'Keep mine' }).click()
  await expect(banner).toHaveCount(0)
  await page.keyboard.press('ControlOrMeta+s')
  await expect(page.getByRole('tab', { name: 'tower', exact: true })).toBeVisible()
  let saved = JSON.parse((await files(page))[TOWER]!)
  expect(saved.name).toBe('Stone tower')
  expect(saved.nodes.top.transform.translation).toEqual([0, 1, 4])

  // Again, this time looking at the diff and taking theirs.
  const z = page.getByRole('textbox', { name: 'Translation Z' })
  await z.fill('5')
  await z.press('Enter')
  await writeTheirs(page, 'Their tower')
  await banner.getByRole('button', { name: 'Diff' }).click()
  const diff = page.getByRole('dialog', { name: /disk \(left\) vs\. yours/ })
  await expect(diff).toBeVisible()
  await diff.getByRole('button', { name: 'Take theirs' }).click()

  await expect(banner).toHaveCount(0)
  await expect(page.getByText('Their tower')).toBeVisible()
  await expect(page.getByRole('tab', { name: 'tower', exact: true })).toBeVisible()
  await expect(z).toHaveValue('4')
  saved = JSON.parse((await files(page))[TOWER]!)
  expect(saved.name).toBe('Their tower')
})

test('a clean document reloads silently, and a deleted one closes', async ({ page }) => {
  await openExample(page)
  await openCentity(page, 'tower')
  await writeTheirs(page, 'Renamed outside')
  await expect(page.getByText('Renamed outside')).toBeVisible()
  await expect(page.getByRole('alert', { name: 'File changed on disk' })).toHaveCount(0)

  await outside(page, (backend) => backend.testDelete('centities/tower'), null)
  await expect(page.getByRole('tab', { name: 'tower' })).toHaveCount(0)
  await expect(page.getByRole('option', { name: 'tower' })).toHaveCount(0)
})

test('a console script error opens the script at its line', async ({ page }) => {
  await openExample(page)
  // An error in the file beside the tower's script that it requires: located there.
  await outside(
    page,
    (backend) =>
      backend.testBridgeEvent('console', {
        items: [
          {
            type: 'script_error',
            message: "attempt to call a nil value (method 'play_animation')",
            source: { file: 'centities/tower/turns.lua', line: 11 },
            traceback:
              'stack traceback:\n\tcentities/tower/turns.lua:11: in function <centities/tower/turns.lua:10>',
          },
        ],
      }),
    null,
  )
  await showPanel(page, 'Console')
  const console = page.getByRole('log', { name: 'Console' })
  await expect(console.getByText(/attempt to call a nil value/)).toBeVisible()
  await console.getByRole('button', { name: 'Open centities/tower/turns.lua:11' }).click()

  await expect(page.getByRole('tab', { name: 'turns.lua' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await expect(page.locator('.monaco-editor .line-numbers.active-line-number')).toHaveText('11')
  await expect(page.locator('.monaco-editor .view-lines')).toContainText(
    'this:play_animation("spin")',
  )
})

test('runs the dev server, hot-reloads on save and spawns', async ({ page }) => {
  await openExample(page)
  await page.getByRole('button', { name: 'Start server' }).click()
  const eula = page.getByRole('dialog', { name: 'Minecraft EULA' })
  await expect(eula.getByRole('link', { name: 'Read the Minecraft EULA' })).toBeVisible()
  await expect(eula.getByRole('button', { name: 'Accept and start' })).toBeDisabled()
  await eula.getByRole('checkbox').check()
  await eula.getByRole('button', { name: 'Accept and start' }).click()

  await expect(page.getByRole('status', { name: 'Server status' })).toContainText('Running')
  await expect(page.getByRole('img', { name: 'Bridge connected' })).toBeVisible()

  // Save a script: the plugin is told to reload just that file.
  await openResource(page, 'tower')
  await filesOf(page, 'centities/tower').getByRole('row', { name: 'script.lua' }).click()
  await page.locator('.monaco-editor .view-lines').click()
  await page.keyboard.press('ControlOrMeta+End')
  await page.keyboard.type('\n-- edited')
  await page.keyboard.press('ControlOrMeta+s')
  await showPanel(page, 'Console')
  await showPanel(page, 'Console')
  await expect(
    page.getByRole('log', { name: 'Console' }).getByText(/Reloaded centity:tower/),
  ).toBeVisible()
  expect((await files(page))['centities/tower/script.lua']).toContain('-- edited')

  await openCentity(page, 'tower')
  await page.getByRole('button', { name: 'Spawn at me' }).click()
  await showPanel(page, 'Instances')
  await expect(
    page
      .getByRole('table', { name: 'Instances' })
      .getByRole('cell', { name: 'tower', exact: true }),
  ).toBeVisible()

  await page.getByRole('button', { name: 'Stop server' }).click()
  await expect(page.getByRole('status', { name: 'Server status' })).toContainText('Stopped')
})

test('the outline’s files, quick open, Ctrl+Tab, the settings tab, a layout that’s remembered, and zoom', async ({
  page,
}) => {
  await openExample(page)
  await openCentity(page, 'tower')
  const tower = filesOf(page, 'centities/tower')

  // A new file in place, through the tree's own input, opens in a tab.
  await tower.getByRole('row', { name: 'script.lua' }).click({ button: 'right' })
  await page.getByRole('menuitem', { name: 'New File…' }).click()
  const name = tower.getByRole('textbox', { name: 'New file name' })
  await name.fill('bad-name.lua')
  await expect(tower.getByRole('alert')).toContainText('Lua file')
  await name.fill('util/helpers.lua')
  await name.press('Enter')
  await expect(page.getByRole('tab', { name: 'helpers.lua' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  expect(Object.keys(await files(page))).toContain('centities/tower/util/helpers.lua')

  // Renamed in place with F2; duplicated with the keyboard.
  await tower.getByRole('row', { name: 'helpers.lua' }).click()
  await page.keyboard.press('F2')
  const rename = tower.getByRole('textbox', { name: 'Rename helpers.lua' })
  await rename.fill('steps.lua')
  await rename.press('Enter')
  await expect(tower.getByRole('row', { name: 'steps.lua' })).toBeVisible()
  await page.keyboard.press('ControlOrMeta+d')
  await expect(tower.getByRole('row', { name: 'steps_copy.lua' })).toBeVisible()

  // Quick open jumps anywhere; Ctrl+Tab and Ctrl+Shift+Tab step through the tabs.
  await page.keyboard.press('ControlOrMeta+p')
  await page.getByRole('textbox', { name: 'Go to resource or file' }).fill('shop')
  await page.keyboard.press('Enter')
  await expect(page.getByRole('tab', { name: 'shop', exact: true })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await expect(page.getByRole('treegrid', { name: 'Slots' })).toBeVisible()
  await page.keyboard.press('Control+Tab')
  await expect(page.getByRole('tab', { name: 'tower', exact: true })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await page.keyboard.press('Control+Shift+Tab')
  await page.keyboard.press('Control+Shift+Tab')
  await expect(page.getByRole('tab', { name: 'steps.lua' })).toHaveAttribute(
    'aria-selected',
    'true',
  )

  // Settings open as a tab, their pages in the outline; the page scrolls inside the tab.
  await page.getByRole('button', { name: 'Settings', exact: true }).click()
  await expect(page.getByRole('tab', { name: 'Settings', exact: true })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await expect(page.getByRole('region', { name: 'Minecraft settings' })).toBeVisible()
  await page
    .getByRole('treegrid', { name: 'Settings pages' })
    .getByRole('row', { name: 'Agents' })
    .click()
  await expect(page.getByRole('region', { name: 'Agents settings' })).toBeVisible()
  await page.getByRole('tab', { name: 'steps.lua' }).click()

  // The palette runs commands too: a settings page, a dock, and leaving the project.
  const palette = page.getByRole('textbox', { name: /run a command/ })
  const matches = page.getByRole('listbox', { name: 'Matches' })
  await page.keyboard.press('ControlOrMeta+Shift+p')
  await expect(palette).toHaveValue('>')
  await palette.fill('>server')
  await expect(matches.getByRole('option', { name: /^Editor Settings: Server(?!-)/ })).toBeVisible()
  await expect(matches.getByRole('option', { name: /^tower/ })).toHaveCount(0)
  await page.keyboard.press('Escape')

  await page.keyboard.press('ControlOrMeta+p')
  await palette.fill('show console')
  await matches.getByRole('option', { name: /^Show Console/ }).click()
  await expect(
    page.getByRole('tablist', { name: 'Output panels' }).getByRole('tab', { name: /Console/ }),
  ).toHaveAttribute('aria-selected', 'true')
  await page.getByRole('tab', { name: 'steps.lua' }).click()

  // Docks resize by dragging their splitter or with its arrow keys, hide, and the project
  // opens again as it was left.
  const outlineWidth = async () => (await page.locator('#outline').boundingBox())!.width
  const splitter = page.getByRole('separator', { name: 'Resize the outline' })
  const grip = (await splitter.boundingBox())!
  // A 1px line that takes its space (the previews' pixels depend on the layout around them).
  expect(grip.width).toBe(1)
  await page.mouse.move(grip.x + grip.width / 2, grip.y + 200)
  await page.mouse.down()
  await page.mouse.move(grip.x + grip.width / 2 + 100, grip.y + 200, { steps: 5 })
  await page.mouse.up()
  await expect.poll(outlineWidth).toBeCloseTo(340, 0)
  await splitter.focus()
  await page.keyboard.press('ArrowLeft')
  await expect.poll(outlineWidth).toBeLessThan(340)
  const resized = await outlineWidth()
  await page.getByRole('button', { name: 'Inspector', exact: true }).click()
  await expect(page.getByRole('region', { name: 'Inspector dock' })).toBeHidden()
  await page.keyboard.press('ControlOrMeta+Shift+p')
  await palette.fill('>close project')
  await page.keyboard.press('Enter')
  await page
    .getByRole('list', { name: 'Recent projects' })
    .getByRole('button', { name: /Basic example/ })
    .click()
  await expect(page.getByRole('tab', { name: 'steps.lua' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await expect(page.getByRole('tab', { name: 'shop', exact: true })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Settings', exact: true })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Inspector dock' })).toBeHidden()
  await expect.poll(outlineWidth).toBeCloseTo(resized, 0)

  // Cmd/Ctrl +/- scale the UI (CSS zoom on the memory backend), remembered across starts; 0 resets.
  const zoom = () => page.evaluate<string>('document.documentElement.style.zoom')
  await page.keyboard.press('ControlOrMeta+Equal')
  await page.keyboard.press('ControlOrMeta+Equal')
  await page.keyboard.press('ControlOrMeta+Minus')
  await expect.poll(zoom).toBe('1.1')
  await page.reload()
  await expect(page.getByRole('list', { name: 'Recent projects' })).toBeVisible()
  await expect.poll(zoom).toBe('1.1')
  await page.keyboard.press('ControlOrMeta+0')
  await expect.poll(zoom).toBe('1')
})

test('the menu bar: docks, quick open, Select All in a field, and leaving a project', async ({
  page,
}) => {
  await openExample(page)
  // Save and undo live in the menus and on their keys now, not the toolbar.
  const toolbar = page.getByRole('toolbar', { name: 'Main toolbar' })
  await expect(toolbar.getByRole('button', { name: 'Save', exact: true })).toHaveCount(0)
  await expect(toolbar.getByRole('button', { name: 'Undo' })).toHaveCount(0)

  await chooseMenu(page, 'View', 'Inspector')
  await expect(page.getByRole('region', { name: 'Inspector dock' })).toBeHidden()
  await page.getByRole('menubar').getByRole('menuitem', { name: 'View', exact: true }).click()
  await expect(
    page.getByRole('menu', { name: 'View' }).getByRole('menuitemcheckbox', { name: /^Inspector/ }),
  ).toHaveAttribute('aria-checked', 'false')
  await page.keyboard.press('Escape')
  await expect(page.getByRole('menu', { name: 'View' })).toBeHidden()
  await page.keyboard.press('ControlOrMeta+Alt+b')
  await expect(page.getByRole('region', { name: 'Inspector dock' })).toBeVisible()

  await chooseMenu(page, 'Edit', 'Go to Resource or File')
  await expect(page.getByRole('textbox', { name: 'Go to resource or file' })).toBeFocused()
  await page.keyboard.press('Escape')

  // The menu never takes focus, so Edit → Select All acts on the field being typed in.
  await openCentity(page, 'tower')
  await page.getByRole('row', { name: 'top' }).click()
  const y = page.getByRole('textbox', { name: 'Translation Y' })
  await y.fill('2.5')
  await chooseMenu(page, 'Edit', 'Select All')
  await expect(y).toBeFocused()
  await page.keyboard.type('3')
  await expect(y).toHaveValue('3')
  await y.press('Enter')
  await expect(page.getByRole('tab', { name: 'tower (unsaved)' })).toBeVisible()
  // Outside a field there is nothing to select all of, and the page itself isn't selected.
  await page.getByRole('row', { name: 'top' }).click()
  // (The field just left still reports its range, so start from no selection.)
  await page.evaluate('getSelection().removeAllRanges()')
  await page.keyboard.press('ControlOrMeta+a')
  await chooseMenu(page, 'Edit', 'Select All')
  expect(await page.evaluate('String(getSelection())')).toBe('')

  // Leaving with unsaved edits asks first; Cancel stays.
  await chooseMenu(page, 'File', 'Close Project')
  const dialog = page.getByRole('dialog', { name: 'Unsaved changes' })
  await dialog.getByRole('button', { name: 'Cancel' }).click()
  await expect(page.getByRole('tab', { name: 'tower (unsaved)' })).toBeVisible()
  await chooseMenu(page, 'File', 'New Project')
  await dialog.getByRole('button', { name: "Don't save" }).click()
  await expect(page.getByRole('form', { name: 'Create project' })).toBeVisible()

  // File → Open Recent brings it back, without the edit that wasn't saved.
  await page
    .getByRole('menubar', { name: 'Menu' })
    .getByRole('menuitem', { name: 'File', exact: true })
    .click()
  await page
    .getByRole('menu', { name: 'File' })
    .getByRole('menuitem', { name: 'Open Recent' })
    .hover()
  await page
    .getByRole('menu', { name: 'Open Recent' })
    .getByRole('menuitem', { name: /Basic example/ })
    .click()
  await expect(page.getByRole('tab', { name: 'tower', exact: true })).toBeVisible()
  expect(JSON.parse((await files(page))[TOWER]!).nodes.top.transform.translation).toEqual([0, 1, 0])
})
