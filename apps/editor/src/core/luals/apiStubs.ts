/** The API's generated LuaLS stubs and gates, bundled as lazy chunks (a Vite build: `api.ts` stays plain TypeScript for Node). */
import type { ApiGate, ApiStubs } from './api'

type Loader = () => Promise<string>

const nfLua = import.meta.glob('/node_modules/@netherforge/api/generated/luals/nf.lua', {
  query: '?raw',
  import: 'default',
}) as Record<string, Loader>

// A terrain script's API (terrain/<id>.lua): a file of its own, with no `nf` in it, since the script runs
// apart from the server. Its classes are locals, so nothing of it is a global anywhere else.
const terrainLua = import.meta.glob('/node_modules/@netherforge/api/generated/luals/terrain.lua', {
  query: '?raw',
  import: 'default',
}) as Record<string, Loader>

const surfaces = import.meta.glob('/node_modules/@netherforge/api/generated/luals/surfaces/*.lua', {
  query: '?raw',
  import: 'default',
}) as Record<string, Loader>

const gates = import.meta.glob('/node_modules/@netherforge/api/generated/luals/gates.json', {
  query: '?raw',
  import: 'default',
}) as Record<string, Loader>

let loaded: Promise<ApiStubs> | null = null

/** The API's stubs, loaded once (they're a lazy chunk: nothing needs them until a project is open). */
export function loadApiStubs(): Promise<ApiStubs> {
  loaded ??= (async () => {
    const files: Record<string, string> = {}
    for (const load of Object.values(nfLua)) files['nf.lua'] = await load()
    for (const load of Object.values(terrainLua)) files['terrain.lua'] = await load()
    for (const [key, load] of Object.entries(surfaces))
      files[`surfaces/${key.slice(key.lastIndexOf('/') + 1)}`] = await load()
    const [text] = await Promise.all(Object.values(gates).map((load) => load()))
    return { files, gates: text ? (JSON.parse(text) as ApiGate[]) : [] }
  })()
  return loaded
}
