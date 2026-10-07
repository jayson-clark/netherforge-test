import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MemoryBackend } from '@/core/backend/memory'
import type { ParticleEffectFile } from '@/core/format'
import { AppProvider, createApp, type AppStores } from '@/state/providers'
import { EXAMPLE_ROOT, exampleProjects } from '@/testing/fixtures'
import { PlayOnServer } from './PlayOnServer'

const SPARKLE = 'particles/sparkle/effect.json'

let backend: MemoryBackend
let app: AppStores

beforeEach(async () => {
  backend = new MemoryBackend({ projects: exampleProjects() })
  app = createApp(backend)
  await app.workspace.getState().openProject(EXAMPLE_ROOT)
  await app.workspace.getState().openFile(SPARKLE)
  await app.run.getState().connect()
})

afterEach(cleanup)

const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)))

function show(loop: boolean) {
  render(
    <AppProvider app={app}>
      <PlayOnServer path={SPARKLE} id="sparkle" loop={loop} />
    </AppProvider>,
  )
  return {
    play: screen.getByRole('button', { name: /Play on server/ }),
    stop: screen.getByRole('button', { name: /Stop/ }),
  }
}

describe('Play on server', () => {
  it('waits for the dev server', () => {
    const { play, stop } = show(false)
    expect((play as HTMLButtonElement).disabled).toBe(true)
    expect((stop as HTMLButtonElement).disabled).toBe(true)
  })

  it('saves a changed effect first, then plays it with the loop toggle, and stops', async () => {
    act(() => backend.testConnect())
    app.workspace.getState().edit<ParticleEffectFile>(SPARKLE, (draft) => {
      draft.duration = 30
    })
    const { play, stop } = show(true)
    expect((play as HTMLButtonElement).disabled).toBe(false)
    fireEvent.click(play)
    await settle()
    expect(app.workspace.getState().docs[SPARKLE]?.dirty).toBe(false)
    expect(
      backend.bridgeLog.filter(
        (it) => it.method !== 'profiler_subscribe' && it.method !== 'settings',
      ),
    ).toEqual([
      { method: 'reload', params: { paths: [SPARKLE] } },
      { method: 'play_particle_effect', params: { effect: 'sparkle', loop: true } },
    ])
    fireEvent.click(stop)
    await settle()
    expect(backend.bridgeLog.at(-1)).toEqual({ method: 'stop_particle_effects' })
  })

  it('plays a saved effect without saving it again', async () => {
    act(() => backend.testConnect())
    fireEvent.click(show(false).play)
    await settle()
    expect(
      backend.bridgeLog.filter(
        (it) => it.method !== 'profiler_subscribe' && it.method !== 'settings',
      ),
    ).toEqual([{ method: 'play_particle_effect', params: { effect: 'sparkle', loop: false } }])
  })
})
