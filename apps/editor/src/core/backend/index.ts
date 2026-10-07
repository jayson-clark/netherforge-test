import type { MemoryBackend } from './memory'
import { TauriBackend } from './tauri'
import type { Backend } from './types'

export type { Backend } from './types'

declare global {
  interface Window {
    /** Set by Tauri in the app's webview. */
    __TAURI_INTERNALS__?: unknown
    /** The memory backend, exposed so Playwright can play the outside world. */
    __netherforge?: { backend: MemoryBackend }
  }
  /**
   * Whether this is Playwright's build (`vite build --mode e2e`), which runs
   * on the seeded memory backend. Every other build, `pnpm dev` included,
   * runs on the Rust backend. Set in vite.config.ts.
   */
  const __MEMORY_BACKEND__: boolean
}

/** The backend for this page: the seeded memory backend in the e2e build, otherwise Tauri. */
export async function createBackend(): Promise<Backend> {
  if (__MEMORY_BACKEND__) {
    const { memoryBackendFor } = await import('./seeded')
    return memoryBackendFor(new URLSearchParams(window.location.search))
  }
  if (!window.__TAURI_INTERNALS__) {
    throw new Error('the editor runs in its desktop app: start it with pnpm dev')
  }
  return new TauriBackend().init()
}
