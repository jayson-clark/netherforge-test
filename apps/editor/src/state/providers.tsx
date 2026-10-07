/**
 * Wires the backend and the stores together and hands them to React.
 */
import { createContext, useContext, type ReactNode } from 'react'
import { useStore } from 'zustand'
import type { Backend } from '@/core/backend/types'
import {
  browserStorage,
  createLayout,
  followProject,
  type LayoutState,
  type LayoutStore,
} from '@/core/store/layout'
import { createDebug, type Debug, type DebugStore } from '@/core/store/debug'
import {
  createResourcePacks,
  type ResourcePacks,
  type ResourcePacksStore,
} from '@/core/store/resourcePacks'
import { createProfiler, type Profiler, type ProfilerStore } from '@/core/store/profiler'
import { createRun, describeReload, type Run, type RunStore } from '@/core/store/run'
import { createTests, type Tests, type TestsStore } from '@/core/store/tests'
import { createUpdates, type Updates, type UpdatesStore } from '@/core/store/updates'
import { createWorkspace, type Workspace, type WorkspaceStore } from '@/core/store/workspace'
import { createZoom, type Zoom } from '@/core/zoom'
import { connectMcp } from '@/core/mcp/tools'
import { followLualsStubs } from '@/core/luals/follow'

export interface AppStores {
  backend: Backend
  workspace: WorkspaceStore
  run: RunStore
  profiler: ProfilerStore
  tests: TestsStore
  debug: DebugStore
  updates: UpdatesStore
  resourcePacks: ResourcePacksStore
  layout: LayoutStore
  zoom: Zoom
}

export function createApp(backend: Backend): AppStores {
  const workspace = createWorkspace(backend)
  const profiler = createProfiler(backend)
  // Attaches to the dev server's debug adapter whenever the bridge comes up (`connect`).
  const debug = createDebug(backend, workspace)
  const run = createRun(backend, {
    onBridgeUp: () => {
      // A dev server that just came up may have exported game data.
      void workspace.getState().refreshGameData()
      // The profiler is always on in dev: every dev server streams to the Profiler panel.
      void profiler.getState().subscribe()
    },
    beforeStop: () => debug.getState().release(),
  })
  workspace.getState().setRunHooks({
    bridgeConnected: () => run.getState().server.bridgeConnected,
    reloaded: (paths, result, error) => {
      const { level, text } = describeReload(paths, result, error)
      run.getState().append({ kind: 'editor', level, text })
      // What the server learns only as it starts (advancements, the main world's generator) needs a restart: do it, as a save always means to.
      if (result?.restart) void run.getState().restart()
    },
    // A paused server refuses reloads: they wait for it to run again.
    holdReload: (paths) => debug.getState().holdReload(paths),
  })
  const resourcePacks = createResourcePacks(workspace, backend)
  // The project's names for lua-language-server, in .netherforge/luals/.
  followLualsStubs(workspace, backend)
  // The MCP server for coding agents, answering from these stores (see core/mcp/tools.ts).
  void connectMcp({ backend, workspace, run })
  // The workbench's layout, remembered per project.
  const layout = createLayout()
  followProject(layout, workspace)
  const zoom = createZoom(backend, browserStorage)
  const tests = createTests(backend)
  return {
    backend,
    workspace,
    run,
    profiler,
    tests,
    debug,
    updates: createUpdates(backend),
    resourcePacks,
    layout,
    zoom,
  }
}

const AppContext = createContext<AppStores | null>(null)

export function AppProvider({ app, children }: { app: AppStores; children: ReactNode }) {
  return <AppContext.Provider value={app}>{children}</AppContext.Provider>
}

export function useApp(): AppStores {
  const app = useContext(AppContext)
  if (!app) throw new Error('useApp outside AppProvider')
  return app
}

export function useWorkspace<T>(selector: (state: Workspace) => T): T {
  return useStore(useApp().workspace, selector)
}

export function useRun<T>(selector: (state: Run) => T): T {
  return useStore(useApp().run, selector)
}

export function useProfiler<T>(selector: (state: Profiler) => T): T {
  return useStore(useApp().profiler, selector)
}

export function useTests<T>(selector: (state: Tests) => T): T {
  return useStore(useApp().tests, selector)
}

export function useDebug<T>(selector: (state: Debug) => T): T {
  return useStore(useApp().debug, selector)
}

export function useUpdates<T>(selector: (state: Updates) => T): T {
  return useStore(useApp().updates, selector)
}

export function useResourcePacks<T>(selector: (state: ResourcePacks) => T): T {
  return useStore(useApp().resourcePacks, selector)
}

export function useLayout<T>(selector: (state: LayoutState) => T): T {
  return useStore(useApp().layout, selector)
}

export const useBackend = () => useApp().backend
