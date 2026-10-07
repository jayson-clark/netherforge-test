import { expect } from '@playwright/test'
import { openExample, outside, showPanel, test } from './helpers'

test('the profiler shows where the dev server’s ticks go and opens a handler at its line', async ({
  page,
}) => {
  const errors = await openExample(page)
  await showPanel(page, 'Profiler')
  await expect(page.getByText(/Start the dev server/)).toBeVisible()

  // The bridge comes up: the editor subscribes, and the (fake) plugin streams a batch a second.
  await outside(page, (backend) => backend.testConnect(), null)
  await expect
    .poll(() => outside(page, (backend) => backend.bridgeLog, null))
    .toContainEqual({ method: 'profiler_subscribe', params: { on: true } })

  const functions = page.getByRole('table', { name: 'Functions' })
  const tower = functions.getByRole('row').filter({ hasText: 'centity tower' })
  await expect(tower.first()).toBeVisible()
  await expect(page.getByRole('img', { name: /The last \d+ ticks, by step/ })).toBeVisible()
  await expect(page.getByText(/\d+ ticks, [\d.]+ ms a tick on average/)).toBeVisible()

  // Sorting by the worst call marks its column.
  await functions.getByRole('button', { name: /Max ms/ }).click()
  await expect(functions.getByRole('columnheader', { name: /Max ms/ })).toHaveAttribute(
    'aria-sort',
    'descending',
  )

  await tower.first().getByRole('button', { name: 'Open centities/tower/script.lua:9' }).click()
  await expect(page.getByRole('tab', { name: 'script.lua' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await expect(page.locator('.monaco-editor .line-numbers.active-line-number')).toHaveText('9')

  await page.getByRole('button', { name: 'Scopes' }).click()
  await expect(page.getByRole('table', { name: 'Scopes' }).getByText('centity tower')).toBeVisible()
  expect(errors).toEqual([])
})
