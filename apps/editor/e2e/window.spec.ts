import { expect, type Page } from '@playwright/test'
import { openExample, test } from './helpers'

/** Whether the point in [bar] at [fraction] of its width, halfway down, would drag the window. */
async function dragsAt(page: Page, bar: string, fraction: number) {
  const box = (await page.locator(bar).boundingBox())!
  const x = box.x + box.width * fraction
  const y = box.y + box.height / 2
  // A string, as elsewhere in e2e: these tests don't compile against the DOM.
  return page.evaluate(
    `document.elementFromPoint(${x}, ${y})?.hasAttribute('data-tauri-drag-region') ?? false`,
  )
}

// A drag region is only the element marked, not its children, so the empty
// parts of a title bar must be marked themselves.
test('the empty parts of the title bar drag the window, on the welcome screen too', async ({
  page,
}) => {
  await page.goto('/?fast')
  await expect(page.getByRole('list', { name: 'Recent projects' })).toBeVisible()
  expect(await dragsAt(page, 'main > div[data-tauri-drag-region]', 0.6)).toBe(true)

  await openExample(page)
  expect(await dragsAt(page, '[role="toolbar"][aria-label="Main toolbar"]', 0.6)).toBe(true)
})
