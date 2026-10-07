/**
 * The project's script tests as the editor keeps them: the last run's report, for the Tests panel.
 * A run is the backend's `runTests`: the `*_test.lua` files run on fake servers by the test runner
 * jar (`netherforge test`), each test on a fresh one; the editor only asks and shows (see the
 * testing skill for the runner, and editor-ui for the panel).
 */
import { createStore, type StoreApi } from 'zustand/vanilla'
import type { Backend, TestReport, TestResult } from '@/core/backend/types'

export interface TestsState {
  /** A run is going: the runner is working through the project's tests. */
  running: boolean
  /** The last run's report, null before the first and after `clear`. */
  report: TestReport | null
  /** What the last run was limited to ("file: name" contains it), if anything. */
  filter: string
  /** Why the last run couldn't happen at all (no Java, no runner, no game data cached, an untrusted project), when it couldn't. */
  error: string | null
}

export interface TestsActions {
  /** Runs the project's tests (those matching [filter]): the report replaces the last one. */
  run(filter?: string): Promise<void>
  clear(): void
}

export type Tests = TestsState & TestsActions
export type TestsStore = StoreApi<Tests>

const initial: TestsState = { running: false, report: null, filter: '', error: null }

export function createTests(backend: Backend): TestsStore {
  return createStore<Tests>((set, get) => ({
    ...initial,
    async run(filter) {
      if (get().running) return
      set({ running: true, filter: filter ?? '', error: null })
      try {
        const report = await backend.runTests(filter)
        set({ running: false, report })
      } catch (error) {
        set({
          running: false,
          report: null,
          error: error instanceof Error ? error.message : String(error),
        })
      }
    },
    clear: () => set(initial),
  }))
}

/** The tests that didn't pass, failures and errors. */
export const unpassed = (report: TestReport | null): TestResult[] =>
  report?.results.filter((it) => it.status !== 'passed') ?? []

/** The report's results grouped by test file, in the order the runner reported them. */
export function byFile(report: TestReport): { file: string; results: TestResult[] }[] {
  const files = new Map<string, TestResult[]>()
  for (const result of report.results) {
    const list = files.get(result.file) ?? []
    list.push(result)
    files.set(result.file, list)
  }
  return [...files].map(([file, results]) => ({ file, results }))
}
