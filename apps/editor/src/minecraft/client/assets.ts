/**
 * The cached client assets for one Minecraft version, read through the
 * backend's asset protocol (`Backend.assetUrl`). Every JSON fetch is cached
 * for the session: the same few parents (`block/cube_all`, `item/generated`)
 * serve most of the registry.
 */
import type { Backend } from '@/core/backend/types'
import { textureAssetPath, type AssetLoader } from './model'

/** `index.json`, written by the import: ids for pickers, so nothing lists folders. */
export interface ClientIndex {
  minecraft: string
  blockstates: string[]
  items: string[]
  fonts: string[]
}

export interface ClientAssets extends AssetLoader {
  version: string
  textureUrl(textureId: string): string
}

const loaders = new Map<string, ClientAssets>()

export function clientAssets(backend: Backend, version: string): ClientAssets {
  const existing = loaders.get(version)
  if (existing) return existing
  const cache = new Map<string, Promise<unknown>>()
  const assets: ClientAssets = {
    version,
    json<T>(path: string): Promise<T | null> {
      let pending = cache.get(path)
      if (!pending) {
        pending = fetch(backend.assetUrl(version, path))
          .then((response) => (response.ok ? response.json() : null))
          .catch(() => null)
        cache.set(path, pending)
      }
      return pending as Promise<T | null>
    },
    textureUrl(textureId: string) {
      return backend.assetUrl(version, textureAssetPath(textureId))
    },
  }
  loaders.set(version, assets)
  return assets
}

/** Forgets cached assets, after an import replaced them. */
export function forgetClientAssets(version: string) {
  loaders.delete(version)
}
