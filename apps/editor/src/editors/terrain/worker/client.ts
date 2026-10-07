/**
 * The main thread's end of the terrain preview: a module worker of its own (Vite bundles it with its own
 * copy of format's JS build), asked for the newest picture only. While one is being drawn, a newer request waits
 * and any older waiting one is dropped, so dragging the map never queues up work.
 */
import { wrap, type Remote } from 'comlink'
import { useEffect, useRef, useState } from 'react'
import type { PreviewAnswer, PreviewCore, PreviewRequest } from './core'

export interface PreviewClient {
  draw(request: PreviewRequest): Promise<PreviewAnswer>
  /** Stops the worker; nothing more is answered. */
  dispose(): void
}

/** A worker of its own. */
export function terrainPreviewWorker(): PreviewClient {
  const worker = new Worker(new URL('./preview.worker.ts', import.meta.url), {
    type: 'module',
    name: 'terrain-preview',
  })
  const api: Remote<PreviewCore> = wrap<PreviewCore>(worker)
  // A worker that fails to start (or dies) answers nothing: every request fails instead of waiting forever.
  const failed = new Promise<never>((_, reject) => {
    worker.addEventListener('error', (event) =>
      reject(new Error(event.message || 'The terrain preview stopped')),
    )
  })
  failed.catch(() => {})
  return {
    draw: (request) => Promise.race([api.draw(request) as Promise<PreviewAnswer>, failed]),
    dispose: () => worker.terminate(),
  }
}

/** What [useTerrainPreview] has: the newest answer, and whether a newer request is being drawn. */
export interface PreviewState {
  answer: PreviewAnswer | null
  /** The request [answer] is for. */
  drawn: PreviewRequest | null
  drawing: boolean
  /** Why the worker gave no answer, when it failed. */
  error: string | null
}

const IDLE: PreviewState = { answer: null, drawn: null, drawing: false, error: null }

/**
 * Asks a worker for the newest request only: one is drawn at a time, and while it is, a newer one waits in the
 * place of any that was waiting. A worker that fails is started over for the next request.
 */
export class LatestPreview {
  private client: PreviewClient
  private busy = false
  private waiting: PreviewRequest | null = null
  private state: PreviewState = IDLE
  private closed = false

  constructor(
    private readonly connect: () => PreviewClient,
    private readonly changed: (state: PreviewState) => void,
  ) {
    this.client = connect()
  }

  request(request: PreviewRequest | null): void {
    this.waiting = request
    this.pump()
  }

  close(): void {
    this.closed = true
    this.client.dispose()
  }

  private set(state: PreviewState): void {
    this.state = state
    if (!this.closed) this.changed(state)
  }

  private pump(): void {
    const next = this.waiting
    if (this.busy || this.closed || !next) return
    this.waiting = null
    this.busy = true
    if (!this.state.drawing) this.set({ ...this.state, drawing: true })
    const client = this.client
    client
      .draw(next)
      .then(
        (answer) => this.set({ answer, drawn: next, drawing: this.waiting !== null, error: null }),
        (error: unknown) => {
          if (this.closed) return
          client.dispose()
          this.client = this.connect()
          this.set({
            ...this.state,
            drawing: this.waiting !== null,
            error: error instanceof Error ? error.message : String(error),
          })
        },
      )
      .finally(() => {
        this.busy = false
        this.pump()
      })
  }
}

/**
 * The preview of [request] (null for none), drawn by a worker this component owns ([connect] makes it: tests pass
 * one that answers in the same thread).
 */
export function useTerrainPreview(
  request: PreviewRequest | null,
  connect: () => PreviewClient = terrainPreviewWorker,
): PreviewState {
  const [state, setState] = useState<PreviewState>(IDLE)
  const queue = useRef<LatestPreview | null>(null)
  const latest = useRef(request)
  // Made first (effects run in order), so the request below always has one to go to.
  useEffect(() => {
    const made = new LatestPreview(connect, setState)
    queue.current = made
    made.request(latest.current)
    return () => {
      made.close()
      if (queue.current === made) queue.current = null
    }
  }, [connect])
  useEffect(() => {
    latest.current = request
    queue.current?.request(request)
  }, [request])
  return state
}
