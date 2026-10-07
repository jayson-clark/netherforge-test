import { clientAssets, type ClientAssets } from './assets'
import { useApp, useWorkspace } from '@/state/providers'

/** The imported client assets for the project's version, or null before an import. */
export function useClientAssets(): ClientAssets | null {
  const { backend } = useApp()
  const minecraft = useWorkspace((s) => s.minecraft)
  const client = useWorkspace((s) => s.cache?.client ?? false)
  return client && minecraft ? clientAssets(backend, minecraft) : null
}
