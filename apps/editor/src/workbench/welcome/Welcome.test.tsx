import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { MemoryBackend } from '@/core/backend/memory'
import { MANIFEST_FILE } from '@/core/format'
import { AppProvider, createApp } from '@/state/providers'
import { Welcome, useWelcome } from './Welcome'

afterEach(() => {
  cleanup()
  useWelcome.setState({ creating: false, error: null })
})

async function createWith(template: string) {
  const backend = new MemoryBackend({ pickFolderResults: ['/memory/new'] })
  const app = createApp(backend)
  await app.workspace.getState().init()
  useWelcome.setState({ creating: true })
  render(
    <AppProvider app={app}>
      <Welcome />
    </AppProvider>,
  )
  await screen.findByRole('form', { name: 'Create project' })
  if (template) {
    fireEvent.change(screen.getByLabelText('Start with'), { target: { value: template } })
  }
  fireEvent.click(screen.getByRole('button', { name: 'Choose…' }))
  await screen.findByText('/memory/new')
  fireEvent.click(screen.getByRole('button', { name: 'Create' }))
  return { backend, app }
}

describe('creating a project', () => {
  it('can start from a template, which comes in as a package to copy from', async () => {
    const { backend, app } = await createWith('rpg_mob')
    await waitFor(() => expect(app.workspace.getState().project?.root).toBe('/memory/new'))
    await waitFor(() =>
      expect(JSON.parse(backend.testFiles()[MANIFEST_FILE]!).dependencies).toEqual({
        template_rpg_mob: { path: 'templates/template_rpg_mob' },
      }),
    )
    expect(
      backend.testFiles()['templates/template_rpg_mob/centities/goblin/centity.json'],
    ).toBeDefined()
  })

  it('starts empty unless asked', async () => {
    const { backend, app } = await createWith('')
    await waitFor(() => expect(app.workspace.getState().project?.root).toBe('/memory/new'))
    expect(JSON.parse(backend.testFiles()[MANIFEST_FILE]!).dependencies).toBeUndefined()
    expect(Object.keys(backend.testFiles()).filter((it) => it.startsWith('templates/'))).toEqual([])
  })
})
