import { describe, expect, it } from 'vitest'
import type { PreviewAnswer, PreviewRequest } from './core'
import { LatestPreview, type PreviewClient, type PreviewState } from './client'

/** A worker whose answers the test gives, one request at a time. */
function fakeWorkers() {
  const asked: string[] = []
  const pending: { resolve: (a: PreviewAnswer) => void; reject: (e: Error) => void }[] = []
  let made = 0
  let disposed = 0
  const connect = (): PreviewClient => {
    made += 1
    return {
      draw: (request) =>
        new Promise<PreviewAnswer>((resolve, reject) => {
          asked.push(request.seed)
          pending.push({ resolve, reject })
        }),
      dispose: () => {
        disposed += 1
      },
    }
  }
  return { asked, pending, connect, made: () => made, disposed: () => disposed }
}

const request = (seed: string) => ({ seed }) as PreviewRequest
const answer = (seed: string) =>
  ({ map: { type: 'failed', problems: [] }, slice: { seed } }) as unknown as PreviewAnswer
const tick = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('the preview asks its worker for the newest picture only', () => {
  it('draws one at a time, dropping requests a newer one overtook', async () => {
    const workers = fakeWorkers()
    const states: PreviewState[] = []
    const queue = new LatestPreview(workers.connect, (state) => states.push(state))
    queue.request(request('1'))
    queue.request(request('2'))
    queue.request(request('3'))
    expect(workers.asked).toEqual(['1'])
    workers.pending[0]!.resolve(answer('1'))
    await tick()
    // 2 was overtaken by 3 while 1 was drawn.
    expect(workers.asked).toEqual(['1', '3'])
    expect(states.at(-1)?.drawing).toBe(true)
    workers.pending[1]!.resolve(answer('3'))
    await tick()
    expect(states.at(-1)?.drawn?.seed).toBe('3')
    expect(states.at(-1)?.drawing).toBe(false)
  })

  it('starts a worker that failed over, and says why', async () => {
    const workers = fakeWorkers()
    const states: PreviewState[] = []
    const queue = new LatestPreview(workers.connect, (state) => states.push(state))
    queue.request(request('1'))
    workers.pending[0]!.reject(new Error('it stopped'))
    await tick()
    expect(states.at(-1)?.error).toBe('it stopped')
    expect([workers.made(), workers.disposed()]).toEqual([2, 1])
    queue.request(request('2'))
    workers.pending[1]!.resolve(answer('2'))
    await tick()
    expect(states.at(-1)?.error).toBeNull()
    queue.close()
    expect(workers.disposed()).toBe(2)
  })
})
